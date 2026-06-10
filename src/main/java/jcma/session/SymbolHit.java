package jcma.session;

import jcma.index.Symbol;

import java.nio.file.Path;

/**
 * One {@code search_symbols} match (M2 task-04): a {@link Symbol} paired with the resolved declaring
 * {@link Path} (so the agent never sees a raw {@code fileId}). The session resolves the path via its
 * {@code FileTable}/{@code repoRoot}; {@code file} is {@code null} only for an external symbol, which
 * the overlay-aware {@code search} never returns (phantoms excluded). A neutral, query-package-free
 * carrier — it keeps the ranking + shaping layers off any session↔query dependency cycle.
 *
 * @param symbol the matched declaration
 * @param file   the declaring source file, or {@code null} if external (not expected from search)
 */
public record SymbolHit(Symbol symbol, Path file) {}
