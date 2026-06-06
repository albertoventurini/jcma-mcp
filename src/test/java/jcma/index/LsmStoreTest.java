package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P1 — the LSM store ({@link LsmStore}), production form of M0 {@code SpikeD.Store}.
 * Ports the Spike D oracle-driven round-trip (random add / modify-body / modify-api / delete edits
 * → <b>0 mismatches</b> vs {@link LsmOracle}), plus the cases this task adds: crash-log replay on
 * reopen, <b>query-identical pre/post compaction</b> (including trigram {@code search}), phantom
 * (dangling-edge) preservation, and a torn trailing log record being dropped rather than fatal.
 *
 * <p>P1 is a unit-level exercise of the store machinery on synthetic, value-distinct symbols/edges;
 * real parsing feeds it in P2. To keep occurrences exact across a compaction without implying an id
 * remap (occurrence enclosing-id handling is a P2 concern with real occurrences), test edges use
 * {@code enclosingSymbolId == -1}.
 */
class LsmStoreTest {

    // ------------------------------------------------------------------ synthetic edit generator

    /** A growing universe of monikers + which file declares each, driving random edits. */
    private static final class World {
        final Random rnd;
        int nextFile;
        int occLine; // a monotonically increasing line so every generated edge is value-distinct
        final List<String> monikers = new ArrayList<>();         // all monikers ever declared
        final java.util.Map<Integer, List<String>> fileSymbols = new java.util.HashMap<>();

        World(Random rnd, int firstFile) {
            this.rnd = rnd;
            this.nextFile = firstFile;
        }

        Symbol sym(String moniker, int fileId) {
            return new Symbol(moniker, SymbolKind.METHOD, 0, null, fileId,
                    new Range(1, 1, 1, 9), simpleName(moniker), null);
        }

        /** Simple name = the moniker's last path segment, so {@code search} has something to match. */
        static String simpleName(String moniker) {
            int slash = moniker.lastIndexOf('/');
            return slash < 0 ? moniker : moniker.substring(slash + 1);
        }

        MonikerEdge edge(String src, String dst) {
            EdgeType t = rnd.nextBoolean() ? EdgeType.CALLS : EdgeType.REFERENCES;
            Occurrence occ = new Occurrence(occLine % 7, new Range(occLine, 1, occLine, 5), -1,
                    t == EdgeType.CALLS ? Occurrence.Role.CALL : Occurrence.Role.READ);
            occLine++;
            return new MonikerEdge(src, dst, t, occ);
        }

        /** A brand-new file declaring {@code symCount} fresh monikers with {@code edgeCount} outgoing edges. */
        FileIndex newFile(int symCount, int edgeCount) {
            int f = nextFile++;
            List<Symbol> syms = new ArrayList<>();
            List<String> mine = new ArrayList<>();
            for (int i = 0; i < symCount; i++) {
                String m = "pkg/f" + f + "/sym" + i;
                mine.add(m);
                monikers.add(m);
                syms.add(sym(m, f));
            }
            fileSymbols.put(f, mine);
            return new FileIndex(f, syms, outgoing(mine, edgeCount));
        }

        /** Re-emit a file's outgoing edges only (a method-body edit): same symbols, fresh edges. */
        FileIndex modifyBody(int f, int edgeCount) {
            List<String> mine = fileSymbols.get(f);
            List<Symbol> syms = mine.stream().map(m -> sym(m, f)).collect(Collectors.toList());
            return new FileIndex(f, syms, outgoing(mine, edgeCount));
        }

        /** An API-surface edit: add a new symbol to the file, keep fresh edges. */
        FileIndex modifyApi(int f, int edgeCount) {
            List<String> mine = new ArrayList<>(fileSymbols.get(f));
            String added = "pkg/f" + f + "/api" + occLine;
            mine.add(added);
            monikers.add(added);
            fileSymbols.put(f, mine);
            List<Symbol> syms = mine.stream().map(m -> sym(m, f)).collect(Collectors.toList());
            return new FileIndex(f, syms, outgoing(mine, edgeCount));
        }

        FileIndex delete(int f) {
            List<String> mine = fileSymbols.remove(f);
            if (mine != null) {
                // keep monikers in the pool: their declaring file is gone but edges may still point at them
            }
            return FileIndex.deleted(f);
        }

        private List<MonikerEdge> outgoing(List<String> srcPool, int edgeCount) {
            List<MonikerEdge> es = new ArrayList<>();
            if (srcPool.isEmpty() || monikers.isEmpty()) {
                return es;
            }
            for (int i = 0; i < edgeCount; i++) {
                String src = srcPool.get(rnd.nextInt(srcPool.size()));
                String dst = monikers.get(rnd.nextInt(monikers.size()));
                es.add(edge(src, dst));
            }
            return es;
        }

