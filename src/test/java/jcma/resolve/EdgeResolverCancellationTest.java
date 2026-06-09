package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jcma.cli.Main;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-12 (red-first) — the cooperative cancel <b>checkpoint</b> in the Tier-2 resolve loop. When the
 * worker thread is interrupted (the time-box's {@code cancel(true)}), {@code find_references} must bail
 * with an unchecked {@link CancellationException} <b>before resolving any candidate file</b> — so
 * cancellation is prompt and never tears a mid-file edit. We assert the checkpoint by pre-setting the
 * interrupt flag and verifying no candidate file was resolved.
 */
class EdgeResolverCancellationTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");
    private static final String TARGET_MONIKER = "app/Service#run().";

    @Test
    void preInterruptedFindReferencesBailsBeforeResolvingAnyFile(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        Metrics metrics = Metrics.create();
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), metrics)) {
            Symbol target = resolver.declarations("run").stream()
                    .filter(s -> TARGET_MONIKER.equals(s.moniker()))
                    .findFirst().orElseThrow(() -> new AssertionError("Service.run() not indexed"));

            Thread.currentThread().interrupt(); // the cancellation signal the time-box would deliver
            try {
                assertThrows(CancellationException.class, () -> resolver.findReferences(target),
                        "the resolve loop's checkpoint trips on interruption");
            } finally {
                Thread.interrupted(); // clear the flag so it can't leak into other tests
            }

            assertEquals(0, metrics.counter("resolve.files").sum(),
                    "bailed at the checkpoint before resolving any candidate file");
        }
    }

    private static void index(Path repo, Path indexDir) {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        assertEquals(0, Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink),
                "jcma index should succeed");
    }
}
