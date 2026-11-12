package com.luisloja.wxssh.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the output of an interactive channel (a shell or a subsystem) in the background and lets the caller wait
 * until a regular expression shows up in it, the channel ends, or time runs out.
 *
 * <p>Why: expect-style interaction ("send a line, wait for the prompt") cannot be built on a plain blocking read,
 * because the caller must stop waiting after a deadline and must not lose bytes that arrive while it is busy. A
 * daemon thread therefore drains the stream into a buffer for the whole life of the channel (which also keeps the
 * remote side from blocking on a full channel window), and the caller looks at that buffer by offset.
 *
 * <p>Matching: the pattern is applied to a one-to-one view of the bytes (ISO-8859-1, every byte is one char), so
 * prompts and delimiters written with ASCII work whatever the real character set is, and binary data cannot
 * break the matching. To keep the cost linear on large outputs, each wait looks at the bytes that arrived since
 * the last look plus at most {@value #LOOK_BACK} bytes before them, never at the whole output again. A prompt or
 * delimiter longer than that, or a pattern that needs text from further back, will not be found.
 *
 * <p>Memory: at most {@code cap} bytes are kept. Bytes beyond that are read and thrown away, and
 * {@link #truncated()} turns true; a wait that has not found its match by then reports {@link Outcome#TRUNCATED}.
 *
 * <p>Side effects: starts one daemon thread that reads the given stream until it ends or fails. The thread cannot
 * be interrupted out of a blocking read, so the caller ends it by closing the channel that owns the stream.
 */
final class ExpectStream implements AutoCloseable {

    /** How many bytes before the newest data a wait still looks at when it searches for its pattern. */
    static final int LOOK_BACK = 8 * 1024;

    /** Why a wait returned. */
    enum Outcome {
        /** The pattern was found. */
        MATCHED,
        /** The deadline passed with no match and the stream still open. */
        TIMEOUT,
        /** The stream ended (the remote side closed the channel) with no match. */
        EOF,
        /** The memory cap was reached before a match was found. */
        TRUNCATED
    }

    /**
     * Result of a wait.
     *
     * @param outcome  why the wait returned
     * @param matchEnd offset just after the matched text when {@code outcome} is {@code MATCHED}, otherwise the
     *                 number of bytes held when the wait returned
     */
    record Result(Outcome outcome, int matchEnd) {
    }

    private final Object lock = new Object();
    private final long cap;
    private byte[] buf = new byte[4096];
    private int len;
    private boolean eof;
    private boolean truncated;

    /**
     * Starts draining {@code in}.
     *
     * @param in         the channel's output stream; closed by the reader thread when it ends
     * @param cap        most bytes to keep; more are read and discarded
     * @param threadName name of the reader thread, so it can be recognised in a thread dump
     */
    ExpectStream(InputStream in, long cap, String threadName) {
        this.cap = cap;
        Thread.ofPlatform().daemon().name(threadName).start(() -> read(in));
    }

    private void read(InputStream in) {
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                append(chunk, n);
            }
        } catch (IOException ignored) {
            // the channel was closed under us (timeout or remote close): what was read so far is kept
        } finally {
            synchronized (lock) {
                eof = true;
                lock.notifyAll();
            }
        }
    }

    private void append(byte[] chunk, int n) {
        synchronized (lock) {
            long room = cap - len;
            int take = (int) Math.max(0, Math.min(n, room));
            if (take < n) {
                truncated = true;
            }
            if (take > 0) {
                if (len + take > buf.length) {
                    buf = Arrays.copyOf(buf, (int) Math.min(Integer.MAX_VALUE - 8L, Math.max(buf.length * 2L, (long) len + take)));
                }
                System.arraycopy(chunk, 0, buf, len, take);
                len += take;
            }
            lock.notifyAll();
        }
    }

    /** @return the number of bytes held now */
    int length() {
        synchronized (lock) {
            return len;
        }
    }

    /** @return true once the stream has ended */
    boolean eof() {
        synchronized (lock) {
            return eof;
        }
    }

    /** @return true when output was discarded because the cap was reached */
    boolean truncated() {
        synchronized (lock) {
            return truncated;
        }
    }

    /**
     * Waits for {@code pattern} to appear in the output at or after {@code from}.
     *
     * <p>Why the order of checks: a match always wins, even when the stream has ended or the cap was reached in the
     * same moment, because the last prompt often arrives together with the end of the channel.
     *
     * <p>Side effects: blocks the calling thread for up to {@code timeoutMs}; nothing is consumed, so the same
     * bytes can be searched again.
     *
     * @param pattern   what to look for; not null
     * @param from      first byte offset to consider
     * @param timeoutMs longest wait in milliseconds; 0 or less waits without a deadline
     * @return the outcome, and the offset after the match or the current length
     * @throws InterruptedException when the waiting thread is interrupted
     */
    Result await(Pattern pattern, int from, long timeoutMs) throws InterruptedException {
        long deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;
        int searched = from;
        synchronized (lock) {
            while (true) {
                int start = Math.max(from, searched - LOOK_BACK);
                if (len > start) {
                    Matcher m = pattern.matcher(view(start, len));
                    if (m.find()) {
                        return new Result(Outcome.MATCHED, start + m.end());
                    }
                }
                searched = len;
                if (truncated) {
                    return new Result(Outcome.TRUNCATED, len);
                }
                if (eof) {
                    return new Result(Outcome.EOF, len);
                }
                if (!pause(deadline)) {
                    return new Result(Outcome.TIMEOUT, len);
                }
            }
        }
    }

    /**
     * Waits for the stream to end.
     *
     * <p>Why: a subsystem that is not given a pattern is finished when the server closes the channel.
     *
     * @param timeoutMs longest wait in milliseconds; 0 or less waits without a deadline
     * @return {@code EOF}, or {@code TIMEOUT} when the stream was still open at the deadline. Reaching the memory
     *         cap does not end this wait (the rest is read and discarded); {@link #truncated()} tells whether data
     *         was lost
     * @throws InterruptedException when the waiting thread is interrupted
     */
    Result awaitEnd(long timeoutMs) throws InterruptedException {
        long deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;
        synchronized (lock) {
            while (!eof) {
                if (!pause(deadline)) {
                    return new Result(Outcome.TIMEOUT, len);
                }
            }
            return new Result(Outcome.EOF, len);
        }
    }

    /**
     * Lets trailing bytes of what was just matched arrive.
     *
     * <p>Why: a prompt such as {@code "$ "} can be delivered in two pieces, and a pattern that already matches the
     * first piece would leave the second one to be mistaken for the start of the next step's output. Waiting until
     * the output has been quiet for a moment makes the step boundary land after the whole prompt.
     *
     * <p>Side effects: blocks the calling thread, at most {@code maxMs} in total.
     *
     * @param quietMs how long the output must stay unchanged
     * @param maxMs   upper bound for the whole wait, so a stream that never goes quiet cannot hold the caller
     * @return the number of bytes held when the output went quiet or the bound was reached
     * @throws InterruptedException when the waiting thread is interrupted
     */
    int settle(long quietMs, long maxMs) throws InterruptedException {
        long limit = System.nanoTime() + maxMs * 1_000_000L;
        synchronized (lock) {
            while (!eof) {
                int before = len;
                long left = (limit - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    break;
                }
                lock.wait(Math.min(quietMs, left));
                if (len == before) {
                    break;
                }
            }
            return len;
        }
    }

    /**
     * Copies a slice of the output.
     *
     * @param from first byte offset
     * @param to   offset after the last byte; clamped to the bytes held
     * @return the bytes, possibly empty
     */
    byte[] bytes(int from, int to) {
        synchronized (lock) {
            int end = Math.min(to, len);
            return from >= end ? new byte[0] : Arrays.copyOfRange(buf, from, end);
        }
    }

    /**
     * Decodes a slice of the output.
     *
     * @param from    first byte offset
     * @param to      offset after the last byte
     * @param charset how to turn the bytes into text
     * @return the text
     */
    String text(int from, int to, Charset charset) {
        return new String(bytes(from, to), charset);
    }

    private String view(int from, int to) {
        return new String(buf, from, to - from, StandardCharsets.ISO_8859_1);
    }

    /** Waits on the lock until notified or the deadline; false when the deadline has passed. Caller holds the lock. */
    private boolean pause(long deadline) throws InterruptedException {
        if (deadline == 0) {
            lock.wait();
            return true;
        }
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left <= 0) {
            return false;
        }
        lock.wait(left);
        return true;
    }

    /** Nothing to release: the reader thread ends when its channel is closed. Present so it can sit in a try block. */
    @Override
    public void close() {
        // intentionally empty
    }
}
