package jcma.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jcma.engine.UsageSite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the two producers of {@code usage-names.seg} against drift. The cold-index pass builds the
 * index from use-sites already collected off the parallel declaration parse ({@link
 * UsageNameIndexer#buildFrom}, fed by {@link Indexer.ParseResult#usagesByFile()}); the incremental
 * fallback re-parses every current file ({@link UsageNameIndexer#build}). Both must yield the same
 * index — same {@code name → sorted fileIds} — so {@code find_references} is unaffected by which path ran.
 */
class UsageNameIndexerTest {

    private static final Path SHAPES = Path.of("src/test/resources/fixtures/indexer/com/example/shapes");

    @Test
    void buildFromCollectedUsagesMatchesReparseBuild(@TempDir Path reparseDir, @TempDir Path collectedDir)
            throws IOException {
        // The fixture file set, under stable ids 0..n-1.
        Map<Integer, Path> filesById = new LinkedHashMap<>();
        List<Path> files = List.of(
                SHAPES.resolve("Circle.java"), SHAPES.resolve("Shape.java"),
                SHAPES.resolve("Day.java"), SHAPES.resolve("Point.java"));
        List<Indexer.ParseRequest> requests = new java.util.ArrayList<>();
        for (int id = 0; id < files.size(); id++) {
            filesById.put(id, files.get(id));
            requests.add(new Indexer.ParseRequest(id, files.get(id), SourceSet.MAIN));
        }

        // Producer A: the re-parse build (the incremental fallback).
        UsageNameIndexer.build(reparseDir, filesById);

        // Producer B: buildFrom the use-sites carried out of the real parallel parse (the cold path).
        Map<Integer, List<UsageSite>> usagesByFile = new Indexer().parseAll(requests).usagesByFile();
        UsageNameIndexer.buildFrom(collectedDir, usagesByFile);

        // Both funnel through UsageNameIndex.write over a TreeMap<String,TreeSet<Integer>>, so equal
        // maps ⇒ byte-identical segments. A byte diff is the tightest guard against the two diverging.
        byte[] reparse = Files.readAllBytes(reparseDir.resolve(UsageNameIndexer.FILE_NAME));
        byte[] collected = Files.readAllBytes(collectedDir.resolve(UsageNameIndexer.FILE_NAME));
        assertArrayEquals(reparse, collected, "buildFrom must produce the same segment as the re-parse build");

        // Sanity: the index is non-trivial, and a sampled exact-name lookup agrees across both.
        try (UsageNameIndex a = UsageNameIndex.load(reparseDir.resolve(UsageNameIndexer.FILE_NAME));
                UsageNameIndex b = UsageNameIndex.load(collectedDir.resolve(UsageNameIndexer.FILE_NAME))) {
            assertTrue(a.nNames() > 0, "the fixtures contain use-sites");
            assertEquals(a.nNames(), b.nNames());
            assertEquals(a.entryCount(), b.entryCount());
            assertArrayEquals(a.candidateFiles("Shape"), b.candidateFiles("Shape"),
                    "a sampled name's candidate files agree");
        }
    }
}
