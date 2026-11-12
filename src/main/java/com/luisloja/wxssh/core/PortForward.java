package com.luisloja.wxssh.core;

/**
 * Immutable description of one active port forward owned by an {@link SshSession}.
 *
 * <p>Why: a forward is a long-lived resource (a listening socket on one side of the tunnel). Flow services need a
 * stable handle to list and cancel it later, and need the <em>actual</em> bound port when the caller asked for an
 * ephemeral one (port 0).
 *
 * @param id         opaque handle returned to the caller; pass it to {@link SshSession#closeForward(String)}
 * @param kind       direction of the tunnel
 * @param bindHost   interface the listener is bound to (local machine for {@link Kind#LOCAL}, SSH server for
 *                   {@link Kind#REMOTE})
 * @param boundPort  port actually bound; never 0
 * @param targetHost where connections arriving at the listener are sent (reachable from the SSH server for
 *                   {@link Kind#LOCAL}, from the adapter host for {@link Kind#REMOTE})
 * @param targetPort port on {@code targetHost}
 */
public record PortForward(String id, Kind kind, String bindHost, int boundPort, String targetHost, int targetPort) {

    /** Direction of a forward, using OpenSSH's {@code -L} / {@code -R} naming. */
    public enum Kind {
        /** Listener on the adapter host; traffic exits from the SSH server ({@code ssh -L}). */
        LOCAL,
        /** Listener on the SSH server; traffic exits from the adapter host ({@code ssh -R}). */
        REMOTE
    }
}
