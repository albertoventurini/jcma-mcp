package jcma.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import jcma.IndexFixture;
import jcma.index.SearchSpec;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 task-01 — the {@link AnalysisSession#searchText} read API end-to-end over a real persisted
 * index: a text query resolves to {@link TextHit}s carrying the declaring <b>path</b> (not a raw
 * fileId) and a hyphenated <b>kind label</b>, and an absent query returns empty (not error). The
 * indexer fixtures carry Javadoc, so a Javadoc phrase exercises the {@code javadoc} label + path
 * resolution.
 */
class SessionTextSearchTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/indexer");

    @Test
    void findsJavadocPhraseLabelledAndPathResolved(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("idx");
        IndexFixture.build(FIXTURE, indexDir);
        try (AnalysisSession session =
                AnalysisSession.open(indexDir, Workspace.discover(FIXTURE), Metrics.noop())) {
            // "minimal interface" appears only in Shape.java's class Javadoc.
            List<TextHit> hits = session.searchText("minimal interface");
            assertFalse(hits.isEmpty(), "the Javadoc phrase is found");
            TextHit hit = hits.get(0);
            assertTrue(hit.kind().equals("javadoc"), "labelled javadoc, got: " + hit.kind());
            assertTrue(hit.file() != null && hit.file().endsWith("Shape.java"),
                    "resolved to its declaring path, got: " + hit.file());
            assertTrue(hit.snippet().contains("minimal interface"), "snippet carries the matched line");

            assertTrue(session.searchText("zzzz-no-such-text").isEmpty(), "absent query → empty, not error");
        }
    }

    @Test
    void regexAndCaseInsensitiveOverTheSession(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("idx");
        IndexFixture.build(FIXTURE, indexDir);
        try (AnalysisSession session =
                AnalysisSession.open(indexDir, Workspace.discover(FIXTURE), Metrics.noop())) {
            // A regex spanning the Javadoc phrase "minimal interface".
            List<TextHit> regex = session.searchText(SearchSpec.of("minimal.*interface", false, true));
            assertFalse(regex.isEmpty(), "the `.*` regex finds the Javadoc phrase");
            assertTrue(regex.get(0).file().endsWith("Shape.java"), "resolved to its declaring path");
            // Case-sensitive by default misses; opting out matches.
            assertTrue(session.searchText(SearchSpec.of("MINIMAL", false, true)).isEmpty(), "sensitive by default");
            assertFalse(session.searchText(SearchSpec.of("MINIMAL", false, false)).isEmpty(),
                    "case_sensitive:false matches");
        }
    }
}
