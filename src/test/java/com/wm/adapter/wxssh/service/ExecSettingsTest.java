package com.wm.adapter.wxssh.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecSettings}: pipeline input overrides the Command Settings panel, which overrides the
 * built-in default. Pure Java, no Integration Server needed.
 */
class ExecSettingsTest {

    // ---- command ----

    @Test
    void inputCommandOverridesConfiguredCommand() {
        assertEquals("uname -a", ExecSettings.resolveCommand("uname -a", "ls"));
    }

    @Test
    void configuredCommandUsedWhenInputMissingOrBlank() {
        assertEquals("ls", ExecSettings.resolveCommand(null, "ls"));
        assertEquals("ls", ExecSettings.resolveCommand("", "ls"));
        assertEquals("ls", ExecSettings.resolveCommand("   ", "ls"));
    }

    @Test
    void inputCommandAloneIsEnoughWhenNothingConfigured() {
        assertEquals("id", ExecSettings.resolveCommand("id", ""));
        assertEquals("id", ExecSettings.resolveCommand("id", null));
    }

    @Test
    void noCommandAnywhereStaysBlankSoTheSshLayerRejectsIt() {
        assertNull(ExecSettings.resolveCommand(null, null));
        assertEquals("", ExecSettings.resolveCommand("", ""));
    }

    @Test
    void commandIsPassedThroughUntouched() {
        assertEquals("  echo  hi  ", ExecSettings.resolveCommand("  echo  hi  ", "ls"));
    }

    // ---- timeout ----

    @Test
    void inputTimeoutOverridesConfiguredTimeout() {
        assertEquals(5, ExecSettings.resolveTimeoutSeconds("5", "60"));
    }

    @Test
    void configuredTimeoutUsedWhenInputMissingBlankOrInvalid() {
        assertEquals(60, ExecSettings.resolveTimeoutSeconds(null, "60"));
        assertEquals(60, ExecSettings.resolveTimeoutSeconds("", "60"));
        assertEquals(60, ExecSettings.resolveTimeoutSeconds("abc", "60"));
        assertEquals(60, ExecSettings.resolveTimeoutSeconds("-3", "60"));
    }

    @Test
    void defaultTimeoutUsedWhenNothingUsable() {
        assertEquals(30, ExecSettings.resolveTimeoutSeconds(null, null));
        assertEquals(30, ExecSettings.resolveTimeoutSeconds("x", "y"));
        assertEquals(30, ExecSettings.resolveTimeoutSeconds("-1", "-1"));
    }

    @Test
    void zeroTimeoutIsAValidValueMeaningWaitForever() {
        assertEquals(0, ExecSettings.resolveTimeoutSeconds("0", "60"));
        assertEquals(0, ExecSettings.resolveTimeoutSeconds(null, "0"));
    }

    @Test
    void timeoutIsTrimmed() {
        assertEquals(7, ExecSettings.resolveTimeoutSeconds(" 7 ", "60"));
    }

    // ---- allocatePty ----

    @Test
    void inputPtyOverridesConfiguredPtyInBothDirections() {
        assertTrue(ExecSettings.resolveAllocatePty("true", "false"));
        assertFalse(ExecSettings.resolveAllocatePty("false", "true"));
    }

    @Test
    void configuredPtyUsedWhenInputMissingOrBlank() {
        assertTrue(ExecSettings.resolveAllocatePty(null, "true"));
        assertTrue(ExecSettings.resolveAllocatePty("  ", "true"));
        assertFalse(ExecSettings.resolveAllocatePty(null, "false"));
    }

    @Test
    void ptyDefaultsToFalse() {
        assertFalse(ExecSettings.resolveAllocatePty(null, null));
        assertFalse(ExecSettings.resolveAllocatePty("", ""));
    }

    @Test
    void ptyParsingIsCaseInsensitiveAndTrimmedAndStrict() {
        assertTrue(ExecSettings.resolveAllocatePty(" TRUE ", "false"));
        assertFalse(ExecSettings.resolveAllocatePty("yes", "true"));
    }
}
