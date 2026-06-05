package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Task 01 — CLI dispatch + the native smoke harness, exercised on the JVM.
 *
 * <p>The same {@code jcma} entry points are what the native binary runs; these JVM tests are the
 * red-first contract, and the native verification (task protocol step 4b) runs the built binary
 * through {@code jcma version} / {@code jcma selftest}.
 */
class MainTest {

    /** Capture stdout/stderr around a dispatcher run and return [exitCode, out, err]. */
    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        int exit = Main.run(args, out, err);
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void versionPrintsVersionAndExitsZero() {
        Run r = dispatch("version");
        assertEquals(0, r.exit(), "version should exit 0");
        // Prints a semver-ish line (e.g. "jcma 0.1.0-SNAPSHOT"); assert it carries a digit.dot.digit.
        assertTrue(r.out().matches("(?s).*\\d+\\.\\d+.*"), "version output should contain a version number: " + r.out());
    }

    @Test
    void unknownSubcommandExitsNonZeroWithUsage() {
        Run r = dispatch("definitely-not-a-subcommand");
        assertNotEquals(0, r.exit(), "unknown subcommand should exit non-zero");
        assertTrue(r.err().toLowerCase().contains("usage"), "should print usage to stderr: " + r.err());
    }

    @Test
    void noArgsExitsNonZeroWithUsage() {
        Run r = dispatch();
        assertNotEquals(0, r.exit(), "no args should exit non-zero");
        assertTrue(r.err().toLowerCase().contains("usage"), "should print usage to stderr: " + r.err());
    }

    /**
     * The ported {@code cap()/selftest()} harness (M0 Spike C) — the reusable native smoke. From
     * task 1 it covers parse + FFM mmap (both native-image-risky); later tasks add capabilities.
     * Running it on the JVM here proves the harness contract; running the native binary proves the
     * capabilities survive native-image.
     */
    @Test
    void selftestRunsCapabilitiesAndExitsZero() {
        Run r = dispatch("selftest");
        assertEquals(0, r.exit(), "selftest should pass on the JVM: " + r.out() + r.err());
        assertTrue(r.out().contains("parse"), "selftest should report the parse capability: " + r.out());
        assertTrue(r.out().contains("mmap"), "selftest should report the mmap capability: " + r.out());
        assertTrue(r.out().contains("store"), "selftest should report the §5.1 store capability: " + r.out());
        assertTrue(r.out().contains("PASS"), "selftest should report PASS lines: " + r.out());
    }
}
