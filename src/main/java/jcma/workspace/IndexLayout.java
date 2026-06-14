package jcma.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Where jcma keeps its on-disk artifacts.
 *
 * <p>Indexes do <b>not</b> live in the repo (a {@code <repo>/.jcma} dir would need gitignoring and
 * gets crawled by IDE indexers, leaking jcma's internal segments into IDE search). Instead — the way
 * IntelliJ keeps project caches under its system dir — each repo's index lives under a user-level
 * cache, in a per-repo subdirectory keyed by the repo's name plus a hash of its absolute path (the
 * hash disambiguates two repos that share a leaf name). This also matches the existing JDK-signature
 * cache location ({@code ~/.cache/jcma/jdk-*.jar}, PRD §5.1).
 *
 * <p>Cache root: {@code ${XDG_CACHE_HOME:-$HOME/.cache}/jcma}. Per-repo index:
 * {@code <cacheRoot>/index/<name>-<hash>}.
 */
public final class IndexLayout {

    private IndexLayout() {}

    /** The resolved-classpath cache file under an index dir (M2 task-09); see {@link #classpathCache}. */
    public static final String CLASSPATH_CACHE = "classpath.txt";

    /** {@code ${XDG_CACHE_HOME:-$HOME/.cache}/jcma} — the shared root for all jcma cache artifacts. */
    public static Path cacheRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("jcma");
    }

    /**
     * Default index directory for {@code repo}: {@code <cacheRoot>/index/<name>-<hash>}, where the
     * hash is over the repo's canonical absolute path so the same repo maps to the same dir however
     * it's referenced on the command line (relative path, symlink, trailing slash).
     */
    public static Path defaultIndexDir(Path repo) {
        return cacheRoot().resolve("index").resolve(repoSlug(repo));
    }

    /**
     * The resolved-classpath cache for an index: {@code <indexDir>/classpath.txt}, in the
     * {@link java.io.File#pathSeparator}-joined format {@link Workspace#readClasspathJars} parses.
     * {@code jcma index} writes it (resolving {@code mvn}/{@code gradle} once); every later session
     * reads it instead of re-spawning the build tool (M2 task-09). Located under the index dir, like
     * the other per-index segments, so it lives and dies with the index — never in the repo tree.
     */
    public static Path classpathCache(Path indexDir) {
        return indexDir.resolve(CLASSPATH_CACHE);
    }

    /**
     * Per-repo MCP call log: {@code <cacheRoot>/logs/<name>-<hash>.log}. One file per repo (keyed
     * the same way as the index dir) so concurrent {@code jcma serve} processes — one per repo under
     * an agent — never interleave their lines into a shared file.
     */
    public static Path serveLogFile(Path repo) {
        return cacheRoot().resolve("logs").resolve(repoSlug(repo) + ".log");
    }

    /** {@code <name>-<hash>}: a recognizable, filesystem-safe key for {@code repo}'s canonical path. */
    private static String repoSlug(Path repo) {
        Path canonical = canonicalize(repo);
        String leaf = canonical.getFileName() != null ? canonical.getFileName().toString() : "root";
        return sanitize(leaf) + "-" + hash(canonical.toString());
    }

    private static Path canonicalize(Path repo) {
        try {
            return repo.toRealPath();
        } catch (IOException e) {
            return repo.toAbsolutePath().normalize();
        }
    }

    /** Keep a recognizable, filesystem-safe leaf; collapse anything unusual to {@code _}. */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') ? c : '_');
        }
        return sb.isEmpty() ? "root" : sb.toString();
    }

    /** FNV-1a 64-bit, hex — same family used by the JDK-signature cache key (PRD §5.1). */
    private static String hash(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h = (h ^ (b & 0xff)) * 0x100000001b3L;
        }
        return Long.toHexString(h);
    }
}
