package jcma.query;

import jcma.engine.Position;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import jcma.resolve.Definition;
import jcma.resolve.References;
import jcma.session.AnalysisSession;
import jcma.session.SymbolHit;
import jcma.session.TextHit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Virtual-thread cancellable, time-boxed query serving over a single {@link AnalysisSession} (M1
 * task-12). Each query runs on a <b>single-thread virtual-thread executor</b>: the caller submits the
 * work and blocks on {@link Future#get(long, TimeUnit)} with the query's deadline, so the caller
 * returns promptly even if the worker stalls in a non-cooperative region. On expiry the worker is
 * {@linkplain Future#cancel(boolean) interrupted} — the resolver's cancel checkpoint stops it at a
 * candidate-file boundary (never mid-edit) — and a {@link QueryTimeoutException} is thrown.
 *
 * <p>The single worker thread is deliberate: it structurally serializes mutation of the one shared
 * store (one writer at a time), so no locking and no concurrent-overlay hazard. M1 serves one query
 * at a time (the consumer is a coding agent); cross-query concurrency and best-effort partial results
 * are deferred optimizations (see the task's scope override). The service <b>owns</b> the session and
 * closes it (and the worker) on {@link #close()}.
 */
public final class QueryService implements AutoCloseable {

    private final AnalysisSession session;
    private final ExecutorService worker;

    public QueryService(AnalysisSession session) {
        this.session = session;
        ThreadFactory factory = Thread.ofVirtual().name("jcma-query-", 0).factory();
        this.worker = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Run {@code op} on the worker thread, time-boxed by {@code deadline}. Returns its result, or
     * throws {@link QueryTimeoutException} on expiry (cancelling the worker). Checked
     * {@link IOException}s from {@code op} are unwrapped and rethrown; other failures propagate as
     * runtime exceptions.
     */
    <T> T execute(Callable<T> op, Duration deadline) throws QueryTimeoutException, IOException {
        Future<T> future = worker.submit(op);
        try {
            return future.get(Math.max(0, deadline.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            future.cancel(true); // interrupt the worker → it stops at the resolver's cancel checkpoint
            throw new QueryTimeoutException(deadline);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new QueryTimeoutException(deadline);
        } catch (ExecutionException failed) {
            throw rethrow(failed.getCause());
        }
    }

    /** Unwrap a worker failure: surface {@link IOException} as-is, otherwise rethrow unchecked. */
    private static IOException rethrow(Throwable cause) {
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof UncheckedIOException uio) {
            return uio.getCause();
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        throw new RuntimeException(cause);
    }

    public List<Symbol> declarations(String name, Duration deadline) throws QueryTimeoutException, IOException {
        return execute(() -> session.declarations(name), deadline);
    }

    /**
     * Resolve a (optionally qualified or fully-qualified) {@code symbol} to its target declarations —
     * the one selector every surface shares (the §6 tools, the CLI {@code def}/{@code refs}, the REPL).
     * The last dotted segment is the simple name looked up via {@link #declarations}; the typed name as
     * a whole then suffix-anchors against each candidate's moniker ({@link QualifiedName#matches}). A
     * bare name keeps every declaration of that name; a qualified name narrows. The (pure) filter runs
     * off the worker thread — only the {@code declarations} lookup needs the single-writer worker.
     */
    public List<Symbol> resolveTargets(String symbol, Duration deadline) throws QueryTimeoutException, IOException {
        String simple = symbol.substring(symbol.lastIndexOf('.') + 1);
        List<Symbol> targets = new ArrayList<>();
        for (Symbol s : declarations(simple, deadline)) {
            if (QualifiedName.matches(s.moniker(), symbol)) {
                targets.add(s);
            }
        }
        return targets;
    }

    public References findReferences(Symbol target, Duration deadline) throws QueryTimeoutException, IOException {
        return execute(() -> session.findReferences(target), deadline);
    }

    public Definition findDefinition(Symbol target, Duration deadline) throws QueryTimeoutException, IOException {
        return execute(() -> session.findDefinition(target), deadline);
    }

    public Optional<Definition> findDefinitionAt(Path file, Position pos, Duration deadline)
            throws QueryTimeoutException, IOException {
        return execute(() -> session.findDefinitionAt(file, pos), deadline);
    }

    /**
     * {@code search_symbols} — run the index name search on the worker thread (it refreshes, so it
     * holds the single-writer invariant), then off-thread filter by {@code kind} (if non-null), order by
     * {@link SymbolRanking#byRelevance}, and truncate to {@code limit}.
     */
    public List<SymbolHit> searchSymbols(String query, SymbolKind kind, int limit, Duration deadline)
            throws QueryTimeoutException, IOException {
        List<SymbolHit> hits = execute(() -> session.searchSymbols(query), deadline);
        Stream<SymbolHit> stream = hits.stream();
        if (kind != null) {
            stream = stream.filter(h -> h.symbol().kind() == kind);
        }
        return stream
                .sorted(Comparator.comparing(SymbolHit::symbol, SymbolRanking.byRelevance(query)))
                .limit(limit)
                .toList();
    }

    /**
     * {@code grep_java} text tier (M3 task-01) — run the pure text search on the worker thread (it
     * refreshes, holding the single-writer invariant), then off-thread order by {@code (file, line,
     * col)} and truncate to {@code limit}. A pure passthrough: no resolve, no deadline-sensitive work.
     */
    public List<TextHit> searchText(String query, int limit, Duration deadline)
            throws QueryTimeoutException, IOException {
        List<TextHit> hits = execute(() -> session.searchText(query), deadline);
        return hits.stream()
                .sorted(Comparator.comparing((TextHit h) -> h.file() == null ? "" : h.file())
                        .thenComparingInt(TextHit::line)
                        .thenComparingInt(TextHit::col))
                .limit(limit)
                .toList();
    }

    /** {@code find_references} by use-site position (go-to-refs). */
    public References findReferencesAt(Path file, Position pos, Duration deadline)
            throws QueryTimeoutException, IOException {
        return execute(() -> session.findReferencesAt(file, pos), deadline);
    }

    public List<MonikerEdge> supertypes(Symbol target, Duration deadline) throws QueryTimeoutException, IOException {
        return execute(() -> session.supertypes(target), deadline);
    }

    public List<MonikerEdge> subtypes(Symbol target, Duration deadline) throws QueryTimeoutException, IOException {
        return execute(() -> session.subtypes(target), deadline);
    }

    /** A moniker's display signature (pure, no deadline) — passthrough to the session. */
    public String signatureOf(String moniker) {
        return session.signatureOf(moniker);
    }

    @Override
    public void close() throws IOException {
        // Stop the worker (interrupting any in-flight, just-timed-out query) and wait for it to
        // actually terminate *before* closing the store, so nothing mutates it during/after close.
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        session.close();
    }
}
