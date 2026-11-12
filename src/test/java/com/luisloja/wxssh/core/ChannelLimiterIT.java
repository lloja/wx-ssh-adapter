package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the per-session channel limiter in {@link SshSession}, against an embedded server that enforces a
 * channel cap the way OpenSSH's {@code MaxSessions} does.
 *
 * <p>Why: with one shared transport and one channel per operation, a burst of parallel operations larger than the
 * server's cap used to fail with {@link ErrorCode#CHANNEL_FAILED} (measured against OpenSSH 10.3: 10 of 40 and
 * 10 of 100 succeeded). These tests pin the replacement behaviour: surplus callers wait, nobody fails, and no slot
 * is ever lost.
 */
class ChannelLimiterIT {

    private static final int SERVER_CAP = 3;

    /**
     * Limiter value used where a test expects every operation to succeed: one below the server cap. With the two
     * values equal the burst tests failed roughly 1 run in 4, and the server log showed MINA refusing an open with
     * "Currently active channels (4) at max.: 3". The likely cause (inferred from that log line, not proven) is
     * that the server confirms a channel close slightly before it removes the channel from its own count, so a
     * slot handed on the moment the client sees the confirmation can make the server briefly count cap + 1.
     * Whether OpenSSH behaves the same is not established by this test; the shipped default of 8 against
     * MaxSessions 10 is what leaves headroom either way, and the setting is documented as "at or below".
     */
    private static final int LIMITER_WITH_HEADROOM = SERVER_CAP - 1;
    @TempDir
    Path tmp;

    private TestSshServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestSshServer(Files.createDirectories(tmp.resolve("remote-root")), tmp.resolve("hostkey.ser"));
        server.limitChannels(SERVER_CAP);
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

