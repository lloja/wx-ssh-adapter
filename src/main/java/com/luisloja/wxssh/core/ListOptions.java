package com.luisloja.wxssh.core;

import java.util.function.Predicate;

/**
 * Options for {@link SshSession#sftpList(String, ListOptions)}.
 *
 * <p>Why a record instead of more overloads: the options are independent switches that callers combine freely, and
 * the ADK layer builds them from three pipeline strings plus a constant.
 *
 * @param filter        glob matched against each entry <em>name</em> (see {@code GlobMatcher} for the syntax:
 *                      {@code * ? [a-z] [!x] {a,b} \}); null or blank means "no filter". A directory that does not
 *                      match is still searched when {@code recursive} is true (like {@code find -name}), it is
 *                      just not reported
 * @param recursive     true to also list the contents of sub-directories (breadth first). Symbolic links are never
 *                      followed, so a link to a directory is reported as a link and not entered, which makes loops
 *                      impossible
 * @param includeHidden false to leave out entries whose name starts with {@code .}; a hidden directory is then not
 *                      entered either
 * @param maxEntries    most entries that may be returned; going past it raises
 *                      {@link ErrorCode#LIMIT_EXCEEDED} instead of exhausting memory on a huge tree
 */
public record ListOptions(String filter, boolean recursive, boolean includeHidden, int maxEntries) {

    /** Upper bound used when the caller does not choose one. */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /** Plain listing of one directory, hidden entries included: the behaviour before options existed. */
    public static final ListOptions DEFAULT = new ListOptions(null, false, true, DEFAULT_MAX_ENTRIES);

    public ListOptions {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
    }

    /** Same as the canonical constructor with {@link #DEFAULT_MAX_ENTRIES}. */
    public ListOptions(String filter, boolean recursive, boolean includeHidden) {
        this(filter, recursive, includeHidden, DEFAULT_MAX_ENTRIES);
    }

    /**
     * Compiles the filter once so it is not re-parsed for every entry.
     *
     * @return the name predicate, or null when there is no filter
     * @throws SshAdapterException {@link ErrorCode#INVALID_CONFIG} when the glob is malformed (detected before any
     *                             network I/O)
     */
    Predicate<String> nameFilter() throws SshAdapterException {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        try {
            return GlobMatcher.compile(filter);
        } catch (IllegalArgumentException e) {
            throw new SshAdapterException(ErrorCode.INVALID_CONFIG, "invalid filter: " + e.getMessage(), e);
        }
    }
}
