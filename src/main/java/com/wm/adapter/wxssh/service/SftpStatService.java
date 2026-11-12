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
 * Adapter service template that reads the attributes of one remote path over SFTP.
 * <p>
 * Why: lets a flow ask "what is this?" (size, times, permissions, type) without listing a whole directory, and
 * tell a symbolic link from the file it points to.
 * <p>
 * Settings precedence: {@code remotePath} and {@code followLinks} are declared as input fields of the service
 * signature. A non-blank value supplied in the pipeline overrides the matching value in the service's "SFTP Stat
 * Settings" panel; a blank or missing input leaves the panel value in effect. See {@link SftpSettings}.
 * <p>
 * Output: {@code exists} (always) and, when the path exists, {@code attributes} (a document, see
 * {@link #entryData}). A missing path is data, not an error: {@code exists} is false and there is no
 * {@code attributes}. With {@code followLinks} true a dangling symbolic link counts as missing.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session, performs one read-only stat request and closes it.
 */
public class SftpStatService extends SshAdapterService {

    /** Pipeline field names of the overridable settings; also the names shown in the input signature. */
    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_FOLLOW_LINKS = "followLinks";

    private String defaultRemotePath = ".";
    private String followLinks = "true";

    public SftpStatService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor inCursor = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inFollow = null;
        if (inCursor != null) {
            inPath = text(inCursor, INPUT_REMOTE_PATH);
            inFollow = text(inCursor, INPUT_FOLLOW_LINKS);
            inCursor.destroy();
        }

        String path = SftpSettings.resolveRemotePath(inPath, defaultRemotePath);
        boolean follow = SftpSettings.resolveBoolean(inFollow, followLinks, true);

        RemoteEntry entry;
        try {
            entry = session.sftpStat(path, follow);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();
        IDataUtil.put(outCursor, "exists", entry != null);
        if (entry != null) {
            IDataUtil.put(outCursor, "attributes", entryData(entry));
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
        descriptor.createGroup("SFTP Stat Settings", new String[]{
            "defaultRemotePath", "followLinks",
            "remotePathInputField", "remotePathInputFieldType",
            "followLinksInputField", "followLinksInputFieldType"
        });
        // No descriptor.setValidValues(...): on a service template it causes ART.114.244.

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "followLinksInputField", "followLinksInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getFollowLinksInputField() { return INPUT_FOLLOW_LINKS; }
    public String getFollowLinksInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getFollowLinks() { return followLinks; }
    public void setFollowLinks(String followLinks) { this.followLinks = followLinks; }
}
