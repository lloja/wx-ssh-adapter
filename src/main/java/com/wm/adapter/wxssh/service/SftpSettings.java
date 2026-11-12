package com.wm.adapter.wxssh.service;

/**
 * Pure precedence rules for the SFTP service settings: a value supplied in the service input pipeline wins over
 * the value configured in the service's settings panel, which wins over the built-in default. An input only counts
 * when it has content: a missing, empty or whitespace-only input never overrides the panel.
 * <p>
 * Why: same reasoning as {@link ExecSettings}. The rules live in a class with no ADK or Integration Server types,
 * so they can be unit-tested without an Integration Server; the service classes only extract the raw strings.
 * <p>
 * Side effects: none; every method is a pure function of its arguments.
 */
final class SftpSettings {

    /** Directory listed when neither the input nor the configuration supplies one: the session's start directory. */
    static final String DEFAULT_REMOTE_PATH = ".";

    /** Encoding used when neither the input nor the configuration names one. */
    static final String DEFAULT_CHARSET = "UTF-8";

    private SftpSettings() {
    }

    /**
     * Picks the remote path for a listing.
     * <p>
     * Why: lets a flow choose the directory per call while the panel value stays a fallback. A blank panel value
     * falls back to {@value #DEFAULT_REMOTE_PATH} instead of sending an empty path to the server.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the SFTP Settings panel ("Default Remote Path"), may be null or blank.
     * @return the input when it is not blank, otherwise the configured value when it is not blank, otherwise
     *         {@value #DEFAULT_REMOTE_PATH}. The chosen value is returned exactly as given, without trimming.
     */
    static String resolveRemotePath(String input, String configured) {
        String chosen = resolveText(input, configured);
        return chosen != null && !chosen.isBlank() ? chosen : DEFAULT_REMOTE_PATH;
    }

    /**
     * Picks a free-text setting such as a remote file path or a local file path.
     * <p>
     * Why: for these settings "nothing" is a meaningful answer (no remote path means the service reports
     * {@code remotePath is required}; no local path means "use the pipeline"), so no default is invented here.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the settings panel, may be null or blank.
     * @return the input when it is not blank, otherwise {@code configured} as is (possibly null or blank). The
     *         chosen value is returned exactly as given, without trimming.
     */
    static String resolveText(String input, String configured) {
        if (input != null && !input.isBlank()) {
            return input;
        }
        return configured;
    }

    /**
     * Picks the character encoding name.
     * <p>
     * Why: each source is tried in turn so a blank input cannot discard a valid panel value.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the settings panel, may be null or blank.
     * @return the input when not blank, else the configured value when not blank, else {@value #DEFAULT_CHARSET}.
     *         The name is not validated here; the service decides what to do with an unknown name.
     */
    static String resolveCharset(String input, String configured) {
        if (input != null && !input.isBlank()) {
            return input;
        }
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return DEFAULT_CHARSET;
    }

    /**
     * Picks a true/false setting such as atomic upload.
     * <p>
     * Why: an explicit {@code "false"} in the input must be able to turn off a configured {@code "true"}, so a
     * non-blank input always decides, whatever its content.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the settings panel, may be null or blank.
     * @return {@code Boolean.parseBoolean} of the input when it is not blank, otherwise of the configured value
     *         (a blank or missing configured value is false); anything other than "true" (case-insensitive) is
     *         false.
     */
    static boolean resolveBoolean(String input, String configured) {
        return resolveBoolean(input, configured, false);
    }

