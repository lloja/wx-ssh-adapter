package com.wm.adapter.wxssh.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisloja.wxssh.core.AuthMethod;
import com.luisloja.wxssh.core.ErrorCode;
import com.luisloja.wxssh.core.HostKeyPolicy;
import com.luisloja.wxssh.core.SshAdapterException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectionSettings}: a connection value is either valid or refused with a message that
 * names the field. The security-relevant cases are the host-key policy and the authentication method, which used
 * to turn a typo into {@code ACCEPT_ANY} and {@code PASSWORD}. Pure Java, no Integration Server needed.
 */
class ConnectionSettingsTest {

    private static SshAdapterException refused(org.junit.jupiter.api.function.Executable e) {
        SshAdapterException ex = assertThrows(SshAdapterException.class, e);
        assertEquals(ErrorCode.INVALID_CONFIG, ex.getCode());
        return ex;
    }

    // ---- numbers ----

    @Test
    void validNumberIsParsedAndTrimmed() throws Exception {
        assertEquals(2222, ConnectionSettings.parseInt("port", " 2222 ", 22, 1, 65535));
    }

    @Test
    void blankOrMissingNumberMeansTheDefault() throws Exception {
        assertEquals(22, ConnectionSettings.parseInt("port", null, 22, 1, 65535));
        assertEquals(22, ConnectionSettings.parseInt("port", "", 22, 1, 65535));
        assertEquals(22, ConnectionSettings.parseInt("port", "   ", 22, 1, 65535));
    }

    @Test
    void nonNumericNumberIsRefusedWithTheFieldName() {
        SshAdapterException ex = refused(() -> ConnectionSettings.parseInt("port", "22a", 22, 1, 65535));
        assertTrue(ex.getMessage().contains("port"), ex.getMessage());
        assertTrue(ex.getMessage().contains("22a"), ex.getMessage());
        refused(() -> ConnectionSettings.parseInt("connectTimeoutMs", "30 s", 30000, 0, Integer.MAX_VALUE));
        refused(() -> ConnectionSettings.parseInt("readTimeoutMs", "1.5", 30000, 0, Integer.MAX_VALUE));
    }

    @Test
    void numberTooLargeForAnIntIsRefused() {
        refused(() -> ConnectionSettings.parseInt("port", "99999999999", 22, 1, 65535));
    }

    @Test
    void numberOutsideItsRangeIsRefused() {
        SshAdapterException ex = refused(() -> ConnectionSettings.parseInt("port", "70000", 22, 1, 65535));
        assertTrue(ex.getMessage().contains("between 1 and 65535"), ex.getMessage());
        refused(() -> ConnectionSettings.parseInt("port", "0", 22, 1, 65535));
        refused(() -> ConnectionSettings.parseInt("keepAliveSeconds", "-1", 0, 0, Integer.MAX_VALUE));
        refused(() -> ConnectionSettings.parseInt("maxConcurrentChannels", "0", 8, 1, Integer.MAX_VALUE));
    }

    @Test
    void numbersAtTheEdgesOfTheRangeAreAccepted() throws Exception {
        assertEquals(1, ConnectionSettings.parseInt("port", "1", 22, 1, 65535));
        assertEquals(65535, ConnectionSettings.parseInt("port", "65535", 22, 1, 65535));
        assertEquals(0, ConnectionSettings.parseInt("readTimeoutMs", "0", 30000, 0, Integer.MAX_VALUE));
    }

    // ---- auth method ----

    @Test
    void everyOfferedAuthMethodIsAcceptedInAnyCase() throws Exception {
        assertEquals(AuthMethod.PASSWORD, ConnectionSettings.parseAuthMethod("PASSWORD"));
        assertEquals(AuthMethod.PASSWORD, ConnectionSettings.parseAuthMethod(" password "));
        assertEquals(AuthMethod.PUBLIC_KEY, ConnectionSettings.parseAuthMethod("PUBLIC_KEY"));
        assertEquals(AuthMethod.PUBLIC_KEY, ConnectionSettings.parseAuthMethod("public_key"));
        assertEquals(AuthMethod.KEYBOARD_INTERACTIVE_PASSWORD, ConnectionSettings.parseAuthMethod("KEYBOARD_INTERACTIVE"));
    }

    @Test
    void mistypedAuthMethodIsRefusedInsteadOfBecomingPassword() {
        SshAdapterException ex = refused(() -> ConnectionSettings.parseAuthMethod("PUBLICKEY"));
        assertTrue(ex.getMessage().contains("authMethod"), ex.getMessage());
        assertTrue(ex.getMessage().contains("PUBLICKEY"), ex.getMessage());
        assertTrue(ex.getMessage().contains("PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE"), ex.getMessage());
        refused(() -> ConnectionSettings.parseAuthMethod("KEYBOARD_INTERACTIVE_PASSWORD"));
    }

    @Test
    void blankAuthMethodIsRefused() {
        SshAdapterException ex = refused(() -> ConnectionSettings.parseAuthMethod(""));
        assertTrue(ex.getMessage().contains("required"), ex.getMessage());
        refused(() -> ConnectionSettings.parseAuthMethod(null));
    }

    // ---- host key policy ----

