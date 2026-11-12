package com.luisloja.wxssh.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/**
 * Minimal in-process "shell" for exec tests. Supported commands:
 * <ul>
 *   <li>{@code echo <text>} - writes text + newline to stdout, exit 0</li>
 *   <li>{@code err <text>} - writes text + newline to stderr, exit 0</li>
 *   <li>{@code cat} - copies stdin to stdout until EOF, exit 0</li>
 *   <li>{@code fail <n>} - exit status n</li>
 *   <li>{@code sleep <ms>} - waits then exits 0 (used for timeout tests)</li>
 *   <li>{@code flood <bytes>} - writes that many 'x' bytes to stdout (used for cap tests)</li>
 *   <li>{@code getent passwd|group NAME} - a tiny account table: {@code wxtest} is user 1000, {@code root} 0, group
 *       {@code users} 100; an unknown name exits 2 like the real tool; {@code nolookup} exits 1 to force the
 *       caller onto its flat-file fallback</li>
 * </ul>
 * Why not a real shell: portability. The tests must pass on Windows developer machines and CI alike.
 */
final class FakeShellCommand implements Command, Runnable {

    private final String line;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;
    private Thread thread;

    FakeShellCommand(String line) {
        this.line = line;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) {
        thread = new Thread(this, "fake-shell");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void destroy(ChannelSession channel) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        int exit = 0;
        try {
            String[] parts = line.split(" ", 2);
            String arg = parts.length > 1 ? parts[1] : "";
            switch (parts[0]) {
                case "echo" -> write(out, arg + "\n");
                case "err" -> write(err, arg + "\n");
                case "cat" -> in.transferTo(out);
                case "fail" -> exit = Integer.parseInt(arg.trim());
                case "sleep" -> Thread.sleep(Long.parseLong(arg.trim()));
                case "flood" -> {
                    byte[] chunk = new byte[8192];
                    java.util.Arrays.fill(chunk, (byte) 'x');
                    long left = Long.parseLong(arg.trim());
                    while (left > 0) {
                        int n = (int) Math.min(left, chunk.length);
                        out.write(chunk, 0, n);
                        left -= n;
                    }
                }
                case "getent" -> exit = getent(arg);
                default -> {
                    write(err, "unknown command: " + parts[0] + "\n");
                    exit = 127;
                }
            }
            out.flush();
            err.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = 130;
        } catch (IOException | RuntimeException e) {
            exit = 1;
        } finally {
            callback.onExit(exit);
        }
    }

    private int getent(String arg) throws IOException {
        String[] a = arg.trim().split(" ", 2);
        String name = a.length > 1 ? a[1].trim().replace("'", "") : "";
        String entry = switch (a[0] + ":" + name) {
            case "passwd:wxtest" -> "wxtest:x:1000:1000::/home/wxtest:/bin/sh";
            case "passwd:root" -> "root:x:0:0:root:/root:/bin/sh";
            case "group:users" -> "users:x:100:wxtest";
            case "passwd:nolookup", "group:nolookup" -> null;
            default -> "";
        };
        if (entry == null) {
            return 1;
        }
        if (entry.isEmpty()) {
            return 2;
        }
        write(out, entry + "\n");
        return 0;
    }

    private static void write(OutputStream s, String text) throws IOException {
        s.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
