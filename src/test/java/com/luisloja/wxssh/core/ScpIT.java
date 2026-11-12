package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the SCP calls of {@link SshSession} against the embedded server: the path-based calls with their
 * directory guard, the in-memory calls with the memory cap, and that every call gives its channel slot back.
 *
 * <p>Why separate from {@code SshSessionIT}: SCP's directory semantics are the surprising part and are measured
 * here on purpose; the same sequence was also run by hand against OpenSSH 10.3.
 */
class ScpIT {

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

    private SshConnectionConfig.Builder base() {
        return SshConnectionConfig.builder()
                .host("127.0.0.1").port(server.port())
                .username(TestSshServer.USER).password(TestSshServer.PASSWORD)
                .hostKeyPolicy(HostKeyPolicy.ACCEPT_ANY)
                .connectTimeoutMs(5_000).socketTimeoutMs(20_000);
    }

    private static void assertSlotsFree(SshSession s) {
        assertEquals(SshConnectionConfig.DEFAULT_MAX_CONCURRENT_CHANNELS, s.availableChannelPermits());
    }

    private Path localTree() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src").resolve("sub"));
        Files.writeString(dir.getParent().resolve("a.txt"), "alpha");
        Files.writeString(dir.resolve("b.txt"), "beta");
        return dir.getParent();
    }

    @Test
    void aFileRoundTripsThroughThePathCalls() throws Exception {
        Path src = Files.writeString(tmp.resolve("one.txt"), "scp-data");
        Path back = tmp.resolve("back.txt");
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUpload(src.toString(), "/one.txt", false);
            assertEquals("scp-data", Files.readString(server.root().resolve("one.txt")));
            s.scpDownload("/one.txt", back.toString());
            assertSlotsFree(s);
        }
        assertEquals("scp-data", Files.readString(back));
    }

    @Test
    void bytesRoundTripThroughTheMemoryCalls() throws Exception {
        byte[] data = {0, 1, 2, (byte) 0xFF, 10, 13, 0};
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUploadBytes(data, "/blob.bin");
            assertArrayEquals(data, Files.readAllBytes(server.root().resolve("blob.bin")));
            assertArrayEquals(data, s.scpDownloadBytes("/blob.bin"));
            s.scpUploadBytes(new byte[0], "/empty.bin");
            assertEquals(0, s.scpDownloadBytes("/empty.bin").length);
            assertSlotsFree(s);
        }
    }

    @Test
    void aDirectoryIsRefusedUnlessRecursiveAndNothingIsCreated() throws Exception {
        Path tree = localTree();
        try (SshSession s = SshSession.connect(base().build())) {
            SshAdapterException e = assertThrows(SshAdapterException.class,
                    () -> s.scpUpload(tree.toString(), "/tree", false));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertTrue(e.getMessage().contains("recursive"), e.getMessage());
            assertFalse(Files.exists(server.root().resolve("tree")));
            assertSlotsFree(s);
        }
    }

    @Test
    void recursiveUploadCopiesContentsToANewDestinationAndNestsOnTheSecondRun() throws Exception {
        Path tree = localTree();
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUpload(tree.toString(), "/tree", true);
            assertEquals("alpha", Files.readString(server.root().resolve("tree/a.txt")));
            assertEquals("beta", Files.readString(server.root().resolve("tree/sub/b.txt")));
            // The documented trap: the destination exists now, so the source directory is created inside it.
            s.scpUpload(tree.toString(), "/tree", true);
            assertEquals("alpha", Files.readString(server.root().resolve("tree/src/a.txt")));
            assertSlotsFree(s);
        }
    }

    @Test
    void aDirectoryDownloadsAsATree() throws Exception {
        Path tree = localTree();
        Path back = tmp.resolve("back-tree");
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUpload(tree.toString(), "/tree", true);
            s.scpDownload("/tree", back.toString());
            assertSlotsFree(s);
        }
        assertEquals("alpha", Files.readString(back.resolve("a.txt")));
        assertEquals("beta", Files.readString(back.resolve("sub").resolve("b.txt")));
    }

    @Test
    void aDirectoryIsRefusedByANonRecursiveDownloadAndNothingIsCreatedLocally() throws Exception {
        Path tree = localTree();
        Path back = tmp.resolve("not-created");
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUpload(tree.toString(), "/tree", true);
            assertThrows(SshAdapterException.class, () -> s.scpDownload("/tree", back.toString(), false));
            assertFalse(Files.exists(back), "a refused directory download must not create the destination");
            assertSlotsFree(s);
            assertTrue(s.isOpen());
        }
    }

    @Test
    void aSingleFileDownloadsWithRecursiveOffAndOn() throws Exception {
        Path src = Files.writeString(tmp.resolve("one.txt"), "scp-data");
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUpload(src.toString(), "/one.txt", false);
            s.scpDownload("/one.txt", tmp.resolve("off.txt").toString(), false);
            s.scpDownload("/one.txt", tmp.resolve("on.txt").toString(), true);
            assertSlotsFree(s);
        }
        assertEquals("scp-data", Files.readString(tmp.resolve("off.txt")));
        assertEquals("scp-data", Files.readString(tmp.resolve("on.txt")));
    }
    @Test
    void aDirectoryCannotBeDownloadedIntoMemory() throws Exception {
        Path tree = localTree();
        try (SshSession s = SshSession.connect(base().build())) {
            s.scpUpload(tree.toString(), "/tree", true);
            SshAdapterException e = assertThrows(SshAdapterException.class, () -> s.scpDownloadBytes("/tree"));
            assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
            assertSlotsFree(s);
        }
    }

    @Test
    void theMemoryCapAppliesToDownloads() throws Exception {
        try (SshSession s = SshSession.connect(base().maxInMemoryBytes(100).build())) {
            s.scpUploadBytes(new byte[500], "/big.bin");
            SshAdapterException e = assertThrows(SshAdapterException.class, () -> s.scpDownloadBytes("/big.bin"));
            assertEquals(ErrorCode.LIMIT_EXCEEDED, e.getCode());
            assertSlotsFree(s);
        }
    }

    @Test
    void failuresAreMappedAndGiveTheSlotBack() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            assertThrows(SshAdapterException.class, () -> s.scpDownloadBytes("/nope.bin"));
            assertThrows(SshAdapterException.class,
                    () -> s.scpUpload(tmp.resolve("missing.txt").toString(), "/x.txt", false));
            assertSlotsFree(s);
        }
    }

    @Test
    void blankInputIsRejectedBeforeAnyChannelIsOpened() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.scpUpload(" ", "/x", false)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.scpUpload("a", "", false)).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.scpUploadBytes(null, "/x")).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.scpDownloadBytes(" ")).getCode());
            assertSlotsFree(s);
        }
    }
}
