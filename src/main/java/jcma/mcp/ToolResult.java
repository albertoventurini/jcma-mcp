package jcma.mcp;

/**
 * The outcome of one {@link ToolHandler#call}. Minimal for task-2: a single text payload plus the
 * MCP {@code isError} flag (PRD §6). A failing tool is <em>not</em> a transport error — it returns a
 * successful JSON-RPC {@code result} whose {@code isError} is {@code true} (MCP convention), so the
 * agent sees the failure as a tool outcome, not a protocol fault. Task-3 enriches this into the full
 * shaping/budget model; for now it is the seam, not the richness.
 */
public record ToolResult(String text, boolean isError) {

    /** A successful result carrying {@code text}. */
    public static ToolResult text(String text) {
        return new ToolResult(text, false);
    }

    /** A tool-level failure carrying an error {@code message} ({@code isError:true}). */
    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