    /**
     * Picks a true/false setting whose built-in default is not necessarily false, such as "follow links".
     * <p>
     * Why: with a plain {@link #resolveBoolean(String, String)} a cleared panel value would silently turn a
     * default-on option off. Here a blank panel value falls back to {@code defaultValue}.
     *
     * @param input        value from the input pipeline, may be null or blank.
     * @param configured   value from the settings panel, may be null or blank.
     * @param defaultValue used when both are blank.
     * @return {@code Boolean.parseBoolean} of the input when it is not blank, else of the configured value when
     *         it is not blank, else {@code defaultValue}; anything other than "true" (case-insensitive) in a
     *         non-blank value is false.
     */
    static boolean resolveBoolean(String input, String configured, boolean defaultValue) {
        if (input != null && !input.isBlank()) {
            return Boolean.parseBoolean(input.trim());
        }
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured.trim());
        }
        return defaultValue;
    }

    /**
     * Picks an optional whole-number setting such as a user id, a time or a size.
     * <p>
     * Why: for these "nothing" is meaningful (a chown that sets only the group, a set-times call that leaves the
     * access time alone), so a blank value on both sides means "not given" and is returned as null instead of
     * being turned into 0, which would silently change a file's owner to root.
     *
     * @param field      pipeline field name, used in the error message.
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the settings panel, may be null or blank.
     * @param min        smallest accepted value.
     * @param max        largest accepted value.
     * @return the number, or null when both sources are blank. The input wins when it is not blank.
     * @throws IllegalArgumentException when the chosen text is not a whole number or is outside {@code min..max};
     *                                  the message names the field, so a typo is reported instead of ignored.
     */
    static Long resolveLong(String field, String input, String configured, long min, long max) {
        String chosen = resolveText(input, configured);
        if (chosen == null || chosen.isBlank()) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(chosen.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number, got '" + chosen.trim() + "'");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ", got " + value);
        }
        return value;
    }

    /**
     * Parses POSIX permission bits written as an octal string.
     * <p>
     * Why: permissions are written in octal everywhere (640, 0755), but a number typed into a flow is easily read
     * as decimal, which would set the wrong bits without any error. Only octal digits are accepted, so 9 or 1.5
     * fail instead of being misread.
     *
     * @param field the pipeline field name, used in the error message.
     * @param text  for example {@code 640}, {@code 0640} or {@code 4755}; surrounding blanks are ignored.
     * @return the permission bits, between 0 and 07777 (rwx for three classes plus setuid, setgid and sticky).
     * @throws IllegalArgumentException when the text is blank, has a non-octal character or has more than four
     *                                  digits.
     */
    static int parseOctalPermissions(String field, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String s = text.trim();
        if (!s.matches("[0-7]{1,4}")) {
            throw new IllegalArgumentException(field + " must be 1 to 4 octal digits such as 640 or 0755, got '" + s + "'");
        }
        return Integer.parseInt(s, 8);
    }

    /** An owner or group chosen for a chown: a name to look up on the server, or a number, never both. */
    record AccountRef(String name, Long id) {
        /** True when neither a name nor an id was given. */
        boolean isEmpty() {
            return name == null && id == null;
        }
    }

    /**
     * Picks the owner or the group of a chown from a name field and a numeric-id field, each with a pipeline
     * input and a panel value.
     * <p>
     * Why: the user may know the account as a name ({@code deploy}) or only as a number ({@code 1001}). The
     * source decides first, as everywhere else: a pipeline value (name or id) beats the panel. Inside one source
     * a name together with an id is refused instead of one silently winning, because the two can disagree and
     * a chown cannot be undone without the old value.
     *
     * @param nameField  pipeline field name of the name, used in messages.
     * @param idField    pipeline field name of the id, used in messages.
     * @param inName     name from the pipeline, may be null or blank.
     * @param inId       id from the pipeline, may be null or blank.
     * @param cfgName    name from the panel, may be null or blank.
     * @param cfgId      id from the panel, may be null or blank.
     * @return the chosen account (name trimmed, or id), or an empty reference when nothing was given.
     * @throws IllegalArgumentException when one source gives both a name and an id, or the id is not a whole
     *                                  number between 0 and {@link Integer#MAX_VALUE}.
     */
    static AccountRef resolveAccount(String nameField, String idField, String inName, String inId,
                                     String cfgName, String cfgId) {
        boolean pipeline = !isBlank(inName) || !isBlank(inId);
        String name = pipeline ? inName : cfgName;
        String id = pipeline ? inId : cfgId;
        if (!isBlank(name) && !isBlank(id)) {
            throw new IllegalArgumentException("give either " + nameField + " or " + idField + ", not both");
        }
        if (!isBlank(name)) {
            return new AccountRef(name.trim(), null);
        }
        return new AccountRef(null, resolveLong(idField, id, null, 0, Integer.MAX_VALUE));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }}
