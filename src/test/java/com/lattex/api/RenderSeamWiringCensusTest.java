package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The structural seam census (plan 28201def; rulings L1623, C1626, F1627): every public render
 * entry of {@link LatteX} runs its WHOLE post-parse pipeline inside {@code containRender}, or is
 * classified as something that does not need to.
 *
 * <p><strong>Why it exists.</strong> The never-throw floor used to be witnessed by a real stack
 * overflow arriving at each public entry's channel through that entry's own catch. Under the
 * nesting limit of 80 and the parser's own backstop, a real overflow from legal input is typed
 * inside the parser before any public seam's catch sees it, so that witness is no longer
 * constructible. The property still matters, because a FUTURE post-parse stage could overflow.
 * It is now witnessed in two halves:
 * <ol>
 *   <li>the boundary's catch handles the Error:
 *       {@code RenderContainmentTest.boundaryContainsStackOverflowErrorIntoTheTypedChannel} and
 *       its synthetic siblings; and</li>
 *   <li>every entry is wired to that catch with its whole pipeline inside: THIS test.</li>
 * </ol>
 *
 * <p><strong>What it asserts: shape, not a count.</strong> It reads
 * {@code src/main/java/com/lattex/api/LatteX.java} at test time (java.base only, no parser
 * library), blanks comments and string, char and text-block literals so a decoy cannot satisfy or
 * violate it, and then checks:
 * <ul>
 *   <li>Exactly SEVEN owners ({@link #OWNERS}) each return through ONE {@code containRender}
 *       call whose supplier is a zero-argument lambda, with the owner's parse, layout, emit,
 *       MathML, attribute or pair work inside that lambda, and NO call outside it except a short
 *       allowlist of argument checks ({@link #PRE_BOUNDARY_ALLOWED}). An extra return route around
 *       the boundary fails, and a parse moved before the supplier fails, even while all seven
 *       boundary calls remain. {@code tryRenderMath} reaches {@code renderMathPair} under its
 *       supplier; {@code fxContainerAttrs} has parse and attrs, and no layout.</li>
 *   <li>Exactly FOUR public delegates ({@link #DELEGATES}) are one return of their owner,
 *       forwarding the source unchanged.</li>
 *   <li>{@code renderWithDiagnostics} is classified separately: it never uses
 *       {@code containRender}, and is held by {@code diagnosticRender}'s own catches plus
 *       {@code renderedErrorFailSoft}. Asset accessors are not render pipelines.</li>
 *   <li>FAIL CLOSED: a public static method of LatteX the census cannot classify fails, naming
 *       it, and the census's own reading of the public static surface must equal the compiled
 *       class's (reflection), so a declaration the reader misses cannot pass silently.</li>
 * </ul>
 * The parameterized rows report each owner and each delegate as its own case, with distinct
 * {@code ownerRow} / {@code delegateRow} display-name prefixes (Gradle's XML names a parameterized
 * case by its display name alone).
 *
 * <p><strong>Controls</strong> (run once as a causal check, never applied to the tree): (a)
 * removing only {@code StackOverflowError} from {@code containRender}'s catch must fail the
 * synthetic unit, not this census; (b) unwrapping {@code toMathML} must fail only its
 * {@code ownerRow}; (c) moving one owner's parse outside the wrapper must fail that owner's row.
 *
 * <p><strong>Scope, stated so it is not read as broader.</strong> This is finite structural
 * coverage of ONE source file against a frozen classification. It is not a whole-classpath
 * check, not a taint or data-flow proof, and not a universal stack-exhaustion proof. It does not
 * look inside the parser, layout or emitter, it judges stages by the calls that name them
 * ({@link Stage}), it merges a helper's overloads by name, and it does not translate unicode
 * escapes outside literals. It relies on the existing {@code :test} input declaration of
 * {@code LatteX.java} (property {@code deprecationSunsetSource}), so a comment-only edit to that
 * file still re-runs it.
 */
class RenderSeamWiringCensusTest {

    private static final Path API = Path.of("src/main/java/com/lattex/api/LatteX.java");

    // ------------------------------------------------------------------
    // The frozen classification (L1623 inventory, C1626 amendment, F1627)
    // ------------------------------------------------------------------

    /** A pipeline stage, recognised by the call that performs it (see {@link Census#directStage}). */
    enum Stage { PARSE, LAYOUT, EMIT, MATHML, ATTRS, PAIR, TRAVERSAL }

    /** An owner: its signature, the stages its supplier must reach, the ones it must not. */
    record OwnerSpec(String signature, Set<Stage> required, Set<Stage> forbidden, boolean failInert) {
    }

    /** A delegate and the owner (or diagnostic route) it must be one return of. */
    record DelegateSpec(String signature, String target) {
    }

    static final List<OwnerSpec> OWNERS = List.of(
        new OwnerSpec("render(String,RenderOptions)",
            EnumSet.of(Stage.PARSE, Stage.LAYOUT, Stage.EMIT), EnumSet.noneOf(Stage.class), false),
        new OwnerSpec("renderInlineResult(String)",
            EnumSet.of(Stage.PARSE, Stage.LAYOUT, Stage.EMIT), EnumSet.noneOf(Stage.class), false),
        new OwnerSpec("renderFragment(String,double)",
            EnumSet.of(Stage.PARSE, Stage.LAYOUT, Stage.EMIT, Stage.MATHML), EnumSet.noneOf(Stage.class), false),
        new OwnerSpec("renderStyledHtml(String,RenderOptions)",
            EnumSet.of(Stage.PARSE, Stage.LAYOUT, Stage.EMIT, Stage.ATTRS), EnumSet.noneOf(Stage.class), false),
        new OwnerSpec("fxContainerAttrs(String)",
            EnumSet.of(Stage.PARSE, Stage.ATTRS), EnumSet.of(Stage.LAYOUT), false),
        new OwnerSpec("tryRenderMath(String,RenderOptions)",
            EnumSet.of(Stage.PAIR, Stage.PARSE, Stage.LAYOUT, Stage.EMIT), EnumSet.noneOf(Stage.class), true),
        new OwnerSpec("toMathML(String)",
            EnumSet.of(Stage.PARSE, Stage.MATHML), EnumSet.noneOf(Stage.class), false));

    static final List<DelegateSpec> DELEGATES = List.of(
        new DelegateSpec("render(String)", "render(String,RenderOptions)"),
        new DelegateSpec("renderInline(String)", "renderInlineResult(String)"),
        new DelegateSpec("renderStyledHtml(String)", "renderStyledHtml(String,RenderOptions)"),
        new DelegateSpec("tryRenderMath(String)", "tryRenderMath(String,RenderOptions)"));

    /** The diagnostic route: classified separately, never forced through containRender. */
    static final String DIAGNOSTIC_ROUTE = "renderWithDiagnostics(String,RenderOptions)";
    static final DelegateSpec DIAGNOSTIC_DELEGATE = new DelegateSpec("renderWithDiagnostics(String)", DIAGNOSTIC_ROUTE);

    /** Asset accessors: bundled resources, not render pipelines. */
    static final List<String> ASSETS = List.of("fxRuntimeJs()", "fxStylesCss()");

    /**
     * The only calls an owner may make OUTSIDE its containRender supplier: argument checks, the
     * default options, and the fail-inert owner's Optional wrapping. Anything else must move
     * inside the supplier, or be added here deliberately with a reviewer.
     */
    static final Set<String> PRE_BOUNDARY_ALLOWED = Set.of(
        "java.util.Objects.requireNonNull", "Objects.requireNonNull",
        "Double.isFinite",
        "new IllegalArgumentException",
        "RenderOptions.defaults", "<expr>.inline",
        "java.util.Optional.of", "Optional.of",
        "java.util.Optional.empty", "Optional.empty");

    // ------------------------------------------------------------------
    // The census over the real source
    // ------------------------------------------------------------------

    @Test
    void theCensusReadsTheRealLatteXSource() {
        // POSITIVE CONTROL. If the path moves, the census reads nothing and every row below would
        // be judged against an empty file.
        assertTrue(Files.exists(API),
            "LatteX.java not found from " + Path.of("").toAbsolutePath() + ": the census is INERT");
        Census census = Census.of(realSource());
        // Coverage, not only discrimination: the reader must see exactly the public static surface
        // the compiled class has, or a declaration it fails to parse would be skipped silently.
        assertEquals(compiledPublicStatics(), new TreeSet<>(census.publicStaticSignatures()),
            "the source census and the compiled LatteX class disagree on the public static surface");
        assertEquals(List.of("containRender(Supplier)"), census.signaturesNamed("containRender"),
            "exactly one containRender declaration, taking the Supplier every owner hands it");
    }

    static Stream<Arguments> ownerRows() {
        return OWNERS.stream().map(o -> Arguments.of(o.signature()));
    }

    @ParameterizedTest(name = "ownerRow {0}")
    @MethodSource("ownerRows")
    void ownerRunsItsWholePipelineInsideContainRender(String signature) {
        Census census = Census.of(realSource());
        assertEquals(List.of(), census.ownerViolations(owner(signature)),
            signature + " must return through ONE containRender call with its whole post-parse pipeline"
                + " inside the supplier and nothing but argument checks outside it");
    }

    static Stream<Arguments> delegateRows() {
        return DELEGATES.stream().map(d -> Arguments.of(d.signature(), d.target()));
    }

    @ParameterizedTest(name = "delegateRow {0} to {1}")
    @MethodSource("delegateRows")
    void delegateIsOneReturnOfItsOwner(String signature, String target) {
        Census census = Census.of(realSource());
        assertEquals(List.of(), census.delegateViolations(new DelegateSpec(signature, target)),
            signature + " must be one return of " + target + ", forwarding the source unchanged");
    }

    @Test
    void everyPublicStaticEntryIsClassifiedOrTheCensusFailsClosed() {
        Census census = Census.of(realSource());
        assertEquals(List.of(), census.classificationViolations(),
            "every public static method of LatteX must be an owner, a delegate, the diagnostic route or an"
                + " asset accessor; classify a new one here before it ships, never skip it");
        List<String> nested = new ArrayList<>();
        for (Class<?> type : LatteX.class.getDeclaredClasses()) {
            for (Method m : type.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                    nested.add(type.getSimpleName() + "." + signatureOf(m));
                }
            }
        }
        assertEquals(List.of(), nested, "a public static method on a nested type of LatteX is an unclassified route");
    }

    @Test
    void renderWithDiagnosticsIsHeldByItsOwnCatchesNotContainRender() {
        Census census = Census.of(realSource());
        assertEquals(List.of(), census.diagnosticViolations(),
            "renderWithDiagnostics is classified separately: diagnosticRender's own StackOverflowError and"
                + " RuntimeException catches hold its pipeline, and the error card runs under renderedErrorFailSoft");
    }

    @Test
    void assetAccessorsAreNotRenderPipelines() {
        Census census = Census.of(realSource());
        assertEquals(List.of(), census.assetViolations(), "an asset accessor reaches no render stage");
    }

    @Test
    void reformattingAndCommentDecoysLeaveTheRealVerdictUnchanged() {
        // Unrelated formatting stays healthy, and a decoy in a comment cannot move the verdict.
        // Built from the real file as a FRESH text, never by editing the file under test.
        String source = realSource();
        String noisy = Lexer.reformatWithDecoys(source);
        assertTrue(count(noisy, "MathParser.parse(") >= count(source, "MathParser.parse(") + 100,
            "the decoys must actually be injected, or this proves nothing");
        assertEquals(Census.of(source).verdict(), Census.of(noisy).verdict(),
            "reflowed lines plus comment decoys after every '{' and before every ';' changed the census verdict");
    }

    // ------------------------------------------------------------------
    // The analyzer's own discrimination, on a synthetic LatteX-shaped source
    // ------------------------------------------------------------------

    @Test
    void theLexerBlanksCommentsAndLiteralsButKeepsCodePositions() {
        String src = "a(); // b()\n"
            + "/* c() */ d(\"e() \\\" f()\", 'g', '\\'', '(');\n"
            + "String t = \"\"\"\n    h() \\\"\"\" i()\n    \"\"\";\n"
            + "j(\"http://k()\"); l();";
        String code = Lexer.codeOnly(src);
        assertEquals(src.length(), code.length(), "blanking keeps every offset");
        List<String> chains = new ArrayList<>();
        for (Census.Call c : Census.calls(code)) {
            chains.add(c.chain());
        }
        assertEquals(List.of("a", "d", "j", "l"), chains,
            "only real calls survive: none from a comment, a string, an escaped quote, a char or a text block");
    }

    @Test
    void theAnalyzerSeesThroughDecoysOnAHealthySyntheticSource() {
        Census census = Census.of(SYNTHETIC);
        assertEquals(Map.of(), failingRows(census.violationsByRow()), "the synthetic source is healthy");
        assertEquals(OWNERS.size(), census.boundaryCallCount(), "one boundary call per synthetic owner");
        assertTrue(count(SYNTHETIC, "MathParser.parse(") > count(census.code(), "MathParser.parse("),
            "the synthetic source must really carry decoys in comments and literals");
    }

    @Test
    void aParseMovedBeforeTheSupplierFailsItsOwnerWhileSevenBoundaryCallsRemain() {
        String mutated = SYNTHETIC.replaceFirst(
            "return\\s+containRender\\(\\(\\)\\s*->\\s*\\{\\s*MathNode inlineNode = MathParser\\.parse\\(latex\\);",
            "MathNode inlineNode = MathParser.parse(latex);\n        return containRender(() -> {");
        assertNotEquals(SYNTHETIC, mutated, "the mutation must apply, or this test proves nothing");
        Census census = Census.of(mutated);
        assertEquals(OWNERS.size(), census.boundaryCallCount(), "a count would still see seven boundary calls");
        assertEquals(Map.of("ownerRow renderInlineResult(String)", List.of(
                "missing inside the containRender supplier: [PARSE]",
                "outside the containRender supplier: MathParser.parse [PARSE]")),
            failingRows(census.violationsByRow()));
    }

    @Test
    void anAlternateReturnRouteFailsItsOwnerWhileSevenBoundaryCallsRemain() {
        String mutated = SYNTHETIC.replaceFirst("char quote = '\"';",
            "char quote = '\"';\n        if (latex.isEmpty()) {\n"
                + "            return SvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex), null), latex);\n"
                + "        }");
        assertNotEquals(SYNTHETIC, mutated, "the mutation must apply, or this test proves nothing");
        Census census = Census.of(mutated);
        assertEquals(OWNERS.size(), census.boundaryCallCount(), "a count would still see seven boundary calls");
        Map<String, List<String>> failing = failingRows(census.violationsByRow());
        assertEquals(Set.of("ownerRow render(String,RenderOptions)"), failing.keySet(), "only that owner: " + failing);
        List<String> row = failing.get("ownerRow render(String,RenderOptions)");
        assertTrue(row.contains("an alternate return route bypasses containRender:"
                + " returnSvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex),null),latex);"), row.toString());
        assertTrue(row.contains("outside the containRender supplier: MathParser.parse [PARSE]"), row.toString());
    }

    @Test
    void anUnclassifiedPublicEntryFailsClosedWhileSevenBoundaryCallsRemain() {
        String extra = SYNTHETIC.replaceFirst("public static String fxRuntimeJs\\(\\) \\{",
            "public static String renderUnguarded(String latex) {\n"
                + "        return SvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex), null), latex);\n"
                + "    }\n\n    public static String fxRuntimeJs() {");
        assertNotEquals(SYNTHETIC, extra, "the mutation must apply, or this test proves nothing");
        Census census = Census.of(extra);
        assertEquals(OWNERS.size(), census.boundaryCallCount(), "a count would still see seven boundary calls");
        assertEquals(Map.of("classification", List.of("unclassified public static entry: renderUnguarded(String)")),
            failingRows(census.violationsByRow()));

        String nested = SYNTHETIC.replaceFirst("public RenderedMath \\{",
            "public static RenderedMath of(String svg) {\n"
                + "            return new RenderedMath(svg, java.util.Map.of());\n"
                + "        }\n\n        public RenderedMath {");
        assertNotEquals(SYNTHETIC, nested, "the mutation must apply, or this test proves nothing");
        assertEquals(Map.of("classification", List.of("unclassified public static method on a nested type: RenderedMath.of(String)")),
            failingRows(Census.of(nested).violationsByRow()));
    }

    @Test
    void aDelegateThatStopsDelegatingFailsItsRow() {
        String mutated = SYNTHETIC.replaceFirst("return renderInlineResult\\(latex\\)\\.svg\\(\\);",
            "return SvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex), null), latex);");
        assertNotEquals(SYNTHETIC, mutated, "the mutation must apply, or this test proves nothing");
        Map<String, List<String>> failing = failingRows(Census.of(mutated).violationsByRow());
        assertEquals(Set.of("delegateRow renderInline(String)"), failing.keySet(), "only that delegate: " + failing);
    }

    @Test
    void layoutInsideFxContainerAttrsIsFlagged() {
        String mutated = SYNTHETIC.replaceFirst("return fxAttrs\\(node\\);",
            "LayoutEngine.layout(node, null);\n            return fxAttrs(node);");
        assertNotEquals(SYNTHETIC, mutated, "the mutation must apply, or this test proves nothing");
        assertEquals(Map.of("ownerRow fxContainerAttrs(String)", List.of("forbidden inside the containRender supplier: [LAYOUT]")),
            failingRows(Census.of(mutated).violationsByRow()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String realSource() {
        try {
            return Files.readString(API, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("LatteX.java not readable from " + Path.of("").toAbsolutePath()
                + ": the census is INERT", e);
        }
    }

    private static OwnerSpec owner(String signature) {
        for (OwnerSpec o : OWNERS) {
            if (o.signature().equals(signature)) {
                return o;
            }
        }
        throw new IllegalArgumentException("not an owner: " + signature);
    }

    private static Set<String> compiledPublicStatics() {
        Set<String> out = new TreeSet<>();
        for (Method m : LatteX.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                out.add(signatureOf(m));
            }
        }
        return out;
    }

    private static String signatureOf(Method m) {
        List<String> types = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
            types.add(p.getSimpleName());
        }
        return m.getName() + "(" + String.join(",", types) + ")";
    }

    private static Map<String, List<String>> failingRows(Map<String, List<String>> rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : rows.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static int count(String hay, String needle) {
        int n = 0;
        for (int at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // The lexer: blanks comments and literal contents, keeping every offset
    // ------------------------------------------------------------------

    static final class Lexer {

        /** The source with comments and literal contents blanked, plus which offsets are code. */
        record Lexed(String code, boolean[] mask) {
        }

        private Lexer() {
        }

        static String codeOnly(String src) {
            return lex(src).code();
        }

        /**
         * Blanks every comment, and the contents of every string, char and text-block literal, to
         * spaces (newlines kept), so offsets are preserved. Literal delimiters stay, so
         * {@code f(x, "")} keeps its shape. {@code mask[k]} is true for code characters only.
         */
        static Lexed lex(String src) {
            int n = src.length();
            char[] out = src.toCharArray();
            boolean[] mask = new boolean[n];
            int i = 0;
            while (i < n) {
                char c = src.charAt(i);
                char d = i + 1 < n ? src.charAt(i + 1) : '\0';
                if (c == '/' && d == '/') {
                    int end = src.indexOf('\n', i);
                    if (end < 0) {
                        end = n;
                    }
                    blank(out, i, end);
                    i = end;
                } else if (c == '/' && d == '*') {
                    int end = src.indexOf("*/", i + 2);
                    if (end < 0) {
                        throw new IllegalStateException("unterminated block comment at offset " + i);
                    }
                    blank(out, i, end + 2);
                    i = end + 2;
                } else if (c == '"' && d == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                    int j = i + 3;
                    while (true) {
                        if (j + 2 >= n) {
                            throw new IllegalStateException("unterminated text block at offset " + i);
                        }
                        char x = src.charAt(j);
                        if (x == '\\') {
                            j += 2;
                        } else if (x == '"' && src.charAt(j + 1) == '"' && src.charAt(j + 2) == '"') {
                            break;
                        } else {
                            j++;
                        }
                    }
                    mask[i] = true;
                    mask[i + 1] = true;
                    mask[i + 2] = true;
                    blank(out, i + 3, j);
                    mask[j] = true;
                    mask[j + 1] = true;
                    mask[j + 2] = true;
                    i = j + 3;
                } else if (c == '"' || c == '\'') {
                    int j = i + 1;
                    while (true) {
                        if (j >= n || src.charAt(j) == '\n') {
                            throw new IllegalStateException("unterminated literal at offset " + i);
                        }
                        char x = src.charAt(j);
                        if (x == '\\') {
                            j += 2;
                        } else if (x == c) {
                            break;
                        } else {
                            j++;
                        }
                    }
                    mask[i] = true;
                    blank(out, i + 1, j);
                    mask[j] = true;
                    i = j + 1;
                } else {
                    mask[i] = true;
                    i++;
                }
            }
            return new Lexed(new String(out), mask);
        }

        private static void blank(char[] out, int from, int to) {
            for (int k = from; k < to; k++) {
                if (out[k] != '\n') {
                    out[k] = ' ';
                }
            }
        }

        /** Comment decoys a text scan would mistake for pipeline calls and boundaries. */
        static final String DECOY = " /* return SvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex), ctx));"
            + " containRender(() -> x); \"it's\" */ // MathParser.parse(latex); '\"' { containRender(\n  ";

        /**
         * A fresh text from {@code src}: a comment decoy after every code {@code '{'} and before
         * every code {@code ';'}, and every code line break doubled. Literals and comments are
         * copied untouched, so the program is unchanged; only its formatting and comments differ.
         */
        static String reformatWithDecoys(String src) {
            boolean[] mask = lex(src).mask();
            StringBuilder out = new StringBuilder(src.length() * 4);
            for (int k = 0; k < src.length(); k++) {
                char ch = src.charAt(k);
                if (!mask[k]) {
                    out.append(ch);
                } else if (ch == ';') {
                    out.append(DECOY).append(';');
                } else if (ch == '{') {
                    out.append('{').append(DECOY);
                } else if (ch == '\n') {
                    out.append("\n\n");
                } else {
                    out.append(ch);
                }
            }
            return out.toString();
        }
    }

    // ------------------------------------------------------------------
    // The census: member reader, call reader, stage closure, and the row checks
    // ------------------------------------------------------------------

    static final class Census {

        /** A method declared directly in LatteX, with its code-only body. */
        record Decl(String name, String signature, List<String> paramNames, Set<String> modifiers, String body) {
            boolean isPublic() {
                return modifiers.contains("public");
            }

            boolean isStatic() {
                return modifiers.contains("static");
            }

            boolean isPrivate() {
                return modifiers.contains("private");
            }
        }

        /** A call: its callee chain (e.g. {@code MathParser.parse}), where it starts, and its '('. */
        record Call(String chain, int start, int paren) {
        }

        /** A try statement: its block's braces and the simple names of the types it catches. */
        record TryBlock(int open, int close, Set<String> catches) {
        }

        private static final Pattern CLASS_LATTEX = Pattern.compile("\\bclass\\s+LatteX\\b");
        private static final Pattern TYPE_DECL = Pattern.compile(
            "(?:^|[^\\w$.])(?:@\\s*interface|class|interface|enum|record)\\s+([\\w$]+)");
        private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");
        private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
        private static final Pattern TRY = Pattern.compile("\\btry\\b");
        private static final Pattern PARSE_CALL = Pattern.compile("(?:[\\w$.]*\\.)?MathParser\\.[\\w$]+");
        private static final Pattern LAYOUT_CALL = Pattern.compile("(?:[\\w$.]*\\.)?LayoutEngine\\.[\\w$]+");
        private static final Pattern EMIT_CALL = Pattern.compile("(?:[\\w$.]*\\.)?(?:SvgEmitter|RenderedErrorCard)\\.[\\w$]+");
        private static final Pattern EXPANSION_CALL = Pattern.compile("(?:[\\w$.]*\\.)?(?:SumExpansion|SubstituteExpansion)\\.[\\w$]+");
        private static final Pattern SELF_CALL = Pattern.compile("(?:(?:com\\.lattex\\.api\\.)?LatteX\\.)?([\\w$]+)");

        private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "static", "final",
            "abstract", "synchronized", "native", "default", "strictfp");
        private static final Set<String> NOT_CALLS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "return", "throw", "try", "assert", "yield", "case", "else", "do", "new", "instanceof");
        private static final Set<String> BOUNDARY = Set.of(
            "containRender", "LatteX.containRender", "com.lattex.api.LatteX.containRender");
        private static final Set<String> FAIL_INERT_BOUNDARY_RETURNS = Set.of(
            "returnjava.util.Optional.of(@B);", "returnOptional.of(@B);");
        private static final Set<String> FAIL_INERT_EMPTY_RETURNS = Set.of(
            "returnjava.util.Optional.empty();", "returnOptional.empty();");
        private static final Set<String> CONTAINMENT_CATCHES = Set.of("StackOverflowError", "RuntimeException");

        private final String code;
        private final List<Decl> methods = new ArrayList<>();
        private final List<String> nestedPublicStatics = new ArrayList<>();
        private Map<String, Set<Stage>> closures;

        private Census(String code) {
            this.code = code;
        }

        static Census of(String source) {
            Census c = new Census(Lexer.codeOnly(source));
            Matcher m = CLASS_LATTEX.matcher(c.code);
            if (!m.find()) {
                throw new IllegalStateException("no 'class LatteX' declaration in the source");
            }
            int open = c.code.indexOf('{', m.end());
            if (open < 0) {
                throw new IllegalStateException("'class LatteX' has no body");
            }
            c.collectMembers(open, matching(c.code, open), true, "");
            return c;
        }

        String code() {
            return code;
        }

        // ---- the member reader ----

        private void collectMembers(int open, int close, boolean topLevel, String typePrefix) {
            int start = open + 1;
            int i = start;
            while (i < close) {
                char ch = code.charAt(i);
                if (ch == '(') {
                    i = matching(code, i) + 1;
                } else if (ch == ';') {
                    i++;
                    start = i;
                } else if (ch == '{') {
                    String header = code.substring(start, i);
                    int end = matching(code, i);
                    if (assignsAtTopLevel(header)) {
                        // A field initializer with braces (array, lambda, anonymous class): its ';' ends it.
                        i = end + 1;
                        continue;
                    }
                    String typeName = typeDeclName(header);
                    if (typeName != null) {
                        collectMembers(i, end, false, typePrefix + typeName + ".");
                    } else if (header.indexOf('(') >= 0) {
                        Decl d = parseDecl(header, code.substring(i + 1, end));
                        if (topLevel) {
                            methods.add(d);
                        } else if (d.isPublic() && d.isStatic()) {
                            nestedPublicStatics.add(typePrefix + d.signature());
                        }
                    }
                    i = end + 1;
                    start = i;
                } else {
                    i++;
                }
            }
        }

        private static boolean assignsAtTopLevel(String header) {
            int depth = 0;
            for (int k = 0; k < header.length(); k++) {
                char ch = header.charAt(k);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == '=' && depth == 0) {
                    char prev = k > 0 ? header.charAt(k - 1) : ' ';
                    char next = k + 1 < header.length() ? header.charAt(k + 1) : ' ';
                    if (next != '=' && prev != '=' && prev != '!' && prev != '<' && prev != '>') {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String typeDeclName(String header) {
            Matcher m = TYPE_DECL.matcher(withoutParenContents(header));
            return m.find() ? m.group(1) : null;
        }

        private static String withoutParenContents(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            int depth = 0;
            for (int k = 0; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '(') {
                    if (depth == 0) {
                        sb.append('(');
                    }
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        sb.append(')');
                    }
                } else if (depth == 0) {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private static Decl parseDecl(String header, String body) {
            int close = header.lastIndexOf(')');
            int open = matchingBackward(header, close);
            int e = skipSpaceBack(header, open - 1);
            int b = e;
            while (b > 0 && Character.isJavaIdentifierPart(header.charAt(b - 1))) {
                b--;
            }
            String name = header.substring(b, e + 1);
            Set<String> modifiers = new TreeSet<>();
            Matcher w = WORD.matcher(header.substring(0, b));
            while (w.find()) {
                if (MODIFIERS.contains(w.group())) {
                    modifiers.add(w.group());
                }
            }
            List<String> types = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String p : splitTopLevel(header.substring(open + 1, close), ',')) {
                String param = stripAnnotations(p).replaceAll("\\bfinal\\b", " ").trim();
                if (param.isEmpty()) {
                    continue;
                }
                int pe = param.length() - 1;
                int pb = pe;
                while (pb > 0 && Character.isJavaIdentifierPart(param.charAt(pb - 1))) {
                    pb--;
                }
                names.add(param.substring(pb, pe + 1));
                types.add(simpleType(param.substring(0, pb)));
            }
            return new Decl(name, name + "(" + String.join(",", types) + ")",
                List.copyOf(names), Set.copyOf(modifiers), body);
        }

        private static String stripAnnotations(String s) {
            StringBuilder sb = new StringBuilder();
            int k = 0;
            while (k < s.length()) {
                char ch = s.charAt(k);
                if (ch != '@') {
                    sb.append(ch);
                    k++;
                    continue;
                }
                k++;
                while (k < s.length() && (Character.isJavaIdentifierPart(s.charAt(k)) || s.charAt(k) == '.'
                        || Character.isWhitespace(s.charAt(k)))) {
                    if (Character.isWhitespace(s.charAt(k)) && k + 1 < s.length()
                            && Character.isJavaIdentifierStart(s.charAt(k + 1))) {
                        break; // the annotation name ended; the next word is the type
                    }
                    k++;
                }
                if (k < s.length() && s.charAt(k) == '(') {
                    k = matching(s, k) + 1;
                }
                sb.append(' ');
            }
            return sb.toString();
        }

        private static String simpleType(String t) {
            String s = t.replaceAll("\\s+", "");
            boolean varargs = s.endsWith("...");
            if (varargs) {
                s = s.substring(0, s.length() - 3);
            }
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            for (int k = 0; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '<') {
                    depth++;
                } else if (ch == '>') {
                    depth--;
                } else if (depth == 0) {
                    sb.append(ch);
                }
            }
            s = sb.toString();
            StringBuilder arrays = new StringBuilder();
            while (s.endsWith("[]")) {
                arrays.append("[]");
                s = s.substring(0, s.length() - 2);
            }
            int dot = s.lastIndexOf('.');
            return (dot >= 0 ? s.substring(dot + 1) : s) + arrays + (varargs ? "[]" : "");
        }

        // ---- the call reader ----

        /** Every call in {@code t} (code-only text), left to right by its '('. */
        static List<Call> calls(String t) {
            List<Call> out = new ArrayList<>();
            for (int i = 0; i < t.length(); i++) {
                if (t.charAt(i) != '(') {
                    continue;
                }
                int e = skipSpaceBack(t, i - 1);
                if (e >= 0 && t.charAt(e) == '>') {
                    // a diamond or explicit type arguments before the '(': new Foo<>(...)
                    int lt = skipTypeArgsBack(t, e);
                    if (lt < 0) {
                        continue;
                    }
                    e = skipSpaceBack(t, lt - 1);
                }
                if (e < 0 || !Character.isJavaIdentifierPart(t.charAt(e))) {
                    continue; // a cast, a grouping paren or a lambda's parameter list
                }
                int b = e;
                while (b > 0 && Character.isJavaIdentifierPart(t.charAt(b - 1))) {
                    b--;
                }
                String name = t.substring(b, e + 1);
                if (!Character.isJavaIdentifierStart(name.charAt(0)) || NOT_CALLS.contains(name)) {
                    continue;
                }
                if (b > 0 && t.charAt(b - 1) == '@') {
                    continue; // an annotation, not a call
                }
                String chain = name;
                int start = b;
                while (true) {
                    int d = skipSpaceBack(t, start - 1);
                    if (d >= 0 && t.charAt(d) == '>') {
                        int lt = skipTypeArgsBack(t, d);
                        if (lt < 0) {
                            break;
                        }
                        d = skipSpaceBack(t, lt - 1);
                    }
                    if (d < 0 || t.charAt(d) != '.') {
                        break;
                    }
                    int q = skipSpaceBack(t, d - 1);
                    if (q < 0 || !Character.isJavaIdentifierPart(t.charAt(q))) {
                        chain = "<expr>." + chain; // a call on an expression's result: f().g(
                        start = d;
                        break;
                    }
                    int qb = q;
                    while (qb > 0 && Character.isJavaIdentifierPart(t.charAt(qb - 1))) {
                        qb--;
                    }
                    chain = t.substring(qb, q + 1) + "." + chain;
                    start = qb;
                }
                int k = skipSpaceBack(t, start - 1);
                boolean isNew = k >= 2 && t.startsWith("new", k - 2)
                    && (k - 3 < 0 || !Character.isJavaIdentifierPart(t.charAt(k - 3)));
                if (isNew) {
                    start = k - 2;
                }
                out.add(new Call(isNew ? "new " + chain : chain, start, i));
            }
            return out;
        }

        // ---- stages ----

        /** The stage a callee chain names directly, or null. */
        static Stage directStage(String chain) {
            if (chain.startsWith("new ")) {
                return null;
            }
            if (PARSE_CALL.matcher(chain).matches()) {
                return Stage.PARSE;
            }
            if (LAYOUT_CALL.matcher(chain).matches()) {
                return Stage.LAYOUT;
            }
            if (EMIT_CALL.matcher(chain).matches()) {
                return Stage.EMIT;
            }
            if (EXPANSION_CALL.matcher(chain).matches()) {
                return Stage.TRAVERSAL;
            }
            String self = selfName(chain);
            if (self == null) {
                return null;
            }
            switch (self) {
                case "mathmlDocument", "mathmlOrEmpty", "toMathML":
                    return Stage.MATHML;
                case "fxAttrs", "openTag", "containerAttrMap":
                    return Stage.ATTRS;
                case "renderMathPair":
                    return Stage.PAIR;
                case "describe":
                    return Stage.TRAVERSAL;
                default:
                    return null;
            }
        }

        /** The simple name of a call on LatteX itself (unqualified, or LatteX-qualified), or null. */
        static String selfName(String chain) {
            Matcher m = SELF_CALL.matcher(chain);
            return m.matches() ? m.group(1) : null;
        }

        /** Every stage a call reaches: the one it names, plus, for a LatteX helper, its closure. */
        Set<Stage> stagesOfCall(String chain) {
            Set<Stage> out = EnumSet.noneOf(Stage.class);
            Stage direct = directStage(chain);
            if (direct != null) {
                out.add(direct);
            }
            String self = selfName(chain);
            if (self != null) {
                Set<Stage> reached = closures().get(self);
                if (reached != null) {
                    out.addAll(reached);
                }
            }
            return out;
        }

        Set<Stage> stagesOf(String region) {
            Set<Stage> out = EnumSet.noneOf(Stage.class);
            for (Call c : calls(region)) {
                out.addAll(stagesOfCall(c.chain()));
            }
            return out;
        }

        /** Per LatteX method NAME (overloads merged): every stage its bodies reach, to a fixpoint. */
        private Map<String, Set<Stage>> closures() {
            if (closures != null) {
                return closures;
            }
            Map<String, Set<Stage>> reach = new LinkedHashMap<>();
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            for (Decl d : methods) {
                reach.computeIfAbsent(d.name(), k -> EnumSet.noneOf(Stage.class));
                edges.computeIfAbsent(d.name(), k -> new TreeSet<>());
            }
            for (Decl d : methods) {
                for (Call c : calls(d.body())) {
                    Stage direct = directStage(c.chain());
                    if (direct != null) {
                        reach.get(d.name()).add(direct);
                    }
                    String self = selfName(c.chain());
                    if (self != null && reach.containsKey(self)) {
                        edges.get(d.name()).add(self);
                    }
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
                    Set<Stage> mine = reach.get(e.getKey());
                    for (String callee : e.getValue()) {
                        if (mine.addAll(reach.get(callee))) {
                            changed = true;
                        }
                    }
                }
            }
            closures = reach;
            return reach;
        }

        // ---- lookups ----

        List<String> publicStaticSignatures() {
            List<String> out = new ArrayList<>();
            for (Decl d : methods) {
                if (d.isPublic() && d.isStatic()) {
                    out.add(d.signature());
                }
            }
            return out;
        }

        List<String> signaturesNamed(String name) {
            List<String> out = new ArrayList<>();
            for (Decl d : methods) {
                if (d.name().equals(name)) {
                    out.add(d.signature());
                }
            }
            return out;
        }

        private Decl publicStatic(String signature, List<String> v) {
            List<Decl> found = new ArrayList<>();
            for (Decl d : methods) {
                if (d.isPublic() && d.isStatic() && d.signature().equals(signature)) {
                    found.add(d);
                }
            }
            if (found.size() != 1) {
                v.add("expected exactly one public static " + signature + ", found " + found.size());
                return null;
            }
            return found.get(0);
        }

        private Decl named(String name, List<String> v) {
            List<Decl> found = new ArrayList<>();
            for (Decl d : methods) {
                if (d.name().equals(name)) {
                    found.add(d);
                }
            }
            if (found.size() != 1) {
                v.add("expected exactly one declaration of " + name + ", found " + found.size());
                return null;
            }
            return found.get(0);
        }

        /** containRender calls in a body, as {start, '(', ')'} offsets. */
        private static List<int[]> boundaries(String body) {
            List<int[]> out = new ArrayList<>();
            for (Call c : calls(body)) {
                if (BOUNDARY.contains(c.chain())) {
                    out.add(new int[] {c.start(), c.paren(), matching(body, c.paren())});
                }
            }
            return out;
        }

        int boundaryCallCount() {
            int n = 0;
            for (Decl d : methods) {
                n += boundaries(d.body()).size();
            }
            return n;
        }

        // ---- the owner row ----

        List<String> ownerViolations(OwnerSpec spec) {
            List<String> v = new ArrayList<>();
            Decl m = publicStatic(spec.signature(), v);
            if (m == null) {
                return v;
            }
            String body = m.body();
            List<int[]> spans = boundaries(body);
            if (spans.size() != 1) {
                v.add("expected exactly one containRender call in the body, found " + spans.size());
            }

            // Every return outside a boundary call: one must return the boundary, and the only other
            // return a fail-inert owner may have is its empty result.
            int[] boundary = null;
            Matcher r = RETURN.matcher(body);
            while (r.find()) {
                int at = r.start();
                if (within(spans, at)) {
                    continue; // a return inside the supplier lambda
                }
                int end = statementEnd(body, at);
                int[] held = null;
                for (int[] s : spans) {
                    if (s[0] > at && s[2] < end) {
                        held = s;
                        break;
                    }
                }
                String shape = (held == null ? body.substring(at, end + 1)
                    : body.substring(at, held[0]) + "@B" + body.substring(held[2] + 1, end + 1))
                    .replaceAll("\\s+", "");
                if (held != null && boundary == null) {
                    boundary = held;
                    boolean direct = shape.equals("return@B;");
                    boolean wrapped = spec.failInert() && FAIL_INERT_BOUNDARY_RETURNS.contains(shape);
                    if (!direct && !wrapped) {
                        v.add("the containRender result is not returned directly: " + abbreviate(shape));
                    }
                } else if (!(spec.failInert() && FAIL_INERT_EMPTY_RETURNS.contains(shape))) {
                    v.add("an alternate return route bypasses containRender: " + abbreviate(shape));
                }
            }
            if (boundary == null) {
                v.add("no return statement returns through containRender");
                if (!spans.isEmpty()) {
                    boundary = spans.get(0);
                }
            }

            // The supplier: a zero-argument lambda that reaches every required stage, no forbidden one.
            Set<Stage> inside = EnumSet.noneOf(Stage.class);
            if (boundary != null) {
                String arg = body.substring(boundary[1] + 1, boundary[2]);
                String argShape = arg.replaceAll("\\s+", "");
                if (!argShape.startsWith("()->")) {
                    v.add("the containRender supplier is not a zero-argument lambda: " + abbreviate(argShape));
                }
                inside.addAll(stagesOf(arg));
            }
            Set<Stage> missing = EnumSet.noneOf(Stage.class);
            missing.addAll(spec.required());
            missing.removeAll(inside);
            if (!missing.isEmpty()) {
                v.add("missing inside the containRender supplier: " + missing);
            }
            Set<Stage> forbidden = EnumSet.noneOf(Stage.class);
            forbidden.addAll(spec.forbidden());
            forbidden.retainAll(inside);
            if (!forbidden.isEmpty()) {
                v.add("forbidden inside the containRender supplier: " + forbidden);
            }

            // Outside the supplier: nothing but the argument-check allowlist.
            for (Call c : calls(body)) {
                if (boundary != null && c.start() >= boundary[0] && c.paren() <= boundary[2]) {
                    continue;
                }
                if (PRE_BOUNDARY_ALLOWED.contains(c.chain())) {
                    continue;
                }
                Set<Stage> reached = stagesOfCall(c.chain());
                v.add("outside the containRender supplier: " + c.chain() + (reached.isEmpty() ? "" : " " + reached));
            }
            for (int ref = body.indexOf("::"); ref >= 0; ref = body.indexOf("::", ref + 2)) {
                if (boundary == null || ref < boundary[0] || ref > boundary[2]) {
                    v.add("outside the containRender supplier: a method reference");
                }
            }

            if (spec.required().contains(Stage.PAIR)) {
                v.addAll(pairSubtreeViolations());
            }
            return v;
        }

        /** tryRenderMath reaches renderMathPair under its supplier, and renderMathPair is no side door. */
        private List<String> pairSubtreeViolations() {
            List<String> v = new ArrayList<>();
            Decl pair = named("renderMathPair", v);
            if (pair == null) {
                return v;
            }
            if (!pair.isPrivate()) {
                v.add("renderMathPair must stay private, or it is a public route around the boundary");
            }
            Set<Stage> own = EnumSet.noneOf(Stage.class);
            for (Call c : calls(pair.body())) {
                Stage s = directStage(c.chain());
                if (s != null) {
                    own.add(s);
                }
            }
            Set<Stage> need = EnumSet.of(Stage.PARSE, Stage.LAYOUT, Stage.EMIT);
            need.removeAll(own);
            if (!need.isEmpty()) {
                v.add("renderMathPair no longer runs " + need + " itself");
            }
            for (Decl d : methods) {
                List<int[]> spans = boundaries(d.body());
                for (Call c : calls(d.body())) {
                    if ("renderMathPair".equals(selfName(c.chain())) && !within(spans, c.start())) {
                        v.add("renderMathPair is called outside a containRender supplier in " + d.signature());
                    }
                }
            }
            return v;
        }

        // ---- the delegate row ----

        List<String> delegateViolations(DelegateSpec spec) {
            List<String> v = new ArrayList<>();
            Decl d = publicStatic(spec.signature(), v);
            Decl target = publicStatic(spec.target(), v);
            if (d == null || target == null) {
                return v;
            }
            String shape = d.body().replaceAll("\\s+", "");
            String qualified = "returnLatteX." + target.name() + "(";
            String plain = "return" + target.name() + "(";
            String prefix = shape.startsWith(qualified) ? qualified : plain;
            if (!shape.startsWith(prefix)) {
                v.add("the body is not one return of " + target.name() + "(...): " + abbreviate(shape));
                return v;
            }
            int open = prefix.length() - 1;
            int close = matching(shape, open);
            List<String> args = splitTopLevel(shape.substring(open + 1, close), ',');
            if (args.size() != target.paramNames().size()) {
                v.add("passes " + args.size() + " argument(s) to " + target.name() + ", so it does not reach " + spec.target());
            }
            if (args.isEmpty() || !args.get(0).equals(d.paramNames().get(0))) {
                v.add("does not forward its own " + d.paramNames().get(0) + " unchanged as the first argument");
            }
            for (int k = 1; k < args.size(); k++) {
                if (!args.get(k).matches("[\\w$]+|RenderOptions\\.defaults\\(\\)")) {
                    v.add("argument " + (k + 1) + " is neither a parameter nor RenderOptions.defaults(): " + args.get(k));
                }
            }
            String tail = shape.substring(close + 1);
            if (!tail.matches("(?:\\.[\\w$]+\\(\\))*;")) {
                v.add("does more after delegating than a zero-argument accessor: " + abbreviate(tail));
            }
            return v;
        }

        // ---- the diagnostic route, the assets, the classification ----

        List<String> diagnosticViolations() {
            List<String> v = new ArrayList<>();
            for (String s : delegateViolations(DIAGNOSTIC_DELEGATE)) {
                v.add(DIAGNOSTIC_DELEGATE.signature() + ": " + s);
            }
            Decl route = publicStatic(DIAGNOSTIC_ROUTE, v);
            if (route != null) {
                String body = route.body();
                int diagnosticCalls = 0;
                int failSoftCalls = 0;
                int[] failSoftArgs = null;
                for (Call c : calls(body)) {
                    String self = selfName(c.chain());
                    if ("diagnosticRender".equals(self)) {
                        diagnosticCalls++;
                    } else if ("renderedErrorFailSoft".equals(self)) {
                        failSoftCalls++;
                        failSoftArgs = new int[] {c.paren(), matching(body, c.paren())};
                    }
                }
                if (diagnosticCalls != 1) {
                    v.add(DIAGNOSTIC_ROUTE + ": expected exactly one diagnosticRender call, found " + diagnosticCalls);
                }
                if (failSoftCalls != 1) {
                    v.add(DIAGNOSTIC_ROUTE + ": expected exactly one renderedErrorFailSoft call, found " + failSoftCalls);
                }
                for (Call c : calls(body)) {
                    String self = selfName(c.chain());
                    if ("diagnosticRender".equals(self) || "renderedErrorFailSoft".equals(self)) {
                        continue;
                    }
                    Set<Stage> reached = stagesOfCall(c.chain());
                    boolean underFailSoft = failSoftArgs != null
                        && c.start() > failSoftArgs[0] && c.paren() < failSoftArgs[1];
                    if (!reached.isEmpty() && !underFailSoft) {
                        v.add(DIAGNOSTIC_ROUTE + ": " + c.chain() + " " + reached + " runs outside renderedErrorFailSoft");
                    }
                }
            }
            Decl diagnostic = named("diagnosticRender", v);
            if (diagnostic != null) {
                if (!stagesOf(diagnostic.body()).containsAll(EnumSet.of(Stage.PARSE, Stage.LAYOUT, Stage.EMIT))) {
                    v.add("diagnosticRender no longer runs parse, layout and emit, so this classification is stale");
                }
                List<TryBlock> tries = tryBlocks(diagnostic.body());
                for (Call c : calls(diagnostic.body())) {
                    Set<Stage> reached = stagesOfCall(c.chain());
                    if (!reached.isEmpty() && !heldBy(tries, c)) {
                        v.add("diagnosticRender: " + c.chain() + " " + reached
                            + " runs outside a try that catches StackOverflowError and RuntimeException");
                    }
                }
            }
            Decl failSoft = named("renderedErrorFailSoft", v);
            if (failSoft != null && !failSoft.paramNames().isEmpty()) {
                String supplier = failSoft.paramNames().get(failSoft.paramNames().size() - 1);
                List<TryBlock> tries = tryBlocks(failSoft.body());
                int gets = 0;
                for (Call c : calls(failSoft.body())) {
                    if (c.chain().equals(supplier + ".get")) {
                        gets++;
                        if (!heldBy(tries, c)) {
                            v.add("renderedErrorFailSoft: " + supplier
                                + ".get() runs outside a try that catches StackOverflowError and RuntimeException");
                        }
                    }
                }
                if (gets == 0) {
                    v.add("renderedErrorFailSoft never calls " + supplier + ".get(), so this classification is stale");
                }
            }
            return v;
        }

        List<String> assetViolations() {
            List<String> v = new ArrayList<>();
            for (String signature : ASSETS) {
                Decl d = publicStatic(signature, v);
                if (d == null) {
                    continue;
                }
                Set<Stage> reached = stagesOf(d.body());
                if (!reached.isEmpty()) {
                    v.add(signature + " is classified as an asset accessor but reaches " + reached);
                }
            }
            return v;
        }

        List<String> classificationViolations() {
            Set<String> classified = new TreeSet<>();
            for (OwnerSpec o : OWNERS) {
                classified.add(o.signature());
            }
            for (DelegateSpec d : DELEGATES) {
                classified.add(d.signature());
            }
            classified.add(DIAGNOSTIC_DELEGATE.signature());
            classified.add(DIAGNOSTIC_ROUTE);
            classified.addAll(ASSETS);
            Set<String> found = new TreeSet<>(publicStaticSignatures());
            List<String> v = new ArrayList<>();
            for (String signature : found) {
                if (!classified.contains(signature)) {
                    v.add("unclassified public static entry: " + signature);
                }
            }
            for (String signature : classified) {
                if (!found.contains(signature)) {
                    v.add("classified entry missing from LatteX.java: " + signature);
                }
            }
            for (String n : nestedPublicStatics) {
                v.add("unclassified public static method on a nested type: " + n);
            }
            return v;
        }

        /** Each row's violations, keyed the way the parameterized rows name their cases. */
        Map<String, List<String>> violationsByRow() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (OwnerSpec o : OWNERS) {
                out.put("ownerRow " + o.signature(), ownerViolations(o));
            }
            for (DelegateSpec d : DELEGATES) {
                out.put("delegateRow " + d.signature(), delegateViolations(d));
            }
            out.put("diagnostics", diagnosticViolations());
            out.put("assets", assetViolations());
            out.put("classification", classificationViolations());
            return out;
        }

        /** Every row plus what the census saw, so two readings can be compared whole. */
        Map<String, List<String>> verdict() {
            Map<String, List<String>> out = new LinkedHashMap<>(violationsByRow());
            out.put("publicStatics", new ArrayList<>(new TreeSet<>(publicStaticSignatures())));
            out.put("boundaryCalls", List.of(Integer.toString(boundaryCallCount())));
            for (OwnerSpec o : OWNERS) {
                List<String> v = new ArrayList<>();
                Decl d = publicStatic(o.signature(), v);
                out.put("stages " + o.signature(), d == null ? v : List.of(String.valueOf(stagesOf(d.body()))));
            }
            return out;
        }

        // ---- try blocks ----

        static List<TryBlock> tryBlocks(String body) {
            List<TryBlock> out = new ArrayList<>();
            Matcher m = TRY.matcher(body);
            while (m.find()) {
                int k = skipSpace(body, m.end());
                if (k < body.length() && body.charAt(k) == '(') {
                    k = skipSpace(body, matching(body, k) + 1); // try-with-resources
                }
                if (k >= body.length() || body.charAt(k) != '{') {
                    continue;
                }
                int close = matching(body, k);
                Set<String> catches = new TreeSet<>();
                int j = skipSpace(body, close + 1);
                while (body.startsWith("catch", j)
                        && (j + 5 >= body.length() || !Character.isJavaIdentifierPart(body.charAt(j + 5)))) {
                    int p = skipSpace(body, j + 5);
                    if (p >= body.length() || body.charAt(p) != '(') {
                        break;
                    }
                    int pc = matching(body, p);
                    for (String alternative : body.substring(p + 1, pc).split("\\|")) {
                        String t = alternative.replaceAll("\\bfinal\\b", " ").trim();
                        if (!t.isEmpty()) {
                            String type = t.split("\\s+")[0];
                            catches.add(type.substring(type.lastIndexOf('.') + 1));
                        }
                    }
                    int block = skipSpace(body, pc + 1);
                    if (block >= body.length() || body.charAt(block) != '{') {
                        break;
                    }
                    j = skipSpace(body, matching(body, block) + 1);
                }
                out.add(new TryBlock(k, close, Set.copyOf(catches)));
            }
            return out;
        }

        private static boolean heldBy(List<TryBlock> tries, Call c) {
            for (TryBlock t : tries) {
                if (c.start() > t.open() && c.paren() < t.close() && t.catches().containsAll(CONTAINMENT_CATCHES)) {
                    return true;
                }
            }
            return false;
        }

        // ---- text utilities over code-only text ----

        private static boolean within(List<int[]> spans, int at) {
            for (int[] s : spans) {
                if (at >= s[0] && at <= s[2]) {
                    return true;
                }
            }
            return false;
        }

        static int matching(String s, int open) {
            char o = s.charAt(open);
            char c = o == '(' ? ')' : o == '{' ? '}' : o == '[' ? ']' : '\0';
            if (c == '\0') {
                throw new IllegalArgumentException("not an opening bracket at offset " + open);
            }
            int depth = 0;
            for (int k = open; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == o) {
                    depth++;
                } else if (ch == c) {
                    depth--;
                    if (depth == 0) {
                        return k;
                    }
                }
            }
            throw new IllegalStateException("unbalanced '" + o + "' at offset " + open);
        }

        private static int matchingBackward(String s, int close) {
            int depth = 0;
            for (int k = close; k >= 0; k--) {
                char ch = s.charAt(k);
                if (ch == ')') {
                    depth++;
                } else if (ch == '(') {
                    depth--;
                    if (depth == 0) {
                        return k;
                    }
                }
            }
            throw new IllegalStateException("unbalanced ')' in a declaration header");
        }

        /** The offset of the ';' that ends the statement starting at {@code from}. */
        private static int statementEnd(String s, int from) {
            int depth = 0;
            for (int k = from; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '(' || ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == '}' || ch == ']') {
                    depth--;
                    if (depth < 0) {
                        return k - 1;
                    }
                } else if (ch == ';' && depth == 0) {
                    return k;
                }
            }
            return s.length() - 1;
        }

        private static List<String> splitTopLevel(String s, char sep) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            int from = 0;
            for (int k = 0; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch == '(' || ch == '<' || ch == '[' || ch == '{') {
                    depth++;
                } else if (ch == ')' || ch == '>' || ch == ']' || ch == '}') {
                    depth--;
                } else if (ch == sep && depth == 0) {
                    out.add(s.substring(from, k).trim());
                    from = k + 1;
                }
            }
            String last = s.substring(from).trim();
            if (!last.isEmpty() || !out.isEmpty()) {
                out.add(last);
            }
            return out;
        }

        private static int skipTypeArgsBack(String s, int gt) {
            int depth = 0;
            for (int k = gt; k >= 0; k--) {
                char ch = s.charAt(k);
                if (ch == '>') {
                    depth++;
                } else if (ch == '<') {
                    depth--;
                    if (depth == 0) {
                        return k;
                    }
                } else if (!(Character.isJavaIdentifierPart(ch) || Character.isWhitespace(ch)
                        || ch == '.' || ch == ',' || ch == '?' || ch == '[' || ch == ']')) {
                    return -1;
                }
            }
            return -1;
        }

        private static int skipSpaceBack(String s, int k) {
            while (k >= 0 && Character.isWhitespace(s.charAt(k))) {
                k--;
            }
            return k;
        }

        private static int skipSpace(String s, int k) {
            while (k < s.length() && Character.isWhitespace(s.charAt(k))) {
                k++;
            }
            return k;
        }

        private static String abbreviate(String s) {
            return s.length() <= 160 ? s : s.substring(0, 157) + "...";
        }
    }

    // ------------------------------------------------------------------
    // A synthetic LatteX-shaped source for the analyzer's own tests. It is never compiled. Decoys
    // sit in comments and literals OUTSIDE the boundaries, where a text scan would be fooled.
    // ------------------------------------------------------------------

    static final String SYNTHETIC = """
        package com.lattex.api;

        // Synthetic LatteX. A text scan would read this as a pipeline call outside every boundary:
        // return SvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex), ctx));
        public final class LatteX {

            private static final String DECOY_FIELD = "containRender(() -> MathParser.parse(x))";

            private static final class FontHolder {
                static final Object FONT = null;
            }

            private LatteX() {
            }

            static <T> T containRender(java.util.function.Supplier<T> stage) {
                try {
                    return stage.get();
                } catch (StackOverflowError | RuntimeException e) {
                    throw new MathSyntaxException("internal render failure", e);
                }
            }

            public static RenderResult renderWithDiagnostics(String latex) {
                return renderWithDiagnostics(latex, RenderOptions.defaults());
            }

            public static RenderResult renderWithDiagnostics(String latex, RenderOptions opts) {
                RenderResult result = diagnosticRender(latex, opts);
                if (opts == null || !opts.renderErrors()) {
                    return result;
                }
                return renderedErrorFailSoft(result.diagnostics(),
                    () -> RenderedErrorCard.render(latex, result.diagnostics(), FontHolder.FONT));
            }

            private static RenderResult diagnosticRender(String latex, RenderOptions opts) {
                try {
                    MathNode node = MathParser.parse(latex, opts.macros(), new java.util.ArrayList<>());
                    Layout layout = LayoutEngine.layout(node, null);
                    return new RenderResult(SvgEmitter.emit(layout, describe(node)), null);
                } catch (MathSyntaxException e) {
                    return new RenderResult("", null);
                } catch (StackOverflowError | RuntimeException e) {
                    return new RenderResult("", null);
                }
            }

            static RenderResult renderedErrorFailSoft(Diagnostics diagnostics,
                    java.util.function.Supplier<String> renderer) {
                try {
                    return new RenderResult(renderer.get(), diagnostics);
                } catch (StackOverflowError | RuntimeException cardFailure) {
                    return new RenderResult("", diagnostics);
                }
            }

            public static String render(String latex) {
                return render(latex, RenderOptions.defaults()); // MathParser.parse(latex)
            }

            public static String render(String latex, RenderOptions opts) {
                java.util.Objects.requireNonNull(opts, "a \\" MathParser.parse(x) \\" b");
                /* MathNode early = MathParser.parse(latex); LayoutEngine.layout(early, ctx); */
                char quote = '"';
                return containRender(() -> {
                    MathNode node = MathParser.parse(latex, opts.macros());
                    Layout layout = LayoutEngine.layout(node, null);
                    return SvgEmitter.emit(layout, describe(node));
                });
            }

            public static String renderInline(String latex) {
                return renderInlineResult(latex).svg();
            }

            public static InlineSvgResult renderInlineResult(String latex) {
                RenderOptions opts = RenderOptions.defaults().inline();
                return containRender(() -> {
                    MathNode inlineNode = MathParser.parse(latex);
                    Layout layout = LayoutEngine.layout(inlineNode, null);
                    return new InlineSvgResult(SvgEmitter.emit(layout, describe(inlineNode)), 0.0, 0.0);
                });
            }

            public static MathFragment renderFragment(String latex, double fontSizePx) {
                if (!Double.isFinite(fontSizePx)) {
                    throw new IllegalArgumentException("SvgEmitter.emitFragment(layout) is not called here");
                }
                return containRender(() -> {
                    MathNode node = MathParser.parse(latex);
                    Layout layout = LayoutEngine.layout(node, null);
                    return new MathFragment(SvgEmitter.emitFragment(layout), mathmlOrEmpty(node));
                });
            }

            public static String renderStyledHtml(String latex) {
                return renderStyledHtml(latex, RenderOptions.defaults());
            }

            public static String renderStyledHtml(String latex, RenderOptions opts) {
                java.util.Objects.requireNonNull(opts, "opts");
                String note = \"""
                    return SvgEmitter.emit(LayoutEngine.layout(MathParser.parse(latex), ctx));
                    \""";
                return containRender(() -> {
                    MathNode node = MathParser.parse(latex, opts.macros());
                    Layout layout = LayoutEngine.layout(node, null);
                    return openTag(node) + SvgEmitter.emit(layout, describe(node)) + "</span>";
                });
            }

            @Deprecated
            public static String fxContainerAttrs(String latex) {
                return containRender(() -> {
                    MathNode node = MathParser.parse(latex);
                    return fxAttrs(node);
                });
            }

            public static java.util.Optional<RenderedMath> tryRenderMath(String latex) {
                return tryRenderMath(latex, RenderOptions.defaults());
            }

            public static java.util.Optional<RenderedMath> tryRenderMath(String latex, RenderOptions opts) {
                java.util.Objects.requireNonNull(opts, "opts");
                try {
                    return java.util.Optional.of(containRender(() -> renderMathPair(latex, opts)));
                } catch (RuntimeException containedOrTyped) {
                    return java.util.Optional.empty();
                }
            }

            private static RenderedMath renderMathPair(String latex, RenderOptions opts) {
                MathNode seamNode = MathParser.parse(latex, opts.macros());
                Layout layout = LayoutEngine.layout(seamNode, null);
                return new RenderedMath(SvgEmitter.emit(layout, describe(seamNode)), containerAttrMap(seamNode));
            }

            public static String toMathML(String latex) {
                return containRender(() -> mathmlDocument(MathParser.parse(latex)));
            }

            private static String mathmlDocument(MathNode node) {
                return "<math>" + toMathML(node) + "</math>";
            }

            private static String toMathML(MathNode node) {
                return "<mi>" + describe(node) + "</mi>";
            }

            private static String mathmlOrEmpty(MathNode node) {
                try {
                    return mathmlDocument(node);
                } catch (RuntimeException | StackOverflowError e) {
                    return "";
                }
            }

            private static String openTag(MathNode node) {
                return "<span class=lx-math" + fxAttrs(node) + ">";
            }

            private static String fxAttrs(MathNode node) {
                return " data-lx-fx-hover=" + node;
            }

            private static java.util.Map<String, String> containerAttrMap(MathNode node) {
                return java.util.Map.of();
            }

            static String describe(MathNode node) {
                return String.valueOf(node);
            }

            public static String fxRuntimeJs() {
                return FxRuntimeHolder.JS;
            }

            public static String fxStylesCss() {
                return FxStylesHolder.CSS;
            }

            private static final class FxRuntimeHolder {
                static final String JS = bundledResource("/com/lattex/fx/lattex-fx.js");
            }

            private static final class FxStylesHolder {
                static final String CSS = bundledResource("/com/lattex/fx/lattex-fx.css");
            }

            private static String bundledResource(String path) {
                return path;
            }

            public record RenderedMath(String svg, java.util.Map<String, String> containerAttrs) {
                public RenderedMath {
                    java.util.Objects.requireNonNull(svg, "svg");
                }
            }
        }
        """;
}
