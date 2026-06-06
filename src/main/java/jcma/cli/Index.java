package jcma.cli;

import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.obs.Metrics;
import jcma.obs.Timer;
import jcma.workspace.Reconciler;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jcma index <repo> [<indexDir>]} (task-06 P4) — cold full index of a repo: discover source
 * roots, parse-only extract across virtual threads, persist through the LSM store, compact, and
 * report throughput. {@code indexDir} defaults to {@code <repo>/.jcma}. The same {@code Main.run}
 * dispatch runs under native-image.
 */
final class Index {

    private Index() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || args.length > 3) {
            err.println("jcma: usage: jcma index <repo> [indexDir]");
            return 2;
        }
        Path repo = Path.of(args[1]);
        if (!Files.isDirectory(repo)) {
            err.println("jcma: not a directory: " + repo);
            return 1;
        }
        Path indexDir = args.length == 3 ? Path.of(args[2]) : repo.resolve(".jcma");

        // Tagged source roots (main + test) from the workspace; fall back to the repo itself as MAIN.
        List<SourceRoot> roots = new ArrayList<>();
        for (SourceRoot root : Workspace.discoverSourceSets(repo)) {
            if (Files.isDirectory(root.dir())) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            roots.add(new SourceRoot(repo, SourceSet.MAIN));
        }

        try {
            Metrics metrics = Metrics.create();
            Reconciler.ReindexStats stats;
            try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics)) {
                stats = new Reconciler(new Indexer(metrics), metrics).reindex(repo, roots, store, indexDir);
            }
            out.printf("%d reparsed (%d new, %d changed, %d deleted, %d unchanged), "
                            + "%d symbols, %,d LOC in %.2fs → %s%n",
                    stats.reparsed(), stats.added(), stats.changed(), stats.deleted(), stats.unchanged(),
                    stats.symbols(), stats.loc(), stats.seconds(), indexDir);
            Timer.Snapshot compaction = metrics.timerValues()
                    .getOrDefault("compaction", new Timer.Snapshot(0, 0, 0));
            out.printf("compaction: %.1f ms%n", compaction.totalNanos() / 1e6);
            return 0;
        } catch (Exception e) {
            err.println("jcma: index failed: " + e.getMessage());
            return 1;
        }
    }
}
