package com.luisloja.wxssh.core;

/**
 * Policy used to decide whether to trust the server's host key.
 *
 * <p>Why explicit: host-key checking is what protects against man-in-the-middle attacks. The default is strict;
 * disabling verification is possible only by choosing {@link #ACCEPT_ANY}, which is intended for development.
 */
public enum HostKeyPolicy {
    /** Trust only keys present in a {@code known_hosts} file. */
    KNOWN_HOSTS,
    /** Trust only a key whose SHA-256 fingerprint ({@code SHA256:...}) equals one of the pinned fingerprints. */
    PINNED_FINGERPRINT,
    /** Trust any key. Insecure; development only. A warning is logged on every connect. */
    ACCEPT_ANY
}
