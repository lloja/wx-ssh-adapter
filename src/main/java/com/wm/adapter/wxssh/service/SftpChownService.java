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
 * Adapter service template that changes the owner and/or group of a remote path over SFTP, optionally for a
 * whole directory tree.
 * <p>
 * Why: some deployments need files handed to a service account after upload.
 * <p>
 * Settings precedence: {@code remotePath}, {@code userName}, {@code uid}, {@code groupName}, {@code gid} and
 * {@code recursive} are declared as input fields of the service signature. A non-blank value supplied in the
 * pipeline overrides the matching value in the "SFTP Chown Settings" panel. {@code remotePath} is required. At
 * least one of owner and group must be given; a blank one is <b>left unchanged</b>, never set to 0 (which would
 * hand the file to root).
 * <p>
 * Names or numbers: the owner can be given as {@code userName} or {@code uid}, the group as {@code groupName} or
 * {@code gid}. SFTP version 3, which SSHJ speaks, carries numbers only, so a name is looked up on the server
 * first ({@code getent}, else {@code /etc/passwd} or {@code /etc/group}); the same name can be a different number
 * on another machine. Within one source (pipeline, or panel) giving a name and a number for the same account is
 * refused rather than guessed; a pipeline value of either kind replaces the panel's. See
 * {@link SftpSettings#resolveAccount} and {@link SshSession#sftpResolveUserId(String)}.
 * <p>
 * Recursive: with {@code recursive} true (default <b>false</b>) the path is a directory whose whole tree is
 * changed. Symbolic links are skipped and never followed; the tree is listed completely first and a tree over the
 * list cap changes nothing. See {@link SshSession#sftpChownRecursive(String, Integer, Integer)}.
 * <p>
 * Output: {@code success}, always true, and {@code changed}, the number of entries changed (1 when not
 * recursive); every failure is thrown. Servers normally allow this only to a privileged user, and answer
 * {@code PERMISSION_DENIED} (SFTP status) otherwise.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and changes ownership on the server; a name
 * lookup also opens one exec channel (or reads a file over SFTP).
 */
public class SftpChownService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_USER_NAME = "userName";
    static final String INPUT_UID = "uid";
    static final String INPUT_GROUP_NAME = "groupName";
    static final String INPUT_GID = "gid";
    static final String INPUT_RECURSIVE = "recursive";

    private String defaultRemotePath = "";
    private String defaultUserName = "";
    private String defaultUid = "";
    private String defaultGroupName = "";
    private String defaultGid = "";
    private String recursive = "false";

    public SftpChownService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inUserName = null;
        String inUid = null;
        String inGroupName = null;
        String inGid = null;
        String inRecursive = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            inUserName = text(in, INPUT_USER_NAME);
            inUid = text(in, INPUT_UID);
            inGroupName = text(in, INPUT_GROUP_NAME);
            inGid = text(in, INPUT_GID);
            inRecursive = text(in, INPUT_RECURSIVE);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));
        boolean deep = SftpSettings.resolveBoolean(inRecursive, recursive, false);
        SftpSettings.AccountRef owner;
        SftpSettings.AccountRef group;
        try {
            owner = SftpSettings.resolveAccount(INPUT_USER_NAME, INPUT_UID, inUserName, inUid,
                    defaultUserName, defaultUid);
            group = SftpSettings.resolveAccount(INPUT_GROUP_NAME, INPUT_GID, inGroupName, inGid,
                    defaultGroupName, defaultGid);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }
        if (owner.isEmpty() && group.isEmpty()) {
            throw invalidInput(new IllegalArgumentException(
                    "give at least one of " + INPUT_USER_NAME + ", " + INPUT_UID + ", "
                            + INPUT_GROUP_NAME + " and " + INPUT_GID));
        }

        int changed = 1;
        try {
            Integer uid = owner.name() != null ? Integer.valueOf(session.sftpResolveUserId(owner.name()))
                    : owner.id() == null ? null : Integer.valueOf(owner.id().intValue());
            Integer gid = group.name() != null ? Integer.valueOf(session.sftpResolveGroupId(group.name()))
                    : group.id() == null ? null : Integer.valueOf(group.id().intValue());
            if (deep) {
                changed = session.sftpChownRecursive(path, uid, gid);
            } else {
                session.sftpChown(path, uid, gid);
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

        descriptor.createGroup("SFTP Chown Settings", new String[]{
            "defaultRemotePath", "defaultUserName", "defaultUid", "defaultGroupName", "defaultGid", "recursive",
            "remotePathInputField", "remotePathInputFieldType",
            "userNameInputField", "userNameInputFieldType",
            "uidInputField", "uidInputFieldType",
            "groupNameInputField", "groupNameInputFieldType",
            "gidInputField", "gidInputFieldType",
            "recursiveInputField", "recursiveInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "userNameInputField", "userNameInputFieldType");
        declareInputField(descriptor, "uidInputField", "uidInputFieldType");
        declareInputField(descriptor, "groupNameInputField", "groupNameInputFieldType");
        declareInputField(descriptor, "gidInputField", "gidInputFieldType");
        declareInputField(descriptor, "recursiveInputField", "recursiveInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getUserNameInputField() { return INPUT_USER_NAME; }
    public String getUserNameInputFieldType() { return STRING_TYPE; }

    public String getUidInputField() { return INPUT_UID; }
    public String getUidInputFieldType() { return STRING_TYPE; }

    public String getGroupNameInputField() { return INPUT_GROUP_NAME; }
    public String getGroupNameInputFieldType() { return STRING_TYPE; }

    public String getGidInputField() { return INPUT_GID; }
    public String getGidInputFieldType() { return STRING_TYPE; }

    public String getRecursiveInputField() { return INPUT_RECURSIVE; }
    public String getRecursiveInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultUserName() { return defaultUserName; }
    public void setDefaultUserName(String defaultUserName) { this.defaultUserName = defaultUserName; }

    public String getDefaultUid() { return defaultUid; }
    public void setDefaultUid(String defaultUid) { this.defaultUid = defaultUid; }

    public String getDefaultGroupName() { return defaultGroupName; }
    public void setDefaultGroupName(String defaultGroupName) { this.defaultGroupName = defaultGroupName; }

    public String getDefaultGid() { return defaultGid; }
    public void setDefaultGid(String defaultGid) { this.defaultGid = defaultGid; }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }
}