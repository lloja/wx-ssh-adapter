package com.luisloja.wxssh.core;

import java.util.Map;

/**
 * Input for a remote command execution.
 *
 * @param command     command line passed to the remote shell; required
 * @param stdin       bytes written to the command's standard input, or null for none
 * @param timeoutMs   maximum time to wait for completion; 0 means wait indefinitely
 * @param allocatePty whether to request a pseudo-terminal (merges some servers' stderr into stdout)
 * @param environment environment variables to request (servers commonly refuse most of them); may be null
 */
public record ExecRequest(String command, byte[] stdin, long timeoutMs, boolean allocatePty,
                          Map<String, String> environment) {

    /** Convenience factory for the common case: a command with a timeout, no stdin, no PTY, no environment. */
    public static ExecRequest of(String command, long timeoutMs) {
        return new ExecRequest(command, null, timeoutMs, false, Map.of());
    }
}
