package com.wm.adapter.wxssh.service;

/**
 * Pure precedence rules for the ExecCommand settings: a value supplied in the service input pipeline wins over
 * the value configured in the service's "Command Settings" panel, which wins over the built-in default.
 * <p>
 * Why: the rule lives in its own class, with no ADK or Integration Server types, so it can be unit-tested on a
 * machine that has no Integration Server. The service class only extracts the raw strings from the pipeline.
 * <p>
 * Side effects: none; every method is a pure function of its arguments.
 */
final class ExecSettings {

    /** Timeout applied when neither the input nor the configuration supplies a usable value. */
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private ExecSettings() {
    }

    /**
     * Picks the command to run.
     * <p>
     * Why: lets a flow supply the command at runtime so the service needs no configured default.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the Command Settings panel, may be null or blank.
     * @return the input when it is not blank, otherwise the configured value (possibly null or blank, in which
     *         case the SSH layer rejects the call with {@code INVALID_CONFIG: command is required}).
     */
    static String resolveCommand(String input, String configured) {
        if (input != null && !input.isBlank()) {
            return input;
        }
        return configured;
    }

    /**
     * Picks the timeout in seconds.
     * <p>
     * Why: an input that is missing, blank, negative or not a number must not silently discard a valid configured
     * value, so each source is tried in turn before the built-in default is used.
     *
     * @param input      value from the input pipeline, may be null, blank or invalid.
     * @param configured value from the Command Settings panel, may be null, blank or invalid.
     * @return the first non-negative integer found in {@code input}, then {@code configured}, else
     *         {@value #DEFAULT_TIMEOUT_SECONDS}. A result of {@code 0} means "wait indefinitely".
     */
    static int resolveTimeoutSeconds(String input, String configured) {
        Integer value = parseNonNegative(input);
        if (value != null) {
            return value;
        }
        value = parseNonNegative(configured);
        return value != null ? value : DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * Picks whether to request a pseudo-terminal.
     * <p>
     * Why: an explicit {@code "false"} in the input must be able to turn off a configured {@code "true"}, so a
     * non-blank input always decides, whatever its content.
     *
     * @param input      value from the input pipeline, may be null or blank.
     * @param configured value from the Command Settings panel, may be null or blank.
     * @return {@code Boolean.parseBoolean} of the input when it is not blank, otherwise of the configured value;
     *         anything other than "true" (case-insensitive) is false.
     */
    static boolean resolveAllocatePty(String input, String configured) {
        if (input != null && !input.isBlank()) {
            return Boolean.parseBoolean(input.trim());
        }
        return configured != null && Boolean.parseBoolean(configured.trim());
    }

    private static Integer parseNonNegative(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(text.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