    @Test
    void everyOfferedHostKeyPolicyIsAcceptedInAnyCase() throws Exception {
        assertEquals(HostKeyPolicy.ACCEPT_ANY, ConnectionSettings.parseHostKeyPolicy("ACCEPT_ANY"));
        assertEquals(HostKeyPolicy.KNOWN_HOSTS, ConnectionSettings.parseHostKeyPolicy("known_hosts"));
        assertEquals(HostKeyPolicy.PINNED_FINGERPRINT, ConnectionSettings.parseHostKeyPolicy(" Pinned_Fingerprint "));
    }

    @Test
    void mistypedHostKeyPolicyIsRefusedInsteadOfBecomingAcceptAny() {
        for (String typo : new String[]{"KNOWN_HOST", "KNOWNHOSTS", "PINNED", "STRICT", "NONE", "ACCEPT"}) {
            SshAdapterException ex = refused(() -> ConnectionSettings.parseHostKeyPolicy(typo));
            assertTrue(ex.getMessage().contains("hostKeyPolicy"), ex.getMessage());
            assertTrue(ex.getMessage().contains(typo), ex.getMessage());
            assertTrue(ex.getMessage().contains("ACCEPT_ANY, KNOWN_HOSTS, PINNED_FINGERPRINT"), ex.getMessage());
        }
    }

    @Test
    void blankHostKeyPolicyIsRefusedInsteadOfBecomingAcceptAny() {
        SshAdapterException ex = refused(() -> ConnectionSettings.parseHostKeyPolicy(""));
        assertTrue(ex.getMessage().contains("required"), ex.getMessage());
        refused(() -> ConnectionSettings.parseHostKeyPolicy("   "));
        refused(() -> ConnectionSettings.parseHostKeyPolicy(null));
    }

    // ---- flag ----

    @Test
    void flagAcceptsTrueAndFalseInAnyCaseAndBlankMeansFalse() throws Exception {
        assertTrue(ConnectionSettings.parseFlag("compression", "true"));
        assertTrue(ConnectionSettings.parseFlag("compression", " TRUE "));
        assertFalse(ConnectionSettings.parseFlag("compression", "false"));
        assertFalse(ConnectionSettings.parseFlag("compression", ""));
        assertFalse(ConnectionSettings.parseFlag("compression", null));
    }

    @Test
    void mistypedFlagIsRefusedInsteadOfBecomingFalse() {
        SshAdapterException ex = refused(() -> ConnectionSettings.parseFlag("compression", "ture"));
        assertTrue(ex.getMessage().contains("compression"), ex.getMessage());
        refused(() -> ConnectionSettings.parseFlag("compression", "yes"));
    }

    // ---- messages ----

    @Test
    void aVeryLongBadValueIsShortenedInTheMessage() {
        String junk = "x".repeat(500);
        SshAdapterException ex = refused(() -> ConnectionSettings.parseHostKeyPolicy(junk));
        assertTrue(ex.getMessage().length() < 200, "message length " + ex.getMessage().length());
        assertTrue(ex.getMessage().contains("..."), ex.getMessage());
    }

    // ---- through the connection factory ----

    @Test
    void factoryRefusesATypoInTheHostKeyPolicy() {
        SshConnectionFactory f = validFactory();
        f.setHostKeyPolicy("KNOWN_HOST");
        SshAdapterException ex = refused(f::toSshConnectionConfig);
        assertTrue(ex.getMessage().contains("hostKeyPolicy"), ex.getMessage());
    }

    @Test
    void factoryRefusesABadPortAndABadTimeout() {
        SshConnectionFactory f = validFactory();
        f.setPort("22x");
        assertTrue(refused(f::toSshConnectionConfig).getMessage().contains("port"));
        SshConnectionFactory g = validFactory();
        g.setConnectTimeoutMs("soon");
        assertTrue(refused(g::toSshConnectionConfig).getMessage().contains("connectTimeoutMs"));
    }

    @Test
    void factoryBuildsTheConfigurationForGoodValues() throws Exception {
        SshConnectionFactory f = validFactory();
        f.setPort("2222");
        f.setHostKeyPolicy("KNOWN_HOSTS");
        f.setCompression("true");
        com.luisloja.wxssh.core.SshConnectionConfig c = f.toSshConnectionConfig();
        assertEquals(2222, c.port());
        assertEquals(HostKeyPolicy.KNOWN_HOSTS, c.hostKeyPolicy());
        assertEquals(AuthMethod.PASSWORD, c.authMethod());
        assertTrue(c.compression());
        assertEquals(8, c.maxConcurrentChannels());
    }

    @Test
    void factoryDefaultsStillBuildAValidConfiguration() throws Exception {
        com.luisloja.wxssh.core.SshConnectionConfig c = validFactory().toSshConnectionConfig();
        assertEquals(22, c.port());
        assertEquals(HostKeyPolicy.ACCEPT_ANY, c.hostKeyPolicy());
        c.validate();
    }

    private static SshConnectionFactory validFactory() {
        SshConnectionFactory f = new SshConnectionFactory();
        f.setHost("example.test");
        f.setUsername("u");
        f.setPassword("p");
        return f;
    }
}
