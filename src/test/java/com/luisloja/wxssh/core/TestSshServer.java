package com.luisloja.wxssh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * Embedded Apache MINA SSHD server used by the integration tests.
 *
 * <p>Why: gives tests a real SSH handshake, real SFTP and real SCP without Docker or a remote host. The server
 * is rooted in a temp directory so file operations are isolated and cleaned up by JUnit.
 *
 * <p>Side effects: binds an ephemeral localhost port; generates a host key under the supplied directory.
 * Remote command execution is handled by a tiny in-process interpreter (not a real shell) so tests are portable.
 */
final class TestSshServer implements AutoCloseable {

    static final String USER = "tester";
    static final String PASSWORD = "s3cret";

    private final SshServer server;
    private volatile PublicKey authorizedKey;
    private final Path root;

    TestSshServer(Path root, Path hostKeyFile) throws IOException {
        this.root = root;
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));
        server.setPasswordAuthenticator((u, p, s) -> USER.equals(u) && PASSWORD.equals(p));
        server.setPublickeyAuthenticator((u, key, s) -> USER.equals(u) && authorizedKey != null
                && authorizedKey.equals(key));
        List<SubsystemFactory> subsystems = new ArrayList<>();
        subsystems.add(new SftpSubsystemFactory());
        subsystems.addAll(FakeSubsystem.factories());
        server.setSubsystemFactories(subsystems);
        server.setShellFactory(FakeInteractiveShell.FACTORY);
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.setCommandFactory(new ScpCommandFactory.Builder()
                .withDelegate((channel, command) -> new FakeShellCommand(command))
                .build());
        server.start();
    }

    /**
     * Makes the server refuse any channel beyond {@code max} open ones on a connection, the way OpenSSH's
     * {@code MaxSessions} does. Why: lets a test prove that the façade's channel limiter keeps within a server
     * limit instead of relying on a real sshd. Takes effect for channels opened after the call.
     */
    void limitChannels(int max) {
        CoreModuleProperties.MAX_CONCURRENT_CHANNELS.set(server, max);
    }

    /** Authorises exactly this public key for public-key authentication. */
    void authorize(PublicKey key) {
        this.authorizedKey = key;
    }

    int port() {
        return server.getPort();
    }

    Path root() {
        return root;
    }

    /** @return the SHA-256 fingerprint of the server's host key, computed independently of the façade's probe */
    String hostKeyFingerprint() throws Exception {
        KeyPairProvider provider = server.getKeyPairProvider();
        PublicKey key = provider.loadKeys(null).iterator().next().getPublic();
        return HostKeyProbe.sha256Fingerprint(key);
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }

    /** Writes a file under the server root for tests that need pre-existing remote content. */
    Path seed(String relative, String content) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }
}
