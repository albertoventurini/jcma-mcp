package m0;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * M0 Spike D (throwaway) — incremental mmap format prototype (PRD §5.1). Proves the CSR graph can
 * be mutated via LSM base + overlay + compaction, per-file, without rescanning. FFM-mapped base
 * (Arena + FileChannel.map -> MemorySegment), CSR both directions, moniker-stable identity.
 *
 * Run: java --enable-native-access=ALL-UNNAMED -cp target/m0-spike.jar m0.SpikeD <outDir>
 */
public final class SpikeD {

    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    static final long MAGIC = 0x4A434D4153454731L; // "JCMASEG1"
    static final int VERSION = 1;
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    record Edge(String src, String dst, int file) {}
    record FileContent(Set<String> monikers, List<Edge> edges) {
        static FileContent empty() { return new FileContent(Set.of(), List.of()); }
    }

    // ------------------------------------------------------------ FFM base segment

    /** Immutable, memory-mapped columnar base: string arena + symbol columns + fwd/rev CSR. */
    static final class Base implements AutoCloseable {
        final Arena arena;
        final MemorySegment seg;
        final int n;                 // symbols
        final long arenaOff, symColOff, fwdOff, revOff;
        final String[] monikerById;
        final int[] fileById;
        final Map<String, Integer> idByMoniker;

        private Base(Arena arena, MemorySegment seg) {
            this.arena = arena;
            this.seg = seg;
            this.n = seg.get(I, 12);
            this.arenaOff = seg.get(L, 24);
            this.symColOff = seg.get(L, 32);
            this.fwdOff = seg.get(L, 40);
            this.revOff = seg.get(L, 48);
            this.monikerById = new String[n];
            this.fileById = new int[n];
            this.idByMoniker = new HashMap<>(n * 2);
            long fileColOff = symColOff + (long) n * 4;
            for (int i = 0; i < n; i++) {
                long mOff = arenaOff + seg.get(I, symColOff + (long) i * 4);
                int len = seg.get(I, mOff);
                byte[] b = new byte[len];
                MemorySegment.copy(seg, mOff + 4, MemorySegment.ofArray(b), 0, len);
                String m = new String(b, UTF8);
                monikerById[i] = m;
                fileById[i] = seg.get(I, fileColOff + (long) i * 4);
                idByMoniker.put(m, i);
            }
        }

