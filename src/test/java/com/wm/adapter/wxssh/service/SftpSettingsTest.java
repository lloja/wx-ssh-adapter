package com.wm.adapter.wxssh.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SftpSettings}: pipeline input overrides the SFTP Settings panel, which overrides the
 * built-in default. Pure Java, no Integration Server needed.
 */
class SftpSettingsTest {

    @Test
    void inputOverridesConfiguredPath() {
        assertEquals("/data/in", SftpSettings.resolveRemotePath("/data/in", "/config"));
    }

    @Test
    void configuredPathUsedWhenInputMissingOrBlank() {
        assertEquals("/config", SftpSettings.resolveRemotePath(null, "/config"));
        assertEquals("/config", SftpSettings.resolveRemotePath("", "/config"));
        assertEquals("/config", SftpSettings.resolveRemotePath("   ", "/config"));
    }

    @Test
    void inputAloneIsEnoughWhenNothingConfigured() {
        assertEquals("/data", SftpSettings.resolveRemotePath("/data", ""));
        assertEquals("/data", SftpSettings.resolveRemotePath("/data", null));
    }

    @Test
    void fallsBackToDotWhenNeitherSideHasAValue() {
        assertEquals(".", SftpSettings.resolveRemotePath(null, null));
        assertEquals(".", SftpSettings.resolveRemotePath("", ""));
        assertEquals(".", SftpSettings.resolveRemotePath("  ", "  "));
    }

    @Test
    void chosenValueIsReturnedUntrimmed() {
        assertEquals(" /a b ", SftpSettings.resolveRemotePath(" /a b ", "/config"));
        assertEquals(" /cfg ", SftpSettings.resolveRemotePath(null, " /cfg "));
    }

    // ---- resolveText (remote file path, local file path) ----

    @Test
    void textInputOverridesConfigured() {
        assertEquals("/in.txt", SftpSettings.resolveText("/in.txt", "/cfg.txt"));
    }

    @Test
    void textBlankInputKeepsConfiguredValueAsIs() {
        assertEquals("/cfg.txt", SftpSettings.resolveText(null, "/cfg.txt"));
        assertEquals("/cfg.txt", SftpSettings.resolveText("", "/cfg.txt"));
        assertEquals("/cfg.txt", SftpSettings.resolveText("   ", "/cfg.txt"));
    }

    @Test
    void textInventsNoDefaultSoNothingStaysNothing() {
        assertNull(SftpSettings.resolveText(null, null));
        assertEquals("", SftpSettings.resolveText("", ""));
        assertEquals("  ", SftpSettings.resolveText(null, "  "));
    }

    // ---- resolveCharset ----

    @Test
    void charsetInputOverridesConfigured() {
        assertEquals("ISO-8859-1", SftpSettings.resolveCharset("ISO-8859-1", "UTF-16"));
    }

    @Test
    void charsetFallsThroughBlankInputThenBlankConfiguredToUtf8() {
        assertEquals("UTF-16", SftpSettings.resolveCharset("", "UTF-16"));
        assertEquals("UTF-16", SftpSettings.resolveCharset("  ", "UTF-16"));
        assertEquals("UTF-8", SftpSettings.resolveCharset(null, null));
        assertEquals("UTF-8", SftpSettings.resolveCharset("", ""));
        assertEquals("UTF-8", SftpSettings.resolveCharset("  ", "  "));
    }

    // ---- resolveBoolean (atomic) ----

    @Test
    void booleanExplicitFalseInputTurnsOffConfiguredTrue() {
        assertFalse(SftpSettings.resolveBoolean("false", "true"));
    }

    @Test
    void booleanExplicitTrueInputTurnsOnConfiguredFalse() {
        assertTrue(SftpSettings.resolveBoolean("true", "false"));
        assertTrue(SftpSettings.resolveBoolean(" TRUE ", "false"));
    }

    @Test
    void booleanBlankInputUsesConfiguredValue() {
        assertTrue(SftpSettings.resolveBoolean(null, "true"));
        assertTrue(SftpSettings.resolveBoolean("", " true "));
        assertFalse(SftpSettings.resolveBoolean("  ", "false"));
    }

    @Test
    void booleanNothingAnywhereIsFalseAndGarbageIsFalse() {
        assertFalse(SftpSettings.resolveBoolean(null, null));
        assertFalse(SftpSettings.resolveBoolean("", ""));
        assertFalse(SftpSettings.resolveBoolean("yes", "true"));
        assertFalse(SftpSettings.resolveBoolean(null, "maybe"));
    }

    // ---- resolveBoolean with a default (followLinks) ----

    @Test
    void booleanWithDefaultUsesDefaultOnlyWhenBothAreBlank() {
        assertTrue(SftpSettings.resolveBoolean(null, null, true));
        assertTrue(SftpSettings.resolveBoolean("", "  ", true));
        assertFalse(SftpSettings.resolveBoolean(null, null, false));
    }

