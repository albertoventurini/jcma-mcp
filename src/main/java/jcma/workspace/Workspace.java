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
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-root + classpath discovery for a Java project (PRD §4 engine inputs).
 *
 * <p><b>Source roots</b> come from the Maven {@code pom.xml} {@code <sourceDirectory>}, defaulting
 * to the standard layout {@code src/main/java}.
 *
 * <p><b>Classpath</b> is auto-detected from the build tool — {@code mvn dependency:build-classpath}
 * for Maven, or a Gradle {@code --init-script} that prints {@code testRuntimeClasspath} — each writing
 * a private temp file (never the repo tree) that we re-read into a {@link java.io.File#pathSeparator}-
 * split {@code .jar} list. Any failure degrades to an empty classpath; the source dirs themselves are
 * already covered by {@code JavaParserTypeSolver}, so no jars are needed for source→source resolution.
 * Because that subprocess is slow (cold daemon), {@code jcma index} resolves it <em>once</em> and
 * persists the result to {@link IndexLayout#classpathCache the index dir}; later sessions open via
 * {@link #discover(Path, Path)} and read that cache instead of re-spawning the build tool (M2 task-09).
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
     * Maven compiler-level tags, in resolution priority. {@code <release>} (compiler plugin) and
     * {@code <maven.compiler.release>} (property) win over source, which wins over target — matching
     * javac's own precedence. Each captures a major version, possibly {@code 1.x} (normalized later).
     * Applied to {@code help:effective-pom} output (parent/property inheritance already resolved), or
     * to the raw {@code pom.xml} as a no-subprocess fallback.
     */
    private static final Pattern MVN_RELEASE = Pattern.compile(
            "<(?:maven\\.compiler\\.release|release)>\\s*(\\d+(?:\\.\\d+)?)\\s*</(?:maven\\.compiler\\.release|release)>");
    private static final Pattern MVN_SOURCE = Pattern.compile(
            "<(?:maven\\.compiler\\.source|source)>\\s*(\\d+(?:\\.\\d+)?)\\s*</(?:maven\\.compiler\\.source|source)>");
    private static final Pattern MVN_TARGET = Pattern.compile(
            "<(?:maven\\.compiler\\.target|target)>\\s*(\\d+(?:\\.\\d+)?)\\s*</(?:maven\\.compiler\\.target|target)>");

    /**
     * Directory names skipped by the per-module source-root walk: VCS/IDE metadata and build-output
     * trees that can carry their own {@code src/main/java} (e.g. an exploded jar under {@code build/}),
     * which would otherwise be mis-tagged as project source.
     */
    private static final Set<String> PRUNED_DIRS = Set.of(
            ".git", ".gradle", ".idea", "build", "target", "out", "bin", "node_modules");

    /** Defensive cap on the per-module walk depth, against pathological/symlinked trees. */
    private static final int MODULE_WALK_MAX_DEPTH = 8;

    /** Marker prefixes the Gradle init script tags its two output lines with (keyed, order-free). */
    private static final String GRADLE_CLASSPATH_MARKER = "JCMA_CLASSPATH=";
    private static final String GRADLE_LEVEL_MARKER = "JCMA_SRC_LEVEL=";

    /**
     * Gradle init script (Groovy DSL) registering {@code jcmaPrintClasspath} on the root project. It
     * prints two keyed lines (prefixed {@link #GRADLE_CLASSPATH_MARKER} / {@link #GRADLE_LEVEL_MARKER}
     * so the reader keys off the prefix rather than line position):
     * <ul>
     *   <li>the resolved {@code testRuntimeClasspath} (superset covering main + test deps; falls back
     *       to {@code runtimeClasspath}) as a {@code File.pathSeparator}-joined list; and</li>
     *   <li>the source Java major version, from (in order) {@code java.sourceCompatibility}, the
     *       {@code compileJava} {@code options.release}, or the toolchain {@code languageVersion} — so
     *       the engine can parse at the project's level (yield/records/patterns).</li>
     * </ul>
     * Every probe is guarded: a non-Java root, a missing configuration, or an unset level simply omits
     * that line (→ empty classpath / runtime-JDK level fallback). {@code majorVersion} already
     * normalizes {@code 1.8} → {@code 8}.
     */
    private static final String GRADLE_INIT_SCRIPT = """
            gradle.rootProject {
                tasks.register('jcmaPrintClasspath') {
                    doLast {
                        def cfg = ['testRuntimeClasspath', 'runtimeClasspath']
                                .collect { configurations.findByName(it) }
                                .find { it != null }
                        if (cfg != null) {
                            println 'JCMA_CLASSPATH=' + cfg.files.collect { it.absolutePath }.join(File.pathSeparator)
                        }
                        def level = null
                        try {
                            def sc = project.hasProperty('sourceCompatibility') ? project.sourceCompatibility : null
                            if (sc != null) { level = sc.majorVersion }
                        } catch (ignored) {}
                        if (level == null) {
                            try {
                                def rel = tasks.findByName('compileJava')?.options?.release?.getOrNull()
                                if (rel != null) { level = rel.toString() }
                            } catch (ignored) {}
                        }
                        if (level == null) {
                            try {
                                def tc = project.extensions.findByName('java')?.toolchain?.languageVersion?.getOrNull()
                                if (tc != null) { level = tc.asInt().toString() }
                            } catch (ignored) {}
                        }
                        if (level != null) {
                            println 'JCMA_SRC_LEVEL=' + level
                        }
                    }
                }
            }
            """;

    private final Path projectRoot;
    private final List<Path> sourceRoots;
    private final List<Path> classpathJars;
    private final OptionalInt discoveredJavaLevel;

    /**
     * Direct construction with no discovered Java level (used by tests and {@link #ofSourceRoot}); the
     * engine then falls back to its runtime JDK feature version when parsing.
     */
    public Workspace(Path projectRoot, List<Path> sourceRoots, List<Path> classpathJars) {
        this(projectRoot, sourceRoots, classpathJars, OptionalInt.empty());
    }

    /** Direct construction carrying the build-discovered source Java major version (or empty). */
    public Workspace(Path projectRoot, List<Path> sourceRoots, List<Path> classpathJars,
            OptionalInt discoveredJavaLevel) {
        this.projectRoot = projectRoot;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.classpathJars = List.copyOf(classpathJars);
        this.discoveredJavaLevel = discoveredJavaLevel;
    }

    /**
     * Build-derived facts resolved together from a single {@code mvn}/{@code gradle} subprocess and
     * cached side by side under the index dir: the dependency {@code classpath} and the source
     * {@code javaLevel} (the build's declared {@code source}/{@code release} major version, or empty
     * when the build declares none).
     */
    public record BuildFacts(List<Path> classpath, OptionalInt javaLevel) {
        static final BuildFacts EMPTY = new BuildFacts(List.of(), OptionalInt.empty());
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
     * build marker) and build a workspace, <b>live-resolving</b> the classpath from the build tool (no
     * persistence). For ad-hoc / no-index callers (e.g. {@code jcma resolve}); a long-running session
     * with an index dir should use {@link #discover(Path, Path)} to read the persisted cache instead.
     */
    public static Workspace discover(Path anyFile) {
        return discover(anyFile, Workspace::resolveBuildFacts);
    }

    /**
     * As {@link #discover(Path)}, but the classpath and source Java level come from the
     * {@link IndexLayout#classpathCache index dir caches} when present (the files {@code jcma index}
     * wrote) — so the session reads files instead of spawning a {@code mvn}/{@code gradle} subprocess.
     * On a cache miss it live-resolves and <b>writes through</b> to the caches, so the next session is
     * fast (M2 task-09).
     */
    public static Workspace discover(Path anyFile, Path indexDir) {
        return discover(anyFile, projectRoot -> buildFactsFromCache(projectRoot, indexDir));
    }

    /** Shared discovery body; {@code facts} maps the resolved project root to its build-derived facts. */
    private static Workspace discover(Path anyFile, java.util.function.Function<Path, BuildFacts> facts) {
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
        BuildFacts f = projectRoot != null ? facts.apply(projectRoot) : BuildFacts.EMPTY;
        return new Workspace(root, List.copyOf(roots), f.classpath(), f.javaLevel());
    }

    /**
     * Resolve {@code projectRoot}'s build facts once (one {@code mvn}/{@code gradle} subprocess) and
     * persist them next to each other under {@code indexDir} (M2 task-09): the classpath to
     * {@link IndexLayout#classpathCache} and the source Java level to {@link IndexLayout#languageLevelCache}.
     * Called by {@code jcma index} so a re-index re-resolves and overwrites, tying both to the index
     * lifecycle. Returns the resolved facts (for the caller's report).
     */
    public static BuildFacts persistBuildFacts(Path projectRoot, Path indexDir) {
        BuildFacts facts = resolveBuildFacts(projectRoot);
        writeClasspathCache(IndexLayout.classpathCache(indexDir), facts.classpath());
        writeJavaLevel(IndexLayout.languageLevelCache(indexDir), facts.javaLevel());
        return facts;
    }

    /**
     * Cache-aware build facts: when the classpath cache is present (the index has been built) read both
     * caches (an absent/older level cache → empty, engine falls back to its runtime JDK); else resolve
     * once and write both through. The classpath cache's presence is the index-built signal — keyed off
     * it so a freshly-built index without the (newer) level file still reads from cache, not the build.
     */
    private static BuildFacts buildFactsFromCache(Path projectRoot, Path indexDir) {
        Path cpCache = IndexLayout.classpathCache(indexDir);
        if (Files.isRegularFile(cpCache)) {
            return new BuildFacts(readClasspathJars(cpCache),
                    readJavaLevel(IndexLayout.languageLevelCache(indexDir)));
        }
        BuildFacts facts = resolveBuildFacts(projectRoot);
        writeClasspathCache(cpCache, facts.classpath());
        writeJavaLevel(IndexLayout.languageLevelCache(indexDir), facts.javaLevel());
        return facts;
    }

    /** Write a discovered Java level as a bare integer to {@code cache} (best-effort; empty → no file). */
    private static void writeJavaLevel(Path cache, OptionalInt level) {
        if (level.isEmpty()) {
            return; // nothing discovered → leave no file; readers fall back to the runtime JDK
        }
        try {
            Path parent = cache.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(cache, Integer.toString(level.getAsInt()));
        } catch (IOException ignore) {
            // best-effort: a later session just re-resolves on a miss
        }
    }

    /** Read a bare-integer Java level from {@code cache} (the {@link #writeJavaLevel} format), or empty. */
    public static OptionalInt readJavaLevel(Path cache) {
        if (cache == null || !Files.isRegularFile(cache)) {
            return OptionalInt.empty();
        }
        try {
            return normalizeLevel(Files.readString(cache).trim());
        } catch (IOException e) {
            return OptionalInt.empty();
        }
    }

    /** Write {@code jars} as a {@link File#pathSeparator}-joined line to {@code cache} (best-effort). */
    private static void writeClasspathCache(Path cache, List<Path> jars) {
        try {
            Path parent = cache.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder sb = new StringBuilder();
            for (Path jar : jars) {
                if (sb.length() > 0) {
                    sb.append(File.pathSeparatorChar);
                }
                sb.append(jar);
            }
            Files.writeString(cache, sb.toString());
        } catch (IOException ignore) {
            // best-effort: the resolve already succeeded; a later session just re-resolves on a miss
        }
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
     * Parse a classpath file ({@link File#pathSeparator}-split) into its {@code .jar} entries — the
     * format the index-dir cache ({@link IndexLayout#classpathCache}) is written in. Missing/empty file
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
        return cp.isEmpty() ? List.of() : splitClasspath(cp);
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
     * Best-effort build facts (classpath + source Java level) for a project root, auto-detected from
     * the build tool — Maven ({@code dependency:build-classpath} + {@code help:effective-pom}) or a
     * Gradle init script — in a <b>single subprocess</b> per tool, each writing only <b>private temp
     * files</b> (never the repo tree). Any failure degrades to {@link BuildFacts#EMPTY} (source→source
     * resolution still works; the engine falls back to its runtime JDK level). Persistence to the index
     * dir is the caller's concern ({@link #persistBuildFacts} / {@link #buildFactsFromCache}).
     */
    private static BuildFacts resolveBuildFacts(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return mavenBuildFacts(projectRoot);
        }
        if (isGradleRoot(projectRoot)) {
            return gradleBuildFacts(projectRoot);
        }
        return BuildFacts.EMPTY;
    }

    /**
     * Best-effort Maven build facts in one {@code mvn} invocation: {@code dependency:build-classpath}
     * (→ classpath temp file) and {@code help:effective-pom} (→ resolved-POM temp file, parent/property
     * inheritance already applied) whose {@code release}/{@code source}/{@code target} we regex
     * ({@link #mavenLevelFromPom}). If that combined run fails (e.g. the help plugin isn't available
     * offline) we retry classpath-only so the classpath never regresses, and read the level from the
     * raw {@code pom.xml} as a no-subprocess fallback.
     */
    private static BuildFacts mavenBuildFacts(Path projectRoot) {
        Path cpFile = null;
        Path pomOut = null;
        try {
            cpFile = Files.createTempFile("jcma-cp", ".txt");
            pomOut = Files.createTempFile("jcma-pom", ".xml");
            boolean ok = runMaven(projectRoot,
                    "dependency:build-classpath", "-Dmdep.outputFile=" + cpFile.toAbsolutePath(),
                    "help:effective-pom", "-Doutput=" + pomOut.toAbsolutePath());
            List<Path> jars;
            OptionalInt level;
            if (ok) {
                jars = readClasspathJars(cpFile);
                level = mavenLevelFromPom(readOrEmpty(pomOut));
            } else {
                // The combined run failed — preserve the classpath via the original single-goal command,
                // and take the level from the raw pom (no effective-pom resolution, best-effort).
                jars = runMaven(projectRoot, "dependency:build-classpath",
                        "-Dmdep.outputFile=" + cpFile.toAbsolutePath())
                        ? readClasspathJars(cpFile) : List.of();
                level = OptionalInt.empty();
            }
            if (level.isEmpty()) {
                level = mavenLevelFromPom(readOrEmpty(projectRoot.resolve("pom.xml")));
            }
            return new BuildFacts(jars, level);
        } catch (IOException e) {
            return BuildFacts.EMPTY; // could not create a temp out-file
        } finally {
            deleteQuietly(cpFile);
            deleteQuietly(pomOut);
        }
    }

    /** Run {@code mvn -q <goals/args…>} in {@code projectRoot}; true iff it exits 0. Drains stdout. */
    private static boolean runMaven(Path projectRoot, String... goalsAndArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        cmd.add("-q");
        cmd.addAll(List.of(goalsAndArgs));
        try {
            Process proc = new ProcessBuilder(cmd)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes(); // drain so the subprocess can finish
            return proc.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false; // mvn unavailable/failed
        }
    }

    /**
     * The source Java major version declared in a POM (effective or raw), or empty. Priority follows
     * javac: {@code release} → {@code source} → {@code target}, each in either compiler-plugin
     * ({@code <release>}) or property ({@code <maven.compiler.release>}) form, normalized so
     * {@code 1.8} → {@code 8}.
     */
    static OptionalInt mavenLevelFromPom(String pom) {
        for (Pattern p : List.of(MVN_RELEASE, MVN_SOURCE, MVN_TARGET)) {
            Matcher m = p.matcher(pom);
            if (m.find()) {
                OptionalInt level = normalizeLevel(m.group(1));
                if (level.isPresent()) {
                    return level;
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Best-effort Gradle build facts in one invocation: an {@code --init-script} registers
     * {@code jcmaPrintClasspath}, which prints two keyed lines (classpath + source level; see
     * {@link #GRADLE_INIT_SCRIPT}). We capture stdout and key off the marker prefixes
     * ({@link #parseGradleFacts}). The wrapper ({@code gradlew}) is preferred over a {@code gradle} on
     * {@code PATH}. Any failure (no Gradle, non-Java root, daemon error) degrades to
     * {@link BuildFacts#EMPTY}.
     */
    private static BuildFacts gradleBuildFacts(Path projectRoot) {
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
                return parseGradleFacts(stdout);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // gradle unavailable/failed → empty facts
        } finally {
            deleteQuietly(initScript);
        }
        return BuildFacts.EMPTY;
    }

    /** Parse the init script's keyed stdout ({@code JCMA_CLASSPATH=…} / {@code JCMA_SRC_LEVEL=…}). */
    static BuildFacts parseGradleFacts(String stdout) {
        List<Path> jars = List.of();
        OptionalInt level = OptionalInt.empty();
        for (String line : stdout.split("\\R")) {
            String l = line.trim();
            if (l.startsWith(GRADLE_CLASSPATH_MARKER)) {
                jars = splitClasspath(l.substring(GRADLE_CLASSPATH_MARKER.length()));
            } else if (l.startsWith(GRADLE_LEVEL_MARKER)) {
                level = normalizeLevel(l.substring(GRADLE_LEVEL_MARKER.length()).trim());
            }
        }
        return new BuildFacts(jars, level);
    }

    /** The Gradle command: the project's wrapper if present, else a {@code gradle} on {@code PATH}. */
    private static String gradleInvocation(Path projectRoot) {
        String wrapper = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "gradlew.bat" : "gradlew";
        Path w = projectRoot.resolve(wrapper);
        return Files.isRegularFile(w) ? w.toAbsolutePath().toString() : "gradle";
    }

    /**
     * Normalize a build-declared Java version to a bare major-version int: strips a {@code 1.} prefix
     * ({@code 1.8} → {@code 8}) and any trailing non-digits. Empty when there is no leading digit.
     */
    private static OptionalInt normalizeLevel(String raw) {
        String v = raw.trim();
        if (v.startsWith("1.")) {
            v = v.substring(2);
        }
        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(v.substring(0, i)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** {@code File.pathSeparator}-split a classpath string into its {@code .jar} entries (in order). */
    private static List<Path> splitClasspath(String cp) {
        List<Path> jars = new ArrayList<>();
        for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
            String e = entry.trim();
            if (e.endsWith(".jar")) {
                jars.add(Path.of(e));
            }
        }
        return List.copyOf(jars);
    }

    /** {@link Files#readString} tolerating a missing/unreadable file (→ empty string). */
    private static String readOrEmpty(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Best-effort delete of a temp file (null-tolerant). */
    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignore) {
                // temp file cleanup is best-effort
            }
        }
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

    /**
     * The build-discovered source Java major version (e.g. {@code 17}), or empty when the build declared
     * none — in which case the engine parses at its runtime JDK feature version. Drives the engine's
     * resolving parser language level so {@code yield}/records/sealed/patterns parse (and so the symbol
     * resolver attaches to the whole compilation unit).
     */
    public OptionalInt discoveredJavaLevel() {
        return discoveredJavaLevel;
    }
}
