package jcma.index;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The swappable match policy for the {@code grep_java} search seams (M3 task-03; D4). It carries the
 * raw {@code pattern} plus the two grep-style toggles — {@code fixedString} (grep {@code -F}) and
 * {@code caseSensitive} — and answers every "does this string match?" question, so the index seams
 * ({@link TextIndex}, {@link TrigramIndex}, {@link LsmStore}) ask <em>it</em> instead of hardcoding
 * {@code contains}/{@code indexOf}.
 *
 * <p><b>Default semantics (the locked D-a..D-d decisions).</b> {@code pattern} is a
 * {@link java.util.regex} regular expression by default (D-a); {@code fixedString} opts into literal
 * matching. Matching is case-sensitive by default (D-c). The compiled pattern carries
 * {@link Pattern#MULTILINE} (so {@code ^}/{@code $} anchor per physical line) but not {@code DOTALL}
 * (so {@code .} does not cross {@code \n}) — D-d.
 *
 * <p><b>The literal fast path is an invisible optimization.</b> A metachar-free, case-sensitive
 * pattern matches the <em>identical</em> set under {@code String.contains} and {@code Matcher.find},
 * so {@link #fastPathEligible()} keeps today's trigram/{@code indexOf} path (zero regression on the
 * common literal query). The regex path is taken only when the pattern actually carries a
 * metacharacter or matching is case-insensitive. {@code java.util.regex} only — native-image safe,
 * no new reflective dependency.
 */
public final class SearchSpec {

    // The regex metacharacters whose presence forces the regex path (a small, conservative set).
    private static final String META = ".\\[](){}*+?^$|";

    private final String pattern;
    private final boolean fixedString;
    private final boolean caseSensitive;
    private final boolean literal;
    private Pattern compiled; // lazily compiled once per query (never per unit/name)

    private SearchSpec(String pattern, boolean fixedString, boolean caseSensitive) {
        this.pattern = pattern == null ? "" : pattern;
        this.fixedString = fixedString;
        this.caseSensitive = caseSensitive;
        this.literal = fixedString || !hasRegexMeta(this.pattern);
    }

    /** A literal, case-sensitive spec — the {@code String}-overload delegate (today's behavior). */
    public static SearchSpec literal(String pattern) {
        return new SearchSpec(pattern, true, true);
    }

    /** A spec from the tool inputs: {@code pattern} as regex unless {@code fixedString}, with case mode. */
    public static SearchSpec of(String pattern, boolean fixedString, boolean caseSensitive) {
        return new SearchSpec(pattern, fixedString, caseSensitive);
    }

    /** The raw pattern string (the literal substring on the fast path; the ranking key elsewhere). */
    public String pattern() {
        return pattern;
    }

    /** True for a blank pattern (every seam returns empty rather than scanning). */
    public boolean isEmpty() {
        return pattern.isEmpty();
    }

    /** True when the pattern carries no regex semantics — {@code fixedString} or metachar-free. */
    public boolean isLiteral() {
        return literal;
    }

    /**
     * True when the literal fast path is exact: literal <em>and</em> case-sensitive. Such a query's
     * {@code String.contains}/trigram-{@code indexOf} result equals its {@code Matcher.find} result, so
     * the seams keep the accelerated literal path with no behavioral change.
     */
    public boolean fastPathEligible() {
        return literal && caseSensitive;
    }

    /** The substring used by the trigram pre-filter + {@code contains} on the fast path. */
    public String literal() {
        return pattern;
    }

    /**
     * The compiled pattern for the regex path, built once per query with {@link Pattern#MULTILINE}
     * (plus {@link Pattern#CASE_INSENSITIVE} when case-insensitive, {@link Pattern#LITERAL} when
     * {@code fixedString} so a literal-but-insensitive query treats metacharacters as text). Throws
     * {@link PatternSyntaxException} for a malformed pattern.
     */
    public Pattern compiled() {
        Pattern p = compiled;
        if (p == null) {
            int flags = Pattern.MULTILINE;
            if (!caseSensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            if (fixedString) {
                flags |= Pattern.LITERAL;
            }
            p = Pattern.compile(pattern, flags);
            compiled = p;
        }
        return p;
    }

    /** True if {@code s} matches: {@code contains} on the fast path, {@code Matcher.find} otherwise. */
    public boolean matches(String s) {
        return fastPathEligible() ? s.contains(literal()) : compiled().matcher(s).find();
    }

    /** A {@link Matcher} of the compiled pattern over {@code s} (regex-path callers only). */
    public Matcher matcher(String s) {
        return compiled().matcher(s);
    }

    /**
     * Force compilation when the regex path will be used, surfacing a {@link PatternSyntaxException}
     * eagerly (so the tool can report a clean error before any scan). A no-op on the fast path, where
     * there is no regex to be malformed.
     */
    public void validate() {
        if (!fastPathEligible()) {
            compiled();
        }
    }

    /** True if {@code s} contains any of the {@link #META} regex metacharacters. */
    static boolean hasRegexMeta(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (META.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
