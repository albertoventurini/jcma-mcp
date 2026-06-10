package jcma.session;

import jcma.engine.AnalysisEngine;
import jcma.engine.JavaParserEngine;
import jcma.engine.Position;
import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.index.UsageNameIndex;
import jcma.index.UsageNameIndexer;
import jcma.obs.Metrics;
import jcma.resolve.Cascade;
import jcma.resolve.Definition;
import jcma.resolve.EdgeResolver;
import jcma.resolve.References;
import jcma.workspace.FileTable;
import jcma.workspace.FreshnessGuard;
import jcma.workspace.FreshnessSource;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The session-scoped owner of the live code-analysis state for one repo (M1 task-11c) — the runtime
 * counterpart to the static {@link Workspace} (root + classpath) and the on-disk {@link LsmStore}. It
 * holds a <b>single shared</b> {@code (LsmStore, FileTable, Indexer, AnalysisEngine)} for the
 * session's lifetime, wires a Tier-1 {@link FreshnessGuard} and a Tier-2 {@link EdgeResolver} over
 * that one state, and serves navigation queries by running <b>refresh → cascade → serve</b>: before
 * an answer is returned, any changed files (drained from the {@link FreshnessSource} and the file the
 * query reads) are re-indexed and the node-diff {@link Cascade} returns their referrers to unresolved.
 *
 * <p>This is the object a long-running process (a REPL today; the MCP server in M2) keeps alive, and
 * the seed of task-12's {@code QueryService} — which will wrap it with cancellation + time-boxing.
 * Deliberately small: no threading, no protocol, no deadlines here.
 */
public final class AnalysisSession implements AutoCloseable {

    private final LsmStore store;
    private final FileTable fileTable;
    private final EdgeResolver resolver;
    private final FreshnessGuard guard;
    private final FreshnessSource source;
    private final Cascade cascade;
    private final Path repoRoot;

    private Set<Path> lastInvalidated = Set.of();

    private AnalysisSession(LsmStore store, FileTable fileTable, EdgeResolver resolver,
            FreshnessGuard guard, FreshnessSource source, Cascade cascade, Path repoRoot) {
        this.store = store;
        this.fileTable = fileTable;
        this.resolver = resolver;
        this.guard = guard;
        this.source = source;
        this.cascade = cascade;
        this.repoRoot = repoRoot;
    }

    /** Open a session with no proactive change producer — the on-access backstop covers freshness. */
    public static AnalysisSession open(Path indexDir, Workspace workspace, Metrics metrics) throws IOException {
        return open(indexDir, workspace, FreshnessSource.none(), metrics);
    }

    /**
     * Open a session over the persisted index, driven by {@code source} as its change producer. Builds
     * one shared store/table/indexer/engine and wires the guard + resolver + cascade over it.
     */
    public static AnalysisSession open(Path indexDir, Workspace workspace, FreshnessSource source,
            Metrics metrics) throws IOException {
        LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics);
        Path usagePath = indexDir.resolve(UsageNameIndexer.FILE_NAME);
        UsageNameIndex usageIndex = Files.exists(usagePath) ? UsageNameIndex.load(usagePath) : null;
        FileTable fileTable = FileTable.load(indexDir);
        Indexer indexer = new Indexer(metrics);
        AnalysisEngine engine = new JavaParserEngine(workspace);
        Path repoRoot = workspace.projectRoot().toAbsolutePath().normalize();

        EdgeResolver resolver = EdgeResolver.over(store, usageIndex, fileTable, engine, indexer, repoRoot, metrics);
        FreshnessGuard guard = new FreshnessGuard(repoRoot, indexDir, fileTable, store, indexer, source, metrics);
        Cascade cascade = new Cascade(guard, resolver, store);
        return new AnalysisSession(store, fileTable, resolver, guard, source, cascade, repoRoot);
    }

    // ------------------------------------------------------------------ queries (refresh → cascade → serve)

    /** Declarations whose simple name equals {@code name} (drains the change producer first). */
    public List<Symbol> declarations(String name) throws IOException {
        refresh(null);
        return resolver.declarations(name);
    }

    /** {@code find_references(target)} — refresh + cascade, then serve from the graph. */
    public References findReferences(Symbol target) throws IOException {
        refresh(fileOf(target));
        return resolver.findReferences(target);
    }

    /** {@code find_definition} by symbol. */
    public Definition findDefinition(Symbol target) throws IOException {
        refresh(fileOf(target));
        return resolver.findDefinition(target);
    }

    /** {@code find_definition} by use-site position (go-to-def). */
    public Optional<Definition> findDefinitionAt(Path file, Position pos) throws IOException {
        refresh(file);
        return resolver.findDefinitionAt(file, pos);
    }

    /**
     * {@code search_symbols} — a pure name search over the live (overlay-aware, phantom-free) index,
     * each hit paired with its declaring path. Refreshes for parity with {@link #declarations}; ranking
     * + filtering are the caller's (off-thread) concern.
     */
    public List<SymbolHit> searchSymbols(String query) throws IOException {
        refresh(null);
        List<SymbolHit> hits = new ArrayList<>();
        for (Symbol s : store.search(query)) {
            hits.add(new SymbolHit(s, fileOf(s)));
        }
        return hits;
    }

    /**
     * {@code find_references} by use-site position (go-to-refs): resolve the occurrence at {@code pos}
     * to its declaration, map that back to an in-store {@link Symbol}, then serve its references. An
     * unresolved site or an external target (no in-store symbol) yields an empty result.
     */
    public References findReferencesAt(Path file, Position pos) throws IOException {
        refresh(file);
        Optional<Definition> def = resolver.findDefinitionAt(file, pos);
        if (def.isEmpty()) {
            return new References(List.of(), List.of());
        }
        Optional<Symbol> target = store.symbol(def.get().moniker());
        if (target.isEmpty()) {
            return new References(List.of(), List.of());
        }
        return resolver.findReferences(target.get());
    }

    /** {@code supertypes(target)} — direct EXTENDS/IMPLEMENTS (+ OVERRIDES for a method). */
    public List<MonikerEdge> supertypes(Symbol target) throws IOException {
        refresh(fileOf(target));
        return resolver.supertypes(target);
    }

    /** {@code subtypes(target)} — direct subtypes / implementors / overriders. */
    public List<MonikerEdge> subtypes(Symbol target) throws IOException {
        refresh(fileOf(target));
        return resolver.subtypes(target);
    }

    /**
     * Drain the change producer, add the file the query is about to read (the on-access backstop), and
     * run the cascade over the union — so a changed file's referrers are returned to unresolved before
     * the query serves an answer.
     */
    private void refresh(Path targetFile) throws IOException {
        Set<Path> changed = new java.util.HashSet<>(source.drainChanged());
        if (targetFile != null) {
            changed.add(targetFile.toAbsolutePath().normalize());
        }
        lastInvalidated = cascade.refresh(changed);
    }

    /** The referrer files the most recent {@link #refresh} returned to unresolved (observability/tests). */
    public Set<Path> invalidatedByLastRefresh() {
        return lastInvalidated;
    }

    /** A moniker's display signature (passthrough to the resolver) — for CLI/REPL rendering. */
    public String signatureOf(String moniker) {
        return resolver.signatureOf(moniker);
    }

    private Path fileOf(Symbol target) {
        if (target == null || target.fileId() < 0) {
            return null;
        }
        Path rel = fileTable.pathOf(target.fileId());
        return rel == null ? null : repoRoot.resolve(rel);
    }

    @Override
    public void close() throws IOException {
        resolver.close(); // closes the shared store + usage index
    }
}
