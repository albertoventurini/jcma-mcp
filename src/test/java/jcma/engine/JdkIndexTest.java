package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;

import jcma.jdkindex.JdkIndexer;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-02b mechanism tests (JVM-deterministic):
 * <ul>
 *   <li>the {@link JdkIndexer} helper produces a de-moduled JDK jar that {@code JarTypeSolver} can
 *       byte-parse — the exact native-side resolve path, exercised here on the JVM;</li>
 *   <li>{@link HostJdkIndex#fingerprint} is stable for the same JDK (cache hit) and changes when the
 *       JDK's {@code release} differs (cache rebuild).</li>
 * </ul>
 * The native end-to-end contract is the {@code jdkResolveSmoke} Gradle task.
 */
class JdkIndexTest {

    @Test
    void indexerProducesJarThatResolvesJdkTypeAndMember(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("jdk-index.jar");
        JdkIndexer.main(new String[] {jar.toString()});
        assertTrue(Files.size(jar) > 0, "indexer should write a non-empty jar");

        JarTypeSolver solver = new JarTypeSolver(jar);
        var ref = solver.tryToSolveType("java.util.Arrays");
        assertTrue(ref.isSolved(), "the de-moduled JDK jar should carry java.util.Arrays");
        ResolvedReferenceTypeDeclaration arrays = ref.getCorrespondingDeclaration();
        assertTrue(arrays.getDeclaredMethods().stream().anyMatch(m -> m.getName().equals("equals")),
                "java.util.Arrays should expose its equals(..) members through the jar");
    }

    @Test
    void fingerprintIsStableForSameJdkAndChangesWithRelease(@TempDir Path tmp) throws Exception {
        Path homeA = tmp.resolve("jdkA");
        Files.createDirectories(homeA.resolve("lib"));
        Files.writeString(homeA.resolve("release"), "JAVA_VERSION=\"25.0.3\"\n");
        Files.write(homeA.resolve("lib").resolve("modules"), new byte[] {1, 2, 3, 4});

        String fp = HostJdkIndex.fingerprint(homeA);
        assertEquals(fp, HostJdkIndex.fingerprint(homeA), "same JDK → same fingerprint (cache hit)");

        Path homeB = tmp.resolve("jdkB");
        Files.createDirectories(homeB.resolve("lib"));
        Files.writeString(homeB.resolve("release"), "JAVA_VERSION=\"21.0.1\"\n");
        Files.write(homeB.resolve("lib").resolve("modules"), new byte[] {1, 2, 3, 4});
        assertNotEquals(fp, HostJdkIndex.fingerprint(homeB),
                "different release → different fingerprint (cache rebuild)");
    }
}
