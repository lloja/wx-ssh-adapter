package com.luisloja.wxssh.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of how to reach and authenticate to one SSH server.
 *
 * <p>Why a plain value object: the ADK connection factory builds one of these from the Designer connection form,
 * so the façade never depends on ADK types and can be exercised in unit tests with no Integration Server.
 *
 * <p>Side effects: none. Secrets (password, passphrase, inline key) are held in memory only and are deliberately
 * excluded from {@link #toString()} so they cannot leak into logs.
 */
public final class SshConnectionConfig {

    private final String host;
    private final int port;
    private final String username;
    private final AuthMethod authMethod;
    private final String password;
    private final String privateKeyPath;
    private final String privateKeyPem;
    private final String keyPassphrase;
    private final HostKeyPolicy hostKeyPolicy;
    private final String knownHostsPath;
    private final List<String> pinnedFingerprints;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;
    private final int keepAliveSeconds;
    private final boolean compression;
    private final long maxInMemoryBytes;
    private final int maxConcurrentChannels;

    private SshConnectionConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.authMethod = b.authMethod;
        this.password = b.password;
        this.privateKeyPath = b.privateKeyPath;
        this.privateKeyPem = b.privateKeyPem;
        this.keyPassphrase = b.keyPassphrase;
        this.hostKeyPolicy = b.hostKeyPolicy;
        this.knownHostsPath = b.knownHostsPath;
        this.pinnedFingerprints = Collections.unmodifiableList(new ArrayList<>(b.pinnedFingerprints));
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.socketTimeoutMs = b.socketTimeoutMs;
        this.keepAliveSeconds = b.keepAliveSeconds;
        this.compression = b.compression;
        this.maxInMemoryBytes = b.maxInMemoryBytes;
        this.maxConcurrentChannels = b.maxConcurrentChannels;
    }

    /**
     * Channels one session may have open at the same time unless configured otherwise. OpenSSH allows 10 session
     * channels per connection by default (MaxSessions), so 8 leaves headroom for the server releasing a closed
     * channel a moment after the client has.
     */
    public static final int DEFAULT_MAX_CONCURRENT_CHANNELS = 8;

    /** @return a new builder with safe defaults (strict host-key checking, 22, 30 s timeouts, 16 MiB memory cap, 8 channels) */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates the configuration before any network I/O so mistakes surface as {@link ErrorCode#INVALID_CONFIG}.
     *
     * @throws SshAdapterException when a required field is missing or inconsistent
     */
    public void validate() throws SshAdapterException {
        if (host == null || host.isBlank()) {
            throw invalid("host is required");
        }
        if (port < 1 || port > 65535) {
            throw invalid("port must be 1..65535");
        }
        if (username == null || username.isBlank()) {
            throw invalid("username is required");
        }
        switch (authMethod) {
            case PASSWORD, KEYBOARD_INTERACTIVE_PASSWORD -> {
                if (password == null) {
                    throw invalid("password is required for " + authMethod);
                }
            }
            case PUBLIC_KEY -> {
                boolean hasPath = privateKeyPath != null && !privateKeyPath.isBlank();
                boolean hasPem = privateKeyPem != null && !privateKeyPem.isBlank();
                if (hasPath == hasPem) {
                    throw invalid("exactly one of privateKeyPath or privateKeyPem is required for PUBLIC_KEY");
                }
            }
        }
        if (hostKeyPolicy == HostKeyPolicy.PINNED_FINGERPRINT && pinnedFingerprints.isEmpty()) {
            throw invalid("at least one pinned fingerprint is required for PINNED_FINGERPRINT");
        }
        if (maxInMemoryBytes < 1) {
            throw invalid("maxInMemoryBytes must be positive");
        }
        if (maxConcurrentChannels < 1) {
            throw invalid("maxConcurrentChannels must be at least 1");
        }
    }

    private static SshAdapterException invalid(String message) {
        return new SshAdapterException(ErrorCode.INVALID_CONFIG, message, null);
    }

    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public AuthMethod authMethod() { return authMethod; }
    public String password() { return password; }
    public String privateKeyPath() { return privateKeyPath; }
    public String privateKeyPem() { return privateKeyPem; }
    public String keyPassphrase() { return keyPassphrase; }
    public HostKeyPolicy hostKeyPolicy() { return hostKeyPolicy; }
    public String knownHostsPath() { return knownHostsPath; }
    public List<String> pinnedFingerprints() { return pinnedFingerprints; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int socketTimeoutMs() { return socketTimeoutMs; }
    public int keepAliveSeconds() { return keepAliveSeconds; }
    public boolean compression() { return compression; }
    public long maxInMemoryBytes() { return maxInMemoryBytes; }
    public int maxConcurrentChannels() { return maxConcurrentChannels; }

    /** Secrets are intentionally omitted. */
    @Override
    public String toString() {
        return "SshConnectionConfig[" + username + "@" + host + ":" + port
                + ", auth=" + authMethod + ", hostKeyPolicy=" + hostKeyPolicy + "]";
    }

    /** Fluent builder for {@link SshConnectionConfig}. */
    public static final class Builder {
        private String host;
        private int port = 22;
        private String username;
        private AuthMethod authMethod = AuthMethod.PASSWORD;
        private String password;
        private String privateKeyPath;
        private String privateKeyPem;
        private String keyPassphrase;
        private HostKeyPolicy hostKeyPolicy = HostKeyPolicy.KNOWN_HOSTS;
        private String knownHostsPath;
        private final List<String> pinnedFingerprints = new ArrayList<>();
        private int connectTimeoutMs = 30_000;
        private int socketTimeoutMs = 30_000;
        private int keepAliveSeconds = 0;
        private boolean compression;
        private long maxInMemoryBytes = 16L * 1024 * 1024;
        private int maxConcurrentChannels = DEFAULT_MAX_CONCURRENT_CHANNELS;

        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder authMethod(AuthMethod v) { this.authMethod = Objects.requireNonNull(v); return this; }
        public Builder password(String v) { this.password = v; return this; }
        public Builder privateKeyPath(String v) { this.privateKeyPath = v; return this; }
        public Builder privateKeyPem(String v) { this.privateKeyPem = v; return this; }
        public Builder keyPassphrase(String v) { this.keyPassphrase = v; return this; }
        public Builder hostKeyPolicy(HostKeyPolicy v) { this.hostKeyPolicy = Objects.requireNonNull(v); return this; }
        public Builder knownHostsPath(String v) { this.knownHostsPath = v; return this; }
        public Builder addPinnedFingerprint(String v) { this.pinnedFingerprints.add(v); return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder socketTimeoutMs(int v) { this.socketTimeoutMs = v; return this; }
        /** @param v keep-alive interval in seconds; 0 disables */
        public Builder keepAliveSeconds(int v) { this.keepAliveSeconds = v; return this; }
        public Builder compression(boolean v) { this.compression = v; return this; }
        public Builder maxInMemoryBytes(long v) { this.maxInMemoryBytes = v; return this; }
        /**
         * @param v most exec / SFTP / SCP operations that may hold a channel at once on one session; further
         *          callers wait (up to the socket timeout) instead of being refused by the server. Set it at or
         *          below the server's limit (OpenSSH MaxSessions, default 10), which cannot be read over SSH.
         */
        public Builder maxConcurrentChannels(int v) { this.maxConcurrentChannels = v; return this; }

        /** @return the immutable configuration (call {@link SshConnectionConfig#validate()} before use) */
        public SshConnectionConfig build() { return new SshConnectionConfig(this); }
    }
}
