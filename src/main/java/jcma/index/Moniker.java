package jcma.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The SCIP-style structured moniker scheme (PRD §5.1 symbol identity; closes a PRD §11 sub-decision)
 * and the moniker ↔ {@code int32} interner.
 *
 * <h2>Grammar</h2>
 * A moniker is a single structured string built bottom-up; each descriptor carries its own
 * terminator so monikers compose by plain concatenation:
 * <ul>
 *   <li><b>package</b> {@code com.acme.foo} → {@code "com/acme/foo/"} (dots→{@code /}, trailing
 *       {@code /}); the default package → {@code ""}.</li>
 *   <li><b>type</b> {@code Bar} under a parent moniker → {@code parent + "Bar#"} (so nested types
 *       compose: {@code com/acme/foo/Bar#Baz#}).</li>
 *   <li><b>method</b> {@code doIt(int, java.lang.String)} → {@code type + "doIt(int,java.lang.String)."}
 *       (comma-joined parameter type names, trailing {@code .}).</li>
 *   <li><b>constructor</b> → like a method named {@code <init>}.</li>
 *   <li><b>field / term</b> {@code value} → {@code type + "value."}.</li>
 * </ul>
 * This is deliberately a <em>local</em> scheme (no package-manager / version coordinates); jar/JDK
 * symbols get the same shape, keyed by their FQN, which is all navigation needs.
 */
public final class Moniker {

    private Moniker() {}

    /** Package moniker: {@code com.acme.foo → "com/acme/foo/"}; the default (empty) package → {@code ""}. */
    public static String forPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        return packageName.replace('.', '/') + "/";
    }

    /** Type {@code simpleName} nested under {@code parentMoniker} (a package or enclosing-type moniker). */
    public static String forType(String parentMoniker, String simpleName) {
        return parentMoniker + simpleName + "#";
    }

    /** Method {@code name} on {@code typeMoniker} with the given (FQN) parameter-type names. */
    public static String forMethod(String typeMoniker, String name, List<String> paramTypes) {
        return typeMoniker + name + "(" + String.join(",", paramTypes) + ").";
    }

    /** Constructor on {@code typeMoniker} — a method named {@code <init>}. */
    public static String forConstructor(String typeMoniker, List<String> paramTypes) {
        return forMethod(typeMoniker, "<init>", paramTypes);
    }

    /** Field / enum-constant {@code name} on {@code typeMoniker}. */
    public static String forField(String typeMoniker, String name) {
        return typeMoniker + name + ".";
    }

    /**
     * In-memory moniker ↔ {@code int32} interner (dense ids in first-seen order). Used during
     * indexing before persistence; the persisted {@link SymbolStore} carries its own canonical
     * moniker→id mapping.
     */
    public static final class Interner {
        private final List<String> monikerById = new ArrayList<>();
        private final Map<String, Integer> idByMoniker = new HashMap<>();

        /** Intern {@code moniker}; return its id (re-interning an equal moniker returns the same id). */
        public int intern(String moniker) {
            Integer existing = idByMoniker.get(moniker);
            if (existing != null) {
                return existing;
            }
            int id = monikerById.size();
            monikerById.add(moniker);
            idByMoniker.put(moniker, id);
            return id;
        }

        /** Id of an already-interned {@code moniker}, or {@code -1} if absent. */
        public int idOf(String moniker) {
            return idByMoniker.getOrDefault(moniker, -1);
        }

        /** The moniker for {@code id} (as assigned by {@link #intern}). */
        public String monikerOf(int id) {
            return monikerById.get(id);
        }

        /** Number of distinct interned monikers. */
        public int size() {
            return monikerById.size();
        }
    }
}
