package jcma.resolve;

import jcma.index.LsmStore;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.workspace.FreshnessGuard;
import jcma.workspace.NodeDiff;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Tier-1 ↔ Tier-2 seam (M1 task-11c): node-diff cascade invalidation. For each file reported
 * changed it (1) captures the file's current hierarchy edges, (2) re-indexes the file at Tier-1
 * (the structural node diff), (3) eagerly re-resolves the file at Tier-2 so a new
 * {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES} edge becomes visible, (4) computes the
 * hierarchy-edge change by diffing captured-vs-current, then (5) walks the <b>reverse edges</b> of
 * every removed / added / signature-changed / hierarchy-changed node to the exact referrer files,
 * returning them to <b>unresolved</b>. They re-resolve lazily on the next query that touches them;
 * the cascade itself resolves only the changed files. No trigram, no changed-name heuristic, no
 * repo-wide invalidation — exact graph reachability over the fully-modeled graph (tasks 11a/11b).
 */
public final class Cascade {

    private final FreshnessGuard guard;
    private final EdgeResolver resolver;
    private final LsmStore store;

    public Cascade(FreshnessGuard guard, EdgeResolver resolver, LsmStore store) {
        this.guard = guard;
        this.resolver = resolver;
        this.store = store;
    }

    /**
     * Re-index and cascade each path in {@code changedFiles}. Returns the set of <b>referrer files</b>
     * returned to unresolved by the cascade (empty when nothing relevant changed).
     */
    public Set<Path> refresh(Collection<Path> changedFiles) throws IOException {
        Set<String> changedMonikers = new HashSet<>();
        Set<String> changedNames = new HashSet<>();
        Set<Integer> reResolved = new HashSet<>();
        boolean engineRefreshed = false;

        for (Path file : changedFiles) {
            int fid = resolver.fileId(file);
            if (fid < 0) {
                // Untracked → discover it (FreshnessGuard allocates an id + Tier-1 indexes a real file).
                NodeDiff diff = guard.reindexOne(file);
                if (!diff.reindexed()) {
                    continue; // truly untracked: no such file (gone) — nothing to discover
                }
                int newFid = diff.fileId();
                if (!engineRefreshed) {
                    resolver.refreshEngine();
                    engineRefreshed = true;
                }
                // refreshSymbols is REQUIRED: enclosingMoniker reads symbolsByFile, so without the new
                // file's declarations its resolved occurrences would attribute to nothing (no edge written).
                resolver.refreshSymbols(newFid);
                resolver.refreshUsageOverlay(newFid);     // make the new file a find_references candidate
                Map<String, String> names = namesByMoniker(store.symbolsOf(newFid));
                collectNames(changedNames, names, diff.added()); // re-bind prior unconfirmed refs to its decls
                continue;
            }
            // Capture old declarations + hierarchy BEFORE the reindex drops the file's Tier-2 edges.
            Map<String, String> oldNames = namesByMoniker(store.symbolsOf(fid));
            Map<String, Set<MonikerEdge>> oldHier = hierarchyByMoniker(oldNames.keySet());

            NodeDiff diff = guard.reindexOne(file);
            if (!diff.reindexed()) {
                continue; // unchanged / mtime-lie
            }

            // A file's bytes changed → the engine's cached cross-file view is stale. Shed it once,
            // before any re-resolution, so the changed file and its referrers resolve against disk.
            if (!engineRefreshed) {
                resolver.refreshEngine();
                engineRefreshed = true;
            }
            resolver.refreshSymbols(fid); // caches now match the reindexed Tier-1 symbols…
            resolver.reResolve(fid);      // …then re-resolve so the new hierarchy edges are in the graph
            resolver.refreshUsageOverlay(fid); // re-derive overlay (edited file's new use; tombstone clears)
            reResolved.add(fid);

            Map<String, String> newNames = namesByMoniker(store.symbolsOf(fid));
            Map<String, Set<MonikerEdge>> newHier = hierarchyByMoniker(newNames.keySet());

            changedMonikers.addAll(diff.changedMonikers());
            changedMonikers.addAll(hierarchyChanged(oldHier, newHier));
            collectNames(changedNames, oldNames, diff.removed());
            collectNames(changedNames, newNames, diff.added());
        }

        if (changedMonikers.isEmpty() && changedNames.isEmpty()) {
            return Set.of();
        }
        return resolver.invalidateReferrers(changedMonikers, changedNames, reResolved);
    }

    /** Monikers whose hierarchy-edge set differs between the captured and current graph. */
    private static Set<String> hierarchyChanged(Map<String, Set<MonikerEdge>> oldHier,
            Map<String, Set<MonikerEdge>> newHier) {
        Set<String> changed = new HashSet<>();
        Set<String> monikers = new HashSet<>(oldHier.keySet());
        monikers.addAll(newHier.keySet());
        for (String m : monikers) {
            if (!Objects.equals(oldHier.getOrDefault(m, Set.of()), newHier.getOrDefault(m, Set.of()))) {
                changed.add(m);
            }
        }
        return changed;
    }

    private Map<String, Set<MonikerEdge>> hierarchyByMoniker(Collection<String> monikers) {
        Map<String, Set<MonikerEdge>> out = new HashMap<>();
        for (String m : monikers) {
            out.put(m, resolver.hierarchyOut(m));
        }
        return out;
    }

    private static Map<String, String> namesByMoniker(List<Symbol> symbols) {
        Map<String, String> out = new HashMap<>();
        for (Symbol s : symbols) {
            out.put(s.moniker(), s.name());
        }
        return out;
    }

    /** Add the simple names of {@code monikers} (looked up in {@code names}) to {@code into}. */
    private static void collectNames(Set<String> into, Map<String, String> names, Set<String> monikers) {
        for (String m : monikers) {
            String name = names.get(m);
            if (name != null) {
                into.add(name);
            }
        }
    }
}
