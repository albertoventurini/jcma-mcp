package jcma.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The per-index <b>single-writer lock</b> (PRD §5.1). The LSM store's overlay + durable {@code
 * overlay.log} are owned by one appender/compactor: two processes opening the same index would
 * interleave log appends and swap base segments under each other's mmap — silent corruption. This is an
 * OS-level advisory {@link FileLock} on {@code <indexDir>/index.lock} that makes that ownership
 * explicit.
 *
 * <p>Two acquisition modes encode the policy decided for jcma:
 * <ul>
 *   <li><b>{@link #acquire}</b> (writers — {@code serve}/{@code index}) <em>requires</em> the lock and
 *       throws {@link IndexLockedException} if it is held: a writer that cannot own the index fails
 *       fast rather than corrupting it or silently degrading the agent's answers.</li>
 *   <li><b>{@link #tryAcquire}</b> (query commands — {@code refs}/{@code def}/{@code repl}/…) returns an
 *       empty {@link Optional} when held, so the caller can fall back to a read-only session that
 *       observes the live writer's persisted graph without touching disk.</li>
 * </ul>
 *
 * <p>The decision is an <em>atomic</em> try-acquire, never a "does a lock file exist?" check: between a
 * check and an open another process could grab or drop the lock. An OS lock also has no stale-lock
 * hazard — the kernel releases it when the holding process dies (crash, {@code kill -9}), so a dead
 * writer never wedges future readers. A second holder <em>within the same JVM</em> surfaces as {@link
 * OverlappingFileLockException}; we treat that identically to a foreign hold (held → not available).
 */
public final class IndexLock implements AutoCloseable {

    /** File name of the per-index write lock within an index directory. */
    public static final String LOCK_FILE = "index.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private IndexLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Atomically try to take the write lock for {@code indexDir}. Returns the held lock, or empty if
     * another process (or another holder in this JVM) currently owns it. Creates {@code indexDir} if
     * absent (a cold {@code jcma index} locks before the store dir exists).
     */
    public static Optional<IndexLock> tryAcquire(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        FileChannel channel = FileChannel.open(indexDir.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock(); // null ⇒ a foreign process holds it
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new IndexLock(channel, lock));
        } catch (OverlappingFileLockException heldInThisJvm) {
            channel.close();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Take the write lock for {@code indexDir}, or throw {@link IndexLockedException} if it is held —
     * the fail-fast path for writers that must own the index.
     */
    public static IndexLock acquire(Path indexDir) throws IOException {
        return tryAcquire(indexDir).orElseThrow(() -> new IndexLockedException(indexDir));
    }

    /** Release the lock and close the channel. */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