    /** Runs {@code jobs} on as many threads as there are jobs, so all of them start together. */
    private static List<String> runAll(List<Callable<String>> jobs) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(jobs.size())) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> job : jobs) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return job.call();
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @Test
    void theServerReallyEnforcesItsCapSoTheOtherTestsMeanSomething() throws Exception {
        // limiter set far above the server cap: the surplus must be refused by the server
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(50).build())) {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                jobs.add(() -> {
                    try {
                        s.exec(ExecRequest.of("sleep 400", 10_000));
                        return "ok";
                    } catch (SshAdapterException e) {
                        return e.getCode().name();
                    }
                });
            }
            List<String> results = runAll(jobs);
            assertTrue(results.contains("CHANNEL_FAILED"),
                    "the embedded server should have refused some channels, got " + results);
        }
    }

    @Test
    void burstLargerThanTheServerCapAllSucceedsWhenTheLimiterIsBelowIt() throws Exception {
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(LIMITER_WITH_HEADROOM).build())) {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final int n = i;
                jobs.add(() -> {
                    ExecResult r = s.exec(ExecRequest.of("echo job" + n, 20_000));
                    return r.stdout().equals("job" + n + "\n") ? "ok" : "bad output: " + r.stdout();
                });
            }
            List<String> results = runAll(jobs);
            assertEquals(40, results.stream().filter("ok"::equals).count(), "results: " + results);
            assertEquals(LIMITER_WITH_HEADROOM, s.availableChannelPermits(), "every slot must be returned");
        }
    }

    @Test
    void aMixOfExecSftpAndScpUnderTheCapAllSucceeds() throws Exception {
        Path local = Files.writeString(tmp.resolve("scp-src.txt"), "scp");
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(LIMITER_WITH_HEADROOM).build())) {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                final int n = i;
                jobs.add(() -> {
                    switch (n % 3) {
                        case 0 -> {
                            byte[] data = ("d" + n).getBytes(StandardCharsets.UTF_8);
                            s.sftpPut(data, "/f" + n + ".txt", false);
                            if (!java.util.Arrays.equals(data, s.sftpGet("/f" + n + ".txt"))) {
                                return "mismatch " + n;
                            }
                        }
                        case 1 -> {
                            if (!s.exec(ExecRequest.of("echo x", 20_000)).stdout().equals("x\n")) {
                                return "bad exec " + n;
                            }
                        }
                        default -> s.scpUpload(local.toString(), "/scp" + n + ".txt");
                    }
                    return "ok";
                });
            }
            List<String> results = runAll(jobs);
            assertEquals(30, results.stream().filter("ok"::equals).count(), "results: " + results);
            assertEquals(LIMITER_WITH_HEADROOM, s.availableChannelPermits());
        }
    }

    @Test
    void neverMoreOperationsHoldAChannelThanTheLimiterAllows() throws Exception {
        int limit = 2;
        AtomicInteger maxSeen = new AtomicInteger();
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(limit).build())) {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                jobs.add(() -> {
                    // a running exec holds a slot, so the free-slot count seen from inside can never go below 0 and
                    // the number held (limit - free) can never exceed the limit
                    Thread watcher = Thread.ofPlatform().daemon().start(() -> {
                        for (int k = 0; k < 20; k++) {
                            int held = limit - s.availableChannelPermits();
                            maxSeen.accumulateAndGet(held, Math::max);
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    });
                    s.exec(ExecRequest.of("sleep 150", 20_000));
                    watcher.join();
                    return "ok";
                });
            }
            runAll(jobs);
        }
        assertTrue(maxSeen.get() >= 1, "the watcher should have seen a held slot");
        assertTrue(maxSeen.get() <= limit, "held " + maxSeen.get() + " slots with a limit of " + limit);
    }

    @Test
    void aCallerWaitsInsteadOfFailingWhenAllSlotsAreBusy() throws Exception {
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(1).build())) {
            long t0 = System.nanoTime();
            List<Callable<String>> jobs = List.of(
                    () -> s.exec(ExecRequest.of("sleep 600", 20_000)).timedOut() ? "timedOut" : "ok",
                    () -> {
                        Thread.sleep(150);
                        return s.exec(ExecRequest.of("echo waited", 20_000)).stdout().trim();
                    });
            List<String> results = runAll(jobs);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(List.of("ok", "waited"), results);
            assertTrue(ms >= 600, "the second call must have waited for the first, took " + ms + " ms");
            assertEquals(1, s.availableChannelPermits());
        }
    }

    @Test
    void waitingLongerThanTheSocketTimeoutFailsWithTimeoutAndLeavesNoSlotLeaked() throws Exception {
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(1).socketTimeoutMs(500).build())) {
            List<Callable<String>> jobs = List.of(
                    () -> s.exec(ExecRequest.of("sleep 1500", 0)).timedOut() ? "timedOut" : "ok",
                    () -> {
                        Thread.sleep(150);
                        try {
                            s.exec(ExecRequest.of("echo never", 5_000));
                            return "unexpectedly ran";
                        } catch (SshAdapterException e) {
                            return e.getCode().name();
                        }
                    });
            List<String> results = runAll(jobs);
            assertEquals("ok", results.get(0));
            assertEquals("TIMEOUT", results.get(1));
            assertEquals(1, s.availableChannelPermits(), "the timed-out waiter must not have taken or lost a slot");
        }
    }

    @Test
    void aFailingOperationGivesItsSlotBack() throws Exception {
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(2).build())) {
            for (int i = 0; i < 6; i++) {
                assertThrows(SshAdapterException.class, () -> s.sftpGet("/does-not-exist.txt"));
                assertThrows(SshAdapterException.class, () -> s.scpDownload("/does-not-exist.txt",
                        tmp.resolve("never").toString()));
                assertThrows(SshAdapterException.class, () -> s.sftpList("/does-not-exist"));
            }
            assertEquals(2, s.availableChannelPermits(), "failures must release their slot");
            assertEquals("ok\n", s.exec(ExecRequest.of("echo ok", 5_000)).stdout());
        }
    }

    @Test
    void anInterruptedWaiterGetsChannelFailedKeepsItsInterruptFlagAndTakesNoSlot() throws Exception {
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(1).build())) {
            try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
                Future<?> holder = pool.submit(() -> {
                    try {
                        return s.exec(ExecRequest.of("sleep 1200", 20_000));
                    } catch (SshAdapterException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(200);
                assertEquals(0, s.availableChannelPermits(), "the holder should own the only slot");

                Thread.currentThread().interrupt();
                SshAdapterException e = assertThrows(SshAdapterException.class,
                        () -> s.exec(ExecRequest.of("echo no", 5_000)));
                assertEquals(ErrorCode.CHANNEL_FAILED, e.getCode());
                assertTrue(Thread.interrupted(), "the interrupt flag must be restored");

                holder.get(30, TimeUnit.SECONDS);
            }
            assertEquals(1, s.availableChannelPermits());
        }
    }

    @Test
    void anInvalidArgumentThatNeverOpensAChannelDoesNotConsumeASlot() throws Exception {
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(1).build())) {
            for (int i = 0; i < 5; i++) {
                assertThrows(SshAdapterException.class, () -> s.sftpStat(" "));
                assertThrows(SshAdapterException.class, () -> s.sftpList("/", new ListOptions("a[bc", false, true)));
                assertThrows(SshAdapterException.class, () -> s.exec(ExecRequest.of(" ", 1_000)));
            }
            assertEquals(1, s.availableChannelPermits());
        }
    }

    @Test
    void aRecursiveListHoldsOneSlotForTheWholeWalk() throws Exception {
        for (int d = 0; d < 6; d++) {
            server.seed("t/d" + d + "/f.txt", "x");
        }
        try (SshSession s = SshSession.connect(base().maxConcurrentChannels(1).build())) {
            // with a single slot a walk that opened a channel per directory would deadlock against itself
            assertEquals(12, s.sftpList("/t", new ListOptions(null, true, true)).size());
            assertEquals(1, s.availableChannelPermits());
        }
    }

    @Test
    void closingTheSessionWhileCallersWaitDoesNotHangThem() throws Exception {
        SshSession s = SshSession.connect(base().maxConcurrentChannels(1).build());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> holder = pool.submit(() -> {
                try {
                    s.exec(ExecRequest.of("sleep 2000", 20_000));
                    return "finished";
                } catch (SshAdapterException e) {
                    return e.getCode().name();
                }
            });
            Thread.sleep(200);
            Future<String> waiter = pool.submit(() -> {
                try {
                    s.exec(ExecRequest.of("echo late", 20_000));
                    return "ran";
                } catch (SshAdapterException e) {
                    return e.getCode().name();
                }
            });
            Thread.sleep(200);
            s.close();
            String waiterResult = waiter.get(30, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);
            assertEquals("CHANNEL_FAILED", waiterResult, "a waiter woken by the close must get a clean error");
            assertFalse(s.isOpen());
        }
    }

    @Test
    void theConfigurationRejectsANonPositiveLimitAndDefaultsToEight() {
        SshAdapterException e = assertThrows(SshAdapterException.class,
                () -> SshSession.connect(base().maxConcurrentChannels(0).build()));
        assertEquals(ErrorCode.INVALID_CONFIG, e.getCode());
        assertThrows(SshAdapterException.class, () -> base().maxConcurrentChannels(-3).build().validate());
        assertEquals(8, base().build().maxConcurrentChannels());
        assertEquals(SshConnectionConfig.DEFAULT_MAX_CONCURRENT_CHANNELS, base().build().maxConcurrentChannels());
    }
}
