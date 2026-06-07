package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.index.SourceSet;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 08 — {@link FileTable} persistence: framed-binary save/load round-trip, the monotonic
 * never-reused id counter, removal, and atomic (replace-not-append) overwrite.
 */
class FileTableTest {

    private static final Path A = Path.of("src/main/java/A.java");
    private static final Path B = Path.of("src/main/java/pkg/B.java");

    @Test
    void absentTableLoadsEmpty(@TempDir Path dir) throws IOException {
        FileTable t = FileTable.load(dir);
        assertTrue(t.isEmpty());
        assertEquals(0, t.size());
        assertEquals(0, t.nextFileId(), "ids start at 0");
    }

    @Test
    void putSaveLoadRoundTripsEntriesAndCounter(@TempDir Path dir) throws IOException {
        FileTable t = FileTable.load(dir);
        int ia = t.allocateId();
        int ib = t.allocateId();
        t.put(A, ia, new Fingerprint(10, 111, 0xAAAAL), SourceSet.MAIN);
        t.put(B, ib, new Fingerprint(20, 222, 0xBBBBL), SourceSet.MAIN);
        t.save(dir);

        FileTable r = FileTable.load(dir);
        assertEquals(2, r.size());
        assertEquals(2, r.nextFileId(), "the id high-water mark persists");

        FileTable.Entry ea = r.get(A);
        assertNotNull(ea, "A round-trips");
        assertEquals(ia, ea.fileId());
        assertEquals(10, ea.fingerprint().size());
        assertEquals(111, ea.fingerprint().mtime());
        assertEquals(0xAAAAL, ea.fingerprint().contentHash());
        assertEquals(SourceSet.MAIN, ea.sourceSet(), "the MAIN tag round-trips");

        assertEquals(B, r.pathOf(ib), "id → path round-trips");
        assertTrue(r.paths().contains(A) && r.paths().contains(B));
    }

    @Test
    void sourceSetTagRoundTrips(@TempDir Path dir) throws IOException {
        FileTable t = FileTable.load(dir);
        t.put(A, t.allocateId(), new Fingerprint(10, 111, 0xAAAAL), SourceSet.MAIN);
        t.put(B, t.allocateId(), new Fingerprint(20, 222, 0xBBBBL), SourceSet.TEST);
        t.save(dir);

        FileTable r = FileTable.load(dir);
        assertEquals(SourceSet.MAIN, r.get(A).sourceSet(), "a MAIN file reloads as MAIN");
        assertEquals(SourceSet.TEST, r.get(B).sourceSet(), "a TEST file reloads as TEST, not defaulted to MAIN");
    }

    @Test
    void removeDropsAnEntry(@TempDir Path dir) throws IOException {
        FileTable t = FileTable.load(dir);
        t.put(A, t.allocateId(), new Fingerprint(1, 2, 3), SourceSet.MAIN);
        t.remove(A);
        assertNull(t.get(A));
        assertTrue(t.isEmpty());
    }

    @Test
    void idsAreNeverReusedAfterDeletion(@TempDir Path dir) throws IOException {
        FileTable t = FileTable.load(dir);
        int i0 = t.allocateId();
        t.put(A, i0, new Fingerprint(1, 2, 3), SourceSet.MAIN);
        t.remove(A);
        int i1 = t.allocateId();
        assertNotEquals(i0, i1, "a fresh id, not the deleted one");
        assertEquals(2, t.nextFileId());
    }

    @Test
    void saveReplacesRatherThanAppends(@TempDir Path dir) throws IOException {
        FileTable t = FileTable.load(dir);
        t.put(A, t.allocateId(), new Fingerprint(1, 2, 3), SourceSet.MAIN);
        t.save(dir);

        FileTable t2 = FileTable.load(dir);
        t2.remove(A);
        t2.save(dir);

        assertTrue(FileTable.load(dir).isEmpty(), "the rewrite replaces the prior table");
    }
}
