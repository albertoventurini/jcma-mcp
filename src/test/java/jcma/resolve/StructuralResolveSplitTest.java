package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.IndexFixture;
import jcma.engine.JavaParserEngine;
import jcma.index.CompactionPolicy;
import jcma.index.EdgeType;
import jcma.index.LsmStore;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.index.UsageNameIndex;
import jcma.index.UsageNameIndexer;
import jcma.obs.Metrics;
import jcma.session.AnalysisSession;
import jcma.workspace.FileTable;
import jcma.workspace.TreeScanSource;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B0 + B1 (red-first) — the structural resolve layer is split so that the {@code find_references} path
 * resolves <b>type-refs only</b> (never the hierarchy layer, which it does not read) and is
 * <b>name-scoped</b> (resolves only the queried name's type-refs, not every type-ref in the file),
 * while the hierarchy-query path ({@code subtypes}/{@code supertypes}) resolves the <b>hierarchy layer
 * only</b> (never type-refs it does not read). Both layers stay lazily correct via independent markers.
 *
 * <p>Counting is done with a {@link CountingEngine} spy (zero-call assertions on each layer) and the
 * {@code resolve.typerefs} metric (the per-name scoping count). Completeness is asserted alongside
 * every "resolved zero of the other layer" claim, so a trivially-empty answer cannot pass.
 */
class StructuralResolveSplitTest {

    private static final Path REFS_SUPERTYPE = Path.of("src/test/resources/fixtures/resolve/refs-supertype");
    private static final Path TYPEREF_SCOPE = Path.of("src/test/resources/fixtures/resolve/typeref-scope");

    // ------------------------------------------------------------------ B0: no hierarchy on find_references

    @Test
    void findReferencesResolvesTypeRefsButNeverTheHierarchyLayer(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(REFS_SUPERTYPE, indexDir);
        CountingEngine spy = engineFor(REFS_SUPERTYPE);
        try (EdgeResolver resolver = resolverOver(indexDir, REFS_SUPERTYPE, spy, Metrics.create())) {
            References refs = resolver.findReferences(typeDecl(resolver, "Shape", "app/Shape#"));

            assertEquals(0, spy.hierarchyCalls,
                    "find_references reads only TYPE_REF/value edges — the hierarchy layer is pure waste here");
            assertEquals(4, refs.totalRefs(),
                    "completeness unchanged: Canvas field + Canvas param + Circle/Square `implements Shape`");
        }
    }

    // ------------------------------------------------------------------ B1: name-scoped type-refs

    @Test
    void findReferencesResolvesOnlyTheQueriedNamesTypeRefs(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(TYPEREF_SCOPE, indexDir);
        Metrics metrics = Metrics.create();
        try (EdgeResolver resolver = resolverOver(indexDir, TYPEREF_SCOPE, engineFor(TYPEREF_SCOPE), metrics)) {
            References refs = resolver.findReferences(typeDecl(resolver, "Alpha", "app/Alpha#"));

            assertEquals(2, metrics.counter("resolve.typerefs").sum(),
                    "User references 5 types, but Alpha-scoped resolve touches only the 2 Alpha type-refs");
            assertEquals(2, refs.totalRefs(), "completeness: both Alpha use-sites (field + parameter) are found");
        }
    }

    // ------------------------------------------------------------------ B1: cache semantics across names

    @Test
    void perNameTypeRefCacheResolvesEachNameOnceAndKeepsBothCorrect(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(TYPEREF_SCOPE, indexDir);
        Metrics metrics = Metrics.create();
        try (EdgeResolver resolver = resolverOver(indexDir, TYPEREF_SCOPE, engineFor(TYPEREF_SCOPE), metrics)) {
            long t0 = metrics.counter("resolve.typerefs").sum();

            int alpha = resolver.findReferences(typeDecl(resolver, "Alpha", "app/Alpha#")).totalRefs();
            long afterAlpha = metrics.counter("resolve.typerefs").sum();
            assertEquals(2, alpha, "Alpha: 2 refs");
            assertEquals(2, afterAlpha - t0, "Alpha resolves its 2 type-refs");

            int beta = resolver.findReferences(typeDecl(resolver, "Beta", "app/Beta#")).totalRefs();
            long afterBeta = metrics.counter("resolve.typerefs").sum();
            assertEquals(1, beta, "Beta: 1 ref");
            assertEquals(1, afterBeta - afterAlpha, "Beta resolves only its own 1 type-ref, not Alpha's again");

            int alphaAgain = resolver.findReferences(typeDecl(resolver, "Alpha", "app/Alpha#")).totalRefs();
            long afterAlphaAgain = metrics.counter("resolve.typerefs").sum();
            assertEquals(2, alphaAgain, "Alpha again: same 2 refs");
            assertEquals(0, afterAlphaAgain - afterBeta, "the second Alpha query resolves 0 type-refs (per-name cache)");
        }
    }

    // ------------------------------------------------------------------ B1's symmetric half: hierarchy-only path

    @Test
    void subtypesResolvesTheHierarchyLayerButNeverTypeRefs(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(REFS_SUPERTYPE, indexDir);
        CountingEngine spy = engineFor(REFS_SUPERTYPE);
        try (EdgeResolver resolver = resolverOver(indexDir, REFS_SUPERTYPE, spy, Metrics.create())) {
            List<MonikerEdge> subs = resolver.subtypes(typeDecl(resolver, "Shape", "app/Shape#"));

            assertEquals(0, spy.typeRefCalls,
                    "subtypes walks only hierarchy edges — resolving type-refs would be pure waste");
            assertTrue(spy.hierarchyCalls > 0, "but it does resolve the hierarchy layer");
            long implementors = subs.stream().filter(e -> e.type() == EdgeType.IMPLEMENTS).count();
            assertEquals(2, implementors, "completeness: Circle + Square IMPLEMENTS Shape");
        }
    }

    // ------------------------------------------------------------------ cascade regression: hierarchy still whole-file in reResolve

    @Test
    void editingToAddASupertypeIsReflectedBySubtypes(@TempDir Path repo) throws Exception {
        write(repo, "Base.java", "package app; public class Base {}");
        write(repo, "Service.java", "package app; public class Service {}");
        Path service = repo.resolve("app").resolve("Service.java");
        Path indexDir = repo.resolve(".jcma");
        IndexFixture.build(repo, indexDir);

        TreeScanSource source = new TreeScanSource(List.of(repo));
        try (AnalysisSession s = AnalysisSession.open(indexDir, Workspace.discover(repo), source, Metrics.create())) {
            Symbol base = s.declarations("Base").stream().filter(b -> b.moniker().equals("app/Base#"))
                    .findFirst().orElseThrow(() -> new AssertionError("Base not indexed"));
            assertTrue(s.subtypes(base).isEmpty(), "Service does not extend Base yet");

            edit(service, "package app; public class Service extends Base {}");

            List<MonikerEdge> subs = s.subtypes(base); // scanner drains Service → hierarchy cascade (reResolve)
            assertTrue(subs.stream().anyMatch(e -> e.type() == EdgeType.EXTENDS && e.src().equals("app/Service#")),
                    "the supertype edit cascades whole-file in reResolve: Service now EXTENDS Base, got: " + subs);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A resolver over a built index, with an injected (spy) engine — the {@code over} path of the resolver. */
    private static EdgeResolver resolverOver(Path indexDir, Path repo, CountingEngine engine, Metrics metrics)
            throws IOException {
        LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics);
        Path usagePath = indexDir.resolve(UsageNameIndexer.FILE_NAME);
        UsageNameIndex usageIndex = Files.exists(usagePath) ? UsageNameIndex.load(usagePath) : null;
        FileTable fileTable = FileTable.load(indexDir);
        Path repoRoot = Workspace.ofSourceRoot(repo).projectRoot().toAbsolutePath().normalize();
        return EdgeResolver.over(store, usageIndex, fileTable, engine, repoRoot, metrics);
    }

    private static CountingEngine engineFor(Path repo) {
        return new CountingEngine(new JavaParserEngine(Workspace.ofSourceRoot(repo)));
    }

    private static Symbol typeDecl(EdgeResolver resolver, String name, String moniker) {
        return resolver.declarations(name).stream()
                .filter(s -> moniker.equals(s.moniker()))
                .findFirst().orElseThrow(() -> new AssertionError(moniker + " not indexed"));
    }

    private static void write(Path repo, String name, String content) throws IOException {
        Path dir = repo.resolve("app");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    private static void edit(Path file, String content) throws IOException {
        Files.writeString(file, content);
        long later = Files.getLastModifiedTime(file).toMillis() + 5_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(later));
    }
}
