package com.luisloja.wxssh.core;

/** How the client authenticates to the SSH server. */
public enum AuthMethod {
    /** SSH {@code password} authentication. */
    PASSWORD,
    /** Public-key authentication using a private key file or inline key material. */
    PUBLIC_KEY,
    /** Keyboard-interactive authentication where every prompt is answered with the configured password. */
    KEYBOARD_INTERACTIVE_PASSWORD
}
