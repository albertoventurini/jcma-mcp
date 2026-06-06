package jcma.index;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory ground truth for {@link LsmStoreTest} — the production-shaped form of M0
 * {@code SpikeD.Oracle}, in moniker space and over typed {@link MonikerEdge}s. Applying a
 * {@link FileIndex} replaces that file's prior symbols + edges (same wholesale-per-file semantics as
 * {@link LsmStore#applyEdit}); queries are answered against the accumulated state so the test can
 * assert {@code contains}/{@code fwd}/{@code rev}/{@code search} match the store cheaply.
 *
 * <p>Edges in the test are made value-distinct (a unique occurrence range each), so the adjacency
 * sets need no multiplicity bookkeeping and set-equality against the store is exact.
 */
final class LsmOracle {

    private final Map<Integer, FileIndex> byFile = new HashMap<>();
    private final Map<String, Integer> symFile = new HashMap<>();          // declared moniker -> fileId
    private final Map<String, Symbol> declared = new HashMap<>();          // declared moniker -> latest Symbol
    private final Map<String, Set<MonikerEdge>> fwd = new HashMap<>();     // src moniker -> outgoing
    private final Map<String, Set<MonikerEdge>> rev = new HashMap<>();     // dst moniker -> incoming

    void applyFile(FileIndex c) {
        FileIndex old = byFile.get(c.fileId());
        if (old != null) {
            for (Symbol s : old.symbols()) {
                symFile.remove(s.moniker(), c.fileId());
                declared.remove(s.moniker());
            }
            for (MonikerEdge e : old.edges()) {
                remove(fwd, e.src(), e);
                remove(rev, e.dst(), e);
            }
        }
        byFile.put(c.fileId(), c);
        for (Symbol s : c.symbols()) {
            symFile.put(s.moniker(), c.fileId());
            declared.put(s.moniker(), s);
        }
        for (MonikerEdge e : c.edges()) {
            add(fwd, e.src(), e);
            add(rev, e.dst(), e);
        }
    }

    /** True if the moniker is declared somewhere live (a dangling/phantom endpoint is not "contained"). */
    boolean contains(String moniker) {
        return symFile.containsKey(moniker);
    }

    Set<MonikerEdge> fwd(String moniker) {
        return new HashSet<>(fwd.getOrDefault(moniker, Set.of()));
    }

    Set<MonikerEdge> rev(String moniker) {
        return new HashSet<>(rev.getOrDefault(moniker, Set.of()));
    }

    /** Monikers of declared symbols whose simple name contains {@code query} (case-sensitive substring). */
    Set<String> search(String query) {
        Set<String> out = new HashSet<>();
        for (Symbol s : declared.values()) {
            if (s.name() != null && s.name().contains(query)) {
                out.add(s.moniker());
            }
        }
        return out;
    }

    private static void add(Map<String, Set<MonikerEdge>> adj, String key, MonikerEdge e) {
        adj.computeIfAbsent(key, k -> new HashSet<>()).add(e);
    }

    private static void remove(Map<String, Set<MonikerEdge>> adj, String key, MonikerEdge e) {
        Set<MonikerEdge> s = adj.get(key);
        if (s != null) {
            s.remove(e);
            if (s.isEmpty()) {
                adj.remove(key);
            }
        }
    }
}
