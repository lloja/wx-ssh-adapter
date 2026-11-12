package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.RemoteEntry;
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
 * Adapter service template that reads where a remote symbolic link points, without following it.
 * <p>
 * Why: the stored target may be relative or dangling, which a real-path lookup would hide or reject. Flows that
 * mirror or audit a directory need the link as stored.
 * <p>
 * Settings precedence: {@code remotePath} is declared as an input field of the service signature. A non-blank
 * value supplied in the pipeline overrides the "Default Remote Path" in the service's "SFTP ReadLink Settings"
 * panel; a blank or missing input leaves the panel value in effect, and when that is blank too the path is
 * {@code .} (the login directory, which is not a link, so the answer is {@code isLink=false}). See
 * {@link SftpSettings}.
 * <p>
 * Output: {@code isLink} (always) and, when the path is a symbolic link, {@code target} exactly as the server
 * stores it. A path that exists but is not a symbolic link is data, not an error: {@code isLink} is false and
 * there is no {@code target}, which lets a flow branch without catching. A path that does not exist is still an
 * error (OpenSSH answers {@code NO_SUCH_FILE}); use SftpExists first if that must not fail.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session for an {@code lstat} (to learn the type), then a
 * second one for the {@code readlink} when the path is a link; both are read-only and closed again.
 */
public class SftpReadLinkService extends SshAdapterService {

    /** Pipeline field name of the overridable path; also the name shown in the input signature. */
    static final String INPUT_REMOTE_PATH = "remotePath";

    private String defaultRemotePath = ".";

    public SftpReadLinkService() {
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

        String target = null;
        try {
            RemoteEntry entry = session.sftpStat(path, false);
            // lstat first: asking readlink about a regular file fails with a server specific status (OpenSSH:
            // BAD_MESSAGE), so the type is checked instead of interpreting that error. A missing path (null entry)
            // falls through to readlink so the server's own NO_SUCH_FILE is reported as before.
            if (entry == null || entry.type() == RemoteEntry.Type.SYMLINK) {
                target = session.sftpReadLink(path);
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();
        IDataUtil.put(outCursor, "isLink", target != null);
        if (target != null) {
            IDataUtil.put(outCursor, "target", target);
        }
        outCursor.destroy();
        return outputRecord;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The hidden *InputField / *InputFieldType helpers must be members of this group: a hidden property that
        // belongs to no group lands in a default group named after the service, which Designer shows as a blank tab.
        descriptor.createGroup("SFTP ReadLink Settings", new String[]{
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
