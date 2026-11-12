package com.luisloja.wxssh.core;

/**
 * Outcome of {@link SshSession#runSubsystem}. A timeout is reported as data so the caller keeps the bytes received.
 *
 * @param output         everything received from the subsystem, up to the marker (inclusive) when one was given
 * @param matched        true when the marker was seen
 * @param closedByServer true when the server ended the channel
 * @param timedOut       true when neither the marker nor the end of the channel came in time
 * @param truncated      true when output beyond the in-memory limit was discarded
 * @param exitStatus     the subsystem's exit status when the server reported one, otherwise null
 */
public record SubsystemResult(byte[] output, boolean matched, boolean closedByServer, boolean timedOut,
                              boolean truncated, Integer exitStatus) {
}