    @Test
    void booleanWithDefaultLetsInputThenPanelOverrideTheDefault() {
        assertFalse(SftpSettings.resolveBoolean("false", "true", true));
        assertFalse(SftpSettings.resolveBoolean("", "false", true));
        assertTrue(SftpSettings.resolveBoolean("true", "false", false));
        assertFalse(SftpSettings.resolveBoolean("garbage", "true", true), "a non-blank non-true value is false");
    }

    @Test
    void longIsNullWhenBothSourcesAreBlankNotZero() {
        assertNull(SftpSettings.resolveLong("uid", null, null, 0, 100));
        assertNull(SftpSettings.resolveLong("uid", "", "  ", 0, 100));
    }

    @Test
    void longInputOverridesPanelAndPanelIsUsedWhenInputIsBlank() {
        assertEquals(5L, SftpSettings.resolveLong("uid", " 5 ", "9", 0, 100));
        assertEquals(9L, SftpSettings.resolveLong("uid", "", "9", 0, 100));
        assertEquals(0L, SftpSettings.resolveLong("uid", "0", "9", 0, 100), "an explicit 0 is a value, not 'unset'");
    }

    @Test
    void longRejectsATypoNamingTheFieldInsteadOfIgnoringIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SftpSettings.resolveLong("uid", "12x", null, 0, 100));
        assertTrue(e.getMessage().contains("uid") && e.getMessage().contains("12x"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SftpSettings.resolveLong("uid", "1.5", null, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> SftpSettings.resolveLong("uid", "99999999999999999999", null, 0, 100));
    }

    @Test
    void longEnforcesTheRange() {
        assertEquals(100L, SftpSettings.resolveLong("uid", "100", null, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> SftpSettings.resolveLong("uid", "101", null, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> SftpSettings.resolveLong("uid", "-1", null, 0, 100));
    }

    @Test
    void octalPermissionsAreReadAsOctalNotDecimal() {
        assertEquals(0640, SftpSettings.parseOctalPermissions("permissions", "640"));
        assertEquals(0640, SftpSettings.parseOctalPermissions("permissions", "0640"));
        assertEquals(0755, SftpSettings.parseOctalPermissions("permissions", " 755 "));
        assertEquals(04755, SftpSettings.parseOctalPermissions("permissions", "4755"));
        assertEquals(0, SftpSettings.parseOctalPermissions("permissions", "0"));
        assertEquals(07777, SftpSettings.parseOctalPermissions("permissions", "7777"));
    }

    @Test
    void octalPermissionsRejectAnythingThatIsNotOneToFourOctalDigits() {
        for (String bad : new String[]{"", "  ", null, "9", "648", "rwx", "-1", "1.5", "12345", "0x1ff", "+644"}) {
            assertThrows(IllegalArgumentException.class, () -> SftpSettings.parseOctalPermissions("permissions", bad), "input: " + bad);
        }
    }

    @Test
    void accountNothingGivenIsEmpty() {
        assertTrue(SftpSettings.resolveAccount("userName", "uid", null, null, "", " ").isEmpty());
    }

    @Test
    void accountNameOrIdFromOneSourceIsUsedAndTheNameIsTrimmed() {
        SftpSettings.AccountRef byName = SftpSettings.resolveAccount("userName", "uid", " deploy ", "", null, null);
        assertEquals("deploy", byName.name());
        assertNull(byName.id());
        SftpSettings.AccountRef byId = SftpSettings.resolveAccount("userName", "uid", null, "1001", null, null);
        assertNull(byId.name());
        assertEquals(1001L, byId.id());
        assertEquals(0L, SftpSettings.resolveAccount("userName", "uid", null, "0", null, null).id(),
                "an explicit 0 is a value, not 'unset'");
    }

    @Test
    void accountPipelineValueOfEitherKindReplacesThePanelPair() {
        SftpSettings.AccountRef r = SftpSettings.resolveAccount("userName", "uid", "", "7", "panelUser", "");
        assertNull(r.name());
        assertEquals(7L, r.id());
        SftpSettings.AccountRef n = SftpSettings.resolveAccount("userName", "uid", "alice", "", "", "9");
        assertEquals("alice", n.name());
        assertNull(n.id());
        SftpSettings.AccountRef p = SftpSettings.resolveAccount("userName", "uid", null, null, "panelUser", "");
        assertEquals("panelUser", p.name());
    }

    @Test
    void accountNameAndIdInOneSourceAreRefusedNamingBothFields() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SftpSettings.resolveAccount("userName", "uid", "alice", "5", null, null));
        assertTrue(e.getMessage().contains("userName") && e.getMessage().contains("uid"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> SftpSettings.resolveAccount("groupName", "gid", null, null, "staff", "20"));
    }

    @Test
    void accountIdMustBeANonNegativeWholeNumber() {
        assertThrows(IllegalArgumentException.class, () -> SftpSettings.resolveAccount("userName", "uid", null, "x", null, null));
        assertThrows(IllegalArgumentException.class, () -> SftpSettings.resolveAccount("userName", "uid", null, "-1", null, null));
    }
}
