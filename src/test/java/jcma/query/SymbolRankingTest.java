package jcma.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * M2 task-04 (red-first) — the promoted, structure-independent {@link SymbolRanking} business rule, in
 * isolation (no index): tier 0 exact → tier 1 prefix → tier 2 substring, then tiebreak shorter name →
 * lexicographic name → moniker. Case-sensitive, mirroring {@link jcma.index.LsmStore#search}.
 */
class SymbolRankingTest {

    private static Symbol sym(String name) {
        return sym(name, name + "#");
    }

    private static Symbol sym(String name, String moniker) {
        return new Symbol(moniker, SymbolKind.CLASS, 0, null, 0, Range.NONE, name, null);
    }

    /** Sort {@code names} (as symbols) by relevance to {@code query} and return the resulting name order. */
    private static List<String> order(String query, List<Symbol> symbols) {
        return symbols.stream()
                .sorted(SymbolRanking.byRelevance(query))
                .map(Symbol::name)
                .collect(Collectors.toList());
    }

    @Test
    void tiersAreExactThenPrefixThenSubstring() {
        List<String> got = order("Foo", List.of(sym("BarFoo"), sym("Foobar"), sym("Foo")));
        assertEquals(List.of("Foo", "Foobar", "BarFoo"), got, "exact < prefix < substring");
    }

    @Test
    void shorterNameWinsWithinATier() {
        List<String> got = order("X", List.of(sym("Xabcd"), sym("Xab")));
        assertEquals(List.of("Xab", "Xabcd"), got, "within prefix tier, the shorter (tighter) match ranks first");
    }

    @Test
    void lexicographicNameBreaksAnEqualLengthTie() {
        List<String> got = order("X", List.of(sym("Xb"), sym("Xa")));
        assertEquals(List.of("Xa", "Xb"), got, "same tier + same length → lexicographic by name");
    }

    @Test
    void monikerIsTheFinalTiebreakForAnIdenticalName() {
        Symbol b = sym("Foo", "b/Foo#");
        Symbol a = sym("Foo", "a/Foo#");
        List<String> monikers = List.of(a, b).stream()
                .sorted(SymbolRanking.byRelevance("Foo"))
                .map(Symbol::moniker)
                .collect(Collectors.toList());
        assertEquals(List.of("a/Foo#", "b/Foo#"), monikers, "identical name → moniker is the stable final key");
    }

    @Test
    void matchIsCaseSensitive() {
        // "foo" (lowercase) is a substring of neither "Foo" nor "FOO"; only the exact-cased "foo" matches a tier.
        List<String> got = order("foo", List.of(sym("Foo"), sym("foobar"), sym("foo")));
        assertEquals("foo", got.get(0), "exact-cased match ranks ahead of the wrong-case names");
        assertEquals("foobar", got.get(1), "the only other real (prefix) match comes next");
    }
}
