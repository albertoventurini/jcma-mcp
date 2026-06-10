package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Task 09 — the in-session {@link FreshnessGuard} on-access backstop: {@code reindexOne} is a no-op on
 * an unchanged file and on an mtime-lie, but re-parses a genuinely changed file and makes its new
 * symbols queryable; {@code ensureFresh} observes an out-of-band edit made behind the index before the
 * read returns; and a re-parsed TEST file keeps its TEST tag (the reason the tag now lives on the
 * {@link FileTable} row). Each test cold-indexes a generated repo through the {@link Reconciler}, then
 * drives the guard against the same open {@link LsmStore}.
 */
class FreshnessGuardTest {

    private static final String PKG = "com/example/";

    /** Write {@code com.example.<name>} with one method {@code <method>()} into the given source root. */
    private static void writeClass(Path srcRoot, String name, String method) throws IOException {
        Path dir = srcRoot.resolve("com/example");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"),
                "package com.example;\npublic class " + name + " {\n  public void " + method + "() {}\n}\n");
    }

    private static Path mainFile(Path repo, String name) {
        return repo.resolve("src/main/java/com/example/" + name + ".java");
    }

    private static List<SourceRoot> roots(Path repo) {
        return List.of(
                new SourceRoot(repo.resolve("src/main/java"), SourceSet.MAIN),
                new SourceRoot(repo.resolve("src/test/java"), SourceSet.TEST));
    }

    private static Path indexDir(Path repo) {
        return repo.resolve(".jcma");
    }

    /** Push a path's mtime later so size/mtime reconciliation sees it as a suspect. */
    private static void bumpMtime(Path file) throws IOException {
        long later = Files.getLastModifiedTime(file).toMillis() + 5_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(later));
    }

    /** Cold-index {@code repo} (main + test roots) and persist the index + file table. */
    private static void coldIndex(Path repo) throws IOException {
        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            new Reconciler(new Indexer(), Metrics.noop()).reindex(repo, roots(repo), store, indexDir(repo));
        }
    }

    private static FreshnessGuard guard(Path repo, LsmStore store) throws IOException {
        return FreshnessGuard.open(repo, indexDir(repo), store, new Indexer(), FreshnessSource.none(),
                roots(repo), Metrics.noop());
    }

    @Test
    void reindexOneOnUnchangedFileIsANoOp(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/main/java"), "Alpha", "alpha");
        coldIndex(repo);

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);
            assertFalse(g.reindexOne(mainFile(repo, "Alpha")).reindexed(), "an unchanged file is not re-parsed");
            assertTrue(store.contains(PKG + "Alpha#alpha()."), "the symbol is intact");
        }
    }

    @Test
    void reindexOneOnChangedFileReparsesAndIsQueryable(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/main/java"), "Alpha", "alpha");
        coldIndex(repo);

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);

            writeClass(repo.resolve("src/main/java"), "Alpha", "renamedMethod"); // changes bytes + size
            bumpMtime(mainFile(repo, "Alpha"));

            assertTrue(g.reindexOne(mainFile(repo, "Alpha")).reindexed(), "a changed file is re-parsed");
            assertTrue(store.contains(PKG + "Alpha#renamedMethod()."), "the new symbol is indexed");
            assertFalse(store.contains(PKG + "Alpha#alpha()."), "the old symbol is gone");
        }
    }

    @Test
    void reindexOneOnMtimeLieIsANoOp(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/main/java"), "Alpha", "alpha");
        coldIndex(repo);

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);

            bumpMtime(mainFile(repo, "Alpha")); // size+mtime differ, but the bytes (hash) do not

            assertFalse(g.reindexOne(mainFile(repo, "Alpha")).reindexed(), "the hash proves the bytes are unchanged → no re-parse");
            assertTrue(store.contains(PKG + "Alpha#alpha()."), "the symbol is intact");
        }
    }

    @Test
    void ensureFreshObservesAnOutOfBandEdit(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/main/java"), "Alpha", "alpha");
        coldIndex(repo);

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);

            // An edit made behind the index — the case a missed proactive signal would otherwise lose.
            writeClass(repo.resolve("src/main/java"), "Alpha", "outOfBand");
            bumpMtime(mainFile(repo, "Alpha"));

            assertTrue(g.ensureFresh(mainFile(repo, "Alpha")).stream().anyMatch(NodeDiff::reindexed),
                    "the read path refreshes the stale file");
            assertTrue(store.contains(PKG + "Alpha#outOfBand()."), "the edit is visible before the read returns");
            assertFalse(store.contains(PKG + "Alpha#alpha()."));
        }
    }

    @Test
    void reindexOneTombstonesAFileDeletedOutOfBand(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/main/java"), "Alpha", "alpha");
        writeClass(repo.resolve("src/main/java"), "Beta", "beta");
        coldIndex(repo);

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);

            Files.delete(mainFile(repo, "Alpha"));

            assertTrue(g.reindexOne(mainFile(repo, "Alpha")).reindexed(), "a vanished file is reconciled");
            assertFalse(store.contains(PKG + "Alpha#alpha()."), "its symbols are tombstoned");
            assertFalse(store.contains(PKG + "Alpha#"));
            assertTrue(store.contains(PKG + "Beta#beta()."), "an untouched file is intact");
            assertFalse(FileTable.load(indexDir(repo)).paths().contains(Path.of("src/main/java/com/example/Alpha.java")),
                    "the row is dropped from the persisted table");
        }
    }

    @Test
    void reindexOneOnUntrackedButExistingFileIndexesIt(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/main/java"), "Alpha", "alpha");
        coldIndex(repo);

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);

            // A brand-new file with no table row — cross-file new-file discovery (task-10 completeness).
            writeClass(repo.resolve("src/main/java"), "Gamma", "gamma");

            assertTrue(g.reindexOne(mainFile(repo, "Gamma")).reindexed(),
                    "an untracked-but-existing file is allocated an id and indexed");
            assertTrue(store.contains(PKG + "Gamma#gamma()."), "the new file's symbol is queryable");
        }
    }

    @Test
    void reindexedTestFileKeepsItsTestTag(@TempDir Path repo) throws IOException {
        writeClass(repo.resolve("src/test/java"), "AlphaTest", "checks");
        coldIndex(repo);
        Path testFile = repo.resolve("src/test/java/com/example/AlphaTest.java");

        try (LsmStore store = LsmStore.open(indexDir(repo), CompactionPolicy.manual())) {
            FreshnessGuard g = guard(repo, store);

            writeClass(repo.resolve("src/test/java"), "AlphaTest", "verifies"); // re-parse the test file
            bumpMtime(testFile);

            assertTrue(g.reindexOne(testFile).reindexed(), "the changed test file is re-parsed");
            assertEquals(SourceSet.TEST, methodSourceSet(store, "verifies"),
                    "a re-parsed test file's symbols stay tagged TEST (recovered from the file row)");
        }
    }

    /** The source set of the indexed method {@code com.example.AlphaTest#<method>()} via name search. */
    private static SourceSet methodSourceSet(LsmStore store, String method) {
        for (Symbol s : store.search(method)) {
            if (s.moniker().equals(PKG + "AlphaTest#" + method + "().")) {
                return s.sourceSet();
            }
        }
        throw new AssertionError("method not found in index: " + method);
    }
}
