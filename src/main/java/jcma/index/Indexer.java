package jcma.index;

import jcma.engine.FileOutline;
import jcma.engine.Outline;
import jcma.engine.StructuralParser;
import jcma.engine.UsageSite;
import jcma.obs.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The Tier-1 structural indexing pipeline (PRD §5.1; merged task-06 phase P2). Walks a repo's
 * source roots, parses each {@code .java} file <b>parse-only</b> through the engine seam
 * ({@link StructuralParser} — no SymbolSolver), maps each file's {@link FileOutline} to a
 * {@link FileIndex} (symbols with SCIP-style {@link Moniker}s, containment as {@link
 * EdgeType#CONTAINS} edges, signatures), and writes it <b>through {@link LsmStore}</b> (so
 * re-indexing one file is a single overlay edit). Parallelised across virtual threads (ported from
 * M0 {@code SpikeB}).
 *
 * <p><b>Tier-1 scope (decided P2):</b> the structural skeleton only — symbols + containment +
 * signatures + the symbol-name trigram. Resolved call/reference edges, reverse edges, and use-site
 * (occurrence) name indexing are Tier-2 (lazy-resolve, task-10), where they are consumed and tested.
 *
 * <p><b>Monikers (decided P2):</b> method/constructor param types are taken <em>as written</em>
 * (generics erased), per the amended PRD §11 scheme — a project symbol's identity is built from its
 * declaration on both tiers, so it is opaque and never parsed for type facts.
 *
 * <p><b>File ids (decided P2):</b> assigned deterministically within a run (sorted file order); the
 * stable, persistent path↔id table is task-08's {@code FileTable}. {@code outline} re-parses a
 * single file, so it needs no id lookup.
 */
public final class Indexer {

    /** Summary of a repo index run (surfaced by {@code jcma index}). */
    public record IndexStats(int files, long loc, int symbols, double seconds) {}

    /** One file to parse-extract under a caller-assigned (stable) {@code fileId} and source-set tag. */
    public record ParseRequest(int fileId, Path path, SourceSet sourceSet) {}

    /** The two projections carried out of a single parse for one file: its {@link FileIndex}
     * (symbols + edges + text) and its use-sites (the usage-name index input — see {@link
     * UsageNameIndexer#buildFrom}). The usages ride a side channel rather than {@link FileIndex} so
     * they are not retained through the store overlay/compaction. */
    public record ParsedFile(FileIndex index, List<UsageSite> usages) {}

    /** The result of a parallel parse pass: the per-request {@link FileIndex}es (nulls = parse
     * failures, positionally aligned with the requests), the use-sites collected off the <em>same</em>
     * parse keyed by {@code fileId} (the parse-free usage-name build path), and the total LOC read. */
    public record ParseResult(List<FileIndex> indices, Map<Integer, List<UsageSite>> usagesByFile, long loc) {}

    private final StructuralParser parser = new StructuralParser();
    private final Metrics metrics;

    /** An indexer with no metrics ({@link Metrics#noop()}). */
    public Indexer() {
        this(Metrics.noop());
    }

    /** An indexer that records throughput metrics (files, symbols, per-file parse time) into {@code metrics}. */
    public Indexer(Metrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Extract one file's structural content into a {@link FileIndex} under {@code fileId} (parse-only;
     * no resolution), tagging its symbols {@link SourceSet#MAIN}. Used by {@code outline}.
     */
    public FileIndex indexFile(int fileId, Path file) throws IOException {
        return indexFile(fileId, file, SourceSet.MAIN);
    }

    /**
     * As {@link #indexFile(int, Path)}, but tagging every extracted symbol with {@code sourceSet}
     * (PRD §5.1 test-source indexing) — used by {@link #indexRepo} so each file inherits its source
     * root's tag. The tag is packed into {@link Symbol#flags()} (see {@link SourceSet}).
     */
    public FileIndex indexFile(int fileId, Path file, SourceSet sourceSet) throws IOException {
        return collectFile(fileId, file, sourceSet).index();
    }

    /**
     * As {@link #indexFile(int, Path, SourceSet)}, but also carries out the file's use-sites — both
     * projections off the <em>same</em> parse, so the cold-index pass need not re-parse every file to
     * build {@code usage-names.seg} (see {@link UsageNameIndexer#buildFrom}).
     */
    ParsedFile collectFile(int fileId, Path file, SourceSet sourceSet) throws IOException {
        StructuralParser.Parsed parsed = parser.collect(file);
        FileOutline fo = parsed.outline();
        List<Symbol> symbols = new ArrayList<>();
        List<MonikerEdge> edges = new ArrayList<>();
        String packageMoniker = Moniker.forPackage(fo.packageName());
        for (Outline type : fo.types()) {
            walk(type, packageMoniker, null, fo.packageName(), fileId, sourceSet, symbols, edges);
        }
        // Three projections off the same parse: declarations (symbols/edges), the D2 text corpus
        // (M3 task-01), and the use-sites (the usage-name index input) — no extra parse for any.
        FileIndex index = new FileIndex(fileId, symbols, edges, parsed.textUnits());
        return new ParsedFile(index, parsed.usages());
    }

    /**
     * Discover the {@code .java} files under {@code sourceRoots}, extract them in parallel (virtual
     * threads), apply each {@link FileIndex} through {@code store}, then compact into a fresh base.
     * Returns the run's {@link IndexStats}. A file that fails to parse is skipped (logged), not fatal.
     */
    public IndexStats indexRepo(List<SourceRoot> sourceRoots, LsmStore store) throws IOException {
        List<TaggedFile> files = discover(sourceRoots);
        long t0 = System.nanoTime();

        // Parse + extract in parallel; ids are the (deterministic) sorted index of each file.
        List<ParseRequest> requests = new ArrayList<>(files.size());
        for (int id = 0; id < files.size(); id++) {
            requests.add(new ParseRequest(id, files.get(id).path(), files.get(id).set()));
        }
        ParseResult parsed = parseAll(requests);

        // Apply sequentially (the store is single-writer), then fold into a fresh base.
        int symbols = 0;
        int indexed = 0;
        for (FileIndex fi : parsed.indices()) {
            if (fi != null) {
                store.applyEdit(fi);
                symbols += fi.symbols().size();
                indexed++;
            }
        }
        store.compact();
        double seconds = (System.nanoTime() - t0) / 1e9;

        metrics.counter("index.files").add(indexed);
        metrics.counter("index.symbols").add(symbols);
        metrics.timer("index").record(System.nanoTime() - t0);
        return new IndexStats(indexed, parsed.loc(), symbols, seconds);
    }

    /**
     * Parse-extract a set of files in parallel across a fixed platform-thread pool sized to the
     * available core count (ported from M0 {@code SpikeB}; parsing is CPU-bound, so cores — not a
     * per-task virtual thread — is the right degree of parallelism, and the pool bounds in-flight ASTs).
     * Each {@link ParseRequest} carries the (caller-assigned, stable) file id and source-set tag, so
     * both the cold {@link #indexRepo} pass and the warm {@code Reconciler} reuse one parse engine. The
     * returned {@link FileIndex} list is positionally aligned with {@code requests} (a {@code null}
     * marks a file that failed to parse — logged, never fatal). Does not touch the store.
     */
    public ParseResult parseAll(List<ParseRequest> requests) throws IOException {
        List<FileIndex> extracted = new ArrayList<>(requests.size());
        Map<Integer, List<UsageSite>> usagesByFile = new HashMap<>(requests.size() * 2);
        AtomicLong loc = new AtomicLong();
        try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            List<Future<ParsedFile>> futures = new ArrayList<>(requests.size());
            for (ParseRequest req : requests) {
                futures.add(pool.submit(() -> {
                    loc.addAndGet(countLines(req.path()));
                    long parseStart = System.nanoTime();
                    try {
                        ParsedFile pf = collectFile(req.fileId(), req.path(), req.sourceSet());
                        metrics.timer("index.parse").record(System.nanoTime() - parseStart); // per file, not per symbol
                        return pf;
                    } catch (IOException e) {
                        System.err.println("jcma: skip (parse failed): " + req.path() + " — " + e.getMessage());
                        return null;
                    }
                }));
            }
            // Aggregate sequentially as futures complete (HashMap is not thread-safe; the per-file
            // parse stays parallel — this loop only collects already-computed results).
            for (int i = 0; i < requests.size(); i++) {
                ParsedFile pf = futures.get(i).get();
                if (pf == null) {
                    extracted.add(null);
                } else {
                    extracted.add(pf.index());
                    usagesByFile.put(requests.get(i).fileId(), pf.usages());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("indexing interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("indexing failed: " + e.getCause(), e.getCause());
        }
        return new ParseResult(extracted, usagesByFile, loc.get());
    }

    // ------------------------------------------------------------------ outline → symbols

    /**
     * Map one {@link Outline} (and, for a type, its members) to {@link Symbol}s + {@link
     * EdgeType#CONTAINS} edges. {@code parentMoniker} composes this symbol's moniker;
     * {@code enclosingMoniker} ({@code null} at top level) is its container for the containment edge;
     * {@code enclosingFqn} is the dotted FQN context for a readable signature.
     */
    private void walk(Outline o, String parentMoniker, String enclosingMoniker, String enclosingFqn,
            int fileId, SourceSet sourceSet, List<Symbol> symbols, List<MonikerEdge> edges) {
        String moniker;
        String signature;
        String childFqn = enclosingFqn;
        boolean isType = false;
        switch (o.kind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION -> {
                moniker = Moniker.forType(parentMoniker, o.name());
                childFqn = enclosingFqn.isEmpty() ? o.name() : enclosingFqn + "." + o.name();
                signature = childFqn;
                isType = true;
            }
            case METHOD -> {
                moniker = Moniker.forMethod(parentMoniker, o.name(), o.paramTypes());
                signature = enclosingFqn + "." + o.name() + "(" + String.join(",", o.paramTypes()) + ")";
            }
            case CONSTRUCTOR -> {
                moniker = Moniker.forConstructor(parentMoniker, o.paramTypes());
                signature = enclosingFqn + ".<init>(" + String.join(",", o.paramTypes()) + ")";
            }
            case FIELD, ENUM_CONSTANT -> {
                moniker = Moniker.forField(parentMoniker, o.name());
                signature = enclosingFqn + "." + o.name();
            }
            default -> {
                return;
            }
        }

        Range range = new Range(o.startLine(), o.startCol(), o.endLine(), o.endCol());
        symbols.add(new Symbol(moniker, mapKind(o.kind()), SourceSet.flagBits(sourceSet),
                enclosingMoniker, fileId, range, o.name(), signature));
        if (enclosingMoniker != null) {
            edges.add(new MonikerEdge(enclosingMoniker, moniker, EdgeType.CONTAINS, Occurrence.NONE));
        }
        if (isType) {
            for (Outline child : o.children()) {
                walk(child, moniker, moniker, childFqn, fileId, sourceSet, symbols, edges);
            }
        }
    }

    private static SymbolKind mapKind(Outline.Kind kind) {
        return switch (kind) {
            case CLASS -> SymbolKind.CLASS;
            case INTERFACE -> SymbolKind.INTERFACE;
            case ENUM -> SymbolKind.ENUM;
            case RECORD -> SymbolKind.RECORD;
            case ANNOTATION -> SymbolKind.ANNOTATION;
            case METHOD -> SymbolKind.METHOD;
            case CONSTRUCTOR -> SymbolKind.CONSTRUCTOR;
            case FIELD -> SymbolKind.FIELD;
            case ENUM_CONSTANT -> SymbolKind.ENUM_CONSTANT;
        };
    }

    // ------------------------------------------------------------------ file discovery

    /** A discovered {@code .java} file paired with its source root's {@link SourceSet} tag. */
    private record TaggedFile(Path path, SourceSet set) {}

    private static List<TaggedFile> discover(List<SourceRoot> sourceRoots) throws IOException {
        List<TaggedFile> files = new ArrayList<>();
        for (SourceRoot root : sourceRoots) {
            if (!Files.isDirectory(root.dir())) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root.dir())) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> files.add(new TaggedFile(p, root.set())));
            }
        }
        files.sort(Comparator.comparing(TaggedFile::path)); // deterministic in-run file ids
        return files;
    }

    private static long countLines(Path file) {
        try {
            return Files.readAllLines(file).size();
        } catch (IOException e) {
            return 0;
        }
    }
}
