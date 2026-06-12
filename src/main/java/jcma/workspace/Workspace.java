package jcma.workspace;

import jcma.index.SourceRoot;
import jcma.index.SourceSet;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-root + classpath discovery for a Java project (PRD §4 engine inputs).
 *
 * <p><b>Source roots</b> come from the Maven {@code pom.xml} {@code <sourceDirectory>}, defaulting
 * to the standard layout {@code src/main/java}.
 *
 * <p><b>Classpath</b> has three plumbed paths (precedence order): (1) a manual {@code cp.txt} beside
 * the project root, {@link java.io.File#pathSeparator}-split, {@code .jar} entries only (the
 * {@code SolverSetup} read loop); (2) auto-detect from the build tool — {@code mvn
 * dependency:build-classpath} for Maven, or a Gradle {@code --init-script} that prints
 * {@code testRuntimeClasspath} — each writing {@code cp.txt} then re-reading it; (3) the source dirs
 * themselves — which {@code JavaParserTypeSolver} already covers, so no jars are needed for
 * source→source resolution. Automated tests use a committed {@code cp.txt}; the live {@code mvn} /
 * {@code gradle} subprocess paths are exercised only by the manual CLI check.
 *
 * <p>A <b>project root</b> is a dir carrying a Maven {@code pom.xml} or any Gradle build marker
 * ({@code build.gradle[.kts]} / {@code settings.gradle[.kts]}); {@link #discover} walks up to it.
 */
public final class Workspace {

    /** {@code <sourceDirectory>…</sourceDirectory>} (first occurrence; whitespace-tolerant). */
    private static final Pattern SOURCE_DIRECTORY =
            Pattern.compile("<sourceDirectory>\\s*(.*?)\\s*</sourceDirectory>", Pattern.DOTALL);

    /** {@code <testSourceDirectory>…</testSourceDirectory>} (first occurrence; whitespace-tolerant). */
    private static final Pattern TEST_SOURCE_DIRECTORY =
            Pattern.compile("<testSourceDirectory>\\s*(.*?)\\s*</testSourceDirectory>", Pattern.DOTALL);

    /** The {@code package a.b.c;} declaration of a compilation unit (first occurrence). */
    private static final Pattern PACKAGE_DECL =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * Directory names skipped by the per-module source-root walk: VCS/IDE metadata and build-output
     * trees that can carry their own {@code src/main/java} (e.g. an exploded jar under {@code build/}),
     * which would otherwise be mis-tagged as project source.
     */
    private static final Set<String> PRUNED_DIRS = Set.of(
            ".git", ".gradle", ".idea", "build", "target", "out", "bin", "node_modules");

    /** Defensive cap on the per-module walk depth, against pathological/symlinked trees. */
    private static final int MODULE_WALK_MAX_DEPTH = 8;

    /**
     * Gradle init script (Groovy DSL) registering {@code jcmaPrintClasspath} on the root project: it
     * prints the resolved {@code testRuntimeClasspath} (superset covering main + test deps; falls back
     * to {@code runtimeClasspath}) as a single {@code File.pathSeparator}-joined line. A non-Java root
     * (no such configuration) prints nothing, yielding an empty classpath.
     */
    private static final String GRADLE_INIT_SCRIPT = """
            gradle.rootProject {
                tasks.register('jcmaPrintClasspath') {
                    doLast {
                        def cfg = ['testRuntimeClasspath', 'runtimeClasspath']
                                .collect { configurations.findByName(it) }
                                .find { it != null }
                        if (cfg != null) {
                            println cfg.files.collect { it.absolutePath }.join(File.pathSeparator)
                        }
                    }
                }
            }
            """;

    private final Path projectRoot;
    private final List<Path> sourceRoots;
    private final List<Path> classpathJars;

    /** Direct construction (used by tests and by the discovery factory). */
    public Workspace(Path projectRoot, List<Path> sourceRoots, List<Path> classpathJars) {
        this.projectRoot = projectRoot;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.classpathJars = List.copyOf(classpathJars);
    }

    /**
     * An ad-hoc workspace rooted at a single source directory, with no classpath (source→source
     * resolution only). Use this when a directory is known to be a self-contained source tree and
     * should be taken verbatim — without {@link #discover}'s walk up to an enclosing build tool root,
     * which would (correctly) adopt that outer project instead.
     */
    public static Workspace ofSourceRoot(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        return new Workspace(root, List.of(root), List.of());
    }

    /**
     * Walk up from an arbitrary file to the enclosing project root (Maven {@code pom.xml} or a Gradle
     * build marker) and build a workspace. The target file's own source root is derived from its
     * {@code package} declaration (so a bare file resolves even without a build file), unioned with any
     * build-declared source roots; the classpath comes from the project root (cp.txt, else best-effort
     * {@code mvn}/{@code gradle}).
     */
    public static Workspace discover(Path anyFile) {
        Path start = anyFile.toAbsolutePath().normalize();
        Path startDir = Files.isDirectory(start) ? start : start.getParent();

        Path projectRoot = null;
        for (Path p = startDir; p != null; p = p.getParent()) {
            if (isProjectRoot(p)) {
                projectRoot = p;
                break;
            }
        }

        // Source roots: the package-derived root for the target file, plus pom-declared roots.
        Set<Path> roots = new LinkedHashSet<>();
        if (!Files.isDirectory(start)) {
            Path pkgRoot = packageRoot(start);
            if (pkgRoot != null) {
                roots.add(pkgRoot);
            }
        }
        if (projectRoot != null) {
            roots.addAll(discoverSourceRoots(projectRoot));
        }
        if (roots.isEmpty() && startDir != null) {
            roots.add(startDir);
        }

        Path root = projectRoot != null ? projectRoot : (startDir != null ? startDir : start);
        List<Path> jars = projectRoot != null ? resolveClasspath(projectRoot) : List.of();
        return new Workspace(root, List.copyOf(roots), jars);
    }

    /**
     * Resolve the project root for {@code start} (a directory or a file): the nearest ancestor with a
     * build marker ({@code pom.xml} or a Gradle root), else — when nothing upward looks like a
     * project — {@code start}'s own directory. This is what lets the CLI infer the repo from the
     * working directory: any subdir resolves to the same root, so the cache index is keyed
     * consistently ({@link jcma.cli} commands key {@code IndexLayout.defaultIndexDir} off this).
     */
    public static Path projectRoot(Path start) {
        Path s = start.toAbsolutePath().normalize();
        Path dir = Files.isDirectory(s) ? s : s.getParent();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (isProjectRoot(p)) {
                return p;
            }
        }
        return dir != null ? dir : s;
    }

    /**
     * The source root for a {@code .java} file, derived from its {@code package} declaration: a file
     * declaring {@code package a.b.c} sitting at {@code …/a/b/c/File.java} has source root the dir
     * three levels above. A file in the default package (or unreadable) → its own directory.
     */
    private static Path packageRoot(Path file) {
        Path dir = file.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return null;
        }
        String pkg = "";
        try {
            Matcher m = PACKAGE_DECL.matcher(Files.readString(file));
            if (m.find()) {
                pkg = m.group(1).trim();
            }
        } catch (IOException ignore) {
            // unreadable here → the engine's parse() will surface it; treat as default package
        }
        if (pkg.isEmpty()) {
            return dir;
        }
        int segments = (int) pkg.chars().filter(c -> c == '.').count() + 1;
        Path root = dir;
        for (int i = 0; i < segments && root != null; i++) {
            root = root.getParent();
        }
        return root;
    }

    /**
     * Parse a {@code cp.txt} (pathSeparator-split) into its {@code .jar} entries. Missing/empty file
     * tolerated → empty list. Non-jar entries (e.g. {@code target/classes} dirs) are dropped.
     */
    public static List<Path> readClasspathJars(Path cpFile) {
        if (cpFile == null || !Files.isRegularFile(cpFile)) {
            return List.of();
        }
        String cp;
        try {
            cp = Files.readString(cpFile).trim();
        } catch (IOException e) {
            return List.of();
        }
        if (cp.isEmpty()) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
            String e = entry.trim();
            if (e.endsWith(".jar")) {
                jars.add(Path.of(e));
            }
        }
        return List.copyOf(jars);
    }

    /** Source roots for a project root (untagged) — the {@link #discoverSourceSets} dirs in order. */
    public static List<Path> discoverSourceRoots(Path projectRoot) {
        return discoverSourceSets(projectRoot).stream().map(SourceRoot::dir).toList();
    }

    /**
     * Source roots <b>tagged</b> by {@link SourceSet} (PRD §5.1 test-source indexing). With a Maven
     * {@code pom.xml}: {@code <sourceDirectory>} (default {@code src/main/java}) → {@code MAIN}, and
     * {@code <testSourceDirectory>} → {@code TEST} (the {@code src/test/java} default is added only
     * when it exists). Without a build file: the standard {@code src/main/java} / {@code src/test/java}
     * dirs that exist, tagged by convention (covers standard Gradle too — build files aren't parsed).
     *
     * <p>The root-level result is then <b>unioned</b> with a pruned recursive walk
     * ({@link #findModuleSourceRoots}) that applies the same {@code src/main/java} / {@code src/test/java}
     * convention <em>per module</em> — so a multi-module repo (e.g. Spring) whose root carries no
     * {@code src/main/java}, only per-module ones, is discovered rather than yielding empty. The
     * {@link LinkedHashSet} dedups the root-level dirs the walk re-finds.
     *
     * <p>An ad-hoc tree with neither a pom nor any {@code src/main/java} (root or per-module) yields an
     * empty list, leaving the caller to fall back (e.g. index the repo root as {@code MAIN}).
     */
    public static List<SourceRoot> discoverSourceSets(Path projectRoot) {
        Set<SourceRoot> roots = new LinkedHashSet<>();
        Path pom = projectRoot.resolve("pom.xml");
        boolean pomParsed = false;
        if (Files.isRegularFile(pom)) {
            try {
                String content = Files.readString(pom);
                roots.add(new SourceRoot(
                        projectRoot.resolve(group(SOURCE_DIRECTORY, content, "src/main/java")), SourceSet.MAIN));
                String declaredTest = group(TEST_SOURCE_DIRECTORY, content, null);
                if (declaredTest != null) {
                    roots.add(new SourceRoot(projectRoot.resolve(declaredTest), SourceSet.TEST));
                } else {
                    addIfDir(roots, projectRoot.resolve("src/test/java"), SourceSet.TEST);
                }
                pomParsed = true;
            } catch (IOException ignore) {
                // fall through to convention-based discovery
            }
        }
        if (!pomParsed) {
            addIfDir(roots, projectRoot.resolve("src/main/java"), SourceSet.MAIN);
            addIfDir(roots, projectRoot.resolve("src/test/java"), SourceSet.TEST);
        }
        roots.addAll(findModuleSourceRoots(projectRoot));
        return List.copyOf(roots);
    }

    private static final Path SRC_MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path SRC_TEST_JAVA = Path.of("src", "test", "java");

    /**
     * Recursively collect every {@code src/main/java} ({@code MAIN}) / {@code src/test/java}
     * ({@code TEST}) directory under {@code projectRoot}, applying the convention <em>per module</em>.
     * Path-sorted for determinism. Prunes {@link #PRUNED_DIRS} (VCS/IDE/build-output trees), does not
     * descend into a matched root (its children are packages, not modules — saving the deep walk), and
     * caps depth at {@link #MODULE_WALK_MAX_DEPTH}. Hermetic — no build tool is run.
     */
    private static List<SourceRoot> findModuleSourceRoots(Path projectRoot) {
        List<SourceRoot> found = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, Set.of(), MODULE_WALK_MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(projectRoot)) {
                                return FileVisitResult.CONTINUE;
                            }
                            Path name = dir.getFileName();
                            if (name != null && PRUNED_DIRS.contains(name.toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (dir.endsWith(SRC_MAIN_JAVA)) {
                                found.add(new SourceRoot(dir, SourceSet.MAIN));
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (dir.endsWith(SRC_TEST_JAVA)) {
                                found.add(new SourceRoot(dir, SourceSet.TEST));
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignore) {
            // unreadable tree → whatever the root-level convention already found
        }
        found.sort(Comparator.comparing(r -> r.dir().toString()));
        return found;
    }

    /** The trimmed first capture of {@code p} in {@code content}, or {@code dflt} if absent/blank. */
    private static String group(Pattern p, String content, String dflt) {
        Matcher m = p.matcher(content);
        if (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        return dflt;
    }

    /** Append {@code SourceRoot(dir, set)} iff {@code dir} is an existing directory. */
    private static void addIfDir(Collection<SourceRoot> roots, Path dir, SourceSet set) {
        if (Files.isDirectory(dir)) {
            roots.add(new SourceRoot(dir, set));
        }
    }

    /**
     * Classpath jars for a project root: prefer a committed {@code cp.txt}; if absent, best-effort
     * auto-detect from the build tool — {@code mvn dependency:build-classpath} for Maven, or a Gradle
     * init script for Gradle — each writing {@code cp.txt} then re-reading it. Any failure degrades
     * to an empty classpath (source→source resolution still works).
     */
    private static List<Path> resolveClasspath(Path projectRoot) {
        Path cpFile = projectRoot.resolve("cp.txt");
        List<Path> jars = readClasspathJars(cpFile);
        if (!jars.isEmpty() || Files.exists(cpFile)) {
            return jars; // committed/manual cp.txt wins (even if it is intentionally empty)
        }
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return mavenClasspath(projectRoot, cpFile);
        }
        if (isGradleRoot(projectRoot)) {
            return gradleClasspath(projectRoot, cpFile);
        }
        return List.of();
    }

    /** Best-effort {@code mvn dependency:build-classpath} → writes {@code cp.txt}, re-read. */
    private static List<Path> mavenClasspath(Path projectRoot, Path cpFile) {
        try {
            Process proc = new ProcessBuilder("mvn", "-q",
                    "dependency:build-classpath", "-Dmdep.outputFile=cp.txt")
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes(); // drain so the subprocess can finish
            if (proc.waitFor() == 0) {
                return readClasspathJars(cpFile);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // mvn unavailable/failed → empty classpath
        }
        return List.of();
    }

    /**
     * Best-effort Gradle classpath: there is no built-in classpath task, so we feed an
     * {@code --init-script} that registers {@code jcmaPrintClasspath} on the root project — it prints
     * {@code testRuntimeClasspath} (the superset covering both main and test deps; falls back to
     * {@code runtimeClasspath}) as a {@link File#pathSeparator}-joined list. We capture that line,
     * persist it as {@code cp.txt}, and re-read. The wrapper ({@code gradlew}) is preferred over a
     * {@code gradle} on {@code PATH}. Any failure (no Gradle, non-Java root, daemon error) degrades
     * to an empty classpath.
     */
    private static List<Path> gradleClasspath(Path projectRoot, Path cpFile) {
        Path initScript = null;
        try {
            initScript = Files.createTempFile("jcma-cp", ".gradle");
            Files.writeString(initScript, GRADLE_INIT_SCRIPT);
            Process proc = new ProcessBuilder(gradleInvocation(projectRoot), "-q", "--console=plain",
                    "--init-script", initScript.toString(), "jcmaPrintClasspath")
                    .directory(projectRoot.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (proc.waitFor() == 0) {
                String cp = lastNonBlankLine(stdout);
                if (cp != null) {
                    Files.writeString(cpFile, cp);
                    return readClasspathJars(cpFile);
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // gradle unavailable/failed → empty classpath
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (IOException ignore) {
                    // temp init script cleanup is best-effort
                }
            }
        }
        return List.of();
    }

    /** The Gradle command: the project's wrapper if present, else a {@code gradle} on {@code PATH}. */
    private static String gradleInvocation(Path projectRoot) {
        String wrapper = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "gradlew.bat" : "gradlew";
        Path w = projectRoot.resolve(wrapper);
        return Files.isRegularFile(w) ? w.toAbsolutePath().toString() : "gradle";
    }

    /** The last non-blank line of {@code s} (the init-script's classpath line), or null if none. */
    private static String lastNonBlankLine(String s) {
        String last = null;
        for (String line : s.split("\\R")) {
            if (!line.isBlank()) {
                last = line.trim();
            }
        }
        return last;
    }

    /** Whether {@code dir} is a project root: a Maven {@code pom.xml} or any Gradle build marker. */
    private static boolean isProjectRoot(Path dir) {
        return Files.exists(dir.resolve("pom.xml")) || isGradleRoot(dir);
    }

    /** Whether {@code dir} carries a Gradle build marker (Kotlin or Groovy DSL, build or settings). */
    private static boolean isGradleRoot(Path dir) {
        return Files.exists(dir.resolve("build.gradle.kts"))
                || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("settings.gradle.kts"))
                || Files.exists(dir.resolve("settings.gradle"));
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public List<Path> classpathJars() {
        return classpathJars;
    }
}
