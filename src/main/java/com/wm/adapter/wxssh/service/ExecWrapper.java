package com.wm.adapter.wxssh.service;

import java.util.UUID;

/**
 * Wraps a remote command so the service can learn which directory the shell ended up in, and unwraps the result.
 * <p>
 * Why: every SSH exec channel starts in the user's home directory and forgets any {@code cd} afterwards. To let a
 * flow carry a working directory from one call to the next, the command is run inside a small POSIX shell script
 * that (1) optionally changes into the requested directory first and (2) prints a unique marker line followed by
 * {@code $PWD} from an {@code EXIT} trap. The trap fires on normal completion and on an explicit {@code exit N} in
 * the user's command, and it keeps the original exit status.
 * <p>
 * The {@code cd} runs <em>before</em> the trap is installed on purpose: if the requested directory does not exist,
 * the script exits with the shell's own error and prints no marker, so the caller keeps the directory it had instead
 * of being told the (unrelated) home directory.
 * <p>
 * Limits (by design, not bugs): the script is POSIX {@code sh} syntax, so it works for sh, bash, dash, ksh, zsh and
 * busybox but not for csh/fish or a Windows cmd/PowerShell default shell. When the marker never arrives (timeout,
 * kill, {@code exec} replacing the shell, stdout beyond the in-memory cap, unsupported shell) the caller keeps the
 * directory it already had.
 * <p>
 * Side effects: none; every method is a pure function. The random marker only makes a collision with the command's
 * own output practically impossible.
 */
final class ExecWrapper {

    /** Result of {@link #parse}: the command's stdout without the marker, and the directory reported by the trap. */
    record Parsed(String stdout, String workingDirectory) {
    }

    private ExecWrapper() {
    }

    /**
     * Creates a marker that will not plausibly appear in real command output.
     *
     * @return a fresh marker made of letters, digits and underscores only (safe inside a shell script)
     */
    static String newMarker() {
        return "__WXSSH_PWD_" + UUID.randomUUID().toString().replace("-", "") + "__";
    }

    /**
     * Builds the shell script that runs {@code command} and reports the final directory.
     * <p>
     * Why braces on their own lines: a trailing comment or heredoc in the user's command cannot swallow the closing
     * brace, and the command runs in the current shell so its {@code cd} and variables take effect.
     *
     * @param command          the user's command line; the caller must not wrap a blank command
     * @param workingDirectory directory to {@code cd} into first, or null/blank to start where the shell starts
     * @param marker           value from {@link #newMarker()}
     * @return the script to pass as the SSH exec command
     */
    static String wrap(String command, String workingDirectory, String marker) {
        StringBuilder script = new StringBuilder();
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            script.append("cd ").append(quote(workingDirectory)).append(" || exit 1\n");
        }
        script.append("trap 'printf \"\\n%s\\n\" \"").append(marker).append("$PWD\"' EXIT\n");
        script.append("{\n").append(command).append("\n}");
        return script.toString();
    }

    /**
     * Separates the marker line from the command's real stdout.
     * <p>
     * Why exact reversal: the trap prints a newline before the marker, so exactly one newline (and a preceding
     * carriage return when a PTY is in use) is removed along with it. Output that did or did not end in a newline
     * therefore comes back unchanged.
     *
     * @param stdout captured standard output, possibly containing the marker
     * @param marker the marker used in {@link #wrap}
     * @return the cleaned stdout and the reported directory; the directory is null when the marker is absent, in
     *         which case stdout is returned untouched
     */
    static Parsed parse(String stdout, String marker) {
        if (stdout == null) {
            return new Parsed(null, null);
        }
        int at = stdout.lastIndexOf(marker);
        if (at < 0) {
            return new Parsed(stdout, null);
        }
        String path = stdout.substring(at + marker.length());
        if (path.endsWith("\r\n")) {
            path = path.substring(0, path.length() - 2);
        } else if (path.endsWith("\n")) {
            path = path.substring(0, path.length() - 1);
        }
        int cut = at;
        if (cut > 0 && stdout.charAt(cut - 1) == '\n') {
            cut--;
            if (cut > 0 && stdout.charAt(cut - 1) == '\r') {
                cut--;
            }
        }
        return new Parsed(stdout.substring(0, cut), path.isEmpty() ? null : path);
    }

    /**
     * Quotes a value for a POSIX shell so it is one literal word, whatever it contains.
     * <p>
     * Why: the directory comes from a pipeline value and ends up inside a shell command line; single-quoting with
     * the {@code '\''} escape stops spaces, {@code ;}, {@code $()}, backticks and similar from being interpreted.
     *
     * @param value text to quote
     * @return the value wrapped in single quotes with embedded single quotes escaped
     */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
