package jcma.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jcma.IndexFixture;
import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.response.BudgetPolicy;
import jcma.response.ToolResult;
import jcma.session.AnalysisSession;
import jcma.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-04 (opt-in IT) — the {@code find_references}/{@code find_definition} tools on the pinned
 * commons-lang corpus, reproducing the M1 worksheet oracles end-to-end through the tool + shaping +
 * {@link QueryService} stack (cf. {@code EdgeResolverCommonsIT}). The budget is {@link
 * BudgetPolicy#manual()} so line-level locations survive for the recall check (the default cap would
 * roll a hot symbol's hundreds of refs up to per-file counts). Skipped unless the corpus is present.
 */
class ToolsCommonsIT {

    private static final Path CORPUS = Path.of("milestones/m0-spike/corpus/commons-lang");
    private static final Path WORKSHEET = Path.of("milestones/m0-spike/out/findrefs-worksheet-commons.md");

    @Test
    void findReferencesReproducesTheWorksheetFoundRefs(@TempDir Path indexDir) throws Exception {
        assumeTrue(Files.isDirectory(CORPUS), "pinned commons-lang corpus present");
        assumeTrue(Files.isRegularFile(WORKSHEET), "M0 find-refs worksheet present");
        Set<String> labeled = labeledFoundRefs(WORKSHEET); // keyed "ClassName.java:line"
        assumeTrue(labeled.size() > 50, "worksheet has a substantial labeled set: " + labeled.size());

        try (QueryService svc = corpusService(indexDir)) {
            FindReferencesTool tool = new FindReferencesTool(() -> svc, BudgetPolicy.manual());
            // The worksheet's first symbol section: SystemProperties.getProperty(java.lang.String).
            ToolResult r = tool.call(args("{\"symbol\":\"SystemProperties.getProperty\"}"));
            String out = r.render();

            long hit = labeled.stream().filter(site -> out.contains(site)).count();
            double recall = (double) hit / labeled.size();
            assertTrue(recall >= 0.98,
                    "find_references recall vs worksheet oracle = " + recall + " (" + hit + "/" + labeled.size() + ")");
        }
    }

    @Test
    void findDefinitionLandsTheHotSymbolDeclaration(@TempDir Path indexDir) throws Exception {
        assumeTrue(Files.isDirectory(CORPUS), "pinned commons-lang corpus present");

        try (QueryService svc = corpusService(indexDir)) {
            FindDefinitionTool tool = new FindDefinitionTool(() -> svc, BudgetPolicy.manual());
            ToolResult r = tool.call(args("{\"symbol\":\"SystemProperties.getProperty\"}"));
            String out = r.render();
            assertTrue(out.contains("SystemProperties.java"),
                    "by-symbol find_definition lands in the declaring file: " + out);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static QueryService corpusService(Path indexDir) throws Exception {
        IndexFixture.buildWithCachedClasspath(CORPUS, indexDir);
        return new QueryService(AnalysisSession.open(indexDir, Workspace.discover(CORPUS, indexDir), Metrics.noop()));
    }

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
                    break;
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

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }
}
