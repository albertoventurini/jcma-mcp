package jcma.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import jcma.IndexFixture;
import jcma.mcp.McpServer;
import jcma.mcp.ToolHandler;
import jcma.mcp.ToolRegistry;
import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.session.AnalysisSession;
import jcma.workspace.Workspace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Shared harness for the §6 tool tests (M2 task-04): build a persisted index of an in-tree fixture and
 * open a {@link QueryService} over it (the {@code () -> svc} session seam the tools take), plus a
 * one-shot {@code tools/call} transcript through a real {@link McpServer} (cf. {@code McpServerTest}).
 */
final class ToolTestSupport {

    private ToolTestSupport() {}

    /** Index {@code repo} into {@code indexDir} and open a query service over it (caller closes it). */
    static QueryService queryService(Path repo, Path indexDir) throws IOException {
        IndexFixture.build(repo, indexDir);
        return new QueryService(AnalysisSession.open(indexDir, Workspace.ofSourceRoot(repo), Metrics.noop()));
    }

    /**
     * Drive {@code initialize} + a single {@code tools/call} for {@code tool} (with {@code argsJson} as
     * the raw {@code arguments} object) through a real {@link McpServer}, returning the parsed
     * {@code tools/call} reply — the true end-to-end path through the registry and the wire.
     */
    static JsonValue callThroughServer(ToolHandler tool, String argsJson) throws IOException {
        ToolRegistry reg = new ToolRegistry();
        reg.register(tool);
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool.name() + "\",\"arguments\":" + argsJson + "}}";
        ByteArrayInputStream in = new ByteArrayInputStream((init + "\n" + call + "\n").getBytes(UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        new McpServer(in, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8),
                reg, () -> {}, Metrics.noop()).serve();
        JsonValue last = null;
        for (String line : out.toString(UTF_8).split("\n")) {
            if (!line.isBlank()) {
                last = JsonReader.parse(line.trim());
            }
        }
        return last; // the tools/call reply (id 2)
    }

    /** The single text block of a {@code tools/call} reply. */
    static String textOf(JsonValue reply) {
        return reply.get("result").get("content").asArray().elements().get(0).get("text").asString();
    }

    /** The {@code isError} flag of a {@code tools/call} reply. */
    static boolean isError(JsonValue reply) {
        return reply.get("result").get("isError").asBoolean();
    }
}
