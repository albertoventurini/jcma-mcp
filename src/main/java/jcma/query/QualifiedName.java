package jcma.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Suffix-anchored, segment-exact matching of a typed (optionally qualified or fully-qualified) name
 * against a symbol's moniker — the promoted, <b>structure-independent</b> selector business rule for
 * {@code find_definition}/{@code find_references} (M2 task-04). Like {@link SymbolRanking}, it lives in
 * the query layer rather than at the MCP tool surface so every consumer — the §6 tools, the CLI
 * {@code def}/{@code refs}, and the REPL — resolves a qualified name <em>identically</em>.
 *
 * <p>The typed name is split on {@code .}; it matches iff its segments are a <b>contiguous tail</b> of
 * the moniker's name-path (its package/type/member segments, with the parameter descriptor and a
 * leading phantom {@code ~} stripped). Consequences, all deliberate:
 * <ul>
 *   <li>a bare simple name ({@code area}) is a length-1 suffix → matches every {@code area};</li>
 *   <li>a type qualifier ({@code Circle.area}) anchors to the enclosing type — it matches
 *       {@code …/Circle#area()} but not {@code …/Circles/MyAwesomeCircle#area()} (a different type,
 *       never a raw substring) nor {@code …/Shape#area()} (a different enclosing type);</li>
 *   <li>two legitimately same-named types are <em>genuine ambiguity</em>: {@code Circle.area} matches
 *       both and is narrowed by adding a segment ({@code shapes.Circle.area});</li>
 *   <li>a full FQN is the maximal suffix — unique <em>up to overloads</em> (which share a name-path,
 *       since they differ only in the dropped descriptor; position mode separates those).</li>
 * </ul>
 */
public final class QualifiedName {

    private QualifiedName() {}

    /** Whether {@code moniker}'s name-path ends with the dotted {@code typed} name (see the class doc). */
    public static boolean matches(String moniker, String typed) {
        List<String> path = namePath(moniker);
        String[] query = typed.split("\\.");
        if (query.length > path.size()) {
            return false;
        }
        int offset = path.size() - query.length; // align the query against the path's tail
        for (int i = 0; i < query.length; i++) {
            if (!query[i].equals(path.get(offset + i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The package/type/member segments of {@code moniker}, with the parameter descriptor and a leading
     * phantom {@code ~} stripped: everything before the first {@code (}, split on {@code /}, {@code #}
     * and {@code .}. Overloads (which differ only in the dropped descriptor) therefore share a name-path.
     */
    static List<String> namePath(String moniker) {
        String m = moniker.startsWith("~") ? moniker.substring(1) : moniker;
        int paren = m.indexOf('(');
        if (paren >= 0) {
            m = m.substring(0, paren);
        }
        List<String> segments = new ArrayList<>();
        for (String seg : m.split("[/#.]")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        return segments;
    }
}
