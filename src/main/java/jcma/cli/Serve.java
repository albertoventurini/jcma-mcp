package jcma.cli;

import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import jcma.index.SymbolStore;
import jcma.mcp.HealthTool;
import jcma.mcp.McpServer;
import jcma.mcp.ToolRegistry;
import jcma.obs.CallLog;
import jcma.obs.FileCallLog;
import jcma.obs.Metrics;
import jcma.obs.MetricsReport;
import jcma.query.QueryService;
import jcma.response.BudgetPolicy;
import jcma.session.AnalysisSession;
import jcma.tools.FindDefinitionTool;
import jcma.tools.FindReferencesTool;
import jcma.tools.FindSubtypesTool;
import jcma.tools.FindSupertypesTool;
import jcma.tools.GrepJavaTool;
import jcma.tools.SearchSymbolsTool;
import jcma.workspace.IndexLayout;
import jcma.workspace.IndexLock;
import jcma.workspace.IndexLockedException;
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
 * {@code jcma serve} (M2 task-2) — run the MCP (JSON-RPC over stdio) server for the repo inferred
 * from the working directory, the in-process
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

    static int run(Path cwd, String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("jcma: usage: jcma serve");
            return 2;
        }
        Path repo = Workspace.projectRoot(cwd);
        Path indexDir = IndexLayout.defaultIndexDir(repo);

        // Single-writer: serve must own the index. Fail fast if another jcma process holds the write
        // lock — a second writable server would corrupt the shared overlay log + base segments.
        IndexLock lock;
        try {
            lock = IndexLock.acquire(indexDir);
        } catch (IndexLockedException e) {
            err.println("jcma: " + e.getMessage());
            return 1;
        } catch (java.io.IOException e) {
            err.println("jcma: serve failed: " + e.getMessage());
            return 1;
        }

        Workspace workspace = Workspace.discover(repo, indexDir);
        Metrics metrics = Metrics.create();
        // Per-repo, size-bounded JSON call log — the only persistent per-call trail (the wire carries none).
        CallLog callLog = FileCallLog.open(IndexLayout.serveLogFile(repo), 8L << 20);

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
        BudgetPolicy budget = BudgetPolicy.defaultPolicy(metrics);
        registry.register(new HealthTool(() -> {
            String base = session.svc == null
                    ? "jcma: not yet indexed"
                    : "ok — " + repo + " indexed and ready";
            String report = MetricsReport.format(metrics);
            return report.isEmpty() ? base : base + "\n\n" + report;
        }));
        registry.register(new FindDefinitionTool(() -> session.svc, budget));
        registry.register(new FindReferencesTool(() -> session.svc, budget));
        registry.register(new FindSupertypesTool(() -> session.svc, budget));
        registry.register(new FindSubtypesTool(() -> session.svc, budget));
        registry.register(new SearchSymbolsTool(() -> session.svc, budget));
        registry.register(new GrepJavaTool(() -> session.svc, budget));

        // Pause-to-index: synchronous, lazy on the first tools/call, with a one-time stderr note.
        Runnable bootstrap = () -> {
            try {
                // "Cold" = never indexed. The write lock pre-creates indexDir (to hold index.lock), so
                // the signal is the absence of the base segment, not of the directory.
                boolean cold = !Files.exists(indexDir.resolve(SymbolStore.FILE_NAME));
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
                // Always-on status line → the MCP-host server log (stderr): the jar reads in
                // AnalysisSession.open are the residual cold cost, never a silent hang.
                err.println("jcma: loading " + workspace.classpathJars().size() + " dependency jars…");
                session.svc = new QueryService(AnalysisSession.open(
                        indexDir, workspace, new TreeScanSource(workspace.sourceRoots()), metrics));
            } catch (java.io.IOException e) {
                throw new UncheckedIOException("index build failed", e);
            }
        };

        try {
            new McpServer(System.in, out, err, registry, bootstrap, metrics, callLog).serve();
            return 0;
        } catch (Exception e) {
            err.println("jcma: serve failed: " + e.getMessage());
            return 1;
        } finally {
            String report = MetricsReport.format(metrics);
            if (!report.isEmpty()) {
                err.println("jcma: metrics on shutdown —\n" + report);
            }
            if (session.svc != null) {
                try {
                    session.svc.close();
                } catch (Exception e) {
                    err.println("jcma: warning: failed to close session: " + e.getMessage());
                }
            }
            // Release the write lock only after the session is closed, so nothing mutates the index
            // between the last close and the lock drop.
            try {
                lock.close();
            } catch (java.io.IOException e) {
                err.println("jcma: warning: failed to release index lock: " + e.getMessage());
            }
        }
    }
}
