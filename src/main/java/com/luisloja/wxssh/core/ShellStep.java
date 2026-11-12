package com.luisloja.wxssh.core;

/**
 * What one line of a shell script produced.
 *
 * @param input   the line that was sent, without its line ending
 * @param output  everything received from just after the previous step up to and including this step's prompt.
 *                With a pseudo-terminal this starts with the server's echo of {@code input} and uses {@code \r\n}
 *                line breaks
 * @param matched true when the prompt was seen; false when the server closed the channel (for example after an
 *                {@code exit} line) or time ran out first
 */
public record ShellStep(String input, String output, boolean matched) {
}
