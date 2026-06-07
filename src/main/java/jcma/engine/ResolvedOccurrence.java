package jcma.engine;

/**
 * The outcome of resolving one use-site (Tier-2). Carries the use-site (kind + name + range) and
 * exactly one of: a {@link ResolvedTarget} (resolved) or a {@link ResolveFailure} (safe-degrading
 * miss). {@code jcma.resolve} turns a resolved occurrence into a graph edge and a failed one into an
 * unconfirmed-tail entry.
 *
 * @param kind       the use-site category
 * @param targetName the simple name referenced at the use-site
 * @param startLine  1-based start line of the use-site
 * @param startCol   1-based start column
 * @param endLine    1-based end line
 * @param endCol     1-based end column
 * @param target     the resolved declaration, or {@code null} if resolution failed
 * @param failure    the miss facts, or {@code null} if resolution succeeded
 */
public record ResolvedOccurrence(OccurrenceKind kind, String targetName,
        int startLine, int startCol, int endLine, int endCol,
        ResolvedTarget target, ResolveFailure failure) {

    /** True if the use-site resolved to a declaration. */
    public boolean isResolved() {
        return target != null;
    }
}
