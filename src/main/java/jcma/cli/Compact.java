package jcma.cli;

import jcma.index.CompactionPolicy;
import jcma.index.LsmStore;
import jcma.index.SymbolStore;
import jcma.obs.Metrics;
import jcma.obs.Timer;
import jcma.workspace.IndexLock;
import jcma.workspace.IndexLockedException;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code jcma compact <indexDir>} (task-06 P4) — force a compaction: fold the overlay into a fresh
 * base (all three segments) and report how many files were pending + the timing.
 */
final class Compact {

    private Compact() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("jcma: usage: jcma compact <indexDir>");
            return 2;
        }
        Path indexDir = Path.of(args[1]);
        if (!Files.exists(indexDir.resolve(SymbolStore.FILE_NAME))
                && !Files.exists(indexDir.resolve(LsmStore.OVERLAY_LOG))) {
            err.println("jcma: no index at " + indexDir);
            return 1;
        }
        // Single-writer: compaction rewrites the base segments + clears the log; fail fast if held.
        try (IndexLock ignored = IndexLock.acquire(indexDir)) {
            Metrics metrics = Metrics.create();
            int pending;
            try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics)) {
                pending = store.overlayFileCount();
                store.compact();
            }
            Timer.Snapshot compaction = metrics.timerValues()
                    .getOrDefault("compaction", new Timer.Snapshot(0, 0, 0));
            out.printf("compacted %d pending file(s) into the base in %.1f ms%n",
                    pending, compaction.totalNanos() / 1e6);
            return 0;
        } catch (IndexLockedException e) {
            err.println("jcma: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("jcma: compact failed: " + e.getMessage());
            return 1;
        }
    }
}
