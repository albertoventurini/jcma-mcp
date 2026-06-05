package jcma.index;

/**
 * A 1-based source range (start/end line+column) for a symbol's declaration (PRD §5.1 {@code range}
 * column) — enough to point {@code find_definition} at the declaration and to slice a context
 * snippet. {@link #NONE} marks a symbol with no source range (a phantom / external symbol whose
 * declaring file we never parse — see {@link Symbol#fileId()} {@code == -1}).
 */
public record Range(int startLine, int startCol, int endLine, int endCol) {

    /** Sentinel for "no range" — all components {@code -1}. */
    public static final Range NONE = new Range(-1, -1, -1, -1);

    public boolean isNone() {
        return equals(NONE);
    }
}
