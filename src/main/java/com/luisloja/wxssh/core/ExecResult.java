package com.luisloja.wxssh.core;

/**
 * Outcome of a remote command execution.
 *
 * <p>A non-zero exit status is data, not an error: the caller decides whether it is fatal.
 *
 * @param stdout     captured standard output decoded as UTF-8
 * @param stderr     captured standard error decoded as UTF-8
 * @param exitStatus the command's exit status, or null when the remote side ended without one (for example killed)
 * @param exitSignal the terminating signal name when the command was killed by a signal, otherwise null
 * @param timedOut   true when the timeout elapsed first; output captured so far is still returned
 * @param truncated  true when stdout or stderr exceeded the in-memory cap and the excess was discarded
 */
public record ExecResult(String stdout, String stderr, Integer exitStatus, String exitSignal,
                         boolean timedOut, boolean truncated) {
}
