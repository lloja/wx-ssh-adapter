package com.luisloja.wxssh.core;

/**
 * Stable, documented error categories that the ADK layer maps onto adapter exceptions and that flows can branch on.
 *
 * <p>Why: SSHJ throws a wide mix of transport, connection, user-auth, SFTP and I/O exceptions. Flow developers
 * should not need to know SSHJ's class hierarchy, so every failure is collapsed into one of these codes.
 */
public enum ErrorCode {
    /** Invalid or incomplete connection configuration (detected before any network I/O). */
    INVALID_CONFIG,
    /** TCP connect or SSH handshake failed (host unreachable, refused, protocol error). */
    CONNECT_FAILED,
    /** The server's host key was not accepted by the configured {@link HostKeyPolicy}. */
    HOST_KEY_REJECTED,
    /** The server rejected every authentication attempt. */
    AUTH_FAILED,
    /** An operation exceeded its configured timeout. */
    TIMEOUT,
    /** A channel (session, SFTP, SCP) could not be opened or broke mid-operation. */
    CHANNEL_FAILED,
    /** The SFTP server returned a non-OK status; see {@link SshAdapterException#getSftpStatus()}. */
    SFTP_STATUS,
    /** Local or remote I/O failure not covered above. */
    IO_ERROR,
    /** The requested payload exceeds a configured safety limit (for example the in-memory download cap). */
    LIMIT_EXCEEDED
}
