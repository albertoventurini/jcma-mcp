package jcma.resolve;

import jcma.engine.AnalysisEngine;
import jcma.engine.JavaParserEngine;
import jcma.engine.HierarchyKind;
import jcma.engine.OccurrenceKind;
import jcma.engine.ParsedUnit;
import jcma.engine.Position;
import jcma.engine.ResolvedHierarchy;
import jcma.engine.ResolvedOccurrence;
import jcma.engine.ResolvedRef;
import jcma.engine.ResolvedTarget;
import jcma.engine.ResolvedType;
import jcma.engine.StructuralParser;
import jcma.engine.TextUnit;
import jcma.engine.UsageSite;
import jcma.index.CompactionPolicy;
import jcma.index.EdgeType;
import jcma.index.FileIndex;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.MonikerEdge;
import jcma.index.Occurrence;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SourceSet;
import jcma.index.UsageNameIndex;
import jcma.index.UsageNameIndexer;
import jcma.obs.Metrics;
import jcma.workspace.FileTable;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tier-2 lazy-resolve-and-cache (PRD §5.1; M1 task-10). See the class doc on the original stub for the
 * full flow. In short: a first {@code find_references(X)} prunes to candidate files via the usage-name
 * index, resolves their occurrences (all seven categories), writes the confirmed {@code CALLS}/… edges
 * <b>into the graph</b> (both directions — reverse is the byproduct), and answers from {@code rev(X)}
 * grouped by enclosing symbol + the unconfirmed tail. The second query re-resolves nothing.
 */
public final class EdgeResolver implements AutoCloseable {

    private final LsmStore store;
    private final UsageNameIndex usageIndex; // null if usage-names.seg is absent
    private final FileTable fileTable;
    private final AnalysisEngine engine;
    private final Indexer indexer;
    private final Path repoRoot;
    private final Metrics metrics;

    // Built once at open() from the (immutable-under-Tier-2) declaration set.
    private final Map<Integer, List<Symbol>> symbolsByFile = new HashMap<>();
    private final Map<String, Symbol> symbolByMoniker = new HashMap<>();

    /**
     * Reserved moniker suffix for the <b>name-keyed unconfirmed placeholder</b> node (task-11b). A
     * failed reference to simple name {@code foo} is a normal {@code CALLS}/… edge whose {@code dst}
     * is {@code foo}{@value} — coalescing every miss on that name onto one node so a later definition
     * of {@code foo} cascades by a plain {@code rev(foo}{@value}{@code )} walk. {@code ~} never
     * appears in a real moniker (Java identifiers + {@code / # ( ) . ;}), so it cannot collide.
     */
    public static final String UNRESOLVED_SUFFIX = "~UNRESOLVED";

    // Tier-2 session state. A file's resolution is accumulated per file in a FileSlice, because
    // store.applyEdit REPLACES the file's whole overlay slice — so writing name X's edges then later
    // name Y's would wipe X's. The slice holds the Tier-1 base + the union of every resolved layer
    // (per-name value edges + the once-per-file structural edges) and is re-applied as that union.
    private final Map<Integer, FileSlice> slices = new HashMap<>();
    private final Map<Path, List<String>> lineCache = new HashMap<>();

    // In-session usage overlay (task-10): target simple name → file ids that use it, re-derived from a
    // file's current usages after any in-session reindex. It unions with the static usage-names.seg so a
    // session-new file (and an edited file that gained a new use) becomes a find_references candidate —
    // the static index was built once at index time and excludes both. overlayNamesByFile is the inverse
    // (file → the names it currently contributes) so clearing a file is O(1) per name, not a full scan.
    private final Map<String, Set<Integer>> usageOverlay = new HashMap<>();
    private final Map<Integer, Set<String>> overlayNamesByFile = new HashMap<>();
    private final StructuralParser overlayParser = new StructuralParser();

