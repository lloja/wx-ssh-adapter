package com.luisloja.wxssh.core;

/**
 * One entry returned by an SFTP directory listing or stat call.
 *
 * @param name        file name without directory
 * @param path        full remote path
 * @param size        size in bytes
 * @param modifiedSec last-modified time in seconds since the epoch
 * @param permissions POSIX permission bits (for example {@code 0644} as decimal 420)
 * @param type        {@link Type} of the entry
 * @param accessedSec last-access time in seconds since the epoch; {@code 0} when the server did not send it
 * @param uid         numeric owner id (SFTP v3 carries ids only, never names); {@code 0} when not sent
 * @param gid         numeric group id (SFTP v3 carries ids only, never names); {@code 0} when not sent
 */
public record RemoteEntry(String name, String path, long size, long modifiedSec, int permissions, Type type,
                          long accessedSec, int uid, int gid) {

    /** Coarse file type. */
    public enum Type { FILE, DIRECTORY, SYMLINK, OTHER }
}
