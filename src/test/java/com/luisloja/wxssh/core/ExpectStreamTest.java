package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExpectStream}, driven through an in-memory pipe so no SSH server is involved. They cover
 * the parts the shell and subsystem operations depend on: matching across chunks, deadlines, end of stream, the
 * memory cap and the "settle" pause after a prompt.
 */
class ExpectStreamTest {

    private PipedOutputStream feed;
    private PipedInputStream source;

    @BeforeEach
    void pipe() throws IOException {
        source = new PipedInputStream(1 << 20);
        feed = new PipedOutputStream(source);
    }

    @AfterEach
    void closePipe() throws IOException {
        feed.close();
    }

    private void send(String s) throws IOException {
        feed.write(s.getBytes(StandardCharsets.ISO_8859_1));
        feed.flush();
    }

    private ExpectStream stream(long cap) {
        return new ExpectStream(source, cap, "test-expect");
    }

    @Test
    void findsAPromptThatIsAlreadyThere() throws Exception {
        ExpectStream s = stream(1024);
        send("Last login\nuser@host:~$ ");
        ExpectStream.Result r = s.await(Pattern.compile("\\$ $"), 0, 2000);
        assertEquals(ExpectStream.Outcome.MATCHED, r.outcome());
        assertEquals("Last login\nuser@host:~$ ".length(), r.matchEnd());
    }

