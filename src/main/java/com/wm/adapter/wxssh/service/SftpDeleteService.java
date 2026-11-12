package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import jakarta.resource.ResourceException;
import java.util.Locale;

/**
 * Adapter service template that deletes one remote file (or symbolic link) over SFTP.
 * <p>
 * Why: lets a flow remove a file after it has been processed. Directories are removed by SftpRmdir, so a wrong
 * path cannot delete a whole tree through this service.
 * <p>
 * Settings precedence: {@code remotePath} is declared as an input field of the service signature. A non-blank
 * value supplied in the pipeline overrides the "Default Remote Path" in the "SFTP Delete Settings" panel. It is
 * required: there is no built-in default. See {@link SftpSettings}.
 * <p>
 * Output: {@code success}, always true; every failure is thrown, including a missing file (SFTP status
 * {@code NO_SUCH_FILE}); use SftpExists first when a missing file must not fail the flow. A symbolic link is
 * removed as a link; its target is not touched.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and permanently deletes the file on the server.
 */
public class SftpDeleteService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";

    private String defaultRemotePath = "";

    public SftpDeleteService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));

        try {
            session.sftpDelete(path);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }
        return successRecord();
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP Delete Settings", new String[]{
            "defaultRemotePath",
            "remotePathInputField", "remotePathInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
    }

    /** Read-only: the pipeline field name of the declared input. Discovered by the ADK as a bean property. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }
}
