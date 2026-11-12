package com.luisloja.wxssh.core;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Input for {@link SshSession#runShell}: an interactive shell driven line by line, waiting for a prompt after each.
 *
 * @param lines             the lines to send, in order, without line endings; at least one
 * @param promptPattern     Java regular expression that recognises the shell prompt in the output; required. It
 *                          is searched with {@code find()}, not {@code matches()}, so it need only match the end
 *                          of the output, for example {@code [$#>] $}. Add flags yourself, for example
 *                          {@code (?m)}
 * @param stepTimeoutMs     longest wait for the prompt after the first prompt wait and after each line; 0 waits
 *                          without a deadline
 * @param allocatePty       whether to request a pseudo-terminal. A shell prints its prompt only on a terminal, so
 *                          prompt-driven scripts almost always need this
 * @param waitForFirstPrompt whether to wait for a prompt (banner, login messages) before sending the first line
 * @param lineEnding        what to append to each line: {@code "\n"}, {@code "\r"} or {@code "\r\n"}
 * @param charset           how to turn the received bytes into text and the lines into bytes; null means UTF-8
 */
public record ShellRequest(List<String> lines, String promptPattern, long stepTimeoutMs, boolean allocatePty,
                           boolean waitForFirstPrompt, String lineEnding, Charset charset) {
}
