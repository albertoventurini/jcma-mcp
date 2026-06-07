package jcma.cli;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;

import jcma.index.Moniker;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import jcma.index.SymbolStore;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.OptionalInt;

/**
 * Native-image smoke harness — ported from M0 Spike C ({@code SpikeC.cap()/selftest()}). Each
 * capability exercises something native-image-risky and is timed + reported PASS/FAIL. The point
 * is that the *same* harness runs on the JVM (under {@code ./gradlew test}) and inside the native
 * binary ({@code jcma selftest}); a capability that survives both is proven for native-image.
 *
 * <p>From M1 task 1 the harness covers {@code parse} (JavaParser) and {@code mmap} (FFM
 * {@code Arena} + {@code FileChannel.map}); later tasks register more capabilities (resolve, the
 * §5.1 store) as their modules land.
 */
final class SelfTest {

    private SelfTest() {}

    @FunctionalInterface
    interface Capability {
        void run() throws Exception;
    }

    /** Run all capabilities; return 0 if all pass, 1 otherwise. */
    static int run(PrintStream out) {
        boolean ok = true;
        ok &= cap(out, "parse", SelfTest::capParse);
        ok &= cap(out, "mmap", SelfTest::capMmap);
        ok &= cap(out, "store", SelfTest::capStore);
        ok &= cap(out, "trigram", SelfTest::capTrigram);
        ok &= cap(out, "usage", SelfTest::capUsage);
        out.println(ok ? "ALL PASS" : "SOME FAIL");
        return ok ? 0 : 1;
    }

    /** Run one capability, time it, print "PASS/FAIL name (nanos)", return success. */
    static boolean cap(PrintStream out, String name, Capability c) {
        long t0 = System.nanoTime();
        try {
            c.run();
            long ns = System.nanoTime() - t0;
            out.printf("PASS %-8s %,d ns%n", name, ns);
            return true;
        } catch (Throwable e) {
            long ns = System.nanoTime() - t0;
            out.printf("FAIL %-8s %,d ns — %s%n", name, ns, e);
            return false;
        }
    }

    private static final String PARSE_SRC = """
            package demo;
            public class Greeter {
                private final String who;
                public Greeter(String who) { this.who = who; }
                public String greet() { return "hi " + who; }
                void use() { String s = greet(); int n = s.length(); }
            }
            """;

