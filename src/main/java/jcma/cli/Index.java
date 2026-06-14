package jcma.cli;

import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.obs.Metrics;
import jcma.obs.Timer;
import jcma.workspace.IndexLayout;
import jcma.workspace.IndexLock;
import jcma.workspace.IndexLockedException;
import jcma.workspace.Reconciler;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jcma index [<indexDir>]} (task-06 P4) — cold full index of the repo inferred from the
 * working directory: discover source roots, parse-only extract across virtual threads, persist
 * through the LSM store, compact, and report throughput. {@code indexDir} defaults to a per-repo dir
 * under the user cache ({@link IndexLayout#defaultIndexDir}). The same {@code Main.run} dispatch runs
 * under native-image.
 */
final class Index {

    private Index() {}

    static int run(Path cwd, String[] args, PrintStream out, PrintStream err) {
        if (args.length > 2) {
            err.println("jcma: usage: jcma index [indexDir]");
            return 2;
        }
        Path repo = Workspace.projectRoot(cwd);
        Path indexDir = args.length == 2 ? Path.of(args[1]) : IndexLayout.defaultIndexDir(repo);

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

        // Single-writer: indexing mutates the store; fail fast if a live serve/another writer owns it.
        try (IndexLock ignored = IndexLock.acquire(indexDir)) {
            Metrics metrics = Metrics.create();
            // Resolve the build-tool classpath here (the slow mvn/gradle subprocess) and persist it to
            // the index dir, so later repl/serve/query sessions read a file instead of re-spawning it.
            // A re-index re-resolves and overwrites, tying classpath freshness to the index lifecycle.
            long cpStart = System.nanoTime();
            List<Path> jars = Workspace.persistClasspath(repo, indexDir);
            metrics.timer("index.classpath_resolve").record(System.nanoTime() - cpStart);
            out.printf("classpath: resolved %d jar(s) → %s%n", jars.size(), IndexLayout.classpathCache(indexDir));
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
        } catch (IndexLockedException e) {
            err.println("jcma: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("jcma: index failed: " + e.getMessage());
            return 1;
        }
    }
}
