package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the SFTP "manage" operations of the façade (design section 6.5) against an embedded SSH server.
 *
 * <p>Why a separate class: {@code SshSessionIT} is already long, and these tests change the server's file tree,
 * so each one checks the result on the server's real disk rather than trusting the call's own return value.
 *
 * <p>Not covered here, on purpose: {@code sftpChown} actually changing an owner (the embedded server runs as a
 * normal user on Windows, where ownership cannot be changed). The embedded server <em>does</em> offer
 * {@code posix-rename@openssh.com}, so the overwriting rename tests run the atomic branch by default and the
 * delete-then-rename fallback through the package-private switch ({@code usePosixExtension=false}).
 */
class SftpManageIT {

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

    private SshSession open() throws Exception {
        return SshSession.connect(SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.ACCEPT_ANY)
                .connectTimeoutMs(5_000).socketTimeoutMs(10_000).build());
    }

    private static SshAdapterException fails(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(SshAdapterException.class, call);
    }

    // ------------------------------------------------------------------ mkdir

    @Test
    void mkdirWithParentsCreatesTheWholeChainAndToleratesAnExistingDirectory() throws Exception {
        try (SshSession s = open()) {
            s.sftpMkdir("/a/b/c", true);
            assertTrue(Files.isDirectory(root.resolve("a/b/c")));
            s.sftpMkdir("/a/b/c", true);
        }
    }

    @Test
    void mkdirWithoutParentsFailsWhenAParentIsMissingAndCreatesNothing() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpMkdir("/x/y", false));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
            assertFalse(Files.exists(root.resolve("x")));
            s.sftpMkdir("/x", false);
            assertTrue(Files.isDirectory(root.resolve("x")));
        }
    }

    @Test
    void mkdirRejectsABlankPath() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpMkdir(" ", true)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpMkdir(null, false)).getCode());
        }
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteOnADirectoryExplainsWhatToUseInsteadAndKeepsTheDirectory() throws Exception {
        Files.createDirectories(root.resolve("keep/inner"));
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpDelete("/keep"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("is a directory") && e.getMessage().contains("SftpRmdir"), e.getMessage());
            assertTrue(Files.isDirectory(root.resolve("keep/inner")));
        }
    }

    @Test
    void deleteOnAMissingPathStillReportsTheServerStatus() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.SFTP_STATUS, fails(() -> s.sftpDelete("/nope.txt")).getCode());
        }
    }

    @Test
    void rmdirOnARegularFileExplainsWhatToUseInsteadAndKeepsTheFile() throws Exception {
        Files.writeString(root.resolve("f.txt"), "x");
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpRmdir("/f.txt"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("not a directory") && e.getMessage().contains("SftpDelete"), e.getMessage());
            assertTrue(Files.exists(root.resolve("f.txt")));
            assertEquals(ErrorCode.SFTP_STATUS, fails(() -> s.sftpRmdir("/missing")).getCode());
        }
    }

    // ------------------------------------------------------------------ rename into folders that do not exist

    @Test
    void renameIntoAMissingFolderNamesTheFolderAndMovesNothing() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x");
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpRename("/a.txt", "/done/2026/a.txt", false));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
            assertEquals("NO_SUCH_FILE", e.getSftpStatus());
            assertTrue(e.getMessage().contains("target folder /done/2026 does not exist"), e.getMessage());
            assertTrue(Files.exists(root.resolve("a.txt")));
            assertFalse(Files.exists(root.resolve("done")));
        }
    }

    @Test
    void renameWithAMissingSourceSaysSoAndCreatesNoFolders() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpRename("/nope.txt", "/done/x.txt", false, true));
            assertEquals("NO_SUCH_FILE", e.getSftpStatus());
            assertTrue(e.getMessage().contains("source /nope.txt does not exist"), e.getMessage());
            assertFalse(Files.exists(root.resolve("done")));
            SshAdapterException e2 = fails(() -> s.sftpRename("/nope.txt", "/done/x.txt", false));
            assertTrue(e2.getMessage().contains("source /nope.txt does not exist"), e2.getMessage());
        }
    }

    @Test
    void renameWithCreateParentsBuildsTheFolderChainAndMovesTheFile() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello");
        Files.writeString(root.resolve("b.txt"), "world");
        try (SshSession s = open()) {
            s.sftpRename("/a.txt", "/done/2026/11/a.txt", false, true);
            assertEquals("hello", Files.readString(root.resolve("done/2026/11/a.txt")));
            assertFalse(Files.exists(root.resolve("a.txt")));
            // the folder now exists, so a second move into it works too, with and without overwrite
            s.sftpRename("/b.txt", "/done/2026/11/a.txt", true, true);
            assertEquals("world", Files.readString(root.resolve("done/2026/11/a.txt")));
        }
    }

    @Test
    void renameWithCreateParentsAndAnExistingTargetStillRefusesWithoutOverwrite() throws Exception {
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("a.txt"), "new");
        Files.writeString(root.resolve("d/a.txt"), "old");
        try (SshSession s = open()) {
            fails(() -> s.sftpRename("/a.txt", "/d/a.txt", false, true));
            assertEquals("old", Files.readString(root.resolve("d/a.txt")));
            assertTrue(Files.exists(root.resolve("a.txt")));
        }
    }

    @Test
    void renameToARootLevelNameNeedsNoParentFolder() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x");
        try (SshSession s = open()) {
            s.sftpRename("/a.txt", "/b.txt", false, true);
            assertTrue(Files.exists(root.resolve("b.txt")));
            Files.writeString(root.resolve("rel.txt"), "y");
            s.sftpRename("rel.txt", "rel2.txt", false, true);
            assertTrue(Files.exists(root.resolve("rel2.txt")));
        }
    }

    // ------------------------------------------------------------------ recursive rmdir

    @Test
    void recursiveRmdirRemovesTheTreeAndCountsEveryEntry() throws Exception {
        server.seed("tree/one.txt", "1");
        server.seed("tree/sub/two.txt", "2");
        server.seed("tree/sub/deeper/three.txt", "3");
        Files.createDirectories(root.resolve("tree/empty"));
        server.seed("keep.txt", "keep");
        try (SshSession s = open()) {
            // tree, sub, deeper, empty = 4 directories; one, two, three = 3 files
            assertEquals(7, s.sftpRmdirRecursive("/tree"));
        }
        assertFalse(Files.exists(root.resolve("tree")));
        assertTrue(Files.exists(root.resolve("keep.txt")));
    }

    @Test
    void recursiveRmdirOfAnEmptyDirectoryRemovesJustIt() throws Exception {
        Files.createDirectories(root.resolve("empty"));
        try (SshSession s = open()) {
            assertEquals(1, s.sftpRmdirRecursive("/empty"));
        }
        assertFalse(Files.exists(root.resolve("empty")));
    }

    @Test
    void recursiveRmdirRefusesTheRootDotAndDotDotWithoutTouchingAnything() throws Exception {
        server.seed("safe/file.txt", "x");
        try (SshSession s = open()) {
            for (String bad : new String[]{"/", "//", ".", "..", "/safe/..", "/safe/.", "./", "safe/../"}) {
                SshAdapterException e = fails(() -> s.sftpRmdirRecursive(bad));
                assertEquals(ErrorCode.INVALID_CONFIG, e.getCode(), "path " + bad);
            }
        }
        assertTrue(Files.exists(root.resolve("safe/file.txt")));
    }

    @Test
    void recursiveRmdirRefusesAFileAndAMissingPath() throws Exception {
        server.seed("plain.txt", "x");
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpRmdirRecursive("/plain.txt")).getCode());
            assertEquals("NO_SUCH_FILE", fails(() -> s.sftpRmdirRecursive("/nope")).getSftpStatus());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpRmdirRecursive("")).getCode());
        }
        assertTrue(Files.exists(root.resolve("plain.txt")));
    }

    // ------------------------------------------------------------------ rename

    @Test
    void renameWithoutOverwriteRefusesAnExistingTargetAndChangesNothing() throws Exception {
        server.seed("from.txt", "new");
        server.seed("to.txt", "old");
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpRename("/from.txt", "/to.txt", false));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
        }
        assertEquals("new", Files.readString(root.resolve("from.txt")));
        assertEquals("old", Files.readString(root.resolve("to.txt")));
    }

    @Test
    void renameWithOverwriteReplacesAnExistingFile() throws Exception {
        server.seed("from.txt", "new");
        server.seed("to.txt", "old");
        try (SshSession s = open()) {
            s.sftpRename("/from.txt", "/to.txt", true);
        }
        assertFalse(Files.exists(root.resolve("from.txt")));
        assertEquals("new", Files.readString(root.resolve("to.txt")));
    }

    @Test
    void renameWithOverwriteToAFreeNameJustMoves() throws Exception {
        server.seed("from.txt", "data");
        try (SshSession s = open()) {
            s.sftpRename("/from.txt", "/moved.txt", true);
        }
        assertEquals("data", Files.readString(root.resolve("moved.txt")));
    }

    @Test
    void renameWithOverwriteNeverReplacesADirectory() throws Exception {
        server.seed("from.txt", "data");
        server.seed("dir/inner.txt", "keep");
        try (SshSession s = open()) {
            // Either the fallback refuses (INVALID_CONFIG) or the server does (SFTP_STATUS): never a deletion.
            SshAdapterException e = fails(() -> s.sftpRename("/from.txt", "/dir", true));
            assertTrue(e.getCode() == ErrorCode.INVALID_CONFIG || e.getCode() == ErrorCode.SFTP_STATUS, e.getCode().name());
        }
        assertEquals("keep", Files.readString(root.resolve("dir/inner.txt")));
        assertEquals("data", Files.readString(root.resolve("from.txt")));
    }

    @Test
    void renameWithOverwriteOfAMissingSourceLeavesTheTargetAlone() throws Exception {
        server.seed("to.txt", "precious");
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpRename("/missing.txt", "/to.txt", true));
            assertEquals("NO_SUCH_FILE", e.getSftpStatus());
        }
        assertEquals("precious", Files.readString(root.resolve("to.txt")));
    }

    @Test
    void renameOverwriteFallbackWithoutThePosixExtensionReplacesAFile() throws Exception {
        server.seed("from.txt", "new");
        server.seed("to.txt", "old");
        try (SshSession s = open()) {
            s.sftpRename("/from.txt", "/to.txt", true, false, false);
        }
        assertFalse(Files.exists(root.resolve("from.txt")));
        assertEquals("new", Files.readString(root.resolve("to.txt")));
    }

    @Test
    void renameOverwriteFallbackKeepsADirectoryTargetAndTheSource() throws Exception {
        server.seed("from.txt", "data");
        server.seed("dir/inner.txt", "keep");
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpRename("/from.txt", "/dir", true, false, false));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
        }
        assertEquals("keep", Files.readString(root.resolve("dir/inner.txt")));
        assertEquals("data", Files.readString(root.resolve("from.txt")));
    }

    @Test
    void renameOverwriteFallbackWithAMissingSourceLeavesTheTargetAlone() throws Exception {
        server.seed("to.txt", "precious");
        try (SshSession s = open()) {
            assertEquals("NO_SUCH_FILE", fails(() -> s.sftpRename("/missing.txt", "/to.txt", true, false, false)).getSftpStatus());
        }
        assertEquals("precious", Files.readString(root.resolve("to.txt")));
    }

    @Test
    void renameOverwriteFallbackToAFreeNameJustMoves() throws Exception {
        server.seed("from.txt", "data");
        try (SshSession s = open()) {
            s.sftpRename("/from.txt", "/free.txt", true, false, false);
        }
        assertEquals("data", Files.readString(root.resolve("free.txt")));
    }
    @Test
    void renameRejectsABlankPath() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpRename("", "/b", true)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpRename("/a", null, false)).getCode());
        }
    }

    // ------------------------------------------------------------------ recursive chmod
    // The embedded server runs on Windows, where permission bits do not stick, so these tests check what the
    // facade controls (which entries are visited, counts, refusals); the modes themselves were checked by hand
    // against OpenSSH (see design 6.5).

    @Test
    void directoryModeAddsExecuteWhereReadIsSet() {
        assertEquals(0750, SshSession.directoryModeFor(0640));
        assertEquals(0700, SshSession.directoryModeFor(0600));
        assertEquals(0755, SshSession.directoryModeFor(0644));
        assertEquals(0777, SshSession.directoryModeFor(0666));
        assertEquals(0000, SshSession.directoryModeFor(0000));
        assertEquals(02750, SshSession.directoryModeFor(02640));
    }

    @Test
    void recursiveChmodVisitsEveryFileAndDirectoryAndCountsThem() throws Exception {
        Files.createDirectories(root.resolve("t/a/b"));
        Files.writeString(root.resolve("t/1.txt"), "x");
        Files.writeString(root.resolve("t/a/2.txt"), "x");
        Files.writeString(root.resolve("t/a/b/3.txt"), "x");
        try (SshSession s = open()) {
            // t, t/a, t/a/b + three files
            assertEquals(6, s.sftpChmodRecursive("/t", 0640, null));
            assertEquals(6, s.sftpChmodRecursive("/t", 0644, 0755));
        }
    }

    @Test
    void recursiveChmodOnAPlainFileChangesOnlyThatFile() throws Exception {
        Files.writeString(root.resolve("only.txt"), "x");
        try (SshSession s = open()) {
            assertEquals(1, s.sftpChmodRecursive("/only.txt", 0600, null));
        }
    }

    @Test
    void recursiveChmodRefusesTheRootAndRelativeRoots() throws Exception {
        try (SshSession s = open()) {
            for (String p : new String[] {"/", ".", "..", "/a/..", "a/."}) {
                assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChmodRecursive(p, 0644, null)).getCode(), p);
            }
        }
    }

    @Test
    void recursiveChmodRejectsModesOutOfRangeAndBlankPaths() throws Exception {
        Files.createDirectories(root.resolve("d"));
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChmodRecursive("/d", 010000, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChmodRecursive("/d", -1, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChmodRecursive("/d", 0644, 010000)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChmodRecursive(" ", 0644, null)).getCode());
        }
    }

    @Test
    void recursiveChmodOnAMissingPathReportsTheServerStatus() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpChmodRecursive("/nope", 0644, null));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
            assertEquals("NO_SUCH_FILE", e.getSftpStatus());
        }
    }

    @Test
    void recursiveChmodDoesNotFollowOrChangeSymbolicLinks() throws Exception {
        Files.createDirectories(root.resolve("tree"));
        Files.createDirectories(root.resolve("outside"));
        Files.writeString(root.resolve("outside/secret.txt"), "x");
        Files.writeString(root.resolve("tree/f.txt"), "x");
        try (SshSession s = open()) {
            try {
                s.sftpSymlink("/tree/out", "../outside");
            } catch (SshAdapterException e) {
                org.junit.jupiter.api.Assumptions.abort("cannot create symbolic links here: " + e.getMessage());
            }
            // tree + f.txt; the link is skipped, and the walk never enters ../outside
            assertEquals(2, s.sftpChmodRecursive("/tree", 0640, null));
            // a link given as the starting point is refused, not followed
            SshAdapterException e = fails(() -> s.sftpChmodRecursive("/tree/out", 0640, null));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ symlink

    @Test
    void symlinkPointsAtTheTargetAndReadLinkReturnsItAsGiven() throws Exception {
        server.seed("target.txt", "linked");
        try (SshSession s = open()) {
            try {
                s.sftpSymlink("/link.txt", "target.txt");
            } catch (SshAdapterException e) {
                org.junit.jupiter.api.Assumptions.abort("cannot create symbolic links here: " + e.getMessage());
            }
            assertEquals("target.txt", s.sftpReadLink("/link.txt"));
            assertEquals(RemoteEntry.Type.SYMLINK, s.sftpStat("/link.txt", false).type());
            assertEquals(RemoteEntry.Type.FILE, s.sftpStat("/link.txt", true).type());
        }
    }

    @Test
    void symlinkRejectsBlankArguments() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpSymlink("", "t")).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpSymlink("/l", " ")).getCode());
        }
    }

    // ------------------------------------------------------------------ chown

    @Test
    void chownRejectsMissingAndNegativeIdsBeforeAnyRequest() throws Exception {
        server.seed("f.txt", "x");
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChown("/f.txt", null, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChown("/f.txt", -1, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChown("/f.txt", null, -5)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChown(" ", 1, 1)).getCode());
        }
    }

    @Test
    void recursiveChownVisitsEveryFileAndDirectoryAndCountsThem() throws Exception {
        Files.createDirectories(root.resolve("t/a/b"));
        Files.writeString(root.resolve("t/1.txt"), "x");
        Files.writeString(root.resolve("t/a/2.txt"), "x");
        Files.writeString(root.resolve("t/a/b/3.txt"), "x");
        try (SshSession s = open()) {
            // t, t/a, t/a/b + three files; the embedded server accepts the request but cannot change an owner
            assertEquals(6, s.sftpChownRecursive("/t", 1000, 1000));
            assertEquals(6, s.sftpChownRecursive("/t", 1000, null));
            assertEquals(6, s.sftpChownRecursive("/t", null, 1000));
        }
    }

    @Test
    void recursiveChownOnAPlainFileChangesOnlyThatFile() throws Exception {
        Files.writeString(root.resolve("only.txt"), "x");
        try (SshSession s = open()) {
            assertEquals(1, s.sftpChownRecursive("/only.txt", 1000, 1000));
        }
    }

    @Test
    void recursiveChownRefusesTheRootRelativeRootsAndBadIds() throws Exception {
        Files.createDirectories(root.resolve("d"));
        try (SshSession s = open()) {
            for (String p : new String[] {"/", ".", "..", "/a/..", "a/."}) {
                assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChownRecursive(p, 1, 1)).getCode(), p);
            }
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChownRecursive("/d", null, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChownRecursive("/d", -1, 1)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpChownRecursive(" ", 1, 1)).getCode());
        }
    }

    @Test
    void recursiveChownOnAMissingPathReportsTheServerStatus() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpChownRecursive("/nope", 1, 1));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
            assertEquals("NO_SUCH_FILE", e.getSftpStatus());
        }
    }

    @Test
    void recursiveChownDoesNotFollowOrChangeSymbolicLinks() throws Exception {
        Files.createDirectories(root.resolve("tree"));
        Files.createDirectories(root.resolve("outside"));
        Files.writeString(root.resolve("outside/secret.txt"), "x");
        Files.writeString(root.resolve("tree/f.txt"), "x");
        try (SshSession s = open()) {
            try {
                s.sftpSymlink("/tree/out", "../outside");
            } catch (SshAdapterException e) {
                org.junit.jupiter.api.Assumptions.abort("cannot create symbolic links here: " + e.getMessage());
            }
            assertEquals(2, s.sftpChownRecursive("/tree", 1000, 1000));
            SshAdapterException e = fails(() -> s.sftpChownRecursive("/tree/out", 1000, 1000));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        }
    }

    @Test
    void namesAreLookedUpWithGetentOnTheServer() throws Exception {
        try (SshSession s = open()) {
            assertEquals(1000, s.sftpResolveUserId("wxtest"));
            assertEquals(0, s.sftpResolveUserId(" root "));
            assertEquals(100, s.sftpResolveGroupId("users"));
        }
    }

    @Test
    void anUnknownNameIsReportedAsMissingAndNotRetriedOnTheFlatFile() throws Exception {
        // the file would answer 4242, but getent has already said "does not exist" (exit 2): that is final
        server.seed("etc/passwd", "ghost:x:4242:4242::/:/bin/false\n");
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpResolveUserId("ghost"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpResolveGroupId("nogroup")).getCode());
        }
    }

    @Test
    void whenGetentCannotAnswerTheFlatFileIsRead() throws Exception {
        server.seed("etc/passwd", "root:x:0:0::/root:/bin/sh\nnolookup:x:4242:4243::/:/bin/false\n");
        server.seed("etc/group", "# comment\nnolookup:x:4243:\n");
        try (SshSession s = open()) {
            assertEquals(4242, s.sftpResolveUserId("nolookup"));
            assertEquals(4243, s.sftpResolveGroupId("nolookup"));
            SshAdapterException e = fails(() -> s.sftpResolveUserId("nolookup2"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
        }
    }

    @Test
    void whenNeitherGetentNorTheFlatFileWorksTheMessageSaysToUseTheNumber() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = fails(() -> s.sftpResolveUserId("nolookup"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("numeric id"), e.getMessage());
        }
    }

    @Test
    void namesWithShellCharactersOrNoTextAreRefusedBeforeAnythingRuns() throws Exception {
        try (SshSession s = open()) {
            for (String bad : new String[] {"a b", "x'; rm -rf /; '", "$(id)", "-n", "a`id`", "DOM\\user", "a;b", "", " ", null}) {
                assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpResolveUserId(bad)).getCode(), String.valueOf(bad));
                assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpResolveGroupId(bad)).getCode(), String.valueOf(bad));
            }
        }
    }
    // ------------------------------------------------------------------ setTimes

    @Test
    void setTimesSetsBothTimesInOneCall() throws Exception {
        server.seed("t.txt", "x");
        try (SshSession s = open()) {
            s.sftpSetTimes("/t.txt", 1_600_000_000L, 1_700_000_000L);
            RemoteEntry e = s.sftpStat("/t.txt");
            assertEquals(1_700_000_000L, e.modifiedSec());
        }
        assertEquals(1_700_000_000L, Files.getLastModifiedTime(root.resolve("t.txt")).to(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void setTimesWithOnlyMtimeKeepsTheAccessTime() throws Exception {
        server.seed("t.txt", "x");
        try (SshSession s = open()) {
            s.sftpSetTimes("/t.txt", 1_600_000_000L, 1_650_000_000L);
            long atimeBefore = s.sftpStat("/t.txt").accessedSec();
            s.sftpSetTimes("/t.txt", null, 1_700_000_000L);
            RemoteEntry after = s.sftpStat("/t.txt");
            assertEquals(1_700_000_000L, after.modifiedSec());
            assertEquals(atimeBefore, after.accessedSec());
        }
    }

    @Test
    void setTimesWithOnlyAtimeKeepsTheModificationTime() throws Exception {
        server.seed("t.txt", "x");
        Files.setLastModifiedTime(root.resolve("t.txt"), FileTime.fromMillis(1_650_000_000_000L));
        try (SshSession s = open()) {
            long mtimeBefore = s.sftpStat("/t.txt").modifiedSec();
            s.sftpSetTimes("/t.txt", 1_600_000_000L, null);
            RemoteEntry after = s.sftpStat("/t.txt");
            assertEquals(mtimeBefore, after.modifiedSec());
            assertEquals(1_600_000_000L, after.accessedSec());
        }
    }

    @Test
    void setTimesRejectsNothingToSetNegativeTimesAndBlankPath() throws Exception {
        server.seed("t.txt", "x");
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpSetTimes("/t.txt", null, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpSetTimes("/t.txt", -1L, null)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpSetTimes("/t.txt", null, -1L)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpSetTimes("", 1L, 1L)).getCode());
        }
    }

    @Test
    void setTimesOnAMissingPathReportsNoSuchFileEvenWithOneTimeGiven() throws Exception {
        try (SshSession s = open()) {
            assertEquals("NO_SUCH_FILE", fails(() -> s.sftpSetTimes("/nope", null, 5L)).getSftpStatus());
            assertEquals("NO_SUCH_FILE", fails(() -> s.sftpSetTimes("/nope", 5L, 5L)).getSftpStatus());
        }
    }

    // ------------------------------------------------------------------ truncate

    @Test
    void truncateCutsAFileAndKeepsTheStart() throws Exception {
        server.seed("big.txt", "0123456789");
        try (SshSession s = open()) {
            s.sftpTruncate("/big.txt", 4);
        }
        assertEquals("0123", Files.readString(root.resolve("big.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void truncateToZeroEmptiesTheFile() throws Exception {
        server.seed("big.txt", "0123456789");
        try (SshSession s = open()) {
            s.sftpTruncate("/big.txt", 0);
        }
        assertEquals(0, Files.size(root.resolve("big.txt")));
    }

    @Test
    void truncateRejectsNegativeSizeAndBlankPathAndReportsMissingFile() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpTruncate("/f", -1)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, fails(() -> s.sftpTruncate("", 0)).getCode());
            assertEquals("NO_SUCH_FILE", fails(() -> s.sftpTruncate("/nope", 0)).getSftpStatus());
        }
    }

    // ------------------------------------------------------------------ the channel is always given back

    @Test
    void everyManageOperationReleasesItsChannelEvenWhenItFails() throws Exception {
        SshSession s = SshSession.connect(SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.ACCEPT_ANY)
                .connectTimeoutMs(5_000).socketTimeoutMs(2_000)
                .maxConcurrentChannels(1).build());
        try (s) {
            // With a single slot, one leaked permit would make every later call wait and then time out.
            // sftpChown is left out: the embedded server ignores ownership on Windows and accepts a missing path.
            for (int i = 0; i < 3; i++) {
                fails(() -> s.sftpMkdir("/nope/deeper", false));
                fails(() -> s.sftpRmdirRecursive("/nope"));
                fails(() -> s.sftpRename("/nope", "/other", true));
                fails(() -> s.sftpRename("/nope", "/other", false));
                fails(() -> s.sftpSymlink("", "x"));
                fails(() -> s.sftpSetTimes("/nope", 1L, null));
                fails(() -> s.sftpTruncate("/nope", 0));
            }
            s.sftpMkdir("/still-works", true);
            assertNotNull(s.sftpStat("/still-works"));
        }
    }
}
