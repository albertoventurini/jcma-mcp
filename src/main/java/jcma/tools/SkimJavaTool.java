package jcma.tools;

import jcma.engine.SkimUnit;
import jcma.engine.StructuralParser;
import jcma.mcp.ToolHandler;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.render.SkimRenderer;
import jcma.response.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code skim_java} §6 tool: the agent's "read this file to learn its shape" reflex, made cheap.
 * It renders one {@code .java} file as <b>real Java with method bodies elided</b> (IDE collapse-all) —
 * trivially-short bodies kept inline, docs preserved, behind a source-true line-number gutter — so the
 * agent gets a file's shape without pulling every body it didn't need. It is for orientation, not
 * behavior (the logic lives in the bodies); elision is always visible ({@code { … }} + a gutter gap),
 * so the agent knows it is seeing shape, not source.
 *
 * <p>Fresh-parse per call (as {@code jcma outline}): the index holds no render-grade data (no
 * modifiers/annotations/verbatim doc+body), so the file is parsed to the AST regardless — which also
 * sidesteps any staleness. No {@code QueryService}/index needed: pure parse + render.
 */
public final class SkimJavaTool implements ToolHandler {

    /**
     * Default body-inline char gate. <b>Calibrated 2026-06-18</b> on jcma's own {@code src/main} (~770
     * block bodies): the whitespace-normalized inner-body length where the corpus splits cleanly —
     * below ~100 chars sit getters / delegators / guard one-liners / field-assign constructors (shape,
     * not logic), and the band above ~100 is multi-statement real logic (loops, branching, arithmetic).
     * 100 inlines the whole delegator band (honoring the locked "bias to show on ties": showing a short
     * body is never a lie) while eliding real logic. The gate measures the body's normalized inner text,
     * not the signature line — so a short body inlines regardless of how long its signature is. Mirrors
     * the {@code grep_java} convention: a measured boundary, never a round number on faith.
     */
    public static final int DEFAULT_INLINE_BODY_MAX_CHARS = 100;

    private final Path repoRoot;

    public SkimJavaTool(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Override
    public String name() {
        return "skim_java";
    }

    @Override
    public String description() {
        return "Skim a `.java` file to learn its shape — use instead of `Read` for an exploratory read. "
                + "Renders the file as real Java with method bodies elided (`{ … }`), short bodies kept "
                + "inline, docs preserved, behind a source-true line-number gutter. It shows shape, not "
                + "behavior: the logic lives in the elided bodies, and the gutter gap shows how much each "
                + "hides — drill into a body with `Read` at the shown lines. `path` (required) is repo-"
                + "relative or absolute. `inlineBodyMaxChars` (default " + DEFAULT_INLINE_BODY_MAX_CHARS
                + ") sets the inline-body width. `includeDocs` (default true) drops Javadoc when false.";
    }

    @Override
    public JsonValue schema() {
        JsonObject props = JsonObject.empty()
                .with("path", ToolSupport.typed("string",
                        "The `.java` file to skim (repo-relative or absolute)."))
                .with("inlineBodyMaxChars", ToolSupport.typed("integer",
                        "Inline a method body when its normalized length is at most this (default "
                                + DEFAULT_INLINE_BODY_MAX_CHARS + "); longer bodies elide to `{ … }`."))
                .with("includeDocs", ToolSupport.typed("boolean",
                        "Render Javadoc verbatim (default true); false drops it entirely."));
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", props)
                .with("required", new JsonValue.JsonArray(List.of(JsonValue.of("path"))));
    }

    @Override
    public ToolResult call(JsonValue args) {
        JsonObject in = ToolSupport.obj(args);
        String pathArg = in.optString("path");
        if (pathArg == null || pathArg.isEmpty()) {
            return ToolResult.error("skim_java: provide a `path`");
        }
        Path file = resolve(pathArg);
        if (!Files.isRegularFile(file)) {
            return ToolResult.error("skim_java: not a file: " + pathArg);
        }

        Integer gateArg = ToolSupport.optInt(in, "inlineBodyMaxChars");
        int gate = gateArg == null ? DEFAULT_INLINE_BODY_MAX_CHARS : gateArg;
        boolean includeDocs = ToolSupport.optBool(in, "includeDocs", true);

        try {
            SkimUnit unit = new StructuralParser().collect(file).skim();
            return ToolResult.text(SkimRenderer.render(unit, gate, includeDocs));
        } catch (IOException e) {
            return ToolResult.error("skim_java failed: " + e.getMessage());
        }
    }

    /** Resolve {@code path} against the repo root (absolute paths pass through), mirroring FreshnessGuard. */
    private Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : repoRoot.resolve(p);
    }
}
