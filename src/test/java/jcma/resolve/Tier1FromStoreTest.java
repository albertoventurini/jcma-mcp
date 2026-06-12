package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jcma.IndexFixture;
import jcma.index.FileIndex;
import jcma.index.Indexer;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lever #1 crux (docs/structural-resolve-split-and-parse-cache.md): {@link EdgeResolver#tier1Slice}
 * rebuilds a file's Tier-1 base — symbols, {@code CONTAINS} edges, text units — from already-persisted
 * state, with <b>no re-parse</b>. This pins the equivalence the optimisation rests on: the
 * reconstructed slice must equal a fresh structural re-parse ({@link Indexer#indexFile}) of the same
 * file, field for field (compared as <b>sets</b> — order differs, content must not). The fixture file
 * carries Javadoc, so its text units are non-trivial.
 */
class Tier1FromStoreTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");

    @Test
    void tier1SliceEqualsAReParseOfTheFile(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(REFS, indexDir);
        Path file = REFS.resolve("app/Service.java").toAbsolutePath().normalize();
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
            int fid = resolver.fileId(file);
            assertFalse(fid < 0, "Service.java is indexed");

            EdgeResolver.FileSlice slice = resolver.tier1Slice(fid);
            FileIndex reparse = new Indexer().indexFile(fid, file);

            assertEquals(new HashSet<>(reparse.symbols()), new HashSet<>(slice.symbols),
                    "reconstructed symbols == re-parse");
            assertEquals(new HashSet<>(reparse.edges()), new HashSet<>(slice.base),
                    "reconstructed CONTAINS edges == re-parse");
            assertEquals(new HashSet<>(reparse.texts()), new HashSet<>(slice.texts),
                    "text units read back from the store == re-parse");
            assertFalse(slice.texts.isEmpty(), "the fixture's Javadoc makes the text units non-trivial");
        }
    }
}
