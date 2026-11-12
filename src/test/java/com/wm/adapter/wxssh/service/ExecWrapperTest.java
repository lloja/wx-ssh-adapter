package com.wm.adapter.wxssh.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecWrapper}: script construction, shell quoting and marker parsing. Pure Java; the
 * behaviour of the generated script in a real shell is checked separately against an OpenSSH server.
 */
class ExecWrapperTest {

    private static final String M = "__WXSSH_PWD_TEST__";

    // ---- marker ----

    @Test
    void markersAreUniqueAndShellSafe() {
        String a = ExecWrapper.newMarker();
        String b = ExecWrapper.newMarker();
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-Za-z0-9_]+"), a);
    }

    // ---- quote ----

    @Test
    void quoteWrapsInSingleQuotes() {
        assertEquals("'/var/log'", ExecWrapper.quote("/var/log"));
    }

    @Test
    void quoteNeutralisesShellMetacharacters() {
        assertEquals("'a b; rm -rf /'", ExecWrapper.quote("a b; rm -rf /"));
        assertEquals("'$(id)`id`'", ExecWrapper.quote("$(id)`id`"));
    }

    @Test
    void quoteEscapesEmbeddedSingleQuotes() {
        assertEquals("'it'\\''s'", ExecWrapper.quote("it's"));
    }

    // ---- wrap ----

    @Test
    void wrapWithoutDirectoryHasNoCd() {
        String s = ExecWrapper.wrap("ls", null, M);
        assertFalse(s.contains("cd "), s);
        assertTrue(s.contains("trap "), s);
        assertTrue(s.contains(M + "$PWD"), s);
        assertTrue(s.endsWith("{\nls\n}"), s);
    }

    @Test
    void wrapTreatsBlankDirectoryAsAbsent() {
        assertFalse(ExecWrapper.wrap("ls", "   ", M).contains("cd "));
        assertFalse(ExecWrapper.wrap("ls", "", M).contains("cd "));
    }

    @Test
    void wrapChangesDirectoryBeforeInstallingTheTrap() {
        String s = ExecWrapper.wrap("ls", "/tmp", M);
        int cd = s.indexOf("cd '/tmp' || exit 1");
        int trap = s.indexOf("trap ");
        assertTrue(cd >= 0, s);
        assertTrue(trap > cd, "a failed cd must exit before the trap exists so no marker is printed: " + s);
    }

    @Test
    void wrapQuotesTheDirectory() {
        String s = ExecWrapper.wrap("ls", "/tmp/x; echo pwned", M);
        assertTrue(s.contains("cd '/tmp/x; echo pwned' || exit 1"), s);
    }

    @Test
    void wrapKeepsTheUsersCommandOnItsOwnLines() {
        String s = ExecWrapper.wrap("echo hi # trailing comment", null, M);
        assertTrue(s.endsWith("{\necho hi # trailing comment\n}"), s);
    }

    // ---- parse ----

    @Test
    void parseSplitsOutputFromDirectory() {
        ExecWrapper.Parsed p = ExecWrapper.parse("hello\n\n" + M + "/home/wxtest\n", M);
        assertEquals("hello\n", p.stdout());
        assertEquals("/home/wxtest", p.workingDirectory());
    }

    @Test
    void parseRestoresOutputWithoutTrailingNewline() {
        ExecWrapper.Parsed p = ExecWrapper.parse("hello\n" + M + "/tmp\n", M);
        assertEquals("hello", p.stdout());
        assertEquals("/tmp", p.workingDirectory());
    }

    @Test
    void parseHandlesNoOutputAtAll() {
        ExecWrapper.Parsed p = ExecWrapper.parse("\n" + M + "/tmp\n", M);
        assertEquals("", p.stdout());
        assertEquals("/tmp", p.workingDirectory());
    }

    @Test
    void parseHandlesPtyCarriageReturns() {
        ExecWrapper.Parsed p = ExecWrapper.parse("hello\r\n\r\n" + M + "/tmp\r\n", M);
        assertEquals("hello\r\n", p.stdout());
        assertEquals("/tmp", p.workingDirectory());
    }

    @Test
    void parseKeepsPathsWithSpaces() {
        assertEquals("/srv/my dir", ExecWrapper.parse("x\n" + M + "/srv/my dir\n", M).workingDirectory());
    }

    @Test
    void parseWithoutMarkerReturnsStdoutUntouchedAndNoDirectory() {
        ExecWrapper.Parsed p = ExecWrapper.parse("partial output", M);
        assertEquals("partial output", p.stdout());
        assertNull(p.workingDirectory());
    }

    @Test
    void parseUsesTheLastMarkerOccurrence() {
        ExecWrapper.Parsed p = ExecWrapper.parse("echo of " + M + "/fake\n\n" + M + "/real\n", M);
        assertEquals("/real", p.workingDirectory());
        assertEquals("echo of " + M + "/fake\n", p.stdout());
    }

    @Test
    void parseOfNullOrEmptyPathGivesNoDirectory() {
        assertNull(ExecWrapper.parse(null, M).stdout());
        assertNull(ExecWrapper.parse("x\n" + M + "\n", M).workingDirectory());
    }
}
