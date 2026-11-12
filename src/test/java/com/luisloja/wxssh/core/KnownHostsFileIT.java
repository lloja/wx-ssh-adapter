package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the first-time-trust workflow: probe a server, write its key into a {@code known_hosts} file, then
 * connect with the {@code KNOWN_HOSTS} policy.
 *
 * <p>Why against a real connection: the only proof that the line we write is the line the verifier wants is that a
 * session using that file connects (and one using a file with a different key does not).
 */
class KnownHostsFileIT {

    @TempDir
    Path tmp;

    private TestSshServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestSshServer(Files.createDirectories(tmp.resolve("remote-root")), tmp.resolve("hostkey.ser"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private SshConnectionConfig.Builder withKnownHosts(Path file) {
        return SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.KNOWN_HOSTS).knownHostsPath(file.toString())
                .connectTimeoutMs(5_000).socketTimeoutMs(20_000);
    }

    @Test
    void aProbedKeyWrittenToANewFileLetsTheKnownHostsPolicyConnect() throws Exception {
        Path file = tmp.resolve("dot-ssh").resolve("known_hosts"); // parent folder does not exist yet
        HostKeyProbe.Result probed = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);

        KnownHostsFile.AddResult r = KnownHostsFile.add(file, "127.0.0.1", server.port(), probed);

        assertTrue(r.added());
        assertEquals("[127.0.0.1]:" + server.port() + " " + probed.algorithm() + " " + probed.wireBase64(),
                r.line());
        assertEquals(List.of(r.line()), Files.readAllLines(file));
        try (SshSession s = SshSession.connect(withKnownHosts(file).build())) {
            assertEquals(SshConnectionConfig.DEFAULT_MAX_CONCURRENT_CHANNELS, s.availableChannelPermits());
        }
    }

    @Test
    void addingTheSameKeyAgainChangesNothing() throws Exception {
        Path file = tmp.resolve("known_hosts");
        HostKeyProbe.Result probed = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);
        KnownHostsFile.add(file, "127.0.0.1", server.port(), probed);
        String before = Files.readString(file);

        KnownHostsFile.AddResult again = KnownHostsFile.add(file, "127.0.0.1", server.port(), probed);

        assertFalse(again.added());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void existingLinesAreKeptAndMissingFinalNewlineIsRepaired() throws Exception {
        Path file = tmp.resolve("known_hosts");
        Files.writeString(file, "# my servers\nother.example ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEBOGUSBOGUSBOGUSBOGUSBOGUSBOGUSBOGUSBOGUSBOGUS");
        HostKeyProbe.Result probed = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);

        KnownHostsFile.add(file, "127.0.0.1", server.port(), probed);

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals("# my servers", lines.get(0));
        assertTrue(lines.get(1).startsWith("other.example "));
        assertTrue(lines.get(2).startsWith("[127.0.0.1]:"));
    }

    @Test
    void aDifferentKeyOfTheSameAlgorithmIsRefusedAndTheFileIsNotTouched() throws Exception {
        Path file = tmp.resolve("known_hosts");
        HostKeyProbe.Result real = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);
        // Same algorithm, different key: a stand-in for a changed or impersonated server.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PublicKey other = gen.generateKeyPair().getPublic();
        HostKeyProbe.Result impostor = new HostKeyProbe.Result(real.algorithm(), "SHA256:other", other);
        Files.writeString(file, impostor.knownHostsLine("127.0.0.1", server.port()) + "\n");
        String before = Files.readString(file);

        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> KnownHostsFile.add(file, "127.0.0.1", server.port(), real));

        assertEquals(ErrorCode.HOST_KEY_REJECTED, e.getCode());
        assertTrue(e.getMessage().contains("different"), e.getMessage());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void aFileWithAnotherKeyDoesNotLetTheSessionConnect() throws Exception {
        Path file = tmp.resolve("known_hosts");
        HostKeyProbe.Result real = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        HostKeyProbe.Result impostor = new HostKeyProbe.Result(real.algorithm(), "SHA256:other",
                gen.generateKeyPair().getPublic());
        Files.writeString(file, impostor.knownHostsLine("127.0.0.1", server.port()) + "\n");
        assertTrue(real.fingerprint().startsWith("SHA256:"));

        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(withKnownHosts(file).build()));
        assertEquals(ErrorCode.HOST_KEY_REJECTED, e.getCode());
    }

    @Test
    void invalidArgumentsAreRejected() throws Exception {
        HostKeyProbe.Result probed = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);
        assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                () -> KnownHostsFile.add(tmp.resolve("kh"), " ", 22, probed)).getCode());
        assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                () -> KnownHostsFile.add(tmp.resolve("kh"), "h", 0, probed)).getCode());
        assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                () -> KnownHostsFile.add(null, "h", 22, probed)).getCode());
    }

    @Test
    void port22UsesThePlainHostName() {
        assertEquals("example.org", HostKeyProbe.Result.hostPattern("example.org", 22));
        assertEquals("[example.org]:2222", HostKeyProbe.Result.hostPattern("example.org", 2222));
    }
}
