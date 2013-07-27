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

import org.apache.commons.lang.StringUtils;
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

/**
 * This interface describes an instance of a connection between a repository and ManifoldCF's
 * standard "pull" ingestion agent.
 * <p/>
 * Each instance of this interface is used in only one thread at a time.  Connection Pooling
 * on these kinds of objects is performed by the factory which instantiates repository connectors
 * from symbolic names and config parameters, and is pooled by these parameters.  That is, a pooled connector
 * handle is used only if all the connection parameters for the handle match.
 * <p/>
 * Implementers of this interface should provide a default constructor which has this signature:
 * <p/>
 * xxx();
 * <p/>
 * Connectors are either configured or not.  If configured, they will persist in a pool, and be
 * reused multiple times.  Certain methods of a connector may be called before the connector is
 * configured.  This includes basically all methods that permit inspection of the connector's
 * capabilities.  The complete list is:
 * <p/>
 * <p/>
 * The purpose of the repository connector is to allow documents to be fetched from the repository.
 * <p/>
 * Each repository connector describes a set of documents that are known only to that connector.
 * It therefore establishes a space of document identifiers.  Each connector will only ever be
 * asked to deal with identifiers that have in some way originated from the connector.
 * <p/>
 * Documents are fetched in three stages.  First, the getDocuments() method is called in the connector
 * implementation.  This returns a set of document identifiers.  The document identifiers are used to
 * obtain the current document version strings in the second stage, using the getDocumentVersions() method.
 * The last stage is processDocuments(), which queues up any additional documents needed, and also ingests.
 * This method will not be called if the document version seems to indicate that no document change took
 * place.
 */

public class EmailConnector extends org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector {


    /**
     * Session expiration time interval
     */
    protected final static long SESSION_EXPIRATION_MILLISECONDS = 300000L;


    // Local variables.

    /**
     * The email server
     */
    protected String hostServer = null;


