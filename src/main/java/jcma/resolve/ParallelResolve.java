package jcma.resolve;

import jcma.engine.AnalysisEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * The producer/consumer fan-out for Direction A (parallel resolve). K platform-thread <b>producers</b>,
 * each owning its own thread-independent {@link AnalysisEngine} fork, pull candidate files off a shared
 * work queue and run only engine-bound work ({@code parse} + name-scoped resolution) — touching no
 * {@code EdgeResolver} state and no store. The single <b>consumer</b> (the calling query-worker thread)
 * drains the results and applies each one serially via {@code consume}, so the single-writer store and
 * the cancel-at-file-boundary checkpoint are both preserved (producers never write; the consumer applies
 * whole files between cancel points).
 *
 * <p>This is the conservative per-shard variant (PRD doc "Start conservative"): each fork holds its own
 * mutable parser/type-solver/facade caches, so there is <b>zero</b> cross-thread cache mutation in the
 * parallel region — the only shared mutable state is the thread-safe {@code Metrics} and the
 * result-handoff queue. The forks' static {@code JavaParserFacade} keys are seeded single-threaded by the
 * caller before fan-out (see {@code JavaParserEngine.seedFacade}).
 *
 * <p><b>Cancellation:</b> on deadline the query worker (the consumer) is interrupted; it interrupts the
 * producers and rethrows {@link CancellationException}, same contract as the serial path. A producer
 * checks its interrupt flag between files (it cannot be interrupted mid-{@code resolve} — a
 * non-cooperative region — so it finishes the current file first, matching the serial boundary).
 */
final class ParallelResolve {

    /** Engine-bound work for one task; may throw {@link IOException} (a parse failure — skipped, as serial does). */
    @FunctionalInterface
    interface Produce<T, R> {
        R apply(AnalysisEngine engine, T task) throws IOException;
    }

    private ParallelResolve() {
    }

    /**
     * Run {@code produce} across {@code engines} (one per producer thread) over {@code tasks}, applying
     * each result with {@code consume} on the calling thread. A task whose {@code produce} throws {@link
     * IOException} is skipped (no {@code consume}), exactly as the serial path drops an unparseable file.
     * Any other producer {@link Throwable} is captured and rethrown on the calling thread (never silently
     * swallowed — a genuine bug must surface, not become a wrong answer). Blocks until every task is
     * consumed, then joins all producers.
     */
    static <T, R> void run(List<AnalysisEngine> engines, List<T> tasks,
            Produce<T, R> produce, BiConsumer<T, R> consume) {
        ConcurrentLinkedQueue<T> work = new ConcurrentLinkedQueue<>(tasks);
        BlockingQueue<Object[]> results = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> producerError = new AtomicReference<>();
        int total = tasks.size();

        List<Thread> producers = new ArrayList<>(engines.size());
        int i = 0;
        for (AnalysisEngine engine : engines) {
            Thread th = Thread.ofPlatform().name("jcma-resolve-" + i++).unstarted(() -> {
                T task;
                while ((task = work.poll()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        return; // cooperative cancel between files (cannot interrupt a resolve in flight)
                    }
                    try {
                        R r = produce.apply(engine, task);
                        results.add(new Object[] {task, r});
                    } catch (IOException skip) {
                        results.add(new Object[] {task, null}); // unparseable → skip, like serial
                    } catch (Throwable t) {
                        producerError.compareAndSet(null, t);
                        results.add(new Object[] {task, null}); // let the consumer progress, then rethrow
                    }
                }
            });
            producers.add(th);
        }
        producers.forEach(Thread::start);

        try {
            int consumed = 0;
            while (consumed < total) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("find_references cancelled");
                }
                Object[] pair = results.poll(50, TimeUnit.MILLISECONDS);
                if (pair == null) {
                    continue; // re-check interrupt; deadline may have fired while producers are busy
                }
                consumed++;
                @SuppressWarnings("unchecked")
                T task = (T) pair[0];
                @SuppressWarnings("unchecked")
                R result = (R) pair[1];
                if (result != null) {
                    consume.accept(task, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("find_references cancelled");
        } finally {
            producers.forEach(Thread::interrupt);
            for (Thread th : producers) {
                try {
                    th.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Throwable err = producerError.get();
        if (err != null) {
            if (err instanceof RuntimeException re) {
                throw re;
            }
            if (err instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(err);
        }
    }
}
