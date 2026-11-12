package com.wm.adapter.wxssh.notification;

import com.luisloja.wxssh.core.RemoteDirectoryPoller;
import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adapter.wxssh.WxSshAdapter;
import com.wm.adapter.wxssh.connection.SshManagedConnection;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.error.AdapterException;
import com.wm.adk.i18n.AdapterResourceBundleManager;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.adk.notification.WmPollingNotification;
import com.wm.app.b2b.server.ServerAPI;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Polling notification that watches a directory on an SSH/SFTP server and publishes one document per new file.
 *
 * <p>Why: the standard way to react to "a partner dropped a file" on an SFTP server is a scheduled service that
 * lists, compares and gets; this notification does the listing, the new-file bookkeeping, the in-flight-upload
 * protection and the clean-up in one configured object, on the connection the user already has.
 *
 * <p>How it runs: the Integration Server's notification scheduler calls {@link #runNotification()} at the
 * configured interval with the notification's connection. Each ready file is published with
 * {@link #doNotify(WmRecord)}; the rules for what counts as new, ready and done are in
 * {@link RemoteDirectoryPoller}, which is tested without this class. Delivery is at-least-once.
 *
 * <p>Side effects: every run opens an SFTP channel on the connection's session (listing, and reading when content
 * is requested), may rename, move or delete delivered files on the server, publishes documents, and rewrites the
 * state file (default {@code <package config dir>/poll-state/<notification name>.state}). The state file is local
 * to one Integration Server node: in a cluster, enable the notification on one node only, otherwise each node
 * delivers each file.
 *
 * <p>Verification status: the base-class contract (override {@code runNotification()}, publish with
 * {@code doNotify}, register as a notification type) was read from {@code wmart.jar} and the JDBC adapter with
 * {@code javap}. How Designer presents the panel and the published document, and how the scheduler handles a
 * thrown error, are [VERIFY-ADK] until the owner has run it.
 */
public class RemoteDirectoryPollNotification extends WmPollingNotification {

    static final String STRING_TYPE = "java.lang.String";
    static final String OBJECT_TYPE = "java.lang.Object";

    private String directory = "";
    private String filter = "";
    private String recursive = "false";
    private String minAgeSeconds = "0";
    private String stabilityCheck = "true";
    private String postAction = "NONE";
    private String postActionArg = "";
    private String sortOrder = "NAME";
    private String maxFilesPerPoll = "100";
    private String includeContent = "false";
    private String maxContentBytes = "1048576";
    private String charset = "UTF-8";
    private String stateFilePath = "";

    public RemoteDirectoryPollNotification() {
        super();
    }

    @Override
    public void runNotification() throws ResourceException {
        RemoteDirectoryPoller.Settings settings;
        Charset cs;
        try {
            settings = PollSettings.parse(directory, filter, recursive, minAgeSeconds, stabilityCheck, postAction,
                    postActionArg, sortOrder, maxFilesPerPoll, includeContent, maxContentBytes);
            cs = PollSettings.charset(charset);
        } catch (IllegalArgumentException ex) {
            throw error("INVALID_CONFIG: " + ex.getMessage());
        }

        WmManagedConnection conn = retrieveConnection();
        if (!(conn instanceof SshManagedConnection ssh)) {
            throw error("Expected SshManagedConnection but received "
                    + (conn != null ? conn.getClass().getName() : "null"));
        }
        SshSession session = ssh.getOrReconnectSession();

        RemoteDirectoryPoller poller = new RemoteDirectoryPoller(settings, stateFile());
        RemoteDirectoryPoller.PollResult result;
        try {
            result = poller.poll(session, file -> doNotify(toRecord(settings, file, cs)));
        } catch (SshAdapterException ex) {
            throw error(ex.getCode() + ": " + ex.getMessage());
        }
        for (String failure : result.postActionFailures()) {
            errorLog(error(failure));
        }
    }

    /** Builds the published document for one file. */
    private static WmRecord toRecord(RemoteDirectoryPoller.Settings settings, RemoteDirectoryPoller.PolledFile f,
                                     Charset cs) {
        WmRecord out = WmRecord.create();
        IDataCursor c = out.getCursor();
        IDataUtil.put(c, "path", f.path());
        IDataUtil.put(c, "name", f.name());
        IDataUtil.put(c, "directory", settings.directory());
        IDataUtil.put(c, "size", Long.toString(f.size()));
        IDataUtil.put(c, "modifiedSec", Long.toString(f.modifiedSec()));
        IDataUtil.put(c, "contentSkipped", Boolean.toString(f.contentSkipped()));
        if (f.content() != null) {
            IDataUtil.put(c, "contentBytes", f.content());
            IDataUtil.put(c, "content", new String(f.content(), cs));
        }
        c.destroy();
        return out;
    }

    /** The configured state file, else the default one under the package's config directory. */
    private Path stateFile() {
        if (stateFilePath != null && !stateFilePath.isBlank()) {
            return Paths.get(stateFilePath.trim());
        }
        return ServerAPI.getPackageConfigDir(WxSshAdapter.ADAPTER_NAME).toPath()
                .resolve("poll-state").resolve(PollSettings.fileSafe(nodeNameOrUnknown()) + ".state");
    }

