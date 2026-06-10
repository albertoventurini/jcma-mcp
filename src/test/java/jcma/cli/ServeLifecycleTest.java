package jcma.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import jcma.workspace.IndexLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-2 — {@code jcma serve <repo>} lifecycle, driven through the same {@code Main.run} dispatch the
 * native binary uses, with a scripted JSON-RPC conversation piped through {@code System.in}. Proves
 * the settled pause-to-index UX: the {@code initialize}/{@code tools/list} handshake answers with no
 * index on disk, and the index is built lazily on the first {@code tools/call}; a re-serve against an
 * already-indexed repo is mmap-and-go (warm reconcile, no rebuild).
 */
class ServeLifecycleTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/indexer");

    private record Run(int exit, String out, String err) {}

    /** Dispatch {@code args}, feeding {@code stdin} as the process's standard input. */
    private static Run dispatch(String stdin, String... args) {
        InputStream savedIn = System.in;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(UTF_8)));
            int exit = Main.run(args, new PrintStream(outBuf, true, UTF_8), new PrintStream(errBuf, true, UTF_8));
            return new Run(exit, outBuf.toString(UTF_8), errBuf.toString(UTF_8));
        } finally {
            System.setIn(savedIn);
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static final String INIT =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
    private static final String TOOLS_LIST =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    private static final String CALL_HEALTH =
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"health\",\"arguments\":{}}}";

    @Test
    void handshakeAloneNeverBuildsTheIndexButFirstToolCallDoes(@TempDir Path repo) throws IOException {
        copyTree(FIXTURE, repo);
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        try {
            // Run A: handshake only (initialize + tools/list, then EOF). No tool call → no index.
            Run handshake = dispatch(INIT + "\n" + TOOLS_LIST + "\n", "serve", "-C", repo.toString());
            assertEquals(0, handshake.exit(), handshake.err());
            assertTrue(handshake.out().contains("2025-06-18"), "initialize answered: " + handshake.out());
            assertTrue(handshake.out().contains("health"), "tools/list answered: " + handshake.out());
            assertFalse(Files.isDirectory(indexDir), "the handshake must not build an index");

            // Run B: a tools/call lazily builds the index before answering.
            Run call = dispatch(INIT + "\n" + CALL_HEALTH + "\n", "serve", "-C", repo.toString());
            assertEquals(0, call.exit(), call.err());
            assertTrue(Files.isDirectory(indexDir), "the first tools/call builds the index");
            assertTrue(call.err().contains("indexing"), "a one-time stderr indexing note: " + call.err());
            assertTrue(call.out().contains("\"isError\":false"), "the tool call returns a result: " + call.out());
        } finally {
            cleanCache(repo, indexDir);
        }
    }

    @Test
    void serveAgainstAnAlreadyIndexedRepoDoesNotRebuild(@TempDir Path repo) throws IOException {
        copyTree(FIXTURE, repo);
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        try {
            Run prebuild = dispatch("", "index", "-C", repo.toString());
            assertEquals(0, prebuild.exit(), prebuild.err());
            assertTrue(Files.isDirectory(indexDir), "pre-built index present");

            Run warm = dispatch(INIT + "\n" + CALL_HEALTH + "\n", "serve", "-C", repo.toString());
            assertEquals(0, warm.exit(), warm.err());
            assertTrue(warm.err().contains("up to date"), "a warm reconcile reports up-to-date: " + warm.err());
            assertFalse(warm.err().contains("indexing"), "a warm repo is not re-indexed: " + warm.err());
        } finally {
            cleanCache(repo, indexDir);
        }
    }

    /** Remove every ~/.cache artifact a serve run created for {@code repo}: the index dir and the call log. */
    private static void cleanCache(Path repo, Path indexDir) throws IOException {
        deleteRecursively(indexDir);
        Path log = IndexLayout.serveLogFile(repo);
        Files.deleteIfExists(log);
        Files.deleteIfExists(log.resolveSibling(log.getFileName() + ".1"));
    }

    /** Remove the default cache dir a serve/index run created so the test leaves no trace in ~/.cache. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void usageWhenExtraArgs() {
        // serve takes no positionals now (repo comes from the working dir); a stray arg is a usage error.
        assertEquals(2, dispatch("", "serve", "x").exit());
    }
}
