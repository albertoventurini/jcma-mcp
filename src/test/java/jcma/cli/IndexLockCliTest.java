package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import jcma.workspace.IndexLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fail-fast half of the M2 policy at the CLI surface: a <em>writer</em> ({@code jcma index}) must
 * refuse to run — with a clear "in use" message, not silent corruption — when another jcma process holds
 * the write lock, and run normally when it is free. (A second holder in this JVM stands in for a live
 * {@code serve}.)
 */
class IndexLockCliTest {

    private static PrintStream discard() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    @Test
    void indexFailsFastWhenLockHeld(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path indexDir = tmp.resolve("idx");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try (IndexLock owner = IndexLock.acquire(indexDir)) {
            int code = Index.run(repo, new String[] {"index", indexDir.toString()},
                    discard(), new PrintStream(err));
            assertEquals(1, code, "a writer must fail (exit 1) when the index is in use");
            assertTrue(err.toString().contains("in use"),
                    "the failure must name the cause; got: " + err);
        }
    }

    @Test
    void indexRunsWhenLockFree(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path indexDir = tmp.resolve("idx");
        int code = Index.run(repo, new String[] {"index", indexDir.toString()}, discard(), discard());
        assertEquals(0, code, "a free index builds normally");
    }
}
