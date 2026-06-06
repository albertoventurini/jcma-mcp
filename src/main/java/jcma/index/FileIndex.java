package jcma.index;

import java.util.List;
import java.util.Objects;

/**
 * The full structural index of a <em>single file</em> — the unit the {@code Indexer} emits and the
 * unit {@link LsmStore#applyEdit} consumes. Applying a {@code FileIndex} replaces whatever the store
 * previously held for {@link #fileId} (a file's symbols + edges are re-emitted wholesale on every
 * edit; there is no sub-file delta), so per-file mutation needs no repo rescan.
 *
 * <p>The empty content ({@link #deleted}) is a <b>tombstone</b>: it supersedes the file's base rows
 * with nothing, which is how a deletion is represented over an immutable base.
 *
 * @param fileId  the file's id (stable for the file's path); the delete tombstone carries it with
 *                empty symbols + edges
 * @param symbols the symbols declared in the file (each with {@code fileId} == this id); never {@code null}
 * @param edges   the file's outgoing edges in moniker space; never {@code null}
 */
public record FileIndex(int fileId, List<Symbol> symbols, List<MonikerEdge> edges) {

    public FileIndex {
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(edges, "edges");
        symbols = List.copyOf(symbols);
        edges = List.copyOf(edges);
    }

    /** A tombstone for {@code fileId}: the file is gone, so it contributes no symbols and no edges. */
    public static FileIndex deleted(int fileId) {
        return new FileIndex(fileId, List.of(), List.of());
    }

    /** True if this is a deletion/empty file (no symbols and no edges). */
    public boolean isEmpty() {
        return symbols.isEmpty() && edges.isEmpty();
    }
}
