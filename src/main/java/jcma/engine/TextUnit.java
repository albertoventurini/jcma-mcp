package jcma.engine;

/**
 * One unit of indexable text extracted from a {@code .java} source (M3 task-01) — a string literal,
 * comment, or Javadoc, with its 1-based source range and its text content. The third projection the
 * {@link StructuralParser} offers off a single parse (alongside {@code outline()} + {@code usages()}),
 * so the text corpus costs no extra parse. AST-free, so it crosses the engine seam (PRD §4): the
 * {@code jcma.index} text index consumes only these neutral units, never the JavaParser AST.
 *
 * <p>{@link #text} is the searchable content (a literal's value without its quotes; a comment's body
 * without its markers), preserving internal newlines so a multi-line unit's matched line can be
 * recovered as {@code startLine + (newlines before the match offset)}.
 *
 * @param kind      string-literal / comment / javadoc (text blocks fold into {@code STRING_LITERAL})
 * @param startLine 1-based start line
 * @param startCol  1-based start column
 * @param endLine   1-based end line
 * @param endCol    1-based end column
 * @param text      the unit's searchable text content
 */
public record TextUnit(TextKind kind, int startLine, int startCol, int endLine, int endCol, String text) {}
