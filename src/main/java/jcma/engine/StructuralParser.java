package jcma.engine;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier-1 structural extractor (PRD §5.1 "Tier-1 structural") — parses a {@code .java} file with a
 * bare JavaParser (<b>no SymbolSolver</b>: Tier-1 is lexical and cheap, the parse <em>is</em> the
 * cost) and returns its {@link FileOutline}. Lives in {@code jcma.engine} so the JavaParser AST
 * never crosses the seam (PRD §4); the indexing pipeline ({@code jcma.index.Indexer}) consumes only
 * the neutral {@link Outline} tree.
 *
 * <p>Distinct from {@link JavaParserEngine}, which builds the (expensive) resolving type-solver
 * stack for Tier-2; this one stays solver-free so a cold full-repo scan parallelises cheaply.
 */
public final class StructuralParser {

    // RAW (not JAVA_25): we want the grammar (which accepts all syntax) but NOT the post-parse
    // language-level validators. Those validators read node properties via JavaParser's reflective
    // meta-model (PropertyMetaModel.getValue → getDeclaredFields by name), which under native-image
    // throws NoSuchFieldError for any AST field the build trace didn't happen to register (e.g.
    // UnionType.elements on a multi-catch). We surface no diagnostics (PRD §4), so validation is
    // pure cost — RAW removes the whole reflective path and is native-image-clean.
    private final ParserConfiguration config =
            new ParserConfiguration().setLanguageLevel(LanguageLevel.RAW);

    public StructuralParser() {
        // No solver to build — parse-only.
    }

    /** Parse {@code file} (parse-only) and extract its package + top-level declarations as a tree. */
    public FileOutline outline(Path file) throws IOException {
        return collect(file).outline();
    }

    /**
     * Parse {@code file} (parse-only) and enumerate its use-sites (the seven occurrence categories) —
     * the input to the usage-name index ({@code usage-names.seg}). No resolution: each site carries
     * only its syntactic target name + range, which is all the {@code find_references} candidate prune
     * needs. Mirrors {@link #outline} so a cold-index pass gets declarations + usages from one parse.
     */
    public List<UsageSite> usages(Path file) throws IOException {
        return collect(file).usages();
    }

    /**
     * Parse {@code file} (parse-only) and extract its D2 text corpus — string literals (+ text
     * blocks), comments, and Javadoc — the input to the text index ({@code text.seg}; M3 task-01).
     * Mirrors {@link #outline}/{@link #usages} so a cold-index pass gets the third projection from
     * the same parse, at no extra parse cost.
     */
    public List<TextUnit> textUnits(Path file) throws IOException {
        return collect(file).textUnits();
    }

    /** Parse {@code file} once and expose all three projections (declarations + usages + text). */
    public Parsed collect(Path file) throws IOException {
        // A fresh JavaParser per call: JavaParser instances are not thread-safe, and indexRepo
        // parses across virtual threads. The shared config is read-only, so sharing it is fine.
        ParseResult<CompilationUnit> result = new JavaParser(config).parse(file);
        CompilationUnit cu = result.getResult().orElseThrow(
                () -> new IOException("parse failed: " + file + " — " + result.getProblems()));
        return new Parsed(cu);
    }

    /** One parsed file, offering the declaration outline and the use-site enumeration off a single parse. */
    public final class Parsed {
        private final CompilationUnit cu;

        private Parsed(CompilationUnit cu) {
            this.cu = cu;
        }

        /** The package + top-level declarations as a tree. */
        public FileOutline outline() {
            String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            List<Outline> types = new ArrayList<>();
            for (TypeDeclaration<?> td : cu.getTypes()) {
                types.add(outlineOf(td));
            }
            return new FileOutline(packageName, types);
        }

        /** The file's use-sites (the seven occurrence categories). */
        public List<UsageSite> usages() {
            List<UsageSite> out = new ArrayList<>();
            for (Occurrences.Occ o : Occurrences.scan(cu)) {
                out.add(new UsageSite(o.kind(), o.targetName(),
                        o.startLine(), o.startCol(), o.endLine(), o.endCol()));
            }
            return out;
        }

        /**
         * The file's D2 text units (string literals + text blocks → {@code STRING_LITERAL}; Javadoc
         * → {@code JAVADOC}; line/block comments → {@code COMMENT}), each with its 1-based range and
         * searchable text. A unit with no range (synthetic node) is skipped — the index keys on
         * {@code (fileId, line, col)}, so a position-less unit cannot be reported.
         */
        public List<TextUnit> textUnits() {
            List<TextUnit> out = new ArrayList<>();
            for (StringLiteralExpr s : cu.findAll(StringLiteralExpr.class)) {
                add(out, TextKind.STRING_LITERAL, s, s.getValue());
            }
            for (TextBlockLiteralExpr t : cu.findAll(TextBlockLiteralExpr.class)) {
                add(out, TextKind.STRING_LITERAL, t, t.getValue());
            }
            for (Comment c : cu.getAllComments()) {
                add(out, c.isJavadocComment() ? TextKind.JAVADOC : TextKind.COMMENT, c, c.getContent());
            }
            return out;
        }

