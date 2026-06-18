import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The accuracy comparator + markdown report for the jcma dependency-resolution QA harness. Run as a
 * JDK single-file source-launch:
 * <pre>
 *   java Compare.java &lt;oracle.tsv&gt; &lt;jcma.tsv&gt; &lt;repo-types.txt&gt; &lt;perf.properties&gt; \
 *                     &lt;report.md&gt; &lt;diffs.tsv&gt; &lt;repoName&gt;
 * </pre>
 *
 * <p>Both TSVs are {@code RELATION<TAB>owner<TAB>dep} rows (the oracle is the javac AST truth, jcma
 * is {@code jcma resolve-file} output; jcma {@code UNRESOLVED} rows are counted as diagnostics but
 * excluded from scoring — an unresolved mention is simply not in jcma's set, i.e. a miss). For each
 * relation ({@code SUPERTYPE}, {@code TYPEREF}) it reports recall/precision, partitioned into
 * <b>intra-repo</b> (the dep is a repo-declared type, per {@code repo-types.txt}) — the headline
 * correctness signal — and <b>external</b> (JDK/library, classpath-dependent). Per-owner diffs go to
 * {@code diffs.tsv} for spot-checking; performance numbers come from {@code perf.properties}.
 */
public final class Compare {

    /** A directed dependency edge: which type depends on which, under a relation + partition. */
    private record Edge(String owner, String dep) {}

    private static final String[] RELATIONS = {"SUPERTYPE", "TYPEREF"};

    public static void main(String[] args) throws IOException {
        if (args.length != 7) {
            System.err.println("usage: java Compare.java <oracle.tsv> <jcma.tsv> <repo-types.txt> "
                    + "<perf.properties> <report.md> <diffs.tsv> <repoName>");
            System.exit(2);
        }
        Path oracleTsv = Path.of(args[0]);
        Path jcmaTsv = Path.of(args[1]);
        Path repoTypesTxt = Path.of(args[2]);
        Path perfProps = Path.of(args[3]);
        Path reportMd = Path.of(args[4]);
        Path diffsTsv = Path.of(args[5]);
        String repoName = args[6];

        Set<String> repoTypes = new TreeSet<>(readLines(repoTypesTxt));
        List<String[]> oracleRows = readRows(oracleTsv, false);
        long jcmaUnresolved = countUnresolved(jcmaTsv);
        List<String[]> jcmaRows = readRows(jcmaTsv, true);

        Properties perf = new Properties();
        if (Files.exists(perfProps)) {
            try (var in = Files.newInputStream(perfProps)) {
                perf.load(in);
            }
        }

        StringBuilder md = new StringBuilder();
        List<String> diffs = new ArrayList<>();
        diffs.add("relation\tkind\towner\tdep\tpartition");

        md.append("# jcma dependency-resolution QA — ").append(repoName).append("\n\n");
        md.append("Accuracy of `jcma resolve-file` (JavaParser + JavaSymbolSolver, source-level) ")
                .append("against a **javac AST oracle** (full `parse()`+`analyze()` over the same ")
                .append("sources and classpath). Apples-to-apples: both resolve type mentions at the ")
                .append("source level; the comparison is over **erased type-element FQNs**, deduped, ")
                .append("excluding primitives and `java.lang.Object`. Types the oracle sees only via ")
                .append("inference (`var` locals, implicit lambda params — never written in source) are ")
                .append("split out as an *out-of-contract* category, not charged against headline ")
                .append("recall.\n\n");

        appendCoverage(md, oracleRows, jcmaRows, jcmaUnresolved, repoTypes);

        for (String relation : RELATIONS) {
            appendRelation(md, diffs, relation, oracleRows, jcmaRows, repoTypes);
        }

        appendInferred(md, diffs, oracleRows, jcmaRows, repoTypes);
        appendPerf(md, perf);
        appendMethodNotes(md);

        Files.writeString(reportMd, md.toString());
        Files.write(diffsTsv, diffs);
        System.err.println("compare: wrote " + reportMd + " and " + diffsTsv);
    }

    // ---------------------------------------------------------------- sections

    private static void appendCoverage(StringBuilder md, List<String[]> oracle, List<String[]> jcma,
            long jcmaUnresolved, Set<String> repoTypes) {
        Set<String> oracleOwners = owners(oracle);
        Set<String> jcmaOwners = owners(jcma);
        Set<String> missedOwners = new TreeSet<>(oracleOwners);
        missedOwners.removeAll(jcmaOwners);

        md.append("## Coverage\n\n");
        md.append("| metric | value |\n|---|---|\n");
        md.append("| repo types (declared, from index) | ").append(repoTypes.size()).append(" |\n");
        md.append("| types with ≥1 dependency (oracle) | ").append(oracleOwners.size()).append(" |\n");
        md.append("| types with ≥1 dependency (jcma) | ").append(jcmaOwners.size()).append(" |\n");
        md.append("| oracle owner types jcma never emitted | ").append(missedOwners.size()).append(" |\n");
        md.append("| jcma unresolved type mentions (safe-degraded misses) | ").append(jcmaUnresolved).append(" |\n");
        md.append("\n");
        if (!missedOwners.isEmpty()) {
            md.append("> Owner types present in the oracle but absent from jcma output: `")
                    .append(String.join("`, `", limit(missedOwners, 20))).append("`")
                    .append(missedOwners.size() > 20 ? " …" : "").append("\n\n");
        }
    }

    private static void appendRelation(StringBuilder md, List<String> diffs, String relation,
            List<String[]> oracleRows, List<String[]> jcmaRows, Set<String> repoTypes) {
        Set<Edge> oracleAll = edges(oracleRows, relation);
        Set<Edge> jcmaAll = edges(jcmaRows, relation);

        md.append("## ").append(relation.equals("SUPERTYPE") ? "Supertypes (extends/implements)"
                : "Outgoing type references").append("\n\n");
        md.append("| partition | oracle | jcma | matched | recall | precision |\n");
        md.append("|---|---|---|---|---|---|\n");
        appendPartitionRow(md, "intra-repo", filter(oracleAll, repoTypes, true),
                filter(jcmaAll, repoTypes, true));
        appendPartitionRow(md, "external", filter(oracleAll, repoTypes, false),
                filter(jcmaAll, repoTypes, false));
        appendPartitionRow(md, "all", oracleAll, jcmaAll);
        md.append("\n");

        // Macro (per-owner mean) for the headline intra-repo partition.
        Set<Edge> oIntra = filter(oracleAll, repoTypes, true);
        Set<Edge> jIntra = filter(jcmaAll, repoTypes, true);
        double[] macro = macroAverages(oIntra, jIntra);
        md.append(String.format("Intra-repo **macro** (per-type mean): recall %.1f%%, precision %.1f%% "
                + "over %d owner type(s).%n%n", macro[0] * 100, macro[1] * 100, (int) macro[2]));

        // Worst offenders (intra-repo), by total diff size, and the diff rows.
        Map<String, List<String>> missByOwner = new TreeMap<>();
        Map<String, List<String>> extraByOwner = new TreeMap<>();
        for (Edge e : oIntra) {
            if (!jIntra.contains(e)) {
                missByOwner.computeIfAbsent(e.owner(), k -> new ArrayList<>()).add(e.dep());
                diffs.add(relation + "\tMISS\t" + e.owner() + "\t" + e.dep() + "\tintra");
            }
        }
        for (Edge e : jIntra) {
            if (!oIntra.contains(e)) {
                extraByOwner.computeIfAbsent(e.owner(), k -> new ArrayList<>()).add(e.dep());
                diffs.add(relation + "\tEXTRA\t" + e.owner() + "\t" + e.dep() + "\tintra");
            }
        }
        // External diffs to the diff file too (not headlined, but spot-checkable).
        Set<Edge> oExt = filter(oracleAll, repoTypes, false);
        Set<Edge> jExt = filter(jcmaAll, repoTypes, false);
        for (Edge e : oExt) {
            if (!jExt.contains(e)) {
                diffs.add(relation + "\tMISS\t" + e.owner() + "\t" + e.dep() + "\texternal");
            }
        }
        for (Edge e : jExt) {
            if (!oExt.contains(e)) {
                diffs.add(relation + "\tEXTRA\t" + e.owner() + "\t" + e.dep() + "\texternal");
            }
        }

        appendWorstOffenders(md, missByOwner, extraByOwner);
    }

    /**
     * The out-of-contract category: dependencies the oracle sees only through an inferred position
     * ({@code var} local / implicit lambda parameter) and never as a written type. jcma's syntactic
     * scan cannot reach these by design, so they are reported separately and excluded from headline
     * recall. "inferred-only" = TYPEREF_INFERRED minus TYPEREF (a dep also written somewhere is already
     * covered by the syntactic headline, so it is not a gap).
     */
    private static void appendInferred(StringBuilder md, List<String> diffs, List<String[]> oracleRows,
            List<String[]> jcmaRows, Set<String> repoTypes) {
        Set<Edge> inferred = edges(oracleRows, "TYPEREF_INFERRED");
        Set<Edge> syntactic = edges(oracleRows, "TYPEREF");
        Set<Edge> inferredOnly = new TreeSet<>(Comparator.comparing(Edge::owner).thenComparing(Edge::dep));
        for (Edge e : inferred) {
            if (!syntactic.contains(e)) {
                inferredOnly.add(e);
            }
        }
        Set<Edge> jcmaTyperef = edges(jcmaRows, "TYPEREF");
        Set<Edge> intraOnly = filter(inferredOnly, repoTypes, true);
        Set<Edge> extOnly = filter(inferredOnly, repoTypes, false);

        md.append("## Inferred-only type references (out of navigation contract)\n\n");
        md.append("Dependencies the javac oracle sees **only** through an inferred position — a `var` ")
                .append("local or an implicit lambda parameter, where the type name is never written in ")
                .append("source. jcma's syntactic occurrence scan (like IntelliJ's *Find Usages* on a ")
                .append("type) does not surface these by design, so they are reported here separately and ")
                .append("are **not** part of the headline recall above.\n\n");
        md.append("| partition | inferred-only (oracle) | jcma also reports | coverage |\n");
        md.append("|---|---|---|---|\n");
        appendInferredRow(md, "intra-repo", intraOnly, jcmaTyperef);
        appendInferredRow(md, "external", extOnly, jcmaTyperef);
        md.append("\n");

        if (intraOnly.isEmpty()) {
            md.append("No intra-repo inferred-only dependencies.\n\n");
            return;
        }
        Map<String, List<String>> byOwner = new TreeMap<>();
        for (Edge e : intraOnly) {
            byOwner.computeIfAbsent(e.owner(), k -> new ArrayList<>()).add(e.dep());
            diffs.add("TYPEREF_INFERRED\tINFERRED_ONLY\t" + e.owner() + "\t" + e.dep() + "\tintra");
        }
        md.append("Intra-repo inferred-only dependencies (owner → inferred dep):\n\n");
        md.append("| owner | inferred-only deps |\n|---|---|\n");
        for (Map.Entry<String, List<String>> en : byOwner.entrySet()) {
            md.append("| `").append(shorten(en.getKey())).append("` | ")
                    .append(fmtDeps(en.getValue())).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendInferredRow(StringBuilder md, String label, Set<Edge> inferredOnly,
            Set<Edge> jcma) {
        long also = inferredOnly.stream().filter(jcma::contains).count();
        double cov = inferredOnly.isEmpty() ? 0.0 : (double) also / inferredOnly.size();
        md.append(String.format("| %s | %d | %d | %.1f%% |%n", label, inferredOnly.size(), also, cov * 100));
    }

    private static void appendWorstOffenders(StringBuilder md, Map<String, List<String>> missByOwner,
            Map<String, List<String>> extraByOwner) {
        Set<String> owners = new TreeSet<>();
        owners.addAll(missByOwner.keySet());
        owners.addAll(extraByOwner.keySet());
        if (owners.isEmpty()) {
            md.append("No intra-repo divergences. ✅\n\n");
            return;
        }
        List<String> ranked = new ArrayList<>(owners);
        ranked.sort(Comparator.comparingInt((String o) ->
                missByOwner.getOrDefault(o, List.of()).size()
                        + extraByOwner.getOrDefault(o, List.of()).size()).reversed());
        md.append("Worst intra-repo offenders (misses = oracle−jcma, extras = jcma−oracle):\n\n");
        md.append("| owner | misses | extras |\n|---|---|---|\n");
        for (String o : limit(ranked, 15)) {
            md.append("| `").append(shorten(o)).append("` | ")
                    .append(fmtDeps(missByOwner.get(o))).append(" | ")
                    .append(fmtDeps(extraByOwner.get(o))).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendPerf(StringBuilder md, Properties p) {
        if (p.isEmpty()) {
            return;
        }
        md.append("## Performance\n\n");
        md.append("| metric | value |\n|---|---|\n");
        perfRow(md, p, "Cold index (wall, incl. classpath resolve)", "index_wall_s", "s");
        perfRow(md, p, "Cold index (internal parse+persist)", "index_internal_s", "s");
        perfRow(md, p, "Classpath resolve (mvn)", "classpath_resolve_s", "s");
        perfRow(md, p, "Classpath jars", "classpath_jars", "");
        perfRow(md, p, "Indexed LOC", "loc_total", "");
        perfRow(md, p, "Indexed symbols", "symbols", "");
        perfRow(md, p, "Index throughput", "loc_per_s", " LOC/s");
        md.append("| | |\n");
        perfRow(md, p, "resolve-file total (all files)", "resolve_total_s", "s");
        perfRow(md, p, "resolve-file files", "resolve_files", "");
        perfRow(md, p, "resolve-file mean / file", "resolve_mean_ms", " ms");
        perfRow(md, p, "resolve-file p95 / file", "resolve_p95_ms", " ms");
        perfRow(md, p, "resolve-file max / file", "resolve_max_ms", " ms");
        md.append("\n");
    }

    private static void appendMethodNotes(StringBuilder md) {
        md.append("## Method\n\n");
        md.append("- **Oracle**: `javac` `JavacTask.parse()+analyze()` over all sources with the full ")
                .append("Maven classpath; a `TreePathScanner` emits each declared type's direct ")
                .append("supertypes and every resolved type mention (field/var/param/return/throws/")
                .append("type-args/extends/implements/new/cast/instanceof/annotation), erased to the ")
                .append("type-element FQN. A type written in source is `TYPEREF`; one only javac infers ")
                .append("(`var` local, implicit lambda param) is `TYPEREF_INFERRED`.\n");
        md.append("- **Navigation contract**: jcma's syntactic scan (like IntelliJ's *Find Usages*) ")
                .append("surfaces written type names, not inferred ones. So headline TYPEREF recall is ")
                .append("measured over written types; inferred-only deps are reported separately as an ")
                .append("out-of-contract category — present in the oracle, by-design absent from jcma.\n");
        md.append("- **jcma**: `jcma resolve-file <file>` runs the same JavaParserSymbolSolver per-node ")
                .append("resolve a real `find_references` uses, but over **every** type mention in the ")
                .append("file (exhaustive selection, identical resolution), attributed to the enclosing ")
                .append("type.\n");
        md.append("- **Intra-repo** = the dependency is a repo-declared type; this is jcma's primary ")
                .append("correctness signal. **External** resolution depends on classpath completeness ")
                .append("and is reported separately.\n");
        md.append("- A jcma **miss** (oracle has it, jcma doesn't) is the safe-degrading mode; a jcma ")
                .append("**extra** (jcma has it, oracle doesn't) is the one to scrutinise. See the ")
                .append("`*-diffs.tsv` for the full per-type divergence list.\n");
    }

    // ---------------------------------------------------------------- metrics

    private static void appendPartitionRow(StringBuilder md, String label, Set<Edge> oracle, Set<Edge> jcma) {
        long matched = oracle.stream().filter(jcma::contains).count();
        double recall = oracle.isEmpty() ? 1.0 : (double) matched / oracle.size();
        double precision = jcma.isEmpty() ? 1.0 : (double) matched / jcma.size();
        md.append(String.format("| %s | %d | %d | %d | %.1f%% | %.1f%% |%n",
                label, oracle.size(), jcma.size(), matched, recall * 100, precision * 100));
    }

    /** {recall, precision, ownerCount} averaged per owner present in the oracle (recall) / jcma (precision). */
    private static double[] macroAverages(Set<Edge> oracle, Set<Edge> jcma) {
        Map<String, long[]> perOwner = new LinkedHashMap<>(); // owner → {tp, oracleN, jcmaN}
        for (Edge e : oracle) {
            perOwner.computeIfAbsent(e.owner(), k -> new long[3])[1]++;
            if (jcma.contains(e)) {
                perOwner.get(e.owner())[0]++;
            }
        }
        for (Edge e : jcma) {
            perOwner.computeIfAbsent(e.owner(), k -> new long[3])[2]++;
        }
        double recallSum = 0;
        int recallN = 0;
        double precSum = 0;
        int precN = 0;
        for (long[] v : perOwner.values()) {
            if (v[1] > 0) {
                recallSum += (double) v[0] / v[1];
                recallN++;
            }
            if (v[2] > 0) {
                precSum += (double) v[0] / v[2];
                precN++;
            }
        }
        return new double[] {recallN == 0 ? 1 : recallSum / recallN,
                precN == 0 ? 1 : precSum / precN, perOwner.size()};
    }

    // ---------------------------------------------------------------- helpers

    private static Set<Edge> edges(List<String[]> rows, String relation) {
        Set<Edge> out = new TreeSet<>(Comparator.comparing(Edge::owner).thenComparing(Edge::dep));
        for (String[] r : rows) {
            if (r[0].equals(relation)) {
                out.add(new Edge(r[1], r[2]));
            }
        }
        return out;
    }

    private static Set<Edge> filter(Set<Edge> edges, Set<String> repoTypes, boolean intra) {
        Set<Edge> out = new TreeSet<>(Comparator.comparing(Edge::owner).thenComparing(Edge::dep));
        for (Edge e : edges) {
            if (repoTypes.contains(e.dep()) == intra) {
                out.add(e);
            }
        }
        return out;
    }

    private static Set<String> owners(List<String[]> rows) {
        Set<String> out = new TreeSet<>();
        for (String[] r : rows) {
            out.add(r[1]);
        }
        return out;
    }

    private static List<String[]> readRows(Path tsv, boolean dropUnresolved) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(tsv)) {
            return out;
        }
        for (String line : Files.readAllLines(tsv)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                continue;
            }
            if (dropUnresolved && parts[0].equals("UNRESOLVED")) {
                continue;
            }
            // Uniform normalization (applied to BOTH sides here, so neither emitter has to): drop the
            // universal `java.lang.Object` noise and self-edges (a type referencing its own enclosing
            // type — e.g. a nested enum naming its outer type, or a sealed type listing its permits —
            // is not a dependency on something else).
            if (parts[2].equals("java.lang.Object") || parts[1].equals(parts[2])) {
                continue;
            }
            out.add(parts);
        }
        return out;
    }

    private static long countUnresolved(Path tsv) throws IOException {
        if (!Files.exists(tsv)) {
            return 0;
        }
        return Files.readAllLines(tsv).stream().filter(l -> l.startsWith("UNRESOLVED\t")).count();
    }

    private static List<String> readLines(Path p) throws IOException {
        return Files.exists(p) ? Files.readAllLines(p).stream().filter(s -> !s.isBlank()).toList() : List.of();
    }

    private static void perfRow(StringBuilder md, Properties p, String label, String key, String unit) {
        String v = p.getProperty(key);
        if (v != null) {
            md.append("| ").append(label).append(" | ").append(v).append(unit).append(" |\n");
        }
    }

    private static String fmtDeps(List<String> deps) {
        if (deps == null || deps.isEmpty()) {
            return "—";
        }
        List<String> shortened = deps.stream().map(Compare::shorten).sorted().toList();
        return "`" + String.join("`, `", limit(shortened, 8)) + "`" + (shortened.size() > 8 ? " …" : "");
    }

    /** Trim a long FQN to its last two segments for table readability. */
    private static String shorten(String fqn) {
        int dot = fqn.lastIndexOf('.');
        if (dot < 0) {
            return fqn;
        }
        int prev = fqn.lastIndexOf('.', dot - 1);
        return prev < 0 ? fqn : "…" + fqn.substring(prev + 1);
    }

    private static <T> List<T> limit(Iterable<T> it, int n) {
        List<T> out = new ArrayList<>();
        for (T t : it) {
            if (out.size() >= n) {
                break;
            }
            out.add(t);
        }
        return out;
    }
}
