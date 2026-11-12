package com.luisloja.wxssh.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.subsystem.SubsystemFactory;

/**
 * Three tiny SSH subsystems for the subsystem tests.
 * <ul>
 *   <li>{@code wxecho} - reads its input to the end (so it only answers once the client has signalled end of
 *       input), writes {@code ECHO:} plus the upper-cased input, and exits with status 7</li>
 *   <li>{@code wxdelim} - for every line received answers {@code REPLY(line)]]>]]>} and stays open until the
 *       client ends its input; the NETCONF-like "message plus delimiter" shape</li>
 *   <li>{@code wxsilent} - reads and never answers; used for timeouts</li>
 * </ul>
 */
final class FakeSubsystem implements Command, Runnable {

    /** Subsystem names, so tests do not repeat string literals. */
    static final String ECHO = "wxecho";
    static final String DELIM = "wxdelim";
    static final String SILENT = "wxsilent";

    /** The factories to register with the server. */
    static List<SubsystemFactory> factories() {
        return List.of(factory(ECHO), factory(DELIM), factory(SILENT));
    }

    private static SubsystemFactory factory(String name) {
        return new SubsystemFactory() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public Command createSubsystem(ChannelSession channel) {
                return new FakeSubsystem(name);
            }
        };
    }

    private final String name;
    private InputStream in;
    private OutputStream out;
    private ExitCallback callback;
    private Thread thread;

    private FakeSubsystem(String name) {
        this.name = name;
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
        // not used
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) {
        thread = new Thread(this, "fake-subsystem-" + name);
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
            switch (name) {
                case ECHO -> {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.write(("ECHO:" + text.toUpperCase()).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    exit = 7;
                }
                case DELIM -> {
                    ByteArrayOutputStream line = new ByteArrayOutputStream();
                    int b;
                    while ((b = in.read()) != -1) {
                        if (b == '\n') {
                            out.write(("REPLY(" + line.toString(StandardCharsets.UTF_8) + ")]]>]]>")
                                    .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            line.reset();
                        } else {
                            line.write(b);
                        }
                    }
                }
                default -> {
                    while (in.read() != -1) {
                        // swallow everything, answer nothing
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            exit = 1;
        } finally {
            callback.onExit(exit);
        }
    }
}