        private static void add(List<TextUnit> out, TextKind kind, Node n, String text) {
            n.getRange().ifPresent(r -> out.add(
                    new TextUnit(kind, r.begin.line, r.begin.column, r.end.line, r.end.column, text)));
        }
    }

    /** A type declaration → an {@link Outline} with its members (and record components) as children. */
    private Outline outlineOf(TypeDeclaration<?> td) {
        List<Outline> children = new ArrayList<>();
        if (td instanceof RecordDeclaration record) {
            // Record components become FIELD symbols (the implicit canonical constructor is not emitted).
            for (Parameter component : record.getParameters()) {
                children.add(leaf(Outline.Kind.FIELD, component.getNameAsString(), null, List.of(), component));
            }
        }
        if (td instanceof EnumDeclaration enumDecl) {
            // Enum constants live in getEntries(), not getMembers().
            for (EnumConstantDeclaration entry : enumDecl.getEntries()) {
                children.add(leaf(Outline.Kind.ENUM_CONSTANT, entry.getNameAsString(), null, List.of(), entry));
            }
        }
        for (BodyDeclaration<?> member : td.getMembers()) {
            addMember(children, member);
        }
        return node(typeKind(td), td.getNameAsString(), td.getNameAsString(), List.of(), td, children);
    }

    private void addMember(List<Outline> out, BodyDeclaration<?> member) {
        if (member instanceof TypeDeclaration<?> nested) {
            out.add(outlineOf(nested));
        } else if (member instanceof MethodDeclaration method) {
            List<String> params = paramTypes(method.getParameters());
            out.add(leaf(Outline.Kind.METHOD, method.getNameAsString(),
                    localSig(method.getNameAsString(), params), params, method));
        } else if (member instanceof ConstructorDeclaration ctor) {
            List<String> params = paramTypes(ctor.getParameters());
            out.add(leaf(Outline.Kind.CONSTRUCTOR, ctor.getNameAsString(),
                    localSig("<init>", params), params, ctor));
        } else if (member instanceof FieldDeclaration field) {
            // One declaration can declare several variables (`int a, b;`) → one FIELD symbol each.
            for (VariableDeclarator var : field.getVariables()) {
                out.add(leaf(Outline.Kind.FIELD, var.getNameAsString(), null, List.of(), var));
            }
        } else if (member instanceof EnumConstantDeclaration constant) {
            out.add(leaf(Outline.Kind.ENUM_CONSTANT, constant.getNameAsString(), null, List.of(), constant));
        } else if (member instanceof AnnotationMemberDeclaration element) {
            out.add(leaf(Outline.Kind.METHOD, element.getNameAsString(),
                    localSig(element.getNameAsString(), List.of()), List.of(), element));
        }
        // Initializer blocks, compact record constructors, etc. are not Tier-1 symbols → skipped.
    }

    private static Outline.Kind typeKind(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration cls) {
            return cls.isInterface() ? Outline.Kind.INTERFACE : Outline.Kind.CLASS;
        }
        if (td instanceof EnumDeclaration) {
            return Outline.Kind.ENUM;
        }
        if (td instanceof RecordDeclaration) {
            return Outline.Kind.RECORD;
        }
        if (td instanceof AnnotationDeclaration) {
            return Outline.Kind.ANNOTATION;
        }
        return Outline.Kind.CLASS;
    }

    /** Param types as written in source, generics erased, varargs/arrays kept as {@code []} (PRD §11). */
    private static List<String> paramTypes(List<Parameter> params) {
        List<String> out = new ArrayList<>(params.size());
        for (Parameter p : params) {
            out.add(stripGenerics(p.getType().asString()) + (p.isVarArgs() ? "[]" : ""));
        }
        return out;
    }

    /** Drop balanced {@code <…>} (type arguments) from a written type string, honouring nesting. */
    private static String stripGenerics(String type) {
        StringBuilder sb = new StringBuilder(type.length());
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String localSig(String name, List<String> paramTypes) {
        return name + "(" + String.join(",", paramTypes) + ")";
    }

    private static Outline leaf(Outline.Kind kind, String name, String signature, List<String> params, Node n) {
        return node(kind, name, signature, params, n, List.of());
    }

    private static Outline node(Outline.Kind kind, String name, String signature, List<String> params,
            Node n, List<Outline> children) {
        return n.getRange()
                .map(r -> new Outline(kind, name, signature, params,
                        r.begin.line, r.begin.column, r.end.line, r.end.column, children))
                .orElseGet(() -> new Outline(kind, name, signature, params, -1, -1, -1, -1, children));
    }
}
