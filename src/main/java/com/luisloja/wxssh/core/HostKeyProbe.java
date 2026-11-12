package com.luisloja.wxssh.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

/**
 * Fetches a server's host key and fingerprint <em>without authenticating</em>.
 *
 * <p>Why: this supports a safe first-time-trust workflow. An operator runs the probe, compares the fingerprint
 * out-of-band with the server administrator, and then pins it in the connection configuration.
 *
 * <p>Side effects: opens a TCP connection and performs the SSH key exchange, then disconnects. No credentials are
 * sent. The key is captured but deliberately <em>accepted</em> for the probe only; it is never stored.
 */
public final class HostKeyProbe {

    private HostKeyProbe() {
    }

    /**
     * Result of a probe.
     *
     * @param algorithm   key algorithm name as offered by the server (for example {@code ssh-ed25519})
     * @param fingerprint OpenSSH-style SHA-256 fingerprint, {@code SHA256:<unpadded base64>}
     * @param publicKey   the key itself, needed to write a {@code known_hosts} line; public information
     */
    public record Result(String algorithm, String fingerprint, PublicKey publicKey) {

        /**
         * The Base64 text that follows the algorithm name in a {@code known_hosts} line: the SSH wire encoding of
         * the key. It is what {@code ssh-keyscan} prints.
         *
         * @return standard (padded) Base64 of the wire encoding
         */
        public String wireBase64() {
            try {
                return Base64.getEncoder().encodeToString(new Buffer.PlainBuffer().putPublicKey(publicKey).getCompactData());
            } catch (RuntimeException e) {
                throw new IllegalStateException("Host key cannot be encoded", e);
            }
        }

        /**
         * The host part of a {@code known_hosts} line as OpenSSH writes it: the plain host name for port 22,
         * {@code [host]:port} otherwise.
         *
         * @param host target host name
         * @param port target port
         * @return the host pattern
         */
        public static String hostPattern(String host, int port) {
            return port == 22 ? host : "[" + host + "]:" + port;
        }

        /**
         * A complete, unhashed {@code known_hosts} line for this key.
         *
         * @param host target host name
         * @param port target port
         * @return {@code <host pattern> <algorithm> <base64>}
         */
        public String knownHostsLine(String host, int port) {
            return hostPattern(host, port) + " " + algorithm + " " + wireBase64();
        }
    }

    /**
     * Connects, captures the host key, and disconnects.
     *
     * @param host             target host
     * @param port             target port
     * @param connectTimeoutMs TCP connect timeout in milliseconds
     * @return the offered key algorithm and SHA-256 fingerprint
     * @throws SshAdapterException {@link ErrorCode#CONNECT_FAILED} if the handshake fails
     */
    public static Result probe(String host, int port, int connectTimeoutMs) throws SshAdapterException {
        AtomicReference<PublicKey> captured = new AtomicReference<>();
        HostKeyVerifier capture = new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int p, PublicKey key) {
                captured.set(key);
                return true;
            }

            @Override
            public List<String> findExistingAlgorithms(String hostname, int p) {
                return List.of();
            }
        };
        try (SSHClient client = new SSHClient()) {
            client.addHostKeyVerifier(capture);
            client.setConnectTimeout(connectTimeoutMs);
            client.connect(host, port);
        } catch (IOException e) {
            if (captured.get() == null) {
                throw new SshAdapterException(ErrorCode.CONNECT_FAILED,
                        "Host key probe failed for " + host + ":" + port + ": " + e.getMessage(), e);
            }
        }
        PublicKey key = captured.get();
        if (key == null) {
            throw new SshAdapterException(ErrorCode.CONNECT_FAILED,
                    "Server " + host + ":" + port + " did not present a host key", null);
        }
        return new Result(KeyType.fromKey(key).toString(), sha256Fingerprint(key), key);
    }

    /**
     * Computes the OpenSSH-style SHA-256 fingerprint of a public key.
     *
     * <p>The digest is taken over the SSH wire encoding of the key (algorithm name + key blob), which is what
     * {@code ssh-keygen -l} hashes.
     *
     * @param key public key
     * @return {@code SHA256:} followed by unpadded Base64
     */
    public static String sha256Fingerprint(PublicKey key) {
        try {
            byte[] wire = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(wire);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
