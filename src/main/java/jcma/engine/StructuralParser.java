package jcma.engine;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Range;
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
import com.github.javaparser.ast.stmt.BlockStmt;

import java.io.IOException;
import java.nio.file.Files;
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

    /** Parse {@code file} once and expose all four projections (declarations + usages + text + skim). */
    public Parsed collect(Path file) throws IOException {
        // Read the source ourselves and parse the string (one read, identical ranges) so the Parsed can
        // slice verbatim spans for skim() — JavaParser's parse(file) would read it but not retain the text.
        String source = Files.readString(file);
        // A fresh JavaParser per call: JavaParser instances are not thread-safe, and indexRepo
        // parses across virtual threads. The shared config is read-only, so sharing it is fine.
        ParseResult<CompilationUnit> result = new JavaParser(config).parse(source);
        CompilationUnit cu = result.getResult().orElseThrow(
                () -> new IOException("parse failed: " + file + " — " + result.getProblems()));
        return new Parsed(cu, source);
    }

    /** One parsed file, offering the declaration outline and the use-site enumeration off a single parse. */
    public final class Parsed {
        private final CompilationUnit cu;
        private final String source;
        /** {@code lineStarts[i]} = char offset where 1-based line {@code i+1} begins (for span slicing). */
        private final int[] lineStarts;

        private Parsed(CompilationUnit cu, String source) {
            this.cu = cu;
            this.source = source;
            this.lineStarts = computeLineStarts(source);
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

        /**
         * The file shaped for {@code skim_java}: package + imports + top-level types, each declaration
         * carrying its <b>verbatim source spans</b> (doc / header / body) sliced from this parse's source
         * text. The fourth projection off the single parse; no Java is re-synthesized — every span is an
         * exact substring, so the renderer reproduces the file faithfully (PRD §6).
         */
        public SkimUnit skim() {
            var pkg = cu.getPackageDeclaration();
            String packageName = pkg.map(p -> p.getNameAsString()).orElse("");
            int packageLine = pkg.flatMap(Node::getRange).map(r -> r.begin.line).orElse(-1);

            List<SkimImport> imports = new ArrayList<>();
            for (var imp : cu.getImports()) {
                imp.getRange().ifPresent(r -> imports.add(
                        new SkimImport(sliceInclusive(r.begin.line, r.begin.column, r.end.line, r.end.column),
                                r.begin.line)));
            }

            List<SkimDecl> types = new ArrayList<>();
            for (TypeDeclaration<?> td : cu.getTypes()) {
                types.add(skimType(td));
            }
            return new SkimUnit(packageName, packageLine, imports, types);
        }

        /** A type → a {@link SkimDecl} whose header runs to the body brace and whose members are children. */
        private SkimDecl skimType(TypeDeclaration<?> td) {
            var range = td.getRange().orElseThrow();
            var brace = bodyBrace(td);
            // Header = decl start (incl. modifiers/annotations/generics/components/extends) up to the brace.
            String header = rstrip(sliceExclusiveEnd(range.begin.line, brace.begin.line, brace.begin.column));
            DocSpan doc = docOf(td);

            List<SkimDecl> children = new ArrayList<>();
            if (td instanceof EnumDeclaration enumDecl) {
                for (EnumConstantDeclaration entry : enumDecl.getEntries()) {
                    children.add(leafDecl(Outline.Kind.ENUM_CONSTANT, entry.getNameAsString(), entry));
                }
            }
            for (BodyDeclaration<?> member : td.getMembers()) {
                addSkimMember(children, member);
            }
            return new SkimDecl(typeKind(td), td.getNameAsString(), doc.text(), doc.line(),
                    header, range.begin.line, range.end.line, null, false, false, children);
        }

        private void addSkimMember(List<SkimDecl> out, BodyDeclaration<?> member) {
            if (member instanceof TypeDeclaration<?> nested) {
                out.add(skimType(nested));
            } else if (member instanceof MethodDeclaration method) {
                out.add(methodLike(Outline.Kind.METHOD, method.getNameAsString(), method,
                        method.getBody().orElse(null)));
            } else if (member instanceof ConstructorDeclaration ctor) {
                out.add(methodLike(Outline.Kind.CONSTRUCTOR, ctor.getNameAsString(), ctor, ctor.getBody()));
            } else if (member instanceof FieldDeclaration field) {
                // One declaration → one FIELD shown whole (`int a, b;` stays a single verbatim line).
                String name = field.getVariables().isEmpty() ? "" : field.getVariable(0).getNameAsString();
                out.add(leafDecl(Outline.Kind.FIELD, name, field));
            } else if (member instanceof EnumConstantDeclaration constant) {
                out.add(leafDecl(Outline.Kind.ENUM_CONSTANT, constant.getNameAsString(), constant));
            } else if (member instanceof AnnotationMemberDeclaration element) {
                out.add(leafDecl(Outline.Kind.METHOD, element.getNameAsString(), element));
            }
            // Initializer blocks, compact record constructors, etc. are not skim declarations → skipped.
        }

        /** A method/constructor: header up to the body brace + verbatim inner body, or the whole decl if bodyless. */
        private SkimDecl methodLike(Outline.Kind kind, String name, Node node, BlockStmt body) {
            var range = node.getRange().orElseThrow();
            DocSpan doc = docOf(node);
            if (body != null && body.getRange().isPresent()) {
                var b = body.getRange().get();
                String header = rstrip(sliceExclusiveEnd(range.begin.line, b.begin.line, b.begin.column));
                // Inner = between the braces: exclude the leading `{` and the trailing `}`.
                String inner = source.substring(offset(b.begin.line, b.begin.column) + 1,
                        offset(b.end.line, b.end.column));
                return new SkimDecl(kind, name, doc.text(), doc.line(), header,
                        range.begin.line, range.end.line, inner, true, hasLineComment(body), List.of());
            }
            return leafDecl(kind, name, node);
        }

        /** A no-body declaration (field, abstract method, enum constant) shown whole, incl. the trailing {@code ;}. */
        private SkimDecl leafDecl(Outline.Kind kind, String name, Node node) {
            var range = node.getRange().orElseThrow();
            DocSpan doc = docOf(node);
            String full = sliceInclusiveFromLineStart(range.begin.line, range.end.line, range.end.column);
            return new SkimDecl(kind, name, doc.text(), doc.line(), full,
                    range.begin.line, range.end.line, null, false, false, List.of());
        }

        /** Whether a block body holds a {@code //} line comment (a comment token, never a {@code //} in a string). */
        private boolean hasLineComment(BlockStmt body) {
            for (JavaToken t : body.getTokenRange().orElseThrow()) {
                if (t.getText().startsWith("//")) {
                    return true;
                }
            }
            return false;
        }

        /** The node's attached Javadoc as a verbatim span, or {@code (null, -1)} when there is none. */
        private DocSpan docOf(Node node) {
            var comment = node.getComment();
            if (comment.isPresent() && comment.get().isJavadocComment() && comment.get().getRange().isPresent()) {
                var r = comment.get().getRange().get();
                return new DocSpan(sliceInclusiveFromLineStart(r.begin.line, r.end.line, r.end.column), r.begin.line);
            }
            return new DocSpan(null, -1);
        }

        /** The opening body brace of {@code node} — the first {@code &#123;} outside any {@code ()}/{@code []}. */
        private Range bodyBrace(Node node) {
            int paren = 0;
            int bracket = 0;
            for (JavaToken t : node.getTokenRange().orElseThrow()) {
                switch (t.getText()) {
                    case "(" -> paren++;
                    case ")" -> paren--;
                    case "[" -> bracket++;
                    case "]" -> bracket--;
                    case "{" -> {
                        // The body brace is at top level; an annotation array `@A({…})` or array initializer
                        // sits inside `()`/`[]`, so skip those.
                        if (paren == 0 && bracket == 0) {
                            return t.getRange().orElseThrow();
                        }
                    }
                    default -> { }
                }
            }
            throw new IllegalStateException("no body brace in " + node.getClass().getSimpleName());
        }

        // ---- verbatim slicing (1-based line/col; col is a character position within the line) -------

        private int offset(int line, int col) {
            return lineStarts[line - 1] + (col - 1);
        }

        /** Source from line/col {@code begin} through line/col {@code end}, both inclusive. */
        private String sliceInclusive(int beginLine, int beginCol, int endLine, int endCol) {
            return source.substring(offset(beginLine, beginCol), offset(endLine, endCol) + 1);
        }

        /** Source from the <em>start</em> of {@code beginLine} through (end inclusive) — keeps leading indent. */
        private String sliceInclusiveFromLineStart(int beginLine, int endLine, int endCol) {
            return source.substring(lineStarts[beginLine - 1], offset(endLine, endCol) + 1);
        }

        /** Source from the start of {@code beginLine} up to (not including) line/col {@code end}. */
        private String sliceExclusiveEnd(int beginLine, int endLine, int endCol) {
            return source.substring(lineStarts[beginLine - 1], offset(endLine, endCol));
        }
    }

    /** A verbatim Javadoc span: its text (or {@code null} if none) and 1-based start line ({@code -1} if none). */
    private record DocSpan(String text, int line) {}

    /** Trim trailing whitespace (keeps leading indentation, which a span needs for source-true layout). */
    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Char offset where each 1-based line begins: {@code [0]} is line 1, then one past every {@code '\n'}. */
    private static int[] computeLineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = starts.get(i);
        }
        return out;
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
