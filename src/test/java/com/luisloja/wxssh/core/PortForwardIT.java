package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for local and remote port forwarding against the embedded SSH server.
 *
 * <p>Why: forwarding owns sockets and threads whose lifetime is tied to the session, so leaks and teardown are
 * the interesting failure modes. Each test pushes real TCP bytes through the tunnel to an in-process echo server
 * instead of only checking that a port was bound.
 */
class PortForwardIT {

    @TempDir
    Path tmp;

    private TestSshServer server;
    private EchoServer echo;

    @BeforeEach
    void start() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("remote-root"));
        server = new TestSshServer(root, tmp.resolve("hostkey.ser"));
        echo = new EchoServer();
    }

    @AfterEach
    void stop() throws Exception {
        echo.close();
        server.close();
    }

    private SshSession open() throws Exception {
        return SshSession.connect(SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.ACCEPT_ANY)
                .connectTimeoutMs(5_000).socketTimeoutMs(10_000).build());
    }

    @Test
    void localForwardCarriesTrafficToTarget() throws Exception {
        try (SshSession s = open()) {
            PortForward f = s.openLocalForward(null, 0, "127.0.0.1", echo.port());
            assertEquals(PortForward.Kind.LOCAL, f.kind());
            assertNotEquals(0, f.boundPort(), "ephemeral port must be reported");
            assertEquals("127.0.0.1", f.bindHost());
            assertEquals("echo:hello", roundTrip(f.boundPort(), "hello"));
            assertEquals("echo:again", roundTrip(f.boundPort(), "again"));
        }
    }

    @Test
    void closingLocalForwardReleasesThePort() throws Exception {
        try (SshSession s = open()) {
            PortForward f = s.openLocalForward(null, 0, "127.0.0.1", echo.port());
            assertEquals(1, s.listForwards().size());
            assertTrue(s.closeForward(f.id()));
            assertTrue(s.listForwards().isEmpty());
            assertFalse(s.closeForward(f.id()), "second close reports unknown id");
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", f.boundPort()).close());
        }
    }

    /**
     * Opening a forward and closing it at once must never throw and must not leave a spinning acceptor thread.
     * SSHJ's LocalPortForwarder.close() dereferences a thread that only exists once the acceptor thread has
     * entered listen(); closing before that used to throw a NullPointerException and strand the thread.
     */
    @Test
    void openingAndImmediatelyClosingLocalForwardsIsSafe() throws Exception {
        try (SshSession s = open()) {
            for (int i = 0; i < 300; i++) {
                PortForward f = s.openLocalForward(null, 0, "127.0.0.1", echo.port());
                long t0 = System.nanoTime();
                assertTrue(s.closeForward(f.id()), "iteration " + i);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < 2_000, "close took " + ms + " ms in iteration " + i + " (acceptor thread stuck?)");
            }
        }
        long deadline = System.currentTimeMillis() + 3_000;
        long alive;
        do {
            alive = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("wxssh-local-forward-") && t.isAlive()).count();
            if (alive == 0) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(0, alive, "no acceptor thread may outlive its forward");
    }
    @Test
    void closingSessionReleasesLocalForwardPort() throws Exception {
        int port;
        try (SshSession s = open()) {
            port = s.openLocalForward(null, 0, "127.0.0.1", echo.port()).boundPort();
        }
        int p = port;
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", p).close());
    }

    @Test
    void remoteForwardCarriesTrafficBackToAdapterHost() throws Exception {
        try (SshSession s = open()) {
            PortForward f = s.openRemoteForward("127.0.0.1", 0, "127.0.0.1", echo.port());
            assertEquals(PortForward.Kind.REMOTE, f.kind());
            assertNotEquals(0, f.boundPort(), "server-chosen port must be reported");
            assertEquals("echo:ping", roundTrip(f.boundPort(), "ping"));
            assertTrue(s.closeForward(f.id()));
        }
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyNetworkIo() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.openLocalForward(null, 0, "127.0.0.1", 0)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.openLocalForward(null, 70000, "127.0.0.1", 80)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.openRemoteForward(null, 0, " ", 80)).getCode());
        }
    }

    @Test
    void bindingAnOccupiedLocalPortFailsCleanly() throws Exception {
        try (SshSession s = open(); ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            SshAdapterException e = assertThrows(SshAdapterException.class,
                    () -> s.openLocalForward("127.0.0.1", taken.getLocalPort(), "127.0.0.1", echo.port()));
            assertEquals(ErrorCode.IO_ERROR, e.getCode());
            assertTrue(s.listForwards().isEmpty());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String roundTrip(int port, String line) throws IOException {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(),
                    StandardCharsets.UTF_8));
            out.println(line);
            return in.readLine();
        }
    }

    /** Line-based echo server that prefixes replies with {@code echo:}. */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket ss;

        EchoServer() throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::run, "test-echo-server");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        private void run() {
            while (!ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    Thread h = new Thread(() -> handle(c), "test-echo-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void handle(Socket c) {
            try (c) {
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(),
                        StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(c.getOutputStream(), true, StandardCharsets.UTF_8);
                String line;
                while ((line = in.readLine()) != null) {
                    out.println("echo:" + line);
                }
            } catch (IOException ignored) {
                // client went away
            }
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }
}
