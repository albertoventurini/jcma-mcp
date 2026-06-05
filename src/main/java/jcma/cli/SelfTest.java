package jcma.cli;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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

    /** Capability: parse an embedded source string at JDK-25 level; assert a clean single-type AST. */
    static void capParse() {
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25));
        ParseResult<CompilationUnit> r = parser.parse(PARSE_SRC);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new IllegalStateException("parse failed: " + r.getProblems());
        }
        long types = r.getResult().get().getTypes().size();
        if (types != 1) {
            throw new IllegalStateException("expected 1 type, got " + types);
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
}
