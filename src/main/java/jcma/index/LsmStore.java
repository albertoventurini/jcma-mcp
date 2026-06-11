package jcma.index;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.zip.CRC32;

import jcma.obs.Metrics;

/**
 * The LSM index store (PRD §5.1) — the production form of M0 {@code SpikeD.Store}. An immutable,
 * memory-mapped <b>base</b> (the {@link SymbolStore} + {@link Csr} + {@link TrigramIndex} triple) is
 * read in place and never edited; per-file changes land in a small, queryable in-memory
 * <b>overlay</b>; queries return {@code base ∪ overlay − tombstones}; and {@link #compact()}
 * periodically folds the overlay into a fresh base.
 *
 * <p><b>Overlay (indexed, moniker space).</b> A new file introduces monikers with no base id yet,
 * and an edit must not rewrite the base — so the overlay is keyed by moniker and holds, per edited
 * file, its {@link Symbol} declarations and its outgoing {@link MonikerEdge}s. It is <em>indexed</em>
 * by src and dst (M0 found a naive linear merge ~340× slower), so a merged {@code fwd}/{@code rev}
 * is {@code O(degree)}. A {@link FileIndex#deleted tombstone} supersedes a file's base rows with
 * nothing. A base edge is attributed to its <em>src symbol's declaring file</em> (no separate owner
 * column needed): the live view suppresses base rows whose owning file is in the overlay.
 *
 * <p><b>Durability (decided, Task-06).</b> Each edit is appended to a length-framed, CRC32-checksummed
 * {@code overlay.log} and <em>flushed to the OS</em> (survives a process crash; ~µs cost). On
 * {@link #open} the log is replayed to rebuild the overlay; a torn trailing record (crash mid-write)
 * is detected by its frame/checksum, dropped, and truncated, never fatal. The log is a
 * <em>validated cache</em>, not the source of truth: correctness rests on filesystem freshness
 * (Task-08) re-indexing against the actual files, so a lost or truncated log only costs re-parsing,
 * never a wrong answer. {@code fsync} is reserved for compaction's atomic base swap.
 *
 * <p><b>Compaction.</b> Rewrites all three base segments together — symbols, edges, <em>and the
 * trigram</em> so name search is correct immediately after — via temp-write + {@code fsync} +
 * atomic rename, then clears the overlay + log. Ids may be reassigned (moniker-stable identity).
 * <b>Phantom nodes</b> (an edge whose endpoint's declaring file was deleted; {@code fileId == -1})
 * are preserved so dangling references survive. The trigger is a swappable {@link CompactionPolicy}
 * checked after each edit; {@link #compact()} also forces it.
 */
public final class LsmStore implements AutoCloseable {

    /** File name of the durable overlay log within an index directory. */
    public static final String OVERLAY_LOG = "overlay.log";

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    private final Path symPath;
    private final Path edgePath;
    private final Path triPath;
    private final Path logPath;
    private final CompactionPolicy policy;
    private final Metrics metrics;

    // The immutable mmap'd base, or null when the index is still cold (no compaction yet).
    private SymbolStore baseSym;
    private Csr baseCsr;
    private TrigramIndex baseTri;

    // The in-memory overlay (moniker space). `edited` is the per-file source of truth (its keys are
    // the tombstoned/superseded files); the adjacency maps are its query index.
    private final LinkedHashMap<Integer, FileIndex> edited = new LinkedHashMap<>();
    private final Map<String, Symbol> ovSymbols = new HashMap<>();
    private final Map<String, Set<MonikerEdge>> ovFwd = new HashMap<>();
    private final Map<String, Set<MonikerEdge>> ovRev = new HashMap<>();

    // The durable overlay log, opened for append; `logBytes` mirrors its on-disk size.
    private DataOutputStream logOut;
    private long logBytes;

    // True for a read-only open: every mutation stays in this process's heap overlay; the durable log
    // and base segments are never written, so a query process can coexist with a live writer.
    private final boolean readOnly;

