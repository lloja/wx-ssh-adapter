package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisloja.wxssh.core.RemoteDirectoryPoller.PollResult;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.PolledFile;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.PostAction;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.Settings;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.SortOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RemoteDirectoryPoller} against a real SFTP subsystem (the embedded server): listing with filter and
 * recursion, content, the three file-changing post-actions and the restart behaviour.
 *
 * <p>Why in addition to {@code RemoteDirectoryPollerTest}: the unit test fakes the server; this one proves the
 * adapter from {@link SshSession} to the poller's {@code Remote} view (filter, recursion, rename with parent
 * creation) actually behaves the way the fake assumed.
 */
class RemoteDirectoryPollerIT {

    @TempDir
    Path tmp;

    private TestSshServer server;
    private Path root;

    @BeforeEach
    void start() throws Exception {
        root = Files.createDirectories(tmp.resolve("remote-root"));
        server = new TestSshServer(root, tmp.resolve("hostkey.ser"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private SshSession connect() throws Exception {
        return SshSession.connect(SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.ACCEPT_ANY)
                .connectTimeoutMs(5_000).socketTimeoutMs(20_000).build());
    }

    private static Settings settings(String filter, boolean recursive, PostAction action, String arg,
                                     boolean content) {
        return new Settings("/in", filter, recursive, 0, false, action, arg, SortOrder.NAME, 100, content, 1024);
    }

    private Path file(String rel, String text) throws Exception {
        Path p = root.resolve("in").resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    @Test
    void filterAndRecursionSelectTheFilesAndContentIsDelivered() throws Exception {
        file("a.csv", "one");
        file("b.txt", "two");
        file("sub/c.csv", "three");
        List<PolledFile> got = new ArrayList<>();
        try (SshSession s = connect()) {
            RemoteDirectoryPoller flat = new RemoteDirectoryPoller(
                    settings("*.csv", false, PostAction.NONE, null, true), null);
            PollResult r1 = flat.poll(s, got::add);
            assertEquals(1, r1.delivered());
            assertEquals("/in/a.csv", got.get(0).path());
            assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), got.get(0).content());

            got.clear();
            RemoteDirectoryPoller deep = new RemoteDirectoryPoller(
                    settings("*.csv", true, PostAction.NONE, null, false), null);
            PollResult r2 = deep.poll(s, got::add);
            assertEquals(2, r2.delivered());
            assertEquals(List.of("/in/a.csv", "/in/sub/c.csv"), got.stream().map(PolledFile::path).toList());
            assertEquals(null, got.get(0).content());
            assertEquals(SshConnectionConfig.DEFAULT_MAX_CONCURRENT_CHANNELS, s.availableChannelPermits());
        }
    }

    @Test
    void deleteRenameAndMoveReallyChangeTheServer() throws Exception {
        file("d.dat", "x");
        file("r.dat", "y");
        file("m.dat", "z");
        try (SshSession s = connect()) {
            new RemoteDirectoryPoller(settings("d.dat", false, PostAction.DELETE, null, false), null)
                    .poll(s, f -> { });
            assertFalse(Files.exists(root.resolve("in/d.dat")));

            new RemoteDirectoryPoller(settings("r.dat", false, PostAction.RENAME, ".done", false), null)
                    .poll(s, f -> { });
            assertFalse(Files.exists(root.resolve("in/r.dat")));
            assertEquals("y", Files.readString(root.resolve("in/r.dat.done")));

            PollResult moved = new RemoteDirectoryPoller(
                    settings("m.dat", false, PostAction.MOVE, "/archive/2026", false), null).poll(s, f -> { });
            assertEquals(0, moved.postActionFailures().size(), moved.postActionFailures().toString());
            assertFalse(Files.exists(root.resolve("in/m.dat")));
            assertEquals("z", Files.readString(root.resolve("archive/2026/m.dat")));
        }
    }

    @Test
    void aMoveOntoAnExistingFileIsReportedAndNothingIsOverwritten() throws Exception {
        file("m.dat", "new");
        Files.createDirectories(root.resolve("archive"));
        Files.writeString(root.resolve("archive/m.dat"), "old");
        try (SshSession s = connect()) {
            PollResult r = new RemoteDirectoryPoller(settings("m.dat", false, PostAction.MOVE, "/archive", false), null)
                    .poll(s, f -> { });
            assertEquals(1, r.delivered());
            assertEquals(1, r.postActionFailures().size(), r.postActionFailures().toString());
        }
        assertEquals("old", Files.readString(root.resolve("archive/m.dat")));
        assertEquals("new", Files.readString(root.resolve("in/m.dat")));
    }

    @Test
    void theStateFileStopsARestartedPollerFromFiringAgainAndANewFileStillFires() throws Exception {
        Path state = tmp.resolve("state/poll.state");
        file("a.csv", "1");
        try (SshSession s = connect()) {
            List<String> first = new ArrayList<>();
            new RemoteDirectoryPoller(settings(null, false, PostAction.NONE, null, false), state)
                    .poll(s, f -> first.add(f.path()));
            assertEquals(List.of("/in/a.csv"), first);

            file("b.csv", "2");
            List<String> second = new ArrayList<>();
            new RemoteDirectoryPoller(settings(null, false, PostAction.NONE, null, false), state)
                    .poll(s, f -> second.add(f.path()));
            assertEquals(List.of("/in/b.csv"), second);
        }
    }

    @Test
    void aMissingDirectoryAndABadFilterAreReportedWithTheirCodes() throws Exception {
        try (SshSession s = connect()) {
            SshAdapterException missing = assertThrows(SshAdapterException.class, () -> new RemoteDirectoryPoller(
                    settings(null, false, PostAction.NONE, null, false), null).poll(s, f -> { }));
            assertEquals(ErrorCode.SFTP_STATUS, missing.getCode());

            file("a.csv", "1");
            SshAdapterException bad = assertThrows(SshAdapterException.class, () -> new RemoteDirectoryPoller(
                    settings("[", false, PostAction.NONE, null, false), null).poll(s, f -> { }));
            assertEquals(ErrorCode.INVALID_CONFIG, bad.getCode());
            assertTrue(s.isOpen());
        }
    }
}
