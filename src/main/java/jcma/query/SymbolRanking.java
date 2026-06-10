package jcma.query;

import jcma.index.Symbol;

import java.util.Comparator;

/**
 * The relevance ordering for {@code search_symbols} (M2 task-04) — promoted from an internal property
 * of {@link jcma.index.TrigramIndex}'s scan order to a named, <b>structure-independent</b> business
 * rule, so it survives swapping the underlying name index and is applied to the merged base+overlay
 * result rather than depending on any one segment's order.
 *
 * <p>Three tiers by how the query matches the simple name — tier 0 <b>exact</b>, tier 1 <b>prefix</b>,
 * tier 2 <b>substring</b> — then a deterministic tiebreak: shorter name first (a tighter match), then
 * the name lexicographically, then the moniker (a stable, unique final key). Case-sensitive, mirroring
 * {@link jcma.index.LsmStore#search}.
 */
public final class SymbolRanking {

    private SymbolRanking() {}

    /** A comparator ordering symbols by relevance to {@code query} (see the class doc). */
    public static Comparator<Symbol> byRelevance(String query) {
        return Comparator.<Symbol>comparingInt(s -> tier(s.name(), query))
                .thenComparingInt(s -> s.name() == null ? Integer.MAX_VALUE : s.name().length())
                .thenComparing(s -> s.name() == null ? "" : s.name())
                .thenComparing(Symbol::moniker);
    }

    /** Match tier: 0 exact, 1 prefix, 2 substring, 3 none (case-sensitive; a null name is worst). */
    private static int tier(String name, String query) {
        if (name == null) {
            return 3;
        }
        if (name.equals(query)) {
            return 0;
        }
        if (name.startsWith(query)) {
            return 1;
        }
        return name.contains(query) ? 2 : 3;
    }
}
