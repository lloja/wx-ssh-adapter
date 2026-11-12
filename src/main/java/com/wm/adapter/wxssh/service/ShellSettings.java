package com.wm.adapter.wxssh.service;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parsing and precedence rules for the RunShellScript and StartSubsystem settings.
 * <p>
 * Why: kept free of ADK and Integration Server types so it can be unit-tested on a machine without an Integration
 * Server, like {@link ExecSettings} and {@link SftpSettings}, whose generic helpers (text, boolean, timeout) it
 * reuses. Only what is specific to scripts, line endings and strict character sets lives here.
 * <p>
 * Side effects: none; every method is a pure function of its arguments.
 */
final class ShellSettings {

    /** Prompt pattern used when neither the input nor the panel supplies one: a shell prompt ending in $ # > or %. */
    static final String DEFAULT_PROMPT_PATTERN = "[$#>%] $";

    private ShellSettings() {
    }

    /**
     * Splits a multi-line script into the lines to send.
     * <p>
     * Why: a script is typed into one multi-line field, one step per line. Line breaks of any style (LF, CR, CRLF)
     * separate lines. Blank lines inside the script are kept, because an empty Enter is a valid step (it confirms
     * a pager or a prompt); blank lines at the end are dropped, because a trailing newline in a text field is
     * almost always an accident and would send a needless extra Enter.
     *
     * @param script the script text, may be null or blank.
     * @return the lines, possibly empty (never null). The lines are not trimmed: leading blanks can matter to the
     *         remote side.
     */
    static List<String> splitScript(String script) {
        List<String> lines = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return lines;
        }
        for (String line : script.split("\\R", -1)) {
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * Picks what is sent after each line.
     * <p>
     * Why: a terminal's Enter key sends CR, a Unix pipe uses LF, and some devices want CRLF. Naming them (instead of
     * asking for escape sequences in a text field) keeps a typo from sending something unintended.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the settings panel, may be null or blank.
     * @return {@code "\n"}, {@code "\r"} or {@code "\r\n"}. The input wins when not blank, then the panel; both
     *         blank gives LF. Names are case-insensitive.
     * @throws IllegalArgumentException when the chosen name is not LF, CR or CRLF.
     */
    static String resolveLineEnding(String input, String configured) {
        String chosen = SftpSettings.resolveText(input, configured);
        if (chosen == null || chosen.isBlank()) {
            return "\n";
        }
        return switch (chosen.trim().toUpperCase(Locale.ROOT)) {
            case "LF" -> "\n";
            case "CR" -> "\r";
            case "CRLF" -> "\r\n";
            default -> throw new IllegalArgumentException(
                    "lineEnding must be LF, CR or CRLF, got '" + chosen.trim() + "'");
        };
    }

    /**
     * Picks and checks the character set used to turn text into bytes and back.
     * <p>
     * Why: the file services quietly fall back to UTF-8 on an unknown name; for a shell, silently using another
     * encoding would send garbled commands to a remote host, so a bad name is rejected here instead.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the settings panel, may be null or blank.
     * @return the character set; UTF-8 when both are blank.
     * @throws IllegalArgumentException when the name is illegal or not supported by this JVM.
     */
    static Charset resolveCharset(String input, String configured) {
        String name = SftpSettings.resolveCharset(input, configured).trim();
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException("charset '" + name + "' is not supported");
        }
    }

    /**
     * Converts a timeout in seconds to the millisecond value the SSH layer takes.
     *
     * @param seconds result of {@link ExecSettings#resolveTimeoutSeconds}; 0 means no deadline.
     * @return milliseconds, 0 for no deadline.
     */
    static long toMillis(int seconds) {
        return seconds > 0 ? seconds * 1000L : 0L;
    }
}
