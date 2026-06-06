package jcma.cli;

import jcma.index.CompactionPolicy;
import jcma.index.Csr;
import jcma.index.LsmStore;
import jcma.index.SymbolStore;
import jcma.index.TrigramIndex;
import jcma.obs.Metrics;
import jcma.obs.Timer;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code jcma stats <indexDir>} — the observability surface (PRD §11). Reports the live on-disk
 * state of an index: base segment sizes, the overlay log size + file count, and the overlay/base
 * ratio the compaction policy reasons about. Opening the store also exercises the reopen-replay
 * metric, which is reported too. A pure reader; the same {@code Main.run} dispatch runs under native.
 */
final class Stats {

    private Stats() {}

    /** Dispatch {@code stats} args; return the process exit code (0 ok, 1 failure, 2 usage). */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("jcma: usage: jcma stats <indexDir>");
            return 2;
        }
        Path dir = Path.of(args[1]);
        Path sym = dir.resolve(SymbolStore.FILE_NAME);
        Path log = dir.resolve(LsmStore.OVERLAY_LOG);
        if (!Files.exists(sym) && !Files.exists(log)) {
            err.println("jcma: no index at " + dir);
            return 1;
        }
        try {
            long symBytes = sizeOf(sym);
            long edgeBytes = sizeOf(dir.resolve(Csr.FILE_NAME));
            long triBytes = sizeOf(dir.resolve(TrigramIndex.FILE_NAME));
            long baseBytes = symBytes + edgeBytes + triBytes;
            long logBytes = sizeOf(log);

            Metrics metrics = Metrics.create();
            int overlayFiles;
            try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual(), metrics)) {
                overlayFiles = store.overlayFileCount();
            }
            Timer.Snapshot replay = metrics.timerValues()
                    .getOrDefault("replay", new Timer.Snapshot(0, 0, 0));

            out.println("index: " + dir);
            out.printf("base:        %,d bytes (symbols %,d, edges %,d, trigrams %,d)%n",
                    baseBytes, symBytes, edgeBytes, triBytes);
            out.printf("overlay log: %,d bytes (%d file(s) pending)%n", logBytes, overlayFiles);
            out.printf("overlay/base ratio: %.3f%n", baseBytes == 0 ? 0.0 : (double) logBytes / baseBytes);
            out.printf("reopen replay: %d record(s) in %.2f ms%n",
                    metrics.counter("replay.records").sum(), replay.totalNanos() / 1e6);
            return 0;
        } catch (Exception e) {
            err.println("jcma: stats failed: " + e.getMessage());
            return 1;
        }
    }

    private static long sizeOf(Path p) throws java.io.IOException {
        return Files.exists(p) ? Files.size(p) : 0L;
    }
}
