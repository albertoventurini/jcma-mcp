package jcma.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A read-only {@link AnalysisSession#openReadOnly} session (M2) must serve real answers while writing
 * nothing to the index directory — the property that lets a diagnostic {@code repl}/one-shot coexist
 * with a live {@code serve}. {@code find_references} normally lazy-resolves and caches edges into the
 * overlay log (a disk write); read-only mode keeps that resolution in heap so the answer is complete
 * but the on-disk index is byte-for-byte unchanged.
 */
class ReadOnlySessionTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/indexer");

    /** Every regular file under {@code dir} → its byte size; the fingerprint of "did anything write?". */
    private static Map<Path, Long> sizes(Path dir) throws IOException {
        Map<Path, Long> out = new HashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(p, Files.size(p));
            }
        }
        return out;
    }

    @Test
    void servesReferencesWithoutWritingTheIndex(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("idx");
        IndexFixture.build(FIXTURE, indexDir);

        Map<Path, Long> before = sizes(indexDir);
        try (AnalysisSession ro = AnalysisSession.openReadOnly(indexDir, Workspace.discover(FIXTURE), Metrics.noop())) {
            List<Symbol> shape = ro.declarations("Shape");
            assertFalse(shape.isEmpty(), "read-only session still serves declarations");
            // find_references would append resolved edges to overlay.log in a writable session.
            ro.findReferences(shape.get(0));
        }
        assertEquals(before, sizes(indexDir), "a read-only session must not write to the index dir");
    }
}
