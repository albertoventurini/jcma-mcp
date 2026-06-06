package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P2 — the {@link Indexer} repo pass writing <b>through {@link LsmStore}</b> (parse files
 * in parallel → apply each {@link FileIndex} → compact). Asserts the persisted index contains the
 * expected symbols (PRD §10 M1 verification): a controlled multi-file package, and a slice of the
 * pinned commons-lang corpus for real-code scale.
 */
class IndexRepoTest {

    private static final Path SHAPES_ROOT = Path.of("src/test/resources/fixtures/indexer");
    private static final Path COMMONS_SLICE =
            Path.of("src/test/resources/fixtures/engine/commons-lang-slice/src");

    @Test
    void indexesAControlledPackageThroughTheStore(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            Indexer.IndexStats stats =
                    new Indexer().indexRepo(List.of(new SourceRoot(SHAPES_ROOT, SourceSet.MAIN)), store);

            assertEquals(4, stats.files(), "Shape, Circle, Day, Point");
            assertTrue(stats.symbols() >= 12, "symbols across the 4 files: " + stats.symbols());
            assertTrue(store.isCompacted(), "indexRepo compacts into a fresh base");

            // Every top-level type is present, across files, after compaction.
            assertTrue(store.contains("com/example/shapes/Shape#"));
            assertTrue(store.contains("com/example/shapes/Circle#"));
            assertTrue(store.contains("com/example/shapes/Day#"));
            assertTrue(store.contains("com/example/shapes/Point#"));
            assertTrue(store.contains("com/example/shapes/Circle#Builder#"), "nested type indexed");

            // Name search (trigram, built by compaction) finds a declared symbol.
            assertTrue(monikers(store.search("Circle")).contains("com/example/shapes/Circle#"));
            assertTrue(monikers(store.search("isFriday")).contains("com/example/shapes/Day#isFriday()."));
        }
    }

    @Test
    void indexesCommonsLangSliceForScale(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            Indexer.IndexStats stats =
                    new Indexer().indexRepo(List.of(new SourceRoot(COMMONS_SLICE, SourceSet.MAIN)), store);

            assertTrue(stats.files() >= 2, "the slice has at least AnnotationUtils + ToStringStyle");
            assertTrue(stats.symbols() >= 30, "real classes carry many members: " + stats.symbols());

            assertTrue(store.contains("org/apache/commons/lang3/AnnotationUtils#"),
                    "top-level type indexed with its FQN moniker");
            assertTrue(store.contains("org/apache/commons/lang3/builder/ToStringStyle#"),
                    "a type in a sub-package");
            assertTrue(monikers(store.search("ToStringStyle")).contains(
                    "org/apache/commons/lang3/builder/ToStringStyle#"));
        }
    }

    @Test
    void indexFileOutputAppliesAsOneOverlayEdit(@TempDir Path dir) throws IOException {
        // The Indexer's per-file output flows through LsmStore as a single overlay edit (no rescan),
        // and the containment it extracts is queryable via the store.
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            FileIndex circle = new Indexer().indexFile(0, SHAPES_ROOT.resolve("com/example/shapes/Circle.java"));
            store.applyEdit(circle);
            assertEquals(1, store.overlayFileCount(), "one file = one overlay edit");
            assertTrue(store.contains("com/example/shapes/Circle#area()."));
            assertTrue(store.fwd("com/example/shapes/Circle#").stream()
                    .anyMatch(e -> e.type() == EdgeType.CONTAINS), "containment queryable via the store");
        }
    }

    private static Set<String> monikers(List<Symbol> symbols) {
        return symbols.stream().map(Symbol::moniker).collect(Collectors.toSet());
    }
}
