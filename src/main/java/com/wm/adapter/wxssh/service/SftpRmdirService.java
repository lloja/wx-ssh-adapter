package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.util.Locale;

/**
 * Adapter service template that removes a remote directory over SFTP, optionally with everything below it.
 * <p>
 * Why: SFTP itself removes only empty directories; the recursive option lets a flow clean up a whole tree in one
 * call.
 * <p>
 * Settings precedence: {@code remotePath} and {@code recursive} are declared as input fields of the service
 * signature. A non-blank value supplied in the pipeline overrides the matching value in the "SFTP Rmdir
 * Settings" panel. {@code remotePath} is required. {@code recursive} defaults to <b>false</b>, so nothing is
 * deleted below a directory unless the flow asks for it. See {@link SftpSettings}.
 * <p>
 * Output: {@code success} (always true; every failure is thrown) and {@code removed}, the number of entries
 * deleted, the directory itself included (1 when not recursive).
 * <p>
 * Safety of the recursive mode: see {@link SshSession#sftpRmdirRecursive}. It refuses {@code /}, {@code .} and
 * {@code ..}, does not follow symbolic links, and lists the whole tree before deleting the first entry.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and permanently deletes directories (and, when
 * recursive, files) on the server.
 */
public class SftpRmdirService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_RECURSIVE = "recursive";

    private String defaultRemotePath = "";
    private String recursive = "false";

    public SftpRmdirService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inRecursive = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            inRecursive = text(in, INPUT_RECURSIVE);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));
        boolean deep = SftpSettings.resolveBoolean(inRecursive, recursive, false);

        int removed;
        try {
            if (deep) {
                removed = session.sftpRmdirRecursive(path);
            } else {
                session.sftpRmdir(path);
                removed = 1;
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord out = successRecord();
        IDataCursor c = out.getCursor();
        IDataUtil.put(c, "removed", removed);
        c.destroy();
        return out;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP Rmdir Settings", new String[]{
            "defaultRemotePath", "recursive",
            "remotePathInputField", "remotePathInputFieldType",
            "recursiveInputField", "recursiveInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "recursiveInputField", "recursiveInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getRecursiveInputField() { return INPUT_RECURSIVE; }
    public String getRecursiveInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }
}
