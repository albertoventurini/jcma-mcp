package jcma.response;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The outcome of one {@code ToolHandler.call} (PRD §6) — the real M2 response model. A tool builds a
 * list of typed {@link Fragment}s (FQN/signature + {@code file:line} + snippet, so the answer is
 * <em>context-bearing</em>: the agent rarely needs a follow-up read — PRD principle #4), and the
 * transport renders them to a <b>single {@code {type:"text"}} block</b> via {@link #render()} plus the
 * MCP {@code isError} flag. A failing tool is <em>not</em> a transport error — it returns a successful
 * JSON-RPC {@code result} whose {@code isError} is {@code true}, so the agent sees the failure as a tool
 * outcome, not a protocol fault.
 *
 * <p>Token-bounding (the other half of principle #4) is applied by {@link BudgetPolicy#apply}, which a
 * tool routes its result through before returning; the transport stays unaware of budgeting. The
 * budget degrades fidelity ({@link RefGroupFragment} carries a {@link Fidelity}; the per-file rollup is
 * a {@link FileRollupFragment}) but never the completeness of the reference set — counts are sacred.
 */
public record ToolResult(List<Fragment> fragments, boolean isError) {

    public ToolResult {
        fragments = List.copyOf(fragments);
    }

    /** A successful result carrying a single plain-text {@code text} (health, "no references", notes). */
    public static ToolResult text(String text) {
        return new ToolResult(List.of(new TextFragment(text)), false);
    }

    /** A tool-level failure carrying an error {@code message} ({@code isError:true}). */
    public static ToolResult error(String message) {
        return new ToolResult(List.of(new TextFragment(message)), true);
    }

    /** A successful result built from shaped {@code fragments} (the §6 query tools, tasks 4–7). */
    public static ToolResult of(List<Fragment> fragments) {
        return new ToolResult(fragments, false);
    }

    /** Deterministic single-string rendering of every fragment — what the transport writes on the wire. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Fragment f : fragments) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(f.render());
        }
        return sb.toString();
    }

    // ---- fragments ------------------------------------------------------------------------------

    /** Granularity at which a {@link RefGroupFragment} renders its reference sites under the budget. */
    public enum Fidelity {
        /** Enclosing signature + every {@code file:line} + the source snippet. */
        FULL,
        /** Enclosing signature + every {@code file:line}, snippets dropped (re-fetchable via the line). */
        LOCATIONS
    }

    /** One rendered piece of a result; the sealed set the writer and budget know how to handle. */
    public sealed interface Fragment
            permits TextFragment, SymbolFragment, LineMatchFragment, RefGroupFragment, FileRollupFragment,
                    UnconfirmedTailFragment {
        String render();
    }

    /** Plain text — the {@code Total refs} header, budget notes, "no references", error messages. */
    public record TextFragment(String text) implements Fragment {
        @Override public String render() {
            return text;
        }
    }

    /**
     * One {@code grep_java} <b>text-tier</b> match (M3 task-02): a {@code file:line:col} location, a
     * bracketed {@code kind} label ({@code string-literal} / {@code comment} / {@code javadoc} — so the
     * hit is self-describing), and the matching line's {@code snippet}. Rendered grep-style and visually
     * distinct from {@link SymbolFragment}: {@code file:line:col  [kind]  snippet}. Kept separate from
     * the generic {@link TextFragment} (header/note/marker carrier) — a different role, same package.
     */
    public record LineMatchFragment(String location, String kind, String snippet) implements Fragment {
        @Override public String render() {
            StringBuilder sb = new StringBuilder(location).append("  [").append(kind).append(']');
            if (snippet != null && !snippet.isEmpty()) {
                sb.append("  ").append(snippet);
            }
            return sb.toString();
        }
    }

    /**
     * A definition / symbol: FQN or human-readable {@code signature}, optional {@code kind} (present
     * only on the {@code Symbol} path — the resolve shapes carry none), a {@code location}
     * ({@code file:line} or the uniform {@code external (jar/JDK)}), and an optional {@code detail}
     * line (the declaration snippet, omitted for an external target).
     */
    public record SymbolFragment(String display, String kind, String location, String detail)
            implements Fragment {
        @Override public String render() {
            StringBuilder sb = new StringBuilder(display);
            if (kind != null) {
                sb.append("  ").append(kind);
            }
            sb.append("  ").append(location);
            if (detail != null && !detail.isEmpty()) {
                sb.append("\n    ").append(detail);
            }
            return sb.toString();
        }
    }

    /** One reference site: its {@code file:line} {@code location} and the source {@code snippet}. */
    public record RefLine(String location, String snippet) {}

    /**
     * Confirmed references sharing an enclosing declaration ("called from {@code X}" — PRD §6),
     * rendered at the given {@link Fidelity}. {@link #at} produces a copy at a lower fidelity (the
     * budget's snippet-drop); the reference set itself is never reduced here.
     */
    public record RefGroupFragment(String enclosingSignature, List<RefLine> refs, Fidelity fidelity)
            implements Fragment {

        public RefGroupFragment {
            refs = List.copyOf(refs);
        }

        /** A full-fidelity group (what {@link Shaping#refGroup} builds). */
        public RefGroupFragment(String enclosingSignature, List<RefLine> refs) {
            this(enclosingSignature, refs, Fidelity.FULL);
        }

        public int count() {
            return refs.size();
        }

        /** This group re-rendered at {@code fidelity} (same references, fewer columns). */
        public RefGroupFragment at(Fidelity fidelity) {
            return new RefGroupFragment(enclosingSignature, refs, fidelity);
        }

        @Override public String render() {
            StringBuilder sb = new StringBuilder("called from  ")
                    .append(enclosingSignature)
                    .append("  (").append(count()).append(count() == 1 ? " ref)" : " refs)");
            for (RefLine r : refs) {
                sb.append("\n    ").append(r.location());
                if (fidelity == Fidelity.FULL && r.snippet() != null && !r.snippet().isEmpty()) {
                    sb.append("  ").append(r.snippet());
                }
            }
            return sb.toString();
        }
    }

    /** A reference count per file ({@code path: N refs}) — one line of a {@link FileRollupFragment}. */
    public record FileCount(String file, int count) {}

    /**
     * The lossy-but-navigable degrade: confirmed references rolled up to a per-file count (the budget's
     * second rung, after snippet-drop). The agent loses exact lines but keeps the <em>file</em> — what
     * it opens — and the total stays exhaustive in the {@code Total refs} header.
     */
    public record FileRollupFragment(List<FileCount> files) implements Fragment {

        public FileRollupFragment {
            files = List.copyOf(files);
        }

        @Override public String render() {
            StringBuilder sb = new StringBuilder();
            for (FileCount fc : files) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("  ").append(fc.file()).append(": ").append(fc.count())
                        .append(fc.count() == 1 ? " ref" : " refs");
            }
            return sb.toString();
        }
    }

    /**
     * The mandatory <b>unconfirmed tail</b> (M0 Spike A): candidates that matched the target's name but
     * failed to resolve, so the set is explicitly <em>not</em> exhaustive. The header is always kept,
     * never dropped by the budget.
     */
    public record UnconfirmedTailFragment(int count, List<String> lines) implements Fragment {

        public UnconfirmedTailFragment {
            lines = List.copyOf(lines);
        }

        @Override public String render() {
            StringBuilder sb = new StringBuilder()
                    .append(count).append(count == 1 ? " unconfirmed candidate" : " unconfirmed candidates")
                    .append(" — result is NOT exhaustive (matched the name but could not be resolved):");
            for (String l : lines) {
                sb.append("\n    ").append(l);
            }
            return sb.toString();
        }
    }

    // ---- shared helpers -------------------------------------------------------------------------

    /** The file part of a {@code file:line} location string (everything before the last {@code :}). */
    static String fileOf(String location) {
        int c = location.lastIndexOf(':');
        return c >= 0 ? location.substring(0, c) : location;
    }

    /** Distinct files across a list of {@link RefLine}s, in first-seen order. */
    static Set<String> filesOf(List<RefLine> refs) {
        Set<String> files = new LinkedHashSet<>();
        for (RefLine r : refs) {
            files.add(fileOf(r.location()));
        }
        return files;
    }
}
