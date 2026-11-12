package com.luisloja.wxssh.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GlobMatcher}: the syntax it promises, case sensitivity, whole-name matching, and that
 * characters special to regular expressions are taken literally.
 */
class GlobMatcherTest {

    private static boolean m(String glob, String name) {
        Predicate<String> p = GlobMatcher.compile(glob);
        return p.test(name);
    }

    @Test
    void starMatchesAnyRunIncludingNoneAndLeadingDot() {
        assertTrue(m("*.csv", "a.csv"));
        assertTrue(m("*.csv", ".csv"));
        assertTrue(m("*", ""));
        assertTrue(m("*", ".hidden"));
        assertFalse(m("*.csv", "a.csv.bak"));
    }

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        assertTrue(m("r??.txt", "r12.txt"));
        assertFalse(m("r??.txt", "r1.txt"));
        assertFalse(m("r??.txt", "r123.txt"));
    }

    @Test
    void characterClassesAndNegation() {
        assertTrue(m("file[0-9].log", "file7.log"));
        assertFalse(m("file[0-9].log", "fileX.log"));
        assertTrue(m("file[!0-9].log", "fileX.log"));
        assertFalse(m("file[!0-9].log", "file7.log"));
        assertTrue(m("file[^ab].log", "filec.log"));
        assertFalse(m("file[^ab].log", "filea.log"));
    }

    @Test
    void alternativesInBraces() {
        assertTrue(m("*.{csv,txt}", "a.csv"));
        assertTrue(m("*.{csv,txt}", "a.txt"));
        assertFalse(m("*.{csv,txt}", "a.xml"));
    }

    @Test
    void commaAndClosingBraceOutsideAGroupAreLiteral() {
        assertTrue(m("a,b", "a,b"));
        assertFalse(m("a,b", "a"));
        assertTrue(m("a}b", "a}b"));
    }

    @Test
    void backslashMakesTheNextCharacterLiteral() {
        assertTrue(m("a\\*b", "a*b"));
        assertFalse(m("a\\*b", "aXb"));
        assertTrue(m("a\\?", "a?"));
    }

    @Test
    void matchingIsCaseSensitiveAndCoversTheWholeName() {
        assertFalse(m("*.CSV", "a.csv"));
        assertFalse(m("a", "ab"));
        assertFalse(m("b", "ab"));
    }

    @Test
    void regexMetaCharactersInTheGlobAreLiteral() {
        assertTrue(m("a.b", "a.b"));
        assertFalse(m("a.b", "aXb"));
        assertTrue(m("a+b(1)$", "a+b(1)$"));
        assertTrue(m("a|b", "a|b"));
        assertFalse(m("a|b", "a"));
        assertTrue(m("[[]x", "[x"));
    }

    @Test
    void newlineInANameIsMatchedByStarAndQuestionMark() {
        assertTrue(m("a*", "a\nb"));
        assertTrue(m("a?b", "a\nb"));
    }

    @Test
    void aPathologicalGlobIsStillLinearAndReturnsPromptly() {
        // "(a*)*b" style input would hang a backtracking regex written by a user; as a glob it is a plain run of
        // stars, which this matcher turns into a fixed pattern.
        String glob = "*a*a*a*a*a*a*a*a*a*a*b";
        String name = "a".repeat(40);
        long start = System.nanoTime();
        assertFalse(m(glob, name));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "glob matching took too long");
    }

    @Test
    void severalBraceGroupsMultiplyOut() {
        assertTrue(m("{a,b}{1,2}", "a1"));
        assertTrue(m("{a,b}{1,2}", "b2"));
        assertFalse(m("{a,b}{1,2}", "a3"));
        assertTrue(m("x{,y}", "x"));
        assertTrue(m("x{,y}", "xy"));
    }

    @Test
    void anEscapedCommaOrBraceInsideAGroupIsLiteral() {
        assertTrue(m("{a\\,b,c}", "a,b"));
        assertFalse(m("{a\\,b,c}", "a"));
        assertTrue(m("{a\\}b,c}", "a}b"));
    }

    @Test
    void aClassInsideAGroupMayHoldACommaOrBrace() {
        assertTrue(m("{[,},x],q}", ","));
        assertTrue(m("{[,},x],q}", "q"));
    }

    @Test
    void repeatedStarsBehaveLikeOne() {
        assertTrue(m("a***b", "ab"));
        assertTrue(m("a***b", "aXXb"));
    }

    @Test
    void supplementaryCharactersCountAsOneCharacter() {
        String smile = new String(Character.toChars(0x1F600));
        assertTrue(m("a?b", "a" + smile + "b"));
        assertTrue(m("[" + smile + "]", smile));
    }

    @Test
    void tooManyBraceCombinationsAreRejected() {
        // 4 groups of 5 alternatives = 625 patterns, above the cap
        String group = "{a,b,c,d,e}";
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile(group + group + group + group));
    }

    @Test
    void malformedGlobsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("a[bc"));
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("a[]c"));
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("a[!]c"));
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("{a,b"));
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("{a,{b,c}}"));
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("abc\\"));
    }
}
