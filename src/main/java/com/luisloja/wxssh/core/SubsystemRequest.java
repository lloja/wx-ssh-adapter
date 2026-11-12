package com.luisloja.wxssh.core;

/**
 * Input for {@link SshSession#runSubsystem}: start a named SSH subsystem, send it bytes, read what it answers.
 *
 * @param name          subsystem name as the server knows it, for example {@code netconf}; required
 * @param input         bytes to send right after the subsystem starts; null or empty sends nothing
 * @param expectPattern Java regular expression marking the end of the answer, searched with {@code find()} over a
 *                      one-to-one (ISO-8859-1) view of the bytes, for example {@code \]\]>\]\]>}; null or blank
 *                      means "no marker": the input is followed by an end-of-input signal and the answer is read
 *                      until the server closes the channel
 * @param timeoutMs     longest wait for the marker or the end of the channel; 0 waits without a deadline
 */
public record SubsystemRequest(String name, byte[] input, String expectPattern, long timeoutMs) {
}
