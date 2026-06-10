package jcma.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * M2 task-04 — the suffix-anchored, segment-exact qualified-name matcher ({@link
 * QualifiedName#matches}) in isolation. The typed name must be a <b>contiguous tail</b> of the
 * moniker's name-path (descriptor stripped), so a partial qualifier narrows the candidate set without
 * the {@code String.contains} false-matches, genuine ambiguity is surfaced (and narrowable), a full
 * FQN is the maximal suffix, and overloads (same name-path) are deliberately not separated.
 */
class QualifiedNameMatchTest {

    // Two legitimately-distinct Circle.area types, the interface, an unrelated "Circle"-containing
    // type, and an area overload — all the cases that stress the matcher.
    private static final String CIRCLE = "com/example/shapes/Circle#area().";
    private static final String SHAPE = "com/example/shapes/Shape#area().";
    private static final String MYSHAPES_CIRCLE = "com/example/shapes/myshapes/Circle#area().";
    private static final String AWESOME = "com/example/shapes/Circles/MyAwesomeCircle#area().";
    private static final String CIRCLE_OVERLOAD = "com/example/shapes/Circle#area(int).";

    private static boolean matches(String moniker, String typed) {
        return QualifiedName.matches(moniker, typed);
    }

    @Test
    void bareSimpleNameMatchesEveryDeclarationOfThatName() {
        assertTrue(matches(CIRCLE, "area"));
        assertTrue(matches(SHAPE, "area"));
        assertTrue(matches(MYSHAPES_CIRCLE, "area"));
    }

    @Test
    void typeQualifierAnchorsToTheEnclosingType() {
        assertTrue(matches(CIRCLE, "Circle.area"), "Circle is the enclosing type");
        assertFalse(matches(SHAPE, "Circle.area"), "Shape is a different enclosing type");
    }

    @Test
    void substringFalsePositiveIsRejected() {
        // Example 1: a type whose name merely *contains* "Circle" must NOT match.
        assertFalse(matches(AWESOME, "Circle.area"),
                "MyAwesomeCircle is not the type Circle — segment-exact, not substring");
    }

    @Test
    void genuineAmbiguityIsSurfacedThenNarrowedByMoreQualifier() {
        // Example 2: two real Circle types → a bare "Circle.area" legitimately matches both.
        assertTrue(matches(CIRCLE, "Circle.area"));
        assertTrue(matches(MYSHAPES_CIRCLE, "Circle.area"));
        // Adding the package segment narrows to exactly one.
        assertTrue(matches(CIRCLE, "shapes.Circle.area"));
        assertFalse(matches(MYSHAPES_CIRCLE, "shapes.Circle.area"),
                "myshapes.Circle's tail is [myshapes, Circle, area], not [shapes, Circle, area]");
    }

    @Test
    void fullyQualifiedNameIsTheMaximalSuffixAndIsUnique() {
        assertTrue(matches(CIRCLE, "com.example.shapes.Circle.area"));
        assertFalse(matches(MYSHAPES_CIRCLE, "com.example.shapes.Circle.area"),
                "the full FQN selects exactly the one matching path");
    }

    @Test
    void overloadsShareANamePathAndAreNotSeparated() {
        // An FQN omits parameter types, so both overloads match — surfaced as multi-match, not guessed.
        assertTrue(matches(CIRCLE, "Circle.area"));
        assertTrue(matches(CIRCLE_OVERLOAD, "Circle.area"));
        assertTrue(matches(CIRCLE, "com.example.shapes.Circle.area"));
        assertTrue(matches(CIRCLE_OVERLOAD, "com.example.shapes.Circle.area"));
    }

    @Test
    void suffixMustBeContiguousSoTheTypeSegmentCannotBeSkipped() {
        assertFalse(matches(CIRCLE, "shapes.area"),
                "skipping the Circle type segment is not a valid suffix of the name-path");
    }

    @Test
    void aTooLongQueryCannotMatchAShorterPath() {
        assertFalse(matches(CIRCLE, "org.example.shapes.Circle.area"),
                "a query longer than the path (or with a wrong leading segment) does not match");
    }
}