        int randomLiveFile() {
            List<Integer> live = new ArrayList<>(fileSymbols.keySet());
            return live.isEmpty() ? -1 : live.get(rnd.nextInt(live.size()));
        }
    }

    /** Apply the same edit to both store and oracle. */
    private static void apply(LsmStore store, LsmOracle oracle, FileIndex c) throws IOException {
        store.applyEdit(c);
        oracle.applyFile(c);
    }

    /** Compare store vs oracle for a random sample of monikers; return the mismatch count. */
    private static int mismatches(LsmStore store, LsmOracle oracle, World w, int samples) {
        int bad = 0;
        for (int i = 0; i < samples; i++) {
            String m = w.monikers.get(w.rnd.nextInt(w.monikers.size()));
            if (store.contains(m) != oracle.contains(m)) {
                bad++;
            }
            if (!store.fwd(m).equals(oracle.fwd(m))) {
                bad++;
            }
            if (!store.rev(m).equals(oracle.rev(m))) {
                bad++;
            }
        }
        return bad;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void addModifyDeleteRoundTripMatchesOracle(@TempDir Path dir) throws IOException {
        Random rnd = new Random(42);
        World w = new World(rnd, 0);
        LsmOracle oracle = new LsmOracle();

        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            // Seed a base: ~40 files, then compact so later edits exercise base ∪ overlay − tombstones.
            for (int i = 0; i < 40; i++) {
                apply(store, oracle, w.newFile(8, 15));
            }
            store.compact();
            assertEquals(0, mismatches(store, oracle, w, 200), "after seed + compaction");

            int totalMismatches = 0;
            for (int it = 0; it < 200; it++) {
                int kind = rnd.nextInt(4);
                if (kind == 0) {
                    apply(store, oracle, w.newFile(6, 12));
                } else if (kind == 1) {
                    int f = w.randomLiveFile();
                    if (f >= 0) {
                        apply(store, oracle, w.modifyBody(f, 12));
                    }
                } else if (kind == 2) {
                    int f = w.randomLiveFile();
                    if (f >= 0) {
                        apply(store, oracle, w.modifyApi(f, 12));
                    }
                } else {
                    int f = w.randomLiveFile();
                    if (f >= 0) {
                        apply(store, oracle, w.delete(f));
                    }
                }
                totalMismatches += mismatches(store, oracle, w, 40);
                if (it % 50 == 49) {
                    store.compact(); // periodically fold the overlay; queries must stay correct
                    totalMismatches += mismatches(store, oracle, w, 100);
                }
            }
            assertEquals(0, totalMismatches, "0 mismatches across the edit batch (incl. compactions)");
        }
    }

    @Test
    void replayAfterReopenMatchesOracle(@TempDir Path dir) throws IOException {
        Random rnd = new Random(7);
        World w = new World(rnd, 0);
        LsmOracle oracle = new LsmOracle();

        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            for (int i = 0; i < 15; i++) {
                apply(store, oracle, w.newFile(6, 10));
            }
            store.compact();                       // base on disk
            for (int i = 0; i < 15; i++) {         // these live only in the overlay log
                apply(store, oracle, w.newFile(6, 10));
            }
            assertFalse(store.isCompacted(), "overlay should hold the post-compaction edits");
        }

