package jcma.engine;

/**
 * One resolved (or safe-degraded) type dependency of a declared type, as produced by the whole-file
 * {@link JavaParserEngine#resolveFileDependencies} pass behind the QA {@code resolve-file} surface.
 *
 * <ul>
 *   <li>{@code relation} — {@code "SUPERTYPE"} (a direct {@code extends}/{@code implements} target)
 *       or {@code "TYPEREF"} (a type/annotation mention in the type's body).</li>
 *   <li>{@code ownerFqn} — the canonical FQN of the <em>immediately enclosing</em> declared type the
 *       mention belongs to (a nested type's reference attributes to the nested type, not the outer).</li>
 *   <li>{@code target} — the erased FQN of the resolved dependency when {@code resolved}; otherwise the
 *       unresolved use-site's simple name (a safe-degraded miss, surfaced for spot-checking, never a
 *       guessed FQN).</li>
 *   <li>{@code resolved} — whether the use-site bound to a declaration. Supertypes are only emitted
 *       when resolved.</li>
 * </ul>
 */
public record TypeDependency(String relation, String ownerFqn, String target, boolean resolved) {

    /** The {@code SUPERTYPE} relation label. */
    public static final String SUPERTYPE = "SUPERTYPE";

    /** The {@code TYPEREF} relation label. */
    public static final String TYPEREF = "TYPEREF";
}
