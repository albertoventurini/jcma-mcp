package jcma.response;

import jcma.index.Symbol;
import jcma.resolve.Definition;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.response.ToolResult.Fragment;
import jcma.response.ToolResult.RefGroupFragment;
import jcma.response.ToolResult.RefLine;
import jcma.response.ToolResult.SymbolFragment;
import jcma.response.ToolResult.TextFragment;
import jcma.response.ToolResult.UnconfirmedTailFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, stateless shaping of the M1 resolve outputs into {@link ToolResult.Fragment}s (PRD principle
 * #4: context-bearing answers — FQN/signature + {@code file:line} + snippet). No truncation here; the
 * token-bounding half is {@link BudgetPolicy}.
 *
 * <p>Display degrades via {@link #display}, mirroring {@code EdgeResolver.display}: a symbol shows its
 * precomputed {@code signature}, else the moniker with a leading {@code ~} phantom-marker stripped.
 * Monikers are SCIP-style and build-only — never parsed back to FQN/kind. {@code kind} is carried only
 * on the {@link #symbol} path ({@code SymbolKind} lives on {@link Symbol}, not the resolve shapes).
 */
public final class Shaping {

    /** Uniform location for a target with no project source (a dependency jar / the JDK). */
    static final String EXTERNAL = "external (jar/JDK)";

    private Shaping() {}

    /** Signature if non-null, else the fallback moniker with a leading {@code ~} phantom-marker stripped. */
    static String display(String signature, String fallbackMoniker) {
        if (signature != null) {
            return signature;
        }
        return fallbackMoniker.startsWith("~") ? fallbackMoniker.substring(1) : fallbackMoniker;
    }

    /**
     * A {@code Symbol} (the only kind-bearing path): FQN/signature + {@code kind} + {@code file:line} +
     * signature. {@code Symbol} carries a {@code fileId}, not a {@link Path}, so the caller resolves it
     * (via the FileTable) and passes it in; a phantom/external symbol has none → {@code null} → the
     * uniform external form. No agent ever sees a raw {@code fileId}.
     */
    public static SymbolFragment symbol(Symbol s, Path file) {
        String location = (file == null || s.isPhantom() || s.range().isNone())
                ? EXTERNAL
                : file + ":" + s.range().startLine();
        return new SymbolFragment(display(s.signature(), s.moniker()), s.kind().name(), location, null);
    }

    /**
     * A {@code find_definition} answer: signature + {@code file:line} + declaration snippet. For an
     * external target ({@code file == null} / {@code line == -1}) the location is the uniform external
     * form and the snippet is omitted; the signature is still rendered.
     */
    public static SymbolFragment definition(Definition d) {
        if (d.file() == null || d.line() == -1) {
            return new SymbolFragment(display(d.signature(), d.moniker()), null, EXTERNAL, null);
        }
        String snippet = d.snippet() == null || d.snippet().isEmpty() ? null : d.snippet();
        return new SymbolFragment(display(d.signature(), d.moniker()), null, d.file() + ":" + d.line(), snippet);
    }

    /** One confirmed reference group: "called from {@code X}" + a {@code file:line  snippet} per site. */
    public static RefGroupFragment refGroup(ReferenceGroup g) {
        List<RefLine> lines = new ArrayList<>(g.refs().size());
        for (Ref r : g.refs()) {
            lines.add(new RefLine(r.file() + ":" + r.range().startLine(), r.snippet()));
        }
        return new RefGroupFragment(display(g.enclosingSignature(), g.enclosingMoniker()), lines);
    }

    /**
     * A {@code find_references} answer: a {@code Total refs: N across M files} header (the sacred count,
     * always first), one {@link RefGroupFragment} per group, then an {@link UnconfirmedTailFragment}
     * iff there is a non-exhaustive tail.
     */
    public static List<Fragment> references(References refs) {
        int total = 0;
        Set<String> files = new LinkedHashSet<>();
        for (ReferenceGroup g : refs.groups()) {
            total += g.refs().size();
            for (Ref r : g.refs()) {
                files.add(r.file().toString());
            }
        }

        List<Fragment> out = new ArrayList<>();
        out.add(new TextFragment(refsHeader(total, files.size())));
        for (ReferenceGroup g : refs.groups()) {
            out.add(refGroup(g));
        }
        if (refs.hasUnconfirmedTail()) {
            List<String> lines = new ArrayList<>(refs.unconfirmed().size());
            for (UnconfirmedRef u : refs.unconfirmed()) {
                lines.add(u.file() + ":" + u.range().startLine() + "  " + u.snippet() + "  [" + u.cause() + "]");
            }
            out.add(new UnconfirmedTailFragment(refs.unconfirmed().size(), lines));
        }
        return out;
    }

    /** The always-present total header: {@code Total refs: N across M files}. */
    static String refsHeader(int totalRefs, int fileCount) {
        return "Total refs: " + totalRefs + " across " + fileCount + (fileCount == 1 ? " file" : " files");
    }
}
