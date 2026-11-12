package com.luisloja.wxssh.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;

import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;

/**
 * Tiny interactive "login shell" for the shell-script tests: prints a banner and a prompt, echoes what is typed the
 * way a terminal does, runs one command per line and prints the prompt again.
 *
 * <p>Commands: {@code echo TEXT}, {@code err TEXT} (to stderr), {@code cd DIR} and {@code pwd} (state that must
 * survive between lines), {@code slow} (answers after 300 ms), {@code splitprompt} (delivers its prompt in three
 * pieces, 40 ms apart, to prove a step boundary lands after the whole prompt), {@code hang} (never answers),
 * {@code flood N} (N bytes of 'x'), {@code exit} (ends the shell) and anything else reports "unknown command".
 *
 * <p>The prompt is {@code fake$ }, or {@code fake:DIR$ } after a {@code cd}. A line ends at CR, LF or CRLF.
 * Why not a real shell: portability, as with {@link FakeShellCommand}.
 */
final class FakeInteractiveShell implements Command, Runnable {

    /** Registers one fresh shell per channel. */
    static final ShellFactory FACTORY = channel -> new FakeInteractiveShell();

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;
    private Thread thread;
    private String dir = "";

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
        thread = new Thread(this, "fake-interactive-shell");
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
            write(out, "Welcome to the fake shell\r\n");
            prompt();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean lastWasCr = false;
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n' && lastWasCr) {
                    lastWasCr = false;
                    continue;
                }
                lastWasCr = b == '\r';
                if (b == '\r' || b == '\n') {
                    write(out, "\r\n");
                    String text = line.toString(StandardCharsets.UTF_8);
                    line.reset();
                    if (!command(text)) {
                        break;
                    }
                    prompt();
                } else {
                    out.write(b);
                    out.flush();
                    line.write(b);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = 130;
        } catch (IOException | RuntimeException e) {
            exit = 1;
        } finally {
            callback.onExit(exit);
        }
    }

    /** @return false when the shell must end */
    private boolean command(String text) throws IOException, InterruptedException {
        String[] parts = text.trim().split(" ", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        switch (parts[0]) {
            case "" -> {
            }
            case "echo" -> write(out, arg + "\r\n");
            case "err" -> write(err, arg + "\r\n");
            case "cd" -> dir = arg.trim();
            case "pwd" -> write(out, "/" + dir + "\r\n");
            case "slow" -> {
                Thread.sleep(300);
                write(out, "done\r\n");
            }
            case "splitprompt" -> {
                write(out, "fake");
                Thread.sleep(25);
                write(out, ":split");
                Thread.sleep(25);
                dir = "split";
            }
            case "hang" -> Thread.sleep(60_000);
            case "flood" -> {
                byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) 'x');
                long left = Long.parseLong(arg.trim());
                while (left > 0) {
                    int n = (int) Math.min(left, chunk.length);
                    out.write(chunk, 0, n);
                    left -= n;
                }
                write(out, "\r\n");
            }
            case "exit" -> {
                write(out, "logout\r\n");
                return false;
            }
            default -> write(out, "unknown command: " + parts[0] + "\r\n");
        }
        return true;
    }

    private void prompt() throws IOException {
        write(out, dir.isEmpty() ? "fake$ " : "fake:" + dir + "$ ");
    }

    private static void write(OutputStream s, String text) throws IOException {
        s.write(text.getBytes(StandardCharsets.UTF_8));
        s.flush();
    }
}
