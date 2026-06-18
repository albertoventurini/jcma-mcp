package jcma.render;

import jcma.engine.SkimDecl;
import jcma.engine.SkimImport;
import jcma.engine.SkimUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link SkimUnit} as the {@code skim_java} view: the file as <b>real Java with method
 * bodies elided</b> (IDE collapse-all), behind a source-true line-number gutter. The engine produces
 * verbatim spans; turning them into a {@code String} is this layer's job (PRD §6), not the engine's.
 *
 * <p>Per declaration: an optional verbatim doc (the {@code includeDocs} dial — a clean drop when off,
 * never a clip), the verbatim header, then either the children (for a type, inside {@code &#123; … &#125;})
 * or the body. A block body is <b>inlined</b> when its whitespace-normalized inner text is at most
 * {@code inlineBodyMaxChars}; otherwise it collapses to {@code &#123; … &#125;} — visibly elided, and the
 * gutter then jumps over the hidden lines, so the gap shows how much each body hides. Spans carry their
 * source indentation, so nesting needs no re-indenting; line numbers are the source's own.
 */
public final class SkimRenderer {

    private static final String ELLIPSIS = "…";

    private SkimRenderer() {}

    /** One rendered physical line: its 1-based source line number and the text (no gutter yet). */
    private record Row(int line, String text) {}

    /** Render {@code unit} with the given char gate and doc toggle; returns the gutter'd Java, no trailing newline. */
    public static String render(SkimUnit unit, int inlineBodyMaxChars, boolean includeDocs) {
        List<Row> rows = new ArrayList<>();
        if (unit.packageLine() > 0) {
            rows.add(new Row(unit.packageLine(), "package " + unit.packageName() + ";"));
        }
        for (SkimImport imp : unit.imports()) {
            rows.add(new Row(imp.line(), imp.text()));
        }
        for (SkimDecl type : unit.types()) {
            renderDecl(type, rows, inlineBodyMaxChars, includeDocs);
        }
        return gutter(rows);
    }

    private static void renderDecl(SkimDecl d, List<Row> rows, int gate, boolean includeDocs) {
        if (includeDocs && d.docText() != null) {
            emit(rows, d.docStartLine(), d.docText(), null);
        }
        if (d.isType()) {
            if (d.children().isEmpty()) {
                // An empty (or member-less) body stays on the signature line: `class X {}`.
                emit(rows, d.startLine(), d.headerText(), " {}");
            } else {
                emit(rows, d.startLine(), d.headerText(), " {");
                for (SkimDecl child : d.children()) {
                    renderDecl(child, rows, gate, includeDocs);
                }
                rows.add(new Row(d.endLine(), leadingWhitespace(d.headerText()) + "}"));
            }
        } else if (d.hasBody()) {
            emit(rows, d.startLine(), d.headerText(), bodySuffix(d.bodyInner(), gate, d.bodyHasLineComment()));
        } else {
            // Field / abstract method / enum constant: the whole declaration is the header, shown verbatim.
            emit(rows, d.startLine(), d.headerText(), null);
        }
    }

    /** The collapsed-body tail appended to a signature: inlined verbatim under the gate, else {@code { … }}. */
    private static String bodySuffix(String bodyInner, int gate, boolean hasLineComment) {
        String norm = normalize(bodyInner);
        // A `//` line comment can't be single-lined — collapsing newlines would swallow the `}` into it,
        // so the body always elides (the inlined view stays valid Java), regardless of the char gate.
        if (hasLineComment || norm.length() > gate) {
            return " { " + ELLIPSIS + " }";
        }
        return norm.isEmpty() ? " {}" : " { " + norm + " }";
    }

    /** Emit a (possibly multi-line) verbatim span, line-numbered from {@code startLine}, optional suffix on the last line. */
    private static void emit(List<Row> rows, int startLine, String span, String suffix) {
        String[] lines = span.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i];
            if (suffix != null && i == lines.length - 1) {
                text = text + suffix;
            }
            rows.add(new Row(startLine + i, text));
        }
    }

    /** Collapse every whitespace run (incl. newlines) to a single space and trim — the char-gate measure. */
    private static String normalize(String body) {
        return body == null ? "" : body.replaceAll("\\s+", " ").trim();
    }

    /** The leading whitespace of a span's first line — reused as the closing brace's indentation (tab/space safe). */
    private static String leadingWhitespace(String span) {
        int i = 0;
        while (i < span.length() && (span.charAt(i) == ' ' || span.charAt(i) == '\t')) {
            i++;
        }
        return span.substring(0, i);
    }

    /** Prefix every row with its right-aligned source line number + two spaces (size-at-a-glance + coherence). */
    private static String gutter(List<Row> rows) {
        int max = 0;
        for (Row r : rows) {
            max = Math.max(max, r.line());
        }
        int width = Math.max(1, Integer.toString(max).length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            String num = Integer.toString(r.line());
            sb.append(" ".repeat(width - num.length())).append(num).append("  ").append(r.text());
        }
        return sb.toString();
    }
}
