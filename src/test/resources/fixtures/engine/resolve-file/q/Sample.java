package q;

/**
 * The whole-file resolve-file target: one top-level type (implements a supertype, references Helper
 * three ways) plus a nested type whose own Helper reference must attribute to the nested type, not
 * the outer one. All references are intra-fixture, so resolution needs no classpath.
 */
public class Sample implements Base {

    Helper field;

    Helper make() {
        return new Helper();
    }

    public static class Inner {
        Helper innerField;
    }
}
