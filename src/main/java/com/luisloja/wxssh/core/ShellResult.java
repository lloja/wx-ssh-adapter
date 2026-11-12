package com.luisloja.wxssh.core;

import java.util.List;

/**
 * Outcome of {@link SshSession#runShell}. Running out of time or being cut off by the server is reported here as
 * data, not thrown, so the caller still gets everything that was received.
 *
 * @param transcript     all output received, in order, including banner, echo and prompts
 * @param steps          one entry per line that was sent (fewer than the script when it stopped early)
 * @param stderr         what the shell wrote to its error stream (usually empty with a pseudo-terminal, which
 *                       merges the streams)
 * @param completed      true when every line was sent and the last one ended with the prompt or with the server
 *                       closing the channel
 * @param timedOut       true when a prompt did not arrive in time; the script stopped there
 * @param closedByServer true when the shell ended on its own (an {@code exit} line, a dropped login)
 * @param truncated      true when output beyond the in-memory limit was discarded; the script stopped there
 */
public record ShellResult(String transcript, List<ShellStep> steps, String stderr, boolean completed,
                          boolean timedOut, boolean closedByServer, boolean truncated) {
}
