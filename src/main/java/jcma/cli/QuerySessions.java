package jcma.cli;

import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.session.AnalysisSession;
import jcma.workspace.FreshnessSource;
import jcma.workspace.IndexLock;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Opens a query {@link QueryService} under the per-index single-writer policy (M2). It <em>tries</em>
 * the {@link IndexLock write lock}: if it is free, the command runs a writable session and holds the
 * lock for its lifetime; if another jcma process already owns it (typically a live {@code serve}), the
 * command degrades to an {@link AnalysisSession#openReadOnly read-only} session that observes the
 * writer's persisted graph and resolves in heap, writing nothing. Writers ({@code serve}/{@code index}/
 * {@code compact}) instead {@link IndexLock#acquire} and fail fast — they must own the index.
 *
 * <p>The returned {@link Held} owns the service and the (possibly absent) lock; closing it closes the
 * service <em>then</em> releases the lock, so nothing mutates the store after the lock is dropped.
 */
final class QuerySessions {

    private QuerySessions() {}

    /** A {@link QueryService} and the write lock it holds — {@code lock} is {@code null} when read-only. */
    record Held(QueryService service, IndexLock lock, boolean readOnly) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                service.close();
            } finally {
                if (lock != null) {
                    lock.close();
                }
            }
        }
    }

    /**
     * Open a session over {@code indexDir}: writable (driven by {@code writableSource}) while the write
     * lock is free, else read-only with a one-line notice on {@code err}.
     */
    static Held open(Path indexDir, Workspace workspace, FreshnessSource writableSource,
            Metrics metrics, PrintStream err) throws IOException {
        IndexLock lock = IndexLock.tryAcquire(indexDir).orElse(null);
        try {
            AnalysisSession session;
            if (lock != null) {
                session = AnalysisSession.open(indexDir, workspace, writableSource, metrics);
            } else {
                err.println("jcma: index in use by another jcma process — running read-only "
                        + "(answers reflect its persisted graph; this session resolves in memory, "
                        + "writing nothing)");
                session = AnalysisSession.openReadOnly(indexDir, workspace, metrics);
            }
            return new Held(new QueryService(session), lock, lock == null);
        } catch (IOException | RuntimeException e) {
            if (lock != null) {
                lock.close();
            }
            throw e;
        }
    }
}
