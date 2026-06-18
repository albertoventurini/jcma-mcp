package q;

/**
 * Mirrors the JavaParser record-container nested-resolution bug at DEPTH: a type path that descends
 * through several records — interface → record → record → record → record.
 * {@code JavaParserTypeAdapter.solveType} recurses into class/interface/enum/annotation containers but
 * not records, throwing {@code UnsupportedOperationException} on every descend-through-record hop.
 *
 * <p>{@code Nest} and {@code Nest.Mid} resolve via the stock path ({@code Mid} is a direct member);
 * every deeper hop ({@code .Deep}, {@code .Deeper}, {@code .Deepest}) trips the bug, so resolving the
 * deepest requires the scope itself to be recovered by the fallback — i.e. it pins the <em>recursion</em>,
 * not just a one-level patch.
 */
public sealed interface Nest {

    record Mid(String label) implements Nest {

        public record Deep(int code) {

            public record Deeper(int code) {

                public record Deepest(int code) {}
            }
        }
    }
}
