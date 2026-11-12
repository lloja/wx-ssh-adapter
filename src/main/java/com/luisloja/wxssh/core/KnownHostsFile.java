package com.luisloja.wxssh.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;

/**
 * Adds a server's host key to an OpenSSH-format {@code known_hosts} file, so that a connection using the
 * {@code KNOWN_HOSTS} policy trusts it from then on.
 *
 * <p>Why: it completes the first-time-trust workflow started by {@link HostKeyProbe}. The operator looks at the
 * fingerprint, checks it with the server's administrator, and only then lets the adapter write it down. The caller
 * passes the key it just probed (and checked) here, so what is written is exactly what was approved.
 *
 * <p>Side effects: reads the file if it exists; creates the file and its parent folders if not; appends one line.
 * Existing lines are never changed or removed. Hashed entries ({@code |1|...}) are understood when checking.
 */
public final class KnownHostsFile {

    private KnownHostsFile() {
    }

    /**
     * Outcome of {@link #add}.
     *
     * @param added whether a line was written; false when the very same key was already listed for the host
     * @param line  the {@code known_hosts} line for the key, written or already present
     */
    public record AddResult(boolean added, String line) {
    }

    /**
     * Appends the key to the file unless it is already there.
     *
     * <p>A conflicting entry is a refusal, not an overwrite: if the file already lists a <em>different</em> key of
     * the same algorithm for this host, the new key could be an attack or a legitimate rotation, and only a human
     * can tell which, so the old line has to be removed by hand first.
     *
     * @param file  the {@code known_hosts} file; created when missing
     * @param host  host name or address as used in the connection
     * @param port  SSH port
     * @param probe the key to record, from {@link HostKeyProbe#probe}
     * @return whether a line was added, and the line
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path or host;
     *                             {@link ErrorCode#HOST_KEY_REJECTED} when a different key of the same algorithm is
     *                             already listed for the host; {@link ErrorCode#IO_ERROR} when the file cannot be
     *                             read or written
     */
    public static AddResult add(Path file, String host, int port, HostKeyProbe.Result probe)
            throws SshAdapterException {
        if (file == null || host == null || host.isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "known_hosts path and host are required", null);
        }
        if (port < 1 || port > 65535) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    "port must be between 1 and 65535 but was " + port, null);
        }
        String line = probe.knownHostsLine(host, port);
        try {
            boolean needsNewline = false;
            if (Files.exists(file)) {
                OpenSSHKnownHosts known = new OpenSSHKnownHosts(file.toFile());
                if (known.verify(host, port, probe.publicKey())) {
                    return new AddResult(false, line);
                }
                if (known.findExistingAlgorithms(host, port).stream()
                        .anyMatch(a -> a.equalsIgnoreCase(probe.algorithm()))) {
                    throw new SshAdapterException(ErrorCode.HOST_KEY_REJECTED,
                            file + " already lists a different " + probe.algorithm() + " key for "
                                    + HostKeyProbe.Result.hostPattern(host, port)
                                    + ". The server key may have changed, or this may be an attack; remove the old"
                                    + " line by hand once you have checked, then add again.", null);
                }
                needsNewline = endsWithoutNewline(file);
            } else if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            Files.writeString(file, (needsNewline ? "\n" : "") + line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new AddResult(true, line);
        } catch (IOException e) {
            throw new SshAdapterException(ErrorCode.IO_ERROR, "cannot update " + file + ": " + e.getMessage(), e);
        }
    }

    /** True when the file has content and its last byte is not a line feed. */
    private static boolean endsWithoutNewline(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) {
            return false;
        }
        try (var ch = Files.newByteChannel(file)) {
            ch.position(size - 1);
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one);
            return one.get(0) != '\n';
        }
    }
}