    /**
     * Per-file Tier-2 accumulation (the cache unit is now {@code (file, name)} for value edges, and
     * {@code file} for the name-independent structural layer). {@code names} records which value-name
     * layers are resolved; {@code structural} whether the type/annotation + hierarchy layer is. The
     * applied {@link FileIndex} is the union of {@link #base} (Tier-1) + {@link #valueEdges} (all
     * resolved names) + {@link #structuralEdges}.
     */
    private static final class FileSlice {
        final List<Symbol> symbols;           // Tier-1 declarations
        final List<MonikerEdge> base;         // Tier-1 structural edges (e.g. CONTAINS)
        final List<TextUnit> texts;           // Tier-1 D2 text units (re-emitted so Tier-2 doesn't drop them)
        final Set<String> names = new HashSet<>();              // value-name layers resolved
        final List<MonikerEdge> valueEdges = new ArrayList<>(); // accumulated across names
        boolean structural;                   // type/annotation refs + hierarchy resolved (once/file)
        final List<MonikerEdge> structuralEdges = new ArrayList<>();

        FileSlice(List<Symbol> symbols, List<MonikerEdge> base, List<TextUnit> texts) {
            this.symbols = symbols;
            this.base = base;
            this.texts = texts;
        }

        FileIndex toIndex(int fid) {
            List<MonikerEdge> all = new ArrayList<>(base);
            all.addAll(valueEdges);
            all.addAll(structuralEdges);
            // Carry the file's text units through: applyEdit replaces the whole overlay slice, so a
            // Tier-2 re-apply that dropped texts would erase this file from text search (M3 task-01).
            return new FileIndex(fid, symbols, all, texts);
        }
    }

    private EdgeResolver(LsmStore store, UsageNameIndex usageIndex, FileTable fileTable,
            AnalysisEngine engine, Indexer indexer, Path repoRoot, Metrics metrics) {
        this.store = store;
        this.usageIndex = usageIndex;
        this.fileTable = fileTable;
        this.engine = engine;
        this.indexer = indexer;
        this.repoRoot = repoRoot;
        this.metrics = metrics;
        for (Symbol s : store.liveSymbols()) {
            symbolsByFile.computeIfAbsent(s.fileId(), k -> new ArrayList<>()).add(s);
            symbolByMoniker.put(s.moniker(), s);
        }
    }

