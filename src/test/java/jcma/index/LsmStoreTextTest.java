package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.engine.TextKind;
import jcma.engine.TextUnit;
import jcma.index.TextIndex.TextOccurrence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 task-01 — {@link LsmStore#searchText} over the D2 text corpus: base+overlay fidelity (same
 * answer before and after a compaction) and freshness (editing/deleting a file updates/removes its
 * text hits). The text segment rides {@link LsmStore#compact} like symbols/edges — base(unchanged) ∪
 * overlay(changed), no re-parse.
 */
class LsmStoreTextTest {

    private static FileIndex fileWithText(int fileId, TextKind kind, String text) {
        return new FileIndex(fileId, List.of(), List.of(),
                List.of(new TextUnit(kind, 1, 1, 1, 1 + text.length(), text)));
    }

    private static List<TextOccurrence> search(LsmStore store, String q) {
        return store.searchText(q);
    }

    @Test
    void findsTextInTheOverlayBeforeCompaction(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            store.applyEdit(fileWithText(0, TextKind.STRING_LITERAL, "alpha beta"));
            List<TextOccurrence> hits = search(store, "alpha");
            assertEquals(1, hits.size(), "overlay text is searchable before compaction");
            assertEquals(0, hits.get(0).fileId());
            assertEquals(TextKind.STRING_LITERAL, hits.get(0).kind());
        }
    }

    @Test
    void findsTextInTheBaseAfterCompaction(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            store.applyEdit(fileWithText(0, TextKind.COMMENT, "TODO fix alpha"));
            store.compact();
            assertTrue(store.isCompacted(), "folded into a fresh base");
            List<TextOccurrence> hits = search(store, "alpha");
            assertEquals(1, hits.size(), "text rides compaction into the base (no re-parse)");
            assertEquals(TextKind.COMMENT, hits.get(0).kind());
        }
    }

    @Test
    void editingAFileUpdatesItsTextHits(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            store.applyEdit(fileWithText(0, TextKind.STRING_LITERAL, "alpha beta"));
            store.compact();
            // Re-emit file 0 wholesale with new text — the old text must disappear.
            store.applyEdit(fileWithText(0, TextKind.STRING_LITERAL, "gamma delta"));
            store.compact();
            assertTrue(search(store, "alpha").isEmpty(), "the superseded literal is gone");
            assertEquals(1, search(store, "gamma").size(), "the new literal is present");
        }
    }

    @Test
    void deletingAFileRemovesItsTextHits(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            store.applyEdit(fileWithText(0, TextKind.JAVADOC, "looks up alpha by id"));
            store.applyEdit(fileWithText(1, TextKind.JAVADOC, "renders alpha to text"));
            store.compact();
            assertEquals(2, search(store, "alpha").size());

            store.applyEdit(FileIndex.deleted(0)); // tombstone file 0
            store.compact();
            List<TextOccurrence> remaining = search(store, "alpha");
            assertEquals(1, remaining.size(), "only the un-deleted file's hit survives");
            assertEquals(1, remaining.get(0).fileId());
        }
    }
}
