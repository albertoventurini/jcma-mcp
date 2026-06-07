package jcma.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The §4 analysis seam: parse a file and resolve symbols at a position, speaking <em>only</em> in
 * jcma-owned types ({@link ParsedUnit}, {@link Position}, {@link ResolvedRef}, {@link ResolvedType}).
 * The default engine is {@link JavaParserEngine} (JavaParser + JavaSymbolSolver, the M0 GO verdict),
 * but the javac-hybrid fallback (PRD §4) must satisfy this same contract — hence no JavaParser type
 * appears here.
 *
 * <p><b>Safe-degrade contract (M0 principle):</b> a resolve that cannot be completed returns
 * {@link Optional#empty()} — never a guessed/silent-wrong answer. Implementations guard every
 * resolution call (including {@code StackOverflowError}, per {@code SpikeA.attempt()}).
 */
public interface AnalysisEngine {

    /**
     * Parse {@code file} into an opaque handle. Throws if the file cannot be read/parsed (a parse
     * failure is not a "resolved nothing" — it is an input error the caller must see).
     */
    ParsedUnit parse(Path file) throws java.io.IOException;

    /** Resolve the method/constructor call enclosing {@code pos}; empty if none or unresolvable. */
    Optional<ResolvedRef> resolveMethodCall(ParsedUnit unit, Position pos);

    /** Resolve the type reference enclosing {@code pos}; empty if none or unresolvable. */
    Optional<ResolvedType> resolveType(ParsedUnit unit, Position pos);

    /**
     * Resolve <em>every</em> use-site in {@code unit} (the seven occurrence categories) — the Tier-2
     * lazy-resolve unit (PRD §5.1). Each resolution is guarded (incl. {@code StackOverflowError});
     * a use-site that resolves carries a {@link ResolvedTarget}, one that does not carries a
     * safe-degrading {@link ResolveFailure} — never a guessed answer. The list is the input to the
     * resolved-edge cache + the unconfirmed tail.
     */
    List<ResolvedOccurrence> resolveOccurrences(ParsedUnit unit);

    /**
     * Resolve the <em>structural hierarchy</em> of every declaration in {@code unit} (task-11a): for
     * each type declaration its {@code extends}/{@code implements} targets, and for each method the
     * <b>direct</b> method it overrides or interface method it implements. Each resolution is guarded
     * (incl. {@code StackOverflowError}); an unresolvable supertype safe-degrades to nothing rather
     * than a guessed edge. The list is the input to the {@code EXTENDS}/{@code IMPLEMENTS}/{@code
     * OVERRIDES} graph edges; external (jar/JDK) supertypes carry a {@code null} {@code declFile}
     * (→ phantom node).
     */
    List<ResolvedHierarchy> resolveHierarchy(ParsedUnit unit);
}
