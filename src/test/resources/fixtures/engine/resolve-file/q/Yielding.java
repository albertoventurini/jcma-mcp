package q;

import java.util.List;

/**
 * Reproduces the whole-file resolution cliff (docs/whole-file-resolution-degradation.md): a switch
 * <em>expression</em> with a {@code yield} statement. Parsing at {@link
 * com.github.javaparser.ParserConfiguration.LanguageLevel#RAW} demotes the {@code yield} contextual
 * keyword to an identifier, so the compilation unit carries a parse <em>problem</em> — JavaParser then
 * skips its {@code SymbolResolver} injection (gated on {@code ParseResult.ifSuccessful()}) and every
 * type mention safe-degrades to unconfirmed, even the well-formed {@code Helper} / {@code java.util.List}.
 * RAW's error recovery additionally <em>discards the whole {@code yield} block arm</em>, so the
 * {@code Yielded} mention inside it never even enters the AST.
 *
 * <p>Parsing at the project's real language level (with the validator stripped) fixes both: the CU is
 * problem-free so the resolver attaches, and the {@code yield} arm survives so the {@code Yielded}
 * mention <em>inside</em> it resolves too.
 */
public class Yielding {

    List<Helper> pick(int n) {
        return switch (n) {
            case 0 -> List.of();
            default -> {
                Yielded marker = new Yielded(); // referenced ONLY inside the yield block arm
                Helper h = marker == null ? null : new Helper();
                yield List.of(h);
            }
        };
    }
}
