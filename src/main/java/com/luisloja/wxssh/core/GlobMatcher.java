package com.luisloja.wxssh.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Turns a shell-style glob into a predicate over a single file name.
 *
 * <p>Why a hand-written matcher: {@code java.nio.file.PathMatcher} matches case-insensitively on Windows and rejects
 * names that are legal on the remote Unix host, so the adapter host's operating system would change what a filter
 * matches on the server. A glob translated into a {@code java.util.regex} pattern is also unsafe, because a chain of
 * stars (for example {@code *a*a*a*a*a*b}) makes the regex engine backtrack exponentially on a name that almost
 * matches. The matcher here keeps only the position of the last star and retries from there, so it runs in
 * O(name length x pattern length) however many stars there are.
 *
 * <p>Supported syntax: {@code *} (any run of characters, including none), {@code ?} (exactly one character),
 * {@code [abc]} / {@code [a-z]} / {@code [!abc]} (character class, negated with {@code !} or {@code ^}; a class
 * needs at least one member and has no escapes), a brace group of comma separated alternatives (one level, no
 * nesting) and a backslash that takes the next character literally. A comma or closing brace outside a group is an
 * ordinary character. Matching is case-sensitive and must cover the whole name. A glob is matched against the file
 * <em>name</em>, never against a path, so {@code *} also matches a leading dot.
 *
 * <p>Brace groups are expanded before matching, so {@code {a,b}{1,2}} becomes four plain patterns; more than
 * {@value #MAX_ALTERNATIVES} resulting patterns is rejected.
 *
 * <p>Side effects: none.
 */
final class GlobMatcher {

    /** Most plain patterns a single glob may expand to through brace groups. */
    static final int MAX_ALTERNATIVES = 256;

    private enum Kind { LITERAL, ANY_ONE, ANY_RUN, CLASS }

    /** One element of a compiled pattern. Only the fields of its {@link Kind} are used. */
    private record Token(Kind kind, int literal, boolean negated, int[] ranges) {
        static Token literal(int cp) {
            return new Token(Kind.LITERAL, cp, false, null);
        }

        boolean accepts(int cp) {
            switch (kind) {
                case LITERAL:
                    return cp == literal;
                case ANY_ONE:
                    return true;
                case CLASS:
                    boolean inside = false;
                    for (int r = 0; r < ranges.length; r += 2) {
                        if (cp >= ranges[r] && cp <= ranges[r + 1]) {
                            inside = true;
                            break;
                        }
                    }
                    return inside != negated;
                default:
                    return false;
            }
        }
    }

    private GlobMatcher() {
    }

    /**
     * Compiles a glob.
     *
     * @param glob the pattern, not blank
     * @return a predicate that is true when the whole name matches
     * @throws IllegalArgumentException when the glob is malformed: an unclosed bracket or brace group, an empty
     *                                  character class, a nested brace group, a trailing backslash, or too many
     *                                  brace combinations
     */
    static Predicate<String> compile(String glob) {
        List<List<Token>> alternatives = new ArrayList<>();
        alternatives.add(new ArrayList<>());
        int i = 0;
        while (i < glob.length()) {
            int cp = glob.codePointAt(i);
            if (cp == '{') {
                i = expandGroup(glob, i + 1, alternatives);
            } else {
                List<Token> atom = new ArrayList<>();
                i = parseAtom(glob, i, atom);
                for (List<Token> alternative : alternatives) {
                    alternative.addAll(atom);
                }
            }
        }
        List<List<Token>> patterns = alternatives;
        return name -> {
            int[] chars = name.codePoints().toArray();
            for (List<Token> pattern : patterns) {
                if (matches(pattern, chars)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Reads one brace group starting just after its opening brace and multiplies it into the alternatives built so
     * far.
     *
     * @return the index just after the closing brace
     */
    private static int expandGroup(String glob, int start, List<List<Token>> alternatives) {
        List<List<Token>> options = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int i = start;
        boolean closed = false;
        while (i < glob.length()) {
            int cp = glob.codePointAt(i);
            if (cp == '}') {
                i++;
                closed = true;
                break;
            }
            if (cp == ',') {
                options.add(current);
                current = new ArrayList<>();
                i++;
            } else if (cp == '{') {
                throw new IllegalArgumentException("nested '{' in glob: " + glob);
            } else {
                i = parseAtom(glob, i, current);
            }
        }
        if (!closed) {
            throw new IllegalArgumentException("unclosed '{' in glob: " + glob);
        }
        options.add(current);
        if ((long) alternatives.size() * options.size() > MAX_ALTERNATIVES) {
            throw new IllegalArgumentException("too many {a,b} combinations (more than " + MAX_ALTERNATIVES
                    + ") in glob: " + glob);
        }
        List<List<Token>> expanded = new ArrayList<>();
        for (List<Token> prefix : alternatives) {
            for (List<Token> option : options) {
                List<Token> combined = new ArrayList<>(prefix);
                combined.addAll(option);
                expanded.add(combined);
            }
        }
        alternatives.clear();
        alternatives.addAll(expanded);
        return i;
    }

    /**
     * Reads one pattern element that is not a brace and appends it to {@code out}.
     *
     * @return the index just after the element
     */
    private static int parseAtom(String glob, int start, List<Token> out) {
        int cp = glob.codePointAt(start);
        int i = start + Character.charCount(cp);
        switch (cp) {
            case '*' -> {
                // consecutive stars add nothing, so keep one
                if (out.isEmpty() || out.get(out.size() - 1).kind() != Kind.ANY_RUN) {
                    out.add(new Token(Kind.ANY_RUN, 0, false, null));
                }
            }
            case '?' -> out.add(new Token(Kind.ANY_ONE, 0, false, null));
            case '[' -> i = parseClass(glob, i, out);
            case '\\' -> {
                if (i >= glob.length()) {
                    throw new IllegalArgumentException("trailing '\\' in glob: " + glob);
                }
                int next = glob.codePointAt(i);
                i += Character.charCount(next);
                out.add(Token.literal(next));
            }
            default -> out.add(Token.literal(cp));
        }
        return i;
    }

    /**
     * Reads a character class whose opening bracket was just consumed.
     *
     * @return the index just after the closing bracket
     */
    private static int parseClass(String glob, int start, List<Token> out) {
        int close = glob.indexOf(']', start);
        if (close < 0) {
            throw new IllegalArgumentException("unclosed '[' in glob: " + glob);
        }
        int[] body = glob.substring(start, close).codePoints().toArray();
        int from = 0;
        boolean negated = false;
        if (body.length > 0 && (body[0] == '!' || body[0] == '^')) {
            negated = true;
            from = 1;
        }
        if (from >= body.length) {
            throw new IllegalArgumentException("empty character class in glob: " + glob);
        }
        List<Integer> ranges = new ArrayList<>();
        for (int k = from; k < body.length; k++) {
            int lo = body[k];
            int hi = lo;
            if (k + 2 < body.length && body[k + 1] == '-') {
                hi = body[k + 2];
                k += 2;
            }
            ranges.add(lo);
            ranges.add(hi);
        }
        int[] flat = ranges.stream().mapToInt(Integer::intValue).toArray();
        out.add(new Token(Kind.CLASS, 0, negated, flat));
        return close + 1;
    }

    /**
     * Matches one plain pattern against a whole name, remembering only the last star so the work stays
     * proportional to name length times pattern length.
     */
    private static boolean matches(List<Token> pattern, int[] name) {
        int p = 0;
        int n = 0;
        int starP = -1;
        int starN = 0;
        while (n < name.length) {
            if (p < pattern.size() && pattern.get(p).kind() == Kind.ANY_RUN) {
                starP = p;
                starN = n;
                p++;
            } else if (p < pattern.size() && pattern.get(p).accepts(name[n])) {
                p++;
                n++;
            } else if (starP >= 0) {
                p = starP + 1;
                starN++;
                n = starN;
            } else {
                return false;
            }
        }
        while (p < pattern.size() && pattern.get(p).kind() == Kind.ANY_RUN) {
            p++;
        }
        return p == pattern.size();
    }
}
