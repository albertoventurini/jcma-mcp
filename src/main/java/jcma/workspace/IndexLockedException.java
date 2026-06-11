package jcma.workspace;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Thrown when a <em>writer</em> ({@code jcma serve}/{@code jcma index}) cannot acquire the per-index
 * write lock because another jcma process already holds it. The index is single-writer (PRD §5.1: the
 * LSM overlay + durable log assume one appender/compactor); this turns what would otherwise be silent
 * cross-process corruption into a clear, fail-fast refusal. Query commands degrade to read-only instead
 * of throwing — see {@link IndexLock#tryAcquire}.
 */
public final class IndexLockedException extends IOException {

    public IndexLockedException(Path indexDir) {
        super("index in use by another jcma process: " + indexDir);
    }
}
