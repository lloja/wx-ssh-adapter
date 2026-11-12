package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wm.adapter.wxssh.admin.Service;
import com.wm.app.b2b.server.ServiceException;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataFactory;
import com.wm.data.IDataUtil;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the two Integration Server host-key services ({@code getHostKeyFingerprint}, {@code addToKnownHosts})
 * that sit on top of {@link HostKeyProbe} and {@link KnownHostsFile}.
 *
 * <p>Why in the {@code core} test package: the embedded {@link TestSshServer} is package-private here. The
 * production {@code core} code still has no ADK imports; only this test reaches across.
 */
class HostKeyAdminServicesIT {

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

    private static IData pipeline(String... kv) {
        IData p = IDataFactory.create();
        IDataCursor c = p.getCursor();
        for (int i = 0; i < kv.length; i += 2) {
            IDataUtil.put(c, kv[i], kv[i + 1]);
        }
        c.destroy();
        return p;
    }

    private static String get(IData p, String key) {
        IDataCursor c = p.getCursor();
        try {
            Object v = IDataUtil.get(c, key);
            return v == null ? null : v.toString();
        } finally {
            c.destroy();
        }
    }

    @Test
    void getHostKeyFingerprintReportsWhatTheServerPresents() throws Exception {
        String port = String.valueOf(server.port());
        IData p = pipeline("host", "127.0.0.1", "port", port);

        Service.getHostKeyFingerprint(p);

        HostKeyProbe.Result expected = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);
        assertEquals(expected.algorithm(), get(p, "algorithm"));
        assertEquals(expected.fingerprint(), get(p, "fingerprint"));
        assertEquals(expected.knownHostsLine("127.0.0.1", server.port()), get(p, "knownHostsEntry"));
        assertTrue(get(p, "fingerprint").startsWith("SHA256:"));
    }

    @Test
    void getHostKeyFingerprintRequiresAHostAndAWholeNumberPort() {
        assertThrows(ServiceException.class, () -> Service.getHostKeyFingerprint(pipeline()));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> Service.getHostKeyFingerprint(pipeline("host", "127.0.0.1", "port", "twenty")));
        assertTrue(ex.getMessage().contains("port"), ex.getMessage());
    }

    @Test
    void getHostKeyFingerprintReportsAnUnreachableServer() throws Exception {
        int closed = server.port();
        server.close();
        ServiceException ex = assertThrows(ServiceException.class, () -> Service.getHostKeyFingerprint(
                pipeline("host", "127.0.0.1", "port", String.valueOf(closed), "timeoutSeconds", "2")));
        assertTrue(ex.getMessage().startsWith("CONNECT_FAILED"), ex.getMessage());
    }

    @Test
    void addToKnownHostsWritesTheApprovedKeyAndTheKnownHostsPolicyThenConnects() throws Exception {
        Path file = tmp.resolve("dot-ssh").resolve("known_hosts");
        String port = String.valueOf(server.port());
        IData look = pipeline("host", "127.0.0.1", "port", port);
        Service.getHostKeyFingerprint(look);

        IData p = pipeline("host", "127.0.0.1", "port", port, "knownHostsPath", file.toString(),
                "expectedFingerprint", get(look, "fingerprint"));
        Service.addToKnownHosts(p);

        assertEquals("true", get(p, "added"));
        assertEquals(get(look, "knownHostsEntry"), get(p, "entry"));
        assertEquals(file.toAbsolutePath().toString(), get(p, "knownHostsPath"));
        assertEquals(java.util.List.of(get(look, "knownHostsEntry")), Files.readAllLines(file));
        try (SshSession s = SshSession.connect(SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.KNOWN_HOSTS).knownHostsPath(file.toString())
                .connectTimeoutMs(5_000).socketTimeoutMs(20_000).build())) {
            assertTrue(s.availableChannelPermits() > 0);
        }
    }

    @Test
    void addToKnownHostsWritesNothingWhenTheFingerprintIsNotTheApprovedOne() {
        Path file = tmp.resolve("known_hosts");
        IData p = pipeline("host", "127.0.0.1", "port", String.valueOf(server.port()),
                "knownHostsPath", file.toString(), "expectedFingerprint", "SHA256:notTheRealOne");

        ServiceException ex = assertThrows(ServiceException.class, () -> Service.addToKnownHosts(p));

        assertTrue(ex.getMessage().startsWith("HOST_KEY_REJECTED"), ex.getMessage());
        assertFalse(Files.exists(file), "a refused approval must not create the file");
    }

    @Test
    void addToKnownHostsExplainsWhenTheWholeEntryIsPassedInsteadOfTheFingerprint() throws Exception {
        Path file = tmp.resolve("known_hosts");
        String port = String.valueOf(server.port());
        IData look = pipeline("host", "127.0.0.1", "port", port);
        Service.getHostKeyFingerprint(look);

        ServiceException ex = assertThrows(ServiceException.class, () -> Service.addToKnownHosts(
                pipeline("host", "127.0.0.1", "port", port, "knownHostsPath", file.toString(),
                        "expectedFingerprint", get(look, "knownHostsEntry"))));

        assertTrue(ex.getMessage().contains("fingerprint only"), ex.getMessage());
        assertFalse(Files.exists(file));
    }
    @Test
    void addToKnownHostsIsIdempotentAndRequiresAllItsInputs() throws Exception {
        Path file = tmp.resolve("known_hosts");
        String port = String.valueOf(server.port());
        IData look = pipeline("host", "127.0.0.1", "port", port);
        Service.getHostKeyFingerprint(look);
        String fp = get(look, "fingerprint");

        IData first = pipeline("host", "127.0.0.1", "port", port, "knownHostsPath", file.toString(),
                "expectedFingerprint", fp);
        IData second = pipeline("host", "127.0.0.1", "port", port, "knownHostsPath", file.toString(),
                "expectedFingerprint", fp);
        Service.addToKnownHosts(first);
        Service.addToKnownHosts(second);

        assertEquals("true", get(first, "added"));
        assertEquals("false", get(second, "added"));
        assertEquals(1, Files.readAllLines(file).size());
        assertThrows(ServiceException.class, () -> Service.addToKnownHosts(
                pipeline("host", "127.0.0.1", "knownHostsPath", file.toString())));
    }
}