    /** Same wrapper as the services use, so the text reaches the Designer / log without a message pattern. */
    private static AdapterException error(String text) {
        try {
            AdapterResourceBundleManager bundles = WxSshAdapter.getInstance().getAdapterResourceBundleManager();
            if (bundles != null) {
                return new AdapterException(bundles, Integer.toString(WxSshAdapter.MAJOR_CODE), "1000",
                        new String[] {text}, null, null);
            }
        } catch (AdapterException | RuntimeException e) {
            // fall through to the plain form; the text is still reported
        }
        return new AdapterException(text, null, null);
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        WxSshAdapter adapter = WxSshAdapter.getInstance();
        if (adapter.getAdapterResourceBundleManager() != null) {
            descriptor.setDescriptions(adapter.getAdapterResourceBundleManager(), locale);
        }

        descriptor.createGroup("Remote Directory Poll Settings", new String[] {
            "directory", "filter", "recursive", "minAgeSeconds", "stabilityCheck", "postAction", "postActionArg",
            "sortOrder", "maxFilesPerPoll", "includeContent", "maxContentBytes", "charset", "stateFilePath",
            "pathOutputField", "pathOutputFieldType",
            "nameOutputField", "nameOutputFieldType",
            "directoryOutputField", "directoryOutputFieldType",
            "sizeOutputField", "sizeOutputFieldType",
            "modifiedSecOutputField", "modifiedSecOutputFieldType",
            "contentOutputField", "contentOutputFieldType",
            "contentBytesOutputField", "contentBytesOutputFieldType",
            "contentSkippedOutputField", "contentSkippedOutputFieldType"
        });

        declareOutput(descriptor, "pathOutputField", "pathOutputFieldType");
        declareOutput(descriptor, "nameOutputField", "nameOutputFieldType");
        declareOutput(descriptor, "directoryOutputField", "directoryOutputFieldType");
        declareOutput(descriptor, "sizeOutputField", "sizeOutputFieldType");
        declareOutput(descriptor, "modifiedSecOutputField", "modifiedSecOutputFieldType");
        declareOutput(descriptor, "contentOutputField", "contentOutputFieldType");
        declareOutput(descriptor, "contentBytesOutputField", "contentBytesOutputFieldType");
        declareOutput(descriptor, "contentSkippedOutputField", "contentSkippedOutputFieldType");
    }

    /** Same hidden-property pattern the services use for their output signature. */
    private static void declareOutput(WmTemplateDescriptor descriptor, String nameProperty, String typeProperty)
            throws ResourceException {
        descriptor.setHidden(nameProperty);
        descriptor.setHidden(typeProperty);
        descriptor.setResourceDomain(nameProperty, WmTemplateDescriptor.OUTPUT_FIELD_NAMES,
                new String[] {typeProperty});
    }

    /** Read-only: names and types of the published document's fields, discovered as bean properties. */
    public String getPathOutputField() { return "path"; }
    public String getPathOutputFieldType() { return STRING_TYPE; }
    public String getNameOutputField() { return "name"; }
    public String getNameOutputFieldType() { return STRING_TYPE; }
    public String getDirectoryOutputField() { return "directory"; }
    public String getDirectoryOutputFieldType() { return STRING_TYPE; }
    public String getSizeOutputField() { return "size"; }
    public String getSizeOutputFieldType() { return STRING_TYPE; }
    public String getModifiedSecOutputField() { return "modifiedSec"; }
    public String getModifiedSecOutputFieldType() { return STRING_TYPE; }
    public String getContentOutputField() { return "content"; }
    public String getContentOutputFieldType() { return STRING_TYPE; }
    public String getContentBytesOutputField() { return "contentBytes"; }
    public String getContentBytesOutputFieldType() { return OBJECT_TYPE; }
    public String getContentSkippedOutputField() { return "contentSkipped"; }
    public String getContentSkippedOutputFieldType() { return STRING_TYPE; }

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }

    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }

    public String getMinAgeSeconds() { return minAgeSeconds; }
    public void setMinAgeSeconds(String minAgeSeconds) { this.minAgeSeconds = minAgeSeconds; }

    public String getStabilityCheck() { return stabilityCheck; }
    public void setStabilityCheck(String stabilityCheck) { this.stabilityCheck = stabilityCheck; }

    public String getPostAction() { return postAction; }
    public void setPostAction(String postAction) { this.postAction = postAction; }

    public String getPostActionArg() { return postActionArg; }
    public void setPostActionArg(String postActionArg) { this.postActionArg = postActionArg; }

    public String getSortOrder() { return sortOrder; }
    public void setSortOrder(String sortOrder) { this.sortOrder = sortOrder; }

    public String getMaxFilesPerPoll() { return maxFilesPerPoll; }
    public void setMaxFilesPerPoll(String maxFilesPerPoll) { this.maxFilesPerPoll = maxFilesPerPoll; }

    public String getIncludeContent() { return includeContent; }
    public void setIncludeContent(String includeContent) { this.includeContent = includeContent; }

    public String getMaxContentBytes() { return maxContentBytes; }
    public void setMaxContentBytes(String maxContentBytes) { this.maxContentBytes = maxContentBytes; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }

    public String getStateFilePath() { return stateFilePath; }
    public void setStateFilePath(String stateFilePath) { this.stateFilePath = stateFilePath; }
}
