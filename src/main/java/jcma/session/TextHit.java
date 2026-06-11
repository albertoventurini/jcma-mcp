package jcma.session;

/**
 * One {@code grep_java} text-tier match (M3 task-01): a text occurrence resolved to its declaring
 * {@link String} path, with its precise 1-based {@code line}/{@code col}, its {@code kind} label
 * ({@code string-literal} / {@code comment} / {@code javadoc} — surfaced to the agent so a hit is
 * self-describing), and the matching line's {@code snippet}. The session resolves {@code fileId →
 * path} via its {@code FileTable}/{@code repoRoot}. A neutral, query-package-free carrier — mirrors
 * {@link SymbolHit} so the shaping/ranking layers stay off any session↔query dependency cycle.
 *
 * @param file    the declaring source file (repo-relative or absolute, as the session resolves it)
 * @param line    1-based line of the match within the file
 * @param col     1-based column of the match
 * @param kind    the text-kind label ({@code string-literal} / {@code comment} / {@code javadoc})
 * @param snippet the matching line's text
 */
public record TextHit(String file, int line, int col, String kind, String snippet) {}
