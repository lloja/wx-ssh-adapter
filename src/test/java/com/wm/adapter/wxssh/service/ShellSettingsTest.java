package com.wm.adapter.wxssh.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests of the pure parsing rules in {@link ShellSettings}. */
class ShellSettingsTest {

    @Test
    void splitScriptSeparatesLinesOfAnyStyle() {
        assertEquals(List.of("a", "b", "c", "d"), ShellSettings.splitScript("a\nb\r\nc\rd"));
    }

    @Test
    void splitScriptKeepsInnerBlankLinesAndDropsTrailingOnes() {
        assertEquals(List.of("a", "", "b"), ShellSettings.splitScript("a\n\nb\n\n  \n"));
    }

    @Test
    void splitScriptDoesNotTrimLines() {
        assertEquals(List.of("  indented ", "x"), ShellSettings.splitScript("  indented \nx"));
    }

    @Test
    void splitScriptOfNothingIsEmpty() {
        assertTrue(ShellSettings.splitScript(null).isEmpty());
        assertTrue(ShellSettings.splitScript("").isEmpty());
        assertTrue(ShellSettings.splitScript("  \n \r\n").isEmpty());
    }

    @Test
    void lineEndingNamesAreMappedAndCaseInsensitive() {
        assertEquals("\n", ShellSettings.resolveLineEnding("lf", null));
        assertEquals("\r", ShellSettings.resolveLineEnding(" CR ", null));
        assertEquals("\r\n", ShellSettings.resolveLineEnding("Crlf", null));
    }

    @Test
    void lineEndingFallsBackFromInputToPanelToLf() {
        assertEquals("\r", ShellSettings.resolveLineEnding(null, "CR"));
        assertEquals("\r", ShellSettings.resolveLineEnding("  ", "CR"));
        assertEquals("\n", ShellSettings.resolveLineEnding("LF", "CR"));
        assertEquals("\n", ShellSettings.resolveLineEnding(null, null));
        assertEquals("\n", ShellSettings.resolveLineEnding("", " "));
    }

    @Test
    void anUnknownLineEndingIsRejectedWithItsValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ShellSettings.resolveLineEnding("\\r\\n", null));
        assertTrue(e.getMessage().contains("lineEnding") && e.getMessage().contains("\\r\\n"), e.getMessage());
    }

    @Test
    void charsetIsResolvedFromInputThenPanelThenUtf8() {
        assertEquals(StandardCharsets.ISO_8859_1, ShellSettings.resolveCharset("ISO-8859-1", "UTF-16"));
        assertEquals(StandardCharsets.UTF_16, ShellSettings.resolveCharset(null, "UTF-16"));
        assertEquals(StandardCharsets.UTF_8, ShellSettings.resolveCharset(" ", ""));
    }

    @Test
    void anUnknownCharsetIsRejectedInsteadOfFallingBack() {
        assertThrows(IllegalArgumentException.class, () -> ShellSettings.resolveCharset("no-such-charset", null));
        assertThrows(IllegalArgumentException.class, () -> ShellSettings.resolveCharset("bad name!", null));
    }

    @Test
    void timeoutSecondsBecomeMilliseconds() {
        assertEquals(30_000L, ShellSettings.toMillis(30));
        assertEquals(0L, ShellSettings.toMillis(0));
    }
}
