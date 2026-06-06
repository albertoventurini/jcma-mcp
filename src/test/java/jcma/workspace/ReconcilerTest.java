package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.index.Symbol;
import jcma.obs.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 08 — the warm-reopen {@link Reconciler}: cold scan, fast-path (0 reparsed), single-file edit
 * (exactly that file), deletion (tombstone), mtime-lie (hash confirms skip), and new-file add. Each
 * builds a small repo under a {@code @TempDir} (freshness tests must mutate their inputs, so the
 * fixtures are generated, not committed) and asserts both the {@link Reconciler.ReindexStats} and the
 * resulting live view through {@link LsmStore}.
 */
class ReconcilerTest {

    private Reconciler reconciler() {
        return new Reconciler(new Indexer(), Metrics.noop());
    }

    /** Write {@code com.example.<name>} with one method {@code <method>()} into the main source root. */
    private static void writeClass(Path repo, String name, String method) throws IOException {
        Path dir = repo.resolve("src/main/java/com/example");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"),
                "package com.example;\npublic class " + name + " {\n  public void " + method + "() {}\n}\n");
    }

    private static List<SourceRoot> roots(Path repo) {
        return List.of(new SourceRoot(repo.resolve("src/main/java"), SourceSet.MAIN));
    }

    private static Path indexDir(Path repo) {
        return repo.resolve(".jcma");
    }

    /** Push a path's mtime later so size/mtime reconciliation sees it as a suspect. */
    private static void bumpMtime(Path file) throws IOException {
        long later = Files.getLastModifiedTime(file).toMillis() + 5_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(later));
    }

    @Test
    void coldReindexIndexesEverythingAndWritesTheTable(@TempDir Path repo) throws IOException {
        writeClass(repo, "Alpha", "alpha");
        writeClass(repo, "Beta", "beta");

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            Reconciler.ReindexStats s = reconciler().reindex(repo, roots(repo), store, indexDir(repo));
            assertEquals(2, s.total());
            assertEquals(2, s.added());
            assertEquals(0, s.changed());
            assertEquals(0, s.deleted());
            assertEquals(2, s.reparsed());
            assertFalse(s.warm(), "a cold scan is not a fast-path reopen");
            assertTrue(store.contains("com/example/Alpha#"));
            assertTrue(store.contains("com/example/Beta#"));
        }
        assertFalse(FileTable.load(indexDir(repo)).isEmpty(), "the cold scan persists the file table");
    }

    @Test
    void warmReopenWithNoChangesReparsesNothing(@TempDir Path repo) throws IOException {
        writeClass(repo, "Alpha", "alpha");
        writeClass(repo, "Beta", "beta");
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            reconciler().reindex(repo, roots(repo), store, indexDir(repo));
        }

        // Reopen against the persisted index; nothing on disk changed.
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            Reconciler.ReindexStats s = reconciler().reindex(repo, roots(repo), store, indexDir(repo));
            assertEquals(2, s.total());
            assertEquals(2, s.unchanged());
            assertEquals(0, s.reparsed(), "fast path: nothing re-parsed");
            assertTrue(s.warm());
            assertTrue(store.contains("com/example/Alpha#"), "symbols survive the warm reopen");
            assertTrue(store.contains("com/example/Beta#"));
        }
    }

    @Test
    void touchingOneFileReparsesExactlyThatFile(@TempDir Path repo) throws IOException {
        writeClass(repo, "Alpha", "alpha");
        writeClass(repo, "Beta", "beta");
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            reconciler().reindex(repo, roots(repo), store, indexDir(repo));
        }

        // Rename Alpha's method (changes its bytes + size); leave Beta untouched.
        writeClass(repo, "Alpha", "renamedMethod");
        bumpMtime(repo.resolve("src/main/java/com/example/Alpha.java"));

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            Reconciler.ReindexStats s = reconciler().reindex(repo, roots(repo), store, indexDir(repo));
            assertEquals(1, s.changed());
            assertEquals(0, s.added());
            assertEquals(0, s.deleted());
            assertEquals(1, s.reparsed(), "only the edited file is re-parsed");

            assertTrue(store.contains("com/example/Alpha#renamedMethod()."), "the new symbol is indexed");
            assertFalse(store.contains("com/example/Alpha#alpha()."), "the old symbol is gone");
            assertTrue(store.contains("com/example/Beta#beta()."), "the untouched file is intact");
        }
    }

    @Test
    void deletingAFileTombstonesItsSymbols(@TempDir Path repo) throws IOException {
        writeClass(repo, "Alpha", "alpha");
        writeClass(repo, "Beta", "beta");
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            reconciler().reindex(repo, roots(repo), store, indexDir(repo));
        }

        Files.delete(repo.resolve("src/main/java/com/example/Beta.java"));

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            Reconciler.ReindexStats s = reconciler().reindex(repo, roots(repo), store, indexDir(repo));
            assertEquals(1, s.total(), "only Alpha remains on disk");
            assertEquals(1, s.deleted());
            assertEquals(0, s.reparsed(), "a deletion re-parses nothing");

            assertFalse(store.contains("com/example/Beta#"), "the deleted file's symbols are tombstoned");
            assertTrue(store.contains("com/example/Alpha#"));
        }
        assertEquals(1, FileTable.load(indexDir(repo)).size(), "the table drops the deleted file");
    }

    @Test
    void mtimeLieIsConfirmedByHashAndSkipped(@TempDir Path repo) throws IOException {
        writeClass(repo, "Alpha", "alpha");
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            reconciler().reindex(repo, roots(repo), store, indexDir(repo));
        }

        // Touch the mtime but not the bytes → suspect by stat, but the hash proves it is unchanged.
        bumpMtime(repo.resolve("src/main/java/com/example/Alpha.java"));

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            Reconciler.ReindexStats s = reconciler().reindex(repo, roots(repo), store, indexDir(repo));
            assertEquals(0, s.reparsed(), "the hash confirms the bytes are unchanged → no re-parse");
            assertEquals(1, s.unchanged());
            assertTrue(s.warm());
        }
    }

    @Test
    void addingANewFileIndexesOnlyItWithAFreshId(@TempDir Path repo) throws IOException {
        writeClass(repo, "Alpha", "alpha");
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            reconciler().reindex(repo, roots(repo), store, indexDir(repo));
        }

        writeClass(repo, "Gamma", "gamma");

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            Reconciler.ReindexStats s = reconciler().reindex(repo, roots(repo), store, indexDir(repo));
            assertEquals(2, s.total());
            assertEquals(1, s.added());
            assertEquals(0, s.changed());
            assertEquals(1, s.reparsed(), "only the new file is parsed");

            assertTrue(store.contains("com/example/Gamma#"));
            int alphaFile = typeFileId(store, "Alpha");
            int gammaFile = typeFileId(store, "Gamma");
            assertNotEquals(alphaFile, gammaFile, "the new file gets a distinct id");
        }
    }

    /** The declaring fileId of the top-level type {@code com.example.<name>} via the store's name search. */
    private static int typeFileId(LsmStore store, String name) {
        for (Symbol sym : store.search(name)) {
            if (sym.moniker().equals("com/example/" + name + "#")) {
                return sym.fileId();
            }
        }
        throw new AssertionError("type not found in index: " + name);
    }
}
