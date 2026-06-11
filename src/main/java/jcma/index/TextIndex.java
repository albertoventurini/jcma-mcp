package jcma.index;

import jcma.engine.TextKind;
import jcma.engine.TextUnit;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The D2 text index ({@code text.seg}) — the {@code grep_java} text tier (PRD §6; M3 task-01). An
 * <b>inline-snapshot</b> segment: each indexed {@link TextUnit} (a string literal, comment, or
 * Javadoc) is stored with its kind + 1-based range + its text, and a query is answered by a
 * <b>linear scan</b> over the units ({@code text.contains}; regex verify lands in task-03). No
 * trigram inversion.
 *
 * <p><b>Why inline scan, not a trigram index (structure reversal, 2026-06-11).</b> The task-01
 * footprint measurement showed a trigram index over this corpus is dominated by Javadoc and blows
 * the §5.1 budget (~10 MB on commons-lang, ~3× the raw text). The trigram machinery only buys
 * sub-linear query, which a ~3 MB corpus does not need — a scan is single-digit ms. Storing the text
 * inline costs ≈ the raw bytes (commons-lang ≈ 3.9 MB, ≈ the size of its whole existing index) and
 * keeps the query <b>hermetic</b> (it never reads source files) with a safe, snapshot-coherent
 * failure mode. A trigram pre-filter can layer on top of this exact corpus later (M4) if a large-repo
 * measurement ever demands it. This reverses the M3 D1 "sibling trigram segment" decision on that
 * new evidence; see PRD §5.1 / the task doc.
 *
 * <p><b>Storage = mmap'd</b>, house style ({@link SymbolStore}, {@link TrigramIndex}): FFM {@code
 * Arena.ofShared()} + {@code FileChannel.map} → {@code MemorySegment}, validate-on-read, deterministic
 * bytes. <b>Layout:</b> a {@value #HEADER_BYTES}-byte header (magic, version, {@code nUnits}, {@code
 * nFiles}, section offsets), a dedup {@link StringArena} (unit texts, interned in sorted-unit order),
 * the unit columns (struct-of-arrays, sorted by {@code (fileId, startLine, startCol)}: {@code kind,
 * fileId, startLine, startCol, endLine, endCol, textRef}), and a file directory ({@code fileId[nFiles]}
 * + {@code unitOffset[nFiles+1]}) so {@link #unitsOf} returns a file's units as a contiguous slice
 * (the compact merge's per-file gather, and a per-file drop).
 */
public final class TextIndex implements AutoCloseable {

    /** File name of the text segment within an index directory. */
    public static final String FILE_NAME = "text.seg";

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final long MAGIC = 0x4A434D4154585431L; // "JCMATXT1"
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 64;

    // Unit columns (each is nUnits int32s), struct-of-arrays like SymbolStore.
    private static final int C_KIND = 0;
    private static final int C_FILE_ID = 1;
    private static final int C_START_LINE = 2;
    private static final int C_START_COL = 3;
    private static final int C_END_LINE = 4;
    private static final int C_END_COL = 5;
    private static final int C_TEXT_REF = 6;
    private static final int NUM_COLS = 7;

    private static final TextKind[] KINDS = TextKind.values();

    /**
     * One text-search match: the file, the precise 1-based line/col of the match within the matched
     * unit, the unit's {@link TextKind} (so the hit can be labelled), and the matching line's snippet.
     */
    public record TextOccurrence(int fileId, int line, int col, TextKind kind, String lineSnippet) {}

    // A fileId-bearing unit, used internally for the sort + write (TextUnit itself is fileId-free —
    // it is the engine seam type, produced before file ids are assigned).
    private record Located(int fileId, TextUnit unit) {}

    private final Arena arena;
    private final MemorySegment seg;
    private final int nUnits;
    private final int nFiles;
    private final long arenaOff;
    private final long unitColOff;
    private final long fileDirOff;

    private TextIndex(Arena arena, MemorySegment seg) {
        this.arena = arena;
        this.seg = seg;
        this.nUnits = seg.get(I, 12);
        this.nFiles = seg.get(I, 16);
        this.arenaOff = seg.get(L, 24);
        this.unitColOff = seg.get(L, 32);
        this.fileDirOff = seg.get(L, 40);
    }

    /**
     * Write the units of {@code unitsByFile} ({@code fileId → its text units}) to {@code path} as a
     * fresh segment, indexing only those whose {@link TextUnit#kind()} is in {@code includedKinds}
     * (the build toggle's single filter point). Units are laid out sorted by {@code (fileId, startLine,
     * startCol)} and texts interned in that order, so the bytes are deterministic across rebuilds.
     */
    public static void write(Path path, Map<Integer, ? extends List<TextUnit>> unitsByFile,
            Set<TextKind> includedKinds) throws IOException {
        List<Located> units = new ArrayList<>();
        for (Map.Entry<Integer, ? extends List<TextUnit>> e : unitsByFile.entrySet()) {
            for (TextUnit u : e.getValue()) {
                if (includedKinds.contains(u.kind())) {
                    units.add(new Located(e.getKey(), u));
                }
            }
        }
        units.sort(Comparator
                .comparingInt(Located::fileId)
                .thenComparingInt(l -> l.unit().startLine())
                .thenComparingInt(l -> l.unit().startCol())
                .thenComparingInt(l -> l.unit().endLine())
                .thenComparingInt(l -> l.unit().endCol())
                .thenComparingInt(l -> l.unit().kind().ordinal())
                .thenComparing(l -> l.unit().text()));

        int nUnits = units.size();
        StringArena.Builder strings = new StringArena.Builder();
        int[] textRef = new int[nUnits];
        // File directory: distinct fileIds (ascending, since sorted by fileId first) + unit-offset
        // boundaries. Units of one file are contiguous, so the directory is built in the single pass.
        List<Integer> dirFileIds = new ArrayList<>();
        List<Integer> dirOffsets = new ArrayList<>();
        int prevFile = Integer.MIN_VALUE;
        for (int u = 0; u < nUnits; u++) {
            Located l = units.get(u);
            textRef[u] = strings.intern(l.unit().text());
            if (l.fileId() != prevFile) {
                dirFileIds.add(l.fileId());
                dirOffsets.add(u);
                prevFile = l.fileId();
            }
        }
        dirOffsets.add(nUnits); // closing boundary
        int nFiles = dirFileIds.size();

        long arenaLen = strings.byteSize();
        long arenaOff = HEADER_BYTES;
        long unitColOff = arenaOff + arenaLen;
        long fileDirOff = unitColOff + (long) NUM_COLS * nUnits * 4;
        long total = fileDirOff + (long) nFiles * 4 + (long) (nFiles + 1) * 4;

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, total, a);
            seg.set(L, 0, MAGIC);
            seg.set(I, 8, VERSION);
            seg.set(I, 12, nUnits);
            seg.set(I, 16, nFiles);
            seg.set(L, 24, arenaOff);
            seg.set(L, 32, unitColOff);
            seg.set(L, 40, fileDirOff);

            strings.writeTo(seg, arenaOff);
            for (int u = 0; u < nUnits; u++) {
                TextUnit t = units.get(u).unit();
                setCol(seg, unitColOff, nUnits, C_KIND, u, t.kind().ordinal());
                setCol(seg, unitColOff, nUnits, C_FILE_ID, u, units.get(u).fileId());
                setCol(seg, unitColOff, nUnits, C_START_LINE, u, t.startLine());
                setCol(seg, unitColOff, nUnits, C_START_COL, u, t.startCol());
                setCol(seg, unitColOff, nUnits, C_END_LINE, u, t.endLine());
                setCol(seg, unitColOff, nUnits, C_END_COL, u, t.endCol());
                setCol(seg, unitColOff, nUnits, C_TEXT_REF, u, textRef[u]);
            }
            for (int f = 0; f < nFiles; f++) {
                seg.set(I, fileDirOff + (long) f * 4, dirFileIds.get(f));
            }
            long offBase = fileDirOff + (long) nFiles * 4;
            for (int f = 0; f <= nFiles; f++) {
                seg.set(I, offBase + (long) f * 4, dirOffsets.get(f));
            }
            seg.force();
        }
    }

    /** Memory-map an existing segment at {@code path}; throws {@link IOException} on a bad/short magic. */
    public static TextIndex load(Path path) throws IOException {
        long size = Files.size(path);
        Arena a = Arena.ofShared();
        boolean ok = false;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size < HEADER_BYTES) {
                throw new IOException("text index too small (" + size + " bytes): " + path);
            }
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, a);
            if (seg.get(L, 0) != MAGIC) {
                throw new IOException("bad magic — not a jcma text index: " + path);
            }
            int version = seg.get(I, 8);
            if (version != VERSION) {
                throw new IOException("unsupported text-index version " + version + ": " + path);
            }
            TextIndex idx = new TextIndex(a, seg);
            ok = true;
            return idx;
        } finally {
            if (!ok) {
                a.close();
            }
        }
    }

    /** Number of indexed text units. */
    public int unitCount() {
        return nUnits;
    }

    /** The distinct file ids that contributed at least one indexed unit (ascending). */
    public int[] fileIds() {
        long base = fileDirOff;
        int[] out = new int[nFiles];
        for (int f = 0; f < nFiles; f++) {
            out[f] = seg.get(I, base + (long) f * 4);
        }
        return out;
    }

    /** The text units of {@code fileId} (reconstructed; empty if the file contributed none). */
    public List<TextUnit> unitsOf(int fileId) {
        int f = indexOfFile(fileId);
        if (f < 0) {
            return List.of();
        }
        long offBase = fileDirOff + (long) nFiles * 4;
        int start = seg.get(I, offBase + (long) f * 4);
        int end = seg.get(I, offBase + (long) (f + 1) * 4);
        List<TextUnit> out = new ArrayList<>(end - start);
        for (int u = start; u < end; u++) {
            out.add(unitAt(u));
        }
        return out;
    }

    /**
     * Occurrences whose text contains {@code query} as a literal, case-sensitive substring — the
     * {@code String} overload (delegates to a {@link SearchSpec#literal literal spec}).
     */
    public List<TextOccurrence> search(String query) {
        return search(SearchSpec.literal(query == null ? "" : query));
    }

    /**
     * Occurrences whose text matches {@code spec} (literal substring on the fast path, else regex via
     * {@code Matcher.find}), scanning every unit. One occurrence per distinct matched line within a
     * unit; ordered by {@code (fileId, line, col)}. Empty for a blank pattern.
     */
    public List<TextOccurrence> search(SearchSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return List.of();
        }
        List<TextOccurrence> out = new ArrayList<>();
        for (int u = 0; u < nUnits; u++) {
            match(KINDS[col(C_KIND, u)], col(C_FILE_ID, u), col(C_START_LINE, u), col(C_START_COL, u),
                    textOf(u), spec, out);
        }
        return out;
    }

    @Override
    public void close() {
        arena.close();
    }

    /**
     * Append the occurrences of {@code spec} within one (in-memory) unit to {@code out}. Shared by
     * {@link #search} and {@link LsmStore}'s overlay scan, so a unit matches identically whether it
     * sits in the base segment or the overlay. Routes to the literal {@code indexOf} loop on the fast
     * path, else a single {@code Matcher.find} loop over the unit text.
     */
    static void match(TextKind kind, int fileId, int startLine, int startCol, String text,
            SearchSpec spec, List<TextOccurrence> out) {
        if (spec.fastPathEligible()) {
            matchLiteral(kind, fileId, startLine, startCol, text, spec.literal(), out);
        } else {
            matchRegex(kind, fileId, startLine, startCol, text, spec, out);
        }
    }

    // The literal fast path: indexOf scan, one occurrence per distinct matched line. A match's line/col
    // is recovered from the unit's start and the match offset (newlines before it).
    private static void matchLiteral(TextKind kind, int fileId, int startLine, int startCol, String text,
            String query, List<TextOccurrence> out) {
        int from = 0;
        int idx;
        int lastLine = -1;
        while ((idx = text.indexOf(query, from)) >= 0) {
            int line = startLine;
            int lineStart = 0;
            for (int i = 0; i < idx; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            if (line != lastLine) {
                int col = (line == startLine) ? startCol + (idx - lineStart) : (idx - lineStart) + 1;
                out.add(new TextOccurrence(fileId, line, col, kind, snippet(text, lineStart)));
                lastLine = line;
            }
            from = idx + Math.max(1, query.length());
        }
    }

    // The regex path: a single Matcher over the unit text. find() handles the zero-width advance (no
    // infinite loop on `^`/`a?`); the same per-line dedup applies. MULTILINE makes `^`/`$` anchor per
    // physical line within the unit.
    private static void matchRegex(TextKind kind, int fileId, int startLine, int startCol, String text,
            SearchSpec spec, List<TextOccurrence> out) {
        java.util.regex.Matcher m = spec.matcher(text);
        int lastLine = -1;
        while (m.find()) {
            int idx = m.start();
            int line = startLine;
            int lineStart = 0;
            for (int i = 0; i < idx; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            if (line != lastLine) {
                int col = (line == startLine) ? startCol + (idx - lineStart) : (idx - lineStart) + 1;
                out.add(new TextOccurrence(fileId, line, col, kind, snippet(text, lineStart)));
                lastLine = line;
            }
        }
    }

    private static String snippet(String text, int lineStart) {
        int end = text.indexOf('\n', lineStart);
        return end < 0 ? text.substring(lineStart) : text.substring(lineStart, end);
    }

    // Binary search the (ascending) file directory for fileId; returns its directory index or -1.
    private int indexOfFile(int fileId) {
        int lo = 0;
        int hi = nFiles - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = seg.get(I, fileDirOff + (long) mid * 4);
            if (k < fileId) {
                lo = mid + 1;
            } else if (k > fileId) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private TextUnit unitAt(int u) {
        return new TextUnit(KINDS[col(C_KIND, u)], col(C_START_LINE, u), col(C_START_COL, u),
                col(C_END_LINE, u), col(C_END_COL, u), textOf(u));
    }

    private String textOf(int u) {
        return StringArena.read(seg, arenaOff, col(C_TEXT_REF, u));
    }

    private int col(int c, int u) {
        return seg.get(I, unitColOff + ((long) c * nUnits + u) * 4);
    }

    private static void setCol(MemorySegment seg, long colOff, int n, int c, int u, int value) {
        seg.set(I, colOff + ((long) c * n + u) * 4, value);
    }
}
