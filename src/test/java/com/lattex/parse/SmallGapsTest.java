package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.Negated;
import com.lattex.parse.MathNode.SizedDelim;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The small LaTeX gaps the research corpus still hit after five plans landed
 * (plan fc988bc4): {@code @{...}} array column specs, the {@code \big} family over
 * arrow delimiters, {@code \not} over a relation with no precomposed negation, and
 * the missing amssymb negated-relation names. Measured on main 1c3dd3f over
 * 138,172 research display formulas (288 real gaps); every formula below quoted
 * as a corpus example is verbatim from that run.
 */
class SmallGapsTest {

    private static String assertRenders(String latex) {
        String svg = assertDoesNotThrow(() -> LatteX.render(latex), latex);
        assertTrue(svg.contains("<path"), "renders glyph ink: " + latex);
        return svg;
    }

    private static final Pattern VIEWBOX = Pattern.compile(
        "viewBox=\"[-0-9.]+ [-0-9.]+ ([0-9.]+) ([0-9.]+)\"");

    private static double width(String svg) {
        Matcher m = VIEWBOX.matcher(svg);
        assertTrue(m.find(), "svg has a viewBox");
        return Double.parseDouble(m.group(1));
    }

    // ------------------------------------------------------------------
    // @{...} and !{...} column specs (19 corpus refusals)
    // ------------------------------------------------------------------

    @Test
    void atExpressionsInArrayColumnSpecsRender() {
        assertRenders("\\begin{array}{c@{\\qquad}c@{\\qquad}l} \\text{sign change of }\\log r_{hi} "
            + "&\\text{stable directions}&\\text{condition at the cut}\\\\[2pt] +\\ \\longrightarrow\\ - "
            + "&\\longleftarrow\\ N\\ \\longrightarrow &\\text{prescribe }u_{hi}(N)=0,\\\\[2pt] "
            + "-\\ \\longrightarrow\\ + &\\longrightarrow\\ N\\ \\longleftarrow "
            + "&\\text{match the incoming values}. \\end{array}");
        assertRenders("\\begin{array}{c|c@{\\qquad}c|c} \\text{corner}&\\text{basis}&\\text{corner}"
            + "&\\text{basis}\\\\ \\hline eCe&e,x,y,z&eCf&u,v\\\\ fCe&t,j&fCf&f,n. \\end{array}");
        assertRenders("\\begin{array}{c@{\\quad\\longrightarrow\\quad}l} \\text{irreducible genus bound}"
            + "&\\text{even and odd coefficient bounds},\\\\[2pt] \\text{reducible cyclotomic bound}"
            + "&\\text{even coefficients, then bounded companions}. \\end{array}");
        assertRenders("\\begin{array}{rcl@{\\qquad}l} u&\\leftarrow&u-c[\\,w\\equiv0\\pmod2\\,],&(1)\\\\ "
            + "w&\\leftarrow&w-c[\\,u\\equiv0\\pmod2\\,],&(2) \\end{array}");
        assertRenders("\\begin{array}[b]{c|l@{\\qquad}c|l} n & a & n & a\\\\ \\hline 2 & \\texttt{++} "
            + "& 7 & \\texttt{+++{-}{-}+{-}} \\end{array}");
    }

    @Test
    void atExpressionIsCarriedAsBoundaryMaterialNotAColumn() {
        Matrix m = assertInstanceOf(Matrix.class,
            MathParser.parse("\\begin{array}{@{}c@{\\quad\\to\\quad}l@{}}a&b\\end{array}"));
        assertEquals(2, m.columnCount(), "@-material adds no column");
        List<MathNode.ColumnSeparator> seps = m.columnSeparators();
        assertEquals(3, seps.size(), "one slot per column boundary");
        assertNotNull(seps.get(0), "@{} at the left edge suppresses the edge space");
        assertNotNull(seps.get(1), "@{...} between the columns");
        assertNotNull(seps.get(2), "@{} at the right edge");
        assertTrue(!seps.get(1).keepsPadding(), "@ replaces the intercolumn space");
        // !{...} inserts material but keeps the intercolumn space.
        Matrix bang = assertInstanceOf(Matrix.class,
            MathParser.parse("\\begin{array}{c!{:}c}a&b\\end{array}"));
        assertTrue(bang.columnSeparators().get(1).keepsPadding(), "! keeps the space");
        // A plain spec carries no material at any boundary.
        Matrix plain = assertInstanceOf(Matrix.class,
            MathParser.parse("\\begin{array}{c|c}a&b\\end{array}"));
        assertTrue(plain.columnSeparators().stream().allMatch(s -> s == null));
    }

