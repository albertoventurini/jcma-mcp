import java.util.Arrays;

/**
 * Task-02b fixture: JDK-target call sites whose resolution depends on a working JDK type-solver
 * (reflection on the JVM, the host-derived index on native). Also implements a JDK interface so the
 * supertype-hierarchy path through the JDK is exercised.
 */
public class JdkCalls implements Comparable<JdkCalls> {

    void m() {
        byte[] a = {1};
        byte[] b = {2};
        boolean eq = Arrays.equals(a, b);   // java.util.Arrays.equals(byte[], byte[])
        System.out.println("hi");           // java.io.PrintStream.println(java.lang.String)
    }

    @Override
    public int compareTo(JdkCalls o) {       // overrides java.lang.Comparable.compareTo
        return 0;
    }
}
