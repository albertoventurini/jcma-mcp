package jcma.workspace;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The persistent path↔fileId + {@link Fingerprint} table (PRD §5.1 file table) — the freshness
 * counterpart to the moniker-space LSM store. It records, per indexed file, a stable {@code fileId}
 * (so re-indexing one file overlay-replaces exactly its base rows / tombstones exactly its id) and
 * the fingerprint the index was last built against. A monotonic, persisted {@code nextFileId} counter
 * means ids are never reused, even after a deletion.
 *
 * <p><b>Storage (decided task-08):</b> a simple length-framed binary file ({@value #FILE_NAME}),
 * loaded wholly into memory on open and rewritten atomically on save. Reconciliation always scans the
 * whole table (diff against the directory walk), so the FFM "mmap + go, page in what you touch" win
 * does not apply here — the heap map is the better fit. Paths are stored as given (the {@link
 * Reconciler} relativises them to the repo root before insertion, for repo-move robustness).
 */
public final class FileTable {

    /** File name of the freshness table within an index directory. */
    public static final String FILE_NAME = "files.tbl";

    private static final int MAGIC = 0x4A464D54; // "JFMT"
    private static final int VERSION = 1;

    /** A table row: the file's stable id and the fingerprint it was last indexed against. */
    public record Entry(int fileId, Fingerprint fingerprint) {}

    private final Map<Path, Entry> byPath;
    private final Map<Integer, Path> byId;
    private int nextId;

    private FileTable(Map<Path, Entry> byPath, Map<Integer, Path> byId, int nextId) {
        this.byPath = byPath;
        this.byId = byId;
        this.nextId = nextId;
    }

    /** Load the table from {@code indexDir}, or an empty table if {@value #FILE_NAME} is absent. */
    public static FileTable load(Path indexDir) throws IOException {
        Path file = indexDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new FileTable(new HashMap<>(), new HashMap<>(), 0);
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("bad magic — not a jcma file table: " + file);
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported file-table version " + version + ": " + file);
            }
            int nextId = in.readInt();
            int count = in.readInt();
            Map<Path, Entry> byPath = new HashMap<>(count * 2);
            Map<Integer, Path> byId = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                Path path = Path.of(readStr(in));
                int fileId = in.readInt();
                Fingerprint fp = new Fingerprint(in.readLong(), in.readLong(), in.readLong());
                byPath.put(path, new Entry(fileId, fp));
                byId.put(fileId, path);
            }
            return new FileTable(byPath, byId, nextId);
        }
    }

    /** True if the table holds no entries. */
    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    /** Number of entries. */
    public int size() {
        return byPath.size();
    }

    /** The entry for {@code path}, or {@code null} if untracked. */
    public Entry get(Path path) {
        return byPath.get(path);
    }

    /** The path of the file with {@code fileId}, or {@code null} if no such id is tracked. */
    public Path pathOf(int fileId) {
        return byId.get(fileId);
    }

    /** An immutable snapshot of the tracked paths. */
    public Set<Path> paths() {
        return Set.copyOf(byPath.keySet());
    }

    /** The next id {@link #allocateId()} will hand out (also the persisted high-water mark). */
    public int nextFileId() {
        return nextId;
    }

    /** Reserve and return a fresh, never-before-used file id. */
    public int allocateId() {
        return nextId++;
    }

    /** Insert or replace the entry for {@code path}. */
    public void put(Path path, int fileId, Fingerprint fingerprint) {
        byPath.put(path, new Entry(fileId, fingerprint));
        byId.put(fileId, path);
    }

    /** Drop {@code path} from the table (its id is not reused). */
    public void remove(Path path) {
        Entry removed = byPath.remove(path);
        if (removed != null) {
            byId.remove(removed.fileId());
        }
    }

    /** Write the table to {@code indexDir} atomically (temp file + rename), replacing any prior table. */
    public void save(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Path file = indexDir.resolve(FILE_NAME);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        List<Path> keys = new ArrayList<>(byPath.keySet());
        keys.sort(Comparator.naturalOrder()); // deterministic on-disk order
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(nextId);
            out.writeInt(keys.size());
            for (Path path : keys) {
                Entry e = byPath.get(path);
                writeStr(out, path.toString());
                out.writeInt(e.fileId());
                out.writeLong(e.fingerprint().size());
                out.writeLong(e.fingerprint().mtime());
                out.writeLong(e.fingerprint().contentHash());
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
