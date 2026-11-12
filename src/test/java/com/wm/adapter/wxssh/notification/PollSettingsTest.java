package com.wm.adapter.wxssh.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisloja.wxssh.core.RemoteDirectoryPoller.PostAction;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.Settings;
import com.luisloja.wxssh.core.RemoteDirectoryPoller.SortOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests of {@link PollSettings}: defaults, accepted spellings and the messages for rejected values. */
class PollSettingsTest {

    private static Settings parse(String dir, String recursive, String minAge, String stable, String action,
                                  String arg, String sort, String max, String content, String maxContent) {
        return PollSettings.parse(dir, " *.csv ", recursive, minAge, stable, action, arg, sort, max, content,
                maxContent);
    }

    @Test
    void emptyOptionalValuesGiveTheSafeDefaults() {
        Settings s = parse(" /in ", "", null, " ", "", "", "", "", "", "");

        assertEquals("/in", s.directory());
        assertEquals("*.csv", s.filter());
        assertFalse(s.recursive());
        assertEquals(0, s.minAgeSeconds());
        assertTrue(s.stabilityCheck(), "the stability check is on unless switched off");
        assertEquals(PostAction.NONE, s.postAction());
        assertNull(s.postActionArg());
        assertEquals(SortOrder.NAME, s.sortOrder());
        assertEquals(100, s.maxFilesPerPoll());
        assertFalse(s.includeContent());
        assertEquals(1_048_576L, s.maxContentBytes());
    }

    @Test
    void valuesAreCaseInsensitiveAndTrimmed() {
        Settings s = parse("/in", " TRUE ", " 30 ", "no", " rename ", " .done ", "oldest_first", " 5 ", "Yes", "10");

        assertTrue(s.recursive());
        assertEquals(30, s.minAgeSeconds());
        assertFalse(s.stabilityCheck());
        assertEquals(PostAction.RENAME, s.postAction());
        assertEquals(".done", s.postActionArg());
        assertEquals(SortOrder.OLDEST_FIRST, s.sortOrder());
        assertEquals(5, s.maxFilesPerPoll());
        assertTrue(s.includeContent());
        assertEquals(10, s.maxContentBytes());
    }

    @Test
    void rejectedValuesNameTheSettingAndTheValue() {
        assertMessage("directory is required", () -> parse(" ", "", "", "", "", "", "", "", "", ""));
        assertMessage("recursive must be true or false but was 'ture'", () -> parse("/in", "ture", "", "", "", "", "", "", "", ""));
        assertMessage("minAgeSeconds must be a whole number but was 'ten'", () -> parse("/in", "", "ten", "", "", "", "", "", "", ""));
        assertMessage("postAction must be one of NONE, DELETE, RENAME, MOVE but was 'erase'",
                () -> parse("/in", "", "", "", "erase", "", "", "", "", ""));
        assertMessage("sortOrder must be one of NAME, OLDEST_FIRST, NEWEST_FIRST but was 'size'",
                () -> parse("/in", "", "", "", "", "", "size", "", "", ""));
        assertMessage("maxFilesPerPoll must be at least 1", () -> parse("/in", "", "", "", "", "", "", "0", "", ""));
        assertMessage("minAgeSeconds must not be negative", () -> parse("/in", "", "-1", "", "", "", "", "", "", ""));
        assertMessage("a rename suffix is required", () -> parse("/in", "", "", "", "RENAME", " ", "", "", "", ""));
        assertMessage("a target directory is required", () -> parse("/in", "", "", "", "MOVE", "", "", "", "", ""));
    }

    @Test
    void charsetDefaultsToUtf8AndRejectsUnknownNames() {
        assertEquals(StandardCharsets.UTF_8, PollSettings.charset(" "));
        assertEquals(StandardCharsets.ISO_8859_1, PollSettings.charset(" iso-8859-1 "));
        assertMessage("charset 'nope' is not a known character set", () -> PollSettings.charset("nope"));
    }

    @Test
    void notificationNamesBecomeSafeFileNames() {
        assertEquals("my.pkg_myNotification", PollSettings.fileSafe("my.pkg:myNotification"));
        assertEquals("a_b_c", PollSettings.fileSafe("a/b\\c"));
        assertEquals("unnamed", PollSettings.fileSafe(" "));
    }

    private static void assertMessage(String expectedStart, Runnable r) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(ex.getMessage().startsWith(expectedStart), ex.getMessage());
    }
}
