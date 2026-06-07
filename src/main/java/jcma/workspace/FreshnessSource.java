package jcma.workspace;

import java.nio.file.Path;
import java.util.Set;

/**
 * The swappable <b>change-detection producer</b> seam (PRD §5.1 freshness; task-09 design frame).
 * MCP is request/response with no {@code didChange} push channel, so <em>how we learn a file changed</em>
 * is a separate concern from <em>making a read fresh</em>. The correctness floor is the on-access
 * backstop in {@link FreshnessGuard} (validate-on-read of the files a query touches); a
 * {@code FreshnessSource} is an <em>optional accelerator</em> on top of it — an in-memory dirty set,
 * a {@code .jcma/dirty.log} journal, a filesystem watcher, or an agent edit-hook — that proactively
 * reports paths it has seen change, so the guard can refresh them before they are read.
 *
 * <p>The contract is the stable thing; the producer is the swappable thing. M1 ships {@link #none()}
 * (no producer — the on-access backstop does all the work); any detector can be dropped in later as a
 * new implementation feeding the same {@link #drainChanged()} method, with no change to the query core.
 */
public interface FreshnessSource {

    /**
     * The absolute paths this producer has observed change since the previous call. <b>Draining</b>:
     * each path is returned at most once (the implementation clears its pending set), so the guard can
     * reindex them exactly once. {@link #none()} always returns empty.
     */
    Set<Path> drainChanged();

    /** The M1 default: no proactive producer — {@link FreshnessGuard}'s on-access backstop covers freshness. */
    static FreshnessSource none() {
        return Set::of;
    }
}
