package jcma.cli;

import java.io.PrintStream;

/**
 * Dev-only {@code jcma} CLI — the M1 verification surface (PRD §9; M2 replaces/augments this with
 * the MCP server). Argument dispatch only; subcommands are added per M1 task. The exact same
 * binary runs under GraalVM native-image, so the entry points here are also the native smoke
 * targets (task protocol step 4b).
 */
public final class Main {

    /** Bumped manually; surfaced by {@code jcma version} and the native smoke. */
    static final String VERSION = "0.1.0-SNAPSHOT";

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Testable entry point: dispatch {@code args}, write to the given streams, return the process
     * exit code (0 ok, 2 usage error, 1 subcommand failure).
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        String cmd = args[0];
        switch (cmd) {
            case "version", "--version", "-v" -> {
                out.println("jcma " + VERSION);
                return 0;
            }
            case "selftest" -> {
                return SelfTest.run(out);
            }
            default -> {
                err.println("jcma: unknown subcommand '" + cmd + "'");
                usage(err);
                return 2;
            }
        }
    }

    private static void usage(PrintStream err) {
        err.println("""
                usage: jcma <subcommand> [args]

                subcommands:
                  version     print the jcma version
                  selftest    run the native-image smoke capabilities (parse, mmap, ...)
                """);
    }
}
