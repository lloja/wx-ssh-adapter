package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisloja.wxssh.core.RemoteDirectoryPoller.PollResult;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.PostAction;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.Settings;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.SortOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure tests of {@link RemoteDirectoryPoller}: the seen/ready/stable rules, ordering, post-actions and the state
 * file, driven by a fake directory and a fake clock so every case is deterministic.
 */
class RemoteDirectoryPollerTest {

    @TempDir
    Path tmp;

    /** A fake server directory that records what the poller asks of it. */
    private static final class FakeRemote implements RemoteDirectoryPoller.Remote {
        final Map<String, RemoteEntry> files = new LinkedHashMap<>();
        final Map<String, byte[]> contents = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean failDelete;
        boolean failRead;

        void put(String path, long size, long mtime) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            files.put(path, new RemoteEntry(name, path, size, mtime, 0644, RemoteEntry.Type.FILE, mtime, 0, 0));
        }

        @Override
        public List<RemoteEntry> list(String directory, String filter, boolean recursive) {
            return new ArrayList<>(files.values());
        }

        @Override
        public byte[] read(String path) throws SshAdapterException {
            if (failRead) {
                throw new SshAdapterException(ErrorCode.SFTP_STATUS, "denied", null);
            }
            return contents.getOrDefault(path, new byte[0]);
        }

        @Override
        public void delete(String path) throws SshAdapterException {
            if (failDelete) {
                throw new SshAdapterException(ErrorCode.SFTP_STATUS, "permission denied", null);
            }
            calls.add("delete " + path);
            files.remove(path);
        }