    @Test
    void atMaterialIsVisibleAndChangesTheWidth() {
        double plain = width(LatteX.render("\\begin{array}{cc}a&b\\end{array}"));
        double tight = width(LatteX.render("\\begin{array}{@{}c@{}c@{}}a&b\\end{array}"));
        double wide = width(LatteX.render("\\begin{array}{c@{\\qquad\\qquad}c}a&b\\end{array}"));
        assertTrue(tight < plain, "@{} removes the intercolumn and edge space");
        assertTrue(wide > plain, "@{\\qquad\\qquad} is wider than the 1em default");
        // The arrow drawn by @{\to} is real ink: more glyphs than the two cells.
        String arrow = LatteX.render("\\begin{array}{c@{\\to}c}a&b\\end{array}");
        String noArrow = LatteX.render("\\begin{array}{c@{}c}a&b\\end{array}");
        assertTrue(count(arrow, "<path") > count(noArrow, "<path"), "the @-material is drawn");
    }

    @Test
    void atMaterialIsDrawnOnEveryRow() {
        String one = LatteX.render("\\begin{array}{c@{\\to}c}a&b\\end{array}");
        String two = LatteX.render("\\begin{array}{c@{\\to}c}a&b\\\\c&d\\end{array}");
        assertEquals(count(one, "<path") + 3, count(two, "<path"),
            "the second row adds its two cells and its own copy of the material");
    }

