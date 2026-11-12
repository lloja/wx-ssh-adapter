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
 * Adapter service template that asks the SFTP server for the canonical absolute form of a path.
 * <p>
 * Why: a flow cannot tell where {@code .} or a relative path points (for example the login directory), nor
 * collapse {@code ..} and symbolic links, without asking the server. The answer is the safe base for building
 * absolute paths for the other SFTP services.
 * <p>
 * Settings precedence: {@code remotePath} is declared as an input field of the service signature. A non-blank
 * value supplied in the pipeline overrides the "Default Remote Path" in the service's "SFTP RealPath Settings"
 * panel; a blank or missing input leaves the panel value in effect, and when that is blank too the path is
 * {@code .} (the login directory). See {@link SftpSettings}.
 * <p>
 * Output: {@code realPath}. What a server answers for a path that does not exist is server specific (OpenSSH
 * reports {@code NO_SUCH_FILE} when a parent is missing); the failure is passed on as an adapter error.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session, performs one read-only request and closes it.
 */
public class SftpRealPathService extends SshAdapterService {

    /** Pipeline field name of the overridable path; also the name shown in the input signature. */
    static final String INPUT_REMOTE_PATH = "remotePath";

    private String defaultRemotePath = ".";

    public SftpRealPathService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor inCursor = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        if (inCursor != null) {
            inPath = text(inCursor, INPUT_REMOTE_PATH);
            inCursor.destroy();
        }

        String path = SftpSettings.resolveRemotePath(inPath, defaultRemotePath);

        String real;
        try {
            real = session.sftpRealPath(path);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();
        IDataUtil.put(outCursor, "realPath", real);
        outCursor.destroy();
        return outputRecord;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The hidden *InputField / *InputFieldType helpers must be members of this group: a hidden property that
        // belongs to no group lands in a default group named after the service, which Designer shows as a blank tab.
        descriptor.createGroup("SFTP RealPath Settings", new String[]{
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