        static Base load(Path path) throws IOException {
            long size = Files.size(path);
            Arena a = Arena.ofShared();
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, a);
                if (seg.get(L, 0) != MAGIC) throw new IOException("bad magic");
                return new Base(a, seg);
            }
        }

        static void write(Path path, Map<String, Integer> symFile, List<Edge> edges) throws IOException {
            List<String> monikers = new ArrayList<>(symFile.keySet());
            monikers.sort(null);
            int n = monikers.size(), m = edges.size();
            Map<String, Integer> id = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) id.put(monikers.get(i), i);

            byte[][] mb = new byte[n][];
            int[] monikerOff = new int[n];
            long arenaLen = 0;
            for (int i = 0; i < n; i++) { mb[i] = monikers.get(i).getBytes(UTF8); monikerOff[i] = (int) arenaLen; arenaLen += 4 + mb[i].length; }

            int[] fwdCount = new int[n], revCount = new int[n];
            for (Edge e : edges) { fwdCount[id.get(e.src())]++; revCount[id.get(e.dst())]++; }
            int[] fwdOffA = prefix(fwdCount), revOffA = prefix(revCount);
            int[] fwdTarget = new int[m], fwdEdgeFile = new int[m], revTarget = new int[m], revEdgeFile = new int[m];
            int[] fc = fwdOffA.clone(), rc = revOffA.clone();
            for (Edge e : edges) {
                int s = id.get(e.src()), d = id.get(e.dst());
                int fi = fc[s]++; fwdTarget[fi] = d; fwdEdgeFile[fi] = e.file();
                int ri = rc[d]++; revTarget[ri] = s; revEdgeFile[ri] = e.file();
            }

            long header = 64;
            long arenaOff = header;
            long symColOff = arenaOff + arenaLen;
            long fwdOff = symColOff + (long) n * 4 * 2;
            long fwdSize = (long) (n + 1) * 4 + (long) m * 4 * 2;
            long revOff = fwdOff + fwdSize;
            long total = revOff + (long) (n + 1) * 4 + (long) m * 4 * 2;

            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Arena a = Arena.ofShared()) {
                MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, total, a);
                seg.set(L, 0, MAGIC);
                seg.set(I, 8, VERSION);
                seg.set(I, 12, n);
                seg.set(I, 16, m);
                seg.set(I, 20, m);
                seg.set(L, 24, arenaOff);
                seg.set(L, 32, symColOff);
                seg.set(L, 40, fwdOff);
                seg.set(L, 48, revOff);
                seg.set(L, 56, arenaLen);
                long p = arenaOff;
                for (int i = 0; i < n; i++) {
                    seg.set(I, p, mb[i].length); p += 4;
                    MemorySegment.copy(MemorySegment.ofArray(mb[i]), 0, seg, p, mb[i].length); p += mb[i].length;
                }
                for (int i = 0; i < n; i++) seg.set(I, symColOff + (long) i * 4, monikerOff[i]);
                long fileColOff = symColOff + (long) n * 4;
                for (int i = 0; i < n; i++) seg.set(I, fileColOff + (long) i * 4, fileById(symFile, monikers.get(i)));
                writeInts(seg, fwdOff, fwdOffA);
                long ft = fwdOff + (long) (n + 1) * 4; writeInts(seg, ft, fwdTarget);
                writeInts(seg, ft + (long) m * 4, fwdEdgeFile);
                writeInts(seg, revOff, revOffA);
                long rt = revOff + (long) (n + 1) * 4; writeInts(seg, rt, revTarget);
                writeInts(seg, rt + (long) m * 4, revEdgeFile);
                seg.force();
            }
        }

        // CSR readers (id space). edgeFile = originating file of the edge.
        int fwdStart(int id) { return seg.get(I, fwdOff + (long) id * 4); }
        int fwdEnd(int id)   { return seg.get(I, fwdOff + (long) (id + 1) * 4); }
        int fwdTarget(int k) { return seg.get(I, fwdOff + (long) (n + 1) * 4 + (long) k * 4); }
        int fwdFile(int k)   { return seg.get(I, fwdOff + (long) (n + 1) * 4 + (long) m() * 4 + (long) k * 4); }
        int revStart(int id) { return seg.get(I, revOff + (long) id * 4); }
        int revEnd(int id)   { return seg.get(I, revOff + (long) (id + 1) * 4); }
        int revTarget(int k) { return seg.get(I, revOff + (long) (n + 1) * 4 + (long) k * 4); }
        int revFile(int k)   { return seg.get(I, revOff + (long) (n + 1) * 4 + (long) m() * 4 + (long) k * 4); }
        int m() { return seg.get(I, 16); }

        @Override public void close() { arena.close(); }
    }

    static int fileById(Map<String, Integer> symFile, String moniker) { return symFile.get(moniker); }
    static int[] prefix(int[] count) {
        int[] off = new int[count.length + 1];
        for (int i = 0; i < count.length; i++) off[i + 1] = off[i] + count[i];
        return off;
    }
    static void writeInts(MemorySegment seg, long off, int[] a) {
        for (int i = 0; i < a.length; i++) seg.set(I, off + (long) i * 4, a[i]);
    }

    // ------------------------------------------------------------ store = base ∪ overlay − tombstones

    static final class Store implements AutoCloseable {
        Base base;
        final Path basePath, logPath;
        final Map<Integer, FileContent> edited = new LinkedHashMap<>(); // edited/deleted files -> current content
        // Indexed overlay (the real §5.1 overlay is queryable, not a linear scan): O(degree) merge.
        final Map<String, Map<String, Integer>> ovFwd = new HashMap<>(); // src -> dst -> mult
        final Map<String, Map<String, Integer>> ovRev = new HashMap<>(); // dst -> src -> mult
        final Map<String, Integer> ovSymFile = new HashMap<>();          // overlay-declared moniker -> fileId
        DataOutputStream log;

        Store(Path basePath, Path logPath) throws IOException {
            this.basePath = basePath; this.logPath = logPath;
            this.base = Base.load(basePath);
            this.log = new DataOutputStream(Files.newOutputStream(logPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        }

        void applyEdit(int fileId, FileContent c) throws IOException { index(fileId, c); writeLog(fileId, c); }

        /** Update the in-memory indexed overlay for a file's new content (replacing prior content). */
        private void index(int fileId, FileContent c) {
            FileContent old = edited.get(fileId);
            if (old != null) {
                for (Edge e : old.edges()) { dec(ovFwd, e.src(), e.dst()); dec(ovRev, e.dst(), e.src()); }
                for (String mo : old.monikers()) ovSymFile.remove(mo, fileId);
            }
            edited.put(fileId, c);
            for (Edge e : c.edges()) { inc(ovFwd, e.src(), e.dst()); inc(ovRev, e.dst(), e.src()); }
            for (String mo : c.monikers()) ovSymFile.put(mo, fileId);
        }
        private void clearOverlay() { edited.clear(); ovFwd.clear(); ovRev.clear(); ovSymFile.clear(); }
        private static void inc(Map<String, Map<String, Integer>> a, String x, String y) {
            a.computeIfAbsent(x, k -> new HashMap<>()).merge(y, 1, Integer::sum);
        }
        private static void dec(Map<String, Map<String, Integer>> a, String x, String y) {
            Map<String, Integer> m = a.get(x); if (m == null) return;
            Integer v = m.get(y); if (v == null) return;
            if (v <= 1) { m.remove(y); if (m.isEmpty()) a.remove(x); } else m.put(y, v - 1);
        }

        Set<String> fwd(String m) {
            Set<String> out = new HashSet<>();
            Integer id = base.idByMoniker.get(m);
            if (id != null)
                for (int k = base.fwdStart(id); k < base.fwdEnd(id); k++)
                    if (!edited.containsKey(base.fwdFile(k))) out.add(base.monikerById[base.fwdTarget(k)]);
            out.addAll(ovFwd.getOrDefault(m, Map.of()).keySet());
            return out;
        }

        Set<String> rev(String m) {
            Set<String> out = new HashSet<>();
            Integer id = base.idByMoniker.get(m);
            if (id != null)
                for (int k = base.revStart(id); k < base.revEnd(id); k++)
                    if (!edited.containsKey(base.revFile(k))) out.add(base.monikerById[base.revTarget(k)]);
            out.addAll(ovRev.getOrDefault(m, Map.of()).keySet());
            return out;
        }

        boolean lookup(String m) {
            Integer id = base.idByMoniker.get(m);
            if (id != null && base.fileById[id] >= 0 && !edited.containsKey(base.fileById[id])) return true; // >=0 excludes phantoms
            return ovSymFile.containsKey(m);
        }

        void compact() throws IOException {
            Map<String, Integer> symFile = new TreeMap<>();
            List<Edge> edges = new ArrayList<>();
            // base content, minus edited files (superseded)
            for (int id = 0; id < base.n; id++) {
                if (!edited.containsKey(base.fileById[id])) symFile.put(base.monikerById[id], base.fileById[id]);
                for (int k = base.fwdStart(id); k < base.fwdEnd(id); k++) {
                    int ef = base.fwdFile(k);
                    if (!edited.containsKey(ef)) edges.add(new Edge(base.monikerById[id], base.monikerById[base.fwdTarget(k)], ef));
                }
            }
            // overlay content
            for (Map.Entry<Integer, FileContent> en : edited.entrySet()) {
                for (String mo : en.getValue().monikers()) symFile.put(mo, en.getKey());
                edges.addAll(en.getValue().edges());
            }
            // phantom nodes for dangling edge endpoints (symbol whose declaring file was deleted —
            // the edge survives as an unconfirmed/dangling ref; moniker exists, no declaration). fileId = -1.
            for (Edge e : edges) { symFile.putIfAbsent(e.src(), -1); symFile.putIfAbsent(e.dst(), -1); }
            Path tmp = basePath.resolveSibling("base.seg.tmp");
            Base.write(tmp, symFile, edges);
            base.close();
            Files.move(tmp, basePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            base = Base.load(basePath);
            clearOverlay();
            log.close();
            Files.deleteIfExists(logPath);
            log = new DataOutputStream(Files.newOutputStream(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        }

        private void writeLog(int fileId, FileContent c) throws IOException {
            log.writeInt(fileId);
            log.writeInt(c.monikers().size());
            for (String s : c.monikers()) writeStr(s);
            log.writeInt(c.edges().size());
            for (Edge e : c.edges()) { writeStr(e.src()); writeStr(e.dst()); log.writeInt(e.file()); }
            log.flush();
        }
        private void writeStr(String s) throws IOException { byte[] b = s.getBytes(UTF8); log.writeInt(b.length); log.write(b); }

        /** Replay the overlay log over base (crash-recovery / reopen demo). */
        static Map<Integer, FileContent> replayLog(Path logPath) throws IOException {
            Map<Integer, FileContent> edited = new LinkedHashMap<>();
            if (!Files.exists(logPath) || Files.size(logPath) == 0) return edited;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(logPath))) {
                while (in.available() > 0) {
                    int fileId = in.readInt();
                    int nm = in.readInt(); Set<String> ms = new HashSet<>();
                    for (int i = 0; i < nm; i++) ms.add(readStr(in));
                    int ne = in.readInt(); List<Edge> es = new ArrayList<>();
                    for (int i = 0; i < ne; i++) es.add(new Edge(readStr(in), readStr(in), in.readInt()));
                    edited.put(fileId, new FileContent(ms, es));
                }
            }
            return edited;
        }
        private static String readStr(DataInputStream in) throws IOException { byte[] b = new byte[in.readInt()]; in.readFully(b); return new String(b, UTF8); }

        @Override public void close() throws IOException { log.close(); base.close(); }
    }

    // ------------------------------------------------------------ in-memory oracle (ground truth)

    /** Ground truth with incremental adjacency (O(degree) queries) so the test loop is cheap. */
    static final class Oracle {
        final Map<String, Integer> symFile = new HashMap<>();
        final Map<Integer, FileContent> byFile = new HashMap<>();
        final Map<String, Map<String, Integer>> fwdAdj = new HashMap<>(); // src -> dst -> multiplicity
        final Map<String, Map<String, Integer>> revAdj = new HashMap<>();

        void applyFile(int fileId, FileContent c) {
            FileContent old = byFile.get(fileId);
            if (old != null) {
                for (String mo : old.monikers()) symFile.remove(mo, fileId);
                for (Edge e : old.edges()) { dec(fwdAdj, e.src(), e.dst()); dec(revAdj, e.dst(), e.src()); }
            }
            byFile.put(fileId, c);
            for (String mo : c.monikers()) symFile.put(mo, fileId);
            for (Edge e : c.edges()) { inc(fwdAdj, e.src(), e.dst()); inc(revAdj, e.dst(), e.src()); }
        }
        Set<String> fwd(String m) { return new HashSet<>(fwdAdj.getOrDefault(m, Map.of()).keySet()); }
        Set<String> rev(String m) { return new HashSet<>(revAdj.getOrDefault(m, Map.of()).keySet()); }
        boolean lookup(String m) { return symFile.containsKey(m); }
        FileContent contentOf(int fileId) {
            FileContent c = byFile.get(fileId);
            return c == null ? FileContent.empty() : new FileContent(new HashSet<>(c.monikers()), new ArrayList<>(c.edges()));
        }
        Set<Integer> referrers(int fileId) { // files with an edge pointing INTO a symbol declared in fileId
            Set<Integer> refs = new HashSet<>();
            FileContent c = byFile.get(fileId);
            if (c != null) for (String mo : c.monikers())
                for (String src : revAdj.getOrDefault(mo, Map.of()).keySet()) {
                    Integer sf = symFile.get(src);
                    if (sf != null && sf != fileId) refs.add(sf);
                }
            return refs;
        }
        private static void inc(Map<String, Map<String, Integer>> adj, String a, String b) {
            adj.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
        }
        private static void dec(Map<String, Map<String, Integer>> adj, String a, String b) {
            Map<String, Integer> m = adj.get(a);
            if (m == null) return;
            Integer v = m.get(b);
            if (v == null) return;
            if (v <= 1) { m.remove(b); if (m.isEmpty()) adj.remove(a); } else m.put(b, v - 1);
        }
    }

    // ------------------------------------------------------------ driver

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "out");
        Files.createDirectories(out);
        Path basePath = out.resolve("base.seg"), logPath = out.resolve("overlay.log");
        Files.deleteIfExists(logPath);
        Random rnd = new Random(42);

        // synthetic graph
        int F = 2000, S = 50_000, E = 250_000;
        Oracle oracle = new Oracle();
        Map<String, Integer> symFile = new HashMap<>();
        String[] sym = new String[S];
        int[] symFileArr = new int[S];
        for (int i = 0; i < S; i++) { sym[i] = "sym" + i; symFileArr[i] = rnd.nextInt(F); symFile.put(sym[i], symFileArr[i]); }
        List<Edge> edges = new ArrayList<>(E);
        for (int i = 0; i < E; i++) {
            int s = rnd.nextInt(S), d = rnd.nextInt(S);
            edges.add(new Edge(sym[s], sym[d], symFileArr[s])); // edge.file = src's file (outgoing locality)
        }
        // seed oracle grouped by file
        Map<Integer, FileContent> seed = new HashMap<>();
        for (int f = 0; f < F; f++) seed.put(f, new FileContent(new HashSet<>(), new ArrayList<>()));
        for (int i = 0; i < S; i++) seed.get(symFileArr[i]).monikers().add(sym[i]);
        for (Edge e : edges) seed.get(e.file()).edges().add(e);
        for (int f = 0; f < F; f++) oracle.applyFile(f, seed.get(f));

        long t0 = System.nanoTime();
        Base.write(basePath, symFile, edges);
        double writeMs = (System.nanoTime() - t0) / 1e6;
        long baseSize = Files.size(basePath);

        int nextFile = F;
        int mismatches = 0, edits = 0;
        Map<String, Integer> counts = new TreeMap<>();
        try (Store store = new Store(basePath, logPath)) {
            for (int it = 0; it < 200; it++) {
                int kind = rnd.nextInt(4);
                if (kind == 0) { // add new file
                    int f = nextFile++;
                    Set<String> ms = new HashSet<>(); List<Edge> es = new ArrayList<>();
                    for (int j = 0; j < 20; j++) ms.add("new" + f + "_" + j);
                    List<String> mlist = new ArrayList<>(ms);
                    for (int j = 0; j < 40; j++) es.add(new Edge(mlist.get(rnd.nextInt(mlist.size())), sym[rnd.nextInt(S)], f));
                    FileContent c = new FileContent(ms, es);
                    oracle.applyFile(f, c); store.applyEdit(f, c); counts.merge("add", 1, Integer::sum);
                } else if (kind == 1) { // method-body edit: re-emit one file's OUTGOING edges
                    int f = rnd.nextInt(nextFile);
                    FileContent cur = oracle.contentOf(f);
                    List<Edge> es = new ArrayList<>();
                    List<String> mlist = new ArrayList<>(cur.monikers());
                    if (!mlist.isEmpty()) for (int j = 0; j < 30; j++) es.add(new Edge(mlist.get(rnd.nextInt(mlist.size())), sym[rnd.nextInt(S)], f));
                    FileContent c = new FileContent(cur.monikers(), es);
                    oracle.applyFile(f, c); store.applyEdit(f, c); counts.merge("modify-body", 1, Integer::sum);
                } else if (kind == 2) { // API-surface edit: change file + re-emit a bounded sample of referrers
                    int f = rnd.nextInt(nextFile);
                    List<Integer> refs = new ArrayList<>(oracle.referrers(f));
                    java.util.Collections.shuffle(refs, rnd);
                    if (refs.size() > 8) refs = refs.subList(0, 8); // scoped invalidation, not repo-wide
                    FileContent cur = oracle.contentOf(f);
                    Set<String> ms = new HashSet<>(cur.monikers()); ms.add("api" + f + "_" + it); // add a symbol
                    FileContent c = new FileContent(ms, cur.edges());
                    oracle.applyFile(f, c); store.applyEdit(f, c);
                    for (int r : refs) { FileContent rc = oracle.contentOf(r); oracle.applyFile(r, rc); store.applyEdit(r, rc); }
                    counts.merge("modify-api", 1, Integer::sum);
                } else { // delete
                    int f = rnd.nextInt(nextFile);
                    oracle.applyFile(f, FileContent.empty()); store.applyEdit(f, FileContent.empty()); counts.merge("delete", 1, Integer::sum);
                }
                edits++;
                mismatches += check(store, oracle, sym, rnd, 60);
            }

            // overlay-merge overhead: query batch with full overlay vs after compaction
            long q0 = System.nanoTime(); queryBatch(store, sym, rnd, 5000); double overlayMs = (System.nanoTime() - q0) / 1e6;
            int overlaySize = store.edited.size();

            // reopen/replay durability check before compaction
            int replayMismatch;
            { Store reopened = new Store(basePath, logPath);
              for (var en : Store.replayLog(logPath).entrySet()) reopened.index(en.getKey(), en.getValue());
              replayMismatch = check(reopened, oracle, sym, rnd, 200); reopened.close(); }

            long c0 = System.nanoTime(); store.compact(); double compactMs = (System.nanoTime() - c0) / 1e6;
            long compactedSize = Files.size(basePath);
            int postCompactMismatch = check(store, oracle, sym, rnd, 1000);
            long q1 = System.nanoTime(); queryBatch(store, sym, rnd, 5000); double compactedQueryMs = (System.nanoTime() - q1) / 1e6;

            writeReport(out, S, E, F, edits, mismatches, replayMismatch, postCompactMismatch,
                    overlayMs, compactedQueryMs, overlaySize, compactMs, writeMs, baseSize, compactedSize, counts);
            System.out.printf("[SpikeD] edits=%d mismatches=%d replayMismatch=%d postCompactMismatch=%d%n",
                    edits, mismatches, replayMismatch, postCompactMismatch);
            System.out.printf("[SpikeD] overlay(%d) queryBatch %.1fms vs compacted %.1fms (%.2f×); compaction %.0fms%n",
                    overlaySize, overlayMs, compactedQueryMs, overlayMs / compactedQueryMs, compactMs);
        }
    }

    static int check(Store store, Oracle oracle, String[] sym, Random rnd, int samples) {
        int bad = 0;
        for (int i = 0; i < samples; i++) {
            String m = sym[rnd.nextInt(sym.length)];
            if (!store.fwd(m).equals(oracle.fwd(m))) bad++;
            if (!store.rev(m).equals(oracle.rev(m))) bad++;
            if (store.lookup(m) != oracle.lookup(m)) bad++;
        }
        return bad;
    }
    static void queryBatch(Store store, String[] sym, Random rnd, int n) {
        long sink = 0;
        for (int i = 0; i < n; i++) { String m = sym[rnd.nextInt(sym.length)]; sink += store.fwd(m).size() + store.rev(m).size(); }
        if (sink == Long.MIN_VALUE) System.out.print("");
    }

    static void writeReport(Path out, int S, int E, int F, int edits, int mismatches, int replayMismatch,
                            int postCompactMismatch, double overlayMs, double compactedQueryMs, int overlaySize,
                            double compactMs, double writeMs, long baseSize, long compactedSize,
                            Map<String, Integer> counts) throws IOException {
        boolean pass = mismatches == 0 && replayMismatch == 0 && postCompactMismatch == 0;
        StringBuilder sb = new StringBuilder();
        sb.append("# Spike D — incremental mmap format (G8): ").append(pass ? "PASS" : "FAIL").append("\n\n");
        sb.append(String.format("Synthetic graph: %,d symbols · %,d edges · %,d files. FFM-mapped base + LSM overlay.%n%n", S, E, F));
        sb.append("| check | result |\n|---|---|\n");
        sb.append(String.format("| edit round-trips (fwd/rev/lookup vs oracle), %d edits | **%d mismatches** |%n", edits, mismatches));
        sb.append(String.format("| overlay log replay / reopen | %d mismatches |%n", replayMismatch));
        sb.append(String.format("| post-compaction queries vs oracle | %d mismatches |%n", postCompactMismatch));
        sb.append(String.format("%n**G8: %s** — all edit→query→compaction round-trips %s.%n%n",
                pass ? "PASS" : "FAIL", pass ? "correct" : "FAILED"));
        sb.append("## Edit mix\n");
        for (var e : counts.entrySet()) sb.append(String.format("- %s: %d%n", e.getKey(), e.getValue()));
        sb.append("\n## Timing / overhead\n");
        sb.append(String.format("- Base write (%,d sym / %,d edges): %.0f ms, %,d bytes (%.1f MB)%n", S, E, writeMs, baseSize, baseSize / 1e6));
        sb.append(String.format("- Overlay-merge query overhead: %d edited files → 5k-query batch %.1f ms vs compacted %.1f ms = **%.2f×**%n",
                overlaySize, overlayMs, compactedQueryMs, overlayMs / compactedQueryMs));
        sb.append(String.format("- Compaction (rewrite base, fsync, atomic rename, reopen): **%.0f ms**, compacted base %,d bytes%n", compactMs, compactedSize));
        sb.append("\n## What this validates (PRD §5.1)\n");
        sb.append("- FFM `Arena`+`FileChannel.map`→`MemorySegment` read/write of packed int columns + CSR (both directions).\n");
        sb.append("- Per-file LSM mutation (tombstone-by-fileId + overlay re-emit) with **no rescan**; method-body vs API-surface (scoped referrers) vs add/delete.\n");
        sb.append("- Crash-durable overlay log → replay on reopen; compaction via fsync + atomic rename, query-identical pre/post.\n");
        sb.append("- Moniker-stable identity (int32 ids reassigned freely on compaction). **Seeds Spike C's FFM-under-native-image check.**\n");
        Files.writeString(out.resolve("spikeD-results.md"), sb.toString());
    }
}
