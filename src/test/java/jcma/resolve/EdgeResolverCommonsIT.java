package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jcma.cli.Main;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-10 (red-first) — find-references <b>recall against the M0 hand-labeled worksheet oracle</b>
 * (PRD §10; §Targets: ≥ 98% recall, ~0% FP, mandatory unconfirmed tail) on the pinned commons-lang
 * corpus. The worksheet ({@code findrefs-worksheet-commons.md}) lists, per target symbol, the call
 * sites M0's engine linked to it ("found refs"); Tier-2 uses the same engine, so it must reproduce
 * them. Also asserts the unconfirmed-tail property holds for an overloaded symbol (the M0 finding:
 * primitive-array overloads in {@code isEmpty}/{@code length} leave unresolvable candidates).
 */
class EdgeResolverCommonsIT {

    private static final Path CORPUS = Path.of("milestones/m0-spike/corpus/commons-lang");
    private static final Path WORKSHEET = Path.of("milestones/m0-spike/out/findrefs-worksheet-commons.md");

    @TempDir
    static Path indexDir; // shared: index the corpus once for the whole class

    @BeforeAll
    static void indexCorpusOnce() {
        if (Files.isDirectory(CORPUS)) {
            index(CORPUS, indexDir);
        }
    }

    @Test
    void reproducesLabeledFoundRefsForTheTopSymbol() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS), "pinned commons-lang corpus present");
        assumeTrue(Files.isRegularFile(WORKSHEET), "M0 find-refs worksheet present");

        // Oracle: the first worksheet section is SystemProperties.getProperty(java.lang.String).
        Set<String> labeled = labeledFoundRefs(WORKSHEET);   // keyed "ClassName.java:line"
        assumeTrue(labeled.size() > 50, "worksheet has a substantial labeled set: " + labeled.size());

        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(CORPUS), Metrics.create())) {
            Symbol target = resolver.declarations("getProperty").stream()
                    .filter(s -> "org/apache/commons/lang3/SystemProperties#getProperty(String).".equals(s.moniker()))
                    .findFirst().orElseThrow(() -> new AssertionError("SystemProperties.getProperty(String) not indexed"));

            References refs = resolver.findReferences(target);
            Set<String> got = new HashSet<>();
            refs.groups().forEach(g -> g.refs().forEach(r ->
                    got.add(r.file().getFileName() + ":" + r.range().startLine())));

            long hit = labeled.stream().filter(got::contains).count();
            double recall = (double) hit / labeled.size();
            assertTrue(recall >= 0.98, "find-refs recall vs worksheet oracle = " + recall + " (" + hit + "/" + labeled.size() + ")");
        }
    }

    @Test
    void overloadedSymbolSurfacesAnUnconfirmedTail() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS), "pinned commons-lang corpus present");

        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(CORPUS), Metrics.create())) {
            // The M0 finding: at least one isEmpty overload has candidates that cannot be disambiguated.
            List<Symbol> overloads = resolver.declarations("isEmpty");
            assumeTrue(!overloads.isEmpty(), "isEmpty overloads indexed");

            boolean anyUnconfirmed = overloads.stream()
                    .anyMatch(s -> resolver.findReferences(s).hasUnconfirmedTail());
            assertTrue(anyUnconfirmed,
                    "an overloaded symbol must surface an unconfirmed tail, not a silent miss");
        }
    }

    // ------------------------------------------------------------------ worksheet oracle

    private static final Pattern FOUND_REF =
            Pattern.compile("^\\|\\s*\\S*/([^/|]+\\.java):(\\d+)\\s*\\|");

    /** The "found refs" {@code file:line} sites under the worksheet's first symbol section. */
    private static Set<String> labeledFoundRefs(Path worksheet) throws Exception {
        Set<String> out = new HashSet<>();
        boolean inFound = false;
        for (String line : Files.readAllLines(worksheet)) {
            if (line.startsWith("### found refs")) {
                inFound = true;
                continue;
            }
            if (line.startsWith("### not-linked") || line.startsWith("## ")) {
                if (inFound) {
                    break; // end of the first symbol's found-refs table
                }
                continue;
            }
            if (inFound) {
                Matcher m = FOUND_REF.matcher(line);
                if (m.find()) {
                    out.add(m.group(1) + ":" + m.group(2));
                }
            }
        }
        return out;
    }

    private static void index(Path repo, Path indexDir) {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        int code = Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink);
        assertTrue(code == 0, "jcma index should succeed");
    }
}
