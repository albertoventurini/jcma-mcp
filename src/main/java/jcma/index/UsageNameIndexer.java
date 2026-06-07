package jcma.index;

import jcma.engine.StructuralParser;
import jcma.engine.UsageSite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds {@code usage-names.seg} — the <b>use-site</b> name index (PRD §5.1; task-10), distinct from
 * the declaration index ({@code trigrams.seg}). Where the declaration index answers "where is {@code
 * N} defined?", this one answers "which files contain an unresolved <em>use</em> of {@code N}?" — the
 * {@code find_references} candidate-file prune, so the first {@code find_references(X)} resolves a
 * handful of files instead of the tree, with no rescan.
 *
 * <p>Built parse-only from the current file set: each use-site's syntactic target name accumulates its
 * file id into a {@link TreeMap}{@code <String, }{@link TreeSet}{@code <Integer>>} (dedup + ascending
 * sort fall out for free), then written through {@link UsageNameIndex} as an exact-match {@code name →
 * sorted distinct fileIds} inverted index. The enclosing symbol + exact target are re-derived when a
 * candidate file is resolved.
 *
 * <p><b>Freshness (task-10 baseline):</b> rebuilt from the whole current file set whenever a reconcile
 * pass changes anything (cold index = every file). Making this incremental — updating only the edited
 * files' usage rows — is task-11 (invalidation); a full rebuild is correct, just not yet minimal.
 */
public final class UsageNameIndexer {

    /** File name of the usage-name index within an index directory. */
    public static final String FILE_NAME = "usage-names.seg";

    private UsageNameIndexer() {}

    /**
     * Parse-only walk of {@code filesById} ({@code fileId → absolute path}); write each file's
     * use-site target-names into a fresh {@code usage-names.seg}. A file that fails to parse is
     * skipped (the declaration pass already logged it).
     */
    public static void build(Path indexDir, Map<Integer, Path> filesById) throws IOException {
        StructuralParser parser = new StructuralParser();
        TreeMap<String, TreeSet<Integer>> byName = new TreeMap<>();
        for (Map.Entry<Integer, Path> e : filesById.entrySet()) {
            try {
                for (UsageSite u : parser.usages(e.getValue())) {
                    byName.computeIfAbsent(u.targetName(), k -> new TreeSet<>()).add(e.getKey());
                }
            } catch (IOException skip) {
                // unparseable here too → no usage rows for this file (not fatal)
            }
        }
        UsageNameIndex.write(indexDir.resolve(FILE_NAME), byName);
    }
}
