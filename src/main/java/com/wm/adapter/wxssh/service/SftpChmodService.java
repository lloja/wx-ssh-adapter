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
 * Adapter service template that changes the POSIX permission bits of a remote path over SFTP.
 * <p>
 * Why: uploaded files often need a specific mode (for example 640 for a key, 755 for a script).
 * <p>
 * Settings precedence: {@code remotePath} and {@code permissions} are declared as input fields of the service
 * signature. A non-blank value supplied in the pipeline overrides the matching value in the "SFTP Chmod
 * Settings" panel. Both are required: there is no default mode, because a guessed mode could expose a file.
 * <p>
 * {@code permissions} is written in <b>octal</b>, one to four digits, such as {@code 640}, {@code 0755} or
 * {@code 4755}. Anything else, a decimal-looking 9 included, is rejected with a message naming the field instead
 * of being misread. See {@link SftpSettings#parseOctalPermissions}.
 * <p>
 * Recursive: with {@code recursive} true (default <b>false</b>) the path is a directory whose whole tree is changed.
 * {@code permissions} then applies to files and {@code directoryPermissions} to directories; when
 * {@code directoryPermissions} is empty it is derived by adding the execute bit wherever the read bit is set (640
 * gives 750), so the tree stays enterable. Symbolic links are skipped and never followed; the tree is listed
 * completely first and a tree over the list cap changes nothing. See
 * {@link SshSession#sftpChmodRecursive(String, int, Integer)}.
 * <p>
 * Output: {@code success}, always true, and {@code changed}, the number of entries changed (1 when not recursive);
 * every failure is thrown.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and changes the mode of a path on the server.
 */
public class SftpChmodService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_PERMISSIONS = "permissions";
    static final String INPUT_DIRECTORY_PERMISSIONS = "directoryPermissions";
    static final String INPUT_RECURSIVE = "recursive";

    private String defaultRemotePath = "";
    private String defaultPermissions = "";
    private String defaultDirectoryPermissions = "";
    private String recursive = "false";

    public SftpChmodService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inPerms = null;
        String inDirPerms = null;
        String inRecursive = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            inPerms = text(in, INPUT_PERMISSIONS);
            inDirPerms = text(in, INPUT_DIRECTORY_PERMISSIONS);
            inRecursive = text(in, INPUT_RECURSIVE);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));
        boolean deep = SftpSettings.resolveBoolean(inRecursive, recursive, false);
        int mode;
        Integer dirMode = null;
        try {
            mode = SftpSettings.parseOctalPermissions(INPUT_PERMISSIONS,
                    SftpSettings.resolveText(inPerms, defaultPermissions));
            String dirText = SftpSettings.resolveText(inDirPerms, defaultDirectoryPermissions);
            if (deep && dirText != null && !dirText.isBlank()) {
                dirMode = SftpSettings.parseOctalPermissions(INPUT_DIRECTORY_PERMISSIONS, dirText);
            }
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }

        int changed = 1;
        try {
            if (deep) {
                changed = session.sftpChmodRecursive(path, mode, dirMode);
            } else {
                session.sftpChmod(path, mode);
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord out = successRecord();
        IDataCursor c = out.getCursor();
        IDataUtil.put(c, "changed", changed);
        c.destroy();
        return out;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP Chmod Settings", new String[]{
            "defaultRemotePath", "defaultPermissions", "defaultDirectoryPermissions", "recursive",
            "remotePathInputField", "remotePathInputFieldType",
            "permissionsInputField", "permissionsInputFieldType",
            "directoryPermissionsInputField", "directoryPermissionsInputFieldType",
            "recursiveInputField", "recursiveInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "permissionsInputField", "permissionsInputFieldType");
        declareInputField(descriptor, "directoryPermissionsInputField", "directoryPermissionsInputFieldType");
        declareInputField(descriptor, "recursiveInputField", "recursiveInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getPermissionsInputField() { return INPUT_PERMISSIONS; }
    public String getPermissionsInputFieldType() { return STRING_TYPE; }

    public String getDirectoryPermissionsInputField() { return INPUT_DIRECTORY_PERMISSIONS; }
    public String getDirectoryPermissionsInputFieldType() { return STRING_TYPE; }

    public String getRecursiveInputField() { return INPUT_RECURSIVE; }
    public String getRecursiveInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultPermissions() { return defaultPermissions; }
    public void setDefaultPermissions(String defaultPermissions) { this.defaultPermissions = defaultPermissions; }

    public String getDefaultDirectoryPermissions() { return defaultDirectoryPermissions; }
    public void setDefaultDirectoryPermissions(String defaultDirectoryPermissions) {
        this.defaultDirectoryPermissions = defaultDirectoryPermissions;
    }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }
}