    /**
     * Output the configuration header section.
     * This method is called in the head section of the connector's configuration page.  Its purpose is to
     * add the required tabs to the list, and to output any javascript methods that might be needed by
     * the configuration editing HTML.
     * The connector does not need to be connected for this method to be called.
     *
     * @param threadContext is the local thread context.
     * @param out           is the output to which any HTML should be sent.
     * @param locale        is the desired locale.
     * @param parameters    are the configuration parameters, as they currently exist, for this connection being configured.
     * @param tabsArray     is an array of tab names.  Add to this array any tab names that are specific to the connector.
     */
    @Override
    public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
                                          Locale locale, ConfigParams parameters, List<String> tabsArray)
            throws ManifoldCFException, IOException {
        tabsArray.add("Server");
        // Map the parameters
        Map<String,Object> paramMap = new HashMap<String,Object>();

        // Fill in the parameters from each tab
        fillInServerConfigurationMap(paramMap, parameters);

        // Output the Javascript - only one Velocity template for all tabs
        outputResource("configurationHeader.js", out, locale, paramMap);
    }

    @Override
    public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
                                        Locale locale, ConfigParams parameters, String tabName)
            throws ManifoldCFException, IOException {
        // Output the Server tab
        Map<String, Object> paramMap = new HashMap<String, Object>();
        // Set the tab name
        paramMap.put("TabName", tabName);
        // Fill in the parameters
        fillInServerConfigurationMap(paramMap, parameters);
        Messages.outputResourceWithVelocity(out, locale, "ConfigurationServer.html", paramMap);

    }

    private void fillInServerConfigurationMap(Map<String, Object> paramMap, ConfigParams parameters) {
        int i = 0;
        String username = parameters.getParameter(EmailConfig.USERNAME_PARAM);
        String password = parameters.getParameter(EmailConfig.PASSWORD_PARAM);
        String protocol = parameters.getParameter(EmailConfig.PROTOCOL_PARAM);
        String server = parameters.getParameter(EmailConfig.SERVER_PARAM);
        String port = parameters.getParameter(EmailConfig.PORT_PARAM);
        List<Map<String,String>> list = new ArrayList<Map<String,String>>();
        while (i < parameters.getChildCount())     //In post property set is added as a configuration node
        {
            ConfigNode cn = parameters.getChild(i++);
            if (cn.getType().equals(EmailConfig.NODE_PROPERTIES)) {
                String findParameterName = cn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                String findParameterValue = cn.getAttributeValue(EmailConfig.ATTRIBUTE_VALUE);
                Map<String,String> row = new HashMap<String,String>();
                row.put("name",findParameterName);
                row.put("value",findParameterValue);
                list.add(row);
            }
        }

        if (username == null)
            username = StringUtils.EMPTY;
        if (password == null)
            password = StringUtils.EMPTY;
        if (protocol == null)
            protocol = EmailConfig.PROTOCOL_DEFAULT_VALUE;
        if (server == null)
            server = StringUtils.EMPTY;
        if (port == null)
            port = EmailConfig.PORT_DEFAULT_VALUE;

        paramMap.put(EmailConfig.USERNAME_PARAM, username);
        paramMap.put(EmailConfig.PASSWORD_PARAM, password);
        paramMap.put(EmailConfig.PROTOCOL_PARAM, protocol);
        paramMap.put(EmailConfig.SERVER_PARAM, server);
        paramMap.put(EmailConfig.PORT_PARAM, port);
        paramMap.put(EmailConfig.PROPERTIES_PARAM, list);

    }

    /** Process a configuration post.
     * This method is called at the start of the connector's configuration page, whenever there is a possibility
     * that form data for a connection has been posted.  Its purpose is to gather form information and modify
     * the configuration parameters accordingly.
     * The name of the posted form is always "editconnection".
     * The connector does not need to be connected for this method to be called.
     *@param threadContext is the local thread context.
     *@param variableContext is the set of variables available from the post, including binary file post information.
     *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
     *@return null if all is well, or a string error message if there is an error that should prevent saving of the
     *   connection (and cause a redirection to an error page).
     */
    @Override
    public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
                                           ConfigParams parameters)
            throws ManifoldCFException
    {
        String userName = variableContext.getParameter(EmailConfig.USERNAME_PARAM);
        if (userName != null)
            parameters.setParameter(EmailConfig.USERNAME_PARAM, userName);
        String password = variableContext.getParameter(EmailConfig.PASSWORD_PARAM);
        if (password != null)
            parameters.setParameter(EmailConfig.PASSWORD_PARAM, password);
        String protocol = variableContext.getParameter(EmailConfig.PROTOCOL_PARAM);
        if (protocol != null)
            parameters.setParameter(EmailConfig.PROTOCOL_PARAM,protocol);
        String server = variableContext.getParameter(EmailConfig.SERVER_PARAM);
        if (server != null)
            parameters.setParameter(EmailConfig.SERVER_PARAM,server);
        String port = variableContext.getParameter(EmailConfig.PORT_PARAM);
        if (port != null)
            parameters.setParameter(EmailConfig.PORT_PARAM,port);
        // Remove old find parameter document specification information
        removeNodes(parameters,EmailConfig.NODE_PROPERTIES);

        // Parse the number of records that were posted
        String findCountString = variableContext.getParameter("findcount");
        if (findCountString != null)
        {
            int findCount = Integer.parseInt(findCountString);

            // Loop throught them and add new server properties
            int i = 0;
            while (i < findCount)
            {
                String suffix = "_"+Integer.toString(i++);
                // Only add the name/value if the item was not deleted.
                String findParameterOp = variableContext.getParameter("findop"+suffix);
                if (findParameterOp == null || !findParameterOp.equals("Delete"))
                {
                    String findParameterName = variableContext.getParameter("findname"+suffix);
                    String findParameterValue = variableContext.getParameter("findvalue"+suffix);
                    addFindParameterNode(parameters,findParameterName,findParameterValue);
                }
            }
        }

        // Now, look for a global "Add" operation
        String operation = variableContext.getParameter("findop");
        if (operation != null && operation.equals("Add"))
        {
            // Pick up the global parameter name and value
            String findParameterName = variableContext.getParameter("findname");
            String findParameterValue = variableContext.getParameter("findvalue");
            addFindParameterNode(parameters,findParameterName,findParameterValue);
        }

        return null;
    }

    private void addFindParameterNode(ConfigParams parameters, String findParameterName, String findParameterValue) {
        ConfigNode cn = new ConfigNode(EmailConfig.NODE_PROPERTIES);
        cn.setAttribute(EmailConfig.ATTRIBUTE_NAME,findParameterName);
        cn.setAttribute(EmailConfig.ATTRIBUTE_VALUE,findParameterValue);
        // Add to the end
        parameters.addChild(parameters.getChildCount(),cn);
    }

    protected static void removeNodes(ConfigParams parameters,
                                      String nodeTypeName)
    {
        int i = 0;
        while (i < parameters.getChildCount())
        {
            ConfigNode cn = parameters.getChild(i);
            if (cn.getType().equals(nodeTypeName))
                parameters.removeChild(i);
            else
                i++;
        }
    }

    /**
     * View configuration. This method is called in the body section of the
     * connector's view configuration page. Its purpose is to present the
     * connection information to the user. The coder can presume that the HTML that
     * is output from this configuration will be within appropriate <html> and
     * <body> tags.
     *
     * @param threadContext
     *          is the local thread context.
     * @param out
     *          is the output to which any HTML should be sent.
     * @param parameters
     *          are the configuration parameters, as they currently exist, for
     *          this connection being configured.
     */
    @Override
    public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
                                  Locale locale, ConfigParams parameters) throws ManifoldCFException, IOException {
        Map<String,Object> paramMap = new HashMap<String,Object>();

        // Fill in map from each tab
        fillInServerConfigurationMap(paramMap, parameters);

        outputResource("viewConfiguration.html", out, locale, paramMap);
    }

    /**
     * Read the content of a resource, replace the variable ${PARAMNAME} with the
     * value and copy it to the out.
     *
     * @param resName
     * @param out
     * @throws ManifoldCFException
     */
    private static void outputResource(String resName, IHTTPOutput out,
                                       Locale locale, Map<String,Object> paramMap) throws ManifoldCFException {
        Messages.outputResourceWithVelocity(out,locale,resName,paramMap);
    }
}
