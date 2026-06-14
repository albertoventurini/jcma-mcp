package jcma;

import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.obs.Metrics;
import jcma.workspace.IndexLayout;
import jcma.workspace.Reconciler;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Test harness for building a persisted index of an <i>exact</i> directory into an explicit
 * {@code indexDir} — the API path {@code jcma index} uses, minus the CLI's working-dir→project-root
 * walk-up. Tests that only need an index of an in-tree fixture (e.g. {@code fixtures/resolve/refs},
 * which is not itself a project root) build it here rather than through {@code Main.run("index", …)},
 * whose {@code -C} walk-up would escape the fixture and index jcma itself.
 */
public final class IndexFixture {

    private IndexFixture() {}

    /** Cold/warm full index of {@code repo} into {@code indexDir}; mirrors {@code Index.run}'s core. */
    public static void build(Path repo, Path indexDir) {
        List<SourceRoot> roots = new ArrayList<>();
        for (SourceRoot root : Workspace.discoverSourceSets(repo)) {
            if (Files.isDirectory(root.dir())) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            roots.add(new SourceRoot(repo, SourceSet.MAIN));
        }
        Metrics metrics = Metrics.noop();
        try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics)) {
            new Reconciler(new Indexer(metrics), metrics).reindex(repo, roots, store, indexDir);
        } catch (Exception e) {
            throw new RuntimeException("IndexFixture.build failed for " + repo, e);
        }
    }

    /**
     * As {@link #build}, plus seed the index-dir classpath cache ({@link IndexLayout#classpathCache})
     * from {@code repo}'s committed {@code cp.txt} when present — the M0 corpora ship one. This mirrors
     * what {@code jcma index} does (resolve + persist the classpath once, M2 task-09) without a live
     * {@code mvn}/{@code gradle} subprocess, so a cache-aware {@code Workspace.discover(repo, indexDir)}
     * resolves the corpus's dependencies offline and deterministically.
     */
    public static void buildWithCachedClasspath(Path repo, Path indexDir) {
        build(repo, indexDir);
        Path committedCp = repo.resolve("cp.txt");
        if (Files.isRegularFile(committedCp)) {
            try {
                Files.createDirectories(indexDir);
                Files.copy(committedCp, IndexLayout.classpathCache(indexDir),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("IndexFixture: seeding classpath cache failed for " + repo, e);
            }
        }
    }
}
