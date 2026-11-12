package com.wm.adapter.wxssh.connection;

import com.luisloja.wxssh.core.AuthMethod;
import com.luisloja.wxssh.core.ErrorCode;
import com.luisloja.wxssh.core.HostKeyPolicy;
import com.luisloja.wxssh.core.SshAdapterException;

/**
 * Strict parsing of the text values of the Designer connection form.
 * <p>
 * Why: every connection field arrives as a string. The previous parsing swallowed mistakes: a non-numeric port or
 * timeout silently became the default, a mistyped {@code authMethod} became {@code PASSWORD}, and a mistyped
 * {@code hostKeyPolicy} became {@code ACCEPT_ANY}, which switches host-key checking off. A typo could therefore
 * weaken security with no message. Here a value is either valid or it is refused with a message that names the
 * field and says what is allowed. A <em>blank</em> numeric or boolean value still means "use the default", which is
 * what an untouched optional field looks like; a blank {@code authMethod} or {@code hostKeyPolicy} is refused
 * because both are required fields and neither has a safe thing to guess.
 * <p>
 * This class has no ADK types so it can be unit-tested without an Integration Server. It has no side effects.
 * None of the fields it handles is a secret, so values are echoed (shortened) in messages to help find the typo.
 */
final class ConnectionSettings {

    private ConnectionSettings() {
    }

    /**
     * Parses a whole number setting.
     *
     * @param field    the connection field name used in the message, for example {@code "port"}
     * @param value    the text from the form; may be null or blank
     * @param fallback the value used when {@code value} is null or blank
     * @param min      smallest accepted value, inclusive
     * @param max      largest accepted value, inclusive
     * @return the parsed number, or {@code fallback} when the value is blank
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} when the text is not a whole number or is out
     *                             of range
     */
    static int parseInt(String field, String value, int fallback, int min, int max) throws SshAdapterException {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalid(field + " '" + shorten(value) + "' is not a whole number");
        }
        if (parsed < min || parsed > max) {
            throw invalid(field + " " + parsed + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    /**
     * Parses the authentication method. The names are the ones the Designer drop-down offers, compared without
     * regard to case.
     *
     * @param value the text from the form
     * @return the matching method; {@code KEYBOARD_INTERACTIVE} maps to
     *         {@link AuthMethod#KEYBOARD_INTERACTIVE_PASSWORD}
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} when blank or not one of the offered names
     */
    static AuthMethod parseAuthMethod(String value) throws SshAdapterException {
        String v = value == null ? "" : value.trim();
        if (v.equalsIgnoreCase("PASSWORD")) {
            return AuthMethod.PASSWORD;
        }
        if (v.equalsIgnoreCase("PUBLIC_KEY")) {
            return AuthMethod.PUBLIC_KEY;
        }
        if (v.equalsIgnoreCase("KEYBOARD_INTERACTIVE")) {
            return AuthMethod.KEYBOARD_INTERACTIVE_PASSWORD;
        }
        throw invalid(notValid("authMethod", value, "PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE"));
    }

    /**
     * Parses the host-key policy. A mistake must never end up as {@code ACCEPT_ANY}, so there is no fallback.
     *
     * @param value the text from the form
     * @return the matching policy
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} when blank or not one of the offered names
     */
    static HostKeyPolicy parseHostKeyPolicy(String value) throws SshAdapterException {
        String v = value == null ? "" : value.trim();
        if (v.equalsIgnoreCase("ACCEPT_ANY")) {
            return HostKeyPolicy.ACCEPT_ANY;
        }
        if (v.equalsIgnoreCase("KNOWN_HOSTS")) {
            return HostKeyPolicy.KNOWN_HOSTS;
        }
        if (v.equalsIgnoreCase("PINNED_FINGERPRINT")) {
            return HostKeyPolicy.PINNED_FINGERPRINT;
        }
        throw invalid(notValid("hostKeyPolicy", value, "ACCEPT_ANY, KNOWN_HOSTS, PINNED_FINGERPRINT"));
    }

    /**
     * Parses a true/false setting. {@link Boolean#parseBoolean(String)} was not used because it turns every typo
     * into {@code false}.
     *
     * @param field    the connection field name used in the message
     * @param value    the text from the form; blank means {@code false}
     * @return the parsed flag
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} when not blank, {@code true} or {@code false}
     */
    static boolean parseFlag(String field, String value) throws SshAdapterException {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        throw invalid(notValid(field, value, "true, false"));
    }

    private static String notValid(String field, String value, String allowed) {
        if (value == null || value.isBlank()) {
            return field + " is required; use one of " + allowed;
        }
        return field + " '" + shorten(value) + "' is not valid; use one of " + allowed;
    }

    private static String shorten(String value) {
        String v = value.trim();
        return v.length() <= 40 ? v : v.substring(0, 40) + "...";
    }

    private static SshAdapterException invalid(String message) {
        return new SshAdapterException(ErrorCode.INVALID_CONFIG, message, null);
    }
}
