package jcma.workspace;

import jcma.engine.UsageSite;
import jcma.index.FileIndex;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.index.UsageNameIndexer;
import jcma.obs.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Filesystem-driven freshness reconciliation (PRD §5.1) — the warm-reopen pipeline that makes a
 * reopen ≠ a full scan. It diffs the {@link FileTable} against a metadata-only walk of the source
 * roots, classifying each file as <b>unchanged</b> (size+mtime match → skip), <b>suspect</b>
 * (size/mtime differ → hash to confirm: a matching hash is an mtime-lie, skip; a differing hash is a
 * real change → re-parse), <b>new</b> (untracked → parse + add), or <b>deleted</b> (tracked but gone
 * → tombstone). Only new+changed files are re-parsed (through {@link Indexer}); deletions tombstone
 * their id; the store is compacted and the table rewritten iff anything changed. The all-match fast
 * path does no parse, no store edit — base is already mmap'd by {@link LsmStore#open}.
 *
 * <p>A cold full index is just the empty-table case: every file is new.
 */
public final class Reconciler {

    /**
     * The outcome of one reconcile pass. {@code total} is the number of files currently on disk under
     * the roots; the four buckets partition the previously-known + current sets.
     */
    public record ReindexStats(
            int total, int unchanged, int added, int changed, int deleted,
            int symbols, long loc, double seconds) {

        /** Files actually re-parsed this pass (new + changed); the headline freshness number. */
        public int reparsed() {
            return added + changed;
        }

        /** True if nothing was re-parsed or tombstoned (the fast path). */
        public boolean warm() {
            return added == 0 && changed == 0 && deleted == 0;
        }
    }

    /** A file currently on disk: its absolute path and the source-set of the root it was found under. */
    private record Current(Path absolute, SourceSet set) {}

    private final Indexer indexer;
    private final Metrics metrics;

    public Reconciler(Indexer indexer, Metrics metrics) {
        this.indexer = indexer;
        this.metrics = metrics;
    }

    /**
     * Reconcile the index at {@code indexDir} against the current contents of {@code roots} (paths
     * relativised to {@code repoRoot} in the table), applying only the necessary edits through
     * {@code store} and rewriting the {@link FileTable}. Returns the pass's {@link ReindexStats}.
     */
    public ReindexStats reindex(Path repoRoot, List<SourceRoot> roots, LsmStore store, Path indexDir)
            throws IOException {
        long t0 = System.nanoTime();
        Path root = repoRoot.toAbsolutePath().normalize();
        FileTable table = FileTable.load(indexDir);

        // 1. Walk the roots (metadata only), keyed by repo-relative path, sorted for deterministic
        //    cold-scan ids. First root wins if a file is reachable from more than one.
        TreeMap<Path, Current> current = new TreeMap<>();
        for (SourceRoot sr : roots) {
            if (!Files.isDirectory(sr.dir())) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(sr.dir())) {
                List<Path> javaFiles = walk
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .toList();
                for (Path p : javaFiles) {
                    Path abs = p.toAbsolutePath().normalize();
                    current.putIfAbsent(root.relativize(abs), new Current(abs, sr.set()));
                }
            }
        }

        // 2. Diff vs. the table → unchanged / new / changed (mtime-lies count as unchanged).
        List<Indexer.ParseRequest> toParse = new ArrayList<>();
        int added = 0;
        int changed = 0;
        int unchanged = 0;
        boolean tableDirty = false;
        for (Map.Entry<Path, Current> e : current.entrySet()) {
            Path rel = e.getKey();
            Current c = e.getValue();
            FileTable.Entry prev = table.get(rel);
            if (prev == null) {                                   // NEW
                int id = table.allocateId();
                table.put(rel, id, Fingerprint.of(c.absolute()), c.set());
                toParse.add(new Indexer.ParseRequest(id, c.absolute(), c.set()));
                added++;
                tableDirty = true;
            } else if (statMatches(c.absolute(), prev.fingerprint())) {
                unchanged++;                                      // fast path
            } else {                                              // SUSPECT → confirm by hash
                Fingerprint fp = Fingerprint.of(c.absolute());
                table.put(rel, prev.fileId(), fp, c.set());       // refresh size/mtime either way
                tableDirty = true;
                if (fp.contentHash() == prev.fingerprint().contentHash()) {
                    unchanged++;                                  // mtime-lie
                } else {
                    toParse.add(new Indexer.ParseRequest(prev.fileId(), c.absolute(), c.set()));
                    changed++;
                }
            }
        }

        // 3. Deletions: tracked paths no longer on disk → tombstone their id, drop from the table.
        int deleted = 0;
        for (Path rel : table.paths()) {
            if (!current.containsKey(rel)) {
                store.applyEdit(FileIndex.deleted(table.get(rel).fileId()));
                table.remove(rel);
                deleted++;
                tableDirty = true;
            }
        }

        // 4. Re-parse the new+changed set in parallel and apply each through the store.
        int symbols = 0;
        long loc = 0;
        Map<Integer, List<UsageSite>> usagesByFile = Map.of();
        if (!toParse.isEmpty()) {
            Indexer.ParseResult parsed = indexer.parseAll(toParse);
            loc = parsed.loc();
            usagesByFile = parsed.usagesByFile();
            for (FileIndex fi : parsed.indices()) {
                if (fi != null) {
                    store.applyEdit(fi);
                    symbols += fi.symbols().size();
                }
            }
        }

        // 5. Rebuild the usage-name index, then fold edits into a fresh base. Both only run if
        //    something changed; the table is persisted iff it changed.
        if (added + changed + deleted > 0) {
            // Rebuild the usage-name index (find_references prune) from the current file set. A full
            // rebuild is correct; task-11 makes it incremental. Ids come from the just-updated table.
            Map<Integer, Path> filesById = new HashMap<>();
            for (Map.Entry<Path, Current> e : current.entrySet()) {
                FileTable.Entry te = table.get(e.getKey());
                if (te != null) {
                    filesById.put(te.fileId(), e.getValue().absolute());
                }
            }
            // If this pass re-parsed every current file (the cold index: empty table → all new), the
            // use-sites are already in hand — build the index from them with no second parse. Otherwise
            // (incremental: some unchanged files were not re-parsed) fall back to the re-parse build.
            if (usagesByFile.keySet().containsAll(filesById.keySet())) {
                UsageNameIndexer.buildFrom(indexDir, usagesByFile);
            } else {
                UsageNameIndexer.build(indexDir, filesById);
            }
            usagesByFile = null; // release before compaction: don't stack usages + compaction buffers
            store.compact();
        }
        if (tableDirty) {
            table.save(indexDir);
        }

        double seconds = (System.nanoTime() - t0) / 1e9;
        metrics.counter("reindex.added").add(added);
        metrics.counter("reindex.changed").add(changed);
        metrics.counter("reindex.deleted").add(deleted);
        metrics.counter("reindex.unchanged").add(unchanged);
        metrics.timer("reindex").record(System.nanoTime() - t0);
        return new ReindexStats(current.size(), unchanged, added, changed, deleted, symbols, loc, seconds);
    }

    /** True if {@code file}'s current size+mtime match {@code fp} (the cheap, no-hash fast path). */
    private static boolean statMatches(Path file, Fingerprint fp) throws IOException {
        return Files.size(file) == fp.size()
                && Files.getLastModifiedTime(file).toMillis() == fp.mtime();
    }
}
