/* $Id: JDBCConnector.java 988245 2010-08-23 18:39:35Z kwright $ */

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.manifoldcf.crawler.connectors.email;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.crawler.interfaces.*;
import org.apache.manifoldcf.crawler.system.Logging;
import org.apache.manifoldcf.core.database.*;

import java.sql.*;
import javax.naming.*;
import javax.sql.*;

import java.io.*;
import java.util.*;

/** This interface describes an instance of a connection between a repository and ManifoldCF's
 * standard "pull" ingestion agent.
 *
 * Each instance of this interface is used in only one thread at a time.  Connection Pooling
 * on these kinds of objects is performed by the factory which instantiates repository connectors
 * from symbolic names and config parameters, and is pooled by these parameters.  That is, a pooled connector
 * handle is used only if all the connection parameters for the handle match.
 *
 * Implementers of this interface should provide a default constructor which has this signature:
 *
 * xxx();
 *
 * Connectors are either configured or not.  If configured, they will persist in a pool, and be
 * reused multiple times.  Certain methods of a connector may be called before the connector is
 * configured.  This includes basically all methods that permit inspection of the connector's
 * capabilities.  The complete list is:
 *
 *
 * The purpose of the repository connector is to allow documents to be fetched from the repository.
 *
 * Each repository connector describes a set of documents that are known only to that connector.
 * It therefore establishes a space of document identifiers.  Each connector will only ever be
 * asked to deal with identifiers that have in some way originated from the connector.
 *
 * Documents are fetched in three stages.  First, the getDocuments() method is called in the connector
 * implementation.  This returns a set of document identifiers.  The document identifiers are used to
 * obtain the current document version strings in the second stage, using the getDocumentVersions() method.
 * The last stage is processDocuments(), which queues up any additional documents needed, and also ingests.
 * This method will not be called if the document version seems to indicate that no document change took
 * place.
 */

public class EmailConnector extends org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector{


    /** Session expiration time interval */
    protected final static long SESSION_EXPIRATION_MILLISECONDS = 300000L;
    private static final String PARAMETER_HOST_EMAIL_SERVER = "hostserver";

    // Local variables.

    /** The email server */
    protected String hostServer = null;




    /** Output the configuration header section.
     * This method is called in the head section of the connector's configuration page.  Its purpose is to
     * add the required tabs to the list, and to output any javascript methods that might be needed by
     * the configuration editing HTML.
     * The connector does not need to be connected for this method to be called.
     *@param threadContext is the local thread context.
     *@param out is the output to which any HTML should be sent.
     *@param locale is the desired locale.
     *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
     *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
     */
    @Override
    public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
                                          Locale locale, ConfigParams parameters, List<String> tabsArray)
            throws ManifoldCFException, IOException
    {
        tabsArray.add("Server");
        Messages.outputResourceWithVelocity(out,locale,"ConfigurationHeader.html",null);
    }

    @Override
    public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
                                        Locale locale, ConfigParams parameters, String tabName)
            throws ManifoldCFException, IOException
    {
        // Output the Server tab
        Map<String,Object> velocityContext = new HashMap<String,Object>();
        velocityContext.put("TabName",tabName);
        String emailServer = parameters.getParameter(PARAMETER_HOST_EMAIL_SERVER);
        if (emailServer == null)
            emailServer = "";
        velocityContext.put("hostserverurl", emailServer);
        Messages.outputResourceWithVelocity(out,locale,"ConfigurationServer.html",velocityContext);
    }
}
