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
 * Adapter service template that creates a remote directory over SFTP.
 * <p>
 * Why: lets a flow prepare a destination ("make sure /in/2026/11 exists") before an upload.
 * <p>
 * Settings precedence: {@code remotePath} and {@code parents} are declared as input fields of the service
 * signature. A non-blank value supplied in the pipeline overrides the matching value in the "SFTP Mkdir Settings"
 * panel. {@code remotePath} is required (there is no default: creating a directory in the login directory by
 * accident is not helpful). {@code parents} defaults to true. See {@link SftpSettings}.
 * <p>
 * Output: {@code success}, always true; every failure is thrown. With {@code parents} true an existing directory
 * is not an error; with false the server refuses it.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and creates directories on the server.
 */
public class SftpMkdirService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_PARENTS = "parents";

    private String defaultRemotePath = "";
    private String parents = "true";

    public SftpMkdirService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inParents = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            inParents = text(in, INPUT_PARENTS);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));
        boolean withParents = SftpSettings.resolveBoolean(inParents, parents, true);

        try {
            session.sftpMkdir(path, withParents);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }
        return successRecord();
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The hidden *InputField / *InputFieldType helpers must be members of this group (see SftpStatService).
        descriptor.createGroup("SFTP Mkdir Settings", new String[]{
            "defaultRemotePath", "parents",
            "remotePathInputField", "remotePathInputFieldType",
            "parentsInputField", "parentsInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "parentsInputField", "parentsInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getParentsInputField() { return INPUT_PARENTS; }
    public String getParentsInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getParents() { return parents; }
    public void setParents(String parents) { this.parents = parents; }
}
