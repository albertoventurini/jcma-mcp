package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jcma.obs.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Read-only {@link LsmStore#openReadOnly} (M2). A read-only store must <b>serve</b> the persisted
 * graph (mmap'd base ∪ replayed overlay log) yet <b>never write disk</b>: an {@code applyEdit} updates
 * only this process's heap (so lazy resolution still works), the on-disk log is untouched, and {@link
 * LsmStore#compact()} is rejected. This is what lets a query process coexist with a live writer.
 */
class LsmStoreReadOnlyTest {

    private static Symbol sym(String moniker, int fileId) {
        String name = moniker.substring(moniker.lastIndexOf('/') + 1);
        return new Symbol(moniker, SymbolKind.METHOD, 0, null, fileId, new Range(1, 1, 1, 9), name, null);
    }

    private static FileIndex file(int id, String... monikers) {
        List<Symbol> syms = new ArrayList<>();
        for (String m : monikers) {
            syms.add(sym(m, id));
        }
        return new FileIndex(id, syms, List.of());
    }

    @Test
    void servesPersistedGraphButNeverWritesDisk(@TempDir Path dir) throws IOException {
        // A writer builds a base (compacted) + an overlay-log-only edit, the way `serve` would.
        try (LsmStore w = LsmStore.open(dir, CompactionPolicy.manual())) {
            w.applyEdit(file(0, "pkg/A/base"));
            w.compact();                         // folds into the mmap'd base
            w.applyEdit(file(1, "pkg/B/overlay")); // lives only in overlay.log
        }
        Path log = dir.resolve(LsmStore.OVERLAY_LOG);
        long logSizeBefore = Files.size(log);

        try (LsmStore ro = LsmStore.openReadOnly(dir, Metrics.noop())) {
            assertTrue(ro.contains("pkg/A/base"), "reads the compacted base");
            assertTrue(ro.contains("pkg/B/overlay"), "replays the overlay log");

            // A heap-only edit (what lazy resolution does) is visible to this process...
            ro.applyEdit(file(2, "pkg/C/sessionLocal"));
            assertTrue(ro.contains("pkg/C/sessionLocal"), "edit is visible in this process's heap");
            // ...but must not reach the shared on-disk log...
            assertEquals(logSizeBefore, Files.size(log), "a read-only edit must not touch overlay.log");
            // ...and compaction (which rewrites base segments) is forbidden.
            assertThrows(IllegalStateException.class, ro::compact, "compaction is forbidden read-only");
        }

        // A subsequent writer must see no trace of the read-only session's heap-only edit.
        try (LsmStore reopened = LsmStore.open(dir, CompactionPolicy.manual())) {
            assertFalse(reopened.contains("pkg/C/sessionLocal"), "read-only edit was never persisted");
            assertTrue(reopened.contains("pkg/B/overlay"), "the real overlay edit survived");
        }
    }
}
