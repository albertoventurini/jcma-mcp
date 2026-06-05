package jcma.index;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The columnar symbol node store (PRD §5.1), read/written via FFM ({@code Arena.ofShared()} +
 * {@code FileChannel.map} → {@code MemorySegment}) — the production form of M0 {@code SpikeD.Base}.
 *
 * <p>Layout: a {@value #HEADER_BYTES}-byte header (magic + version + count + section offsets), a
 * dedup {@link StringArena} (names, signatures, monikers), then struct-of-arrays columns
 * ({@code kind, flags, enclosing, fileId, range×4, nameRef, sigRef, monikerRef}). Symbols are stored
 * sorted by moniker so ids are a deterministic function of the symbol set; the {@code int32} row
 * index is the interned symbol id, and {@code enclosing} holds the <em>id</em> of the containing
 * symbol ({@code -1} for top-level). The file is mmap'd and read in place — no deserialisation.
 *
 * <p>Identity is moniker-stable across a rewrite (ids may be reassigned, but {@link #idOf} keeps
 * resolving a moniker to its symbol); phantom symbols ({@code fileId == -1}) are first-class.
 */
public final class SymbolStore implements AutoCloseable {

    /** File name of the symbol segment within an index directory (used by {@code jcma index-dump}). */
    public static final String FILE_NAME = "symbols.seg";

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final long MAGIC = 0x4A434D4153594D31L; // "JCMASYM1"
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 64;

    // Column order within the struct-of-arrays block (each column is n int32s).
    private static final int C_KIND = 0;
    private static final int C_FLAGS = 1;
    private static final int C_ENCLOSING = 2;
    private static final int C_FILE_ID = 3;
    private static final int C_START_LINE = 4;
    private static final int C_START_COL = 5;
    private static final int C_END_LINE = 6;
    private static final int C_END_COL = 7;
    private static final int C_NAME_REF = 8;
    private static final int C_SIG_REF = 9;
    private static final int C_MONIKER_REF = 10;
    private static final int NUM_COLS = 11;

    private final Arena arena;
    private final MemorySegment seg;
    private final int n;
    private final long arenaOff;
    private final long colOff;
    private final String[] monikerById;
    private final Map<String, Integer> idByMoniker;

    private SymbolStore(Arena arena, MemorySegment seg) {
        this.arena = arena;
        this.seg = seg;
        this.n = seg.get(I, 12);
        this.arenaOff = seg.get(L, 16);
        this.colOff = seg.get(L, 32);
        this.monikerById = new String[n];
        this.idByMoniker = new HashMap<>(n * 2);
        for (int id = 0; id < n; id++) {
            String m = StringArena.read(seg, arenaOff, col(C_MONIKER_REF, id));
            monikerById[id] = m;
            idByMoniker.put(m, id);
        }
    }

    /** Write {@code symbols} to {@code path} as a fresh segment (overwrites any existing file). */
    public static void write(Path path, List<Symbol> symbols) throws IOException {
        List<Symbol> sorted = new ArrayList<>(symbols);
        sorted.sort(Comparator.comparing(Symbol::moniker));
        int n = sorted.size();

        Map<String, Integer> idByMoniker = new HashMap<>(n * 2);
        for (int id = 0; id < n; id++) {
            if (idByMoniker.put(sorted.get(id).moniker(), id) != null) {
                throw new IllegalArgumentException("duplicate moniker: " + sorted.get(id).moniker());
            }
        }

        StringArena.Builder strings = new StringArena.Builder();
        int[] nameRef = new int[n];
        int[] sigRef = new int[n];
        int[] monikerRef = new int[n];
        int[] enclosing = new int[n];
        for (int id = 0; id < n; id++) {
            Symbol s = sorted.get(id);
            nameRef[id] = strings.intern(s.name() == null ? "" : s.name());
            sigRef[id] = s.signature() == null ? -1 : strings.intern(s.signature());
            monikerRef[id] = strings.intern(s.moniker());
            if (s.enclosingMoniker() == null) {
                enclosing[id] = -1;
            } else {
                Integer enc = idByMoniker.get(s.enclosingMoniker());
                if (enc == null) {
                    throw new IllegalArgumentException(
                            "enclosing moniker not in symbol set: " + s.enclosingMoniker());
                }
                enclosing[id] = enc;
            }
        }

        long arenaLen = strings.byteSize();
        long arenaOff = HEADER_BYTES;
        long colOff = arenaOff + arenaLen;
        long total = colOff + (long) NUM_COLS * n * 4;

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, total, a);
            seg.set(L, 0, MAGIC);
            seg.set(I, 8, VERSION);
            seg.set(I, 12, n);
            seg.set(L, 16, arenaOff);
            seg.set(L, 24, arenaLen);
            seg.set(L, 32, colOff);
            strings.writeTo(seg, arenaOff);
            for (int id = 0; id < n; id++) {
                Symbol s = sorted.get(id);
                Range r = s.range();
                setCol(seg, colOff, n, C_KIND, id, s.kind().ordinal());
                setCol(seg, colOff, n, C_FLAGS, id, s.flags());
                setCol(seg, colOff, n, C_ENCLOSING, id, enclosing[id]);
                setCol(seg, colOff, n, C_FILE_ID, id, s.fileId());
                setCol(seg, colOff, n, C_START_LINE, id, r.startLine());
                setCol(seg, colOff, n, C_START_COL, id, r.startCol());
                setCol(seg, colOff, n, C_END_LINE, id, r.endLine());
                setCol(seg, colOff, n, C_END_COL, id, r.endCol());
                setCol(seg, colOff, n, C_NAME_REF, id, nameRef[id]);
                setCol(seg, colOff, n, C_SIG_REF, id, sigRef[id]);
                setCol(seg, colOff, n, C_MONIKER_REF, id, monikerRef[id]);
            }
            seg.force();
        }
    }

    /** Memory-map an existing segment at {@code path}; throws {@link IOException} on a bad/short magic. */
    public static SymbolStore load(Path path) throws IOException {
        long size = Files.size(path);
        Arena a = Arena.ofShared();
        boolean ok = false;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size < HEADER_BYTES) {
                throw new IOException("symbol store too small (" + size + " bytes): " + path);
            }
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, a);
            if (seg.get(L, 0) != MAGIC) {
                throw new IOException("bad magic — not a jcma symbol store: " + path);
            }
            int version = seg.get(I, 8);
            if (version != VERSION) {
                throw new IOException("unsupported symbol-store version " + version + ": " + path);
            }
            SymbolStore store = new SymbolStore(a, seg);
            ok = true;
            return store;
        } finally {
            if (!ok) {
                a.close();
            }
        }
    }

    /** Number of symbols (valid ids are {@code 0 .. size()-1}). */
    public int size() {
        return n;
    }

    /** Materialise the symbol at {@code id} (its {@code enclosingMoniker} resolved from the enclosing id). */
    public Symbol symbol(int id) {
        int enc = col(C_ENCLOSING, id);
        String enclosingMoniker = enc < 0 ? null : monikerById[enc];
        Range range = new Range(col(C_START_LINE, id), col(C_START_COL, id),
                col(C_END_LINE, id), col(C_END_COL, id));
        int sigRef = col(C_SIG_REF, id);
        String signature = sigRef < 0 ? null : StringArena.read(seg, arenaOff, sigRef);
        return new Symbol(
                monikerById[id],
                SymbolKind.byOrdinal(col(C_KIND, id)),
                col(C_FLAGS, id),
                enclosingMoniker,
                col(C_FILE_ID, id),
                range,
                StringArena.read(seg, arenaOff, col(C_NAME_REF, id)),
                signature);
    }

    /** The id of {@code moniker}, or empty if no symbol carries it. */
    public OptionalInt idOf(String moniker) {
        Integer id = idByMoniker.get(moniker);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    /** The moniker of the symbol at {@code id}. */
    public String monikerOf(int id) {
        return monikerById[id];
    }

    @Override
    public void close() {
        arena.close();
    }

    // Read column c at row id (uses this store's mapped segment + column block offset).
    private int col(int c, int id) {
        return seg.get(I, colOff + ((long) c * n + id) * 4);
    }

    private static void setCol(MemorySegment seg, long colOff, int n, int c, int id, int value) {
        seg.set(I, colOff + ((long) c * n + id) * 4, value);
    }
}
