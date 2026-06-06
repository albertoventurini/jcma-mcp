package jcma.index;

/**
 * One symbol node — the read/write unit of {@link SymbolStore}, materialised from / into the §5.1
 * columnar layout ({@code kind, flags, enclosing, fileId, range, nameRef, sigRef, monikerRef}).
 *
 * <p>Identity is the {@link #moniker()} (a stable, structured {@link Moniker} string that survives
 * re-indexing and can name symbols in jars we never parse); the store interns it to the compact
 * {@code int32} id used everywhere else. Containment is carried as the <em>enclosing symbol's
 * moniker</em> ({@code null} for a top-level symbol) rather than an id, so the chain survives a
 * rewrite that reassigns ids.
 *
 * @param moniker          stable structured identity (see {@link Moniker}); never {@code null}
 * @param kind             symbol kind ({@link SymbolKind})
 * @param flags            packed jcma-owned bitset, opaque to the store; bit 0 is the {@link
 *                         SourceSet} (see {@link #sourceSet()}), bits 1+ reserved
 * @param enclosingMoniker moniker of the containing symbol, or {@code null} if top-level
 * @param fileId           declaring file id, or {@code -1} for a phantom/external symbol
 * @param range            declaration range, or {@link Range#NONE} if external/unknown
 * @param name             simple (unqualified) name
 * @param signature        fully-qualified signature, or {@code null} if the kind has none
 */
public record Symbol(
        String moniker,
        SymbolKind kind,
        int flags,
        String enclosingMoniker,
        int fileId,
        Range range,
        String name,
        String signature) {

    public Symbol {
        if (moniker == null) {
            throw new IllegalArgumentException("symbol moniker must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("symbol kind must not be null");
        }
        if (range == null) {
            throw new IllegalArgumentException("symbol range must not be null (use Range.NONE)");
        }
    }

    /** A phantom symbol = declared nowhere we parse ({@code fileId == -1}); see PRD §5.1 dangling refs. */
    public boolean isPhantom() {
        return fileId == -1;
    }

    /** The source set this symbol's file belongs to ({@link SourceSet#MAIN} unless {@code flags} bit 0 is set). */
    public SourceSet sourceSet() {
        return SourceSet.of(flags);
    }
}
