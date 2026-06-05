package jcma.jdkindex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JDK signature-index helper — runs on the <b>host JVM</b>, never inside the native image. Spawned by
 * {@link jcma.engine.HostJdkIndex} on a cache miss (≈once per JDK version): it reads the running JDK's
 * own classes through the JVM's built-in {@code jrt:/} filesystem — available on <b>every JDK 9+</b>
 * whether or not {@code jmods/} ships (a JRE / jlink image carries only the {@code lib/modules}
 * jimage) — and repacks them, de-moduled, into a plain jar.
 *
 * <p>The native side then feeds that jar to JavaParser's {@code JarTypeSolver}: the exact native-safe,
 * javassist byte-parse path the Task-02 {@code --enable-url-protocols=jar} fix already proved. Running
 * the read on a real JVM is what keeps <b>zero JDK-internal API out of the native image</b>.
 *
 * <p>Pure JDK — {@code java.nio} + {@code java.util.zip} only, no JavaParser. Compiled at release 17
 * (see {@code build.gradle.kts}) so it runs on a range of host JDKs; the one source of truth for this
 * class is the embedded jar, so the main {@code compileJava} excludes {@code jcma/jdkindex/**}.
 *
 * <p>Usage: {@code java -jar jcma-jdk-indexer.jar <out.jar>}.
 */
public final class JdkIndexer {

    private JdkIndexer() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java -jar jcma-jdk-indexer.jar <out.jar>");
            System.exit(2);
        }
        Path outJar = Path.of(args[0]);
        int written = index(outJar);
        System.out.println("jcma-jdk-indexer: wrote " + written + " classes -> " + outJar);
    }

    /** Repack every JDK class from {@code jrt:/modules} into {@code outJar}; returns the class count. */
    static int index(Path outJar) throws IOException {
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modules = jrt.getPath("/modules");
        Set<String> seen = new HashSet<>();
        int written = 0;
        try (OutputStream os = Files.newOutputStream(outJar);
                ZipOutputStream zip = new ZipOutputStream(os);
                Stream<Path> walk = Files.walk(modules)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path nameP = p.getFileName();
                String fileName = nameP == null ? "" : nameP.toString();
                if (!fileName.endsWith(".class") || fileName.equals("module-info.class")) {
                    continue;
                }
                // /modules/<module>/java/util/Arrays.class -> java/util/Arrays.class
                String entryName = stripModulePrefix(p);
                if (entryName == null || !seen.add(entryName)) {
                    continue; // first module wins on a duplicate (split packages across modules)
                }
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(p, zip);
                zip.closeEntry();
                written++;
            }
        }
        return written;
    }

    /** {@code /modules/<module>/<pkg>/<Class>.class} -> {@code <pkg>/<Class>.class} (null if malformed). */
    private static String stripModulePrefix(Path classPath) {
        // jrt path elements: [modules, <module>, <pkg parts...>, <Class>.class]
        int n = classPath.getNameCount();
        if (n < 3) {
            return null;
        }
        // jrt uses '/' as its separator, so toString() yields a valid zip entry name.
        return classPath.subpath(2, n).toString();
    }
}
