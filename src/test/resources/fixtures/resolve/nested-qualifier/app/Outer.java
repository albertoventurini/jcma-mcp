package app;

/**
 * The nested-qualifier target: an outer type with a nested type {@code Inner} whose <em>only</em> use
 * elsewhere is as the qualifier of a static-member access ({@code Outer.Inner.staticCall()}). The sole
 * occurrence of the name {@code Inner} is the {@code Outer.Inner} qualifier — a {@code FieldAccessExpr}
 * (a <em>qualified</em> ambiguous name, JLS §6.5.2) standing for the nested type. JavaSymbolSolver's
 * value resolution rejects it, so before the qualified-name-as-type fix it lands in the unconfirmed
 * tail rather than being confirmed.
 */
public class Outer {

    public static class Inner {
        static void staticCall() {
            // body irrelevant
        }
    }
}
