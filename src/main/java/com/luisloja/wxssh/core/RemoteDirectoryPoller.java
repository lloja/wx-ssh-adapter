package com.luisloja.wxssh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Decides, poll after poll, which files in a remote directory are new and ready, hands each one to a sink and then
 * applies the configured post-action. This is the SSH-free, ADK-free core of the polling notification.
 *
 * <p>Why a class of its own: the rules that make a poller safe (no re-firing after a restart, no grabbing of a
 * half-uploaded file, no silent loss) are subtle and must be tested against a real server and with a fake clock,
 * none of which is possible inside the Integration Server's notification scheduler.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Seen files.</b> A file is identified by path plus size plus modification time. Once handed to the sink it
 *       is not handed over again unless its size or time changes (it was replaced). Entries for files that are no
 *       longer listed are dropped, so the state cannot grow without bound.</li>
 *   <li><b>In-flight uploads.</b> A file younger than {@code minAgeSeconds} (by the server's modification time
 *       against this machine's clock) is left alone. With {@code stabilityCheck} a file is also only taken when
 *       its size and time are the same on two consecutive polls; that check does not depend on the two clocks
 *       agreeing, so it is the safer choice when they might not.</li>
 *   <li><b>Delivery.</b> At least once. The sink is called first, the file is marked seen and the state saved
 *       after it returns, and the post-action runs last. If the process dies between the sink and the save the
 *       file is delivered again after the restart; it is never lost. A failing sink stops the poll (the
 *       file stays unseen and is retried on the next one).</li>
 *   <li><b>Post-action.</b> A failing post-action does not undo the delivery: the file stays marked seen so it is
 *       not delivered twice, and the failure is reported in {@link PollResult#postActionFailures()}.</li>
 * </ul>
 *
 * <p>Thread-safety: not thread-safe. One instance per notification, polled by one thread at a time (the notification
 * scheduler never overlaps runs of the same notification on one node).
 */
public final class RemoteDirectoryPoller {

    /** What happens to a file after the sink accepted it. */
    public enum PostAction {
        /** Leave the file where it is; the seen-state alone prevents a second delivery. */
        NONE,
        /** Delete it from the server. */
        DELETE,
        /** Rename it in place by appending the configured suffix (for example {@code .done}). */
        RENAME,
        /** Move it into the configured remote directory (created when missing). */
        MOVE
    }

    /** Order in which ready files are delivered when more than {@code maxFilesPerPoll} are ready. */
    public enum SortOrder {
        /** Alphabetical by full path. */
        NAME,
        /** Oldest modification time first; ties broken by path. */
        OLDEST_FIRST,
        /** Newest modification time first; ties broken by path. */
        NEWEST_FIRST
    }

    /**
     * Validated polling configuration.
     *
     * @param directory         remote directory to watch, not blank
     * @param filter            glob on the file name (null/blank = all files)
     * @param recursive         also look in sub-directories
     * @param minAgeSeconds     minimum age by modification time, 0 or more
     * @param stabilityCheck    require unchanged size and time across two polls
     * @param postAction        what to do after delivery
     * @param postActionArg     suffix for {@code RENAME}, directory for {@code MOVE}, ignored otherwise
     * @param sortOrder         delivery order
     * @param maxFilesPerPoll   at most this many files are delivered per poll, 1 or more
     * @param includeContent    also read the file content and pass it to the sink
     * @param maxContentBytes   files larger than this are delivered without content ({@code contentSkipped})
     */
    public record Settings(String directory, String filter, boolean recursive, long minAgeSeconds,
                           boolean stabilityCheck, PostAction postAction, String postActionArg,
                           SortOrder sortOrder, int maxFilesPerPoll, boolean includeContent,
                           long maxContentBytes) {

        /**
         * Rejects settings that could not work, before any network I/O.
         *
         * @throws IllegalArgumentException naming the offending setting
         */
        public Settings {
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("directory is required");
            }
            if (minAgeSeconds < 0) {
                throw new IllegalArgumentException("minAgeSeconds must not be negative but was " + minAgeSeconds);
            }
            if (maxFilesPerPoll < 1) {
                throw new IllegalArgumentException("maxFilesPerPoll must be at least 1 but was " + maxFilesPerPoll);
            }
            if (maxContentBytes < 0) {
                throw new IllegalArgumentException("maxContentBytes must not be negative but was " + maxContentBytes);
            }
            if (postAction == null) {
                postAction = PostAction.NONE;
            }
            if (sortOrder == null) {
                sortOrder = SortOrder.NAME;
            }
            if (postAction == PostAction.RENAME && (postActionArg == null || postActionArg.isBlank())) {
                throw new IllegalArgumentException("a rename suffix is required when the post-action is RENAME");
            }
            if (postAction == PostAction.RENAME && postActionArg.contains("/")) {
                throw new IllegalArgumentException("the rename suffix must not contain '/' (use MOVE to change directory)");
            }
            if (postAction == PostAction.MOVE && (postActionArg == null || postActionArg.isBlank())) {
                throw new IllegalArgumentException("a target directory is required when the post-action is MOVE");
            }
        }
    }

    /**
     * One file handed to the sink.
     *
     * @param path            full remote path
     * @param name            file name
     * @param size            size in bytes as listed
     * @param modifiedSec     modification time, seconds since the epoch
     * @param content         the content when requested and small enough, else null
     * @param contentSkipped  true when content was requested but not read because the file is above the limit
     */
    public record PolledFile(String path, String name, long size, long modifiedSec, byte[] content,
                             boolean contentSkipped) {
    }

    /** Receives one ready file. A thrown exception stops the poll and leaves the file for the next one. */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param file the ready file
         * @throws Exception if the file could not be passed on (nothing is marked seen in that case)
         */
        void accept(PolledFile file) throws Exception;
    }

    /**
     * Outcome of one poll.
     *
     * @param listed               files seen in the directory (after the filter)
     * @param delivered            files handed to the sink
     * @param waiting              files not yet ready (too young, or not yet stable)
     * @param skippedUnreadable    files whose content could not be read; retried on the next poll
     * @param postActionFailures   one message per file whose post-action failed
     */
    public record PollResult(int listed, int delivered, int waiting, int skippedUnreadable,
                             List<String> postActionFailures) {
    }

    /** The remote operations the poller needs; {@link SshSession} is adapted to it by {@link #poll}. */
    interface Remote {
        List<RemoteEntry> list(String directory, String filter, boolean recursive) throws SshAdapterException;

        byte[] read(String path) throws SshAdapterException;

        void delete(String path) throws SshAdapterException;

        void move(String from, String to) throws SshAdapterException;
    }

    /** What is remembered about one path. */
    private record Seen(boolean delivered, long size, long modifiedSec) {
    }

    private final Settings settings;
    private final Path stateFile;
    private final LongSupplier nowSeconds;
    private Map<String, Seen> state;

    /**
     * Creates a poller.
     *
     * @param settings   validated configuration
     * @param stateFile  file in which the seen-state survives a restart; null keeps it in memory only (every
     *                   file is then delivered again after a restart)
     */
    public RemoteDirectoryPoller(Settings settings, Path stateFile) {
        this(settings, stateFile, () -> System.currentTimeMillis() / 1000);
    }

    /** Test seam: same as the public constructor with an injectable clock. */
    RemoteDirectoryPoller(Settings settings, Path stateFile, LongSupplier nowSeconds) {
        this.settings = settings;
        this.stateFile = stateFile;
        this.nowSeconds = nowSeconds;
    }

    /**
     * Runs one poll against a live session.
     *
     * <p>Why: the notification's scheduler calls this once per interval.
     *
     * <p>Side effects: lists the directory (one SFTP channel), reads files when content is requested, applies the
     * post-action, calls the sink, and rewrites the state file after every delivered file. All server operations
     * go through the session's own channel limiter.
     *
     * @param session an open session
     * @param sink    receives each ready file
     * @return what happened
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} for a malformed filter; the listing's errors
     *                             (for example {@link ErrorCode#SFTP_STATUS} when the directory is missing);
     *                             {@link ErrorCode#IO_ERROR} when the sink fails or the state cannot be saved
     */
    public PollResult poll(SshSession session, Sink sink) throws SshAdapterException {
        return poll(new Remote() {
            @Override
            public List<RemoteEntry> list(String directory, String filter, boolean recursive)
                    throws SshAdapterException {
                return session.sftpList(directory, new ListOptions(filter, recursive, true));
            }

            @Override
            public byte[] read(String path) throws SshAdapterException {
                return session.sftpGet(path);
            }

            @Override
            public void delete(String path) throws SshAdapterException {
                session.sftpDelete(path);
            }

            @Override
            public void move(String from, String to) throws SshAdapterException {
                session.sftpRename(from, to, false, true);
            }
        }, sink);
    }

    PollResult poll(Remote remote, Sink sink) throws SshAdapterException {
        loadState();
        List<RemoteEntry> listing = remote.list(settings.directory(), settings.filter(), settings.recursive());

        long now = nowSeconds.getAsLong();
        List<RemoteEntry> ready = new ArrayList<>();
        Map<String, Seen> next = new LinkedHashMap<>();
        Set<String> present = new HashSet<>();
        int files = 0;
        int waiting = 0;
        for (RemoteEntry e : listing) {
            if (e.type() != RemoteEntry.Type.FILE) {
                continue;
            }
            files++;
            present.add(e.path());
            Seen prev = state.get(e.path());
            boolean sameAsPrev = prev != null && prev.size() == e.size() && prev.modifiedSec() == e.modifiedSec();
            if (sameAsPrev && prev.delivered()) {
                next.put(e.path(), prev);
                continue;
            }
            if (now - e.modifiedSec() < settings.minAgeSeconds()) {
                waiting++;
                continue;
            }
            if (settings.stabilityCheck() && !(sameAsPrev && !prev.delivered())) {
                next.put(e.path(), new Seen(false, e.size(), e.modifiedSec()));
                waiting++;
                continue;
            }
            ready.add(e);
        }

        ready.sort(comparator());
        // From here on 'state' is the source of truth; it keeps only paths still present.
        state.keySet().retainAll(present);
        state.putAll(next);

        int delivered = 0;
        int unreadable = 0;
        List<String> postFailures = new ArrayList<>();
        saveState();
        for (RemoteEntry e : ready) {
            if (delivered >= settings.maxFilesPerPoll()) {
                waiting++;
                continue;
            }
            byte[] content = null;
            boolean skipped = false;
            if (settings.includeContent()) {
                if (e.size() > settings.maxContentBytes()) {
                    skipped = true;
                } else {
                    try {
                        content = remote.read(e.path());
                    } catch (SshAdapterException ex) {
                        unreadable++;
                        continue;
                    }
                }
            }
            try {
                sink.accept(new PolledFile(e.path(), e.name(), e.size(), e.modifiedSec(), content, skipped));
            } catch (Exception ex) {
                saveState();
                throw new SshAdapterException(ErrorCode.IO_ERROR,
                        "delivering " + e.path() + " failed, it will be retried on the next poll: " + ex.getMessage(),
                        ex);
            }
            state.put(e.path(), new Seen(true, e.size(), e.modifiedSec()));
            delivered++;
            saveState();
            String failure = applyPostAction(remote, e);
            if (failure != null) {
                postFailures.add(failure);
            } else if (settings.postAction() != PostAction.NONE) {
                state.remove(e.path());
            }
        }
        saveState();
        return new PollResult(files, delivered, waiting, unreadable, List.copyOf(postFailures));
    }

    private Comparator<RemoteEntry> comparator() {
        Comparator<RemoteEntry> byPath = Comparator.comparing(RemoteEntry::path);
        return switch (settings.sortOrder()) {
            case NAME -> byPath;
            case OLDEST_FIRST -> Comparator.comparingLong(RemoteEntry::modifiedSec).thenComparing(byPath);
            case NEWEST_FIRST ->
                    Comparator.comparingLong(RemoteEntry::modifiedSec).reversed().thenComparing(byPath);
        };
    }

    /** Applies the post-action; returns null on success or a message naming the file and the reason. */
    private String applyPostAction(Remote remote, RemoteEntry e) {
        try {
            switch (settings.postAction()) {
                case NONE -> {
                }
                case DELETE -> remote.delete(e.path());
                case RENAME -> remote.move(e.path(), e.path() + settings.postActionArg());
                case MOVE -> {
                    String dir = settings.postActionArg().endsWith("/")
                            ? settings.postActionArg() : settings.postActionArg() + "/";
                    remote.move(e.path(), dir + e.name());
                }
            }
            return null;
        } catch (SshAdapterException ex) {
            return settings.postAction() + " of " + e.path() + " failed: " + ex.getCode() + ": " + ex.getMessage();
        }
    }

    // ------------------------------------------------------------------ state file

    /**
     * Reads the state file once. A line is {@code D|P <size> <mtime> <escaped path>} separated by tabs; D means
     * delivered, P means observed once and waiting for the stability check. A malformed file is an error, not an
     * empty state: ignoring it would re-deliver everything.
     */
    private void loadState() throws SshAdapterException {
        if (state != null) {
            return;
        }
        state = new HashMap<>();
        if (stateFile == null || !Files.exists(stateFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", 4);
                if (f.length != 4 || !(f[0].equals("D") || f[0].equals("P"))) {
                    throw new SshAdapterException(ErrorCode.IO_ERROR,
                            "poll state file " + stateFile + " is damaged (bad line); fix or delete it", null);
                }
                state.put(unescape(f[3]), new Seen(f[0].equals("D"), Long.parseLong(f[1]), Long.parseLong(f[2])));
            }
        } catch (IOException | NumberFormatException e) {
            state = null;
            throw new SshAdapterException(ErrorCode.IO_ERROR,
                    "cannot read poll state file " + stateFile + ": " + e.getMessage(), e);
        }
    }

    /** Writes the state next to the real file and renames it over, so a crash never leaves half a file. */
    private void saveState() throws SshAdapterException {
        if (stateFile == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(state).forEach((path, s) -> sb.append(s.delivered() ? "D" : "P").append('\t')
                .append(s.size()).append('\t').append(s.modifiedSec()).append('\t').append(escape(path)).append('\n'));
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SshAdapterException(ErrorCode.IO_ERROR,
                    "cannot write poll state file " + stateFile + ": " + e.getMessage(), e);
        }
    }

    static String escape(String s) {
        return s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D");
    }

    static String unescape(String s) {
        return s.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%");
    }
}