    /**
     * Open a resolver over a persisted index: the {@link LsmStore} graph (manual policy — Tier-2
     * controls compaction), the usage-name index (if present), the {@link FileTable}, and a resolving
     * {@link JavaParserEngine} for {@code workspace}. {@code resolve.files} is recorded into {@code metrics}.
     */
    public static EdgeResolver open(Path indexDir, Workspace workspace, Metrics metrics) throws IOException {
        LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics);
        Path usagePath = indexDir.resolve(UsageNameIndexer.FILE_NAME);
        UsageNameIndex usageIndex = Files.exists(usagePath) ? UsageNameIndex.load(usagePath) : null;
        FileTable fileTable = FileTable.load(indexDir);
        AnalysisEngine engine = new JavaParserEngine(workspace);
        Path repoRoot = workspace.projectRoot().toAbsolutePath().normalize();
        return new EdgeResolver(store, usageIndex, fileTable, engine, new Indexer(), repoRoot, metrics);
    }

    /**
     * Open a resolver over an <b>already-constructed</b> shared state — used by {@code AnalysisSession}
     * so the Tier-1 freshness guard and this Tier-2 resolver mutate and read the <em>same</em> live
     * {@link LsmStore} + {@link FileTable} + {@link Indexer} (the prerequisite for the node-diff
     * cascade, task-11c). {@link #close()} closes the shared store, so the session must own a single
     * resolver as the store's closer.
     */
    public static EdgeResolver over(LsmStore store, UsageNameIndex usageIndex, FileTable fileTable,
            AnalysisEngine engine, Indexer indexer, Path repoRoot, Metrics metrics) {
        return new EdgeResolver(store, usageIndex, fileTable, engine, indexer, repoRoot, metrics);
    }

    /** Declarations whose simple name equals {@code name} (the by-name target selector). */
    public List<Symbol> declarations(String name) {
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : store.search(name)) {
            if (name.equals(s.name())) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ find_references

    /** {@code find_references(target)}: confirmed refs grouped by enclosing symbol + unconfirmed tail. */
    public References findReferences(Symbol target) {
        ensureResolved(target.name());

        // Confirmed: incoming reference edges (exclude the structural CONTAINS edge from the container).
        Map<String, List<Ref>> byEnclosing = new TreeMap<>();
        for (MonikerEdge e : store.rev(target.moniker())) {
            if (e.type() == EdgeType.CONTAINS) {
                continue;
            }
            Occurrence o = e.occurrence();
            Path file = absPathOf(o.fileId());
            byEnclosing.computeIfAbsent(e.src(), k -> new ArrayList<>())
                    .add(new Ref(o.fileId(), file, o.range(), snippet(file, o.range().startLine())));
        }
        List<ReferenceGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Ref>> g : byEnclosing.entrySet()) {
            g.getValue().sort(Comparator.comparingInt((Ref r) -> r.range().startLine())
                    .thenComparingInt(r -> r.range().startCol()));
            groups.add(new ReferenceGroup(g.getKey(), signatureOf(g.getKey()), g.getValue()));
        }
        return new References(groups, unconfirmedTail(target.name()));
    }

    /**
     * The unconfirmed tail for {@code name}, read back from the graph: the incoming edges of the
     * name-keyed placeholder {@code name}{@link #UNRESOLVED_SUFFIX} (every miss on that name coalesces
     * there). These are persisted edges, so the tail is graph-backed and survives a restart — no
     * in-session bookkeeping. Each edge's occurrence carries the use site + the {@link
     * FailureClassifier.Cause} ordinal (in its enclosing slot).
     */
    private List<UnconfirmedRef> unconfirmedTail(String name) {
        List<UnconfirmedRef> tail = new ArrayList<>();
        for (MonikerEdge e : store.rev(name + UNRESOLVED_SUFFIX)) {
            Occurrence o = e.occurrence();
            Path file = absPathOf(o.fileId());
            tail.add(new UnconfirmedRef(o.fileId(), file, o.range(),
                    snippet(file, o.range().startLine()), causeOf(o)));
        }
        tail.sort(Comparator.comparing((UnconfirmedRef u) -> String.valueOf(u.file()))
                .thenComparingInt(u -> u.range().startLine())
                .thenComparingInt(u -> u.range().startCol()));
        return tail;
    }

    /**
     * Resolve every candidate file for {@code simpleName} (Option A — name-scoped). For each, resolve
     * the per-(file,name) <b>value layer</b> (the queried name's calls/reads — the cubic-cost class,
     * bucketing its misses onto the {@code name~UNRESOLVED} placeholder) and the once-per-file
     * <b>structural layer</b> (type/annotation refs + hierarchy). The cache unit is now {@code (file,
     * name)} for the value layer and {@code file} for the structural layer — a same-name repeat
     * re-resolves nothing.
     *
     * <p>The candidate set is {@link UsageNameIndex#candidateFiles} — an <b>exact</b> simple-name match,
     * so every true use-site of {@code simpleName} is in a candidate file, and the per-name tail is
     * complete for {@code simpleName}.
     */
    private void ensureResolved(String simpleName) {
        for (int fid : candidateFiles(simpleName)) {
            // Cooperative cancel checkpoint (task-12): the time-box interrupts this (single) worker
            // thread; bail here — between files, never mid-applyEdit, so the store is never torn.
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("find_references cancelled");
            }
            warmForReferences(fid, simpleName);
        }
    }

    /**
     * The candidate file set for {@code name}: the static {@link UsageNameIndex#candidateFiles} postings
     * (when {@code usage-names.seg} is present) <b>unioned</b> with the in-session {@link #usageOverlay}.
     * The overlay is what makes a session-new or newly-edited usage discoverable — the static index is
     * built once at index time and cannot list it.
     */
    private Set<Integer> candidateFiles(String name) {
        Set<Integer> out = new HashSet<>();
        if (usageIndex != null) {
            for (int fid : usageIndex.candidateFiles(name)) {
                out.add(fid);
            }
        }
        out.addAll(usageOverlay.getOrDefault(name, Set.of()));
        return out;
    }

    /**
     * Re-derive {@code fid}'s {@link #usageOverlay} entries from its <em>current</em> usages (task-10).
     * First clears the file's prior overlay names (so a removed use stops being a candidate), then — if
     * the file exists and parses — re-adds {@code fid} under each {@link UsageSite#targetName()}, the
     * same source {@code UsageNameIndexer} walks. A missing or unparseable file is left cleared, which is
     * exactly the tombstone case (the file is gone → no overlay entries).
     */
    public void refreshUsageOverlay(int fid) {
        for (String name : overlayNamesByFile.getOrDefault(fid, Set.of())) {
            Set<Integer> files = usageOverlay.get(name);
            if (files != null) {
                files.remove(fid);
                if (files.isEmpty()) {
                    usageOverlay.remove(name);
                }
            }
        }
        overlayNamesByFile.remove(fid);

        Path path = absPathOf(fid);
        if (path == null || !Files.isRegularFile(path)) {
            metrics.counter("overlay.dropped").add(1);
            return;
        }
        try {
            Set<String> names = new HashSet<>();
            for (UsageSite u : overlayParser.usages(path)) {
                names.add(u.targetName());
            }
            for (String name : names) {
                usageOverlay.computeIfAbsent(name, k -> new HashSet<>()).add(fid);
            }
            if (!names.isEmpty()) {
                overlayNamesByFile.put(fid, names);
            }
            metrics.counter("overlay.registered").add(1);
        } catch (IOException unparseable) {
            metrics.counter("overlay.dropped").add(1); // left cleared — cannot derive usages
        }
    }

    /**
     * Warm {@code fid} for a {@code find_references(simpleName)} query: resolve the value layer for
     * {@code simpleName} and the structural layer (each at most once), accumulating into the file's
     * slice and re-applying the union. Both layers share one {@code engine.parse} when both are needed.
     */
    private void warmForReferences(int fid, String simpleName) {
        Path path = absPathOf(fid);
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            FileSlice slice = sliceFor(fid, path);
            boolean needValue = !slice.names.contains(simpleName);
            boolean needStructural = !slice.structural;
            if (!needValue && !needStructural) {
                return; // (file, name) value layer + structural layer both cached
            }
            ParsedUnit unit = engine.parse(path);
            if (needStructural) {
                resolveStructuralInto(slice, fid, unit);
            }
            if (needValue) {
                resolveValueInto(slice, fid, unit, simpleName);
            }
            store.applyEdit(slice.toIndex(fid));
            metrics.counter("resolve.files").add(1);
        } catch (IOException skip) {
            // parse failure: leave whatever is cached (it cannot resolve), surface nothing
        }
    }

    /** The file's slice, building its Tier-1 base (symbols + structural edges) on first touch. */
    private FileSlice sliceFor(int fid, Path path) throws IOException {
        FileSlice slice = slices.get(fid);
        if (slice == null) {
            FileIndex tier1 = indexer.indexFile(fid, path, sourceSetOf(fid));
            slice = new FileSlice(tier1.symbols(), new ArrayList<>(tier1.edges()), tier1.texts());
            slices.put(fid, slice);
        }
        return slice;
    }

    /**
     * Resolve the value layer for {@code simpleName} into {@code slice} (the cubic-cost class, scoped to
     * the queried name). Marks the name resolved even on a zero-edge result, so a repeat is a no-op.
     */
    private void resolveValueInto(FileSlice slice, int fid, ParsedUnit unit, String simpleName) {
        slice.names.add(simpleName);
        for (ResolvedOccurrence o : engine.resolveOccurrences(unit, simpleName)) {
            // Observability (perf gate): every occurrence here was a value-name .resolve() — the
            // cubic-cost class. resolve.values isolates it; resolve.occurrences is the all-kinds total.
            metrics.counter("resolve.occurrences").add(1);
            metrics.counter("resolve.values").add(1);
            addOccurrenceEdge(fid, o, slice.valueEdges);
        }
    }

    /**
     * Resolve the once-per-file structural layer into {@code slice}: the type/annotation references
     * (the cheap, name-independent dependency layer the cascade walks) and the hierarchy edges (task-11a
     * EXTENDS/IMPLEMENTS from a type, OVERRIDES from a method). Marks {@code structural} resolved.
     */
    private void resolveStructuralInto(FileSlice slice, int fid, ParsedUnit unit) {
        slice.structural = true;
        for (ResolvedOccurrence o : engine.resolveTypeReferences(unit)) {
            metrics.counter("resolve.occurrences").add(1);
            addOccurrenceEdge(fid, o, slice.structuralEdges);
        }
        // src = the enclosing declaration's moniker (its name position); dst = the supertype/overridden
        // member, or a phantom for external (jar/JDK) targets.
        for (ResolvedHierarchy h : engine.resolveHierarchy(unit)) {
            String src = enclosingMoniker(fid, h.srcLine(), h.srcCol());
            if (src == null) {
                continue;
            }
            String dst = targetMoniker(h.target());
            if (dst != null) {
                slice.structuralEdges.add(new MonikerEdge(src, dst, hierarchyEdgeType(h.kind()), Occurrence.NONE));
            }
        }
    }

    /**
     * Append the edge for one resolved occurrence to {@code into}: a confirmed edge to the target's
     * moniker, or — for a safe-degrading miss (task-11b) — a faithful syntactic edge to the name-keyed
     * placeholder {@code <name>~UNRESOLVED} (the Cause ordinal rides the occurrence's enclosing slot).
     */
    private void addOccurrenceEdge(int fid, ResolvedOccurrence o, List<MonikerEdge> into) {
        String enclosing = enclosingMoniker(fid, o.startLine(), o.startCol());
        if (enclosing == null) {
            return;
        }
        Range range = new Range(o.startLine(), o.startCol(), o.endLine(), o.endCol());
        if (o.isResolved()) {
            String dst = targetMoniker(o.target());
            if (dst != null) {
                into.add(new MonikerEdge(enclosing, dst, edgeTypeOf(o.kind()),
                        new Occurrence(fid, range, -1, roleOf(o.kind()))));
            }
        } else if (o.targetName() != null) {
            Occurrence occ = new Occurrence(fid, range,
                    causeSlot(FailureClassifier.classify(o.failure())), roleOf(o.kind()));
            into.add(new MonikerEdge(enclosing, o.targetName() + UNRESOLVED_SUFFIX,
                    edgeTypeOf(o.kind()), occ));
        }
    }

    // ------------------------------------------------------------------ type hierarchy

    /**
     * The {@code supertypes(type)} primitive (PRD §6 {@code find_supertypes}): {@code type}'s outgoing
     * {@code EXTENDS}/{@code IMPLEMENTS} edges, plus a method's {@code OVERRIDES} edges. Resolves the
     * declaring file (and the simple-name candidates) on demand, then walks {@code fwd}.
     */
    public List<MonikerEdge> supertypes(Symbol target) {
        ensureHierarchyResolved(target);
        return hierarchyEdges(store.fwd(target.moniker()));
    }

    /**
     * The {@code subtypes(type)} primitive (PRD §6 {@code find_subtypes}/{@code find_implementations}):
     * the {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES} edges pointing <em>at</em> {@code target},
     * i.e. its direct subtypes / implementors / overriders. Walks {@code rev}.
     */
    public List<MonikerEdge> subtypes(Symbol target) {
        ensureHierarchyResolved(target);
        return hierarchyEdges(store.rev(target.moniker()));
    }

    /**
     * Resolve the <b>structural layer</b> of {@code target}'s own file (its outgoing hierarchy) and of
     * every candidate file for {@code target.name()} (the incoming hierarchy: a subtype names its
     * supertype, so it is a candidate). Hierarchy is name-independent, so this resolves <em>only</em>
     * the structural layer — it does not drag in the value-name resolution {@code find_references} needs
     * (and vice-versa).
     */
    private void ensureHierarchyResolved(Symbol target) {
        warmStructural(target.fileId());
        for (int fid : candidateFiles(target.name())) {
            warmStructural(fid);
        }
    }

    /** Resolve a single file's structural layer (type/annotation refs + hierarchy) if not already done. */
    private void warmStructural(int fid) {
        if (fid < 0) {
            return;
        }
        Path path = absPathOf(fid);
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            FileSlice slice = sliceFor(fid, path);
            if (slice.structural) {
                return;
            }
            resolveStructuralInto(slice, fid, engine.parse(path));
            store.applyEdit(slice.toIndex(fid));
            metrics.counter("resolve.files").add(1);
        } catch (IOException skip) {
            // parse failure: cannot resolve, surface nothing
        }
    }

    // ------------------------------------------------------------------ task-11c cascade support

    /** The file id for {@code file} (repo-relative lookup), or {@code -1} if untracked. */
    public int fileId(Path file) {
        return fileIdOf(file);
    }

    /**
     * Shed the engine's cached cross-file view so subsequent (re-)resolutions reflect the current
     * bytes on disk (task-11c). Called by the cascade once a re-index has changed a file — otherwise
     * the resolver would re-resolve a referrer against a stale cached AST of the edited file.
     */
    public void refreshEngine() {
        engine.refresh();
    }

    /** The hierarchy ({@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES}) edges out of {@code moniker}. */
    public Set<MonikerEdge> hierarchyOut(String moniker) {
        Set<MonikerEdge> out = new HashSet<>();
        for (MonikerEdge e : store.fwd(moniker)) {
            if (e.type() == EdgeType.EXTENDS || e.type() == EdgeType.IMPLEMENTS || e.type() == EdgeType.OVERRIDES) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Re-sync the in-memory symbol caches ({@code symbolsByFile}, {@code symbolByMoniker}) for
     * {@code fileId} from the store — called after a Tier-1 reindex so occurrence attribution
     * ({@code enclosingMoniker}) and display use the file's <em>current</em> declarations, not the
     * built-once-at-open set (which a rename/add/remove would leave stale).
     */
    public void refreshSymbols(int fileId) {
        for (Symbol s : symbolsByFile.getOrDefault(fileId, List.of())) {
            symbolByMoniker.remove(s.moniker());
        }
        List<Symbol> now = store.symbolsOf(fileId);
        symbolsByFile.put(fileId, new ArrayList<>(now));
        for (Symbol s : now) {
            symbolByMoniker.put(s.moniker(), s);
        }
    }

    /**
     * Eagerly (re-)resolve {@code fileId} into the graph from current bytes — the changed file is
     * resolved as part of the cascade so its new structural edges (hierarchy + type refs) become
     * visible for the diff. <b>Always</b> resolves the structural layer (even if the file was never
     * warm — a freshly-edited supertype must surface its new EXTENDS edge), and re-resolves the value
     * layer for every name previously warm for the file. Discards the old slice first, so stale edges
     * are not carried. Leaves the file <b>warm</b>.
     */
    public void reResolve(int fileId) {
        FileSlice old = slices.remove(fileId);
        Set<String> names = old == null ? Set.of() : new HashSet<>(old.names);
        Path path = absPathOf(fileId);
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            FileSlice slice = sliceFor(fileId, path);
            ParsedUnit unit = engine.parse(path);
            resolveStructuralInto(slice, fileId, unit);
            for (String name : names) {
                resolveValueInto(slice, fileId, unit, name);
            }
            store.applyEdit(slice.toIndex(fileId));
            metrics.counter("resolve.files").add(1);
        } catch (IOException skip) {
            // parse failure: leave the (empty) slice (it cannot resolve), surface nothing
        }
    }

    /**
     * Return {@code fileId} to <b>unresolved</b>: re-apply its Tier-1-only slice (dropping all its
     * resolved Tier-2 edges — every {@code (file, *)} value layer and the structural layer, including
     * any now-stale binding) and drop its slice, so the next query that touches it re-resolves lazily.
     */
    public void dropTier2(int fileId) throws IOException {
        Path path = absPathOf(fileId);
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        store.applyEdit(indexer.indexFile(fileId, path, sourceSetOf(fileId)));
        refreshSymbols(fileId);
        slices.remove(fileId);
    }

    /**
     * Walk the reverse edges of every changed node and return the exact <b>referrer files</b> to
     * unresolved (M1 task-11c). For each changed moniker, {@code rev(moniker)} gives the confirmed
     * referrers (excluding the structural {@code CONTAINS} edge); for each changed simple name,
     * {@code rev(name~UNRESOLVED)} gives the unconfirmed referrers (so a newly-defined name re-binds).
     * Only files <b>warm</b> in this session are dropped (any {@code (file, *)} layer warm counts — a
     * non-warm referrer re-resolves lazily on first touch anyway); {@code exclude} skips the
     * just-re-resolved changed files. Returns the set of
     * referrer files actually returned to unresolved.
     */
    public Set<Path> invalidateReferrers(Set<String> changedMonikers, Set<String> changedNames,
            Set<Integer> exclude) throws IOException {
        Set<Integer> referrers = new HashSet<>();
        for (String moniker : changedMonikers) {
            for (MonikerEdge e : store.rev(moniker)) {
                if (e.type() != EdgeType.CONTAINS) {
                    addReferrer(referrers, e);
                }
            }
        }
        for (String name : changedNames) {
            for (MonikerEdge e : store.rev(name + UNRESOLVED_SUFFIX)) {
                addReferrer(referrers, e);
            }
        }
        referrers.removeAll(exclude);

        Set<Path> files = new HashSet<>();
        for (int fid : referrers) {
            if (fid < 0 || !slices.containsKey(fid)) {
                continue; // not cached this session → lazy re-resolution will redo it correctly
            }
            dropTier2(fid);
            Path p = absPathOf(fid);
            if (p != null) {
                files.add(p.toAbsolutePath().normalize());
            }
        }
        return files;
    }

    /** The file that owns edge {@code e}: its occurrence's file, or the declaring file of its src moniker. */
    private void addReferrer(Set<Integer> referrers, MonikerEdge e) {
        int fid = e.occurrence().fileId();
        if (fid < 0) {
            Symbol src = symbolByMoniker.get(e.src());
            fid = src == null ? -1 : src.fileId();
        }
        if (fid >= 0) {
            referrers.add(fid);
        }
    }

    private static List<MonikerEdge> hierarchyEdges(Set<MonikerEdge> edges) {
        List<MonikerEdge> out = new ArrayList<>();
        for (MonikerEdge e : edges) {
            if (e.type() == EdgeType.EXTENDS || e.type() == EdgeType.IMPLEMENTS || e.type() == EdgeType.OVERRIDES) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing((MonikerEdge e) -> e.type().ordinal())
                .thenComparing(MonikerEdge::dst).thenComparing(MonikerEdge::src));
        return out;
    }

    // ------------------------------------------------------------------ find_definition

    /** {@code find_definition} by symbol: the declaration site + signature + snippet. */
    public Definition findDefinition(Symbol target) {
        if (target.isPhantom() || target.range().isNone()) {
            return new Definition(target.moniker(), display(target.signature(), target.moniker()), null, -1, "");
        }
        Path file = absPathOf(target.fileId());
        int line = target.range().startLine();
        return new Definition(target.moniker(), display(target.signature(), target.moniker()),
                file, line, snippet(file, line));
    }

    /** {@code find_definition} by use-site position (go-to-def): resolve the occurrence at {@code pos}. */
    public Optional<Definition> findDefinitionAt(Path file, Position pos) {
        try {
            ParsedUnit unit = engine.parse(file);
            Optional<ResolvedRef> ref = engine.resolveMethodCall(unit, pos);
            if (ref.isPresent()) {
                ResolvedRef r = ref.get();
                return Optional.of(definitionAt(r.signature(), r.fqn(), r.declFile(), r.declLine(), r.declCol()));
            }
            Optional<ResolvedType> type = engine.resolveType(unit, pos);
            if (type.isPresent()) {
                ResolvedType t = type.get();
                return Optional.of(definitionAt(t.fqn(), t.fqn(), t.declFile(), t.declLine(), t.declCol()));
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Definition definitionAt(String signature, String fqn, Path declFile, int declLine, int declCol) {
        if (declFile == null) {
            return new Definition(fqn, signature, null, -1, "");
        }
        String moniker = enclosingMoniker(fileIdOf(declFile), declLine, declCol);
        return new Definition(moniker != null ? moniker : fqn, signature, declFile, declLine,
                snippet(declFile, declLine));
    }

    @Override
    public void close() throws IOException {
        store.close();
        if (usageIndex != null) {
            usageIndex.close();
        }
    }

    // ------------------------------------------------------------------ moniker bridge

    /** A resolved target's graph moniker: the project decl's moniker, or a phantom from its signature. */
    private String targetMoniker(ResolvedTarget t) {
        if (t.declFile() != null) {
            // Attribute by the declaration's start position, column-precise: a single-line record's
            // components share the header line, so a line-only match would resolve a type reference to a
            // component field (smaller span) instead of the type itself.
            String m = enclosingMoniker(fileIdOf(t.declFile()), t.declLine(), t.declCol());
            if (m != null) {
                return m;
            }
        }
        // External (jar/JDK) or unlocatable project decl → a phantom node keyed by its signature.
        String key = t.signature() != null ? t.signature() : t.fqn();
        return key == null ? null : "~" + key;
    }

    /**
     * The moniker of the smallest declaration whose range covers position {@code (line, col)}. Serves
     * both attributions in the resolve pipeline: a use-site's <em>enclosing</em> declaration, and a
     * resolved declaration's <em>own</em> moniker (keyed by its start position — see {@link
     * #targetMoniker}). Column-precise containment is what disambiguates declarations sharing a line.
     */
    private String enclosingMoniker(int fileId, int line, int col) {
        Symbol best = null;
        for (Symbol s : symbolsByFile.getOrDefault(fileId, List.of())) {
            if (covers(s.range(), line, col) && smaller(s, best)) {
                best = s;
            }
        }
        return best == null ? null : best.moniker();
    }

    private static boolean covers(Range r, int line, int col) {
        boolean afterStart = line > r.startLine() || (line == r.startLine() && col >= r.startCol());
        boolean beforeEnd = line < r.endLine() || (line == r.endLine() && col <= r.endCol());
        return afterStart && beforeEnd;
    }

    /** True if {@code s} spans fewer lines than {@code best} (innermost wins); null best loses. */
    private static boolean smaller(Symbol s, Symbol best) {
        if (best == null) {
            return true;
        }
        return span(s.range()) < span(best.range());
    }

    private static long span(Range r) {
        return (long) (r.endLine() - r.startLine()) * 1_000_000 + (r.endCol() - r.startCol());
    }

    // ------------------------------------------------------------------ file / path helpers

    private int fileIdOf(Path absolute) {
        Path rel = repoRoot.relativize(absolute.toAbsolutePath().normalize());
        FileTable.Entry e = fileTable.get(rel);
        return e == null ? -1 : e.fileId();
    }

    private Path absPathOf(int fileId) {
        Path rel = fileTable.pathOf(fileId);
        return rel == null ? null : repoRoot.resolve(rel);
    }

    private SourceSet sourceSetOf(int fileId) {
        Path rel = fileTable.pathOf(fileId);
        FileTable.Entry e = rel == null ? null : fileTable.get(rel);
        return e == null ? SourceSet.MAIN : e.sourceSet();
    }

    /** A moniker's display: its indexed signature, the bare phantom signature ({@code ~fqn} → {@code fqn}), or the moniker. */
    public String signatureOf(String moniker) {
        Symbol s = symbolByMoniker.get(moniker);
        if (s != null) {
            return display(s.signature(), moniker);
        }
        return moniker.startsWith("~") ? moniker.substring(1) : moniker;
    }

    private static String display(String signature, String fallback) {
        return signature != null ? signature : fallback;
    }

    /** The trimmed source line at {@code line} (1-based), cached per file (ported {@code snippetOf}). */
    private String snippet(Path file, int line) {
        if (file == null || line < 1) {
            return "";
        }
        List<String> lines = lineCache.computeIfAbsent(file, p -> {
            try {
                return Files.readAllLines(p);
            } catch (IOException e) {
                return List.of();
            }
        });
        return line <= lines.size() ? lines.get(line - 1).trim() : "";
    }

    // ------------------------------------------------------------------ kind mapping

    private static EdgeType edgeTypeOf(OccurrenceKind kind) {
        return switch (kind) {
            case CALL, METHOD_REF -> EdgeType.CALLS;
            case INSTANTIATION -> EdgeType.INSTANTIATES;
            case ANNOTATION -> EdgeType.ANNOTATED_BY;
            case NAME, FIELD_ACCESS, TYPE_REF -> EdgeType.REFERENCES;
        };
    }

    private static EdgeType hierarchyEdgeType(HierarchyKind kind) {
        return switch (kind) {
            case EXTENDS -> EdgeType.EXTENDS;
            case IMPLEMENTS -> EdgeType.IMPLEMENTS;
            case OVERRIDES -> EdgeType.OVERRIDES;
        };
    }

    /** A {@link FailureClassifier.Cause} → the int stored in an unconfirmed edge's enclosing slot. */
    private static int causeSlot(FailureClassifier.Cause cause) {
        return cause.ordinal();
    }

    /** Read the {@link FailureClassifier.Cause} back from an unconfirmed edge's occurrence. */
    private static FailureClassifier.Cause causeOf(Occurrence o) {
        int slot = o.enclosingSymbolId();
        FailureClassifier.Cause[] causes = FailureClassifier.Cause.values();
        return slot >= 0 && slot < causes.length ? causes[slot] : FailureClassifier.Cause.OTHER;
    }

    private static Occurrence.Role roleOf(OccurrenceKind kind) {
        return switch (kind) {
            case CALL, INSTANTIATION, METHOD_REF -> Occurrence.Role.CALL;
            case NAME, FIELD_ACCESS -> Occurrence.Role.READ;
            case TYPE_REF, ANNOTATION -> Occurrence.Role.TYPEREF;
        };
    }
}
