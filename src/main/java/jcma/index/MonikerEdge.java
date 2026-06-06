package jcma.index;

import java.util.Objects;

/**
 * A directed, typed edge expressed in <em>moniker space</em> — the form the {@link LsmStore} overlay
 * and the {@code Indexer} speak, before symbols are interned to {@code int32} ids.
 *
 * <p>{@link Csr} stores edges over ids (a {@link SymbolStore} assigns them by sorting monikers), but
 * a per-file edit introduces brand-new monikers that have no id yet — and an edit must be applied
 * without rewriting the whole base. So the overlay keys edges by the {@link #src}/{@link #dst}
 * monikers (M0 Spike D's design); ids are (re)assigned only at compaction, when the fresh base is
 * written. {@link LsmStore#fwd}/{@link LsmStore#rev} return {@code MonikerEdge}s so a result is
 * identical whether it came from the mmap'd base or the in-memory overlay.
 *
 * @param src        source symbol moniker; never {@code null}
 * @param dst        target symbol moniker; never {@code null}
 * @param type       edge type; never {@code null}
 * @param occurrence the use site, or {@link Occurrence#NONE} for a structural edge; never {@code null}
 */
public record MonikerEdge(String src, String dst, EdgeType type, Occurrence occurrence) {

    public MonikerEdge {
        Objects.requireNonNull(src, "edge src moniker");
        Objects.requireNonNull(dst, "edge dst moniker");
        Objects.requireNonNull(type, "edge type");
        Objects.requireNonNull(occurrence, "edge occurrence");
    }
}
