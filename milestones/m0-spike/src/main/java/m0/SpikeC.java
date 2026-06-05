package m0;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * M0 Spike C (throwaway) — native-image viability, the final M0 gate (PRD §4, §8). One binary,
 * two run modes, exercising the four native-image-risky capabilities:
 *   1. parse   — JavaParser on an embedded source string;
 *   2. resolve — JavaSymbolSolver with ReflectionTypeSolver (JDK reflection = the fiddly bit)
 *                AND JavaParserTypeSolver (project resolve, no reflection);
 *   3. mmap    — FFM Arena + FileChannel.map -> MemorySegment read of a packed int struct
 *                (the same read path Spike D exercised on the JVM, now under native-image);
 *   4. mcp     — a trivial newline-delimited JSON-RPC stdio loop (initialize + tools/list).
 *
 * Modes:
 *   spikec selftest  — runs 1–3, prints PASS/FAIL + nanos each, exits 0 (all pass) / 1.
 *   spikec mcp       — runs 4: reads JSON-RPC from stdin, replies on stdout.
 *
 * Run (JVM):    java --enable-native-access=ALL-UNNAMED -cp target/m0-spike.jar m0.SpikeC selftest
 * Run (native): out/spikec selftest   |   printf '...\n' | out/spikec mcp
 */
public final class SpikeC {

    private SpikeC() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "selftest";
        switch (mode) {
            case "selftest" -> System.exit(selftest());
            case "mcp" -> mcpLoop();
            default -> {
                System.err.println("usage: spikec {selftest|mcp}");
                System.exit(2);
            }
        }
    }

    // ----------------------------------------------------------------- selftest (caps 1–3)

    static int selftest() throws IOException {
        boolean ok = true;
        ok &= cap("parse", SpikeC::capParse);
        ok &= cap("resolve", SpikeC::capResolve);
        ok &= cap("mmap", SpikeC::capMmap);
        System.out.println(ok ? "ALL PASS" : "SOME FAIL");
        return ok ? 0 : 1;
    }

    @FunctionalInterface
    interface Capability {
        void run() throws Exception;
    }

    /** Run one capability, time it, print "PASS/FAIL name (nanos)", return success. */
    static boolean cap(String name, Capability c) {
        long t0 = System.nanoTime();
        try {
            c.run();
            long ns = System.nanoTime() - t0;
            System.out.printf("PASS %-8s %,d ns%n", name, ns);
            return true;
        } catch (Throwable e) {
            long ns = System.nanoTime() - t0;
            System.out.printf("FAIL %-8s %,d ns — %s%n", name, ns, e);
            e.printStackTrace();
            return false;
        }
    }

    static final String PARSE_SRC = """
            package demo;
            public class Greeter {
                private final String who;
                public Greeter(String who) { this.who = who; }
                public String greet() { return "hi " + who; }
                void use() { String s = greet(); int n = s.length(); }
            }
            """;

    /** Capability 1: parse an embedded source string; assert a clean AST. */
    static void capParse() {
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25));
        ParseResult<CompilationUnit> r = parser.parse(PARSE_SRC);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new IllegalStateException("parse failed: " + r.getProblems());
        }
        long types = r.getResult().get().getTypes().size();
        if (types != 1) throw new IllegalStateException("expected 1 type, got " + types);
    }

    /**
     * Capability 2: resolve two ways through one CombinedTypeSolver —
     *   (a) JDK reflection: "x".length() -> java.lang.String.length  (ReflectionTypeSolver);
     *   (b) project source: greet()      -> demo.Greeter.greet       (JavaParserTypeSolver).
     * Writes the project source to a temp dir because JavaParserTypeSolver resolves off disk.
     */
    static void capResolve() throws IOException {
        Path srcRoot = Files.createTempDirectory("spikec-src");
        Path pkg = Files.createDirectories(srcRoot.resolve("demo"));
        Files.writeString(pkg.resolve("Greeter.java"), PARSE_SRC);

        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));
        solver.add(new JavaParserTypeSolver(srcRoot));
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(solver)));

        CompilationUnit cu = parser.parse(PARSE_SRC).getResult().orElseThrow();

        ResolvedMethodDeclaration jdk = null, project = null;
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            ResolvedMethodDeclaration d = call.resolve();
            if (call.getNameAsString().equals("length")) jdk = d;
            if (call.getNameAsString().equals("greet")) project = d;
        }
        if (jdk == null) throw new IllegalStateException("did not find length() call");
        if (project == null) throw new IllegalStateException("did not find greet() call");

        String jq = jdk.getQualifiedSignature();
        if (!jq.startsWith("java.lang.String.length")) {
            throw new IllegalStateException("JDK resolve wrong: " + jq);
        }
        String pq = project.getQualifiedSignature();
        if (!pq.startsWith("demo.Greeter.greet")) {
            throw new IllegalStateException("project resolve wrong: " + pq);
        }
    }

    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;

    /**
     * Capability 3: FFM mmap. Write a tiny struct (magic + 3 ints), then re-open READ_ONLY via
     * Arena.ofShared() + FileChannel.map -> MemorySegment, read the ints back, verify.
     */
    static void capMmap() throws IOException {
        Path f = Files.createTempFile("spikec-struct", ".seg");
        int[] payload = {0x4A434D43 /* "JCMC" */, 11, 22, 33};
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE);
             Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, payload.length * 4L, a);
            for (int i = 0; i < payload.length; i++) seg.set(I, i * 4L, payload[i]);
            seg.force();
        }
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ);
             Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), a);
            for (int i = 0; i < payload.length; i++) {
                int got = seg.get(I, i * 4L);
                if (got != payload[i]) {
                    throw new IllegalStateException("mmap mismatch @" + i + ": " + got + " != " + payload[i]);
                }
            }
        }
        Files.deleteIfExists(f);
    }

    // ----------------------------------------------------------------- mcp (capability 4)

    /**
     * Capability 4: a trivial MCP stdio loop. Reads newline-delimited JSON-RPC requests from
     * stdin and replies on stdout. Handles `initialize` and `tools/list`; ignores notifications
     * (no id); exits on `shutdown`/`exit` or EOF. Hand-rolled JSON (PRD §6 prefers it; keeps the
     * native-image surface clean — no Jackson/Gson reflection).
     */
    static void mcpLoop() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String method = jsonStringField(line, "method");
            String id = jsonRawField(line, "id"); // null for notifications
            if (method == null) continue;
            switch (method) {
                case "initialize" -> {
                    if (id != null) out.println(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
                        + "\"protocolVersion\":\"2024-11-05\","
                        + "\"serverInfo\":{\"name\":\"jcma-spikec\",\"version\":\"0.0.0\"},"
                        + "\"capabilities\":{\"tools\":{}}}}");
                }
                case "tools/list" -> {
                    if (id != null) out.println(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                        + "{\"name\":\"ping\",\"description\":\"spike C stub tool\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}");
                }
                case "shutdown", "exit" -> {
                    return;
                }
                default -> {
                    // Unknown request: minimal method-not-found; ignore notifications.
                    if (id != null) out.println(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
                }
            }
        }
    }

    /** Extract a string field value: "key":"value" -> value (no escape handling; spike input). */
    static String jsonStringField(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** Extract a raw JSON field value (number or quoted string), as text, for echoing the id. */
    static String jsonRawField(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? null : json.substring(i, end + 1);
        }
        int j = i;
        while (j < json.length() && "-+.eE0123456789".indexOf(json.charAt(j)) >= 0) j++;
        return j > i ? json.substring(i, j) : null;
    }
}