    private LsmStore(Path indexDir, CompactionPolicy policy, Metrics metrics, boolean readOnly) {
        this.symPath = indexDir.resolve(SymbolStore.FILE_NAME);
        this.edgePath = indexDir.resolve(Csr.FILE_NAME);
        this.triPath = indexDir.resolve(TrigramIndex.FILE_NAME);
        this.logPath = indexDir.resolve(OVERLAY_LOG);
        this.policy = policy;
        this.metrics = metrics;
        this.readOnly = readOnly;
    }

    /** Open the index at {@code indexDir} with the default policy ({@link CompactionPolicy#relativeToBase}). */
    public static LsmStore open(Path indexDir) throws IOException {
        return open(indexDir, CompactionPolicy.relativeToBase(1.0));
    }

    /** Open with the given policy and no metrics ({@link Metrics#noop()}). */
    public static LsmStore open(Path indexDir, CompactionPolicy policy) throws IOException {
        return open(indexDir, policy, Metrics.noop());
    }

    /**
     * Open the index at {@code indexDir} <b>read-only</b> (M2): mmap the base + replay the overlay log
     * into the in-memory overlay, but never append to the log, never truncate a torn tail, and reject
     * {@link #compact()}. {@link #applyEdit} mutates only this process's heap overlay — nothing reaches
     * disk — so a query process can observe a live writer's persisted graph (and lazily resolve into its
     * own heap) without contending for the {@link jcma.workspace.IndexLock write lock} or risking
     * corruption.
     */
    public static LsmStore openReadOnly(Path indexDir, Metrics metrics) throws IOException {
        Files.createDirectories(indexDir);
        LsmStore store = new LsmStore(indexDir, CompactionPolicy.manual(), metrics, true);
        store.loadBase();
        store.replayLog(); // rebuilds the overlay from disk; never truncates (see replayLog)
        // Deliberately no openLogForAppend: a read-only store never opens the log for writing.
        return store;
    }

