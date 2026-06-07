package jcma.engine;

/**
 * A parse-only use-site (no resolution): the syntactic category, the simple name of the thing it
 * references, and its source range. This is the unit the cold-index pass feeds into the usage-name
 * index ({@code usage-names.seg}) so {@code find_references} can prune to candidate files without a
 * rescan. AST-free, so it crosses the engine seam (PRD §4).
 *
 * @param kind       the occurrence category
 * @param targetName the simple name referenced at the use-site (matches a declaration's simple name)
 * @param startLine  1-based start line
 * @param startCol   1-based start column
 * @param endLine    1-based end line
 * @param endCol     1-based end column
 */
public record UsageSite(OccurrenceKind kind, String targetName,
        int startLine, int startCol, int endLine, int endCol) {}
