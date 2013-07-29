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
import org.apache.manifoldcf.crawler.interfaces.*;

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


    ///////////////////////////////////////Start of Configuration UI/////////////////////////////////////

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
        Map<String, Object> paramMap = new HashMap<String, Object>();

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
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        while (i < parameters.getChildCount())     //In post property set is added as a configuration node
        {
            ConfigNode cn = parameters.getChild(i++);
            if (cn.getType().equals(EmailConfig.NODE_PROPERTIES)) {
                String findParameterName = cn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                String findParameterValue = cn.getAttributeValue(EmailConfig.ATTRIBUTE_VALUE);
                Map<String, String> row = new HashMap<String, String>();
                row.put("name", findParameterName);
                row.put("value", findParameterValue);
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

    /**
     * Process a configuration post.
     * This method is called at the start of the connector's configuration page, whenever there is a possibility
     * that form data for a connection has been posted.  Its purpose is to gather form information and modify
     * the configuration parameters accordingly.
     * The name of the posted form is always "editconnection".
     * The connector does not need to be connected for this method to be called.
     *
     * @param threadContext   is the local thread context.
     * @param variableContext is the set of variables available from the post, including binary file post information.
     * @param parameters      are the configuration parameters, as they currently exist, for this connection being configured.
     * @return null if all is well, or a string error message if there is an error that should prevent saving of the
     *         connection (and cause a redirection to an error page).
     */
    @Override
    public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
                                           ConfigParams parameters)
            throws ManifoldCFException {
        String userName = variableContext.getParameter(EmailConfig.USERNAME_PARAM);
        if (userName != null)
            parameters.setParameter(EmailConfig.USERNAME_PARAM, userName);
        String password = variableContext.getParameter(EmailConfig.PASSWORD_PARAM);
        if (password != null)
            parameters.setParameter(EmailConfig.PASSWORD_PARAM, password);
        String protocol = variableContext.getParameter(EmailConfig.PROTOCOL_PARAM);
        if (protocol != null)
            parameters.setParameter(EmailConfig.PROTOCOL_PARAM, protocol);
        String server = variableContext.getParameter(EmailConfig.SERVER_PARAM);
        if (server != null)
            parameters.setParameter(EmailConfig.SERVER_PARAM, server);
        String port = variableContext.getParameter(EmailConfig.PORT_PARAM);
        if (port != null)
            parameters.setParameter(EmailConfig.PORT_PARAM, port);
        // Remove old find parameter document specification information
        removeNodes(parameters, EmailConfig.NODE_PROPERTIES);

        // Parse the number of records that were posted
        String findCountString = variableContext.getParameter("findcount");
        if (findCountString != null) {
            int findCount = Integer.parseInt(findCountString);

            // Loop throught them and add new server properties
            int i = 0;
            while (i < findCount) {
                String suffix = "_" + Integer.toString(i++);
                // Only add the name/value if the item was not deleted.
                String findParameterOp = variableContext.getParameter("findop" + suffix);
                if (findParameterOp == null || !findParameterOp.equals("Delete")) {
                    String findParameterName = variableContext.getParameter("findname" + suffix);
                    String findParameterValue = variableContext.getParameter("findvalue" + suffix);
                    addFindParameterNode(parameters, findParameterName, findParameterValue);
                }
            }
        }

        // Now, look for a global "Add" operation
        String operation = variableContext.getParameter("findop");
        if (operation != null && operation.equals("Add")) {
            // Pick up the global parameter name and value
            String findParameterName = variableContext.getParameter("findname");
            String findParameterValue = variableContext.getParameter("findvalue");
            addFindParameterNode(parameters, findParameterName, findParameterValue);
        }

        return null;
    }

    private void addFindParameterNode(ConfigParams parameters, String findParameterName, String findParameterValue) {
        ConfigNode cn = new ConfigNode(EmailConfig.NODE_PROPERTIES);
        cn.setAttribute(EmailConfig.ATTRIBUTE_NAME, findParameterName);
        cn.setAttribute(EmailConfig.ATTRIBUTE_VALUE, findParameterValue);
        // Add to the end
        parameters.addChild(parameters.getChildCount(), cn);
    }

    protected static void removeNodes(ConfigParams parameters,
                                      String nodeTypeName) {
        int i = 0;
        while (i < parameters.getChildCount()) {
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
     * @param threadContext is the local thread context.
     * @param out           is the output to which any HTML should be sent.
     * @param parameters    are the configuration parameters, as they currently exist, for
     *                      this connection being configured.
     */
    @Override
    public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
                                  Locale locale, ConfigParams parameters) throws ManifoldCFException, IOException {
        Map<String, Object> paramMap = new HashMap<String, Object>();

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
                                       Locale locale, Map<String, Object> paramMap) throws ManifoldCFException {
        Messages.outputResourceWithVelocity(out, locale, resName, paramMap);
    }

    /////////////////////////////////End of configuration UI////////////////////////////////////////////////////


    /////////////////////////////////Start of Specification UI//////////////////////////////////////////////////

    /**
     * Output the specification header section.
     * This method is called in the head section of a job page which has selected a repository connection of the
     * current type.  Its purpose is to add the required tabs to the list, and to output any javascript methods
     * that might be needed by the job editing HTML.
     * The connector will be connected before this method can be called.
     *
     * @param out       is the output to which any HTML should be sent.
     * @param locale    is the desired locale.
     * @param ds        is the current document specification for this job.
     * @param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
     */
    @Override
    public void outputSpecificationHeader(IHTTPOutput out, Locale locale,
                                          DocumentSpecification ds, List<String> tabsArray)
            throws ManifoldCFException, IOException {
        // Add the tabs
        tabsArray.add("Metadata");
        tabsArray.add("Filter");
        outputResource("SpecificationHeader.js", out, locale, null);
    }

    /**
     * Output the specification body section.
     * This method is called in the body section of a job page which has selected a repository connection of the
     * current type.  Its purpose is to present the required form elements for editing.
     * The coder can presume that the HTML that is output from this configuration will be within appropriate
     * <html>, <body>, and <form> tags.  The name of the form is always "editjob".
     * The connector will be connected before this method can be called.
     *
     * @param out     is the output to which any HTML should be sent.
     * @param locale  is the desired locale.
     * @param ds      is the current document specification for this job.
     * @param tabName is the current tab name.
     */
    @Override
    public void outputSpecificationBody(IHTTPOutput out, Locale locale,
                                        DocumentSpecification ds, String tabName)
            throws ManifoldCFException, IOException {
        outputFilterTab(out, locale, ds, tabName);
        outputMetadataTab(out, locale, ds, tabName);

    }

    /**
     * Take care of "Metadata" tab.
     */
    protected void outputMetadataTab(IHTTPOutput out, Locale locale,
                                     DocumentSpecification ds, String tabName)
            throws ManifoldCFException, IOException {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put("TabName", tabName);
        fillInMetadataTab(paramMap, ds);
        fillInMetadataAttributes(paramMap);
        outputResource("SpecificationMetadata.html", out, locale, paramMap);
    }

    /**
     * Fill in Velocity context for Metadata tab.
     */
    protected static void fillInMetadataTab(Map<String, Object> paramMap,
                                            DocumentSpecification ds) {
        Set<String> metadataSelections = new HashSet<String>();
        int i = 0;
        while (i < ds.getChildCount()) {
            SpecificationNode sn = ds.getChild(i++);
            if (sn.getType().equals(EmailConfig.NODE_METADATA)) {
                String metadataName = sn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                metadataSelections.add(metadataName);
            }
        }
        paramMap.put("metadataselections", metadataSelections);
    }

    /**
     * Fill in Velocity context with data to permit attribute selection.
     */
    protected void fillInMetadataAttributes(Map<String, Object> paramMap) {
        String[] matchNames = EmailConfig.BASIC_METADATA;
        paramMap.put("metadataattributes", matchNames);
    }

    protected void outputFilterTab(IHTTPOutput out, Locale locale,
                                   DocumentSpecification ds, String tabName)
            throws ManifoldCFException, IOException {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put("TabName", tabName);
        fillInFilterTab(paramMap, ds);
        fillInSearchableAttributes(paramMap);
        outputResource("SpecificationFilter.html", out, locale, paramMap);
    }

    private void fillInSearchableAttributes(Map<String, Object> paramMap) {
        String[] attributes = EmailConfig.BASIC_SEARCHABLE_ATTRIBUTES;
        paramMap.put("searchableattributes", attributes);
    }

    protected static void fillInFilterTab(Map<String, Object> paramMap,
                                          DocumentSpecification ds) {
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        int i = 0;
        while (i < ds.getChildCount()) {
            SpecificationNode sn = ds.getChild(i++);
            if (sn.getType().equals(EmailConfig.NODE_FILTER)) {

                String findParameterName = sn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                String findParameterValue = sn.getAttributeValue(EmailConfig.ATTRIBUTE_VALUE);
                Map<String, String> row = new HashMap<String, String>();
                row.put("name", findParameterName);
                row.put("value", findParameterValue);
                list.add(row);
            }
        }
        paramMap.put("matches", list);
    }

    /**
     * Process a specification post.
     * This method is called at the start of job's edit or view page, whenever there is a possibility that form
     * data for a connection has been posted.  Its purpose is to gather form information and modify the
     * document specification accordingly.  The name of the posted form is always "editjob".
     * The connector will be connected before this method can be called.
     *
     * @param variableContext contains the post data, including binary file-upload information.
     * @param ds              is the current document specification for this job.
     * @return null if all is well, or a string error message if there is an error that should prevent saving of
     *         the job (and cause a redirection to an error page).
     */
    @Override
    public String processSpecificationPost(IPostParameters variableContext, DocumentSpecification ds)
            throws ManifoldCFException {

        String result = processFilterTab(variableContext, ds);
        if (result != null)
            return result;
        result = processMetadataTab(variableContext, ds);
        return result;
    }


    protected String processFilterTab(IPostParameters variableContext, DocumentSpecification ds)
            throws ManifoldCFException {
        // Remove old find parameter document specification information
        removeNodes(ds, EmailConfig.NODE_FILTER);

        String findCountString = variableContext.getParameter("findcount");
        if (findCountString != null) {
            int findCount = Integer.parseInt(findCountString);

            int i = 0;
            while (i < findCount) {
                String suffix = "_" + Integer.toString(i++);
                // Only add the name/value if the item was not deleted.
                String findParameterOp = variableContext.getParameter("findop" + suffix);
                if (findParameterOp == null || !findParameterOp.equals("Delete")) {
                    String findParameterName = variableContext.getParameter("findname" + suffix);
                    String findParameterValue = variableContext.getParameter("findvalue" + suffix);
                    addFindParameterNode(ds, findParameterName, findParameterValue);
                }
            }
        }

        String operation = variableContext.getParameter("findop");
        if (operation != null && operation.equals("Add")) {
            String findParameterName = variableContext.getParameter("findname");
            String findParameterValue = variableContext.getParameter("findvalue");
            addFindParameterNode(ds, findParameterName, findParameterValue);
        }

        return null;
    }


    protected String processMetadataTab(IPostParameters variableContext, DocumentSpecification ds)
            throws ManifoldCFException {
        // Remove old included metadata nodes
        removeNodes(ds, EmailConfig.NODE_METADATA);

        // Get the posted metadata values
        String[] metadataNames = variableContext.getParameterValues("metadata");
        if (metadataNames != null) {
            // Add each metadata name as a node to the document specification
            int i = 0;
            while (i < metadataNames.length) {
                String metadataName = metadataNames[i++];
                addIncludedMetadataNode(ds, metadataName);
            }
        }

        return null;
    }

    protected static void removeNodes(DocumentSpecification ds,
                                      String nodeTypeName) {
        int i = 0;
        while (i < ds.getChildCount()) {
            SpecificationNode sn = ds.getChild(i);
            if (sn.getType().equals(nodeTypeName))
                ds.removeChild(i);
            else
                i++;
        }
    }

    protected static void addIncludedMetadataNode(DocumentSpecification ds,
                                                  String metadataName) {
        // Build the proper node
        SpecificationNode sn = new SpecificationNode(EmailConfig.NODE_METADATA);
        sn.setAttribute(EmailConfig.ATTRIBUTE_NAME, metadataName);
        // Add to the end
        ds.addChild(ds.getChildCount(), sn);
    }

    private void addFindParameterNode(DocumentSpecification ds, String findParameterName, String findParameterValue) {
        SpecificationNode sn = new SpecificationNode(EmailConfig.NODE_FILTER);
        sn.setAttribute(EmailConfig.ATTRIBUTE_NAME, findParameterName);
        sn.setAttribute(EmailConfig.ATTRIBUTE_VALUE, findParameterValue);
        // Add to the end
        ds.addChild(ds.getChildCount(), sn);
    }

    /**
     * View specification.
     * This method is called in the body section of a job's view page.  Its purpose is to present the document
     * specification information to the user.  The coder can presume that the HTML that is output from
     * this configuration will be within appropriate <html> and <body> tags.
     * The connector will be connected before this method can be called.
     *
     * @param out    is the output to which any HTML should be sent.
     * @param locale is the desired locale.
     * @param ds     is the current document specification for this job.
     */
    @Override
    public void viewSpecification(IHTTPOutput out, Locale locale, DocumentSpecification ds)
            throws ManifoldCFException, IOException {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        fillInFilterTab(paramMap, ds);
        fillInMetadataTab(paramMap, ds);
        outputResource("SpecificationView.html", out, locale, paramMap);
    }

    ///////////////////////////////////////End of specification UI///////////////////////////////////////////////
}
