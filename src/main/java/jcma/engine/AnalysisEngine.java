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
     * Resolve the <b>value-name</b> use-sites in {@code unit} whose simple name equals {@code
     * simpleName} — the per-(file,name) Tier-2 unit (Option A name-scoping). Value-name resolution
     * (calls, name/field reads, method refs, instantiations) is the cubic-cost class (JavaParser
     * #4975's {@code StatementContext} block scan), so scoping to the queried name before {@code
     * .resolve()} is what avoids resolving thousands of names we never asked about. Each resolution is
     * guarded (incl. {@code StackOverflowError}); a use-site that resolves carries a {@link
     * ResolvedTarget}, one that does not a safe-degrading {@link ResolveFailure}. The list feeds the
     * confirmed edges + the unconfirmed tail <em>for that name</em>.
     */
    List<ResolvedOccurrence> resolveOccurrences(ParsedUnit unit, String simpleName);

    /**
     * Resolve the <b>type/annotation</b> use-sites in {@code unit} whose simple name equals {@code
     * simpleName} (B1 name-scoping — symmetric to {@link #resolveOccurrences}). These resolve through
     * the type solver, <em>not</em> the cubic {@code StatementContext} walk, so each is cheap; scoping
     * to the queried name keeps {@code find_references(Type)} from resolving every other type-ref in the
     * file just to surface the few that match. The cache unit is therefore {@code (file, name)}, not the
     * whole file. These are the per-file dependency layer the node-diff cascade walks (task-11c): a
     * referrer's {@code REFERENCES} edge to a type is how a change to that type finds its referrers.
     * Each resolution is guarded.
     */
    List<ResolvedOccurrence> resolveTypeReferences(ParsedUnit unit, String simpleName);

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

    /**
     * Drop any cached parse/type-solver state for source files so the next resolution reflects the
     * current bytes on disk (M1 task-11c freshness). MCP gives no change channel, so when a file is
     * re-indexed mid-session the engine must be told its cross-file view is stale — otherwise it would
     * resolve against a cached AST of the old source. The default is a no-op (an engine with no cache);
     * {@link JavaParserEngine} rebuilds its source type-solvers (reusing the stable jar/JDK solvers).
     */
    default void refresh() {
        // no cached state by default
    }
}
