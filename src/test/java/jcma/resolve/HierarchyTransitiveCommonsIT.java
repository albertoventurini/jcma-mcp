package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-05 (opt-in) — the transitive hierarchy walk on the pinned commons-lang corpus
 * (pattern from {@link EdgeResolverCommonsIT}; {@code assumeTrue} the corpus is present). Asserts a
 * known project supertype chain resolves transitively: {@code ReflectionToStringBuilder} extends the
 * project class {@code ToStringBuilder}, so the upward closure must contain that project supertype —
 * proving the lazy per-node warm + walk runs against a real, multi-thousand-file index.
 */
class HierarchyTransitiveCommonsIT {

    private static final Path CORPUS = Path.of("milestones/m0-spike/corpus/commons-lang");

    @TempDir
    static Path indexDir;

    @Test
    void supertypesResolveTransitivelyToAProjectSuperclass() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS), "pinned commons-lang corpus present");
        IndexFixture.buildWithCachedClasspath(CORPUS, indexDir);

        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(CORPUS, indexDir), Metrics.create())) {
            Symbol target = resolver.declarations("ReflectionToStringBuilder").stream()
                    .filter(s -> "org/apache/commons/lang3/builder/ReflectionToStringBuilder#".equals(s.moniker()))
                    .findFirst().orElseThrow(() -> new AssertionError("ReflectionToStringBuilder not indexed"));

            Hierarchy.Result result = resolver.supertypesTransitive(target);
            Set<String> monikers = result.nodes().stream().map(HierarchyNode::moniker).collect(Collectors.toSet());
            assertTrue(monikers.contains("org/apache/commons/lang3/builder/ToStringBuilder#"),
                    "the project superclass is in the transitive closure: " + monikers);
        }
    }
}
