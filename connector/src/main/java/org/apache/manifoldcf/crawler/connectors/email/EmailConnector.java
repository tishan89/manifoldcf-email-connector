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
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.agents.interfaces.ServiceInterruption;
import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.crawler.interfaces.*;
import org.apache.manifoldcf.crawler.system.Logging;

import java.io.*;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.search.*;

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

    // Local variables.
    protected long sessionExpiration = -1L;
    protected String server = null;
    protected String port = null;
    protected String username = null;
    protected String password = null;
    protected String protocol = null;
    protected Map<String, String> properties = new HashMap<>();
    private String folderName = null;
    private Folder folder;
    private Store store;


    //////////////////////////////////Start of Basic Connector Methods/////////////////////////

    /**
     * Connect.
     *
     * @param configParameters is the set of configuration parameters, which
     *                         in this case describe the root directory.
     */
    @Override
    public void connect(ConfigParams configParameters) {
        super.connect(configParameters);
        this.server = configParameters.getParameter(EmailConfig.SERVER_PARAM);
        this.port = configParameters.getParameter(EmailConfig.PORT_PARAM);
        this.protocol = configParameters.getParameter(EmailConfig.PROTOCOL_PARAM);
        this.username = configParameters.getParameter(EmailConfig.USERNAME_PARAM);
        this.password = configParameters.getParameter(EmailConfig.PASSWORD_PARAM);
        int i = 0;
        while (i < configParameters.getChildCount())     //In post property set is added as a configuration node
        {
            ConfigNode cn = configParameters.getChild(i++);
            if (cn.getType().equals(EmailConfig.NODE_PROPERTIES)) {
                String findParameterName = cn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                String findParameterValue = cn.getAttributeValue(EmailConfig.ATTRIBUTE_VALUE);
                this.properties.put(findParameterName, findParameterValue);
            }
        }
    }

    /**
     * Close the connection.  Call this before discarding this instance of the
     * repository connector.
     */
    @Override
    public void disconnect()
            throws ManifoldCFException {
        this.server = null;
        this.port = null;
        this.protocol = null;
        this.username = null;
        this.password = null;
        this.properties = null;
        finalizeConnection();
        super.disconnect();
    }

    /**
     * This method is periodically called for all connectors that are connected but not
     * in active use.
     */
    @Override
    public void poll() throws ManifoldCFException {
        if (store != null)
        {
            if (System.currentTimeMillis() >= sessionExpiration)
                finalizeConnection();
        }
    }

    /**
     * Test the connection.  Returns a string describing the connection integrity.
     *
     * @return the connection's status as a displayable string.
     */
    public String check()
            throws ManifoldCFException {
        try {
            checkConnection();
            return super.check();
        } catch (ServiceInterruption e) {
            return "Connection temporarily failed: " + e.getMessage();
        } catch (ManifoldCFException e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    protected void checkConnection() throws ManifoldCFException, ServiceInterruption {
        while (true) {
            try {
                store = getSession().getStore(protocol);
                store.connect(server, username, password);
                Folder defaultFolder = store.getDefaultFolder();
                if (defaultFolder == null) {
                    Logging.connectors.error(
                            "Email: Error during checking the connection.");
                    throw new ManifoldCFException("Email: Error during checking the connection.");
                }

            } catch (Exception e) {
                Logging.connectors.error(
                        "Email: Error during checking the connection.");
                throw new ManifoldCFException("Email: Error during checking the connection.");
            }
            store = null;
            return;
        }
    }

    ///////////////////////////////End of Basic Connector Methods////////////////////////////////////////

    //////////////////////////////Start of Repository Connector Method///////////////////////////////////


    public int getConnectorModel() {
        return MODEL_ADD;                       //Change is not applicable in context of email
    }

    /**
     * Return the list of activities that this connector supports (i.e. writes into the log).
     *
     * @return the list.
     */
    public String[] getActivitiesList() {
        return new String[]{EmailConfig.ACTIVITY_FETCH};
    }

    /**
     * Return the list of relationship types that this connector recognizes.
     *
     * @return the list.
     */
    public String[] getRelationshipTypes() {
        String[] relationships = new String[1];
        relationships[0] = EmailConfig.RELATIONSHIP_CHILD;
        return relationships;
    }

    /**
     * Get the bin name strings for a document identifier.  The bin name describes the queue to which the
     * document will be assigned for throttling purposes.  Throttling controls the rate at which items in a
     * given queue are fetched; it does not say anything about the overall fetch rate, which may operate on
     * multiple queues or bins.
     * For example, if you implement a web crawler, a good choice of bin name would be the server name, since
     * that is likely to correspond to a real resource that will need real throttle protection.
     *
     * @param documentIdentifier is the document identifier.
     * @return the set of bin names.  If an empty array is returned, it is equivalent to there being no request
     *         rate throttling available for this identifier.
     */
    public String[] getBinNames(String documentIdentifier) {
        return new String[]{server};
    }

    /**
     * Get the maximum number of documents to amalgamate together into one batch, for this connector.
     *
     * @return the maximum number. 0 indicates "unlimited".
     */
    public int getMaxDocumentRequest() {
        return 50;
    }

    /**
     * Queue "seed" documents.  Seed documents are the starting places for crawling activity.  Documents
     * are seeded when this method calls appropriate methods in the passed in ISeedingActivity object.
     * <p/>
     * This method can choose to find repository changes that happen only during the specified time interval.
     * The seeds recorded by this method will be viewed by the framework based on what the
     * getConnectorModel() method returns.
     * <p/>
     * It is not a big problem if the connector chooses to create more seeds than are
     * strictly necessary; it is merely a question of overall work required.
     * <p/>
     * The times passed to this method may be interpreted for greatest efficiency.  The time ranges
     * any given job uses with this connector will not overlap, but will proceed starting at 0 and going
     * to the "current time", each time the job is run.  For continuous crawling jobs, this method will
     * be called once, when the job starts, and at various periodic intervals as the job executes.
     * <p/>
     * When a job's specification is changed, the framework automatically resets the seeding start time to 0.  The
     * seeding start time may also be set to 0 on each job run, depending on the connector model returned by
     * getConnectorModel().
     * <p/>
     * Note that it is always ok to send MORE documents rather than less to this method.
     *
     * @param activities is the interface this method should use to perform whatever framework actions are desired.
     * @param spec       is a document specification (that comes from the job).
     * @param startTime  is the beginning of the time range to consider, inclusive.
     * @param endTime    is the end of the time range to consider, exclusive.
     * @param jobMode    is an integer describing how the job is being run, whether continuous or once-only.
     */
    @Override
    public void addSeedDocuments(ISeedingActivity activities,
                                 DocumentSpecification spec, long startTime, long endTime, int jobMode)
            throws ManifoldCFException, ServiceInterruption {
        Session session = getSession();
        int i = 0, j = 0;
        Map findMap;
        while (i < spec.getChildCount()) {
            SpecificationNode sn = spec.getChild(i++);
            if (sn.getType().equals(EmailConfig.NODE_FILTER) && sn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME).equals(EmailConfig.ATTRIBUTE_FOLDER)) {
                folderName = sn.getAttributeValue(EmailConfig.ATTRIBUTE_VALUE);
            }
        }
        while (j < spec.getChildCount()) {
            SpecificationNode sn = spec.getChild(j++);
            if (sn.getType().equals(EmailConfig.NODE_FILTER)) {
                String findParameterName, findParameterValue;
                findParameterName = sn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                findParameterValue = sn.getAttributeValue(EmailConfig.ATTRIBUTE_VALUE);
                findMap = new HashMap();
                findMap.put(findParameterName, findParameterValue);
                try {
                    Message[] messages = findMessages(startTime, endTime, findMap);
                    for (Message message : messages) {
                        String emailID = ((MimeMessage) message).getMessageID();
                        activities.addSeedDocument(emailID);
                    }
                } catch (MessagingException e) {
                    Logging.connectors.warn("Email: Error finding emails: " + e.getMessage(), e);
                    throw new ManifoldCFException(e.getMessage(), e);
                } catch (Exception e) {
                    Logging.connectors.warn("Email: Error finding emails: " + e.getMessage(), e);
                    throw new ManifoldCFException(e.getMessage(), e);
                }finally {
                    finalizeConnection();
                }

            }

        }
    }

    /*
    This method will return the list of messages which matches the given criteria
     */
    private Message[] findMessages(long startTime, long endTime, Map findMap) throws MessagingException {
        Message[] result;
        String findParameterName;
        String findParameterValue;
        initializeConnection();
        if (findMap.size() > 0) {
            result = null;
            Iterator it = findMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry) it.next();
                findParameterName = ((String) pair.getKey()).toLowerCase();
                findParameterValue = (String) pair.getValue();
                it.remove();
                if (Logging.connectors.isDebugEnabled())
                    Logging.connectors.debug("Email: Finding emails where " + findParameterName +
                            "= '" + findParameterValue + "'");
                if (findParameterName.equals(EmailConfig.EMAIL_SUBJECT)) {
                    SubjectTerm subjectTerm = new SubjectTerm(findParameterValue);
                    result = folder.search(subjectTerm);
                } else if (findParameterName.equals(EmailConfig.EMAIL_FROM)) {
                    FromStringTerm fromTerm = new FromStringTerm(findParameterValue);
                    result = folder.search(fromTerm);
                } else if (findParameterName.equals(EmailConfig.EMAIL_TO)) {
                    RecipientStringTerm recipientTerm = new RecipientStringTerm(Message.RecipientType.TO, findParameterValue);
                    result = folder.search(recipientTerm);
                } else if (findParameterName.equals(EmailConfig.EMAIL_BODY)) {
                    BodyTerm bodyTerm = new BodyTerm(findParameterValue);
                    result = folder.search(bodyTerm);
                }
            }
        } else {
            result = folder.getMessages();
        }

        return result;
    }

    private Session getSession() {
        // Create empty properties
        Properties props = new Properties();
        // Get session
        Session session = Session.getDefaultInstance(props, null);
        sessionExpiration = System.currentTimeMillis() + EmailConfig.SESSION_EXPIRATION_MILLISECONDS;
        return session;
    }

    private void initializeConnection() throws MessagingException {
        store = getSession().getStore(protocol);
        store.connect(server, username, password);

        if (protocol == EmailConfig.PROTOCOL_IMAP_PROVIDER) {
            folder = store.getFolder(folderName);
        } else {
            folder = store.getFolder(EmailConfig.FOLDER_INBOX);
        }
        folder.open(Folder.READ_ONLY);
    }

    private void finalizeConnection() {
        try {
            if (folder != null)
                folder.close(false);
            if (store != null)
                store.close();
        } catch (MessagingException e) {
            if (Logging.connectors.isDebugEnabled())
                Logging.connectors.debug("Error while closing connection to server" + e.getMessage());
        } finally {
            folder = null;
            store = null;
        }
    }

    /**
     * Get document versions given an array of document identifiers.
     * This method is called for EVERY document that is considered. It is therefore important to perform
     * as little work as possible here.
     * The connector will be connected before this method can be called.
     *
     * @param documentIdentifiers  is the array of local document identifiers, as understood by this connector.
     * @param oldVersions          is the corresponding array of version strings that have been saved for the document identifiers.
     *                             A null value indicates that this is a first-time fetch, while an empty string indicates that the previous document
     *                             had an empty version string.
     * @param activities           is the interface this method should use to perform whatever framework actions are desired.
     * @param spec                 is the current document specification for the current job.  If there is a dependency on this
     *                             specification, then the version string should include the pertinent data, so that reingestion will occur
     *                             when the specification changes.  This is primarily useful for metadata.
     * @param jobMode              is an integer describing how the job is being run, whether continuous or once-only.
     * @param usesDefaultAuthority will be true only if the authority in use for these documents is the default one.
     * @return the corresponding version strings, with null in the places where the document no longer exists.
     *         Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
     *         will always be processed.
     */
    @Override
    public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities,
                                        DocumentSpecification spec, int jobMode, boolean usesDefaultAuthority)
            throws ManifoldCFException, ServiceInterruption {

        String[] result = null;
        if (documentIdentifiers.length > 0) {
            result = new String[documentIdentifiers.length];
            //Since visioning is not applicable in the current context.
            if (result != null) {
                for (int i=0;i<documentIdentifiers.length;i++) {
                    result[i]=EmailConfig.EMAIL_VERSION;
                }
            }
            return result;

        } else {
            return new String[]{EmailConfig.EMAIL_VERSION};
        }

    }

    /**
     * Process a set of documents.
     * This is the method that should cause each document to be fetched, processed, and the results either added
     * to the queue of documents for the current job, and/or entered into the incremental ingestion manager.
     * The document specification allows this class to filter what is done based on the job.
     * The connector will be connected before this method can be called.
     *
     * @param documentIdentifiers is the set of document identifiers to process.
     * @param versions            is the corresponding document versions to process, as returned by getDocumentVersions() above.
     *                            The implementation may choose to ignore this parameter and always process the current version.
     * @param activities          is the interface this method should use to queue up new document references
     *                            and ingest documents.
     * @param spec                is the document specification.
     * @param scanOnly            is an array corresponding to the document identifiers.  It is set to true to indicate when the processing
     *                            should only find other references, and should not actually call the ingestion methods.
     * @param jobMode             is an integer describing how the job is being run, whether continuous or once-only.
     */
    @Override
    public void processDocuments(String[] documentIdentifiers, String[] versions, IProcessActivity activities,
                                 DocumentSpecification spec, boolean[] scanOnly, int jobMode)
            throws ManifoldCFException, ServiceInterruption {
        int i = 0, count=0;
        List<String> requiredMetadata = new ArrayList<>();
        try {
            initializeConnection();

            while (i < spec.getChildCount()) {
                SpecificationNode sn = spec.getChild(i++);
                if (sn.getType().equals(EmailConfig.NODE_METADATA)) {
                    String metadataAttribute = sn.getAttributeValue(EmailConfig.ATTRIBUTE_NAME);
                    requiredMetadata.add(metadataAttribute);
                }
            }
            for (String id : documentIdentifiers) {
                long startTime = System.currentTimeMillis();
                String msgId = documentIdentifiers[count];
                InputStream is = null;
                if (Logging.connectors.isDebugEnabled())
                    Logging.connectors.debug("Email: Processing document identifier '"
                            + msgId + "'");
                MessageIDTerm messageIDTerm = new MessageIDTerm(id);
                Message[] message = null;

                message = folder.search(messageIDTerm);
                for (Message msg : message) {
                    RepositoryDocument rd = new RepositoryDocument();
                    Date setDate = msg.getSentDate();
                    rd.setFileName(msg.getFileName());
                    is = msg.getInputStream();
                    rd.setBinary(is, msg.getSize());
                    String subject = StringUtils.EMPTY;
                    for (String metadata : requiredMetadata) {
                        if (metadata.toLowerCase().equals(EmailConfig.EMAIL_TO)) {
                            Address[] to = msg.getRecipients(Message.RecipientType.TO);
                            String[] toStr = new String[to.length];
                            int j = 0;
                            for (Address address : to) {
                                toStr[j] = address.toString();
                            }
                            rd.addField(EmailConfig.EMAIL_TO, toStr);
                        } else if (metadata.toLowerCase().equals(EmailConfig.EMAIL_FROM)) {
                            Address[] from = msg.getFrom();
                            String[] fromStr = new String[from.length];
                            int j = 0;
                            for (Address address : from) {
                                fromStr[j] = address.toString();
                            }
                            rd.addField(EmailConfig.EMAIL_TO, fromStr);

                        } else if (metadata.toLowerCase().equals(EmailConfig.EMAIL_SUBJECT)) {
                            subject = msg.getSubject();
                            rd.addField(EmailConfig.EMAIL_SUBJECT, subject);
                        } else if (metadata.toLowerCase().equals(EmailConfig.EMAIL_BODY)) {
                            Multipart mp = (Multipart) msg.getContent();
                            for (int j = 0, n = mp.getCount(); i < n; i++) {
                                Part part = mp.getBodyPart(i);
                                String disposition = part.getDisposition();
                                if ((disposition == null)) {
                                    MimeBodyPart mbp = (MimeBodyPart) part;
                                    if (mbp.isMimeType(EmailConfig.MIMETYPE_TEXT_PLAIN)) {
                                        rd.addField(EmailConfig.EMAIL_BODY, mbp.getContent().toString());
                                    } else if (mbp.isMimeType(EmailConfig.MIMETYPE_HTML)) {
                                        rd.addField(EmailConfig.EMAIL_BODY, mbp.getContent().toString()); //handle html accordingly. Returns content with html tags
                                    }
                                }
                            }
                        } else if (metadata.toLowerCase().equals(EmailConfig.EMAIL_DATE)) {
                            Date sentDate = msg.getSentDate();
                            rd.addField(EmailConfig.EMAIL_DATE, sentDate.toString());
                        } else if (metadata.toLowerCase().equals(EmailConfig.EMAIL_ATTACHMENT_ENCODING)) {
                            Multipart mp = (Multipart) msg.getContent();
                            if (mp != null) {
                                String[] encoding = new String[mp.getCount()];
                                for (int k = 0, n = mp.getCount(); i < n; i++) {
                                    Part part = mp.getBodyPart(i);
                                    String disposition = part.getDisposition();
                                    if ((disposition != null) &&
                                            ((disposition.equals(Part.ATTACHMENT) ||
                                                    (disposition.equals(Part.INLINE))))) {
                                        encoding[k] = part.getFileName().split("\\?")[1];

                                    }
                                }
                                rd.addField(EmailConfig.ENCODING_FIELD, encoding);
                            }
                        } else if (metadata.toLowerCase().equals(EmailConfig.EMAIL_ATTACHMENT_MIMETYPE)) {
                            Multipart mp = (Multipart) msg.getContent();
                            String[] MIMEType = new String[mp.getCount()];
                            for (int k = 0, n = mp.getCount(); i < n; i++) {
                                Part part = mp.getBodyPart(i);
                                String disposition = part.getDisposition();
                                if ((disposition != null) &&
                                        ((disposition.equals(Part.ATTACHMENT) ||
                                                (disposition.equals(Part.INLINE))))) {
                                    MIMEType[k] = part.getContentType();

                                }
                            }
                            rd.addField(EmailConfig.MIMETYPE_FIELD, MIMEType);
                        }
                    }
                    String documentURI = subject + messageIDTerm;
                    String version = versions[count++];
                    activities.ingestDocument(id, version, documentURI, rd);

                }
            }

        } catch (MessagingException e) {

        } catch (IOException e) {
            throw new ManifoldCFException(e.getMessage(), e,
                    ManifoldCFException.INTERRUPTED);
        } finally {
            finalizeConnection();
        }

    }

    //////////////////////////////End of Repository Connector Methods///////////////////////////////////


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
                                           ConfigParams parameters) throws ManifoldCFException {

        String userName = variableContext.getParameter(EmailConfig.USERNAME_PARAM);
        if (userName != null)
            parameters.setParameter(EmailConfig.USERNAME_PARAM, userName);

        String password = variableContext.getParameter(EmailConfig.PASSWORD_PARAM);
        if (password != null)
            parameters.setParameter(EmailConfig.PASSWORD_PARAM, password);

        String protocol = variableContext.getParameter(EmailConfig.PROTOCOL_PARAM);
        if (protocol != null) {

            if (protocol.equals(EmailConfig.PROTOCOL_IMAP)) {
                protocol = EmailConfig.PROTOCOL_IMAP_PROVIDER;
            }

            if (protocol.equals(EmailConfig.PROTOCOL_POP3)) {
                protocol = EmailConfig.PROTOCOL_POP3_PROVIDER;
            }

            parameters.setParameter(EmailConfig.PROTOCOL_PARAM, protocol);
        }

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
