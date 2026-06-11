package jcma.engine;

/**
 * The kind of a {@link TextUnit} — the D2 text corpus's three projections off one parse (PRD §6
 * {@code grep_java}; M3 task-01). Engine-neutral so the {@link AnalysisEngine} seam never leaks a
 * JavaParser type (PRD §4); the text index ({@code text.seg}) tags each indexed unit with its kind
 * so a hit can be labelled {@code string-literal} / {@code comment} / {@code javadoc} to the agent.
 *
 * <p>Text blocks fold into {@link #STRING_LITERAL} (decision D2): a text block is a string literal
 * for search purposes. Code identifiers and keywords are <em>not</em> a text kind — the symbol
 * trigram tier ({@code trigrams.seg}) already serves identifier substring search.
 */
public enum TextKind {
    /** A string literal (or text block), e.g. {@code "user not found"}. */
    STRING_LITERAL,
    /** A line ({@code //}) or block ({@code /* *}{@code /}) comment. */
    COMMENT,
    /** A Javadoc comment ({@code /** *}{@code /}). */
    JAVADOC
}
