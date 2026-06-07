package jcma.workspace;

import jcma.index.FileIndex;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.obs.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-session freshness: the <b>on-access backstop</b> that keeps the index consistent with disk while
 * a session runs (PRD §5.1; task-09). MCP gives us no change-notification channel, so the correctness
 * floor is <em>validate-on-read</em> — before a query serves an answer, the file(s) it actually reads
 * are stat/hash-checked against the {@link FileTable} and re-parsed if stale. This avoids both failure
 * modes the design rejected: no {@code O(tree)} walk on the common (nothing-changed) query, and no
 * freshness window for a file the query actually reads.
 *
 * <p>The guard owns the session's in-memory {@link FileTable} (so a check is a map lookup, not a disk
 * reload) plus the open {@link LsmStore} and the {@link Indexer}. Its core primitive is
 * {@link #reindexOne(Path)} — a per-file structural reconcile, the Tier-1 "re-parse the one file, swap
 * its slice" PRD §5.1 calls for. Cross-file find-references completeness (a <em>new</em> referrer the
 * query didn't already know to read) is deliberately out of scope here — that is task-11's
 * trigram-pruned candidate set + validate-on-read. Proactive low-latency detection at scale is a later
 * {@link FreshnessSource} producer, drained here but empty in M1.
 *
 */
public final class FreshnessGuard {

    private final Path repoRoot;
    private final Path indexDir;
    private final FileTable table;
    private final LsmStore store;
    private final Indexer indexer;
    private final FreshnessSource source;
    private final Metrics metrics;

    public FreshnessGuard(Path repoRoot, Path indexDir, FileTable table, LsmStore store,
            Indexer indexer, FreshnessSource source, Metrics metrics) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.indexDir = indexDir;
        this.table = table;
        this.store = store;
        this.indexer = indexer;
        this.source = source;
        this.metrics = metrics;
    }

    /**
     * Open a guard over {@code indexDir}, loading its {@link FileTable} into memory for the session.
     * The {@code store} must already be open against the same index.
     */
    public static FreshnessGuard open(Path repoRoot, Path indexDir, LsmStore store, Indexer indexer,
            FreshnessSource source, Metrics metrics) throws IOException {
        return new FreshnessGuard(repoRoot, indexDir, FileTable.load(indexDir), store, indexer, source, metrics);
    }

    /**
     * The on-access backstop: make {@code file} (a file a query is about to read) fresh before the read
     * returns, first draining any proactive {@link FreshnessSource} (empty in M1). Returns {@code true}
     * if anything was reconciled (re-parsed or tombstoned).
     */
    public boolean ensureFresh(Path file) throws IOException {
        boolean changed = false;
        for (Path dirty : source.drainChanged()) {
            changed |= reindexOne(dirty);
        }
        changed |= reindexOne(file);
        return changed;
    }

    /**
     * Per-file structural reconcile of {@code file}: look up its {@link FileTable} row, then stat/hash it
     * and swap its slice through the store as needed. The branches mirror {@link Reconciler}'s NEW/SUSPECT
     * logic, scoped to one file:
     * <ul>
     *   <li><b>untracked</b> (no row) → no-op; cross-file discovery of a brand-new referrer is task-11;</li>
     *   <li><b>missing</b> (row exists, file gone) → tombstone its id and drop the row;</li>
     *   <li><b>size+mtime match</b> → fast-path no-op, no hash, no disk write;</li>
     *   <li><b>mtime-lie</b> (stat differs, hash equal) → refresh the row's stat so we don't rehash again;
     *       no store edit;</li>
     *   <li><b>changed</b> → re-parse with the row's tag, apply through the store, refresh the row.</li>
     * </ul>
     * The {@link FileTable} is persisted only when a row actually changed (so the common nothing-changed
     * query writes nothing). Compaction is left to the {@link LsmStore}'s {@code CompactionPolicy} —
     * {@code applyEdit} triggers it; this never compacts per edit. Returns {@code true} iff it applied a
     * store edit (re-parse or tombstone).
     */
    public boolean reindexOne(Path file) throws IOException {
        Path abs = file.isAbsolute() ? file.normalize() : repoRoot.resolve(file).normalize();
        Path rel = repoRoot.relativize(abs);
        FileTable.Entry row = table.get(rel);
        if (row == null) {
            return false; // untracked → warm-reopen reconcile / task-11 territory
        }
        metrics.counter("freshness.checked").add(1);

        if (!Files.exists(abs)) {                                 // MISSING → tombstone
            store.applyEdit(FileIndex.deleted(row.fileId()));
            table.remove(rel);
            table.save(indexDir);
            metrics.counter("freshness.tombstoned").add(1);
            return true;
        }
        if (statMatches(abs, row.fingerprint())) {                // fast path: no change
            return false;
        }
        Fingerprint fp = Fingerprint.of(abs);
        if (fp.contentHash() == row.fingerprint().contentHash()) { // SUSPECT → mtime-lie
            table.put(rel, row.fileId(), fp, row.sourceSet());     // refresh stat; avoid rehashing next time
            table.save(indexDir);
            metrics.counter("freshness.mtimeLie").add(1);
            return false;
        }
        FileIndex fi = indexer.indexFile(row.fileId(), abs, row.sourceSet()); // CHANGED → re-parse
        store.applyEdit(fi);
        table.put(rel, row.fileId(), fp, row.sourceSet());
        table.save(indexDir);
        metrics.counter("freshness.reindexed").add(1);
        return true;
    }

    /** True if {@code file}'s current size+mtime match {@code fp} (the cheap, no-hash fast path). */
    private static boolean statMatches(Path file, Fingerprint fp) throws IOException {
        return Files.size(file) == fp.size()
                && Files.getLastModifiedTime(file).toMillis() == fp.mtime();
    }
}
