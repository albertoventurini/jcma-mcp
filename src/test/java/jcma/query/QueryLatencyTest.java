package jcma.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.session.AnalysisSession;
import jcma.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-12 (red-first) — the §Targets latency gate, ported from M0 {@code SpikeB}'s percentile harness:
 * <b>warm {@code find_references} p95 &lt; 200 ms</b> with the edge cache, served through
 * {@link QueryService} over the pinned commons-lang corpus. M0 measured a cold worst of 262 ms
 * (re-resolving every candidate); once warm the answer is a pure graph walk, so repeats must sit far
 * inside budget. Folds into M1-RESULTS. Skips (assumes) if the corpus is absent.
 */
class QueryLatencyTest {

    private static final Path CORPUS = Path.of("milestones/m0-spike/corpus/commons-lang");
    private static final String HOT_MONIKER = "org/apache/commons/lang3/SystemProperties#getProperty(String).";
    private static final Duration GENEROUS = Duration.ofSeconds(30);

    @TempDir
    static Path indexDir;

    @BeforeAll
    static void indexCorpusOnce() {
        if (Files.isDirectory(CORPUS)) {
            IndexFixture.buildWithCachedClasspath(CORPUS, indexDir);
        }
    }

    @Test
    void warmFindReferencesP95UnderBudget() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS), "pinned commons-lang corpus present");

        try (QueryService svc = new QueryService(
                AnalysisSession.open(indexDir, Workspace.discover(CORPUS, indexDir), Metrics.create()))) {
            Symbol target = svc.declarations("getProperty", GENEROUS).stream()
                    .filter(s -> HOT_MONIKER.equals(s.moniker()))
                    .findFirst().orElseThrow(() -> new AssertionError("SystemProperties.getProperty(String) not indexed"));

            svc.findReferences(target, GENEROUS); // warm the edge cache (one-time resolve)

            List<Double> ms = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                long t0 = System.nanoTime();
                svc.findReferences(target, GENEROUS);
                ms.add((System.nanoTime() - t0) / 1e6);
            }
            Collections.sort(ms);
            double p95 = pct(ms, 95);
            System.out.printf("[latency] warm find_references over commons-lang: "
                    + "p50 %.2f · p90 %.2f · p95 %.2f · p99 %.2f · max %.2f ms (n=%d)%n",
                    pct(ms, 50), pct(ms, 90), p95, pct(ms, 99), ms.get(ms.size() - 1), ms.size());

            assertTrue(p95 < 200.0, "warm find_references p95 = " + p95 + " ms (budget 200 ms)");
        }
    }

    /** SpikeB's nearest-rank percentile (ceil index), ported verbatim. */
    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }
}
