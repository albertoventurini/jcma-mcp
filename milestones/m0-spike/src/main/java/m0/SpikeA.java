package m0;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.AssociableToAST;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * M0 Spike A driver (throwaway). Three modes:
 *   coverage <srcRoot> <cp.txt> <label> <outDir>   -> G1 coverage + failure histogram
 *   gotodef  <srcRoot> <cp.txt> <label> <outDir>   -> G2 go-to-def worksheet (~150 sampled)
 *   findrefs <srcRoot> <cp.txt> <label> <outDir>   -> G3 find-refs worksheets (~10 symbols)
 *
 * All resolve() calls are guarded (incl. StackOverflowError). A thrown failure is safe-degrading
 * by construction; the dangerous silent-wrong rate is judged from the gotodef/findrefs worksheets.
 */
public final class SpikeA {

    enum Cat { METHOD_CALL, OBJ_CREATION, NAME, FIELD_ACCESS, METHOD_REF, TYPE_REF, ANNOTATION }

    /** Outcome of one resolve attempt. */
    record Outcome(boolean ok, boolean excluded, String targetDesc, String targetLoc, Throwable error) {}

    static final int EXEMPLARS_PER_BUCKET = 8;
    static final int GOTODEF_SAMPLE = 150;
    static final int GOTODEF_UNRESOLVED = 15;
    static final int FINDREFS_SYMBOLS = 10;

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("usage: SpikeA <coverage|gotodef|findrefs> <srcRoot> <cp.txt> <label> <outDir>");
            System.exit(2);
        }
        String mode = args[0];
        Path src = Path.of(args[1]);
        Path cp = Path.of(args[2]);
        String label = args[3];
        Path outDir = Path.of(args[4]);
        Files.createDirectories(outDir);

        SolverSetup.Wiring w = SolverSetup.build(src, cp);
        System.out.printf("[%s] %s  src=%s  jars=%d%n", mode, label, src, w.jars());

        switch (mode) {
            case "coverage" -> coverage(w, src, label, outDir);
            case "gotodef"  -> gotodef(w, src, label, outDir);
            case "findrefs" -> findrefs(w, src, label, outDir);
            default -> { System.err.println("unknown mode: " + mode); System.exit(2); }
        }
    }

    // ---------------------------------------------------------------- shared

    private static List<Path> javaFiles(Path src) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static CompilationUnit parse(SolverSetup.Wiring w, Path f) {
        try {
            ParseResult<CompilationUnit> r = w.parser().parse(f);
            return r.isSuccessful() ? r.getResult().orElse(null) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Resolve one occurrence node; never throws. */
    static Outcome attempt(Cat cat, Node node) {
        try {
            Object resolved = switch (cat) {
                case METHOD_CALL  -> ((MethodCallExpr) node).resolve();
                case OBJ_CREATION -> ((ObjectCreationExpr) node).resolve();
                case NAME         -> ((NameExpr) node).resolve();
                case FIELD_ACCESS -> ((FieldAccessExpr) node).resolve();
                case METHOD_REF   -> ((MethodReferenceExpr) node).resolve();
                case TYPE_REF     -> ((ClassOrInterfaceType) node).resolve();
                case ANNOTATION   -> ((AnnotationExpr) node).resolve();
            };
            return new Outcome(true, false, describe(resolved), locate(resolved), null);
        } catch (Throwable t) {
            // A NameExpr that is really a type/qualifier (Upper-initial) failing as a *value*
            // is not a navigation failure — exclude from the value-coverage denominator.
            boolean excluded = cat == Cat.NAME && isLikelyTypeOrQualifier((NameExpr) node);
            return new Outcome(false, excluded, null, null, t);
        }
    }

    private static boolean isLikelyTypeOrQualifier(NameExpr n) {
        String id = n.getNameAsString();
        return !id.isEmpty() && Character.isUpperCase(id.charAt(0));
    }

    private static String describe(Object r) {
        try {
            if (r instanceof ResolvedMethodLikeDeclaration m) return m.getQualifiedSignature();
            if (r instanceof ResolvedTypeDeclaration t)       return t.getQualifiedName();
            if (r instanceof ResolvedValueDeclaration v) {
                String ty;
                try { ty = v.getType().describe(); } catch (Throwable t) { ty = "?"; }
                return ty + " " + v.getName();
            }
            if (r instanceof ResolvedType rt) return rt.describe();
            return String.valueOf(r);
        } catch (Throwable t) {
            return "«describe failed: " + t.getClass().getSimpleName() + "»";
        }
    }

    private static String locate(Object r) {
        try {
            Node n = null;
            if (r instanceof AssociableToAST a) {
                n = a.toAst().orElse(null);
            } else if (r instanceof ResolvedType rt && rt.isReferenceType()) {
                var td = rt.asReferenceType().getTypeDeclaration().orElse(null);
                if (td instanceof AssociableToAST a2) n = a2.toAst().orElse(null);
            }
            return n == null ? "«external/jdk»" : loc(n);
        } catch (Throwable t) {
            return "«locate failed»";
        }
    }

    private static String loc(Node n) {
        String path = n.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(s -> s.getPath().toString())
                .orElse("?");
        int line = n.getRange().map(r -> r.begin.line).orElse(-1);
        return path + ":" + line;
    }

    /** Occurrence categories present in a CU, in a stable order. */
    private static List<Map.Entry<Cat, Node>> occurrences(CompilationUnit cu) {
        List<Map.Entry<Cat, Node>> out = new ArrayList<>();
        cu.findAll(MethodCallExpr.class).forEach(n -> out.add(Map.entry(Cat.METHOD_CALL, n)));
        cu.findAll(ObjectCreationExpr.class).forEach(n -> out.add(Map.entry(Cat.OBJ_CREATION, n)));
        cu.findAll(NameExpr.class).forEach(n -> out.add(Map.entry(Cat.NAME, n)));
        cu.findAll(FieldAccessExpr.class).forEach(n -> out.add(Map.entry(Cat.FIELD_ACCESS, n)));
        cu.findAll(MethodReferenceExpr.class).forEach(n -> out.add(Map.entry(Cat.METHOD_REF, n)));
        cu.findAll(ClassOrInterfaceType.class).forEach(n -> out.add(Map.entry(Cat.TYPE_REF, n)));
        cu.findAll(AnnotationExpr.class).forEach(n -> out.add(Map.entry(Cat.ANNOTATION, n)));
        return out;
    }

    // ---------------------------------------------------------------- G1 coverage

    private static void coverage(SolverSetup.Wiring w, Path src, String label, Path outDir) throws IOException {
        List<Path> files = javaFiles(src);
        Map<Cat, long[]> tally = new EnumMap<>(Cat.class); // [resolved, failed, excluded]
        for (Cat c : Cat.values()) tally.put(c, new long[3]);
        Map<FailureClassifier.Cause, Long> hist = new HashMap<>();
        Map<FailureClassifier.Cause, List<String>> exemplars = new HashMap<>();

        long t0 = System.nanoTime();
        int idx = 0, parseFail = 0;
        for (Path f : files) {
            if (++idx % 50 == 0) System.out.printf("  ... %d/%d files%n", idx, files.size());
            CompilationUnit cu = parse(w, f);
            if (cu == null) { parseFail++; continue; }
            for (var e : occurrences(cu)) {
                Outcome o = attempt(e.getKey(), e.getValue());
                long[] t = tally.get(e.getKey());
                if (o.ok()) { t[0]++; }
                else if (o.excluded()) { t[2]++; }
                else {
                    var res = FailureClassifier.classify(e.getValue(), o.error());
                    if (res.cause() == FailureClassifier.Cause.NON_SYMBOL_QUALIFIER) {
                        t[2]++;   // package-path segment — not a symbol, excluded from denominator
                        continue;
                    }
                    t[1]++;
                    hist.merge(res.cause(), 1L, Long::sum);
                    var ex = exemplars.computeIfAbsent(res.cause(), k -> new ArrayList<>());
                    if (ex.size() < EXEMPLARS_PER_BUCKET) {
                        ex.add(loc(e.getValue()) + "  `" + snippetOf(e.getValue()) + "`  — " + res.note());
                    }
                }
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;

        long resolved = 0, failed = 0, excluded = 0;
        for (long[] t : tally.values()) { resolved += t[0]; failed += t[1]; excluded += t[2]; }
        long denom = resolved + failed; // excluded not counted

        StringBuilder sb = new StringBuilder();
        sb.append("# Spike A — coverage (G1): ").append(label).append("\n\n");
        sb.append(String.format("Files: %d (parse-failed %d). Walk time: %.1fs.%n%n", files.size(), parseFail, secs));
        sb.append(String.format("**Overall coverage = %d / %d = %.2f%%** (excluded non-symbol/qualifier: %d)%n%n",
                resolved, denom, denom == 0 ? 0 : 100.0 * resolved / denom, excluded));
        sb.append("All failures here are **safe-degrading** (resolve threw → product would surface ")
          .append("an *unconfirmed* result). Silent-wrong rate is measured by the gotodef/findrefs worksheets.\n\n");
        sb.append("| Category | resolved | failed | coverage | excluded |\n|---|---|---|---|---|\n");
        for (Cat c : Cat.values()) {
            long[] t = tally.get(c);
            long d = t[0] + t[1];
            sb.append(String.format("| %s | %d | %d | %s | %d |%n", c, t[0], t[1],
                    d == 0 ? "—" : String.format("%.2f%%", 100.0 * t[0] / d), t[2]));
        }
        Path covPath = outDir.resolve("coverage-" + label + ".md");
        Files.writeString(covPath, sb.toString());

        // histogram
        StringBuilder hb = new StringBuilder();
        hb.append("# Spike A — failure-cause histogram: ").append(label).append("\n\n");
        hb.append(String.format("Total failures: %d (all safe-degrading).%n%n", failed));
        hb.append("| Cause | count | %% of failures |\n|---|---|---|\n");
        final long failedF = failed;
        hist.entrySet().stream()
            .sorted(Map.Entry.<FailureClassifier.Cause, Long>comparingByValue().reversed())
            .forEach(en -> hb.append(String.format("| %s | %d | %.1f%% |%n",
                    en.getKey(), en.getValue(), failedF == 0 ? 0 : 100.0 * en.getValue() / failedF)));
        hb.append("\n## Exemplars per bucket\n");
        for (FailureClassifier.Cause c : FailureClassifier.Cause.values()) {
            var ex = exemplars.get(c);
            if (ex == null || ex.isEmpty()) continue;
            hb.append("\n### ").append(c).append("\n");
            ex.forEach(s -> hb.append("- ").append(s).append("\n"));
        }
        Path histPath = outDir.resolve("histogram-" + label + ".md");
        Files.writeString(histPath, hb.toString());

        System.out.printf("overall coverage %.2f%% over %d occurrences (excluded %d)%n",
                denom == 0 ? 0 : 100.0 * resolved / denom, denom, excluded);
        System.out.println("wrote " + covPath + " and " + histPath);
    }

    // ---------------------------------------------------------------- G2 gotodef worksheet

    record Site(Cat cat, String occLoc, String snippet, String targetDesc, String targetLoc) {}

    private static void gotodef(SolverSetup.Wiring w, Path src, String label, Path outDir) throws IOException {
        List<Path> files = javaFiles(src);
        Map<Cat, List<Site>> byCat = new EnumMap<>(Cat.class);
        for (Cat c : Cat.values()) byCat.put(c, new ArrayList<>());
        List<String> unresolved = new ArrayList<>();

        for (Path f : files) {
            CompilationUnit cu = parse(w, f);
            if (cu == null) continue;
            for (var e : occurrences(cu)) {
                Outcome o = attempt(e.getKey(), e.getValue());
                if (o.ok()) {
                    byCat.get(e.getKey()).add(new Site(e.getKey(), loc(e.getValue()),
                            snippetOf(e.getValue()), o.targetDesc(), o.targetLoc()));
                } else if (!o.excluded() && unresolved.size() < 400) {
                    unresolved.add(loc(e.getValue()) + "  `" + snippetOf(e.getValue()) + "`");
                }
            }
        }

        // stratified sample: even-ish per category, deterministic stride.
        int perCat = Math.max(1, GOTODEF_SAMPLE / Cat.values().length);
        List<Site> sample = new ArrayList<>();
        for (Cat c : Cat.values()) sample.addAll(stride(byCat.get(c), perCat));

        StringBuilder sb = new StringBuilder();
        sb.append("# Spike A — go-to-def worksheet (G2): ").append(label).append("\n\n");
        sb.append("Judge each: in **OK?** put ✓ (resolved target is the correct declaration) or ✗.\n");
        sb.append("`«external/jdk»` target = correctly resolved into a dependency (no project source) — still ✓.\n\n");
        sb.append("| # | cat | occurrence | snippet | resolved target | target loc | OK? | note |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        int i = 1;
        for (Site s : sample) {
            sb.append(String.format("| %d | %s | %s | `%s` | `%s` | %s |  |  |%n",
                    i++, s.cat(), s.occLoc(), esc(s.snippet()), esc(s.targetDesc()), s.targetLoc()));
        }
        sb.append("\n## Unresolved (spot-check these are genuine failures, not silent wrong)\n");
        for (String u : stride(unresolved, GOTODEF_UNRESOLVED)) sb.append("- ").append(u).append("\n");

        Path p = outDir.resolve("gotodef-worksheet-" + label + ".md");
        Files.writeString(p, sb.toString());
        System.out.printf("sampled %d resolved sites + %d unresolved -> %s%n",
                sample.size(), Math.min(GOTODEF_UNRESOLVED, unresolved.size()), p);
    }

    // ---------------------------------------------------------------- G3 findrefs worksheet

    record CallRec(String simpleName, String sig, String loc, String snippet) {}

    private static void findrefs(SolverSetup.Wiring w, Path src, String label, Path outDir) throws IOException {
        List<Path> files = javaFiles(src);
        List<CallRec> calls = new ArrayList<>();
        Map<String, Long> sigCount = new HashMap<>();          // qualified signature -> count
        Map<String, java.util.Set<String>> nameToSigs = new HashMap<>(); // simple name -> distinct sigs

        for (Path f : files) {
            CompilationUnit cu = parse(w, f);
            if (cu == null) continue;
            for (MethodCallExpr mce : cu.findAll(MethodCallExpr.class)) {
                String name = mce.getNameAsString();
                String sig = null;
                try { sig = mce.resolve().getQualifiedSignature(); } catch (Throwable ignore) {}
                calls.add(new CallRec(name, sig, loc(mce), snippetOf(mce)));
                if (sig != null) {
                    sigCount.merge(sig, 1L, Long::sum);
                    nameToSigs.computeIfAbsent(name, k -> new java.util.HashSet<>()).add(sig);
                }
            }
        }

        // pick ~10 target signatures by frequency, biased to include overloaded simple names.
        List<String> targets = sigCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(sig -> { // prefer project symbols (org.apache...) over JDK noise
                    return sig.startsWith("org.apache");
                })
                .limit(FINDREFS_SYMBOLS)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("# Spike A — find-references worksheet (G3): ").append(label).append("\n\n");
        sb.append("Per symbol: **found refs** = call-sites the tool linked here. **Not-linked candidates** ");
        sb.append("= same simple name, resolved elsewhere or unresolved (the false-negative surface).\n");
        sb.append("Mark any not-linked row that *is* genuinely this symbol as a **MISS** (recall), and any ");
        sb.append("found row that is *not* this symbol as **FP** (false positive / silent-wrong).\n\n");

        for (String target : targets) {
            String simple = simpleNameOf(target);
            List<CallRec> found = calls.stream().filter(c -> target.equals(c.sig())).toList();
            List<CallRec> notLinked = calls.stream()
                    .filter(c -> c.simpleName().equals(simple) && !target.equals(c.sig()))
                    .toList();
            boolean fullyResolved = notLinked.stream().allMatch(c -> c.sig() != null);

            sb.append("## `").append(target).append("`  (count=").append(sigCount.get(target)).append(")\n");
            sb.append("- distinct signatures sharing name `").append(simple).append("`: ")
              .append(nameToSigs.getOrDefault(simple, java.util.Set.of()).size())
              .append(fullyResolved ? "" : "  ⚠ some candidates unresolved → result must carry an *unconfirmed tail*")
              .append("\n\n");
            sb.append("### found refs (").append(found.size()).append(")\n");
            sb.append("| occurrence | snippet | FP? |\n|---|---|---|\n");
            for (CallRec c : found) sb.append(String.format("| %s | `%s` |  |%n", c.loc(), esc(c.snippet())));
            sb.append("\n### not-linked candidates (").append(notLinked.size()).append(")\n");
            sb.append("| occurrence | snippet | resolved-to | MISS? |\n|---|---|---|---|\n");
            for (CallRec c : stride(notLinked, 30)) {
                sb.append(String.format("| %s | `%s` | `%s` |  |%n",
                        c.loc(), esc(c.snippet()), c.sig() == null ? "«unresolved»" : esc(c.sig())));
            }
            sb.append("\n");
        }

        Path p = outDir.resolve("findrefs-worksheet-" + label + ".md");
        Files.writeString(p, sb.toString());
        System.out.printf("selected %d target symbols across %d total calls -> %s%n",
                targets.size(), calls.size(), p);
    }

    // ---------------------------------------------------------------- tiny helpers

    private static final Map<Path, List<String>> LINE_CACHE = new HashMap<>();

    private static String snippetOf(Node n) {
        Path path = n.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(com.github.javaparser.ast.CompilationUnit.Storage::getPath).orElse(null);
        int line = n.getRange().map(r -> r.begin.line).orElse(-1);
        if (path == null || line < 1) return n.toString().replaceAll("\\s+", " ");
        List<String> lines = LINE_CACHE.computeIfAbsent(path, p -> {
            try { return Files.readAllLines(p); } catch (IOException e) { return List.of(); }
        });
        if (line <= lines.size()) return lines.get(line - 1).trim();
        return n.toString().replaceAll("\\s+", " ");
    }

    private static String simpleNameOf(String qualifiedSignature) {
        int paren = qualifiedSignature.indexOf('(');
        String head = paren < 0 ? qualifiedSignature : qualifiedSignature.substring(0, paren);
        int dot = head.lastIndexOf('.');
        return dot < 0 ? head : head.substring(dot + 1);
    }

    /** Deterministic even sample of up to k items. */
    private static <T> List<T> stride(List<T> in, int k) {
        if (in.size() <= k) return in;
        List<T> out = new ArrayList<>(k);
        double step = (double) in.size() / k;
        for (int i = 0; i < k; i++) out.add(in.get((int) (i * step)));
        return out;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