        @Override
        public void move(String from, String to) {
            calls.add("move " + from + " -> " + to);
            files.remove(from);
        }
    }

    private static Settings settings() {
        return new Settings("/in", null, false, 0, false, PostAction.NONE, null, SortOrder.NAME, 100, false, 0);
    }

    private static Settings with(long minAge, boolean stable, PostAction action, String arg, SortOrder sort,
                                 int max, boolean content, long maxContent) {
        return new Settings("/in", null, false, minAge, stable, action, arg, sort, max, content, maxContent);
    }

    /** Polls once, appends delivered paths to {@code out} and returns [delivered, waiting] as text. */
    private static List<String> collect(RemoteDirectoryPoller p, FakeRemote r, List<String> out)
            throws SshAdapterException {
        PollResult res = p.poll(r, f -> out.add(f.path()));
        return List.of(String.valueOf(res.delivered()), String.valueOf(res.waiting()));
    }

    @Test
    void aNewFileIsDeliveredOnceAndNotAgain() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        List<String> got = new ArrayList<>();
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(settings(), null, () -> 5000);

        assertEquals(List.of("1", "0"), collect(p, r, got));
        assertEquals(List.of("0", "0"), collect(p, r, got));
        assertEquals(List.of("/in/a.csv"), got);
    }

    @Test
    void aReplacedFileWithNewSizeOrTimeIsDeliveredAgain() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        List<String> got = new ArrayList<>();
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(settings(), null, () -> 5000);
        collect(p, r, got);

        r.put("/in/a.csv", 11, 1000);
        collect(p, r, got);
        r.put("/in/a.csv", 11, 1001);
        collect(p, r, got);

        assertEquals(3, got.size());
    }

    @Test
    void aFileYoungerThanTheMinimumAgeWaits() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 990);
        AtomicLong now = new AtomicLong(1000);
        List<String> got = new ArrayList<>();
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(
                with(30, false, PostAction.NONE, null, SortOrder.NAME, 100, false, 0), null, now::get);

        assertEquals(List.of("0", "1"), collect(p, r, got));
        now.set(1019);
        assertEquals(List.of("0", "1"), collect(p, r, got));
        now.set(1020);
        assertEquals(List.of("1", "0"), collect(p, r, got));
    }

    @Test
    void theStabilityCheckNeedsTwoIdenticalPollsAndAGrowingFileResetsIt() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        List<String> got = new ArrayList<>();
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(
                with(0, true, PostAction.NONE, null, SortOrder.NAME, 100, false, 0), null, () -> 5000);

        assertEquals(List.of("0", "1"), collect(p, r, got));
        r.put("/in/a.csv", 20, 1000);
        assertEquals(List.of("0", "1"), collect(p, r, got), "the size changed so the clock starts over");
        assertEquals(List.of("1", "0"), collect(p, r, got));
        assertEquals(List.of("0", "0"), collect(p, r, got));
        assertEquals(List.of("/in/a.csv"), got);
    }

    @Test
    void theSeenStateSurvivesARestartThroughTheStateFile() throws Exception {
        Path state = tmp.resolve("state").resolve("poll.state");
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        r.put("/in/b b\t.csv", 5, 1000);
        List<String> got = new ArrayList<>();
        collect(new RemoteDirectoryPoller(settings(), state, () -> 5000), r, got);
        assertEquals(2, got.size());

        List<String> afterRestart = new ArrayList<>();
        collect(new RemoteDirectoryPoller(settings(), state, () -> 5000), r, afterRestart);
        assertTrue(afterRestart.isEmpty(), "a restart must not re-fire: " + afterRestart);

        List<String> noState = new ArrayList<>();
        collect(new RemoteDirectoryPoller(settings(), tmp.resolve("other.state"), () -> 5000), r, noState);
        assertEquals(2, noState.size());
    }

    @Test
    void entriesOfVanishedFilesAreDroppedFromTheStateFile() throws Exception {
        Path state = tmp.resolve("poll.state");
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        r.put("/in/b.csv", 10, 1000);
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(settings(), state, () -> 5000);
        collect(p, r, new ArrayList<>());
        assertEquals(2, Files.readAllLines(state).size());

        r.files.remove("/in/a.csv");
        collect(p, r, new ArrayList<>());

        List<String> lines = Files.readAllLines(state);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("/in/b.csv"), lines.get(0));
    }

    @Test
    void aDamagedStateFileIsAnErrorNotAnEmptyState() throws Exception {
        Path state = tmp.resolve("poll.state");
        Files.writeString(state, "garbage\n");
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(settings(), state, () -> 5000);

        SshAdapterException ex = assertThrows(SshAdapterException.class, () -> p.poll(r, f -> { }));

        assertEquals(ErrorCode.IO_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("damaged"), ex.getMessage());
    }

    @Test
    void aFailingSinkStopsThePollAndTheFileIsRetriedNextTime() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        r.put("/in/b.csv", 10, 1000);
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(settings(), tmp.resolve("s"), () -> 5000);

        SshAdapterException ex = assertThrows(SshAdapterException.class, () -> p.poll(r, f -> {
            throw new IllegalStateException("broker down");
        }));
        assertEquals(ErrorCode.IO_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("/in/a.csv") && ex.getMessage().contains("broker down"), ex.getMessage());

        List<String> got = new ArrayList<>();
        collect(p, r, got);
        assertEquals(List.of("/in/a.csv", "/in/b.csv"), got);
    }

    @Test
    void deleteRenameAndMovePostActionsRunAfterDelivery() throws Exception {
        for (Object[] c : new Object[][] {
                {PostAction.DELETE, null, "delete /in/a.csv"},
                {PostAction.RENAME, ".done", "move /in/a.csv -> /in/a.csv.done"},
                {PostAction.MOVE, "/archive", "move /in/a.csv -> /archive/a.csv"},
                {PostAction.MOVE, "/archive/", "move /in/a.csv -> /archive/a.csv"}}) {
            FakeRemote r = new FakeRemote();
            r.put("/in/a.csv", 10, 1000);
            List<String> got = new ArrayList<>();
            RemoteDirectoryPoller p = new RemoteDirectoryPoller(
                    with(0, false, (PostAction) c[0], (String) c[1], SortOrder.NAME, 100, false, 0), null, () -> 5000);

            collect(p, r, got);

            assertEquals(List.of("/in/a.csv"), got);
            assertEquals(List.of((String) c[2]), r.calls, String.valueOf(c[0]));
        }
    }

    @Test
    void aFailingPostActionIsReportedAndDoesNotCauseASecondDelivery() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/a.csv", 10, 1000);
        r.failDelete = true;
        List<String> got = new ArrayList<>();
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(
                with(0, false, PostAction.DELETE, null, SortOrder.NAME, 100, false, 0), null, () -> 5000);

        PollResult first = p.poll(r, f -> got.add(f.path()));
        PollResult second = p.poll(r, f -> got.add(f.path()));

        assertEquals(1, first.postActionFailures().size());
        assertTrue(first.postActionFailures().get(0).contains("permission denied"), first.postActionFailures().get(0));
        assertEquals(0, second.delivered());
        assertEquals(List.of("/in/a.csv"), got);
    }

    @Test
    void atMostMaxFilesPerPollAreDeliveredInTheChosenOrder() throws Exception {
        for (Object[] c : new Object[][] {
                {SortOrder.NAME, List.of("/in/a", "/in/b")},
                {SortOrder.OLDEST_FIRST, List.of("/in/c", "/in/b")},
                {SortOrder.NEWEST_FIRST, List.of("/in/a", "/in/b")}}) {
            FakeRemote r = new FakeRemote();
            r.put("/in/a", 1, 300);
            r.put("/in/b", 1, 200);
            r.put("/in/c", 1, 100);
            List<String> got = new ArrayList<>();
            RemoteDirectoryPoller p = new RemoteDirectoryPoller(
                    with(0, false, PostAction.NONE, null, (SortOrder) c[0], 2, false, 0), null, () -> 5000);

            PollResult res = p.poll(r, f -> got.add(f.path()));

            assertEquals(c[1], got, String.valueOf(c[0]));
            assertEquals(1, res.waiting());
        }
    }

    @Test
    void contentIsReadWhenRequestedSkippedWhenTooBigAndAnUnreadableFileIsRetried() throws Exception {
        FakeRemote r = new FakeRemote();
        r.put("/in/small", 3, 1000);
        r.put("/in/big", 100, 1000);
        r.contents.put("/in/small", new byte[] {1, 2, 3});
        List<RemoteDirectoryPoller.PolledFile> got = new ArrayList<>();
        RemoteDirectoryPoller p = new RemoteDirectoryPoller(
                with(0, false, PostAction.NONE, null, SortOrder.NAME, 100, true, 10), null, () -> 5000);

        p.poll(r, got::add);

        assertEquals(2, got.size());
        RemoteDirectoryPoller.PolledFile big = got.get(0);
        RemoteDirectoryPoller.PolledFile small = got.get(1);
        assertTrue(big.contentSkipped());
        assertEquals(null, big.content());
        assertFalse(small.contentSkipped());
        assertEquals(3, small.content().length);

        r.put("/in/locked", 1, 1000);
        r.failRead = true;
        PollResult res = p.poll(r, got::add);
        assertEquals(1, res.skippedUnreadable());
        assertEquals(2, got.size());
        r.failRead = false;
        p.poll(r, got::add);
        assertEquals(3, got.size());
    }

    @Test
    void subDirectoriesAndLinksAreNeverDelivered() throws Exception {
        FakeRemote r = new FakeRemote();
        r.files.put("/in/dir", new RemoteEntry("dir", "/in/dir", 0, 1, 0755, RemoteEntry.Type.DIRECTORY, 1, 0, 0));
        r.files.put("/in/lnk", new RemoteEntry("lnk", "/in/lnk", 0, 1, 0777, RemoteEntry.Type.SYMLINK, 1, 0, 0));
        List<String> got = new ArrayList<>();
        PollResult res = new RemoteDirectoryPoller(settings(), null, () -> 5000).poll(r, f -> got.add(f.path()));
        assertTrue(got.isEmpty());
        assertEquals(0, res.listed());
    }

    @Test
    void settingsRejectWhatCouldNotWork() {
        assertThrows(IllegalArgumentException.class, () -> with(0, false, PostAction.NONE, null, SortOrder.NAME, 0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> with(-1, false, PostAction.NONE, null, SortOrder.NAME, 1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> with(0, false, PostAction.RENAME, " ", SortOrder.NAME, 1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> with(0, false, PostAction.RENAME, "a/b", SortOrder.NAME, 1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> with(0, false, PostAction.MOVE, null, SortOrder.NAME, 1, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Settings(" ", null, false, 0, false, PostAction.NONE, null, SortOrder.NAME, 1, false, 0));
    }

    @Test
    void pathEscapingRoundTrips() {
        String nasty = "/in/a%09b\tc\nd\re%";
        assertEquals(nasty, RemoteDirectoryPoller.unescape(RemoteDirectoryPoller.escape(nasty)));
        assertFalse(RemoteDirectoryPoller.escape(nasty).contains("\t"));
        assertFalse(RemoteDirectoryPoller.escape(nasty).contains("\n"));
    }
}
