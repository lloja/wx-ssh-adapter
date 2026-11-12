package com.wm.adapter.wxssh;

import java.util.ListResourceBundle;

/**
 * Localization and metadata resource bundle for WxSshAdapter.
 * <p>
 * Why: Supplies display names, field labels, descriptions, and help strings to WmART and Software AG / IBM Designer.
 * <p>
 * Side effects: Pure in-memory resource mappings; no external I/O.
 */
public class WxSshAdapterResourceBundle extends ListResourceBundle {

    private static final Object[][] CONTENTS = {
        {"adapter.displayName", "IBM webMethods Adapter for SSH"},
        {"adapter.description", "Adapter for SSH, SFTP, SCP, and port forwarding based on SSHJ"},
        {"adapter.vendorName", "Luis Loja"},
        // Generic error message: the adapter passes the real text as {0}, so quotes and braces in it are never parsed as a message pattern
        {"1000", "{0}"},
        {"WxSshAdapter.DISPLAYNAME", "IBM webMethods Adapter for SSH"},
        {"WxSshAdapter.DESCRIPTION", "Adapter for SSH, SFTP, SCP, and port forwarding based on SSHJ"},
        {"WxSshAdapter.VENDORNAME", "Luis Loja"},

        // Online Help URLs
        {"WxSshAdapter.LISTRESOURCES.HELPURL", "/WxSshAdapter/doc/OnlineHelp/AdapterOH.html"},
        {"WxSshAdapter.LISTCONNECTIONTYPES.HELPURL", "/WxSshAdapter/doc/OnlineHelp/AdapterOH.html"},
        {"WxSshAdapter.LISTPOLLINGNOTIFICATIONS.HELPURL", "/WxSshAdapter/doc/OnlineHelp/AdapterOH.html"},
        {"WxSshAdapter.ABOUT.HELPURL", "/WxSshAdapter/doc/OnlineHelp/AdapterOH.html"},
        {"WxSshAdapter.RELEASENOTEURL", "/WxSshAdapter/ReleaseNotes.html"},
        {"WxSshAdapter.THIRDPARTYCOPYRIGHTURL", "/WxSshAdapter/copyright.html"},

        // Connection Factory
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.DISPLAYNAME", "SSH Connection"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.DESCRIPTION", "Connection definition for remote SSH host"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.HELPURL", "/WxSshAdapter/doc/OnlineHelp/AdapterOH.html"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.Server.GROUPNAME", "Server"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.Authentication.GROUPNAME", "Authentication"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.Trust.GROUPNAME", "Trust & Host Key"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.host.DISPLAYNAME", "Host Name / IP"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.host.DESCRIPTION", "Remote SSH server host name or IP address"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.port.DISPLAYNAME", "Port Number"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.port.DESCRIPTION", "Remote SSH server port number (default: 22)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.connectTimeoutMs.DISPLAYNAME", "Connect Timeout (ms)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.connectTimeoutMs.DESCRIPTION", "Socket connect timeout in milliseconds"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.readTimeoutMs.DISPLAYNAME", "Read Timeout (ms)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.readTimeoutMs.DESCRIPTION", "Socket read / channel timeout in milliseconds"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.keepAliveSeconds.DISPLAYNAME", "Keep-Alive Interval (s)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.keepAliveSeconds.DESCRIPTION", "Heartbeat keep-alive interval in seconds (0 = disabled)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.maxConcurrentChannels.DISPLAYNAME", "Max Concurrent Channels"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.maxConcurrentChannels.DESCRIPTION", "Most operations (exec, SFTP, SCP) allowed to run at once over one SSH connection; further callers wait for a free slot instead of failing. Keep it at or below the server's per-connection limit (OpenSSH MaxSessions, default 10). Default: 8"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.compression.DISPLAYNAME", "Compression Enabled"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.compression.DESCRIPTION", "Enable zlib transport compression"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.authMethod.DISPLAYNAME", "Authentication Method"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.authMethod.DESCRIPTION", "Authentication method: PASSWORD, PUBLIC_KEY, or KEYBOARD_INTERACTIVE"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.username.DISPLAYNAME", "User Name"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.username.DESCRIPTION", "SSH account user name on the remote host"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.password.DISPLAYNAME", "Password"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.password.DESCRIPTION", "SSH account password (for password or keyboard-interactive auth)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.privateKeyPath.DISPLAYNAME", "Private Key File Path"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.privateKeyPath.DESCRIPTION", "Local filesystem path to OpenSSH / PEM private key file"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.privateKeyPem.DISPLAYNAME", "Private Key PEM String"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.privateKeyPem.DESCRIPTION", "Inline PEM formatted private key string"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.keyPassphrase.DISPLAYNAME", "Private Key Passphrase"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.keyPassphrase.DESCRIPTION", "Passphrase for encrypted private key (if any)"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.hostKeyPolicy.DISPLAYNAME", "Host Key Policy"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.hostKeyPolicy.DESCRIPTION", "Host key policy: ACCEPT_ANY (dev only), KNOWN_HOSTS, or PINNED_FINGERPRINT"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.knownHostsPath.DISPLAYNAME", "Known Hosts File Path"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.knownHostsPath.DESCRIPTION", "Path to OpenSSH-formatted known_hosts file"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.pinnedFingerprint.DISPLAYNAME", "Pinned SHA-256 Fingerprint"},
        {"com.wm.adapter.wxssh.connection.SshConnectionFactory.pinnedFingerprint.DESCRIPTION", "SHA-256 fingerprint of the remote host key (hex or SHA256:base64)"},

        // ExecCommandService
        {"com.wm.adapter.wxssh.service.ExecCommandService.DISPLAYNAME", "ExecCommand"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.DESCRIPTION", "Executes a command on the remote host over SSH"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.Command Settings.GROUPNAME", "Command Settings"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.defaultCommand.DISPLAYNAME", "Default Command"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.defaultCommand.DESCRIPTION", "Shell command line executed if not overridden at runtime"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.timeoutSeconds.DISPLAYNAME", "Timeout (seconds)"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.timeoutSeconds.DESCRIPTION", "Maximum seconds to wait for command execution to complete"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.allocatePty.DISPLAYNAME", "Allocate PTY"},
        {"com.wm.adapter.wxssh.service.ExecCommandService.allocatePty.DESCRIPTION", "Whether to request pseudo-terminal allocation"},

        // SftpListService
        {"com.wm.adapter.wxssh.service.SftpListService.DISPLAYNAME", "SftpList"},
        {"com.wm.adapter.wxssh.service.SftpListService.DESCRIPTION", "Lists entries in a remote directory over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpListService.SFTP Settings.GROUPNAME", "SFTP Settings"},
        {"com.wm.adapter.wxssh.service.SftpListService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpListService.defaultRemotePath.DESCRIPTION", "Directory path on remote host, used when the remotePath input is empty (defaults to '.')"},
        {"com.wm.adapter.wxssh.service.SftpListService.filter.DISPLAYNAME", "Name Filter (glob)"},
        {"com.wm.adapter.wxssh.service.SftpListService.filter.DESCRIPTION", "Glob matched against each entry name, for example *.csv or report_??.{csv,txt}; empty = no filter. The filter input, when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.SftpListService.recursive.DISPLAYNAME", "Recursive"},
        {"com.wm.adapter.wxssh.service.SftpListService.recursive.DESCRIPTION", "true also lists the contents of sub-directories (symbolic links are not followed); default false. The recursive input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.SftpListService.includeHidden.DISPLAYNAME", "Include Hidden Entries"},
        {"com.wm.adapter.wxssh.service.SftpListService.includeHidden.DESCRIPTION", "true (default) also lists entries whose name starts with '.'; false leaves them out. The includeHidden input (true/false), when not empty, overrides this"},

        // SftpGetService
        {"com.wm.adapter.wxssh.service.SftpGetService.DISPLAYNAME", "SftpGet"},
        {"com.wm.adapter.wxssh.service.SftpGetService.DESCRIPTION", "Downloads a file from a remote host over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpGetService.SFTP Get Settings.GROUPNAME", "SFTP Get Settings"},
        {"com.wm.adapter.wxssh.service.SftpGetService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpGetService.defaultRemotePath.DESCRIPTION", "Remote file path to download, used when the remotePath input is empty"},
        {"com.wm.adapter.wxssh.service.SftpGetService.defaultLocalPath.DISPLAYNAME", "Default Local Path"},
        {"com.wm.adapter.wxssh.service.SftpGetService.defaultLocalPath.DESCRIPTION", "Local filesystem path to write the download, used when the localFilePath input is empty (empty = return in pipeline)"},
        {"com.wm.adapter.wxssh.service.SftpGetService.charset.DISPLAYNAME", "Character Encoding"},
        {"com.wm.adapter.wxssh.service.SftpGetService.charset.DESCRIPTION", "Encoding used to decode in-memory text content, used when the charset input is empty (default UTF-8)"},

        // SftpPutService
        {"com.wm.adapter.wxssh.service.SftpPutService.DISPLAYNAME", "SftpPut"},
        {"com.wm.adapter.wxssh.service.SftpPutService.DESCRIPTION", "Uploads a file to a remote host over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpPutService.SFTP Put Settings.GROUPNAME", "SFTP Put Settings"},
        {"com.wm.adapter.wxssh.service.SftpPutService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpPutService.defaultRemotePath.DESCRIPTION", "Destination file path on remote host, used when the remotePath input is empty"},
        {"com.wm.adapter.wxssh.service.SftpPutService.defaultLocalPath.DISPLAYNAME", "Default Local Path"},
        {"com.wm.adapter.wxssh.service.SftpPutService.defaultLocalPath.DESCRIPTION", "Local file to upload, used when the localFilePath input is empty (empty = upload from pipeline)"},
        {"com.wm.adapter.wxssh.service.SftpPutService.atomic.DISPLAYNAME", "Atomic Upload (.part)"},
        {"com.wm.adapter.wxssh.service.SftpPutService.atomic.DESCRIPTION", "Upload to .part file first then rename to prevent partial reads; the atomic input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.SftpPutService.charset.DISPLAYNAME", "Character Encoding"},
        {"com.wm.adapter.wxssh.service.SftpPutService.charset.DESCRIPTION", "Encoding used when uploading string content, used when the charset input is empty"},

        // SftpStatService
        {"com.wm.adapter.wxssh.service.SftpStatService.DISPLAYNAME", "SftpStat"},
        {"com.wm.adapter.wxssh.service.SftpStatService.DESCRIPTION", "Reads the attributes (size, times, permissions, type) of a remote path over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpStatService.SFTP Stat Settings.GROUPNAME", "SFTP Stat Settings"},
        {"com.wm.adapter.wxssh.service.SftpStatService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpStatService.defaultRemotePath.DESCRIPTION", "Remote path to inspect, used when the remotePath input is empty (defaults to '.')"},
        {"com.wm.adapter.wxssh.service.SftpStatService.followLinks.DISPLAYNAME", "Follow Symbolic Links"},
        {"com.wm.adapter.wxssh.service.SftpStatService.followLinks.DESCRIPTION", "true (default) reports what a symbolic link points to; false reports the link itself. The followLinks input (true/false), when not empty, overrides this"},

        // SftpExistsService
        {"com.wm.adapter.wxssh.service.SftpExistsService.DISPLAYNAME", "SftpExists"},
        {"com.wm.adapter.wxssh.service.SftpExistsService.DESCRIPTION", "Checks whether a remote path exists and what type it is over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpExistsService.SFTP Exists Settings.GROUPNAME", "SFTP Exists Settings"},
        {"com.wm.adapter.wxssh.service.SftpExistsService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpExistsService.defaultRemotePath.DESCRIPTION", "Remote path to check, used when the remotePath input is empty (defaults to '.')"},
        {"com.wm.adapter.wxssh.service.SftpExistsService.followLinks.DISPLAYNAME", "Follow Symbolic Links"},
        {"com.wm.adapter.wxssh.service.SftpExistsService.followLinks.DESCRIPTION", "true (default) reports the type a symbolic link points to, and a dangling link counts as missing; false reports the link itself. The followLinks input (true/false), when not empty, overrides this"},

        // SftpRealPathService
        {"com.wm.adapter.wxssh.service.SftpRealPathService.DISPLAYNAME", "SftpRealPath"},
        {"com.wm.adapter.wxssh.service.SftpRealPathService.DESCRIPTION", "Returns the canonical absolute form of a remote path over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpRealPathService.SFTP RealPath Settings.GROUPNAME", "SFTP RealPath Settings"},
        {"com.wm.adapter.wxssh.service.SftpRealPathService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpRealPathService.defaultRemotePath.DESCRIPTION", "Path to resolve, used when the remotePath input is empty (defaults to '.', the login directory)"},

        // SftpReadLinkService
        {"com.wm.adapter.wxssh.service.SftpReadLinkService.DISPLAYNAME", "SftpReadLink"},
        {"com.wm.adapter.wxssh.service.SftpReadLinkService.DESCRIPTION", "Reads the target of a remote symbolic link over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpReadLinkService.SFTP ReadLink Settings.GROUPNAME", "SFTP ReadLink Settings"},
        {"com.wm.adapter.wxssh.service.SftpReadLinkService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpReadLinkService.defaultRemotePath.DESCRIPTION", "Symbolic link to read, used when the remotePath input is empty (defaults to '.')"},

        // SftpMkdirService
        {"com.wm.adapter.wxssh.service.SftpMkdirService.DISPLAYNAME", "SftpMkdir"},
        {"com.wm.adapter.wxssh.service.SftpMkdirService.DESCRIPTION", "Creates a remote directory over SFTP, optionally with its missing parents"},
        {"com.wm.adapter.wxssh.service.SftpMkdirService.SFTP Mkdir Settings.GROUPNAME", "SFTP Mkdir Settings"},
        {"com.wm.adapter.wxssh.service.SftpMkdirService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpMkdirService.defaultRemotePath.DESCRIPTION", "Directory to create, used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpMkdirService.parents.DISPLAYNAME", "Create Parent Directories"},
        {"com.wm.adapter.wxssh.service.SftpMkdirService.parents.DESCRIPTION", "true (default) creates missing parents and accepts an existing directory; false creates one level and fails if it exists or a parent is missing. The parents input (true/false), when not empty, overrides this"},

        // SftpDeleteService
        {"com.wm.adapter.wxssh.service.SftpDeleteService.DISPLAYNAME", "SftpDelete"},
        {"com.wm.adapter.wxssh.service.SftpDeleteService.DESCRIPTION", "Deletes one remote file or symbolic link over SFTP (not a directory)"},
        {"com.wm.adapter.wxssh.service.SftpDeleteService.SFTP Delete Settings.GROUPNAME", "SFTP Delete Settings"},
        {"com.wm.adapter.wxssh.service.SftpDeleteService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpDeleteService.defaultRemotePath.DESCRIPTION", "File or symbolic link to delete, used when the remotePath input is empty (required: there is no default)"},

        // SftpRmdirService
        {"com.wm.adapter.wxssh.service.SftpRmdirService.DISPLAYNAME", "SftpRmdir"},
        {"com.wm.adapter.wxssh.service.SftpRmdirService.DESCRIPTION", "Removes a remote directory over SFTP, optionally with everything below it"},
        {"com.wm.adapter.wxssh.service.SftpRmdirService.SFTP Rmdir Settings.GROUPNAME", "SFTP Rmdir Settings"},
        {"com.wm.adapter.wxssh.service.SftpRmdirService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpRmdirService.defaultRemotePath.DESCRIPTION", "Directory to remove, used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpRmdirService.recursive.DISPLAYNAME", "Remove Recursively"},
        {"com.wm.adapter.wxssh.service.SftpRmdirService.recursive.DESCRIPTION", "false (default) removes an empty directory only; true deletes the directory and everything below it, never following symbolic links, and refuses / . and ... The recursive input (true/false), when not empty, overrides this"},

        // SftpRenameService
        {"com.wm.adapter.wxssh.service.SftpRenameService.DISPLAYNAME", "SftpRename"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.DESCRIPTION", "Renames or moves a remote path over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.SFTP Rename Settings.GROUPNAME", "SFTP Rename Settings"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.defaultFromPath.DISPLAYNAME", "Default From Path"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.defaultFromPath.DESCRIPTION", "Existing path, used when the fromPath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.defaultToPath.DISPLAYNAME", "Default To Path"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.defaultToPath.DESCRIPTION", "New path, used when the toPath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.overwrite.DISPLAYNAME", "Overwrite Existing Target"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.overwrite.DESCRIPTION", "false (default) fails when the target exists; true replaces an existing file or link (never a directory). The overwrite input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.createParents.DISPLAYNAME", "Create Parent Directories"},
        {"com.wm.adapter.wxssh.service.SftpRenameService.createParents.DESCRIPTION", "false (default) fails when the folder of the target path does not exist (SFTP rename never creates folders); true creates the missing folders first. The createParents input (true/false), when not empty, overrides this"},

        // SftpSymlinkService
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.DISPLAYNAME", "SftpSymlink"},
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.DESCRIPTION", "Creates a symbolic link on the server over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.SFTP Symlink Settings.GROUPNAME", "SFTP Symlink Settings"},
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.defaultLinkPath.DISPLAYNAME", "Default Link Path"},
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.defaultLinkPath.DESCRIPTION", "Path of the link to create, used when the linkPath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.defaultTarget.DISPLAYNAME", "Default Target"},
        {"com.wm.adapter.wxssh.service.SftpSymlinkService.defaultTarget.DESCRIPTION", "What the link points to, stored as given and may be relative, used when the target input is empty (required: there is no default)"},

        // SftpChmodService
        {"com.wm.adapter.wxssh.service.SftpChmodService.DISPLAYNAME", "SftpChmod"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.DESCRIPTION", "Changes the permission bits of a remote path, or of a whole directory tree, over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.SFTP Chmod Settings.GROUPNAME", "SFTP Chmod Settings"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.defaultRemotePath.DESCRIPTION", "Path to change, used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.defaultPermissions.DISPLAYNAME", "Default Permissions (octal)"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.defaultPermissions.DESCRIPTION", "Permission bits in octal such as 640, 0755 or 4755, used when the permissions input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.defaultDirectoryPermissions.DISPLAYNAME", "Default Directory Permissions (octal)"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.defaultDirectoryPermissions.DESCRIPTION", "Recursive only: permission bits in octal for directories, used when the directoryPermissions input is empty. Empty = derived from the file permissions by adding the execute bit wherever the read bit is set (640 gives 750)"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.recursive.DISPLAYNAME", "Recursive"},
        {"com.wm.adapter.wxssh.service.SftpChmodService.recursive.DESCRIPTION", "false (default) changes only the given path; true changes the whole tree below it: files get the permissions, directories the directory permissions, symbolic links are skipped. The recursive input (true/false), when not empty, overrides this"},

        // SftpChownService
        {"com.wm.adapter.wxssh.service.SftpChownService.DISPLAYNAME", "SftpChown"},
        {"com.wm.adapter.wxssh.service.SftpChownService.DESCRIPTION", "Changes the owner and/or group of a remote path over SFTP, by name or number, optionally for a whole tree"},
        {"com.wm.adapter.wxssh.service.SftpChownService.SFTP Chown Settings.GROUPNAME", "SFTP Chown Settings"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultRemotePath.DESCRIPTION", "Path to change, used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultUserName.DISPLAYNAME", "Default User Name"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultUserName.DESCRIPTION", "New owner as an account name; the server is asked for its number (getent, else /etc/passwd). Used when neither the userName nor the uid input is given. Do not fill both this and Default User Id"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultUid.DISPLAYNAME", "Default User Id"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultUid.DESCRIPTION", "New numeric owner, used when neither the userName nor the uid input is given. Empty leaves the owner unchanged. Do not fill both this and Default User Name. At least one of owner and group is required"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultGroupName.DISPLAYNAME", "Default Group Name"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultGroupName.DESCRIPTION", "New group as a group name; the server is asked for its number (getent, else /etc/group). Used when neither the groupName nor the gid input is given. Do not fill both this and Default Group Id"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultGid.DISPLAYNAME", "Default Group Id"},
        {"com.wm.adapter.wxssh.service.SftpChownService.defaultGid.DESCRIPTION", "New numeric group, used when neither the groupName nor the gid input is given. Empty leaves the group unchanged. Do not fill both this and Default Group Name. At least one of owner and group is required"},
        {"com.wm.adapter.wxssh.service.SftpChownService.recursive.DISPLAYNAME", "Recursive"},
        {"com.wm.adapter.wxssh.service.SftpChownService.recursive.DESCRIPTION", "false (default) changes only the given path; true changes the whole tree below it; symbolic links are skipped. The recursive input (true/false), when not empty, overrides this"},

        // SftpSetTimesService
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.DISPLAYNAME", "SftpSetTimes"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.DESCRIPTION", "Sets the modification and/or access time of a remote path over SFTP"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.SFTP SetTimes Settings.GROUPNAME", "SFTP SetTimes Settings"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.defaultRemotePath.DESCRIPTION", "Path to change, used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.defaultModifiedSec.DISPLAYNAME", "Default Modified Time (seconds)"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.defaultModifiedSec.DESCRIPTION", "New modification time in seconds since 1970-01-01 UTC, used when the modifiedSec input is empty. Empty leaves it unchanged. At least one time is required"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.defaultAccessedSec.DISPLAYNAME", "Default Accessed Time (seconds)"},
        {"com.wm.adapter.wxssh.service.SftpSetTimesService.defaultAccessedSec.DESCRIPTION", "New access time in seconds since 1970-01-01 UTC, used when the accessedSec input is empty. Empty leaves it unchanged"},

        // SftpTruncateService
        {"com.wm.adapter.wxssh.service.SftpTruncateService.DISPLAYNAME", "SftpTruncate"},
        {"com.wm.adapter.wxssh.service.SftpTruncateService.DESCRIPTION", "Sets the size of a remote file over SFTP, cutting it or extending it with zero bytes"},
        {"com.wm.adapter.wxssh.service.SftpTruncateService.SFTP Truncate Settings.GROUPNAME", "SFTP Truncate Settings"},
        {"com.wm.adapter.wxssh.service.SftpTruncateService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.SftpTruncateService.defaultRemotePath.DESCRIPTION", "File to resize, used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.SftpTruncateService.defaultSize.DISPLAYNAME", "Default Size (bytes)"},
        {"com.wm.adapter.wxssh.service.SftpTruncateService.defaultSize.DESCRIPTION", "New size in bytes, used when the size input is empty (required: an empty size is never read as 0; send 0 to empty the file)"},

        // RunShellScriptService
        {"com.wm.adapter.wxssh.service.RunShellScriptService.DISPLAYNAME", "RunShellScript"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.DESCRIPTION", "Drives an interactive remote shell: sends the lines of a script one at a time and waits for the prompt after each. A last resort when command execution is not available; depends on recognising the prompt"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.Shell Script Settings.GROUPNAME", "Shell Script Settings"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.defaultScript.DISPLAYNAME", "Default Script"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.defaultScript.DESCRIPTION", "Lines to send, one step per line, used when the script input is empty (required: there is no default). Blank lines inside the script are sent as an empty Enter; blank lines at the end are dropped"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.promptPattern.DISPLAYNAME", "Prompt Pattern (regular expression)"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.promptPattern.DESCRIPTION", "Java regular expression that recognises the shell prompt at the end of the output, for example [$#>%] $ . Used when the promptPattern input is empty. It is searched, not matched against the whole output"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.timeoutSeconds.DISPLAYNAME", "Timeout per Step (seconds)"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.timeoutSeconds.DESCRIPTION", "Longest wait for the prompt at the first prompt and after each line, not for the whole script; 0 waits without a deadline. Default 30. A timeout stops the script and is reported in the output, not thrown"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.allocatePty.DISPLAYNAME", "Allocate PTY"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.allocatePty.DESCRIPTION", "true (default) requests a pseudo-terminal; a shell prints its prompt only on a terminal. With a terminal the server merges standard error into the normal output. The allocatePty input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.waitForFirstPrompt.DISPLAYNAME", "Wait for First Prompt"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.waitForFirstPrompt.DESCRIPTION", "true (default) waits for a prompt (after the banner or login messages) before sending the first line; false sends it at once. The waitForFirstPrompt input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.lineEnding.DISPLAYNAME", "Line Ending"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.lineEnding.DESCRIPTION", "What is sent after each line: LF (default), CR (the Enter key of a terminal; many network devices) or CRLF. The lineEnding input, when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.charset.DISPLAYNAME", "Character Set"},
        {"com.wm.adapter.wxssh.service.RunShellScriptService.charset.DESCRIPTION", "Character set for the script lines and the received text. Default UTF-8. An unknown name is rejected. The charset input, when not empty, overrides this"},

        // StartSubsystemService
        {"com.wm.adapter.wxssh.service.StartSubsystemService.DISPLAYNAME", "StartSubsystem"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.DESCRIPTION", "Starts a named SSH subsystem on the server (for example netconf), sends it one request and returns its answer"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.Subsystem Settings.GROUPNAME", "Subsystem Settings"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.defaultSubsystem.DISPLAYNAME", "Default Subsystem"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.defaultSubsystem.DESCRIPTION", "Subsystem name as the server knows it, used when the subsystem input is empty (required: there is no default). The server must offer it, otherwise the call fails with CHANNEL_FAILED"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.defaultInput.DISPLAYNAME", "Default Input"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.defaultInput.DESCRIPTION", "Text sent to the subsystem right after it starts, used when the input input is empty and no inputBytes are mapped. Empty sends nothing"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.expect.DISPLAYNAME", "Expect Pattern (regular expression)"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.expect.DESCRIPTION", "Marks the end of the answer, for example \\]\\]>\\]\\]> for NETCONF 1.0; matched against the bytes one-to-one, so use ASCII. Empty (default): the input is followed by an end-of-input signal and the answer is read until the server closes the channel. The expect input, when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.timeoutSeconds.DISPLAYNAME", "Timeout (seconds)"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.timeoutSeconds.DESCRIPTION", "Longest wait for the end of the answer; 0 waits without a deadline. Default 30. A timeout is reported in the output (timedOut), not thrown"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.charset.DISPLAYNAME", "Character Set"},
        {"com.wm.adapter.wxssh.service.StartSubsystemService.charset.DESCRIPTION", "Character set for the input text and for the output field. Default UTF-8. outputBytes is always the raw answer. The charset input, when not empty, overrides this"},

        // ScpUploadService
        {"com.wm.adapter.wxssh.service.ScpUploadService.DISPLAYNAME", "ScpUpload"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.DESCRIPTION", "Uploads a local file, a directory tree, or content from the pipeline with SCP. For servers that offer SCP but not SFTP; prefer SftpPut when SFTP is available. A directory uploaded to an existing destination lands inside it under its own name"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.SCP Upload Settings.GROUPNAME", "SCP Upload Settings"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.defaultRemotePath.DESCRIPTION", "Remote destination used when the remotePath input is empty (required: there is no default). SCP never creates missing parent folders"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.defaultLocalPath.DISPLAYNAME", "Default Local Path"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.defaultLocalPath.DESCRIPTION", "Local file (or directory when Recursive is true) on the Integration Server used when the localFilePath input is empty. When no local path is set, the bytes or content input is uploaded instead"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.recursive.DISPLAYNAME", "Recursive"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.recursive.DESCRIPTION", "true allows a local directory to be uploaded with its whole tree; false (default) refuses a directory. If the remote destination already exists as a directory, the local directory is created inside it under its own name. The recursive input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.charset.DISPLAYNAME", "Character Set"},
        {"com.wm.adapter.wxssh.service.ScpUploadService.charset.DESCRIPTION", "Character set used to turn the content input into bytes. Default UTF-8. An unknown name is rejected. The charset input, when not empty, overrides this"},

        // ScpDownloadService
        {"com.wm.adapter.wxssh.service.ScpDownloadService.DISPLAYNAME", "ScpDownload"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.DESCRIPTION", "Downloads a remote file or directory tree with SCP to a local path, or one file into memory. For servers that offer SCP but not SFTP; prefer SftpGet when SFTP is available. A remote directory is always copied as a whole tree and only to a local path"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.SCP Download Settings.GROUPNAME", "SCP Download Settings"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.defaultRemotePath.DISPLAYNAME", "Default Remote Path"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.defaultRemotePath.DESCRIPTION", "Remote file or directory used when the remotePath input is empty (required: there is no default)"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.defaultLocalPath.DISPLAYNAME", "Default Local Path"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.defaultLocalPath.DESCRIPTION", "Local destination on the Integration Server used when the localFilePath input is empty. When no local path is set, the file is returned in memory as bytes and content, limited by the connection's in-memory limit. If it exists as a directory, the remote file is written inside it"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.recursive.DISPLAYNAME", "Recursive"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.recursive.DESCRIPTION", "true allows a remote directory to be downloaded with its whole tree; false (default) refuses a directory (the server answers 'not a regular file') and creates nothing locally. A tree can only be downloaded to a local path, not into memory. If the local destination already exists as a directory, the remote directory is created inside it under its own name. The recursive input (true/false), when not empty, overrides this"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.charset.DISPLAYNAME", "Character Set"},
        {"com.wm.adapter.wxssh.service.ScpDownloadService.charset.DESCRIPTION", "Character set used to decode the downloaded bytes into the content output. Default UTF-8. An unknown name is rejected. The charset input, when not empty, overrides this"},

        // RemoteDirectoryPollNotification
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.DISPLAYNAME", "RemoteDirectoryPoll"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.DESCRIPTION", "Watches a directory on the SSH/SFTP server and publishes one document per new file. Files younger than the minimum age, or still changing, are left alone; delivered files can be left, deleted, renamed or moved. Delivery is at least once. Enable it on one Integration Server node only: the record of delivered files is local to the node"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.Remote Directory Poll Settings.GROUPNAME", "Remote Directory Poll Settings"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.directory.DISPLAYNAME", "Directory"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.directory.DESCRIPTION", "Remote directory to watch (required), for example /in"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.filter.DISPLAYNAME", "File Name Filter"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.filter.DESCRIPTION", "Glob on the file name, for example *.csv or {a,b}*.xml. Empty means all files"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.recursive.DISPLAYNAME", "Recursive"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.recursive.DESCRIPTION", "true also watches sub-directories (links are never entered). Default false"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.minAgeSeconds.DISPLAYNAME", "Minimum Age (seconds)"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.minAgeSeconds.DESCRIPTION", "A file is only taken when its modification time is at least this many seconds in the past, to avoid files still being uploaded. Compared with this machine's clock, so keep the clocks in step or rely on the stability check. Default 0"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.stabilityCheck.DISPLAYNAME", "Stability Check"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.stabilityCheck.DESCRIPTION", "true (default) takes a file only when its size and modification time are unchanged on two consecutive polls. This does not depend on the two clocks agreeing"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.postAction.DISPLAYNAME", "After Delivery"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.postAction.DESCRIPTION", "NONE (default) leaves the file; DELETE removes it; RENAME appends the Action Argument to its name; MOVE moves it into the directory named by the Action Argument (created if missing, an existing file there is never overwritten). A failed action is logged and the file is not delivered twice"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.postActionArg.DISPLAYNAME", "Action Argument"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.postActionArg.DESCRIPTION", "Suffix such as .done for RENAME, or a remote directory such as /archive for MOVE. Do not move files into the watched directory when Recursive is true"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.sortOrder.DISPLAYNAME", "Sort Order"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.sortOrder.DESCRIPTION", "Order in which ready files are delivered: NAME (default), OLDEST_FIRST or NEWEST_FIRST"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.maxFilesPerPoll.DISPLAYNAME", "Max Files Per Poll"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.maxFilesPerPoll.DESCRIPTION", "At most this many files are delivered per run; the rest follow on the next runs. Default 100"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.includeContent.DISPLAYNAME", "Include Content"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.includeContent.DESCRIPTION", "true adds the file content to the document as contentBytes and, decoded with the character set, as content. Default false. Large files should be fetched in the flow instead"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.maxContentBytes.DISPLAYNAME", "Max Content Bytes"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.maxContentBytes.DESCRIPTION", "Files larger than this are delivered without content and with contentSkipped=true. Default 1048576. The connection's in-memory limit also applies; a file above it cannot be read and is retried on every run"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.charset.DISPLAYNAME", "Character Set"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.charset.DESCRIPTION", "Character set used to decode the content field. Default UTF-8"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.stateFilePath.DISPLAYNAME", "State File"},
        {"com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification.stateFilePath.DESCRIPTION", "Local file that remembers which files were delivered, so a restart does not deliver them again. Empty means <Integration Server>/packages/WxSshAdapter/config/poll-state/<notification name>.state. Do not share one file between notifications"}
    };

    @Override
    public Object[][] getContents() {
        return CONTENTS;
    }
}