    /**
     * Open the index at {@code indexDir}: mmap the base segments if present (else an empty base),
     * then replay {@code overlay.log} to rebuild the in-memory overlay (dropping any torn tail).
     * Compaction + reopen-replay timings are recorded into {@code metrics}.
     */
    public static LsmStore open(Path indexDir, CompactionPolicy policy, Metrics metrics) throws IOException {
        Files.createDirectories(indexDir);
        LsmStore store = new LsmStore(indexDir, policy, metrics, false);
        store.loadBase();
        store.replayLog();
        store.openLogForAppend();
        return store;
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Apply one file's full structural content, replacing any prior content for its
     * {@link FileIndex#fileId()} (empty = delete/tombstone). Appends + flushes the durable log entry,
     * then compacts if the {@link CompactionPolicy} says so.
     */
    public void applyEdit(FileIndex content) throws IOException {
        index(content);
        if (readOnly) {
            return; // heap-only: a read-only store never writes the durable log nor compacts
        }
        writeRecord(content);
        if (policy.shouldCompact(logBytes, baseBytes(), edited.size())) {
            compact();
        }
    }

    /** Update the in-memory overlay for {@code c}, replacing this file's previous overlay content. */
    private void index(FileIndex c) {
        FileIndex old = edited.get(c.fileId());
        if (old != null) {
            for (Symbol s : old.symbols()) {
                ovSymbols.remove(s.moniker());
            }
            for (MonikerEdge e : old.edges()) {
                removeOv(ovFwd, e.src(), e);
                removeOv(ovRev, e.dst(), e);
            }
        }
        edited.put(c.fileId(), c);
        for (Symbol s : c.symbols()) {
            ovSymbols.put(s.moniker(), s);
        }
        for (MonikerEdge e : c.edges()) {
            addOv(ovFwd, e.src(), e);
            addOv(ovRev, e.dst(), e);
        }
    }

    // ------------------------------------------------------------------ queries

    /** True if a symbol with {@code moniker} is declared in the live view (excludes phantoms). */
    public boolean contains(String moniker) {
        if (ovSymbols.containsKey(moniker)) {
            return true;
        }
        if (baseSym != null) {
            OptionalInt id = baseSym.idOf(moniker);
            if (id.isPresent()) {
                int fid = baseSym.symbol(id.getAsInt()).fileId();
                return fid >= 0 && !edited.containsKey(fid);
            }
        }
        return false;
    }

    /**
     * The live declaration for {@code moniker} — the overlay's if the file is overlaid, else the base
     * row whose file is not overlaid (excludes phantoms / overlaid-away rows). Mirrors {@link
     * #contains}; Tier-2 position-mode {@code find_references} uses it to map a resolved decl's moniker
     * back to its graph {@link Symbol}.
     */
    public Optional<Symbol> symbol(String moniker) {
        Symbol overlaid = ovSymbols.get(moniker);
        if (overlaid != null) {
            return Optional.of(overlaid);
        }
        if (baseSym != null) {
            OptionalInt id = baseSym.idOf(moniker);
            if (id.isPresent()) {
                Symbol s = baseSym.symbol(id.getAsInt());
                if (s.fileId() >= 0 && !edited.containsKey(s.fileId())) {
                    return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    /** Outgoing edges of {@code moniker} over {@code base ∪ overlay − tombstones}. */
    public Set<MonikerEdge> fwd(String moniker) {
        Set<MonikerEdge> out = new HashSet<>();
        if (baseSym != null && baseCsr != null) {
            OptionalInt id = baseSym.idOf(moniker);
            if (id.isPresent()) {
                int sid = id.getAsInt();
                // All of a symbol's outgoing edges are owned by its own file; one check suffices.
                if (!edited.containsKey(baseSym.symbol(sid).fileId())) {
                    for (Csr.Edge e : baseCsr.fwd(sid)) {
                        out.add(toMoniker(e));
                    }
                }
            }
        }
        out.addAll(ovFwd.getOrDefault(moniker, Set.of()));
        return out;
    }

    /** Incoming edges of {@code moniker} over {@code base ∪ overlay − tombstones}. */
    public Set<MonikerEdge> rev(String moniker) {
        Set<MonikerEdge> out = new HashSet<>();
        if (baseSym != null && baseCsr != null) {
            OptionalInt id = baseSym.idOf(moniker);
            if (id.isPresent()) {
                // Incoming edges have varying srcs → varying owner files; check each.
                for (Csr.Edge e : baseCsr.rev(id.getAsInt())) {
                    if (!edited.containsKey(baseSym.symbol(e.src()).fileId())) {
                        out.add(toMoniker(e));
                    }
                }
            }
        }
        out.addAll(ovRev.getOrDefault(moniker, Set.of()));
        return out;
    }

    /**
     * Name search over the live view: the mmap'd trigram index (base) unioned with a scan of the
     * overlay's symbols, so results are identical before and after a compaction. Phantoms
     * ({@code fileId < 0}) and base symbols whose file is overlaid are excluded.
     */
    public List<Symbol> search(String query) {
        List<Symbol> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Symbol s : ovSymbols.values()) {
            if (s.name() != null && s.name().contains(query) && seen.add(s.moniker())) {
                out.add(s);
            }
        }
        if (baseSym != null && baseTri != null) {
            for (int id : baseTri.searchSymbols(query)) {
                Symbol s = baseSym.symbol(id);
                if (s.fileId() >= 0 && !edited.containsKey(s.fileId()) && seen.add(s.moniker())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * The live declaration set — base symbols whose file is not overlaid, unioned with the overlay's
     * symbols (overlay wins on moniker). Phantoms ({@code fileId < 0}) are excluded. Tier-2 uses it to
     * map a resolved declaration's {@code file:line} back to its graph moniker.
     */
    public List<Symbol> liveSymbols() {
        Map<String, Symbol> byMoniker = new HashMap<>();
        if (baseSym != null) {
            for (int id = 0; id < baseSym.size(); id++) {
                Symbol s = baseSym.symbol(id);
                if (s.fileId() >= 0 && !edited.containsKey(s.fileId())) {
                    byMoniker.put(s.moniker(), s);
                }
            }
        }
        for (Symbol s : ovSymbols.values()) {
            if (s.fileId() >= 0) {
                byMoniker.put(s.moniker(), s);
            }
        }
        return new ArrayList<>(byMoniker.values());
    }

    /**
     * The live declarations of {@code fileId}: its overlay slice if the file is overlaid (an edit or
     * an empty tombstone), else its base rows. Tier-1 freshness (task-11c) reads this <em>before</em>
     * an {@code applyEdit} to diff a file's old vs. new node set. Phantoms are never file-owned, so
     * they cannot appear here.
     */
    public List<Symbol> symbolsOf(int fileId) {
        FileIndex ov = edited.get(fileId);
        if (ov != null) {
            return new ArrayList<>(ov.symbols());
        }
        List<Symbol> out = new ArrayList<>();
        if (baseSym != null) {
            for (int id = 0; id < baseSym.size(); id++) {
                Symbol s = baseSym.symbol(id);
                if (s.fileId() == fileId) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Number of files currently held in the overlay (edited or tombstoned since the last compaction). */
    public int overlayFileCount() {
        return edited.size();
    }

    /** True if the overlay is empty (everything is in the base; nothing pending). */
    public boolean isCompacted() {
        return edited.isEmpty();
    }

    // ------------------------------------------------------------------ compaction

    /** Force a compaction: fold the overlay into a fresh base (all three segments) and clear the log. */
    public void compact() throws IOException {
        if (readOnly) {
            throw new IllegalStateException("compaction is not allowed on a read-only LsmStore");
        }
        long startNanos = System.nanoTime();
        // 1. Gather the live symbol set + edge set: base content (minus overlaid files) ∪ overlay.
        Map<String, Symbol> symByMoniker = new HashMap<>();
        List<MonikerEdge> allEdges = new ArrayList<>();
        if (baseSym != null && baseCsr != null) {
            for (int id = 0; id < baseSym.size(); id++) {
                Symbol s = baseSym.symbol(id);
                if (edited.containsKey(s.fileId())) {
                    continue; // this file is superseded by the overlay
                }
                if (s.fileId() >= 0) {
                    symByMoniker.put(s.moniker(), s);
                }
                for (Csr.Edge e : baseCsr.fwd(id)) {
                    allEdges.add(toMoniker(e));
                }
            }
        }
        for (FileIndex c : edited.values()) {
            for (Symbol s : c.symbols()) {
                symByMoniker.put(s.moniker(), s);
            }
            allEdges.addAll(c.edges());
        }
        // 2. Phantom nodes for dangling endpoints (declared nowhere we parse; fileId == -1).
        for (MonikerEdge e : allEdges) {
            symByMoniker.computeIfAbsent(e.src(), LsmStore::phantom);
            symByMoniker.computeIfAbsent(e.dst(), LsmStore::phantom);
        }

        // 3. Assign ids by sorting on moniker (mirrors SymbolStore.write), translate edges to id space.
        List<Symbol> symList = new ArrayList<>(symByMoniker.values());
        symList.sort(Comparator.comparing(Symbol::moniker));
        Map<String, Integer> idByMoniker = new HashMap<>(symList.size() * 2);
        List<TrigramIndex.Entry> entries = new ArrayList<>(symList.size());
        for (int i = 0; i < symList.size(); i++) {
            Symbol s = symList.get(i);
            idByMoniker.put(s.moniker(), i);
            entries.add(new TrigramIndex.Entry(s.name() == null ? "" : s.name(), i));
        }
        List<Csr.Edge> idEdges = new ArrayList<>(allEdges.size());
        for (MonikerEdge e : allEdges) {
            idEdges.add(new Csr.Edge(idByMoniker.get(e.src()), idByMoniker.get(e.dst()), e.type(), e.occurrence()));
        }

        // 4. Write the fresh segments to temp files (each fsync's via seg.force()), then atomically
        //    swap them in. Close the old mmaps first so the rename can replace them on any platform.
        Path symTmp = tmp(symPath);
        Path edgeTmp = tmp(edgePath);
        Path triTmp = tmp(triPath);
        SymbolStore.write(symTmp, symList);
        Csr.write(edgeTmp, symList.size(), idEdges);
        TrigramIndex.write(triTmp, entries);
        closeBase();
        atomicReplace(symTmp, symPath);
        atomicReplace(edgeTmp, edgePath);
        atomicReplace(triTmp, triPath);
        loadBase();

        // 5. The overlay is now in the base; clear it and reset the durable log.
        edited.clear();
        ovSymbols.clear();
        ovFwd.clear();
        ovRev.clear();
        logOut.close();
        Files.deleteIfExists(logPath);
        openLogForAppend();
        logBytes = 0;

        metrics.timer("compaction").record(System.nanoTime() - startNanos);
    }

    private static Symbol phantom(String moniker) {
        return new Symbol(moniker, SymbolKind.UNKNOWN, 0, null, -1, Range.NONE, "", null);
    }

    private MonikerEdge toMoniker(Csr.Edge e) {
        return new MonikerEdge(baseSym.monikerOf(e.src()), baseSym.monikerOf(e.dst()), e.type(), e.occurrence());
    }

    // ------------------------------------------------------------------ base segment lifecycle

    private void loadBase() throws IOException {
        if (Files.exists(symPath)) {
            baseSym = SymbolStore.load(symPath);
            baseCsr = Csr.load(edgePath);
            baseTri = TrigramIndex.load(triPath);
        } else {
            baseSym = null;
            baseCsr = null;
            baseTri = null;
        }
    }

    private void closeBase() {
        if (baseSym != null) {
            baseSym.close();
            baseSym = null;
        }
        if (baseCsr != null) {
            baseCsr.close();
            baseCsr = null;
        }
        if (baseTri != null) {
            baseTri.close();
            baseTri = null;
        }
    }

    private long baseBytes() throws IOException {
        long total = 0;
        if (Files.exists(symPath)) {
            total += Files.size(symPath);
        }
        if (Files.exists(edgePath)) {
            total += Files.size(edgePath);
        }
        if (Files.exists(triPath)) {
            total += Files.size(triPath);
        }
        return total;
    }

    private static Path tmp(Path p) {
        return p.resolveSibling(p.getFileName() + ".tmp");
    }

    private static void atomicReplace(Path tmp, Path dst) throws IOException {
        Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ durable overlay log

    private void openLogForAppend() throws IOException {
        logOut = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
    }

    /** Append a length-framed, CRC32'd record for {@code c} and flush it to the OS (no fsync). */
    private void writeRecord(FileIndex c) throws IOException {
        byte[] payload = serialize(c);
        CRC32 crc = new CRC32();
        crc.update(payload);
        logOut.writeInt(payload.length);
        logOut.write(payload);
        logOut.writeInt((int) crc.getValue());
        logOut.flush();
        logBytes += 4L + payload.length + 4L;
    }

    /**
     * Replay {@code overlay.log} into the overlay, stopping at the first torn/CRC-failed record (a
     * crash mid-write), then truncate the file to the last good boundary so future appends stay
     * contiguous and replayable.
     */
    private void replayLog() throws IOException {
        if (!Files.exists(logPath)) {
            logBytes = 0;
            return;
        }
        long startNanos = System.nanoTime();
        byte[] data = Files.readAllBytes(logPath);
        int pos = 0;
        int validLen = 0;
        int records = 0;
        while (pos + 4 <= data.length) {
            int len = readIntBE(data, pos);
            int end = pos + 4 + len + 4; // length field + payload + CRC
            if (len < 0 || end > data.length) {
                break; // incomplete trailing record
            }
            CRC32 crc = new CRC32();
            crc.update(data, pos + 4, len);
            if ((int) crc.getValue() != readIntBE(data, pos + 4 + len)) {
                break; // corrupt trailing record
            }
            index(deserialize(data, pos + 4, len));
            pos = end;
            validLen = end;
            records++;
        }
        metrics.counter("replay.records").add(records);
        metrics.timer("replay").record(System.nanoTime() - startNanos);
        // A read-only store must not write — leave a torn tail in place (it was dropped from the
        // overlay above either way); the owning writer will truncate it on its next open.
        if (validLen != data.length && !readOnly) {
            try (FileChannel ch = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                ch.truncate(validLen);
            }
        }
        logBytes = validLen;
    }

    private static byte[] serialize(FileIndex c) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeInt(c.fileId());
        p.writeInt(c.symbols().size());
        for (Symbol s : c.symbols()) {
            writeSymbol(p, s);
        }
        p.writeInt(c.edges().size());
        for (MonikerEdge e : c.edges()) {
            writeEdge(p, e);
        }
        p.flush();
        return baos.toByteArray();
    }

    private static FileIndex deserialize(byte[] data, int off, int len) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, off, len));
        int fileId = in.readInt();
        int ns = in.readInt();
        List<Symbol> symbols = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            symbols.add(readSymbol(in));
        }
        int ne = in.readInt();
        List<MonikerEdge> edges = new ArrayList<>(ne);
        for (int i = 0; i < ne; i++) {
            edges.add(readEdge(in));
        }
        return new FileIndex(fileId, symbols, edges);
    }

    private static void writeSymbol(DataOutputStream p, Symbol s) throws IOException {
        writeStr(p, s.moniker());
        p.writeInt(s.kind().ordinal());
        p.writeInt(s.flags());
        writeOptStr(p, s.enclosingMoniker());
        p.writeInt(s.fileId());
        Range r = s.range();
        p.writeInt(r.startLine());
        p.writeInt(r.startCol());
        p.writeInt(r.endLine());
        p.writeInt(r.endCol());
        writeOptStr(p, s.name());
        writeOptStr(p, s.signature());
    }

    private static Symbol readSymbol(DataInputStream in) throws IOException {
        String moniker = readStr(in);
        SymbolKind kind = SymbolKind.byOrdinal(in.readInt());
        int flags = in.readInt();
        String enclosing = readOptStr(in);
        int fileId = in.readInt();
        Range range = new Range(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        String name = readOptStr(in);
        String signature = readOptStr(in);
        return new Symbol(moniker, kind, flags, enclosing, fileId, range, name, signature);
    }

    private static void writeEdge(DataOutputStream p, MonikerEdge e) throws IOException {
        writeStr(p, e.src());
        writeStr(p, e.dst());
        p.writeInt(e.type().ordinal());
        Occurrence o = e.occurrence();
        p.writeInt(o.fileId());
        Range r = o.range();
        p.writeInt(r.startLine());
        p.writeInt(r.startCol());
        p.writeInt(r.endLine());
        p.writeInt(r.endCol());
        p.writeInt(o.enclosingSymbolId());
        p.writeInt(o.role().ordinal());
    }

    private static MonikerEdge readEdge(DataInputStream in) throws IOException {
        String src = readStr(in);
        String dst = readStr(in);
        EdgeType type = EdgeType.byOrdinal(in.readInt());
        int occFile = in.readInt();
        Range range = new Range(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        int enclosing = in.readInt();
        Occurrence.Role role = Occurrence.Role.byOrdinal(in.readInt());
        return new MonikerEdge(src, dst, type, new Occurrence(occFile, range, enclosing, role));
    }

    private static void writeStr(DataOutputStream p, String s) throws IOException {
        byte[] b = s.getBytes(UTF8);
        p.writeInt(b.length);
        p.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, UTF8);
    }

    private static void writeOptStr(DataOutputStream p, String s) throws IOException {
        p.writeBoolean(s != null);
        if (s != null) {
            writeStr(p, s);
        }
    }

    private static String readOptStr(DataInputStream in) throws IOException {
        return in.readBoolean() ? readStr(in) : null;
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    // ------------------------------------------------------------------ overlay adjacency helpers

    private static void addOv(Map<String, Set<MonikerEdge>> adj, String key, MonikerEdge e) {
        adj.computeIfAbsent(key, k -> new HashSet<>()).add(e);
    }

    private static void removeOv(Map<String, Set<MonikerEdge>> adj, String key, MonikerEdge e) {
        Set<MonikerEdge> s = adj.get(key);
        if (s != null) {
            s.remove(e);
            if (s.isEmpty()) {
                adj.remove(key);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (logOut != null) {
            logOut.close();
        }
        closeBase();
    }
}