        // Reopen: the base is mmap'd and the overlay log is replayed — no edit is lost.
        try (LsmStore reopened = LsmStore.open(dir, CompactionPolicy.manual())) {
            assertEquals(0, mismatches(reopened, oracle, w, 400), "reopen replays the overlay log");
        }
    }

    @Test
    void queryIdenticalAcrossCompaction(@TempDir Path dir) throws IOException {
        Random rnd = new Random(99);
        World w = new World(rnd, 0);
        LsmOracle oracle = new LsmOracle();

        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            for (int i = 0; i < 25; i++) {
                apply(store, oracle, w.newFile(8, 14));
            }
            // Snapshot fwd/rev/contains/search across the overlay-laden state...
            List<String> probes = w.monikers;
            List<Set<MonikerEdge>> fwdBefore = probes.stream().map(store::fwd).collect(Collectors.toList());
            List<Set<MonikerEdge>> revBefore = probes.stream().map(store::rev).collect(Collectors.toList());
            Set<String> searchBefore = monikersOf(store.search("sym"));

            assertFalse(store.isCompacted(), "precondition: there is an overlay to fold");
            store.compact();
            assertTrue(store.isCompacted(), "overlay is empty after compaction");

            // ...and assert it is byte-for-byte identical after folding into the fresh base.
            for (int i = 0; i < probes.size(); i++) {
                assertEquals(fwdBefore.get(i), store.fwd(probes.get(i)), "fwd identical across compaction");
                assertEquals(revBefore.get(i), store.rev(probes.get(i)), "rev identical across compaction");
            }
            assertEquals(searchBefore, monikersOf(store.search("sym")), "search identical across compaction");
            assertEquals(oracle.search("sym"), monikersOf(store.search("sym")), "search matches oracle");
            assertEquals(0, mismatches(store, oracle, w, 300), "post-compaction queries match oracle");
        }
    }

    @Test
    void phantomEdgePreservedAcrossCompaction(@TempDir Path dir) throws IOException {
        LsmOracle oracle = new LsmOracle();
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            // File A declares "a"; file B has an edge b -> a. Then A is deleted: a is now a dangling
            // (phantom) endpoint — declared nowhere, but B's reference must survive.
            Symbol a = new Symbol("pkg/a/a", SymbolKind.METHOD, 0, null, 1, new Range(1, 1, 1, 4), "a", null);
            Symbol b = new Symbol("pkg/b/b", SymbolKind.METHOD, 0, null, 2, new Range(1, 1, 1, 4), "b", null);
            MonikerEdge bToA = new MonikerEdge("pkg/b/b", "pkg/a/a", EdgeType.CALLS,
                    new Occurrence(2, new Range(3, 1, 3, 5), -1, Occurrence.Role.CALL));

            apply(store, oracle, new FileIndex(1, List.of(a), List.of()));
            apply(store, oracle, new FileIndex(2, List.of(b), List.of(bToA)));
            apply(store, oracle, FileIndex.deleted(1)); // a's declaring file is gone

            assertFalse(store.contains("pkg/a/a"), "a is no longer declared");
            assertEquals(Set.of(bToA), store.rev("pkg/a/a"), "the dangling edge into a survives the delete");

            store.compact(); // phantom node for "a" (fileId == -1) must be preserved
            assertFalse(store.contains("pkg/a/a"), "a stays undeclared after compaction (phantom)");
            assertEquals(Set.of(bToA), store.rev("pkg/a/a"), "dangling edge preserved across compaction");
            assertEquals(Set.of(bToA), store.fwd("pkg/b/b"), "b still references a");
            assertEquals(oracle.rev("pkg/a/a"), store.rev("pkg/a/a"));
        }
    }

    @Test
    void tornLogTailIsDroppedNotFatal(@TempDir Path dir) throws IOException {
        Random rnd = new Random(123);
        World w = new World(rnd, 0);
        LsmOracle oracle = new LsmOracle();

        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            for (int i = 0; i < 10; i++) {
                apply(store, oracle, w.newFile(5, 8)); // oracle tracks these N committed edits
            }
            // One more edit applied to the STORE only — its log record is the trailing one we corrupt.
            store.applyEdit(w.newFile(5, 8));
        }

        // Simulate a crash mid-write: truncate the last byte of the overlay log so its final,
        // length-framed + checksummed record is incomplete.
        Path log = dir.resolve(LsmStore.OVERLAY_LOG);
        try (FileChannel ch = FileChannel.open(log, StandardOpenOption.WRITE)) {
            ch.truncate(ch.size() - 1);
        }

        // Reopen must succeed, dropping the torn record and recovering the committed prefix.
        try (LsmStore reopened = LsmStore.open(dir, CompactionPolicy.manual())) {
            assertEquals(0, mismatches(reopened, oracle, w, 300),
                    "torn trailing record dropped; committed edits recovered");
        }
    }

    @Test
    void autoCompactionFiresPerPolicy(@TempDir Path dir) throws IOException {
        Random rnd = new Random(5);
        World w = new World(rnd, 0);
        LsmOracle oracle = new LsmOracle();

        // A policy that compacts as soon as the overlay holds >= 5 files, regardless of base size.
        CompactionPolicy everyFiveFiles = (overlayBytes, baseBytes, overlayFiles) -> overlayFiles >= 5;
        try (LsmStore store = LsmStore.open(dir, everyFiveFiles)) {
            for (int i = 0; i < 5; i++) {
                apply(store, oracle, w.newFile(6, 10));
            }
            assertTrue(store.isCompacted(), "auto-compaction should have folded the overlay at the 5th file");
            assertEquals(0, mismatches(store, oracle, w, 200), "auto-compacted store matches oracle");
        }
    }

    private static Set<String> monikersOf(List<Symbol> symbols) {
        return symbols.stream().map(Symbol::moniker).collect(Collectors.toCollection(HashSet::new));
    }
}
