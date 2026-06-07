package jcma.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Native-side resolver for the host JDK's signature index (Task-02b). Produces a de-moduled JDK jar
 * that {@link JavaParserEngine} feeds to a {@code JarTypeSolver} in place of the reflection-based
 * {@code ReflectionTypeSolver} (which native-image serves only for build-time-registered classes —
 * see {@code milestones/M0-RESULTS.md}, "JDK half"). Used <b>only on the native path</b>; the JVM/dev
 * path keeps {@code ReflectionTypeSolver} (host classes are loaded → reflection is a known-good
 * fallback).
 *
 * <ul>
 *   <li><b>Cache hit (normal path):</b> discover host JDK → fingerprint → return the cached
 *       {@code jdk-<fp>.jar}. No subprocess.</li>
 *   <li><b>Cache miss (≈once per JDK version):</b> extract the embedded {@code jcma-jdk-indexer.jar}
 *       → run {@code $JAVA_HOME/bin/java -jar <indexer> <tmp>} (the {@link jcma.jdkindex.JdkIndexer}
 *       helper, which reads the JDK's own {@code jrt:/} image) → atomic-rename {@code tmp ->
 *       jdk-<fp>.jar}.</li>
 *   <li><b>Any failure</b> (no {@code JAVA_HOME}, no {@code bin/java}, helper error): degrade — return
 *       empty so the engine adds no JDK solver and still resolves source + jars.</li>
 * </ul>
 *
 * <p>The index carries only {@code java.*}/{@code jdk.*} classes, so it can never mis-resolve a
 * project type — no solver-ordering wart on the native path.
 */
public final class HostJdkIndex {

    private static final boolean DEBUG = System.getenv("JCMA_DEBUG") != null;

    /** Embedded indexer jar (built by the Gradle {@code jdkIndexerJar} task, on the classpath root). */
    private static final String INDEXER_RESOURCE = "/jcma/jdkindex/jcma-jdk-indexer.jar";

    private HostJdkIndex() {}

    /**
     * The cached de-moduled JDK jar for the host JDK, building it on a cache miss. Degrade-safe:
     * returns {@link Optional#empty()} on any problem (with a {@code JCMA_DEBUG} diagnostic), never
     * throwing into the engine constructor.
     */
    public static Optional<Path> resolveCacheJar() {
        try {
            // Host-JDK discovery: JAVA_HOME → require bin/java. (Project-toolchain discovery is
            // deferred — a future sibling of Workspace's classpath discovery.)
            String javaHomeEnv = System.getenv("JAVA_HOME");
            if (javaHomeEnv == null || javaHomeEnv.isBlank()) {
                return debugEmpty("JAVA_HOME unset");
            }
            Path javaHome = Path.of(javaHomeEnv);
            Path javaBin = javaHome.resolve("bin").resolve("java");
            if (!Files.isRegularFile(javaBin)) {
                return debugEmpty("no bin/java under JAVA_HOME=" + javaHome);
            }

            Path cacheDir = cacheDir();
            Files.createDirectories(cacheDir);
            Path cacheJar = cacheDir.resolve("jdk-" + fingerprint(javaHome) + ".jar");
            if (Files.isRegularFile(cacheJar)) {
                return Optional.of(cacheJar); // cache hit — the normal path
            }
            return Optional.of(buildIndex(javaBin, cacheDir, cacheJar));
        } catch (Throwable t) {
            return debugEmpty(String.valueOf(t));
        }
    }

    /** Cache miss: run the embedded indexer on the host JVM and atomic-rename its output into place. */
    private static Path buildIndex(Path javaBin, Path cacheDir, Path cacheJar)
            throws IOException, InterruptedException {
        Path indexer = extractIndexer(cacheDir);
        Path tmp = Files.createTempFile(cacheDir, cacheJar.getFileName() + ".", ".tmp");
        try {
            Process proc = new ProcessBuilder(
                    javaBin.toString(), "-jar", indexer.toString(), tmp.toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("indexer exited " + exit + ": " + out.strip());
            }
            // Temp-file + atomic rename guards concurrent first-runs: each writes its own temp; the
            // rename is the only publish, and the target content is identical either way.
            try {
                Files.move(tmp, cacheJar, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException atomicUnsupported) {
                Files.move(tmp, cacheJar, StandardCopyOption.REPLACE_EXISTING);
            }
            return cacheJar;
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(indexer);
        }
    }

    /** Extract the embedded indexer jar to a temp file under the cache dir for this invocation. */
    private static Path extractIndexer(Path cacheDir) throws IOException {
        Path tmp = Files.createTempFile(cacheDir, "jcma-jdk-indexer-", ".jar");
        try (InputStream in = HostJdkIndex.class.getResourceAsStream(INDEXER_RESOURCE)) {
            if (in == null) {
                Files.deleteIfExists(tmp);
                throw new IOException("embedded indexer resource missing: " + INDEXER_RESOURCE);
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    /**
     * A stable fingerprint of the host JDK: a fast hash over {@code $JAVA_HOME/release} bytes mixed
     * with the {@code lib/modules} jimage size — enough to distinguish JDK version/vendor/build, which
     * is all the per-version signature cache needs (PRD §5.1 freshness model).
     *
     * <p><b>FNV-1a 64-bit (not xxHash64) by design.</b> xxHash64's speed comes from a 4-lane,
     * 32-byte-stripe design that only pays off on large buffers; file fingerprints (whole source
     * files) use it for exactly that reason. This key is the opposite: a one-line {@code release} file
     * + an 8-byte size, hashed once per startup on a cache miss. At that size xxHash64's fixed
     * setup/avalanche overhead buys nothing, so we keep FNV-1a — simpler and at least as efficient for
     * a tiny cold-path input. Each hash is matched to its input size (PRD §11).
     */
    static String fingerprint(Path javaHome) throws IOException {
        long h = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        Path release = javaHome.resolve("release");
        if (Files.isRegularFile(release)) {
            for (byte b : Files.readAllBytes(release)) {
                h = (h ^ (b & 0xff)) * 0x100000001b3L;
            }
        }
        long modulesSize = 0;
        Path modules = javaHome.resolve("lib").resolve("modules");
        if (Files.isRegularFile(modules)) {
            modulesSize = Files.size(modules);
        }
        for (int i = 0; i < 8; i++) {
            h = (h ^ ((modulesSize >>> (i * 8)) & 0xff)) * 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    /** {@code ${XDG_CACHE_HOME:-$HOME/.cache}/jcma}. */
    private static Path cacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("jcma");
    }

    private static Optional<Path> debugEmpty(String reason) {
        if (DEBUG) {
            System.err.println("  skip JDK index: " + reason);
        }
        return Optional.empty();
    }
}
