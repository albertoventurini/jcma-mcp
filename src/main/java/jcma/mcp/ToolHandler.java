package jcma.mcp;

import jcma.mcp.json.JsonValue;
import jcma.response.ToolResult;

/**
 * One MCP tool, the seam between the {@link McpServer} dispatch loop and the §6 query tools (added in
 * tasks 4–7). {@link #name()}, {@link #description()} and {@link #schema()} are static metadata — they
 * need no session, so {@code tools/list} answers before any index exists; only {@link #call} touches
 * the (lazily built) session. A handler that throws is caught by the server and reported as an
 * {@code isError:true} {@link ToolResult}, never a transport error.
 */
public interface ToolHandler {

    /** The tool's unique name (the {@code tools/call} {@code params.name} key). */
    String name();

    /** A one-line description for the {@code tools/list} entry. */
    String description();

    /** The tool's input JSON schema (the {@code inputSchema} of the {@code tools/list} entry). */
    JsonValue schema();

    /** Run the tool against its {@code arguments} object (may be JSON {@code null} / absent). */
    ToolResult call(JsonValue args);
}
