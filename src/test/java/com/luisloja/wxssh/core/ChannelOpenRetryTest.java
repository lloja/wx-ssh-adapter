package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import net.schmizz.sshj.connection.channel.OpenFailException;
import net.schmizz.sshj.connection.channel.OpenFailException.Reason;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the channel-open retry in {@link SshSession}. They drive {@code openChannel} with a stub opener,
 * so the rules (which refusals are retried, how often, what is thrown at the end) are pinned without a server.
 * The real race this exists for, a server still counting channels the client has closed, is covered by the manual
 * 40/100-parallel run against OpenSSH recorded in the session notes, because it cannot be forced on the embedded
 * server.
 */
class ChannelOpenRetryTest {

    private static OpenFailException refusal(Reason reason) {
        return new OpenFailException("session", reason, "open failed");
    }

    @Test
    void succeedsImmediatelyWithoutRetrying() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String result = SshSession.openChannel(() -> {
            calls.incrementAndGet();
            return "channel";
        }, 6, 1);
        assertEquals("channel", result);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesAConnectFailedRefusalUntilItClears() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String result = SshSession.openChannel(() -> {
            if (calls.incrementAndGet() < 4) {
                throw refusal(Reason.CONNECT_FAILED);
            }
            return "channel";
        }, 6, 1);
        assertEquals("channel", result);
        assertEquals(4, calls.get());
    }

    @Test
    void retriesAResourceShortageRefusalToo() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        SshSession.openChannel(() -> {
            if (calls.incrementAndGet() < 2) {
                throw refusal(Reason.RESOURCE_SHORTAGE);
            }
            return "channel";
        }, 6, 1);
        assertEquals(2, calls.get());
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsAndThrowsTheLastRefusal() {
        AtomicInteger calls = new AtomicInteger();
        OpenFailException last = refusal(Reason.CONNECT_FAILED);
        OpenFailException thrown = assertThrows(OpenFailException.class, () -> SshSession.openChannel(() -> {
            if (calls.incrementAndGet() == 3) {
                throw last;
            }
            throw refusal(Reason.CONNECT_FAILED);
        }, 3, 1));
        assertEquals(3, calls.get());
        assertSame(last, thrown);
        // and the facade reports it as a channel failure, the code flows already branch on
        assertEquals(ErrorCode.CHANNEL_FAILED, SshSession.map(thrown, "exec").getCode());
    }

    @Test
    void doesNotRetryAPolicyRefusalOrAnUnknownChannelType() {
        for (Reason reason : new Reason[]{Reason.ADMINISTRATIVELY_PROHIBITED, Reason.UNKNOWN_CHANNEL_TYPE,
                Reason.UNKNOWN}) {
            AtomicInteger calls = new AtomicInteger();
            assertThrows(OpenFailException.class, () -> SshSession.openChannel(() -> {
                calls.incrementAndGet();
                throw refusal(reason);
            }, 6, 1));
            assertEquals(1, calls.get(), "a " + reason + " refusal must not be retried");
        }
    }

    @Test
    void doesNotRetryOtherIoFailures() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> SshSession.openChannel(() -> {
            calls.incrementAndGet();
            throw new IOException("connection reset");
        }, 6, 1));
        assertEquals(1, calls.get());
    }

    @Test
    void anInterruptDuringThePauseStopsTheRetriesAndKeepsTheFlag() {
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        InterruptedIOException e = assertThrows(InterruptedIOException.class, () -> SshSession.openChannel(() -> {
            calls.incrementAndGet();
            throw refusal(Reason.CONNECT_FAILED);
        }, 6, 50));
        assertEquals(1, calls.get(), "no second attempt after the interrupt");
        assertTrue(e.getCause() instanceof OpenFailException);
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
    }

    @Test
    void pausesGrowSoTheTotalWaitIsBounded() throws IOException {
        long t0 = System.nanoTime();
        assertThrows(OpenFailException.class, () -> SshSession.openChannel(() -> {
            throw refusal(Reason.CONNECT_FAILED);
        }, 4, 20));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // pauses 20 + 40 + 80 = 140 ms between four attempts
        assertTrue(ms >= 130, "should have paused about 140 ms, took " + ms);
        assertTrue(ms < 2_000, "should not wait unboundedly, took " + ms);
    }
}
