package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of {@link SshSession#runShell} and {@link SshSession#runSubsystem} against the embedded server with a fake
 * interactive shell ({@link FakeInteractiveShell}) and three fake subsystems ({@link FakeSubsystem}).
 *
 * <p>Why: expect-style interaction depends on timing (prompt split over packets, silent peers) and on how the
 * channel ends, so it is proven over a real SSH channel and not with mocks. Every test also checks that the
 * channel slot comes back, whatever way the call ended.
 */
class ShellSubsystemIT {

    private static final String PROMPT = "\\$ $";

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

    private static ShellRequest script(String prompt, long stepTimeoutMs, String... lines) {
        return new ShellRequest(List.of(lines), prompt, stepTimeoutMs, true, true, "\r", StandardCharsets.UTF_8);
    }

    private static void assertSlotsFree(SshSession s) {
        assertEquals(SshConnectionConfig.DEFAULT_MAX_CONCURRENT_CHANNELS, s.availableChannelPermits());
    }

    // ------------------------------------------------------------------ shell

    @Test
    void stateIsKeptAcrossLines() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script(PROMPT, 5_000, "cd work", "pwd"));
            assertTrue(r.completed());
            assertFalse(r.timedOut());
            assertFalse(r.closedByServer());
            assertEquals(2, r.steps().size());
            assertEquals("cd work", r.steps().get(0).input());
            assertTrue(r.steps().get(1).output().contains("/work"), r.steps().get(1).output());
            assertTrue(r.steps().get(1).matched());
            assertTrue(r.transcript().startsWith("Welcome to the fake shell"));
            assertTrue(r.transcript().endsWith("fake:work$ "), r.transcript());
            assertSlotsFree(s);
        }
    }

    @Test
    void lineEndingsLfAndCrlfAreAccepted() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            for (String eol : List.of("\n", "\r\n")) {
                ShellResult r = s.runShell(new ShellRequest(List.of("echo hi"), PROMPT, 5_000, true, true, eol,
                        StandardCharsets.UTF_8));
                assertTrue(r.completed(), "line ending " + eol.replace("\r", "CR").replace("\n", "LF"));
                assertTrue(r.steps().get(0).output().contains("hi\r\n"), r.steps().get(0).output());
            }
            assertSlotsFree(s);
        }
    }

    @Test
    void aPromptDeliveredInPiecesIsNotSplitBetweenSteps() throws Exception {
        // "(?m)^fake" matches the first piece of the prompt that splitprompt prints as "fake", ":split", "$ ".
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script("(?m)^fake", 5_000, "splitprompt", "echo after"));
            assertTrue(r.completed());
            assertTrue(r.steps().get(0).output().endsWith("fake:split$ "), r.steps().get(0).output());
            assertTrue(r.steps().get(1).output().startsWith("echo after"), r.steps().get(1).output());
            assertSlotsFree(s);
        }
    }

    @Test
    void aSlowCommandIsWaitedFor() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script(PROMPT, 5_000, "slow"));
            assertTrue(r.completed());
            assertTrue(r.steps().get(0).output().contains("done"));
            assertSlotsFree(s);
        }
    }

    @Test
    void aMissingPromptIsReportedAsTimeoutAndStopsTheScript() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script(PROMPT, 500, "echo before", "hang", "echo never"));
            assertTrue(r.timedOut());
            assertFalse(r.completed());
            assertEquals(2, r.steps().size());
            assertTrue(r.steps().get(0).matched());
            assertFalse(r.steps().get(1).matched());
            assertFalse(r.transcript().contains("never"));
            assertSlotsFree(s);
        }
    }

    @Test
    void noFirstPromptIsATimeoutWithoutSteps() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script("will-not-appear", 400, "echo x"));
            assertTrue(r.timedOut());
            assertFalse(r.completed());
            assertTrue(r.steps().isEmpty());
            assertTrue(r.transcript().contains("Welcome"));
            assertSlotsFree(s);
        }
    }

    @Test
    void anExitInTheMiddleStopsTheScriptAndIsReported() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script(PROMPT, 5_000, "echo a", "exit", "echo b"));
            assertTrue(r.closedByServer());
            assertFalse(r.completed());
            assertFalse(r.timedOut());
            assertEquals(2, r.steps().size());
            assertTrue(r.transcript().contains("logout"));
            assertFalse(r.transcript().contains("echo b"));
            assertSlotsFree(s);
        }
    }

    @Test
    void anExitAsTheLastLineStillCountsAsCompleted() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script(PROMPT, 5_000, "echo a", "exit"));
            assertTrue(r.closedByServer());
            assertTrue(r.completed());
            assertSlotsFree(s);
        }
    }

    @Test
    void outputBeyondTheMemoryLimitStopsTheScriptAsTruncated() throws Exception {
        try (SshSession s = SshSession.connect(base().maxInMemoryBytes(1_000).build())) {
            ShellResult r = s.runShell(script(PROMPT, 5_000, "flood 5000", "echo later"));
            assertTrue(r.truncated());
            assertFalse(r.completed());
            assertEquals(1, r.steps().size());
            assertTrue(r.transcript().length() <= 1_000);
            assertSlotsFree(s);
        }
    }

    @Test
    void standardErrorIsReturnedSeparately() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            ShellResult r = s.runShell(script(PROMPT, 5_000, "err oops"));
            assertTrue(r.completed());
            assertTrue(r.stderr().contains("oops"), r.stderr());
            // the typed line "err oops" is echoed, so "oops" occurs once in the transcript; the error text itself is not in it
            assertEquals(1, r.transcript().split("oops", -1).length - 1, r.transcript());
            assertSlotsFree(s);
        }
    }

    @Test
    void invalidInputIsRejectedBeforeAnyChannelIsOpened() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runShell(script("([", 1_000, "echo x"))).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runShell(script("  ", 1_000, "echo x"))).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runShell(new ShellRequest(List.of(), PROMPT, 1_000, true, true, "\n", null))).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runShell(script(PROMPT, 1_000, "echo a\necho b"))).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runShell(null)).getCode());
            assertSlotsFree(s);
        }
    }

    // ------------------------------------------------------------------ subsystem

    @Test
    void withoutAMarkerTheInputIsEndedAndTheAnswerReadToTheEnd() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            SubsystemResult r = s.runSubsystem(new SubsystemRequest(FakeSubsystem.ECHO,
                    "hello".getBytes(StandardCharsets.UTF_8), null, 5_000));
            assertEquals("ECHO:HELLO", new String(r.output(), StandardCharsets.UTF_8));
            assertTrue(r.closedByServer());
            assertFalse(r.matched());
            assertFalse(r.timedOut());
            assertEquals(7, r.exitStatus());
            assertSlotsFree(s);
        }
    }

    @Test
    void withAMarkerTheAnswerEndsAtTheMarkerAndTheChannelIsClosedByUs() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            SubsystemResult r = s.runSubsystem(new SubsystemRequest(FakeSubsystem.DELIM,
                    "abc\n".getBytes(StandardCharsets.UTF_8), "\\]\\]>\\]\\]>", 5_000));
            assertEquals("REPLY(abc)]]>]]>", new String(r.output(), StandardCharsets.UTF_8));
            assertTrue(r.matched());
            assertFalse(r.closedByServer());
            assertFalse(r.timedOut());
            assertNull(r.exitStatus());
            assertSlotsFree(s);
        }
    }

    @Test
    void aSilentSubsystemIsReportedAsTimeout() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            SubsystemResult r = s.runSubsystem(new SubsystemRequest(FakeSubsystem.SILENT,
                    "ping\n".getBytes(StandardCharsets.UTF_8), "never", 400));
            assertTrue(r.timedOut());
            assertFalse(r.matched());
            assertEquals(0, r.output().length);
            assertSlotsFree(s);
        }
    }

    @Test
    void anUnknownSubsystemIsRefusedWithAClearMessage() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            SshAdapterException e = assertThrows(SshAdapterException.class,
                    () -> s.runSubsystem(new SubsystemRequest("nosuch", null, null, 2_000)));
            assertEquals(ErrorCode.CHANNEL_FAILED, e.getCode());
            assertTrue(e.getMessage().contains("nosuch") && e.getMessage().contains("refused"), e.getMessage());
            assertSlotsFree(s);
        }
    }

    @Test
    void theRealSftpSubsystemAnswersItsInitPacket() throws Exception {
        // SSH_FXP_INIT, version 3: length 5, type 1, version 3. A server replies with SSH_FXP_VERSION (type 2).
        byte[] init = {0, 0, 0, 5, 1, 0, 0, 0, 3};
        try (SshSession s = SshSession.connect(base().build())) {
            SubsystemResult r = s.runSubsystem(new SubsystemRequest("sftp", init, "(?s).{9}", 5_000));
            assertTrue(r.matched());
            assertEquals(2, r.output()[4], "reply type should be SSH_FXP_VERSION");
            assertSlotsFree(s);
        }
    }

    @Test
    void invalidSubsystemInputIsRejected() throws Exception {
        try (SshSession s = SshSession.connect(base().build())) {
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runSubsystem(new SubsystemRequest(" ", null, null, 1_000))).getCode());
            assertEquals(ErrorCode.INVALID_CONFIG, assertThrows(SshAdapterException.class,
                    () -> s.runSubsystem(new SubsystemRequest(FakeSubsystem.ECHO, null, "([", 1_000))).getCode());
            assertSlotsFree(s);
        }
    }
}