    @Test
    void atSpecFailuresAreLoud() {
        MathSyntaxException noBrace = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}{c@c}a&b\\end{array}"));
        assertTrue(noBrace.getMessage().contains("@"), noBrace.getMessage());
        MathSyntaxException open = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}{c@{\\quad c}a&b\\end{array}"));
        assertNotNull(open.getMessage());
        // Two @-expressions at one boundary: TeX would concatenate; LatteX refuses loudly.
        MathSyntaxException twice = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}{c@{a}@{b}c}a&b\\end{array}"));
        assertTrue(twice.getMessage().contains("boundary"), twice.getMessage());
        MathSyntaxException ruleAndAt = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}{c|@{a}c}a&b\\end{array}"));
        assertTrue(ruleAndAt.getMessage().contains("boundary"), ruleAndAt.getMessage());
        // A still-unsupported column type keeps its message, now naming @ and ! too.
        MathSyntaxException p = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}{p{3cm}}a\\end{array}"));
        assertTrue(p.getMessage().contains("'p'") && p.getMessage().contains("@{...}"),
            p.getMessage());
    }

    @Test
    void atMaterialReachesMathMLAndTheSpokenLabel() {
        String mml = LatteX.toMathML("\\begin{array}{c@{\\to}c}a&b\\end{array}");
        assertTrue(mml.contains("→"), "the @-material arrow is in the MathML: " + mml);
        assertEquals(2, count(mml, "<mtd>"), "no extra column: " + mml);
    }

    // ------------------------------------------------------------------
    // \big family over arrows (13 corpus refusals)
    // ------------------------------------------------------------------

    @Test
    void bigFamilyTakesArrowDelimiters() {
        assertRenders("\\begin{array}{ccc} G &\\xrightarrow{\\rho}& K_4(S;5)\\\\ \\big\\downarrow "
            + "&& \\big\\downarrow\\\\ K_4(\\overline N)&\\xrightarrow{\\rho}&K_4(\\overline N;5). "
            + "\\end{array}");
        assertRenders("\\begin{array}{ccc} C^N=C^d\\times C^{d'} &\\xrightarrow{\\ \\mu_d\\ }& X\\\\[3pt] "
            + "{\\scriptstyle s_\\xi\\circ\\mu_N}\\Big\\downarrow &&\\Big\\downarrow{\\scriptstyle f}"
            + "\\\\[3pt] F_n^N&\\xrightarrow{\\ q\\ }&F_n^N . \\end{array}");
        assertRenders("D_I^\\circ= \\Bigl(\\bigcap_{i\\in I}D_i\\Bigr) \\mathbin{\\big\\backslash}"
            + "\\Bigl(\\bigcup_{j\\notin I}D_j\\Bigr).");
        Map<String, Integer> arrows = new LinkedHashMap<>();
        arrows.put("uparrow", 0x2191);
        arrows.put("downarrow", 0x2193);
        arrows.put("updownarrow", 0x2195);
        arrows.put("Uparrow", 0x21D1);
        arrows.put("Downarrow", 0x21D3);
        arrows.put("Updownarrow", 0x21D5);
        arrows.put("backslash", 0x5C);
        for (Map.Entry<String, Integer> e : arrows.entrySet()) {
            for (String size : List.of("big", "Big", "bigg", "Bigg", "bigl", "Bigr", "biggm")) {
                SizedDelim d = assertInstanceOf(SizedDelim.class,
                    MathParser.parse("\\" + size + "\\" + e.getKey()), size + e.getKey());
                assertEquals(e.getValue(), d.delimCp(), "\\" + size + "\\" + e.getKey());
            }
            assertDoesNotThrow(() -> LatteX.render("\\left\\" + e.getKey() + " x \\right."));
        }
        // Each arrow is taller under a bigger size: the stretch construction is used.
        double prev = 0;
        for (String size : List.of("big", "Big", "bigg", "Bigg")) {
            String svg = LatteX.render("\\" + size + "\\Downarrow");
            double h = height(svg);
            assertTrue(h > prev, "\\" + size + "\\Downarrow grows: " + h + " vs " + prev);
            prev = h;
        }
    }

    // ------------------------------------------------------------------
    // \not overstrike (12 corpus refusals)
    // ------------------------------------------------------------------

    @Test
    void notPrefersThePrecomposedNegation() {
        assertRenders("F^{2m}N_m\\simeq0,\\qquad F^{2m-2}N_m\\not\\simeq0.");
        assertEquals(new Atom(0x2244, MathClass.REL), MathParser.parse("\\not\\simeq"));
        assertEquals(new Atom(0x22E2, MathClass.REL), MathParser.parse("\\not\\sqsubseteq"));
        assertEquals(new Atom(0x2260, MathClass.REL), MathParser.parse("\\not="),
            "an existing precomposed negation is unchanged");
        assertRenders("\\begin{gathered} mb=ae,\\qquad m\\not\\sqsubseteq a,\\qquad "
            + "\\downarrow m\\cap\\downarrow a=U,\\\\ n\\vee m=ae\\quad(n\\sqsubseteq a,\\ n\\notin U). "
            + "\\end{gathered}");
    }

    @Test
    void notOverstrikesARelationWithNoPrecomposedNegation() {
        assertRenders("ij\\in E(G)\\quad\\Longleftrightarrow\\quad a_i\\perp b_j\\ \\hbox{ and }\\ "
            + "a_j\\not\\perp b_i.");
        assertRenders("\\mathcal K_i(a)= \\bigcap_{\\Pr(b\\not\\perp z\\mid a)\\le\\rho} z^\\perp.");
        Negated n = assertInstanceOf(Negated.class, MathParser.parse("\\not\\perp"));
        assertEquals(new Atom(0x22A5, MathClass.REL), n.body());
        // TeX's \not applies to any following atom; physics writes \not D for the slashed D.
        assertRenders("\\not D_{\\mathcal A}\\psi=0, \\qquad F_{\\mathcal A}^{+} "
            + "=r\\,\\mathfrak q_g(\\psi)-\\frac{ir}{4}\\eta, \\qquad r\\ge1.");
        Negated d = assertInstanceOf(Negated.class, MathParser.parse("\\not D"));
        assertEquals(MathClass.ORD, ((Atom) d.body()).mathClass());
        // The overstrike is real ink: the slash glyph U+0338 is drawn.
        String struck = LatteX.render("a\\not\\perp b");
        String plain = LatteX.render("a\\perp b");
        assertEquals(count(plain, "<path") + 1, count(struck, "<path"), "one slash added");
        assertEquals(width(plain), width(struck), 1e-6, "\\not is zero-width: same advance");
    }

    @Test
    void notOverstrikeMathMLUsesTheCombiningLongSolidus() {
        String mml = LatteX.toMathML("a\\not\\perp b");
        assertTrue(mml.contains("<mo>⊥̸</mo>"), mml);
        assertTrue(LatteX.toMathML("\\not\\simeq").contains("<mo>≄</mo>"),
            "precomposed stays precomposed");
    }

    @Test
    void notStillFailsLoudWithNothingToNegate() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("a\\not"));
    }

    // ------------------------------------------------------------------
    // Negated-relation census
    // ------------------------------------------------------------------

    /** name -> {code point, the Unicode character name that code point must carry}. */
    static final Map<String, Object[]> PRECOMPOSED = new LinkedHashMap<>();

    static {
        put("nless", 0x226E, "NOT LESS-THAN");
        put("ngtr", 0x226F, "NOT GREATER-THAN");
        put("nleq", 0x2270, "NEITHER LESS-THAN NOR EQUAL TO");
        put("ngeq", 0x2271, "NEITHER GREATER-THAN NOR EQUAL TO");
        put("lneq", 0x2A87, "LESS-THAN AND SINGLE-LINE NOT EQUAL TO");
        put("gneq", 0x2A88, "GREATER-THAN AND SINGLE-LINE NOT EQUAL TO");
        put("lneqq", 0x2268, "LESS-THAN BUT NOT EQUAL TO");
        put("gneqq", 0x2269, "GREATER-THAN BUT NOT EQUAL TO");
        put("lnsim", 0x22E6, "LESS-THAN BUT NOT EQUIVALENT TO");
        put("gnsim", 0x22E7, "GREATER-THAN BUT NOT EQUIVALENT TO");
        put("lnapprox", 0x2A89, "LESS-THAN AND NOT APPROXIMATE");
        put("gnapprox", 0x2A8A, "GREATER-THAN AND NOT APPROXIMATE");
        put("nlesssim", 0x2274, "NEITHER LESS-THAN NOR EQUIVALENT TO");
        put("ngtrsim", 0x2275, "NEITHER GREATER-THAN NOR EQUIVALENT TO");
        put("nlessgtr", 0x2278, "NEITHER LESS-THAN NOR GREATER-THAN");
        put("ngtrless", 0x2279, "NEITHER GREATER-THAN NOR LESS-THAN");
        put("nprec", 0x2280, "DOES NOT PRECEDE");
        put("nsucc", 0x2281, "DOES NOT SUCCEED");
        put("npreceq", 0x22E0, "DOES NOT PRECEDE OR EQUAL");
        put("nsucceq", 0x22E1, "DOES NOT SUCCEED OR EQUAL");
        put("precneqq", 0x2AB5, "PRECEDES ABOVE NOT EQUAL TO");
        put("succneqq", 0x2AB6, "SUCCEEDS ABOVE NOT EQUAL TO");
        put("precnsim", 0x22E8, "PRECEDES BUT NOT EQUIVALENT TO");
        put("succnsim", 0x22E9, "SUCCEEDS BUT NOT EQUIVALENT TO");
        put("precnapprox", 0x2AB9, "PRECEDES ABOVE NOT ALMOST EQUAL TO");
        put("succnapprox", 0x2ABA, "SUCCEEDS ABOVE NOT ALMOST EQUAL TO");
        put("nsim", 0x2241, "NOT TILDE");
        put("nsimeq", 0x2244, "NOT ASYMPTOTICALLY EQUAL TO");
        put("ncong", 0x2247, "NEITHER APPROXIMATELY NOR ACTUALLY EQUAL TO");
        put("napprox", 0x2249, "NOT ALMOST EQUAL TO");
        put("nasymp", 0x226D, "NOT EQUIVALENT TO");
        put("nequiv", 0x2262, "NOT IDENTICAL TO");
        put("nmid", 0x2224, "DOES NOT DIVIDE");
        put("nparallel", 0x2226, "NOT PARALLEL TO");
        put("nvdash", 0x22AC, "DOES NOT PROVE");
        put("nvDash", 0x22AD, "NOT TRUE");
        put("nVdash", 0x22AE, "DOES NOT FORCE");
        put("nVDash", 0x22AF, "NEGATED DOUBLE VERTICAL BAR DOUBLE RIGHT TURNSTILE");
        put("ntriangleleft", 0x22EA, "NOT NORMAL SUBGROUP OF");
        put("ntriangleright", 0x22EB, "DOES NOT CONTAIN AS NORMAL SUBGROUP");
        put("ntrianglelefteq", 0x22EC, "NOT NORMAL SUBGROUP OF OR EQUAL TO");
        put("ntrianglerighteq", 0x22ED, "DOES NOT CONTAIN AS NORMAL SUBGROUP OR EQUAL");
        put("nsubset", 0x2284, "NOT A SUBSET OF");
        put("nsupset", 0x2285, "NOT A SUPERSET OF");
        put("nsubseteq", 0x2288, "NEITHER A SUBSET OF NOR EQUAL TO");
        put("nsupseteq", 0x2289, "NEITHER A SUPERSET OF NOR EQUAL TO");
        put("subsetneq", 0x228A, "SUBSET OF WITH NOT EQUAL TO");
        put("supsetneq", 0x228B, "SUPERSET OF WITH NOT EQUAL TO");
        put("subsetneqq", 0x2ACB, "SUBSET OF ABOVE NOT EQUAL TO");
        put("supsetneqq", 0x2ACC, "SUPERSET OF ABOVE NOT EQUAL TO");
        put("nsqsubseteq", 0x22E2, "NOT SQUARE IMAGE OF OR EQUAL TO");
        put("nsqsupseteq", 0x22E3, "NOT SQUARE ORIGINAL OF OR EQUAL TO");
        put("notin", 0x2209, "NOT AN ELEMENT OF");
        put("nni", 0x220C, "DOES NOT CONTAIN AS MEMBER");
        put("notni", 0x220C, "DOES NOT CONTAIN AS MEMBER");
        put("neq", 0x2260, "NOT EQUAL TO");
    }

    private static void put(String name, int cp, String unicodeName) {
        PRECOMPOSED.put(name, new Object[] {cp, unicodeName});
    }

    /** amssymb names with NO precomposed code point: an overstrike over this base. */
    static final Map<String, Object[]> OVERSTRUCK = new LinkedHashMap<>();

    static {
        OVERSTRUCK.put("nleqslant", new Object[] {0x2A7D, "LESS-THAN OR SLANTED EQUAL TO"});
        OVERSTRUCK.put("ngeqslant", new Object[] {0x2A7E, "GREATER-THAN OR SLANTED EQUAL TO"});
        OVERSTRUCK.put("nleqq", new Object[] {0x2266, "LESS-THAN OVER EQUAL TO"});
        OVERSTRUCK.put("ngeqq", new Object[] {0x2267, "GREATER-THAN OVER EQUAL TO"});
        OVERSTRUCK.put("nsubseteqq", new Object[] {0x2AC5, "SUBSET OF ABOVE EQUALS SIGN"});
        OVERSTRUCK.put("nsupseteqq", new Object[] {0x2AC6, "SUPERSET OF ABOVE EQUALS SIGN"});
    }

    @Test
    void censusEveryNegatedRelationNameIsAPrecomposedRelation() {
        SfntFont font = SfntFont.loadBundled();
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : PRECOMPOSED.entrySet()) {
            String name = e.getKey();
            int cp = (Integer) e.getValue()[0];
            String unicodeName = (String) e.getValue()[1];
            if (!unicodeName.equals(Character.getName(cp))) {
                wrong.add(name + ": U+" + Integer.toHexString(cp) + " is " + Character.getName(cp));
            }
            if (font.glyphId(cp) == 0) {
                wrong.add(name + ": STIX Two Math has no glyph for U+" + Integer.toHexString(cp));
            }
            MathNode parsed;
            try {
                parsed = MathParser.parse("\\" + name);
            } catch (MathSyntaxException ex) {
                wrong.add(name + ": " + ex.getMessage());
                continue;
            }
            if (!new Atom(cp, MathClass.REL).equals(parsed)) {
                wrong.add(name + ": parsed to " + parsed);
            }
            // The PASTED glyph classifies as a relation too (pasted U+2244 used to be Ord).
            if (Symbols.classForCodePoint(cp) != MathClass.REL) {
                wrong.add(name + ": pasted U+" + Integer.toHexString(cp) + " is "
                    + Symbols.classForCodePoint(cp));
            }
        }
        assertTrue(wrong.isEmpty(), "negated-relation census: " + wrong);
    }

    @Test
    void censusEveryOverstruckNegationNameStrikesItsBase() {
        SfntFont font = SfntFont.loadBundled();
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : OVERSTRUCK.entrySet()) {
            int base = (Integer) e.getValue()[0];
            if (!e.getValue()[1].equals(Character.getName(base)) || font.glyphId(base) == 0) {
                wrong.add(e.getKey() + ": base U+" + Integer.toHexString(base));
            }
            MathNode parsed;
            try {
                parsed = MathParser.parse("\\" + e.getKey());
            } catch (MathSyntaxException ex) {
                wrong.add(e.getKey() + ": " + ex.getMessage());
                continue;
            }
            if (!new Negated(new Atom(base, MathClass.REL)).equals(parsed)) {
                wrong.add(e.getKey() + ": parsed to " + parsed);
            }
        }
        assertTrue(wrong.isEmpty(), "overstruck census: " + wrong);
        assertNotNull(CommandRegistry.get("nleqslant"), "registered, so suggestion and index see it");
        assertNull(CommandRegistry.get("nleqslantx"), "control: a near-miss stays unknown");
    }

    @Test
    void censusEveryNotTargetIsAPastedRelation() {
        // Every precomposed negation \not can produce is itself a relation when pasted.
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : Symbols.NEGATION.entrySet()) {
            MathClass base = Symbols.classForCodePoint(e.getKey());
            if (e.getKey() < 0x80) {
                continue; // ASCII = < > are classified by the parser's own switch
            }
            if (Symbols.classForCodePoint(e.getValue()) != MathClass.REL) {
                wrong.add("U+" + Integer.toHexString(e.getValue()) + " pasted is not a relation");
            }
            if (Symbols.classForCodePoint(e.getValue()) != base) {
                wrong.add("U+" + Integer.toHexString(e.getValue()) + " is "
                    + Symbols.classForCodePoint(e.getValue()) + ", its base is " + base);
            }
        }
        assertTrue(wrong.isEmpty(), "pasted negation classes: " + wrong);
        // The three bases that had no command (so their pasted glyph was an Ord).
        assertEquals(new Atom(0x227C, MathClass.REL), MathParser.parse("\\preccurlyeq"));
        assertEquals(new Atom(0x227D, MathClass.REL), MathParser.parse("\\succcurlyeq"));
        assertEquals(new Atom(0x22AB, MathClass.REL), MathParser.parse("\\VDash"));
        assertEquals("PRECEDES OR EQUAL TO", Character.getName(0x227C));
        assertEquals("SUCCEEDS OR EQUAL TO", Character.getName(0x227D));
        assertEquals("DOUBLE VERTICAL BAR DOUBLE RIGHT TURNSTILE", Character.getName(0x22AB));
        assertEquals(new Atom(0x22E0, MathClass.REL), MathParser.parse("\\not\\preccurlyeq"));
    }

    @Test
    void aNegatedRelationGetsRelationSpacingOnBothSides() {
        for (String rel : List.of("\\nsimeq", "≄", "\\napprox", "≉", "\\nequiv", "≢",
                "\\nleqslant", "\\not\\perp", "\\npreceq", "⋠")) {
            double spaced = width(LatteX.render("a" + rel + " b"));
            // An Ord-classed copy of the SAME ink: no relation glue on either side.
            double ord = width(LatteX.render("a\\mathord{" + rel + "} b"));
            double neq = width(LatteX.render("a\\neq b"));
            double neqOrd = width(LatteX.render("a\\mathord{\\neq}b"));
            assertEquals(neq - neqOrd, spaced - ord, 1e-6,
                rel + " gets the same two thick spaces a \\neq gets");
            assertTrue(spaced - ord > 0, rel);
        }
    }

    // ------------------------------------------------------------------

    private static double height(String svg) {
        Matcher m = VIEWBOX.matcher(svg);
        assertTrue(m.find(), "svg has a viewBox");
        return Double.parseDouble(m.group(2));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
