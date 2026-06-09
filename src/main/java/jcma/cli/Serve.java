package jcma.cli;

import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.mcp.HealthTool;
import jcma.mcp.McpServer;
import jcma.mcp.ToolRegistry;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.session.AnalysisSession;
import jcma.workspace.Reconciler;
import jcma.workspace.TreeScanSource;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jcma serve <repo>} (M2 task-2) — run the MCP (JSON-RPC over stdio) server, the in-process
 * session model of {@link Repl} generalized onto the wire (PRD §4: MCP is jcma's only surface). The
 * {@code initialize}/{@code tools/list} handshake answers instantly with no session; the index is
 * built <b>lazily</b>, synchronously, on the first {@code tools/call} (a one-time stderr note), then
 * a {@link QueryService} is held open for session-backed tools (added in tasks 4–7). A warm repo is
 * mmap-and-go — {@link Reconciler#reindex} subsumes cold-build and warm-reconcile, so we always call
 * it and let the {@link Reconciler.ReindexStats#warm() stats} decide what to report.
 */
final class Serve {

    private Serve() {}

    /** A mutable seam the bootstrap fills and session-backed handlers (tasks 4–7) read. */
    private static final class Session {
        QueryService svc;
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("jcma: usage: jcma serve <repo>");
            return 2;
        }
        Path repo = Path.of(args[1]);
        if (!Files.isDirectory(repo)) {
            err.println("jcma: not a directory: " + repo);
            return 1;
        }
        Path indexDir = repo.resolve(".jcma");
        Workspace workspace = Workspace.discover(repo);
        Metrics metrics = Metrics.create();

        // Tagged source roots (main + test); fall back to the repo itself as MAIN (cf. Index).
        List<SourceRoot> roots = new ArrayList<>();
        for (SourceRoot root : Workspace.discoverSourceSets(repo)) {
            if (Files.isDirectory(root.dir())) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            roots.add(new SourceRoot(repo, SourceSet.MAIN));
        }

        Session session = new Session();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new HealthTool(() -> session.svc == null
                ? "jcma: not yet indexed"
                : "ok — " + repo + " indexed and ready"));

        // Pause-to-index: synchronous, lazy on the first tools/call, with a one-time stderr note.
        Runnable bootstrap = () -> {
            try {
                boolean cold = !Files.isDirectory(indexDir);
                if (cold) {
                    err.println("jcma: indexing " + repo + " …");
                }
                Reconciler.ReindexStats stats;
                try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), metrics)) {
                    stats = new Reconciler(new Indexer(metrics), metrics).reindex(repo, roots, store, indexDir);
                }
                if (stats.warm()) {
                    err.println("jcma: index up to date");
                } else {
                    err.println("jcma: indexed " + stats.reparsed() + " file(s), " + stats.symbols() + " symbols");
                }
                session.svc = new QueryService(AnalysisSession.open(
                        indexDir, workspace, new TreeScanSource(workspace.sourceRoots()), metrics));
            } catch (java.io.IOException e) {
                throw new UncheckedIOException("index build failed", e);
            }
        };

        try {
            new McpServer(System.in, out, err, registry, bootstrap, metrics).serve();
            return 0;
        } catch (Exception e) {
            err.println("jcma: serve failed: " + e.getMessage());
            return 1;
        } finally {
            if (session.svc != null) {
                try {
                    session.svc.close();
                } catch (Exception e) {
                    err.println("jcma: warning: failed to close session: " + e.getMessage());
                }
            }
        }
    }
}
