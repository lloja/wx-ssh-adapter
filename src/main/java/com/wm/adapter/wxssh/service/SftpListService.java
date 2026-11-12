package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.ListOptions;
import com.luisloja.wxssh.core.RemoteEntry;
import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataFactory;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.util.List;
import java.util.Locale;

/**
 * Adapter service template for listing files and directories on a remote SFTP server.
 * <p>
 * Why: Allows Integration Server services to browse remote directory contents and retrieve file metadata
 * (names, sizes, modification timestamps, POSIX permissions, types), optionally narrowed by a name filter, walked
 * recursively and with or without dot-files, so a flow does not have to fetch everything and filter it itself.
 * <p>
 * Settings precedence: {@code remotePath}, {@code filter}, {@code recursive} and {@code includeHidden} are declared
 * as input fields of the service signature. A non-blank value supplied in the pipeline overrides the matching value
 * in the service's "SFTP Settings" panel; a blank or missing input leaves the panel value in effect. See
 * {@link SftpSettings} for the exact rules. Defaults when both are blank: path {@code .}, no filter,
 * {@code recursive=false}, {@code includeHidden=true} (the behaviour before these options existed).
 * <p>
 * Filter: a glob matched against each entry <em>name</em> (not the path): {@code *}, {@code ?}, {@code [a-z]},
 * {@code [!x]}, {@code {csv,txt}}, {@code \} escape; case-sensitive. A malformed glob is an
 * {@code INVALID_CONFIG} error before any network traffic. With {@code recursive=true} a directory whose name does
 * not match is still searched but not reported.
 * <p>
 * Recursion: breadth first, over one SFTP channel; symbolic links are reported but never followed. More than
 * 100000 matching entries fails with {@code LIMIT_EXCEEDED} rather than exhausting memory.
 * <p>
 * Side effects: Opens one SFTP channel on the active SSH session, issues one directory read per directory visited,
 * and parses attributes. Read-only on the server.
 */
public class SftpListService extends SshAdapterService {

    /** Pipeline field names of the overridable settings; also the names shown in the input signature. */
    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_FILTER = "filter";
    static final String INPUT_RECURSIVE = "recursive";
    static final String INPUT_INCLUDE_HIDDEN = "includeHidden";

    private String defaultRemotePath = ".";
    private String filter = "";
    private String recursive = "false";
    private String includeHidden = "true";

    public SftpListService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor inCursor = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inFilter = null;
        String inRecursive = null;
        String inHidden = null;
        if (inCursor != null) {
            inPath = text(inCursor, INPUT_REMOTE_PATH);
            inFilter = text(inCursor, INPUT_FILTER);
            inRecursive = text(inCursor, INPUT_RECURSIVE);
            inHidden = text(inCursor, INPUT_INCLUDE_HIDDEN);
            inCursor.destroy();
        }

        String path = SftpSettings.resolveRemotePath(inPath, defaultRemotePath);
        ListOptions options = new ListOptions(
                SftpSettings.resolveText(inFilter, filter),
                SftpSettings.resolveBoolean(inRecursive, recursive, false),
                SftpSettings.resolveBoolean(inHidden, includeHidden, true));

        List<RemoteEntry> entries;
        try {
            entries = session.sftpList(path, options);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        IData[] entryDocs = new IData[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            RemoteEntry e = entries.get(i);
            IData doc = IDataFactory.create();
            IDataCursor docCursor = doc.getCursor();
            IDataUtil.put(docCursor, "name", e.name());
            IDataUtil.put(docCursor, "path", e.path());
            IDataUtil.put(docCursor, "size", e.size());
            IDataUtil.put(docCursor, "modifiedSec", e.modifiedSec());
            IDataUtil.put(docCursor, "permissions", e.permissions());
            IDataUtil.put(docCursor, "type", e.type().name());
            IDataUtil.put(docCursor, "isDirectory", e.type() == RemoteEntry.Type.DIRECTORY);
            IDataUtil.put(docCursor, "isRegularFile", e.type() == RemoteEntry.Type.FILE);
            docCursor.destroy();
            entryDocs[i] = doc;
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();
        IDataUtil.put(outCursor, "entries", entryDocs);
        IDataUtil.put(outCursor, "count", entries.size());
        outCursor.destroy();

        return outputRecord;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The hidden *InputField / *InputFieldType helpers must be members of this group (see ExecCommandService for
        // the pattern): a hidden property that belongs to no group lands in a default group named after the service,
        // and Designer then shows that group as a blank tab ("SftpList").
        descriptor.createGroup("SFTP Settings", new String[]{
            "defaultRemotePath", "filter", "recursive", "includeHidden",
            "remotePathInputField", "remotePathInputFieldType",
            "filterInputField", "filterInputFieldType",
            "recursiveInputField", "recursiveInputFieldType",
            "includeHiddenInputField", "includeHiddenInputFieldType"
        });
        // No descriptor.setValidValues(...): on a service template it causes ART.114.244.

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "filterInputField", "filterInputFieldType");
        declareInputField(descriptor, "recursiveInputField", "recursiveInputFieldType");
        declareInputField(descriptor, "includeHiddenInputField", "includeHiddenInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getFilterInputField() { return INPUT_FILTER; }
    public String getFilterInputFieldType() { return STRING_TYPE; }

    public String getRecursiveInputField() { return INPUT_RECURSIVE; }
    public String getRecursiveInputFieldType() { return STRING_TYPE; }

    public String getIncludeHiddenInputField() { return INPUT_INCLUDE_HIDDEN; }
    public String getIncludeHiddenInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }

    public String getIncludeHidden() { return includeHidden; }
    public void setIncludeHidden(String includeHidden) { this.includeHidden = includeHidden; }
}
