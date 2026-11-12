package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests of the façade against an embedded SSH server (real handshake, real SFTP, real SCP).
 *
 * <p>Why: these cover the risks the investigation plan calls out that can be answered without Integration
 * Server - authentication, host-key policies, error mapping, atomic upload, bounded memory and, importantly,
 * concurrent operations over one transport (risk R3).
 */
class SshSessionIT {

    @TempDir
    Path tmp;

    private TestSshServer server;

    @BeforeEach
    void start() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("remote-root"));
        server = new TestSshServer(root, tmp.resolve("hostkey.ser"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private SshConnectionConfig.Builder base() {
        return SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.ACCEPT_ANY)
                .connectTimeoutMs(5_000).socketTimeoutMs(10_000);
    }

    private SshSession open() throws Exception {
        return SshSession.connect(base().build());
    }

    // ------------------------------------------------------------ connect / auth / host key

    @Test
    void passwordAuthenticationSucceeds() throws Exception {
        try (SshSession s = open()) {
            assertTrue(s.isOpen());
        }
    }

    @Test
    void wrongPasswordMapsToAuthFailed() {
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().password("nope").build()));
        assertEquals(ErrorCode.AUTH_FAILED, e.getCode());
    }

    @Test
    void unreachableHostMapsToConnectFailed() {
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().port(1).connectTimeoutMs(1_000).build()));
        assertTrue(e.getCode() == ErrorCode.CONNECT_FAILED || e.getCode() == ErrorCode.TIMEOUT,
                "was " + e.getCode());
    }

    @Test
    void invalidConfigIsRejectedBeforeNetworkIo() {
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(SshConnectionConfig.builder().host("").build()));
        assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
    }

    @Test
    void probeFingerprintMatchesIndependentComputation() throws Exception {
        HostKeyProbe.Result r = HostKeyProbe.probe("127.0.0.1", server.port(), 5_000);
        assertEquals(server.hostKeyFingerprint(), r.fingerprint());
        assertTrue(r.fingerprint().startsWith("SHA256:"));
    }

    @Test
    void pinnedFingerprintAcceptsMatchAndRejectsMismatch() throws Exception {
        String fp = server.hostKeyFingerprint();
        try (SshSession s = SshSession.connect(base().hostKeyPolicy(HostKeyPolicy.PINNED_FINGERPRINT)
                .addPinnedFingerprint(fp).build())) {
            assertTrue(s.isOpen());
        }
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().hostKeyPolicy(HostKeyPolicy.PINNED_FINGERPRINT)
                        .addPinnedFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").build()));
        assertEquals(ErrorCode.HOST_KEY_REJECTED, e.getCode());
    }

    @Test
    void knownHostsPolicyRejectsUnknownHost() throws Exception {
        Path empty = Files.writeString(tmp.resolve("known_hosts"), "");
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().hostKeyPolicy(HostKeyPolicy.KNOWN_HOSTS)
                        .knownHostsPath(empty.toString()).build()));
        assertEquals(ErrorCode.HOST_KEY_REJECTED, e.getCode());
    }

    @Test
    void configToStringNeverContainsSecrets() {
        String text = base().build().toString();
        assertFalse(text.contains(TestSshServer.PASSWORD));
    }

    // ------------------------------------------------------------ public-key auth

    private static String pkcs8Pem(java.security.KeyPair kp) {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(kp.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void publicKeyAuthenticationWorksWithKeyFileAndInlinePem() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        java.security.KeyPair kp = gen.generateKeyPair();
        server.authorize(kp.getPublic());
        String pem = pkcs8Pem(kp);
        Path keyFile = Files.writeString(tmp.resolve("id_rsa.pem"), pem);

        try (SshSession s = SshSession.connect(base().authMethod(AuthMethod.PUBLIC_KEY).password(null)
                .privateKeyPath(keyFile.toString()).build())) {
            assertTrue(s.isOpen());
        }
        try (SshSession s = SshSession.connect(base().authMethod(AuthMethod.PUBLIC_KEY).password(null)
                .privateKeyPem(pem).build())) {
            assertTrue(s.isOpen());
        }
    }

    @Test
    void publicKeyAuthenticationRejectsUnauthorisedKey() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        server.authorize(gen.generateKeyPair().getPublic());
        String otherPem = pkcs8Pem(gen.generateKeyPair());
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().authMethod(AuthMethod.PUBLIC_KEY).password(null)
                        .privateKeyPem(otherPem).build()));
        assertEquals(ErrorCode.AUTH_FAILED, e.getCode());
    }

    @Test
    void publicKeyConfigNeedsExactlyOneKeySource() {
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().authMethod(AuthMethod.PUBLIC_KEY).password(null).build()));
        assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
    }

    // ------------------------------------------------------------ keyboard-interactive

    @Test
    void keyboardInteractivePasswordAuthenticationWorks() throws Exception {
        try (SshSession s = SshSession.connect(base().authMethod(AuthMethod.KEYBOARD_INTERACTIVE_PASSWORD).build())) {
            assertTrue(s.isOpen());
        }
    }
    // ------------------------------------------------------------ exec

    @Test
    void execCapturesStdoutAndExitZero() throws Exception {
        try (SshSession s = open()) {
            ExecResult r = s.exec(ExecRequest.of("echo hello world", 5_000));
            assertEquals("hello world\n", r.stdout());
            assertEquals("", r.stderr());
            assertEquals(0, r.exitStatus());
            assertFalse(r.timedOut());
        }
    }

    @Test
    void execReturnsNonZeroExitAsDataNotException() throws Exception {
        try (SshSession s = open()) {
            ExecResult r = s.exec(ExecRequest.of("fail 7", 5_000));
            assertEquals(7, r.exitStatus());
        }
    }

    @Test
    void execSeparatesStderr() throws Exception {
        try (SshSession s = open()) {
            ExecResult r = s.exec(ExecRequest.of("err oops", 5_000));
            assertEquals("oops\n", r.stderr());
            assertEquals("", r.stdout());
        }
    }

    @Test
    void execPassesStdin() throws Exception {
        try (SshSession s = open()) {
            ExecResult r = s.exec(new ExecRequest("cat", "piped-in".getBytes(StandardCharsets.UTF_8),
                    5_000, false, null));
            assertEquals("piped-in", r.stdout());
        }
    }

    @Test
    void execTimesOutAndReportsIt() throws Exception {
        try (SshSession s = open()) {
            long t0 = System.nanoTime();
            ExecResult r = s.exec(ExecRequest.of("sleep 10000", 500));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(r.timedOut(), "expected timedOut");
            assertTrue(ms < 5_000, "returned promptly, took " + ms + " ms");
        }
    }

    @Test
    void execCapsLargeOutputInsteadOfExhaustingMemory() throws Exception {
        try (SshSession s = SshSession.connect(base().maxInMemoryBytes(1_000).build())) {
            ExecResult r = s.exec(ExecRequest.of("flood 100000", 10_000));
            assertEquals(1_000, r.stdout().length());
            assertTrue(r.truncated());
            assertEquals(0, r.exitStatus());
        }
    }

    // ------------------------------------------------------------ SFTP

    @Test
    void sftpPutThenGetRoundTrip() throws Exception {
        byte[] data = "payload-\u00e9\u4e2d".getBytes(StandardCharsets.UTF_8);
        try (SshSession s = open()) {
            s.sftpPut(data, "/hello.txt", false);
            assertArrayEquals(data, s.sftpGet("/hello.txt"));
        }
        assertArrayEquals(data, Files.readAllBytes(server.root().resolve("hello.txt")));
    }

    @Test
    void sftpListReturnsEntriesWithTypes() throws Exception {
        server.seed("dir/a.txt", "aaa");
        server.seed("dir/b.txt", "bb");
        Files.createDirectories(server.root().resolve("dir/sub"));
        try (SshSession s = open()) {
            List<RemoteEntry> entries = s.sftpList("/dir");
            assertEquals(3, entries.size());
            RemoteEntry a = entries.stream().filter(e -> e.name().equals("a.txt")).findFirst().orElseThrow();
            assertEquals(3, a.size());
            assertEquals(RemoteEntry.Type.FILE, a.type());
            RemoteEntry sub = entries.stream().filter(e -> e.name().equals("sub")).findFirst().orElseThrow();
            assertEquals(RemoteEntry.Type.DIRECTORY, sub.type());
        }
    }

    private void seedTree() throws Exception {
        server.seed("t/a.csv", "1");
        server.seed("t/b.txt", "22");
        server.seed("t/.hidden.csv", "3");
        server.seed("t/sub/c.csv", "4");
        server.seed("t/sub/d.log", "5");
        server.seed("t/sub/deep/e.csv", "6");
        server.seed("t/.cache/f.csv", "7");
    }

    private static List<String> names(List<RemoteEntry> entries) {
        return entries.stream().map(RemoteEntry::name).sorted().toList();
    }

    @Test
    void sftpListWithDefaultOptionsIsOneLevelWithHiddenEntries() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            assertEquals(List.of(".cache", ".hidden.csv", "a.csv", "b.txt", "sub"), names(s.sftpList("/t")));
            assertEquals(names(s.sftpList("/t")), names(s.sftpList("/t", ListOptions.DEFAULT)));
            assertEquals(names(s.sftpList("/t")), names(s.sftpList("/t", null)));
        }
    }

    @Test
    void sftpListFilterMatchesNamesOnlyAndStaysOneLevelWithoutRecursion() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            assertEquals(List.of(".hidden.csv", "a.csv"), names(s.sftpList("/t", new ListOptions("*.csv", false, true))));
            assertEquals(List.of("a.csv", "b.txt"), names(s.sftpList("/t", new ListOptions("[ab].*", false, true))));
            assertEquals(List.of("a.csv", "b.txt"), names(s.sftpList("/t", new ListOptions("?.{csv,txt}", false, true))));
            assertEquals(List.of(), names(s.sftpList("/t", new ListOptions("*.CSV", false, true))));
            assertEquals(List.of("a.csv"), names(s.sftpList("/t", new ListOptions("  a.csv".trim(), false, true))));
        }
    }

    @Test
    void sftpListBlankFilterMeansNoFilter() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            assertEquals(5, s.sftpList("/t", new ListOptions("", false, true)).size());
            assertEquals(5, s.sftpList("/t", new ListOptions("   ", false, true)).size());
        }
    }

    @Test
    void sftpListIncludeHiddenFalseDropsDotEntries() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            assertEquals(List.of("a.csv", "b.txt", "sub"), names(s.sftpList("/t", new ListOptions(null, false, false))));
            assertEquals(List.of("a.csv"), names(s.sftpList("/t", new ListOptions("*.csv", false, false))));
        }
    }

    @Test
    void sftpListRecursiveWalksAllLevelsAndReportsFullPaths() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            List<RemoteEntry> all = s.sftpList("/t", new ListOptions(null, true, true));
            assertEquals(List.of(".cache", ".hidden.csv", "a.csv", "b.txt", "c.csv", "d.log", "deep", "e.csv",
                    "f.csv", "sub"), names(all));
            RemoteEntry e = all.stream().filter(x -> x.name().equals("e.csv")).findFirst().orElseThrow();
            assertEquals("/t/sub/deep/e.csv", e.path());
            assertEquals(RemoteEntry.Type.FILE, e.type());
        }
    }

    @Test
    void sftpListRecursiveIsBreadthFirst() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            List<String> paths = s.sftpList("/t", new ListOptions(null, true, true)).stream()
                    .map(RemoteEntry::path).toList();
            assertTrue(paths.indexOf("/t/sub") < paths.indexOf("/t/sub/c.csv"));
            assertTrue(paths.indexOf("/t/b.txt") < paths.indexOf("/t/sub/deep"));
            assertTrue(paths.indexOf("/t/sub/d.log") < paths.indexOf("/t/sub/deep/e.csv"));
        }
    }

    @Test
    void sftpListRecursiveFilterStillSearchesDirectoriesThatDoNotMatch() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            List<RemoteEntry> csv = s.sftpList("/t", new ListOptions("*.csv", true, true));
            assertEquals(List.of(".hidden.csv", "a.csv", "c.csv", "e.csv", "f.csv"), names(csv));
            assertTrue(csv.stream().noneMatch(x -> x.type() == RemoteEntry.Type.DIRECTORY));
            List<RemoteEntry> dirs = s.sftpList("/t", new ListOptions("s*", true, true));
            assertEquals(List.of("sub"), names(dirs));
        }
    }

    @Test
    void sftpListRecursiveWithoutHiddenSkipsHiddenDirectoriesEntirely() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            List<RemoteEntry> csv = s.sftpList("/t", new ListOptions("*.csv", true, false));
            assertEquals(List.of("a.csv", "c.csv", "e.csv"), names(csv));
        }
    }

    @Test
    void sftpListRecursiveOnAnEmptyDirectoryReturnsNothing() throws Exception {
        Files.createDirectories(server.root().resolve("empty"));
        try (SshSession s = open()) {
            assertEquals(0, s.sftpList("/empty", new ListOptions(null, true, true)).size());
        }
    }

    @Test
    void sftpListEntryCapRaisesLimitExceededInsteadOfGrowingWithoutBound() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            assertEquals(10, s.sftpList("/t", new ListOptions(null, true, true, 10)).size());
            SshAdapterException e = assertThrows(SshAdapterException.class,
                    () -> s.sftpList("/t", new ListOptions(null, true, true, 9)));
            assertEquals(ErrorCode.LIMIT_EXCEEDED, e.getCode());
        }
    }

    @Test
    void sftpListEntriesThatDoNotMatchTheFilterDoNotCountTowardsTheCap() throws Exception {
        seedTree();
        try (SshSession s = open()) {
            assertEquals(1, s.sftpList("/t", new ListOptions("a.csv", true, true, 1)).size());
        }
    }

    @Test
    void sftpListRejectsAMalformedFilterBeforeAnyNetworkTrafficAndABlankPath() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException bad = assertThrows(SshAdapterException.class,
                    () -> s.sftpList("/", new ListOptions("a[bc", false, true)));
            assertEquals(ErrorCode.INVALID_CONFIG, bad.getCode());
            SshAdapterException blank = assertThrows(SshAdapterException.class, () -> s.sftpList(" "));
            assertEquals(ErrorCode.INVALID_CONFIG, blank.getCode());
        }
    }

    @Test
    void sftpListRecursiveOnAMissingDirectoryReportsNoSuchFile() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = assertThrows(SshAdapterException.class,
                    () -> s.sftpList("/nope", new ListOptions(null, true, true)));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
        }
    }

    @Test
    void listOptionsRejectsANonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> new ListOptions(null, false, true, 0));
    }

    @Test
    void sftpStatReturnsNullWhenMissing() throws Exception {
        server.seed("x.txt", "12345");
        try (SshSession s = open()) {
            assertNull(s.sftpStat("/missing.txt"));
            RemoteEntry e = s.sftpStat("/x.txt");
            assertNotNull(e);
            assertEquals(5, e.size());
        }
    }

    @Test
    void sftpMissingFileMapsToSftpStatusNoSuchFile() throws Exception {
        try (SshSession s = open()) {
            SshAdapterException e = assertThrows(SshAdapterException.class, () -> s.sftpGet("/nope.txt"));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
            assertEquals("NO_SUCH_FILE", e.getSftpStatus());
        }
    }

    @Test
    void sftpGetRejectsADirectoryInsteadOfCopyingOrCrashing() throws Exception {
        Files.createDirectories(server.root().resolve("dir/sub"));
        Files.writeString(server.root().resolve("dir/inside.txt"), "x");
        try (SshSession s = open()) {
            SshAdapterException e = assertThrows(SshAdapterException.class, () -> s.sftpGet("/dir"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
        }
    }

    @Test
    void sftpGetToFileRejectsADirectoryAndCreatesNothingLocally() throws Exception {
        Files.createDirectories(server.root().resolve("dir/sub"));
        Files.writeString(server.root().resolve("dir/inside.txt"), "x");
        Path local = tmp.resolve("dl-target");
        try (SshSession s = open()) {
            SshAdapterException e = assertThrows(SshAdapterException.class,
                    () -> s.sftpGetToFile("/dir", local.toString()));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
        }
        assertFalse(Files.exists(local), "a rejected directory download must not create the local path");
    }

    @Test
    void sftpGetToFileStillWritesAFileIntoAnExistingLocalDirectory() throws Exception {
        server.seed("hello.txt", "hi");
        Path localDir = Files.createDirectories(tmp.resolve("dl-dir"));
        try (SshSession s = open()) {
            s.sftpGetToFile("/hello.txt", localDir.toString());
        }
        assertEquals("hi", Files.readString(localDir.resolve("hello.txt")));
    }
    @Test
    void sftpStatCarriesTimesAndOwnerIdsAndFollowLinksFlagWorksForPlainFiles() throws Exception {
        server.seed("plain.txt", "abc");
        try (SshSession s = open()) {
            RemoteEntry followed = s.sftpStat("/plain.txt", true);
            RemoteEntry notFollowed = s.sftpStat("/plain.txt", false);
            assertNotNull(followed);
            assertEquals(RemoteEntry.Type.FILE, followed.type());
            assertEquals(3, followed.size());
            assertTrue(followed.modifiedSec() > 0, "mtime should be set");
            assertEquals(RemoteEntry.Type.FILE, notFollowed.type(), "lstat of a regular file is the file");
            assertNull(s.sftpStat("/missing.txt", false), "lstat of a missing path is null, not an exception");
        }
    }

    @Test
    void sftpStatRejectsABlankPath() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG,
                    assertThrows(SshAdapterException.class, () -> s.sftpStat(" ")).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG,
                    assertThrows(SshAdapterException.class, () -> s.sftpStat(null, false)).getCode());
        }
    }

    @Test
    void sftpRealPathCollapsesDotDotAndRejectsABlankPath() throws Exception {
        server.seed("real.txt", "x");
        Files.createDirectories(server.root().resolve("sub"));
        try (SshSession s = open()) {
            assertEquals("/real.txt", s.sftpRealPath("/sub/../real.txt"));
            assertEquals(ErrorCode.INVALID_CONFIG,
                    assertThrows(SshAdapterException.class, () -> s.sftpRealPath("")).getCode());
        }
    }

    @Test
    void sftpReadLinkRejectsABlankPathAndReportsAMissingPath() throws Exception {
        try (SshSession s = open()) {
            assertEquals(ErrorCode.INVALID_CONFIG,
                    assertThrows(SshAdapterException.class, () -> s.sftpReadLink(null)).getCode());
            SshAdapterException e = assertThrows(SshAdapterException.class, () -> s.sftpReadLink("/nope"));
            assertEquals(ErrorCode.SFTP_STATUS, e.getCode());
        }
    }

    /**
     * Symbolic links need a privilege on Windows, so these checks are skipped where the OS refuses to create one.
     * They are covered against a real OpenSSH server by hand (see the session note of 2026-11-09).
     */
    @Test
    void symbolicLinksAreSeenByLstatAndResolvedByStatRealPathAndReadLink() throws Exception {
        server.seed("target.txt", "linked");
        Path link = server.root().resolve("link.txt");
        try {
            Files.createSymbolicLink(link, Path.of("target.txt"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("cannot create symbolic links here: " + e);
        }
        try (SshSession s = open()) {
            assertEquals(RemoteEntry.Type.SYMLINK, s.sftpStat("/link.txt", false).type());
            assertEquals(RemoteEntry.Type.FILE, s.sftpStat("/link.txt", true).type());
            assertEquals("target.txt", s.sftpReadLink("/link.txt"));
            assertEquals("/target.txt", s.sftpRealPath("/link.txt"));
        }
    }
    @Test
    void sftpMkdirsRenameDeleteRmdir() throws Exception {
        try (SshSession s = open()) {
            s.sftpMkdirs("/a/b/c");
            assertTrue(Files.isDirectory(server.root().resolve("a/b/c")));
            s.sftpPut("x".getBytes(StandardCharsets.UTF_8), "/a/b/c/f.txt", false);
            s.sftpRename("/a/b/c/f.txt", "/a/b/c/g.txt");
            assertTrue(Files.exists(server.root().resolve("a/b/c/g.txt")));
            s.sftpDelete("/a/b/c/g.txt");
            assertFalse(Files.exists(server.root().resolve("a/b/c/g.txt")));
            s.sftpRmdir("/a/b/c");
            assertFalse(Files.exists(server.root().resolve("a/b/c")));
        }
    }

    @Test
    void atomicPutLeavesNoPartFileAndReplacesExisting() throws Exception {
        server.seed("target.txt", "old");
        try (SshSession s = open()) {
            s.sftpPut("new-content".getBytes(StandardCharsets.UTF_8), "/target.txt", true);
        }
        assertEquals("new-content", Files.readString(server.root().resolve("target.txt")));
        assertFalse(Files.exists(server.root().resolve("target.txt.part")));
    }

    @Test
    void inMemoryGetEnforcesCapButFileGetDoesNot() throws Exception {
        byte[] big = new byte[50_000];
        java.util.Arrays.fill(big, (byte) 7);
        Files.write(server.root().resolve("big.bin"), big);
        try (SshSession s = SshSession.connect(base().maxInMemoryBytes(10_000).build())) {
            SshAdapterException e = assertThrows(SshAdapterException.class, () -> s.sftpGet("/big.bin"));
            assertEquals(ErrorCode.LIMIT_EXCEEDED, e.getCode());

            Path local = tmp.resolve("big.local");
            s.sftpGetToFile("/big.bin", local.toString());
            assertArrayEquals(big, Files.readAllBytes(local));
        }
    }

    @Test
    void fileBasedPutStreamsFromDisk() throws Exception {
        Path local = Files.write(tmp.resolve("up.bin"), new byte[]{1, 2, 3, 4});
        try (SshSession s = open()) {
            s.sftpPutFromFile(local.toString(), "/up.bin", true);
        }
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(server.root().resolve("up.bin")));
    }

    // ------------------------------------------------------------ SCP

    @Test
    void scpUploadAndDownloadRoundTrip() throws Exception {
        Path src = Files.writeString(tmp.resolve("scp-src.txt"), "scp-data");
        Path back = tmp.resolve("scp-back.txt");
        try (SshSession s = open()) {
            s.scpUpload(src.toString(), "/scp-dest.txt");
            assertEquals("scp-data", Files.readString(server.root().resolve("scp-dest.txt")));
            s.scpDownload("/scp-dest.txt", back.toString());
        }
        assertEquals("scp-data", Files.readString(back));
    }

    // ------------------------------------------------------------ concurrency (risk R3)

    @Test
    void manyParallelOperationsShareOneTransportSafely() throws Exception {
        int workers = 8;
        int iterations = 10;
        try (SshSession s = open(); ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final int id = w;
                jobs.add(() -> {
                    for (int i = 0; i < iterations; i++) {
                        String path = "/par-" + id + "-" + i + ".txt";
                        byte[] data = ("w" + id + "i" + i).getBytes(StandardCharsets.UTF_8);
                        s.sftpPut(data, path, false);
                        if (!java.util.Arrays.equals(data, s.sftpGet(path))) {
                            return "mismatch " + path;
                        }
                        ExecResult r = s.exec(ExecRequest.of("echo " + id + "-" + i, 10_000));
                        if (!r.stdout().equals(id + "-" + i + "\n")) {
                            return "bad exec output " + r.stdout();
                        }
                    }
                    return "ok";
                });
            }
            for (Future<String> f : pool.invokeAll(jobs)) {
                assertEquals("ok", f.get());
            }
        }
    }
}
