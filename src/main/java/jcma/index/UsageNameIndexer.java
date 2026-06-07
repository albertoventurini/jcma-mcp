package jcma.index;

import jcma.engine.StructuralParser;
import jcma.engine.UsageSite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@code usage-names.seg} — the <b>use-site</b> name index (PRD §5.1; task-10), a second
 * {@link TrigramIndex} instance distinct from the declaration index ({@code trigrams.seg}). Where the
 * declaration index answers "where is {@code N} defined?", this one answers "which files contain an
 * unresolved <em>use</em> of {@code N}?" — the {@code find_references} candidate-file prune, so the
 * first {@code find_references(X)} resolves a handful of files instead of the tree, with no rescan.
 *
 * <p>Built parse-only from the current file set (each use-site's syntactic target name → its file id),
 * reusing the same {@code (name, –, fileId)} {@link TrigramIndex.Entry} shape and read back through
 * {@link TrigramIndex#candidateFiles}. The {@code symbolId} column is unused here (use-sites are not
 * declarations); the enclosing symbol + exact target are re-derived when the candidate is resolved.
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
        List<TrigramIndex.Entry> entries = new ArrayList<>();
        for (Map.Entry<Integer, Path> e : filesById.entrySet()) {
            try {
                for (UsageSite u : parser.usages(e.getValue())) {
                    entries.add(new TrigramIndex.Entry(u.targetName(), -1, e.getKey()));
                }
            } catch (IOException skip) {
                // unparseable here too → no usage rows for this file (not fatal)
            }
        }
        TrigramIndex.write(indexDir.resolve(FILE_NAME), entries);
    }
}
