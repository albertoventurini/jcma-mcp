package jcma.resolve;

import java.nio.file.Path;

/**
 * The {@code find_definition} answer (PRD §6): the declaration site, signature, and a context
 * snippet — shaped via the ported M0 {@code describe}/{@code locate}/{@code loc} + {@code snippetOf}.
 * For a target in a dependency jar/JDK (no project source) {@code file} is {@code null} and
 * {@code line} is {@code -1}, while {@code moniker}/{@code signature} are still populated.
 *
 * @param moniker   the declaration's moniker
 * @param signature human-readable signature (or FQN when the kind has none)
 * @param file      declaring source file, or {@code null} if external
 * @param line      1-based declaration line, or {@code -1} if external/unknown
 * @param snippet   the trimmed source line at the declaration, or empty if external
 */
public record Definition(String moniker, String signature, Path file, int line, String snippet) {}
