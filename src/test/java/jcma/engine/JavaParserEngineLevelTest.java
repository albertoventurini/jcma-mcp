package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import org.junit.jupiter.api.Test;

/**
 * Guard test pinning {@link JavaParserEngine#resolvingConfig}'s index-3 surgery behaviorally: the
 * resolving parser parses at a real language level (so {@code yield}/patterns parse and the resolver
 * attaches to the whole CU, even inside the {@code yield} block arm RAW discards), keeps the
 * {@code var}→{@code VarType} post-processor, and strips the language-level validator (which walks the
 * reflective meta-model — the native-image {@code NoSuchFieldError} hazard). If a javaparser bump
 * shifts the constructor's processor layout away from index 3, one of these fails loudly rather than
 * silently re-enabling the validator or losing var inference.
 */
class JavaParserEngineLevelTest {

    private static JavaParser parser() {
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        return new JavaParser(JavaParserEngine.resolvingConfig(LanguageLevel.JAVA_17, solver));
    }

    /** (a) Level on: a {@code yield}'d block arm parses cleanly and a symbol inside it resolves. */
    @Test
    void yieldedSymbolResolves() {
        ParseResult<CompilationUnit> r = parser().parse("""
                import java.util.List;
                class C {
                    Object pick(int n) {
                        return switch (n) {
                            default -> {
                                List<String> made = List.of();
                                yield made;
                            }
                        };
                    }
                }
                """);
        assertTrue(r.isSuccessful(), () -> "yield must parse cleanly at JAVA_17: " + r.getProblems());
        CompilationUnit cu = r.getResult().orElseThrow();
        // `List` is referenced ONLY inside the yield block arm — resolving it proves the arm survived
        // parsing AND the symbol resolver attached to the whole compilation unit.
        ClassOrInterfaceType list = cu.findAll(ClassOrInterfaceType.class).stream()
                .filter(t -> t.getNameAsString().equals("List"))
                .findFirst().orElseThrow();
        assertEquals("java.util.List", list.resolve().asReferenceType().getQualifiedName());
    }

    /** (b) var post-processor kept: a {@code var} local's inferred type resolves a method call on it. */
    @Test
    void varInferredTypeResolves() {
        ParseResult<CompilationUnit> r = parser().parse("""
                class C {
                    int len() {
                        var s = "hello";
                        return s.length();
                    }
                }
                """);
        assertTrue(r.isSuccessful(), () -> r.getProblems().toString());
        MethodCallExpr call = r.getResult().orElseThrow().findAll(MethodCallExpr.class).stream()
                .filter(m -> m.getNameAsString().equals("length"))
                .findFirst().orElseThrow();
        assertEquals("java.lang.String.length()", call.resolve().getQualifiedSignature());
    }

    /**
     * (c) Validator off: a class extending two classes is grammar-legal in JavaParser's permissive
     * grammar (two {@code extendedTypes}) but the language-level validator flags it. With the validator
     * stripped it parses into a CU with <b>no</b> validation problem — which is also what lets the
     * resolver attach (JavaParser gates resolver injection on a problem-free parse).
     */
    @Test
    void validatorFlaggedConstructProducesNoProblem() {
        ParseResult<CompilationUnit> r = parser().parse(
                "class C extends java.util.ArrayList, java.util.LinkedList {}\n");
        assertTrue(r.getResult().isPresent(), "the construct still parses into a CU");
        assertTrue(r.getProblems().isEmpty(),
                () -> "validator stripped → no validation problem, but got: " + r.getProblems());
    }
}
