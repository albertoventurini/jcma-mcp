package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.IndexFixture;
import jcma.engine.JavaParserEngine;
import jcma.index.CompactionPolicy;
import jcma.index.LsmStore;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.UsageNameIndex;
import jcma.index.UsageNameIndexer;
import jcma.obs.Metrics;
import jcma.workspace.FileTable;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direction A (red-first) — the gated serial/parallel resolve for {@code find_references}. The single
 * per-file resolve logic runs two ways (serial below a candidate-count threshold, parallel above it)
 * and the two paths must be <b>equivalent by construction</b>: identical confirmed groups + unconfirmed
 * tail. The {@code parallel-refs} fixture references one method ({@code Target.ping()}) from 12 sibling
 * files plus one unconfirmable site ({@code Mystery.poke}), enough candidate files to exercise K-way
 * sharding; the rare method {@code Lonely.solo()} (one candidate file) pins the serial side of the gate.
 *
 * <p>The static {@code JavaParserFacade} {@code WeakHashMap} is the one silent-wrong hazard the
 * safe-degrade net misses (a corrupted memo returns a wrong answer without throwing), so {@link
 * #parallelResolveStress} re-runs the parallel query many times and asserts the answer is identical
 * every time — a data race would surface as a flaky diff, not an exception.
 */
class ParallelResolveTest {

    private static final Path PARALLEL_REFS =
            Path.of("src/test/resources/fixtures/resolve/parallel-refs");
    private static final String PING = "app/Target#ping().";
    private static final String SOLO = "app/Lonely#solo().";

    /** Forced-serial (threshold above the candidate count) and forced-parallel (threshold 0) agree exactly. */
    @Test
    void parallelMatchesSerial(@TempDir Path tmp) throws Exception {
        Path serialIdx = tmp.resolve("serial");
        Path parallelIdx = tmp.resolve("parallel");
        IndexFixture.build(PARALLEL_REFS, serialIdx);
        IndexFixture.build(PARALLEL_REFS, parallelIdx);

        References serial;
        try (EdgeResolver r = resolverOver(serialIdx)) {
            r.setParallelThreshold(Integer.MAX_VALUE); // never parallel
            serial = r.findReferences(decl(r, "ping", PING));
            assertEquals(1, r.metrics().counter("resolve.serial").sum(), "the query took the serial path");
            assertEquals(0, r.metrics().counter("resolve.parallel").sum());
        }

        References parallel;
        try (EdgeResolver r = resolverOver(parallelIdx)) {
            r.setParallelThreshold(0);   // always parallel
            r.setResolveThreads(4);
            parallel = r.findReferences(decl(r, "ping", PING));
            assertEquals(1, r.metrics().counter("resolve.parallel").sum(), "the query took the parallel path");
            assertEquals(0, r.metrics().counter("resolve.serial").sum());
        }

        assertEquals(12, serial.totalRefs(), "12 callers each call Target.ping() once");
        assertEquals(1, serial.unconfirmed().size(), "Mystery.poke's u.ping() is the unconfirmed tail");
        assertEquals(confirmedKeys(serial), confirmedKeys(parallel), "confirmed refs identical across paths");
        assertEquals(unconfirmedKeys(serial), unconfirmedKeys(parallel), "unconfirmed tail identical across paths");
    }

    /** Parallel result is identical across many fresh runs — flushes facade-WeakHashMap races / silent-wrong. */
    @Test
    void parallelResolveStress(@TempDir Path tmp) throws Exception {
        Path idx = tmp.resolve("idx");
        IndexFixture.build(PARALLEL_REFS, idx);

        Set<String> reference = null;
        for (int iter = 0; iter < 30; iter++) {
            try (EdgeResolver r = resolverOver(idx)) {
                r.setParallelThreshold(0);
                r.setResolveThreads(8);   // oversubscribe to maximise interleaving
                References refs = r.findReferences(decl(r, "ping", PING));
                Set<String> keys = new HashSet<>(confirmedKeys(refs));
                keys.add("UNCONFIRMED=" + unconfirmedKeys(refs));
                if (reference == null) {
                    reference = keys;
                    assertEquals(12, refs.totalRefs(), "baseline: 12 confirmed refs");
                } else {
                    assertEquals(reference, keys, "iteration " + iter + " diverged — a parallel race");
                }
            }
        }
    }

    /** The gate selects the path by candidate-file count: rare name → serial, common name → parallel. */
    @Test
    void thresholdSelectsPath(@TempDir Path tmp) throws Exception {
        Path idx = tmp.resolve("idx");
        IndexFixture.build(PARALLEL_REFS, idx);

        try (EdgeResolver r = resolverOver(idx)) {
            r.setParallelThreshold(5);   // ping has 13 candidates (parallel), solo has 1 (serial)
            r.setResolveThreads(4);

            r.findReferences(decl(r, "solo", SOLO));
            assertEquals(1, r.metrics().counter("resolve.serial").sum(), "1 candidate < 5 → serial");
            assertEquals(0, r.metrics().counter("resolve.parallel").sum());

            r.findReferences(decl(r, "ping", PING));
            assertEquals(1, r.metrics().counter("resolve.serial").sum(), "serial count unchanged");
            assertEquals(1, r.metrics().counter("resolve.parallel").sum(), "13 candidates ≥ 5 → parallel");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static EdgeResolver resolverOver(Path indexDir) throws IOException {
        Metrics metrics = Metrics.create();
        LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics);
        Path usagePath = indexDir.resolve(UsageNameIndexer.FILE_NAME);
        UsageNameIndex usageIndex = Files.exists(usagePath) ? UsageNameIndex.load(usagePath) : null;
        FileTable fileTable = FileTable.load(indexDir);
        JavaParserEngine engine = new JavaParserEngine(Workspace.ofSourceRoot(PARALLEL_REFS));
        Path repoRoot = Workspace.ofSourceRoot(PARALLEL_REFS).projectRoot().toAbsolutePath().normalize();
        return EdgeResolver.over(store, usageIndex, fileTable, engine, repoRoot, metrics);
    }

    private static Symbol decl(EdgeResolver resolver, String name, String moniker) {
        return resolver.declarations(name).stream()
                .filter(s -> moniker.equals(s.moniker()))
                .findFirst().orElseThrow(() -> new AssertionError(moniker + " not indexed"));
    }

    /** A confirmed reference keyed by enclosing moniker + use-site position — order-independent. */
    private static Set<String> confirmedKeys(References refs) {
        Set<String> keys = new HashSet<>();
        for (ReferenceGroup g : refs.groups()) {
            for (Ref ref : g.refs()) {
                Range r = ref.range();
                keys.add(g.enclosingMoniker() + "@" + ref.fileId() + ":"
                        + r.startLine() + ":" + r.startCol());
            }
        }
        return keys;
    }

    /** An unconfirmed-tail entry keyed by file + position + cause — order-independent. */
    private static Set<String> unconfirmedKeys(References refs) {
        Set<String> keys = new HashSet<>();
        for (UnconfirmedRef u : refs.unconfirmed()) {
            Range r = u.range();
            keys.add(u.fileId() + ":" + r.startLine() + ":" + r.startCol() + ":" + u.cause());
        }
        return keys;
    }
}
