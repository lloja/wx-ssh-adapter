package com.luisloja.wxssh.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.OpenFailException;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder;
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.Response;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import net.schmizz.sshj.xfer.scp.SCPDownloadClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One authenticated SSH transport plus the operations that run over it. This is the only class that talks to SSHJ
 * for command execution, SFTP and SCP.
 *
 * <p>Why: the ADK layer must stay thin. A managed connection owns a {@code SshSession}; adapter services call its
 * methods and translate the plain Java results into pipeline documents.
 *
 * <p>Concurrency: SSH multiplexes channels over one transport. Every operation here opens its <em>own</em>
 * channel (session / SFTP / SCP) and closes it before returning, never sharing a channel between threads, so
 * operations may run in parallel on one {@code SshSession}. Servers limit the channels per connection (OpenSSH
 * {@code MaxSessions}, default 10) and refuse the surplus, so a fair {@link Semaphore} caps the operations that
 * hold a channel at {@link SshConnectionConfig#maxConcurrentChannels()}: further callers wait for a free slot
 * instead of failing. The server's own limit cannot be read over SSH, so the cap is configured, not discovered.
 * The cap alone is not enough: a server frees a closed channel a moment after the client has seen the close, so a
 * burst of operations that finish together can reopen channels while the server still counts the old ones and
 * refuse the surplus. Opening a channel is therefore retried a few times with a short backoff when the server
 * refuses it for lack of resources (see {@link #openChannel}); the refusal happens before any command or transfer
 * starts, so a retry can never run anything twice.
 * Port forwards are outside the cap because OpenSSH's {@code MaxSessions} counts only session channels.
 *
 * <p>Side effects: network I/O; local file reads/writes for the file-based transfer methods. Secrets are never
 * logged. Instances must be {@link #close() closed} to release the transport and SSHJ's threads.
 */
public final class SshSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SshSession.class);

    /** Tries to open one channel before giving up on a server that keeps refusing it. */
    private static final int OPEN_ATTEMPTS = 6;
    /** First pause between open attempts; doubles each time (25, 50, 100, 200, 400 ms: 775 ms in all). */
    private static final long OPEN_BACKOFF_START_MS = 25;

    private final SSHClient client;
    private final long maxInMemoryBytes;
    private final Map<String, ActiveForward> forwards = new ConcurrentHashMap<>();
    private final Semaphore channelPermits;
    private final int maxConcurrentChannels;
    private final long channelWaitMs;

    private SshSession(SSHClient client, long maxInMemoryBytes, int maxConcurrentChannels, long channelWaitMs) {
        this.client = client;
        this.maxInMemoryBytes = maxInMemoryBytes;
        this.maxConcurrentChannels = maxConcurrentChannels;
        // fair: callers get a slot in arrival order, so a steady stream of short operations cannot starve a long one
        this.channelPermits = new Semaphore(maxConcurrentChannels, true);
        this.channelWaitMs = channelWaitMs;
    }

    // ------------------------------------------------------------------ connect

    /**
     * Opens a transport, verifies the host key per the configured policy, and authenticates.
     *
     * @param config validated or not; {@link SshConnectionConfig#validate()} is called here
     * @return a connected, authenticated session
     * @throws SshAdapterException with {@link ErrorCode#INVALID_CONFIG}, {@link ErrorCode#CONNECT_FAILED},
     *                             {@link ErrorCode#HOST_KEY_REJECTED}, {@link ErrorCode#AUTH_FAILED} or
     *                             {@link ErrorCode#TIMEOUT}
     */
    public static SshSession connect(SshConnectionConfig config) throws SshAdapterException {
        config.validate();
        SSHClient client = new SSHClient();
        try {
            applyHostKeyPolicy(client, config);
            client.setConnectTimeout(config.connectTimeoutMs());
            client.setTimeout(config.socketTimeoutMs());
            if (config.keepAliveSeconds() > 0) {
                client.getConnection().getKeepAlive().setKeepAliveInterval(config.keepAliveSeconds());
            }
            if (config.compression()) {
                client.useCompression();
            }
            client.connect(config.host(), config.port());
            authenticate(client, config);
            return new SshSession(client, config.maxInMemoryBytes(), config.maxConcurrentChannels(),
                    config.socketTimeoutMs());
        } catch (SshAdapterException e) {
            closeQuietly(client);
            throw e;
        } catch (IOException e) {
            closeQuietly(client);
            throw map(e, "connect to " + config.host() + ":" + config.port());
        }
    }

    private static void applyHostKeyPolicy(SSHClient client, SshConnectionConfig config)
            throws IOException, SshAdapterException {
        switch (config.hostKeyPolicy()) {
            case KNOWN_HOSTS -> {
                if (config.knownHostsPath() == null || config.knownHostsPath().isBlank()) {
                    client.loadKnownHosts();
                } else {
                    client.loadKnownHosts(new File(config.knownHostsPath()));
                }
            }
            case PINNED_FINGERPRINT -> client.addHostKeyVerifier(new PinnedFingerprintVerifier(
                    config.pinnedFingerprints()));
            case ACCEPT_ANY -> {
                LOG.warn("Host key verification is DISABLED for {}:{} (HostKeyPolicy.ACCEPT_ANY); "
                        + "this is insecure and intended for development only", config.host(), config.port());
                client.addHostKeyVerifier(new PromiscuousVerifier());
            }
        }
    }

    private static void authenticate(SSHClient client, SshConnectionConfig config)
            throws IOException, SshAdapterException {
        switch (config.authMethod()) {
            case PASSWORD -> client.authPassword(config.username(), config.password());
            case KEYBOARD_INTERACTIVE_PASSWORD -> client.auth(config.username(), new AuthKeyboardInteractive(
                    new net.schmizz.sshj.userauth.method.PasswordResponseProvider(
                            PasswordUtils.createOneOff(config.password().toCharArray()))));
            case PUBLIC_KEY -> {
                PasswordFinder finder = config.keyPassphrase() == null ? null
                        : PasswordUtils.createOneOff(config.keyPassphrase().toCharArray());
                KeyProvider keys;
                if (config.privateKeyPath() != null && !config.privateKeyPath().isBlank()) {
                    keys = finder == null ? client.loadKeys(config.privateKeyPath())
                            : client.loadKeys(config.privateKeyPath(), finder);
                } else {
                    keys = client.loadKeys(config.privateKeyPem(), null, finder);
                }
                client.authPublickey(config.username(), keys);
            }
        }
    }

    // ------------------------------------------------------------------ state

    /** @return true while the transport is connected and authenticated */
    public boolean isOpen() {
        return client.isConnected() && client.isAuthenticated();
    }

    /**
     * Cancels every active port forward and then closes the transport.
     *
     * <p>Why forwards first: their listener sockets and threads belong to this session, so leaving them behind
     * would leak a bound port on the adapter host after the connection is gone.
     */
    @Override
    public void close() {
        for (String id : List.copyOf(forwards.keySet())) {
            try {
                closeForward(id);
            } catch (SshAdapterException e) {
                LOG.debug("Ignoring failure while cancelling forward {} during close", id, e);
            }
        }
        closeQuietly(client);
    }

    // ------------------------------------------------------------------ channel limiter

    /**
     * Waits for a free channel slot.
     *
     * <p>Why: the server refuses a channel beyond its per-connection limit with an opaque "open failed"; waiting
     * here turns that into back-pressure. The slot is held until the returned permit is closed, which every
     * caller does in a try-with-resources <em>after</em> the channel itself is closed (resources close in reverse
     * order), so a slot is never handed on while its channel still counts against the server.
     *
     * <p>Side effects: blocks the calling thread for up to the connection's socket timeout (indefinitely when that
     * is 0); consumes one slot until the permit is closed.
     *
     * @param what short description of the operation, used in the error message
     * @return the permit to close when the channel is closed
     * @throws SshAdapterException {@link ErrorCode#TIMEOUT} when no slot became free in time;
     *                             {@link ErrorCode#CHANNEL_FAILED} when the thread was interrupted while waiting
     *                             (its interrupt flag is restored) or when the session is closed
     */
    private ChannelPermit acquire(String what) throws SshAdapterException {
        try {
            if (channelWaitMs > 0) {
                if (!channelPermits.tryAcquire(channelWaitMs, TimeUnit.MILLISECONDS)) {
                    throw new SshAdapterException(ErrorCode.TIMEOUT, what + " timed out after " + channelWaitMs
                            + " ms waiting for a free channel (" + maxConcurrentChannels + " allowed at once on this"
                            + " session; raise the limit only if the server allows more channels per connection)",
                            null);
                }
            } else {
                channelPermits.acquire();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshAdapterException(ErrorCode.CHANNEL_FAILED,
                    what + " interrupted while waiting for a free channel", e);
        }
        ChannelPermit permit = new ChannelPermit(channelPermits);
        // A caller that waited may be woken by the close of the session it was waiting on. SSHJ would then fail
        // with a bare IllegalStateException("Not connected"), which must not escape the facade.
        if (!isOpen()) {
            permit.close();
            throw new SshAdapterException(ErrorCode.CHANNEL_FAILED, what + " failed: the SSH session is closed", null);
        }
        return permit;
    }

    /**
     * Number of channel slots that are free right now. Exists so tests can prove that every code path, including
     * failures and timeouts, gives its slot back.
     *
     * @return free slots, between 0 and the configured maximum
     */
    int availableChannelPermits() {
        return channelPermits.availablePermits();
    }

    /** A held channel slot; closing it more than once releases it only once. */
    private static final class ChannelPermit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        ChannelPermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    /** Something that opens a channel (or a channel plus the protocol on top of it). */
    @FunctionalInterface
    interface ChannelOpener<T> {
        T open() throws IOException;
    }

    /**
     * Opens a channel, retrying when the server refuses it because it is momentarily out of channel slots.
     *
     * <p>Why: see the class comment. Measured against OpenSSH 10.3 with the default {@code MaxSessions 10}: 40
     * parallel {@code exec} calls through a limiter of 8 still saw 3 to 7 refusals ("open failed"), because the
     * server had not finished releasing channels the client had already closed. The refusals are transient, so a
     * short wait clears them.
     *
     * <p>Only an {@link OpenFailException} with reason {@code CONNECT_FAILED} (what OpenSSH sends) or
     * {@code RESOURCE_SHORTAGE} (what Apache MINA sends) is retried. Other failures, including a policy refusal
     * ({@code ADMINISTRATIVELY_PROHIBITED}), are passed on at once. The refusal is a reply to the channel-open
     * request itself, so nothing has been executed or transferred yet and a retry cannot repeat any work.
     *
     * <p>Side effects: sleeps up to 775 ms in total while retrying; the caller's channel slot stays held. An
     * interrupt during a pause stops the retries, restores the interrupt flag and fails with
     * {@link InterruptedIOException}. When every attempt is refused the last refusal is thrown, which
     * {@link #map} reports as {@link ErrorCode#CHANNEL_FAILED}.
     *
     * @param opener what opens the channel
     * @return whatever the opener returned
     * @throws IOException the last failure
     */
    private static <T> T openChannel(ChannelOpener<T> opener) throws IOException {
        return openChannel(opener, OPEN_ATTEMPTS, OPEN_BACKOFF_START_MS);
    }

    /** Same as {@link #openChannel(ChannelOpener)} with the attempt count and first pause chosen by the caller (tests). */
    static <T> T openChannel(ChannelOpener<T> opener, int attempts, long firstPauseMs) throws IOException {
        long pause = firstPauseMs;
        for (int attempt = 1; ; attempt++) {
            try {
                return opener.open();
            } catch (OpenFailException e) {
                boolean transientRefusal = e.getReason() == OpenFailException.Reason.CONNECT_FAILED
                        || e.getReason() == OpenFailException.Reason.RESOURCE_SHORTAGE;
                if (!transientRefusal || attempt >= attempts) {
                    throw e;
                }
                LOG.debug("Server refused a channel ({}), retrying in {} ms (attempt {} of {})",
                        e.getReason(), pause, attempt, attempts);
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException stop = new InterruptedIOException("interrupted while retrying a channel open");
                    stop.initCause(e);
                    throw stop;
                }
                pause *= 2;
            }
        }
    }

    // ------------------------------------------------------------------ interactive shell and subsystem

    /** How long the output must stay quiet after a prompt matched before the step is closed. */
    private static final long SETTLE_QUIET_MS = 50;

    /** Upper bound for the settle wait, so an output that never goes quiet cannot hold a step. */
    private static final long SETTLE_MAX_MS = 500;

    /**
     * Drives an interactive shell: sends the lines of a script one at a time and waits for the prompt after each.
     *
     * <p>Why: some targets (network devices, restricted shells, legacy hosts) offer no usable {@code exec}, only a
     * login shell. State such as the working directory then survives between lines, which {@link #exec} cannot give.
     * This is inherently fragile: it relies on the prompt being recognisable by a regular expression and on the
     * terminal echo, so treat it as the last resort after {@code exec}.
     *
     * <p>Side effects: opens one session channel (holding one limiter slot) and closes it before returning, which
     * ends the remote shell; starts two daemon reader threads that end with the channel. Every line is sent as typed
     * input, so the remote side executes it with the login's rights. A timeout, an end of the channel or reaching
     * the memory limit stops the script and is reported in the result, not thrown.
     *
     * @param request the script and how to recognise the prompt
     * @return the transcript, one entry per line sent, and why the script stopped if it did not finish
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a missing script or a bad prompt pattern;
     *                             {@link ErrorCode#CHANNEL_FAILED} / {@link ErrorCode#IO_ERROR} for transport trouble
     */
    public ShellResult runShell(ShellRequest request) throws SshAdapterException {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "script is required (at least one line)", null);
        }
        Pattern prompt = compilePattern(request.promptPattern(), "promptPattern");
        for (String line : request.lines()) {
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                        "each script line must be one line without a line break (a break would run two commands in one step)",
                        null);
            }
        }

        Charset charset = request.charset() == null ? StandardCharsets.UTF_8 : request.charset();
        String eol = request.lineEnding() == null ? "\n" : request.lineEnding();
        List<ShellStep> steps = new ArrayList<>();
        boolean timedOut = false;
        boolean closedByServer = false;
        boolean truncated = false;
        boolean completed = false;
        String transcript;
        String stderr;
        try (ChannelPermit permit = acquire("shell"); Session session = openChannel(client::startSession)) {
            if (request.allocatePty()) {
                session.allocateDefaultPTY();
            }
            try (Session.Shell shell = session.startShell()) {
                ExpectStream out = new ExpectStream(shell.getInputStream(), maxInMemoryBytes, "wxssh-shell-stdout");
                ExpectStream err = new ExpectStream(shell.getErrorStream(), maxInMemoryBytes, "wxssh-shell-stderr");
                int pos = 0;
                boolean go = true;
                if (request.waitForFirstPrompt()) {
                    ExpectStream.Result first = out.await(prompt, 0, request.stepTimeoutMs());
                    if (first.outcome() == ExpectStream.Outcome.MATCHED) {
                        pos = out.settle(SETTLE_QUIET_MS, SETTLE_MAX_MS);
                    } else {
                        go = false;
                        timedOut = first.outcome() == ExpectStream.Outcome.TIMEOUT;
                        closedByServer = first.outcome() == ExpectStream.Outcome.EOF;
                        truncated = first.outcome() == ExpectStream.Outcome.TRUNCATED;
                    }
                }
                List<String> lines = request.lines();
                for (int i = 0; go && i < lines.size(); i++) {
                    String line = lines.get(i);
                    try {
                        OutputStream stdin = shell.getOutputStream();
                        stdin.write((line + eol).getBytes(charset));
                        stdin.flush();
                    } catch (IOException w) {
                        if (out.awaitEnd(500).outcome() == ExpectStream.Outcome.EOF) {
                            closedByServer = true;
                            break;
                        }
                        throw w;
                    }
                    ExpectStream.Result r = out.await(prompt, pos, request.stepTimeoutMs());
                    switch (r.outcome()) {
                        case MATCHED -> {
                            int end = out.settle(SETTLE_QUIET_MS, SETTLE_MAX_MS);
                            steps.add(new ShellStep(line, out.text(pos, end, charset), true));
                            pos = end;
                            completed = i == lines.size() - 1;
                        }
                        case EOF -> {
                            steps.add(new ShellStep(line, out.text(pos, r.matchEnd(), charset), false));
                            closedByServer = true;
                            completed = i == lines.size() - 1;
                            go = false;
                        }
                        case TIMEOUT -> {
                            steps.add(new ShellStep(line, out.text(pos, r.matchEnd(), charset), false));
                            timedOut = true;
                            go = false;
                        }
                        case TRUNCATED -> {
                            steps.add(new ShellStep(line, out.text(pos, r.matchEnd(), charset), false));
                            truncated = true;
                            go = false;
                        }
                    }
                }
                transcript = out.text(0, out.length(), charset);
                // stderr is read by its own thread; let bytes the server sent before the last prompt arrive
                err.settle(SETTLE_QUIET_MS, SETTLE_MAX_MS);
                stderr = err.text(0, err.length(), charset);
                truncated = truncated || out.truncated() || err.truncated();
            }
        } catch (IOException e) {
            throw map(e, "shell");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshAdapterException(ErrorCode.CHANNEL_FAILED, "shell interrupted", e);
        }
        return new ShellResult(transcript, steps, stderr, completed, timedOut, closedByServer, truncated);
    }

    /**
     * Starts a named SSH subsystem, sends it bytes and reads its answer: one request, one answer.
     *
     * <p>Why: protocols such as NETCONF run as an SSH subsystem rather than as a command. Without an
     * {@code expectPattern} the input is followed by an end-of-input signal and the answer is read until the server
     * closes the channel (right for a one-shot filter); with a pattern the channel stays open until the pattern
     * shows up (right for a protocol that ends each message with a delimiter).
     *
     * <p>Side effects: opens one session channel (holding one limiter slot) and closes it before returning;
     * starts one daemon reader thread. The subsystem's own standard error is not read. A timeout is reported in the
     * result, not thrown.
     *
     * @param request subsystem name, input and end-of-answer marker
     * @return the bytes received and why the wait ended
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a missing name or a bad pattern;
     *                             {@link ErrorCode#CHANNEL_FAILED} when the server refuses the subsystem;
     *                             {@link ErrorCode#IO_ERROR} for transport trouble
     */
    public SubsystemResult runSubsystem(SubsystemRequest request) throws SshAdapterException {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "subsystem name is required", null);
        }
        boolean hasMarker = request.expectPattern() != null && !request.expectPattern().isBlank();
        Pattern marker = hasMarker ? compilePattern(request.expectPattern(), "expect") : null;
        byte[] output;
        boolean matched = false;
        boolean closedByServer = false;
        boolean timedOut = false;
        boolean truncated;
        Integer exitStatus = null;
        try (ChannelPermit permit = acquire("subsystem"); Session session = openChannel(client::startSession)) {
            Session.Subsystem sub;
            try {
                sub = session.startSubsystem(request.name());
            } catch (ConnectionException refused) {
                if (refused.getCause() instanceof TimeoutException) {
                    throw refused;
                }
                throw new SshAdapterException(ErrorCode.CHANNEL_FAILED, "subsystem '" + request.name()
                        + "' refused by the server (is it configured there?)", refused);
            }
            try (sub) {
                ExpectStream out = new ExpectStream(sub.getInputStream(), maxInMemoryBytes, "wxssh-subsystem-stdout");
                OutputStream stdin = sub.getOutputStream();
                if (request.input() != null && request.input().length > 0) {
                    stdin.write(request.input());
                }
                ExpectStream.Result r;
                if (hasMarker) {
                    stdin.flush();
                    r = out.await(marker, 0, request.timeoutMs());
                } else {
                    stdin.close();
                    r = out.awaitEnd(request.timeoutMs());
                }
                matched = r.outcome() == ExpectStream.Outcome.MATCHED;
                closedByServer = r.outcome() == ExpectStream.Outcome.EOF;
                timedOut = r.outcome() == ExpectStream.Outcome.TIMEOUT;
                output = out.bytes(0, r.matchEnd());
                truncated = out.truncated();
                if (closedByServer) {
                    // The exit status travels in its own message just before the close; give it a moment.
                    try {
                        sub.join(1, TimeUnit.SECONDS);
                    } catch (ConnectionException ignored) {
                        LOG.debug("Subsystem did not report a close in time", ignored);
                    }
                }
                exitStatus = sub.getExitStatus();
            }
        } catch (IOException e) {
            throw map(e, "subsystem");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshAdapterException(ErrorCode.CHANNEL_FAILED, "subsystem interrupted", e);
        }
        return new SubsystemResult(output, matched, closedByServer, timedOut, truncated, exitStatus);
    }

    /** Compiles a user-supplied regular expression, turning a syntax error into {@link ErrorCode#INVALID_CONFIG}. */
    private static Pattern compilePattern(String regex, String field) throws SshAdapterException {
        if (regex == null || regex.isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, field + " is required", null);
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    field + " is not a valid regular expression: " + e.getDescription(), e);
        }
    }


    // ------------------------------------------------------------------ exec

    /**
     * Runs one command on its own session channel and captures its output.
     *
     * <p>Output is captured up to {@code maxInMemoryBytes} per stream; the rest is discarded (still drained, so
     * the remote command never blocks on a full channel window) and {@link ExecResult#truncated()} is set.
     *
     * @param request what to run
     * @return captured output and exit information; a non-zero exit status is <em>not</em> an exception
     * @throws SshAdapterException {@link ErrorCode#CHANNEL_FAILED} / {@link ErrorCode#IO_ERROR}
     */
    public ExecResult exec(ExecRequest request) throws SshAdapterException {
        if (request == null || request.command() == null || request.command().isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "command is required", null);
        }
        BoundedBuffer out = new BoundedBuffer(maxInMemoryBytes);
        BoundedBuffer err = new BoundedBuffer(maxInMemoryBytes);
        boolean timedOut = false;
        Integer exitStatus;
        String exitSignal;
        try (ChannelPermit permit = acquire("exec"); Session session = openChannel(client::startSession)) {
            if (request.allocatePty()) {
                session.allocateDefaultPTY();
            }
            if (request.environment() != null) {
                for (Map.Entry<String, String> e : request.environment().entrySet()) {
                    try {
                        session.setEnvVar(e.getKey(), e.getValue());
                    } catch (ConnectionException | TransportException refused) {
                        LOG.debug("Server refused environment variable {}", e.getKey());
                    }
                }
            }
            try (Session.Command cmd = session.exec(request.command())) {
                Thread tOut = drain(cmd.getInputStream(), out, "wxssh-exec-stdout");
                Thread tErr = drain(cmd.getErrorStream(), err, "wxssh-exec-stderr");
                if (request.stdin() != null) {
                    OutputStream in = cmd.getOutputStream();
                    in.write(request.stdin());
                    in.close();
                }
                try {
                    if (request.timeoutMs() > 0) {
                        cmd.join(request.timeoutMs(), TimeUnit.MILLISECONDS);
                    } else {
                        cmd.join();
                    }
                } catch (ConnectionException e) {
                    if (request.timeoutMs() > 0 && e.getCause() instanceof TimeoutException) {
                        timedOut = true;
                    } else {
                        throw e;
                    }
                }
                if (timedOut) {
                    cmd.close();
                }
                joinQuietly(tOut);
                joinQuietly(tErr);
                exitStatus = cmd.getExitStatus();
                exitSignal = cmd.getExitSignal() == null ? null : cmd.getExitSignal().toString();
            }
        } catch (IOException e) {
            throw map(e, "exec");
        }
        return new ExecResult(out.asString(), err.asString(), exitStatus, exitSignal, timedOut,
                out.truncated() || err.truncated());
    }

    private static Thread drain(InputStream in, BoundedBuffer sink, String name) {
        return Thread.ofPlatform().daemon().name(name).start(() -> {
            try (in) {
                in.transferTo(sink);
            } catch (IOException ignored) {
                // channel closed under us (timeout or remote close); captured bytes so far are kept
            }
        });
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ SFTP: browse

    /**
     * Lists a remote directory: one level, hidden entries included, no filter.
     *
     * @param path remote directory
     * @return entries excluding {@code .} and {@code ..}
     * @throws SshAdapterException {@link ErrorCode#SFTP_STATUS} when the path is missing or unreadable
     */
    public List<RemoteEntry> sftpList(String path) throws SshAdapterException {
        return sftpList(path, ListOptions.DEFAULT);
    }

    /**
     * Lists a remote directory with a name filter, optional recursion and optional hiding of dot-files.
     *
     * <p>Why: flows usually want "the {@code *.csv} files under /in" and would otherwise have to list everything
     * and filter in the pipeline, or walk the tree themselves with one service call per directory.
     *
     * <p>Behaviour: the whole walk uses <em>one</em> SFTP channel. Recursion is breadth first, so entries of a
     * directory appear before those of its children. Symbolic links are reported but never entered, so a link
     * cycle cannot make the walk endless. An entry is reported when it passes the hidden rule and the filter; a
     * directory that fails either is not reported, but is still searched when it merely fails the <em>filter</em>
     * (a hidden directory is skipped entirely when {@code includeHidden} is false). A sub-directory that cannot
     * be read fails the whole call rather than being skipped silently.
     *
     * <p>Side effects: opens and closes one SFTP channel; one read-directory round trip per directory visited;
     * read-only on the server.
     *
     * @param path    remote directory, not blank
     * @param options filter / recursion / hidden / size cap; null means {@link ListOptions#DEFAULT}
     * @return the matching entries, each with its full remote path
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path or a malformed filter;
     *                             {@link ErrorCode#LIMIT_EXCEEDED} when more than {@code maxEntries} entries
     *                             match; {@link ErrorCode#SFTP_STATUS} when a directory is missing or unreadable
     */
    public List<RemoteEntry> sftpList(String path, ListOptions options) throws SshAdapterException {
        requirePath(path);
        ListOptions opts = options == null ? ListOptions.DEFAULT : options;
        Predicate<String> nameFilter = opts.nameFilter();
        try (ChannelPermit permit = acquire("sftp list"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            List<RemoteEntry> result = new ArrayList<>();
            ArrayDeque<String> pending = new ArrayDeque<>();
            pending.add(path);
            while (!pending.isEmpty()) {
                String dir = pending.poll();
                for (RemoteResourceInfo info : sftp.ls(dir)) {
                    String name = info.getName();
                    if (!opts.includeHidden() && name.startsWith(".")) {
                        continue;
                    }
                    RemoteEntry entry = toEntry(name, info.getPath(), info.getAttributes());
                    if (nameFilter == null || nameFilter.test(name)) {
                        if (result.size() >= opts.maxEntries()) {
                            throw new SshAdapterException(ErrorCode.LIMIT_EXCEEDED,
                                    "listing of " + path + " exceeds " + opts.maxEntries()
                                            + " entries; narrow the filter or the path", null);
                        }
                        result.add(entry);
                    }
                    if (opts.recursive() && entry.type() == RemoteEntry.Type.DIRECTORY) {
                        pending.add(entry.path());
                    }
                }
            }
            return result;
        } catch (IOException e) {
            throw map(e, "sftp list " + path);
        }
    }

    /**
     * Reads attributes of a remote path, following symbolic links (SFTP {@code stat}).
     *
     * @param path remote path
     * @return the entry, or null if the path does not exist (a dangling symbolic link counts as missing)
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; otherwise on any failure
     *                             other than "no such file"
     */
    public RemoteEntry sftpStat(String path) throws SshAdapterException {
        return sftpStat(path, true);
    }

    /**
     * Reads attributes of a remote path, optionally without following a final symbolic link.
     *
     * <p>Why both modes: with {@code followLinks} true a link reports the attributes of its target (SFTP
     * {@code stat}); with false it reports the link itself, type {@link RemoteEntry.Type#SYMLINK} (SFTP
     * {@code lstat}), which is the only way to see a dangling link or to tell a link from a file.
     *
     * <p>Side effects: opens and closes its own SFTP channel; one round trip; read-only on the server.
     *
     * @param path        remote path
     * @param followLinks true for {@code stat}, false for {@code lstat}
     * @return the entry, or null if the path does not exist; with {@code followLinks} true a dangling link is
     *         reported as missing
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; otherwise on any failure
     *                             other than "no such file"
     */
    public RemoteEntry sftpStat(String path, boolean followLinks) throws SshAdapterException {
        requirePath(path);
        try (ChannelPermit permit = acquire("sftp stat"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            FileAttributes attrs;
            if (followLinks) {
                attrs = sftp.statExistence(path);
            } else {
                try {
                    attrs = sftp.lstat(path);
                } catch (SFTPException e) {
                    if (e.getStatusCode() != Response.StatusCode.NO_SUCH_FILE) {
                        throw e;
                    }
                    attrs = null;
                }
            }
            return attrs == null ? null : toEntry(baseName(path), path, attrs);
        } catch (IOException e) {
            throw map(e, "sftp stat " + path);
        }
    }

    /**
     * Asks the server for the canonical absolute form of a path (SFTP {@code realpath}).
     *
     * <p>Why: lets a flow learn where {@code .} or a relative path really points (for example the login
     * directory) and collapse {@code ..}, {@code .} and links without parsing paths itself. What the server does
     * with a path that does not exist is server specific, so callers must not rely on either outcome.
     *
     * <p>Side effects: opens and closes its own SFTP channel; one round trip; read-only on the server.
     *
     * @param path remote path, may be relative
     * @return the canonical absolute path as reported by the server
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; {@link ErrorCode#SFTP_STATUS}
     *                             if the server refuses
     */
    public String sftpRealPath(String path) throws SshAdapterException {
        requirePath(path);
        try (ChannelPermit permit = acquire("sftp realpath"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            return sftp.canonicalize(path);
        } catch (IOException e) {
            throw map(e, "sftp realpath " + path);
        }
    }

    /**
     * Reads the target of a remote symbolic link without resolving it (SFTP {@code readlink}).
     *
     * <p>Why: the stored target may be relative or dangling, which {@link #sftpRealPath} would hide or reject.
     *
     * <p>Side effects: opens and closes its own SFTP channel; one round trip; read-only on the server.
     *
     * @param path remote symbolic link
     * @return the link target exactly as stored
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; {@link ErrorCode#SFTP_STATUS}
     *                             if the path is missing or is not a symbolic link
     */
    public String sftpReadLink(String path) throws SshAdapterException {
        requirePath(path);
        try (ChannelPermit permit = acquire("sftp readlink"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            return sftp.readlink(path);
        } catch (IOException e) {
            throw map(e, "sftp readlink " + path);
        }
    }

    private static void requirePath(String path) throws SshAdapterException {
        if (path == null || path.isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "path is required", null);
        }
    }

    private static RemoteEntry toEntry(String name, String path, FileAttributes a) {
        RemoteEntry.Type type = switch (a.getType()) {
            case REGULAR -> RemoteEntry.Type.FILE;
            case DIRECTORY -> RemoteEntry.Type.DIRECTORY;
            case SYMLINK -> RemoteEntry.Type.SYMLINK;
            default -> RemoteEntry.Type.OTHER;
        };
        return new RemoteEntry(name, path, a.getSize(), a.getMtime(), a.getMode().getPermissionsMask(), type,
                a.getAtime(), a.getUID(), a.getGID());
    }

    private static String baseName(String path) {
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    // ------------------------------------------------------------------ SFTP: transfer

    /**
     * Downloads a remote file into memory.
     *
     * @param remotePath remote file
     * @return file content
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} if {@code remotePath} is a directory;
     *                             {@link ErrorCode#LIMIT_EXCEEDED} if larger than {@code maxInMemoryBytes};
     *                             use {@link #sftpGetToFile} for large files
     */
    public byte[] sftpGet(String remotePath) throws SshAdapterException {
        CappedDest dest = new CappedDest(remotePath, maxInMemoryBytes);
        try (ChannelPermit permit = acquire("sftp get"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            rejectDirectory(sftp, remotePath);
            sftp.get(remotePath, dest);
            return dest.bytes();
        } catch (IOException e) {
            throw map(e, "sftp get " + remotePath);
        }
    }

    /**
     * Downloads a remote file to a local path, streaming (constant memory).
     *
     * @param remotePath remote file
     * @param localPath  local destination file or directory; overwritten if it exists. When it is an existing
     *                   directory the file is written inside it under its remote name.
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} if {@code remotePath} is a directory (nothing is
     *                             created locally); otherwise on failure
     */
    public void sftpGetToFile(String remotePath, String localPath) throws SshAdapterException {
        try (ChannelPermit permit = acquire("sftp get"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            rejectDirectory(sftp, remotePath);
            sftp.get(remotePath, localPath);
        } catch (IOException e) {
            throw map(e, "sftp get " + remotePath);
        }
    }

    /**
     * Refuses to download a directory.
     *
     * <p>Why: SSHJ's {@code get} silently turns a directory download into a recursive copy of the whole tree
     * (so {@code "."} would copy the user's entire home directory into the local path), and into an
     * {@link AssertionError} ("Unimplemented") for in-memory downloads. Neither is what a "get a file" operation
     * should do, so the call is rejected before anything is read or created locally.
     *
     * <p>Side effects: one SFTP {@code stat} round trip on the already open SFTP channel. A missing path is not
     * rejected here, so the download then fails with the usual {@code NO_SUCH_FILE} status.
     */
    private static void rejectDirectory(SFTPClient sftp, String remotePath) throws IOException, SshAdapterException {
        FileAttributes attrs = sftp.statExistence(remotePath);
        if (attrs != null && attrs.getType() == FileMode.Type.DIRECTORY) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    "sftp get: remote path is a directory, not a file: " + remotePath, null);
        }
    }
    /**
     * Uploads bytes to a remote file.
     *
     * @param content    bytes to write
     * @param remotePath destination path; overwritten if it exists
     * @param atomic     when true, writes {@code remotePath + ".part"} then renames, so readers never see a
     *                   partially written file
     * @throws SshAdapterException on failure
     */
    public void sftpPut(byte[] content, String remotePath, boolean atomic) throws SshAdapterException {
        String target = atomic ? remotePath + ".part" : remotePath;
        try (ChannelPermit permit = acquire("sftp put"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.put(new BytesSource(baseName(remotePath), content), target);
            if (atomic) {
                renameOverwrite(sftp, target, remotePath);
            }
        } catch (IOException e) {
            throw map(e, "sftp put " + remotePath);
        }
    }

    /**
     * Uploads a local file, streaming (constant memory).
     *
     * @param localPath  local file
     * @param remotePath destination path; overwritten if it exists
     * @param atomic     see {@link #sftpPut(byte[], String, boolean)}
     * @throws SshAdapterException on failure
     */
    public void sftpPutFromFile(String localPath, String remotePath, boolean atomic) throws SshAdapterException {
        String target = atomic ? remotePath + ".part" : remotePath;
        try (ChannelPermit permit = acquire("sftp put"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.put(localPath, target);
            if (atomic) {
                renameOverwrite(sftp, target, remotePath);
            }
        } catch (IOException e) {
            throw map(e, "sftp put " + remotePath);
        }
    }

    private static void renameOverwrite(SFTPClient sftp, String from, String to) throws IOException {
        // SFTP v3 rename fails when the target exists; SSHJ falls back to posix-rename@openssh.com where the
        // server offers it. If the target exists and neither works, remove it first (non-atomic fallback).
        try {
            sftp.rename(from, to);
        } catch (SFTPException e) {
            if (sftp.statExistence(to) != null) {
                sftp.rm(to);
                sftp.rename(from, to);
            } else {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ SFTP: manage

    /**
     * Creates a directory and any missing parents.
     *
     * @param path remote directory
     * @throws SshAdapterException on failure
     */
    public void sftpMkdirs(String path) throws SshAdapterException {
        try (ChannelPermit permit = acquire("sftp mkdirs"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.mkdirs(path);
        } catch (IOException e) {
            throw map(e, "sftp mkdirs " + path);
        }
    }

    /**
     * Deletes a remote file.
     *
     * <p>Why the extra check: servers answer {@code rm} on a directory with a bare {@code FAILURE}, which tells a
     * user nothing. When the removal fails, one more {@code lstat} on the same channel finds out whether the path is
     * a directory so the error can say so and name the right operation.
     *
     * @param path remote file
     * @throws SshAdapterException {@link ErrorCode#SFTP_STATUS} (for example {@code NO_SUCH_FILE}), or
     *                             {@link ErrorCode#INVALID_CONFIG} when {@code path} is a directory
     */
    public void sftpDelete(String path) throws SshAdapterException {
        try (ChannelPermit permit = acquire("sftp rm"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            try {
                sftp.rm(path);
            } catch (IOException e) {
                if (isDirectoryQuietly(sftp, path)) {
                    throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "sftp rm: " + path
                            + " is a directory; SftpDelete removes files and links only (use SftpRmdir)", e);
                }
                throw e;
            }
        } catch (IOException e) {
            throw map(e, "sftp rm " + path);
        }
    }

    /** True when {@code lstat} says {@code path} is a directory; any failure of the check itself counts as no. */
    private static boolean isDirectoryQuietly(SFTPClient sftp, String path) {
        FileAttributes attrs = lstatQuietly(sftp, path);
        return attrs != null && attrs.getType() == FileMode.Type.DIRECTORY;
    }

    /** {@code lstat} that returns null when the path is missing or the check itself fails. */
    private static FileAttributes lstatQuietly(SFTPClient sftp, String path) {
        try {
            return sftp.lstat(path);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Removes an empty remote directory.
     *
     * <p>Why the extra check: OpenSSH answers {@code rmdir} on a regular file with {@code NO_SUCH_FILE}, which sends a
     * user looking for a typo in a path that exists. When the removal fails, one more {@code lstat} on the same
     * channel finds out whether the path is something other than a directory, and the error then says so and names
     * the right operation. A missing path and a non-empty directory keep the server's own status.
     *
     * @param path remote directory
     * @throws SshAdapterException {@link ErrorCode#SFTP_STATUS}, or {@link ErrorCode#INVALID_CONFIG} when
     *                             {@code path} exists but is not a directory
     */
    public void sftpRmdir(String path) throws SshAdapterException {
        try (ChannelPermit permit = acquire("sftp rmdir"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            try {
                sftp.rmdir(path);
            } catch (IOException e) {
                FileAttributes attrs = lstatQuietly(sftp, path);
                if (attrs != null && attrs.getType() != FileMode.Type.DIRECTORY) {
                    throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "sftp rmdir: " + path
                            + " is not a directory; SftpRmdir removes directories only (use SftpDelete for files and links)", e);
                }
                throw e;
            }
        } catch (IOException e) {
            throw map(e, "sftp rmdir " + path);
        }
    }

    /**
     * Renames or moves a remote path.
     *
     * @param from existing path
     * @param to   new path
     * @throws SshAdapterException on failure
     */
    public void sftpRename(String from, String to) throws SshAdapterException {
        try (ChannelPermit permit = acquire("sftp rename"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.rename(from, to);
        } catch (IOException e) {
            throw map(e, "sftp rename " + from);
        }
    }

    /**
     * Changes POSIX permissions.
     *
     * @param path        remote path
     * @param permissions permission bits, for example {@code 0640} written as octal literal in Java
     * @throws SshAdapterException on failure
     */
    public void sftpChmod(String path, int permissions) throws SshAdapterException {
        try (ChannelPermit permit = acquire("sftp chmod"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.chmod(path, permissions);
        } catch (IOException e) {
            throw map(e, "sftp chmod " + path);
        }
    }

    /**
     * Changes POSIX permissions on a directory and everything below it.
     *
     * <p>Why: {@code chmod -R} with one number is a trap, because 640 removes the execute bit from every directory
     * and makes the tree impossible to enter. So files and directories get their own mode: {@code fileMode} for
     * everything that is not a directory, and {@code directoryMode} for directories. When {@code directoryMode} is
     * null it is derived from {@code fileMode} by adding the execute bit wherever the read bit is set (see
     * {@link #directoryModeFor(int)}), so 640 gives directories 750.
     *
     * <p>Safety: the whole tree is listed before the first change, so a tree larger than
     * {@link ListOptions#DEFAULT_MAX_ENTRIES} entries fails with {@link ErrorCode#LIMIT_EXCEEDED} with nothing
     * changed. Symbolic links are skipped, never followed and never changed (a chmod on a link would change its
     * target, which may lie outside the tree). {@code /}, {@code .} and {@code ..} are refused. Children are changed
     * before their parents, so a directory mode without the execute bit cannot lock the walk out of its own
     * contents. Once changing has started a failure leaves the rest as it was (there is no rollback); the message
     * names the failing entry. If {@code path} is a plain file, only that file is changed.
     *
     * <p>Side effects: opens and closes one SFTP channel; one read-directory round trip per directory plus one
     * request per changed entry; changes permissions on the server.
     *
     * @param path          remote directory (or file), not blank, not a symbolic link
     * @param fileMode      mode for non-directories, 0 to 07777
     * @param directoryMode mode for directories, 0 to 07777, or null to derive it from {@code fileMode}
     * @return number of entries changed, the starting path included; skipped links are not counted
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank, dangerous or symbolic-link path or
     *                             a mode out of range; {@link ErrorCode#LIMIT_EXCEEDED} for a tree that is too
     *                             large; {@link ErrorCode#SFTP_STATUS} when the server refuses
     */
    public int sftpChmodRecursive(String path, int fileMode, Integer directoryMode) throws SshAdapterException {
        requirePath(path);
        rejectDangerousRoot(path, "change permissions recursively");
        requireMode("fileMode", fileMode);
        if (directoryMode != null) {
            requireMode("directoryMode", directoryMode);
        }
        int dirMode = directoryMode != null ? directoryMode : directoryModeFor(fileMode);
        try (ChannelPermit permit = acquire("sftp chmod -R"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            FileMode.Type rootType = sftp.lstat(path).getType();
            if (rootType == FileMode.Type.SYMLINK) {
                throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                        path + " is a symbolic link; it is not followed, give the path of the directory itself", null);
            }
            if (rootType != FileMode.Type.DIRECTORY) {
                changeEntry(() -> sftp.chmod(path, fileMode), path);
                return 1;
            }
            TreeEntries tree = listTree(sftp, path, "changing");
            for (String file : tree.files()) {
                changeEntry(() -> sftp.chmod(file, fileMode), file);
            }
            // Directories were discovered parent-first; reverse order changes children before their parents.
            for (int i = tree.dirs().size() - 1; i >= 0; i--) {
                String dir = tree.dirs().get(i);
                changeEntry(() -> sftp.chmod(dir, dirMode), dir);
            }
            return tree.files().size() + tree.dirs().size();
        } catch (IOException e) {
            throw map(e, "sftp chmod -R " + path);
        }
    }

    /** Files (links excluded) and directories of a tree, directories parent-first. */
    private record TreeEntries(List<String> files, List<String> dirs) {
    }

    /**
     * Lists a whole directory tree without following symbolic links, before anything is changed.
     *
     * <p>Why: listing first means a tree over the cap, or a directory that cannot be read, fails with nothing
     * changed, and the later changes never depend on a directory whose own mode or owner was just altered.
     * Symbolic links are left out: chmod and chown act on a link''s <em>target</em>, which may lie outside the tree.
     *
     * <p>Side effects: one read-directory round trip per directory on the given channel.
     *
     * @param what verb for the limit message, for example {@code "changing"}
     */
    private static TreeEntries listTree(SFTPClient sftp, String path, String what)
            throws IOException, SshAdapterException {
        List<String> files = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        dirs.add(path);
        pending.add(path);
        int seen = 1;
        while (!pending.isEmpty()) {
            for (RemoteResourceInfo info : sftp.ls(pending.poll())) {
                if (++seen > ListOptions.DEFAULT_MAX_ENTRIES) {
                    throw new SshAdapterException(ErrorCode.LIMIT_EXCEEDED, what + " " + path + " would touch more than "
                            + ListOptions.DEFAULT_MAX_ENTRIES + " entries; nothing was changed", null);
                }
                FileMode.Type type = info.getAttributes().getType();
                if (type == FileMode.Type.DIRECTORY) {
                    dirs.add(info.getPath());
                    pending.add(info.getPath());
                } else if (type != FileMode.Type.SYMLINK) {
                    files.add(info.getPath());
                }
            }
        }
        return new TreeEntries(files, dirs);
    }
    /**
     * Derives the directory mode that goes with a file mode: the execute bit is added for every class (owner,
     * group, other) that has the read bit, because a directory without it cannot be entered.
     *
     * <p>Why: keeps {@code chmod -R 640} from producing a tree nobody can walk. 640 becomes 750, 600 becomes 700,
     * 644 becomes 755. Bits above the nine permission bits (setuid, setgid, sticky) are kept as given.
     *
     * @param fileMode file mode, 0 to 07777
     * @return the matching directory mode
     */
    static int directoryModeFor(int fileMode) {
        return fileMode | ((fileMode & 0444) >> 2);
    }

    private static void requireMode(String field, int mode) throws SshAdapterException {
        if (mode < 0 || mode > 07777) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    field + " must be between 0 and 7777 (octal)", null);
        }
    }

    private static void changeEntry(IoAction action, String what) throws IOException, SshAdapterException {
        try {
            action.run();
        } catch (IOException e) {
            SshAdapterException mapped = map(e, "changing " + what);
            throw new SshAdapterException(mapped.getCode(), mapped.getMessage()
                    + "; entries changed before this failure stay changed", e, mapped.getSftpStatus());
        }
    }
    /**
     * Creates a directory, optionally with all missing parents.
     *
     * <p>Why: a flow often needs "make sure /a/b/c exists" (parents) and sometimes needs the strict form that
     * fails when a parent is missing, so both are offered instead of always creating parents.
     *
     * <p>Behaviour: with {@code parents} true an already existing directory is not an error (SSHJ
     * {@code mkdirs}); with false the server decides, and every server tried reports {@code FAILURE} for an
     * existing path.
     *
     * <p>Side effects: opens and closes its own SFTP channel; creates directories on the server.
     *
     * @param path    remote directory, not blank
     * @param parents true to create missing parents
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; {@link ErrorCode#SFTP_STATUS}
     *                             when the server refuses
     */
    public void sftpMkdir(String path, boolean parents) throws SshAdapterException {
        requirePath(path);
        try (ChannelPermit permit = acquire("sftp mkdir"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            if (parents) {
                sftp.mkdirs(path);
            } else {
                sftp.mkdir(path);
            }
        } catch (IOException e) {
            throw map(e, "sftp mkdir " + path);
        }
    }

    /**
     * Removes a directory and everything below it.
     *
     * <p>Why: SFTP only removes empty directories, so cleaning up a tree otherwise takes one service call per
     * entry. This does the walk on one channel.
     *
     * <p>Safety: the tree is listed completely <em>before</em> anything is deleted, so a tree larger than
     * {@link ListOptions#DEFAULT_MAX_ENTRIES} entries fails with {@link ErrorCode#LIMIT_EXCEEDED} with nothing
     * removed. Symbolic links are removed as links and never entered, so a link cannot lead the walk outside the
     * directory. The root of the server ({@code /}), {@code .} and {@code ..} (also as a last path component) are
     * refused. Once deletion has started, a failure leaves the remainder in place (there is no rollback); the
     * exception message names the entry that failed.
     *
     * <p>Side effects: opens and closes one SFTP channel; one read-directory round trip per directory plus one
     * request per entry; permanently deletes remote files and directories.
     *
     * @param path remote directory, not blank, not a symbolic link
     * @return number of entries removed, the directory itself included
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank, dangerous or non-directory path;
     *                             {@link ErrorCode#LIMIT_EXCEEDED} for a tree that is too large;
     *                             {@link ErrorCode#SFTP_STATUS} when the server refuses
     */
    public int sftpRmdirRecursive(String path) throws SshAdapterException {
        requirePath(path);
        rejectDangerousRoot(path, "remove recursively");
        try (ChannelPermit permit = acquire("sftp rmdir -r"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            if (sftp.lstat(path).getType() != FileMode.Type.DIRECTORY) {
                throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                        path + " is not a directory (a symbolic link is not followed)", null);
            }
            List<String> files = new ArrayList<>();
            List<String> dirs = new ArrayList<>();
            ArrayDeque<String> pending = new ArrayDeque<>();
            dirs.add(path);
            pending.add(path);
            while (!pending.isEmpty()) {
                for (RemoteResourceInfo info : sftp.ls(pending.poll())) {
                    if (files.size() + dirs.size() >= ListOptions.DEFAULT_MAX_ENTRIES) {
                        throw new SshAdapterException(ErrorCode.LIMIT_EXCEEDED, "removing " + path + " would delete more than "
                                + ListOptions.DEFAULT_MAX_ENTRIES + " entries; nothing was deleted", null);
                    }
                    if (info.getAttributes().getType() == FileMode.Type.DIRECTORY) {
                        dirs.add(info.getPath());
                        pending.add(info.getPath());
                    } else {
                        files.add(info.getPath());
                    }
                }
            }
            for (String file : files) {
                removeEntry(() -> sftp.rm(file), file);
            }
            // Directories were discovered parent-first, so the reverse order empties children before parents.
            for (int i = dirs.size() - 1; i >= 0; i--) {
                String dir = dirs.get(i);
                removeEntry(() -> sftp.rmdir(dir), dir);
            }
            return files.size() + dirs.size();
        } catch (IOException e) {
            throw map(e, "sftp rmdir -r " + path);
        }
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private static void removeEntry(IoAction action, String what) throws IOException, SshAdapterException {
        try {
            action.run();
        } catch (IOException e) {
            SshAdapterException mapped = map(e, "removing " + what);
            throw new SshAdapterException(mapped.getCode(), mapped.getMessage()
                    + "; entries removed before this failure stay removed", e, mapped.getSftpStatus());
        }
    }

    private static void rejectDangerousRoot(String path, String action) throws SshAdapterException {
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String last = p.substring(p.lastIndexOf('/') + 1);
        if (p.equals("/") || last.equals(".") || last.equals("..")) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    "refusing to " + action + ": '" + path + "' is the server root, the current directory or a parent", null);
        }
    }

    /**
     * Renames or moves a remote path, with an explicit choice about an existing target.
     *
     * <p>Why: SFTP v3 {@code rename} fails when the target exists, so "move over the old file" needs a different
     * request. Making the choice explicit lets a flow decide, instead of silently replacing data.
     *
     * <p>Behaviour: with {@code overwrite} false the plain rename is sent and the server refuses an existing
     * target. With true, a server that offers {@code posix-rename@openssh.com} replaces the target atomically;
     * otherwise a target that is a file or link is deleted first and then renamed (not atomic: a failure between
     * the two steps loses the old target). A target that is a directory is never deleted by that fallback. The
     * source must exist before anything is replaced. Missing parent directories of the target are not created;
     * use {@link #sftpRename(String, String, boolean, boolean)} for that.
     *
     * <p>Side effects: opens and closes its own SFTP channel; moves a remote path, and may delete the target.
     *
     * @param from      existing path, not blank
     * @param to        new path, not blank
     * @param overwrite true to replace an existing target
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; {@link ErrorCode#SFTP_STATUS}
     *                             when the source is missing or the server refuses
     */
    public void sftpRename(String from, String to, boolean overwrite) throws SshAdapterException {
        sftpRename(from, to, overwrite, false, true);
    }

    /**
     * Renames or moves a remote path, optionally creating the missing parent directories of the target first.
     *
     * <p>Why: SFTP {@code rename} never creates directories, so "move into /done/2026/11/" fails with a bare
     * {@code NO_SUCH_FILE} until someone has made that folder. With {@code createParents} the folder chain is made
     * first (like {@code mkdir -p}), which is what a "file it away" step normally wants.
     *
     * <p>Behaviour: the source is checked first, so a missing source creates nothing. The parents are created
     * before the rename; if the rename then fails (for example the target exists and {@code overwrite} is false)
     * the folders that were created stay. Without {@code createParents} a {@code NO_SUCH_FILE} is explained: the
     * message says whether the source or the target's folder is the missing one.
     *
     * <p>Side effects: opens and closes its own SFTP channel; may create directories; moves a remote path and may
     * delete the target (see {@link #sftpRename(String, String, boolean)}).
     *
     * @param from          existing path, not blank
     * @param to            new path, not blank
     * @param overwrite     true to replace an existing target
     * @param createParents true to create the target's missing parent directories
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; {@link ErrorCode#SFTP_STATUS}
     *                             when the source is missing or the server refuses
     */
    public void sftpRename(String from, String to, boolean overwrite, boolean createParents)
            throws SshAdapterException {
        sftpRename(from, to, overwrite, createParents, true);
    }

    /**
     * Same as {@link #sftpRename(String, String, boolean, boolean)}, with a switch for the atomic server extension.
     *
     * <p>Why: both test servers and OpenSSH offer {@code posix-rename@openssh.com}, so without this switch the
     * delete-then-rename fallback, which exists for servers that lack it, could never be exercised by a test.
     * Package-private on purpose; production callers always allow the extension.
     *
     * @param usePosixExtension false forces the delete-then-rename path even when the server offers the extension
     */
    void sftpRename(String from, String to, boolean overwrite, boolean createParents, boolean usePosixExtension)
            throws SshAdapterException {
        requirePath(from);
        requirePath(to);
        try (ChannelPermit permit = acquire("sftp rename"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            try {
                if (createParents) {
                    sftp.lstat(from); // a missing source must not leave new folders behind
                    int slash = to.lastIndexOf('/');
                    if (slash > 0) {
                        sftp.mkdirs(to.substring(0, slash));
                    }
                }
                if (!overwrite) {
                    sftp.rename(from, to);
                    return;
                }
                sftp.lstat(from); // fails with NO_SUCH_FILE before an existing target could be touched
                if (usePosixExtension && sftp.getSFTPEngine().supportsServerExtension("posix-rename", "openssh.com")) {
                    sftp.rename(from, to, java.util.EnumSet.of(net.schmizz.sshj.sftp.RenameFlags.OVERWRITE));
                    return;
                }
                FileAttributes existing = sftp.statExistence(to);
                if (existing != null && existing.getType() == FileMode.Type.DIRECTORY) {
                    throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                            to + " is a directory; it is not replaced by a rename", null);
                }
                if (existing != null) {
                    sftp.rm(to);
                }
                sftp.rename(from, to);
            } catch (IOException e) {
                throw explainMissingRenamePath(sftp, from, to, createParents, e);
            }
        } catch (IOException e) {
            throw map(e, "sftp rename " + from);
        }
    }

    /**
     * Turns a bare {@code NO_SUCH_FILE} from a rename into a message that names the missing path.
     *
     * <p>Why: the server gives the same status for a missing source and for a missing folder of the target, and the
     * second case is the usual surprise. One extra {@code lstat} on the open channel tells them apart. Any other
     * failure is passed through unchanged for {@link #map(IOException, String)}.
     *
     * @return the exception to throw: an {@link SshAdapterException} with the clearer text and the original
     *         {@code NO_SUCH_FILE} status, or {@code e} itself when it is not that case
     */
    private static IOException explainMissingRenamePath(SFTPClient sftp, String from, String to,
            boolean createParents, IOException e) throws SshAdapterException {
        if (!(e instanceof SFTPException sftpEx) || sftpEx.getStatusCode() != Response.StatusCode.NO_SUCH_FILE) {
            return e;
        }
        if (lstatQuietly(sftp, from) == null) {
            throw new SshAdapterException(ErrorCode.SFTP_STATUS,
                    "sftp rename failed: the source " + from + " does not exist (NO_SUCH_FILE)", e, "NO_SUCH_FILE");
        }
        int slash = to.lastIndexOf('/');
        String parent = slash > 0 ? to.substring(0, slash) : null;
        if (parent != null && !createParents && lstatQuietly(sftp, parent) == null) {
            throw new SshAdapterException(ErrorCode.SFTP_STATUS, "sftp rename failed: the target folder " + parent
                    + " does not exist (NO_SUCH_FILE); create it first or enable Create Parent Directories",
                    e, "NO_SUCH_FILE");
        }
        return e;
    }
    /**
     * Creates a symbolic link on the server.
     *
     * <p>Why: lets a flow publish "current" pointers without copying data.
     *
     * <p>Argument order: the SFTP draft names the link first, but OpenSSH's server (and so nearly every server
     * in use) reads the <em>target</em> first. SSHJ passes its arguments through unchanged, so this method hands
     * SSHJ the target first. Checked against OpenSSH 10.3 (see the session note of 2026-11-09).
     *
     * <p>Side effects: opens and closes its own SFTP channel; creates a link on the server. The target is not
     * checked: a dangling link can be created.
     *
     * @param linkPath path of the link to create, not blank
     * @param target   what the link points to, stored as given (may be relative), not blank
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank argument; {@link ErrorCode#SFTP_STATUS}
     *                             when the server refuses (for example an existing {@code linkPath})
     */
    public void sftpSymlink(String linkPath, String target) throws SshAdapterException {
        requirePath(linkPath);
        requirePath(target);
        try (ChannelPermit permit = acquire("sftp symlink"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.symlink(target, linkPath);
        } catch (IOException e) {
            throw map(e, "sftp symlink " + linkPath);
        }
    }

    /**
     * Changes the numeric owner and/or group of a remote path.
     *
     * <p>Why: SFTP v3 carries numeric ids only (no names), and the two ids travel in one request, so changing
     * one of them alone must send the other unchanged. SSHJ's own {@code chown}/{@code chgrp} read the other id
     * with an extra {@code stat} first; this method does it in one request when both are given. To work from
     * account names, look them up first with {@link #sftpResolveUserId(String)} and
     * {@link #sftpResolveGroupId(String)}.
     *
     * <p>Side effects: opens and closes its own SFTP channel; changes ownership on the server, which normally
     * requires privilege (the server answers {@code PERMISSION_DENIED} otherwise).
     *
     * @param path remote path, not blank
     * @param uid  new numeric owner, or null to leave it
     * @param gid  new numeric group, or null to leave it
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path, when both ids are null or an
     *                             id is negative; {@link ErrorCode#SFTP_STATUS} when the server refuses
     */
    public void sftpChown(String path, Integer uid, Integer gid) throws SshAdapterException {
        requirePath(path);
        requireOwnerArguments(uid, gid);
        try (ChannelPermit permit = acquire("sftp chown"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            chownOne(sftp, path, uid, gid);
        } catch (IOException e) {
            throw map(e, "sftp chown " + path);
        }
    }

    /**
     * Changes the owner and/or group of a directory and everything below it.
     *
     * <p>Why: handing a whole uploaded tree to a service account is one step instead of one call per file.
     *
     * <p>Safety: same rules as {@link #sftpChmodRecursive(String, int, Integer)}. The tree is listed completely
     * first (a tree over {@link ListOptions#DEFAULT_MAX_ENTRIES} entries, or an unreadable directory, changes
     * nothing); symbolic links are skipped, never followed and never changed, because ownership of a link would
     * change its target; a link as the starting path is refused; {@code /}, {@code .} and {@code ..} are refused.
     * Children are changed before their parents. There is no rollback: a failure part-way leaves the rest as it
     * was and the message names the entry.
     *
     * <p>Side effects: opens and closes one SFTP channel; one read-directory round trip per directory and one
     * request per entry (two when only one of the ids is given, because the other is read first); changes
     * ownership on the server, which normally requires privilege.
     *
     * @param path remote directory (or file), not blank, not a symbolic link
     * @param uid  new numeric owner, or null to leave it
     * @param gid  new numeric group, or null to leave it
     * @return number of entries changed, the starting path included; skipped links are not counted
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank, dangerous or symbolic-link path, or
     *                             bad ids; {@link ErrorCode#LIMIT_EXCEEDED} for a tree that is too large;
     *                             {@link ErrorCode#SFTP_STATUS} when the server refuses
     */
    public int sftpChownRecursive(String path, Integer uid, Integer gid) throws SshAdapterException {
        requirePath(path);
        rejectDangerousRoot(path, "change ownership recursively");
        requireOwnerArguments(uid, gid);
        try (ChannelPermit permit = acquire("sftp chown -R"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            FileMode.Type rootType = sftp.lstat(path).getType();
            if (rootType == FileMode.Type.SYMLINK) {
                throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                        path + " is a symbolic link; it is not followed, give the path of the directory itself", null);
            }
            if (rootType != FileMode.Type.DIRECTORY) {
                changeEntry(() -> chownOne(sftp, path, uid, gid), path);
                return 1;
            }
            TreeEntries tree = listTree(sftp, path, "changing");
            for (String file : tree.files()) {
                changeEntry(() -> chownOne(sftp, file, uid, gid), file);
            }
            for (int i = tree.dirs().size() - 1; i >= 0; i--) {
                String dir = tree.dirs().get(i);
                changeEntry(() -> chownOne(sftp, dir, uid, gid), dir);
            }
            return tree.files().size() + tree.dirs().size();
        } catch (IOException e) {
            throw map(e, "sftp chown -R " + path);
        }
    }

    private static void requireOwnerArguments(Integer uid, Integer gid) throws SshAdapterException {
        if (uid == null && gid == null) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "uid or gid is required", null);
        }
        if ((uid != null && uid < 0) || (gid != null && gid < 0)) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "uid and gid must not be negative", null);
        }
    }

    private static void chownOne(SFTPClient sftp, String path, Integer uid, Integer gid) throws IOException {
        if (uid != null && gid != null) {
            sftp.setattr(path, new FileAttributes.Builder().withUIDGID(uid, gid).build());
        } else if (uid != null) {
            sftp.chown(path, uid);
        } else {
            sftp.chgrp(path, gid);
        }
    }

    /** Account names the lookup accepts: no leading dash, no quotes, spaces or shell characters. */
    private static final java.util.regex.Pattern ACCOUNT_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.@][A-Za-z0-9_.@-]{0,63}\\$?");

    /**
     * Looks up the numeric id of a user name on the server.
     *
     * <p>Why: SFTP v3 only carries numeric ids, so a name has to be translated by the server that will use the id;
     * the same name can be a different number on another machine. See {@link #lookupAccountId}.
     *
     * <p>Side effects: one exec channel running {@code getent passwd NAME}; if that is not possible, one SFTP
     * channel reading {@code /etc/passwd}.
     *
     * @param name user name, not blank
     * @return the uid
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for an unusable name, an unknown user, or a
     *                             server that offers neither lookup
     */
    public int sftpResolveUserId(String name) throws SshAdapterException {
        return lookupAccountId("passwd", "user", name, "/etc/passwd");
    }

    /**
     * Looks up the numeric id of a group name on the server; the group counterpart of
     * {@link #sftpResolveUserId(String)}, reading {@code getent group NAME} or {@code /etc/group}.
     *
     * @param name group name, not blank
     * @return the gid
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for an unusable name, an unknown group, or a
     *                             server that offers neither lookup
     */
    public int sftpResolveGroupId(String name) throws SshAdapterException {
        return lookupAccountId("group", "group", name, "/etc/group");
    }

    /**
     * Resolves a user or group name to its id, trying {@code getent} and then the flat file.
     *
     * <p>Why two methods: {@code getent} also sees LDAP, SSSD and other name services, so it is tried first; it is
     * missing on AIX, macOS and some minimal systems, and an SFTP-only account may not be allowed to run commands
     * at all, so {@code /etc/passwd} (or {@code /etc/group}), which SFTP can usually read, is the fallback. An
     * account that only a name service knows cannot be found through the fallback.
     *
     * <p>The name is checked against a strict pattern before it goes into the command line, and is single-quoted
     * as well, so it cannot inject shell syntax. Names with other characters (for example with a domain prefix and a backslash)
     * are refused; use the numeric id for those.
     *
     * <p>Side effects: see {@link #sftpResolveUserId(String)}. Nothing is cached, because accounts change.
     *
     * @param database {@code passwd} or {@code group}
     * @param kind     {@code user} or {@code group}, for messages
     * @param name     account name
     * @param file     flat file to fall back to
     */
    private int lookupAccountId(String database, String kind, String name, String file) throws SshAdapterException {
        if (name == null || name.isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, kind + " name is required", null);
        }
        String account = name.trim();
        if (!ACCOUNT_NAME.matcher(account).matches()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, kind + " name '" + account
                    + "' has characters that are not allowed in a lookup; use the numeric id instead", null);
        }
        try {
            ExecResult r = exec(ExecRequest.of("getent " + database + " '" + account + "'", 10_000));
            if (!r.timedOut() && r.exitStatus() != null) {
                if (r.exitStatus() == 0) {
                    Integer id = idFromColonLine(r.stdout().lines().findFirst().orElse(""));
                    if (id != null) {
                        return id;
                    }
                } else if (r.exitStatus() == 2) {
                    throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                            kind + " '" + account + "' does not exist on the server", null);
                }
            }
        } catch (SshAdapterException e) {
            if (e.getCode() == ErrorCode.INVALID_CONFIG) {
                throw e;
            }
            LOG.debug("getent {} failed, falling back to {}: {}", database, file, e.getMessage());
        }
        byte[] content;
        try {
            content = sftpGet(file);
        } catch (SshAdapterException e) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "cannot look up " + kind + " '" + account
                    + "' on this server (getent is not available and " + file + " cannot be read); use the numeric id instead",
                    e);
        }
        for (String line : new String(content, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith(account + ":")) {
                Integer id = idFromColonLine(line);
                if (id != null) {
                    return id;
                }
            }
        }
        throw new SshAdapterException(ErrorCode.INVALID_CONFIG, kind + " '" + account + "' was not found in " + file
                + " (getent is not available, so name services such as LDAP were not searched)", null);
    }

    /** Third colon-separated field of a passwd/group line as a non-negative int, or null if it is not one. */
    private static Integer idFromColonLine(String line) {
        String[] f = line.split(":", -1);
        if (f.length < 3) {
            return null;
        }
        try {
            int id = Integer.parseInt(f[2].trim());
            return id >= 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sets the access and/or modification time of a remote path.
     *
     * <p>Why: used after an upload to keep the source file's timestamp, or to touch a marker file. SFTP v3 sets
     * both times in one request, so giving only one means the other is read first and written back.
     *
     * <p>Side effects: opens and closes its own SFTP channel; one {@code stat} when only one time is given, then
     * one {@code setstat}; changes timestamps on the server.
     *
     * @param path      remote path, not blank
     * @param atimeSec  new access time in seconds since 1970-01-01 UTC, or null to leave it
     * @param mtimeSec  new modification time in seconds since 1970-01-01 UTC, or null to leave it
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path, when both times are null or a
     *                             time is negative; {@link ErrorCode#SFTP_STATUS} when the server refuses
     */
    public void sftpSetTimes(String path, Long atimeSec, Long mtimeSec) throws SshAdapterException {
        requirePath(path);
        if (atimeSec == null && mtimeSec == null) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "atimeSec or mtimeSec is required", null);
        }
        if ((atimeSec != null && atimeSec < 0) || (mtimeSec != null && mtimeSec < 0)) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "times must not be negative", null);
        }
        try (ChannelPermit permit = acquire("sftp settimes"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            long a;
            long m;
            if (atimeSec != null && mtimeSec != null) {
                a = atimeSec;
                m = mtimeSec;
            } else {
                FileAttributes now = sftp.stat(path);
                a = atimeSec != null ? atimeSec : now.getAtime();
                m = mtimeSec != null ? mtimeSec : now.getMtime();
            }
            sftp.setattr(path, new FileAttributes.Builder().withAtimeMtime(a, m).build());
        } catch (IOException e) {
            throw map(e, "sftp settimes " + path);
        }
    }

    /**
     * Sets the size of a remote file, cutting it or extending it with zero bytes.
     *
     * <p>Why: empties a file in place ({@code size} 0) without deleting it, which keeps its owner and
     * permissions.
     *
     * <p>Side effects: opens and closes its own SFTP channel; changes the file's content on the server.
     *
     * @param path remote file, not blank
     * @param size new size in bytes, not negative
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path or a negative size;
     *                             {@link ErrorCode#SFTP_STATUS} when the server refuses
     */
    public void sftpTruncate(String path, long size) throws SshAdapterException {
        requirePath(path);
        if (size < 0) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "size must not be negative", null);
        }
        try (ChannelPermit permit = acquire("sftp truncate"); SFTPClient sftp = openChannel(client::newSFTPClient)) {
            sftp.truncate(path, size);
        } catch (IOException e) {
            throw map(e, "sftp truncate " + path);
        }
    }

    // ------------------------------------------------------------------ SCP

    /**
     * Uploads a local file or directory tree with SCP, with no guard on directories.
     *
     * <p>Why: kept for callers that want exactly what SSHJ does. New code should prefer
     * {@link #scpUpload(String, String, boolean)}, which refuses a directory unless asked, because SCP's directory
     * semantics are easy to get wrong (see there).
     *
     * <p>Side effects: opens one exec channel (holding one limiter slot) running {@code scp -t} on the server,
     * writes remote files, replaces existing ones, and keeps the local modification time and permissions.
     *
     * @param localPath  local file or directory
     * @param remotePath remote destination
     * @throws SshAdapterException on failure (including servers without SCP support)
     */
    public void scpUpload(String localPath, String remotePath) throws SshAdapterException {
        try (ChannelPermit permit = acquire("scp upload")) {
            openChannel(() -> {
                client.newSCPFileTransfer().upload(localPath, remotePath);
                return null;
            });
        } catch (IOException e) {
            throw map(e, "scp upload " + remotePath);
        }
    }

    /**
     * Uploads a local file, or a directory tree when {@code recursive} is true, with SCP.
     *
     * <p>Why: a server without SFTP (embedded devices, restricted accounts) may still offer SCP. A directory is
     * refused unless {@code recursive} is true so a wrong local path cannot copy a whole tree by accident.
     *
     * <p><b>Directory semantics (measured against OpenSSH 10.3, not just the embedded server):</b> when the remote
     * destination does not exist, it is created and receives the <em>contents</em> of the local directory. When it
     * already exists as a directory, the local directory is created <em>inside</em> it under its own name, so
     * running the same upload twice gives {@code dest/a.txt} the first time and {@code dest/src/a.txt} the second.
     * This is the behaviour of {@code scp -r}/{@code cp -r}, not something this method can hide. A file uploaded
     * onto an existing file replaces it; onto an existing directory it lands inside under its own name. SCP never
     * creates missing parent folders.
     *
     * <p>Side effects: as {@link #scpUpload(String, String)}.
     *
     * @param localPath  local file, or directory when {@code recursive}
     * @param remotePath remote destination
     * @param recursive  whether a local directory may be uploaded
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path or a directory without
     *                             {@code recursive}; otherwise on failure
     */
    public void scpUpload(String localPath, String remotePath, boolean recursive) throws SshAdapterException {
        requirePath(localPath);
        requirePath(remotePath);
        try {
            if (!recursive && java.nio.file.Files.isDirectory(java.nio.file.Path.of(localPath))) {
                throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                        "scp upload: local path is a directory; set recursive to true to copy the whole tree: "
                                + localPath, null);
            }
        } catch (java.nio.file.InvalidPathException e) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "scp upload: invalid local path: " + localPath, e);
        }
        scpUpload(localPath, remotePath);
    }

    /**
     * Uploads bytes to a remote file with SCP.
     *
     * <p>Why: the in-memory counterpart of {@link #scpUpload(String, String, boolean)}, for content built in a flow.
     * The bytes are already in memory, so no cap is applied here.
     *
     * <p>Side effects: as {@link #scpUpload(String, String)}; the remote file gets mode 0644 and the current time
     * is not set explicitly, so the server stamps its own.
     *
     * @param content    bytes to write
     * @param remotePath destination file; an existing file is replaced, an existing directory receives the file
     *                   under the name of the last segment of {@code remotePath}
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path or null content; otherwise on
     *                             failure
     */
    public void scpUploadBytes(byte[] content, String remotePath) throws SshAdapterException {
        requirePath(remotePath);
        if (content == null) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "content is required", null);
        }
        try (ChannelPermit permit = acquire("scp upload")) {
            openChannel(() -> {
                client.newSCPFileTransfer().upload(new BytesSource(baseName(remotePath), content), remotePath);
                return null;
            });
        } catch (IOException e) {
            throw map(e, "scp upload " + remotePath);
        }
    }

    /**
     * Downloads a remote file or directory tree with SCP to a local path.
     *
     * <p>Why: the SCP counterpart of {@link #sftpGetToFile}. <b>A remote directory is copied as a whole tree</b>
     * (SSHJ always asks the server for recursion); when the local destination already exists as a directory the
     * remote directory is created inside it under its own name, otherwise the local path becomes the copy.
     *
     * <p>Side effects: opens one exec channel (holding one limiter slot) running {@code scp -f} on the server,
     * creates or overwrites local files and sets their modification time to the remote one.
     *
     * @param remotePath remote source
     * @param localPath  local destination
     * @throws SshAdapterException on failure
     */
    public void scpDownload(String remotePath, String localPath) throws SshAdapterException {
        scpDownload(remotePath, localPath, true);
    }

    /**
     * Downloads a remote file, or with {@code recursive} a directory tree, with SCP to a local path.
     *
     * <p>Why: the counterpart of {@link #scpUpload(String, String, boolean)}. Without {@code recursive} the server is
     * asked for a single file ({@code scp -f} without {@code -r}), so a directory is refused by the server instead
     * of silently copying a whole tree; this is the safe default for a flow that expects one file. SCP cannot tell
     * the caller beforehand whether the path is a directory (there may be no SFTP on the server), so the refusal
     * comes from the remote {@code scp} and its text is passed on.
     *
     * <p>Side effects: as {@link #scpDownload(String, String)}.
     *
     * @param remotePath remote source
     * @param localPath  local destination
     * @param recursive  true to allow a directory tree
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a blank path; otherwise on any failure,
     *                             including the server refusing a directory when {@code recursive} is false
     */
    public void scpDownload(String remotePath, String localPath, boolean recursive) throws SshAdapterException {
        requirePath(remotePath);
        requirePath(localPath);
        try (ChannelPermit permit = acquire("scp download")) {
            openChannel(() -> {
                SCPDownloadClient dl = client.newSCPFileTransfer().newSCPDownloadClient();
                dl.setRecursiveMode(recursive);
                dl.copy(remotePath, new FileSystemFile(localPath));
                return null;
            });
        } catch (IOException e) {
            throw map(e, "scp download " + remotePath);
        }
    }

    /**
     * Downloads one remote file into memory with SCP.
     *
     * <p>Why: the in-memory counterpart of {@link #scpDownload}, bounded by {@code maxInMemoryBytes} like
     * {@link #sftpGet}. A directory cannot be held in memory and is refused.
     *
     * <p>Side effects: as {@link #scpDownload}, without any local file.
     *
     * @param remotePath remote file
     * @return the file content
     * @throws SshAdapterException {@link ErrorCode#LIMIT_EXCEEDED} when larger than {@code maxInMemoryBytes};
     *                             {@link ErrorCode#INVALID_CONFIG} for a directory; otherwise on failure
     */
    public byte[] scpDownloadBytes(String remotePath) throws SshAdapterException {
        requirePath(remotePath);
        CappedDest dest = new CappedDest(remotePath, maxInMemoryBytes);
        try (ChannelPermit permit = acquire("scp download")) {
            openChannel(() -> {
                client.newSCPFileTransfer().download(remotePath, dest);
                return null;
            });
            return dest.bytes();
        } catch (IOException e) {
            throw map(e, "scp download " + remotePath);
        } catch (AssertionError | UnsupportedOperationException e) {
            // InMemoryDestFile cannot create a directory; this is what SSHJ raises when the remote path is one.
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    "scp download: remote path is a directory, not a file: " + remotePath, null);
        }
    }
    // ------------------------------------------------------------------ port forwarding

    /** A forward plus the action that tears it down. */
    private record ActiveForward(PortForward info, ThrowingRunnable closer) {
    }

    /** Teardown action that may fail with an I/O error. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    /**
     * Opens a local forward ({@code ssh -L}): listens on the adapter host and tunnels each accepted connection
     * through the SSH server to {@code targetHost:targetPort}.
     *
     * <p>Why the default bind is loopback: a listener on a wildcard interface would expose the tunnel (and
     * whatever it reaches behind the SSH server) to the whole network, so callers must opt in explicitly by
     * passing a non-loopback {@code bindHost}.
     *
     * <p>Side effects: binds a server socket on the adapter host and starts one daemon thread that accepts
     * connections until {@link #closeForward(String)} or {@link #close()}.
     *
     * @param bindHost   local interface to bind; {@code null} or blank means {@code 127.0.0.1}
     * @param bindPort   local port; {@code 0} picks a free port (reported in the result)
     * @param targetHost host the SSH server should connect to (resolved on the server side)
     * @param targetPort port on {@code targetHost}, 1-65535
     * @return a handle describing the forward, including the port actually bound
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for bad ports/hosts, {@link ErrorCode#IO_ERROR}
     *                             if the local port cannot be bound
     */
    public PortForward openLocalForward(String bindHost, int bindPort, String targetHost, int targetPort)
            throws SshAdapterException {
        requireTarget(targetHost, targetPort);
        requirePort(bindPort, 0, "bindPort");
        String host = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        final ServerSocket ss = bindServerSocket(host, bindPort);
        LocalPortForwarder forwarder = client.newLocalPortForwarder(
                new Parameters(host, ss.getLocalPort(), targetHost, targetPort), ss);
        Thread acceptor = new Thread(() -> {
            try {
                forwarder.listen();
            } catch (IOException e) {
                // Expected when the forward is closed: the server socket is closed under accept().
                LOG.debug("Local forward listener on {}:{} stopped: {}", host, ss.getLocalPort(), e.toString());
            }
        }, "wxssh-local-forward-" + ss.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
        PortForward info = new PortForward(UUID.randomUUID().toString(), PortForward.Kind.LOCAL, host,
                ss.getLocalPort(), targetHost, targetPort);
        // Why not forwarder.close(): in SSHJ 0.40.0 it interrupts the thread that listen() recorded in a private
        // field and throws a NullPointerException when that field is still null, i.e. when closeForward runs before
        // the acceptor thread has started executing listen() (seen in PortForwardIT; javap of sshj-0.40.0.jar).
        // close() does nothing else but close the server socket, and listen() leaves its loop only when its thread
        // is interrupted, so closing the socket and interrupting the acceptor thread ourselves is equivalent and
        // does not depend on that timing. Interrupting a started thread that has not reached listen() yet is safe:
        // the flag stays set and listen() sees it on its first check.
        forwards.put(info.id(), new ActiveForward(info, () -> {
            closeSocketQuietly(ss);
            acceptor.interrupt();
            joinQuietly(acceptor);
        }));
        return info;
    }

    /** Binds a listener, closing the half-open socket if the bind fails so no descriptor leaks. */
    private static ServerSocket bindServerSocket(String host, int port) throws SshAdapterException {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, port));
            return ss;
        } catch (IOException e) {
            if (ss != null) {
                closeSocketQuietly(ss);
            }
            throw map(e, "bind local forward " + host + ":" + port);
        }
    }

    /**
     * Opens a remote forward ({@code ssh -R}): asks the SSH server to listen and tunnels each connection back to
     * {@code targetHost:targetPort}, which is resolved from the <em>adapter</em> host.
     *
     * <p>Why it can fail without a network problem: the server must allow it ({@code AllowTcpForwarding} in
     * OpenSSH) and non-loopback binds additionally need {@code GatewayPorts}. A refusal surfaces as
     * {@link ErrorCode#CHANNEL_FAILED}.
     *
     * <p>Side effects: a listener appears on the SSH server; dropped by {@link #closeForward(String)} or when the
     * session closes.
     *
     * @param bindHost   address on the SSH server to listen on; {@code null} or blank means {@code 127.0.0.1}
     * @param bindPort   port on the SSH server; {@code 0} lets the server choose (reported in the result)
     * @param targetHost host reachable from the adapter to receive the traffic
     * @param targetPort port on {@code targetHost}, 1-65535
     * @return a handle describing the forward, including the port the server actually bound
     * @throws SshAdapterException on invalid arguments or if the server refuses the request
     */
    public PortForward openRemoteForward(String bindHost, int bindPort, String targetHost, int targetPort)
            throws SshAdapterException {
        requireTarget(targetHost, targetPort);
        requirePort(bindPort, 0, "bindPort");
        String host = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        RemotePortForwarder rpf = client.getRemotePortForwarder();
        try {
            RemotePortForwarder.Forward bound = rpf.bind(new RemotePortForwarder.Forward(host, bindPort),
                    new SocketForwardingConnectListener(new InetSocketAddress(targetHost, targetPort)));
            PortForward info = new PortForward(UUID.randomUUID().toString(), PortForward.Kind.REMOTE, host,
                    bound.getPort(), targetHost, targetPort);
            forwards.put(info.id(), new ActiveForward(info, () -> rpf.cancel(bound)));
            return info;
        } catch (IOException e) {
            throw map(e, "bind remote forward " + host + ":" + bindPort);
        }
    }

    /**
     * Lists the forwards currently owned by this session.
     *
     * @return a snapshot; later opens/closes are not reflected
     */
    public List<PortForward> listForwards() {
        List<PortForward> out = new ArrayList<>();
        for (ActiveForward f : forwards.values()) {
            out.add(f.info());
        }
        return out;
    }

    /**
     * Stops one forward. Connections already being tunnelled are cut when their channels are torn down.
     *
     * @param id handle from {@link PortForward#id()}
     * @return true if the forward existed and was closed, false if the id was unknown (already closed)
     * @throws SshAdapterException if the teardown itself fails (the forward is still forgotten)
     */
    public boolean closeForward(String id) throws SshAdapterException {
        ActiveForward f = forwards.remove(id);
        if (f == null) {
            return false;
        }
        try {
            f.closer().run();
            return true;
        } catch (IOException e) {
            throw map(e, "close forward " + id);
        }
    }

    private static void requireTarget(String host, int port) throws SshAdapterException {
        if (host == null || host.isBlank()) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "targetHost is required", null);
        }
        requirePort(port, 1, "targetPort");
    }

    private static void requirePort(int port, int min, String name) throws SshAdapterException {
        if (port < min || port > 65535) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG,
                    name + " must be between " + min + " and 65535 but was " + port, null);
        }
    }

    private static void closeSocketQuietly(ServerSocket s) {
        try {
            s.close();
        } catch (IOException e) {
            LOG.debug("Ignoring failure while closing server socket", e);
        }
    }

    // ------------------------------------------------------------------ error mapping

    /**
     * Collapses SSHJ/JDK exceptions into the façade's stable error categories.
     *
     * @param e    the failure
     * @param what short description of the attempted action, used in the message
     * @return the exception to throw
     */
    static SshAdapterException map(IOException e, String what) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CappedDest.LimitExceeded) {
                return new SshAdapterException(ErrorCode.LIMIT_EXCEEDED, t.getMessage(), e);
            }
        }
        if (e instanceof SFTPException sftp) {
            String status = sftp.getStatusCode().name();
            return new SshAdapterException(ErrorCode.SFTP_STATUS,
                    what + " failed: " + status + " (" + sftp.getMessage() + ")", e, status);
        }
        if (e instanceof UserAuthException) {
            return new SshAdapterException(ErrorCode.AUTH_FAILED, what + " failed: authentication rejected", e);
        }
        if (e instanceof TransportException te
                && te.getDisconnectReason() == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) {
            return new SshAdapterException(ErrorCode.HOST_KEY_REJECTED,
                    what + " failed: host key not trusted", e);
        }
        if (e instanceof SocketTimeoutException
                || (e instanceof ConnectionException && e.getCause() instanceof TimeoutException)) {
            return new SshAdapterException(ErrorCode.TIMEOUT, what + " timed out", e);
        }
        if (e instanceof ConnectionException) {
            return new SshAdapterException(ErrorCode.CHANNEL_FAILED, what + " failed: " + e.getMessage(), e);
        }
        if (e instanceof TransportException || e instanceof java.net.ConnectException
                || e instanceof java.net.UnknownHostException) {
            return new SshAdapterException(ErrorCode.CONNECT_FAILED, what + " failed: " + e.getMessage(), e);
        }
        return new SshAdapterException(ErrorCode.IO_ERROR, what + " failed: " + e.getMessage(), e);
    }

    private static void closeQuietly(SSHClient c) {
        try {
            c.close();
        } catch (IOException e) {
            LOG.debug("Ignoring failure while closing SSH client", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Pins by SHA-256 fingerprint; accepts values with or without the {@code SHA256:} prefix. */
    private static final class PinnedFingerprintVerifier implements HostKeyVerifier {
        private final List<String> pinned;

        PinnedFingerprintVerifier(List<String> pinned) {
            this.pinned = pinned.stream().map(PinnedFingerprintVerifier::normalise).toList();
        }

        private static String normalise(String f) {
            String s = f.trim();
            return s.startsWith("SHA256:") ? s : "SHA256:" + s;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            return pinned.contains(HostKeyProbe.sha256Fingerprint(key));
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return List.of();
        }
    }

    /** Byte sink that keeps at most {@code cap} bytes and silently discards the rest while remembering it did. */
    private static final class BoundedBuffer extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final long cap;
        private final AtomicBoolean truncated = new AtomicBoolean();

        BoundedBuffer(long cap) {
            this.cap = cap;
        }

        @Override
        public synchronized void write(int b) {
            if (buf.size() < cap) {
                buf.write(b);
            } else {
                truncated.set(true);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            long room = cap - buf.size();
            if (room >= len) {
                buf.write(b, off, len);
            } else {
                if (room > 0) {
                    buf.write(b, off, (int) room);
                }
                truncated.set(true);
            }
        }

        synchronized String asString() {
            return buf.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated.get();
        }
    }

    /** In-memory download target that fails fast once the cap is crossed. */
    private static final class CappedDest extends InMemoryDestFile {
        private final String name;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final long cap;

        CappedDest(String name, long cap) {
            this.name = name;
            this.cap = cap;
        }

        @Override
        public long getLength() {
            return buf.size();
        }

        @Override
        public OutputStream getOutputStream() {
            return getOutputStream(false);
        }

        @Override
        public OutputStream getOutputStream(boolean append) {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    check(1);
                    buf.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    check(len);
                    buf.write(b, off, len);
                }

                private void check(int more) throws IOException {
                    if (buf.size() + (long) more > cap) {
                        throw new LimitExceeded("remote file '" + name + "' exceeds the in-memory limit of "
                                + cap + " bytes; use the file-based transfer");
                    }
                }
            };
        }

        byte[] bytes() {
            return buf.toByteArray();
        }

        /** Marker so {@link SshSession#map} can recognise the cap being crossed. */
        static final class LimitExceeded extends IOException {
            private static final long serialVersionUID = 1L;

            LimitExceeded(String m) {
                super(m);
            }
        }
    }

    /** In-memory upload source. */
    private static final class BytesSource extends InMemorySourceFile {
        private final String name;
        private final byte[] data;

        BytesSource(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getLength() {
            return data.length;
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(data);
        }
    }

    /** Referenced so the FileMode import documents which SSHJ type {@link #toEntry} switches over. */
    @SuppressWarnings("unused")
    private static final Class<?> FILE_MODE_TYPE = FileMode.Type.class;
}