    @Test
    void findsAPromptThatArrivesInPieces() throws Exception {
        ExpectStream s = stream(1024);
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try {
                send("output line\nhost");
                Thread.sleep(80);
                send(":~");
                Thread.sleep(80);
                send("$ ");
            } catch (Exception ignored) {
            }
        });
        ExpectStream.Result r = s.await(Pattern.compile("host:~\\$ $"), 0, 5000);
        writer.join();
        assertEquals(ExpectStream.Outcome.MATCHED, r.outcome());
    }

    @Test
    void aPatternSplitAcrossTwoChunksIsStillFound() throws Exception {
        ExpectStream s = stream(1 << 20);
        send("x".repeat(20_000) + "]]>");
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(100);
                send("]]>");
            } catch (Exception ignored) {
            }
        });
        ExpectStream.Result r = s.await(Pattern.compile("\\]\\]>\\]\\]>"), 0, 5000);
        writer.join();
        assertEquals(ExpectStream.Outcome.MATCHED, r.outcome());
        assertEquals(20_006, r.matchEnd());
    }

    @Test
    void searchStartsAtTheGivenOffset() throws Exception {
        ExpectStream s = stream(1024);
        send("one$ two");
        ExpectStream.Result first = s.await(Pattern.compile("\\$ "), 0, 2000);
        assertEquals(ExpectStream.Outcome.MATCHED, first.outcome());
        // the same text is not found again from after the match
        ExpectStream.Result second = s.await(Pattern.compile("\\$ "), first.matchEnd(), 150);
        assertEquals(ExpectStream.Outcome.TIMEOUT, second.outcome());
        send(" three$ ");
        ExpectStream.Result third = s.await(Pattern.compile("\\$ "), first.matchEnd(), 2000);
        assertEquals(ExpectStream.Outcome.MATCHED, third.outcome());
    }

    @Test
    void reportsTimeoutWhenNothingMatchesAndTheStreamStaysOpen() throws Exception {
        ExpectStream s = stream(1024);
        send("no prompt here");
        long t0 = System.nanoTime();
        ExpectStream.Result r = s.await(Pattern.compile("\\$ $"), 0, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ExpectStream.Outcome.TIMEOUT, r.outcome());
        assertEquals("no prompt here".length(), r.matchEnd());
        assertTrue(ms >= 150 && ms < 3000, "waited " + ms + " ms");
    }

    @Test
    void reportsEofWhenTheStreamEndsWithoutAMatch() throws Exception {
        ExpectStream s = stream(1024);
        send("bye");
        feed.close();
        ExpectStream.Result r = s.await(Pattern.compile("\\$ $"), 0, 5000);
        assertEquals(ExpectStream.Outcome.EOF, r.outcome());
        assertTrue(s.eof());
    }

    @Test
    void aMatchWinsOverTheEndOfTheStream() throws Exception {
        ExpectStream s = stream(1024);
        send("done$ ");
        feed.close();
        Thread.sleep(100);
        ExpectStream.Result r = s.await(Pattern.compile("\\$ $"), 0, 5000);
        assertEquals(ExpectStream.Outcome.MATCHED, r.outcome());
    }

    @Test
    void keepsAtMostTheCapAndReportsTruncation() throws Exception {
        ExpectStream s = stream(100);
        send("y".repeat(5000));
        ExpectStream.Result r = s.await(Pattern.compile("NEVER"), 0, 5000);
        assertEquals(ExpectStream.Outcome.TRUNCATED, r.outcome());
        assertEquals(100, s.length());
        assertTrue(s.truncated());
    }

    @Test
    void capIsStillDrainedSoTheWriterNeverBlocks() throws Exception {
        // a pipe far smaller than the data: the writer only finishes if the reader keeps reading past the cap
        PipedInputStream big = new PipedInputStream(1024);
        PipedOutputStream bigOut = new PipedOutputStream(big);
        ExpectStream bs = new ExpectStream(big, 10, "test-expect-big");
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try {
                bigOut.write(new byte[200_000]);
                bigOut.close();
            } catch (IOException ignored) {
            }
        });
        ExpectStream.Result r = bs.awaitEnd(10_000);
        writer.join(5000);
        assertFalse(writer.isAlive(), "writer was left blocked");
        assertEquals(ExpectStream.Outcome.EOF, r.outcome());
        assertEquals(10, bs.length());
        assertTrue(bs.truncated());
    }

    @Test
    void awaitEndWaitsForTheStreamToEnd() throws Exception {
        ExpectStream s = stream(1024);
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try {
                send("part one ");
                Thread.sleep(100);
                send("part two");
                feed.close();
            } catch (Exception ignored) {
            }
        });
        ExpectStream.Result r = s.awaitEnd(5000);
        writer.join();
        assertEquals(ExpectStream.Outcome.EOF, r.outcome());
        assertEquals("part one part two", s.text(0, r.matchEnd(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void awaitEndTimesOutOnAnOpenStream() throws Exception {
        ExpectStream s = stream(1024);
        send("still going");
        ExpectStream.Result r = s.awaitEnd(150);
        assertEquals(ExpectStream.Outcome.TIMEOUT, r.outcome());
    }

    @Test
    void settleWaitsForTheTailOfAPromptThenStops() throws Exception {
        ExpectStream s = stream(1024);
        send("host:~$");
        ExpectStream.Result r = s.await(Pattern.compile("\\$"), 0, 2000);
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(40);
                send(" ");
            } catch (Exception ignored) {
            }
        });
        int end = s.settle(150, 2000);
        writer.join();
        assertEquals(r.matchEnd() + 1, end);
    }

    @Test
    void settleGivesUpAfterItsBoundOnAStreamThatNeverGoesQuiet() throws Exception {
        ExpectStream s = stream(1 << 20);
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    send("tick");
                    Thread.sleep(20);
                }
            } catch (Exception ignored) {
            }
        });
        long t0 = System.nanoTime();
        s.settle(200, 400);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 1500, "settle took " + ms + " ms");
        writer.interrupt();
    }

    @Test
    void bytesAndTextReturnTheRequestedSlice() throws Exception {
        ExpectStream s = stream(1024);
        send("abcdef");
        s.await(Pattern.compile("f"), 0, 2000);
        assertArrayEquals("cde".getBytes(StandardCharsets.ISO_8859_1), s.bytes(2, 5));
        assertEquals("", s.text(4, 2, StandardCharsets.ISO_8859_1));
        assertEquals("abcdef", s.text(0, 99, StandardCharsets.ISO_8859_1));
    }
}
