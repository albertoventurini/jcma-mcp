package app;

/**
 * Holds the lone reference to the nested type {@code Outer.Inner}: the qualifier of the static call
 * {@code Outer.Inner.staticCall()}. Syntactically {@code Outer.Inner} here is a {@code FieldAccessExpr}
 * in value position; semantically it is a reference to the nested type. It must be a confirmed
 * reference to {@code app/Outer#Inner#}, enclosed by {@code Caller.go()} — not an unconfirmed
 * {@code MISSING_CLASSPATH} candidate.
 */
public class Caller {

    void go() {
        Outer.Inner.staticCall();
    }
}
