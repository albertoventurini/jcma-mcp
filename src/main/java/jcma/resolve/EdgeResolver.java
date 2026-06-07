package jcma.resolve;

import jcma.engine.AnalysisEngine;
import jcma.engine.JavaParserEngine;
import jcma.engine.OccurrenceKind;
import jcma.engine.ParsedUnit;
import jcma.engine.Position;
import jcma.engine.ResolvedOccurrence;
import jcma.engine.ResolvedRef;
import jcma.engine.ResolvedTarget;
import jcma.engine.ResolvedType;
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
import jcma.index.TrigramIndex;
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
    private final TrigramIndex usageIndex; // null if usage-names.seg is absent
    private final FileTable fileTable;
    private final AnalysisEngine engine;
    private final Indexer indexer;
    private final Path repoRoot;
    private final Metrics metrics;

    // Built once at open() from the (immutable-under-Tier-2) declaration set.
    private final Map<Integer, List<Symbol>> symbolsByFile = new HashMap<>();
    private final Map<String, Symbol> symbolByMoniker = new HashMap<>();

    // Tier-2 session state.
    private final Set<Integer> warmFiles = new HashSet<>();
    private final Map<String, List<UnconfirmedRef>> unconfirmedByName = new HashMap<>();
    private final Map<Path, List<String>> lineCache = new HashMap<>();

    private EdgeResolver(LsmStore store, TrigramIndex usageIndex, FileTable fileTable,
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
        TrigramIndex usageIndex = Files.exists(usagePath) ? TrigramIndex.load(usagePath) : null;
        FileTable fileTable = FileTable.load(indexDir);
        AnalysisEngine engine = new JavaParserEngine(workspace);
        Path repoRoot = workspace.projectRoot().toAbsolutePath().normalize();
        return new EdgeResolver(store, usageIndex, fileTable, engine, new Indexer(), repoRoot, metrics);
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
        List<UnconfirmedRef> tail = unconfirmedByName.getOrDefault(target.name(), List.of());
        return new References(groups, tail);
    }

    /**
     * Resolve every not-yet-warm candidate file for {@code simpleName}: write all its resolved edges
     * into the graph and bucket all its misses by target name (so the unconfirmed tail is complete for
     * any name, regardless of query order). Already-warm files are skipped — the cache property.
     */
    private void ensureResolved(String simpleName) {
        if (usageIndex == null) {
            return;
        }
        for (int fid : usageIndex.candidateFiles(simpleName)) {
            if (!warmFiles.add(fid)) {
                continue; // already resolved in this session
            }
            Path path = absPathOf(fid);
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                resolveFile(fid, path);
                metrics.counter("resolve.files").add(1);
            } catch (IOException skip) {
                // parse failure: leave the file warm (it cannot resolve), surface nothing
            }
        }
    }

    /** Resolve one file's occurrences, cache its edges + misses, and write the edges into the store. */
    private void resolveFile(int fid, Path path) throws IOException {
        ParsedUnit unit = engine.parse(path);
        List<ResolvedOccurrence> occurrences = engine.resolveOccurrences(unit);
        FileIndex tier1 = indexer.indexFile(fid, path, sourceSetOf(fid));
        List<MonikerEdge> edges = new ArrayList<>(tier1.edges());
        for (ResolvedOccurrence o : occurrences) {
            String enclosing = enclosingMoniker(fid, o.startLine(), o.startCol());
            if (enclosing == null) {
                continue;
            }
            Range range = new Range(o.startLine(), o.startCol(), o.endLine(), o.endCol());
            if (o.isResolved()) {
                String dst = targetMoniker(o.target());
                if (dst != null) {
                    Occurrence occ = new Occurrence(fid, range, -1, roleOf(o.kind()));
                    edges.add(new MonikerEdge(enclosing, dst, edgeTypeOf(o.kind()), occ));
                }
            } else {
                unconfirmedByName.computeIfAbsent(o.targetName(), k -> new ArrayList<>())
                        .add(new UnconfirmedRef(fid, path, range, snippet(path, o.startLine()),
                                FailureClassifier.classify(o.failure())));
            }
        }
        store.applyEdit(new FileIndex(fid, tier1.symbols(), edges));
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
                return Optional.of(definitionAt(r.signature(), r.fqn(), r.declFile(), r.declLine()));
            }
            Optional<ResolvedType> type = engine.resolveType(unit, pos);
            if (type.isPresent()) {
                ResolvedType t = type.get();
                return Optional.of(definitionAt(t.fqn(), t.fqn(), t.declFile(), t.declLine()));
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Definition definitionAt(String signature, String fqn, Path declFile, int declLine) {
        if (declFile == null) {
            return new Definition(fqn, signature, null, -1, "");
        }
        String moniker = monikerAt(fileIdOf(declFile), declLine);
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
            String m = monikerAt(fileIdOf(t.declFile()), t.declLine());
            if (m != null) {
                return m;
            }
        }
        // External (jar/JDK) or unlocatable project decl → a phantom node keyed by its signature.
        String key = t.signature() != null ? t.signature() : t.fqn();
        return key == null ? null : "~" + key;
    }

    /** The moniker of the declaration at {@code (fileId, line)} — smallest symbol whose lines cover it. */
    private String monikerAt(int fileId, int line) {
        Symbol best = null;
        for (Symbol s : symbolsByFile.getOrDefault(fileId, List.of())) {
            Range r = s.range();
            if (r.startLine() <= line && line <= r.endLine() && smaller(s, best)) {
                best = s;
            }
        }
        return best == null ? null : best.moniker();
    }

    /** The moniker of the smallest declaration whose range covers the use-site at {@code (line, col)}. */
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

    private String signatureOf(String moniker) {
        Symbol s = symbolByMoniker.get(moniker);
        return s == null ? moniker : display(s.signature(), moniker);
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

    private static Occurrence.Role roleOf(OccurrenceKind kind) {
        return switch (kind) {
            case CALL, INSTANTIATION, METHOD_REF -> Occurrence.Role.CALL;
            case NAME, FIELD_ACCESS -> Occurrence.Role.READ;
            case TYPE_REF, ANNOTATION -> Occurrence.Role.TYPEREF;
        };
    }
}
