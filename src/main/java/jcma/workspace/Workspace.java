package jcma.workspace;

import jcma.index.SourceRoot;
import jcma.index.SourceSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * {@code SolverSetup} read loop); (2) auto-detect via {@code mvn dependency:build-classpath}
 * (subprocess), which writes {@code cp.txt} then re-reads it; (3) the source dirs themselves —
 * which {@code JavaParserTypeSolver} already covers, so no jars are needed for source→source
 * resolution. Automated tests use a committed {@code cp.txt}; the live {@code mvn} path is exercised
 * only by the manual CLI check.
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
     * Walk up from an arbitrary file to the enclosing project root (pom.xml) and build a workspace.
     * The target file's own source root is derived from its {@code package} declaration (so a bare
     * file resolves even without a pom), unioned with any pom-declared source roots; the classpath
     * comes from the project root (cp.txt, else best-effort mvn).
     */
    public static Workspace discover(Path anyFile) {
        Path start = anyFile.toAbsolutePath().normalize();
        Path startDir = Files.isDirectory(start) ? start : start.getParent();

        Path projectRoot = null;
        for (Path p = startDir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("pom.xml"))) {
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
     * An ad-hoc tree with neither a pom nor the standard layout yields an empty list, leaving the
     * caller to fall back (e.g. index the repo root as {@code MAIN}).
     */
    public static List<SourceRoot> discoverSourceSets(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            try {
                String content = Files.readString(pom);
                List<SourceRoot> roots = new ArrayList<>();
                roots.add(new SourceRoot(
                        projectRoot.resolve(group(SOURCE_DIRECTORY, content, "src/main/java")), SourceSet.MAIN));
                String declaredTest = group(TEST_SOURCE_DIRECTORY, content, null);
                if (declaredTest != null) {
                    roots.add(new SourceRoot(projectRoot.resolve(declaredTest), SourceSet.TEST));
                } else {
                    addIfDir(roots, projectRoot.resolve("src/test/java"), SourceSet.TEST);
                }
                return roots;
            } catch (IOException ignore) {
                // fall through to convention-based discovery
            }
        }
        List<SourceRoot> roots = new ArrayList<>();
        addIfDir(roots, projectRoot.resolve("src/main/java"), SourceSet.MAIN);
        addIfDir(roots, projectRoot.resolve("src/test/java"), SourceSet.TEST);
        return roots;
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
    private static void addIfDir(List<SourceRoot> roots, Path dir, SourceSet set) {
        if (Files.isDirectory(dir)) {
            roots.add(new SourceRoot(dir, set));
        }
    }

    /**
     * Classpath jars for a project root: prefer a committed {@code cp.txt}; if absent, best-effort
     * {@code mvn dependency:build-classpath} (writes {@code cp.txt}) and re-read. Either failure
     * degrades to an empty classpath (source→source resolution still works).
     */
    private static List<Path> resolveClasspath(Path projectRoot) {
        Path cpFile = projectRoot.resolve("cp.txt");
        List<Path> jars = readClasspathJars(cpFile);
        if (!jars.isEmpty() || Files.exists(cpFile)) {
            return jars; // committed/manual cp.txt wins (even if it is intentionally empty)
        }
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
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
        }
        return List.of();
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
