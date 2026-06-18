package jcma.cli;

import jcma.engine.SkimUnit;
import jcma.engine.StructuralParser;
import jcma.render.SkimRenderer;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code jcma skim <file> [maxChars] [--no-docs]} — re-parse a single file fresh (no index lookup) and
 * print it as real Java with method bodies elided, behind a source-true line-number gutter. The CLI
 * twin of the {@code skim_java} MCP tool (same {@code Parsed.skim()} + {@link SkimRenderer}), so the
 * inline-body char gate can be driven and calibrated without the MCP wire. Same {@code Main.run}
 * dispatch runs under native-image.
 */
final class Skim {

    private Skim() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println("jcma: usage: jcma skim <file> [inlineBodyMaxChars] [--no-docs]");
            return 2;
        }
        Path file = Path.of(args[1]);
        if (!Files.isRegularFile(file)) {
            err.println("jcma: not a file: " + file);
            return 1;
        }
        int gate = jcma.tools.SkimJavaTool.DEFAULT_INLINE_BODY_MAX_CHARS;
        boolean includeDocs = true;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--no-docs")) {
                includeDocs = false;
            } else {
                try {
                    gate = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    err.println("jcma: bad argument '" + a + "' — expected an integer or --no-docs");
                    return 2;
                }
            }
        }
        try {
            SkimUnit unit = new StructuralParser().collect(file).skim();
            out.println(SkimRenderer.render(unit, gate, includeDocs));
            return 0;
        } catch (Exception e) {
            err.println("jcma: skim failed: " + e.getMessage());
            return 1;
        }
    }
}
