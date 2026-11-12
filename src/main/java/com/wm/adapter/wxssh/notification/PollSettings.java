package com.wm.adapter.wxssh.notification;

import com.luisloja.wxssh.core.RemoteDirectoryPoller;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.PostAction;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.SortOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns the text values of the notification panel into a validated {@link RemoteDirectoryPoller.Settings}.
 *
 * <p>Why separate from the notification class: Designer stores every panel value as a string, so a typo such as
 * {@code "ture"} or {@code "ten"} must be caught here and reported with the field name. Keeping this free of ADK
 * types means it is unit tested without an Integration Server.
 *
 * <p>Side effects: none. Every failure is an {@link IllegalArgumentException} whose message names the setting.
 */
final class PollSettings {

    /** Default number of files delivered per poll. */
    static final int DEFAULT_MAX_FILES = 100;

    /** Default size limit for delivering file content in the document: 1 MiB. */
    static final long DEFAULT_MAX_CONTENT_BYTES = 1_048_576L;

    private PollSettings() {
    }

    /**
     * Parses the panel.
     *
     * @param directory       remote directory (required)
     * @param filter          glob on file names, blank for all
     * @param recursive       "true"/"false", blank means false
     * @param minAgeSeconds   whole number of seconds, blank means 0
     * @param stabilityCheck  "true"/"false", blank means true (the safe default for in-flight uploads)
     * @param postAction      NONE, DELETE, RENAME or MOVE, blank means NONE
     * @param postActionArg   suffix (RENAME) or target directory (MOVE)
     * @param sortOrder       NAME, OLDEST_FIRST or NEWEST_FIRST, blank means NAME
     * @param maxFilesPerPoll whole number 1 or more, blank means {@value #DEFAULT_MAX_FILES}
     * @param includeContent  "true"/"false", blank means false
     * @param maxContentBytes whole number 0 or more, blank means {@value #DEFAULT_MAX_CONTENT_BYTES}
     * @return the settings
     * @throws IllegalArgumentException for any value that cannot be used, naming it
     */
    static RemoteDirectoryPoller.Settings parse(String directory, String filter, String recursive,
                                                String minAgeSeconds, String stabilityCheck, String postAction,
                                                String postActionArg, String sortOrder, String maxFilesPerPoll,
                                                String includeContent, String maxContentBytes) {
        return new RemoteDirectoryPoller.Settings(
                trim(directory),
                blankToNull(filter),
                bool("recursive", recursive, false),
                number("minAgeSeconds", minAgeSeconds, 0),
                bool("stabilityCheck", stabilityCheck, true),
                constant("postAction", postAction, PostAction.class, PostAction.NONE),
                blankToNull(postActionArg),
                constant("sortOrder", sortOrder, SortOrder.class, SortOrder.NAME),
                (int) Math.min(Integer.MAX_VALUE, number("maxFilesPerPoll", maxFilesPerPoll, DEFAULT_MAX_FILES)),
                bool("includeContent", includeContent, false),
                number("maxContentBytes", maxContentBytes, DEFAULT_MAX_CONTENT_BYTES));
    }

    /**
     * Resolves the character set used to decode file content into the {@code content} text field.
     *
     * @param name charset name, blank means UTF-8
     * @return the charset
     * @throws IllegalArgumentException for an unknown or illegal name
     */
    static Charset charset(String name) {
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("charset '" + name.trim() + "' is not a known character set");
        }
    }

    /**
     * Makes a notification name safe to use as a file name.
     *
     * @param nodeName full namespace name such as {@code my.pkg:myNotification}
     * @return the name with every character outside {@code [A-Za-z0-9._-]} replaced by {@code _}
     */
    static String fileSafe(String nodeName) {
        if (nodeName == null || nodeName.isBlank()) {
            return "unnamed";
        }
        return nodeName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean bool(String field, String value, boolean dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1":
                return true;
            case "false", "no", "0":
                return false;
            default:
                throw new IllegalArgumentException(field + " must be true or false but was '" + value.trim() + "'");
        }
    }

    private static long number(String field, String value, long dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number but was '" + value.trim() + "'");
        }
    }

    private static <E extends Enum<E>> E constant(String field, String value, Class<E> type, E dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder all = new StringBuilder();
            for (E c : type.getEnumConstants()) {
                all.append(all.length() == 0 ? "" : ", ").append(c.name());
            }
            throw new IllegalArgumentException(field + " must be one of " + all + " but was '" + value.trim() + "'");
        }
    }
}