    /** Capability: parse an embedded source string with the production (RAW) config; assert a clean single-type AST. */
    static void capParse() {
        // RAW to match the real parsers (StructuralParser/JavaParserEngine): grammar only, no
        // reflective validator walk — so this self-test exercises the same native path production does.
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(LanguageLevel.RAW));
        ParseResult<CompilationUnit> r = parser.parse(PARSE_SRC);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new IllegalStateException("parse failed: " + r.getProblems());
        }
        long types = r.getResult().get().getTypes().size();
        if (types != 1) {
            throw new IllegalStateException("expected 1 type, got " + types);
        }
    }

    /**
     * Capability: round-trip a {@link jcma.index.TrigramIndex} through the real FFM write/read path
     * and run a substring query — proves the task-05 mmap'd trigram postings + binary-searched
     * trigram keys survive native-image (the §5.1 declaration name-search read path).
     */
    static void capTrigram() throws Exception {
        Path dir = Files.createTempDirectory("jcma-selftest-trigram");
        Path seg = dir.resolve(jcma.index.TrigramIndex.FILE_NAME);
        try {
            jcma.index.TrigramIndex.write(seg, List.of(
                    new jcma.index.TrigramIndex.Entry("Greeter", 0),
                    new jcma.index.TrigramIndex.Entry("greet", 1),
                    new jcma.index.TrigramIndex.Entry("render", 2)));
            try (jcma.index.TrigramIndex idx = jcma.index.TrigramIndex.load(seg)) {
                List<Integer> hits = idx.searchSymbols("reet"); // substring of Greeter + greet, not render
                if (!hits.contains(0) || !hits.contains(1) || hits.contains(2)) {
                    throw new IllegalStateException("trigram: unexpected search result " + hits);
                }
            }
        } finally {
            Files.deleteIfExists(seg);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Capability: round-trip a {@link jcma.index.UsageNameIndex} through the real FFM write/read path
     * and run an exact-match prune — proves the mmap'd name dictionary (binary-searched) + int32
     * posting slices survive native-image (the §5.1 find-references candidate-file prune).
     */
    static void capUsage() throws Exception {
        Path dir = Files.createTempDirectory("jcma-selftest-usage");
        Path seg = dir.resolve(jcma.index.UsageNameIndexer.FILE_NAME);
        try {
            java.util.TreeMap<String, java.util.Collection<Integer>> byName = new java.util.TreeMap<>();
            byName.put("greet", new java.util.TreeSet<>(List.of(0, 2)));
            byName.put("render", new java.util.TreeSet<>(List.of(1)));
            jcma.index.UsageNameIndex.write(seg, byName);
            try (jcma.index.UsageNameIndex idx = jcma.index.UsageNameIndex.load(seg)) {
                int[] files = idx.candidateFiles("render"); // exact: only "render", in file 1
                if (files.length != 1 || files[0] != 1) {
                    throw new IllegalStateException("usage: unexpected prune " + java.util.Arrays.toString(files));
                }
                if (idx.candidateFiles("gree").length != 0) { // exact, not substring of "greet"
                    throw new IllegalStateException("usage: substring 'gree' must not match 'greet'");
                }
            }
        } finally {
            Files.deleteIfExists(seg);
            Files.deleteIfExists(dir);
        }
    }

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;

    /**
     * Capability: FFM mmap round-trip — write a tiny struct (magic + 3 ints), reopen READ_ONLY via
     * {@code Arena.ofShared()} + {@code FileChannel.map} -> {@code MemorySegment}, read back, verify.
     * This is the §5.1 store's core read path; needs {@code -H:+SharedArenaSupport} under native.
     */
    static void capMmap() throws Exception {
        Path f = Files.createTempFile("jcma-selftest-struct", ".seg");
        try {
            int[] payload = {0x4A434D43 /* "JCMC" */, 11, 22, 33};
            try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 Arena a = Arena.ofShared()) {
                MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, payload.length * 4L, a);
                for (int i = 0; i < payload.length; i++) {
                    seg.set(I, i * 4L, payload[i]);
                }
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
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /**
     * Capability: round-trip a tiny {@link jcma.index.SymbolStore} through the real FFM write/read
     * path (the §5.1 columnar store, not the generic {@code capMmap} struct). Proves the production
     * store's {@code Arena}+{@code FileChannel.map} path survives native-image, the task-03 native
     * smoke.
     */
    static void capStore() throws Exception {
        Path dir = Files.createTempDirectory("jcma-selftest-store");
        Path seg = dir.resolve(SymbolStore.FILE_NAME);
        try {
            String type = Moniker.forType(Moniker.forPackage("demo"), "Greeter");
            String greet = Moniker.forMethod(type, "greet", List.of());
            SymbolStore.write(seg, List.of(
                    new Symbol(type, SymbolKind.CLASS, 0, null, 0, new Range(1, 1, 3, 1), "Greeter", null),
                    new Symbol(greet, SymbolKind.METHOD, 0, type, 0, new Range(2, 3, 2, 20),
                            "greet", "java.lang.String greet()")));
            try (SymbolStore store = SymbolStore.load(seg)) {
                OptionalInt id = store.idOf(greet);
                if (id.isEmpty()) {
                    throw new IllegalStateException("store: greet moniker did not resolve");
                }
                Symbol s = store.symbol(id.getAsInt());
                if (s.kind() != SymbolKind.METHOD || !type.equals(s.enclosingMoniker())) {
                    throw new IllegalStateException("store: round-trip mismatch: " + s);
                }
            }
        } finally {
            Files.deleteIfExists(seg);
            Files.deleteIfExists(dir);
        }
    }
}
