package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.RenderResult;
import com.lattex.font.SfntFont;
import java.text.Normalizer;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/// Plan 14579fe4 (lattex project), part (a), ruling stafficy/35638: a Unicode script
/// character the bundled font cannot draw becomes a REAL script of its base character,
/// instead of the missing-glyph box it drew with a success exit on main 5cb804f7 (found on
/// the crew's showcase pages: x with a subscript i, j, u or v).
class ScriptLetterRewriteTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static String pp(String latex) {
        return MathParserTest.pp(MathParser.parse(latex));
    }

    @Test
    void theCrewsCharactersSetAsRealScripts() {
        assertEquals(pp("x_{i}"), pp("x\u1D62"));
        assertEquals(pp("x_{j}"), pp("x\u2C7C"));
        assertEquals(pp("x_{u}"), pp("x\u1D64"));
        assertEquals(pp("x_{v}"), pp("x\u1D65"));
        assertEquals(pp("x_{n}"), pp("x\u2099"));
        assertEquals(pp("A^{T}"), pp("A\u1D40"));
        assertEquals(pp("e^{\\gamma}"), pp("e\u1D5E"));
        for (String s : new String[] {"x\u1D62", "x\u2C7C", "A\u1D40", "e\u1D5E", "x\u2090\u2097\u2098"}) {
            RenderResult r = LatteX.renderWithDiagnostics(s);
            assertEquals("", r.diagnostics().detail(), s + " must draw no missing-glyph box");
        }
    }

    @Test
    void adjacentScriptLettersOfOneKindFormOneScript() {
        assertEquals(pp("x_{ij}"), pp("x\u1D62\u2C7C"));
        assertEquals(pp("A^{Ta}"), pp("A\u1D40\u1D43"));
    }

    @Test
    void aMixedClusterPutsBothScriptsOnTheSameBase() {
        // Lattice review of 4d0f03a: these put the second script on the first
        assertEquals(pp("x^{2}_{i}"), pp("x\u00B2\u1D62"));
        assertEquals(pp("x^{a}_{2}"), pp("x\u1D43\u2082"));
        assertEquals(pp("x^{T}_{i}"), pp("x\u1D62\u1D40"));
    }

    @Test
    void aLongClusterTheFontDrawsWholeIsLexedInOnePass() {
        // a cluster with no rewrite keeps its own CHAR tokens and their own offsets. Sized so
        // the quadratic first commit (4d0f03a: 3.1 s on 20,000) cannot pass: 100,000
        // characters take it over a minute, and the one-pass lexer milliseconds (Fixpoint
        // review of 6160f84: the old 20,000 / 5,000 ms did not pin the fix).
        String run = "\u00B2".repeat(100_000);
        var tokens = Assertions.assertTimeoutPreemptively(Duration.ofMillis(1500), () -> MathParser.lex("x" + run));
        assertEquals(100_002, tokens.size(), "x, every script character, EOF");
        assertTrue(pp("x" + "\u00B2".repeat(20000)).startsWith("L(A(x,ORD) A(U+00B2,ORD)"));
        assertEquals(1, MathParser.lex("x\u00B2\u00B3").get(1).offset());
        assertEquals(2, MathParser.lex("x\u00B2\u00B3").get(2).offset());
    }

    @Test
    void aScriptDigitInARunThatNeedsTheRewriteMovesWithIt() {
        assertEquals(pp("x^{a2}"), pp("x\u1D43\u00B2"));
        assertEquals(pp("x^{2a}"), pp("x\u00B2\u1D43"));
    }

    @Test
    void aRunTheFontDrawsWholeIsLeftExactlyAsBefore() {
        // the font's own superscript two: a glyph at full size, not a real script
        assertNotEquals(pp("x^{2}"), pp("x\u00B2"));
        assertTrue(pp("x\u00B2").contains("U+00B2"), pp("x\u00B2"));
        assertTrue(pp("x\u207F").contains("U+207F"), pp("x\u207F"));
        assertTrue(pp("x\u2081\u2082").contains("U+2081"), pp("x\u2081\u2082"));
    }

    @Test
    void withNothingBeforeItTheScriptAttachesToAnEmptyBase() {
        assertEquals(pp("{}_{i}x"), pp("\u1D62x"));
        assertEquals(pp("\\frac{{}_{i}}{2}"), pp("\\frac{\u1D62}{2}"));
        assertEquals(pp("\\left({}_{i}\\right)"), pp("\\left(\u1D62\\right)"));
        assertEquals(pp("\\begin{matrix}a&{}_{i}\\end{matrix}"), pp("\\begin{matrix}a&\u1D62\\end{matrix}"));
        // a TYPED script with no nucleus still fails, as before
        org.junit.jupiter.api.Assertions.assertThrows(MathSyntaxException.class, () -> MathParser.parse("_{i}x"));
    }

    // ---- the rewrite may only improve (Fixpoint review of 6160f84, stafficy/35720) -------

    /** Fixpoint's six inputs: main draws each with a box and exit 0; the first branch refused them. */
    @Test
    void fixpointsSixRefusalsNowRenderAsRealScripts() {
        for (String s : new String[] {"x_1\u1D62", "x\u1D62_j", "A^{-1}\u1D40", "x\u1D62 \u2C7C", "x^\u1D62",
                "\\frac1\u1D62"}) {
            RenderResult r = LatteX.renderWithDiagnostics(s);
            assertEquals(com.lattex.api.Outcome.OK, r.diagnostics().outcome(), s + ": " + r.diagnostics().message());
            assertEquals("", r.diagnostics().detail(), s + " draws no box");
        }
        // where it cannot attach, the cluster is its own nucleus: an empty base with its script
        assertEquals(pp("x_1{}_{i}"), pp("x_1\u1D62"));
        // the typed _j attaches to the cluster's own nucleus, as it attached to main's box
        // ({}_{i} then _j has no explicit spelling: it would be a double subscript)
        assertEquals("L(A(x,ORD) SS(SS(L(),_A(i,ORD)),_A(j,ORD)))", pp("x\u1D62_j"));
        assertEquals(pp("x^{{}_{i}}"), pp("x^\u1D62"));
        // a typed script directly after the cluster makes it its own nucleus, which takes it, as
        // the raw character did: decided by one token of lookahead (Lattice review of c3c9e60)
        assertEquals("L(A(x,ORD) SS(SS(L(),_A(i,ORD)),^A(2,ORD)))", pp("x\u1D62^2"));
        assertEquals(pp("x_{i}^{2}"), pp("x\u1D62\u00B2"), "the cluster's own superscript still joins it");
    }

    /**
     * The rule, over a corpus: for every template, every one of the 163 script characters and
     * three cluster shapes (alone, then a drawable superscript two, after one), if main renders
     * it, this renders it. Main drew a script character as one ordinary atom, so main's outcome
     * is modelled by the same input with an ordinary non-letter atom (U+2202) in its place,
     * which this branch parses exactly as main parsed the character. The templates cover list
     * positions, every argument-reading site (Lattice review of b608ecd: \\frac took two raw
     * characters as its two arguments, and a following explicit-script cluster must be read
     * whole) and the raw-text argument sites (\\mathrm unbraced).
     */
    @Test
    void renderedOnMainImpliesRenderedHere() {
        String[] templates = {"x@", "x_1@", "x^1@", "x_1^2@", "x@_j", "x@^j", "x@_j^3", "x@^3_j", "x^1@^2", "x_1@_2",
            "x@ @", "x^@", "x_@", "x^@@", "x^{2}@", "\\frac1@", "\\frac@2", "\\frac@@", "\\frac12@_j^3", "\\sqrt@",
            "\\sqrt[3]@", "\\hat@", "\\mathbf@", "\\mathrm@", "\\binom@@", "\\overset@=", "@x", "@", "{@}", "x'@",
            "x@'", "@_1", "@^1", "\\left(@\\right)", "\\sum@", "\\sum_1@", "\\sum@_1", "\\sum^1@", "\\sum@_j^3",
            "\\int@^b", "\\underbrace{x}_1@", "\\underbrace{x}@_1", "\\overbrace{x}^1@", "\\overbrace{x}@^1_0",
            "\\begin{matrix}a&@\\\\@&b\\end{matrix}", "x@@", "x@\\,@"};
        String[] shapes = {"@", "@\u00B2", "\u00B2@"};
        int checked = 0;
        for (String tpl : templates) {
            for (String shape : shapes) {
                String t = tpl.replace("@", shape);
                boolean mainRenders;
                try {
                    MathParser.parse(t.replace("@", "\u2202"));
                    mainRenders = true;
                } catch (MathSyntaxException e) {
                    mainRenders = false;
                }
                if (!mainRenders) {
                    continue;
                }
                for (int cp : MathParser.SCRIPT_CHARS.keySet()) {
                    String in = t.replace("@", new String(Character.toChars(cp)));
                    try {
                        MathParser.parse(in);
                    } catch (MathSyntaxException e) {
                        throw new AssertionError(String.format("U+%04X in %s: main renders it, this refuses: %s",
                            cp, t, e.getMessage()), e);
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 20_000, "the corpus is not vacuous: " + checked + " pairs checked");
        for (String s : new String[] {"\\frac\u00B2\u1D62", "\\frac12\u1D40_j^3", "\\mathrm\u1D40", "\\mathrm\u00B2\u1D62"}) {
            assertEquals(com.lattex.api.Outcome.OK, LatteX.renderWithDiagnostics(s).diagnostics().outcome(), s);
        }
        // the argument sites take ONE character each, as main did
        assertEquals(pp("\\frac{{}^{2}}{{}_{i}}"), pp("\\frac\u00B2\u1D62"));
        // and a following explicit-script cluster is read whole: the raw T takes both scripts
        assertEquals("L(Frac(A(1,ORD),A(2,ORD)) SS(SS(L(),^A(T,ORD)),^A(3,ORD),_A(j,ORD)))", pp("\\frac12\u1D40_j^3"));
    }

    @Test
    void nestedClustersBeforeTypedScriptsParseInLinearWork() {
        // Lattice review of c3c9e60: with a rewind, a cluster attached, a NON-colliding typed
        // script's argument was parsed, a colliding one rewound and that argument was parsed
        // again, so nesting y, subscript i, ^{...}, _j doubled the work per level. Measured on
        // c3c9e60's jar: 23 levels 1.29 s, 25 levels 4.50 s, 26 levels 8.45 s; this tree 0.08 s
        // at each. Twenty-six levels stay inside the 80-level nesting limit.
        String src = "y\u1D62^{".repeat(26) + "y" + "}_j".repeat(26);
        var tree = Assertions.assertTimeoutPreemptively(Duration.ofMillis(1500), () -> MathParser.parse(src));
        assertTrue(MathParserTest.pp(tree).startsWith("L(A(y,ORD) SS(SS(L(),_A(i,ORD))"),
            "each cluster is its own nucleus, taking ^{...}_j as the raw character did");
    }

    @Test
    void textModeIsNotRewritten() {
        // a text run is taken raw; the rewrite is a math-mode lexer rule
        assertEquals("Txt[ROMAN](a\u1D62b)", pp("\\text{a\u1D62b}"));
    }

    @Test
    void theTableMatchesUnicodeAndTheBundledFont() {
        int rewritten = 0;
        int kept = 0;
        for (var e : MathParser.SCRIPT_CHARS.entrySet()) {
            int cp = e.getKey();
            int kind = e.getValue()[0];
            int base = e.getValue()[1];
            boolean flagged = e.getValue()[2] == 1;
            boolean neither = e.getValue()[2] == 2;
            String ch = new String(Character.toChars(cp));
            String b = new String(Character.toChars(base));
            String at = String.format("U+%04X", cp);
            assertEquals(b, Normalizer.normalize(ch, Normalizer.Form.NFKD), at + ": base is its compatibility decomposition");
            String name = Character.getName(cp);
            assertEquals(kind == 1, name.contains("SUBSCRIPT"), at + " " + name + ": kind agrees with the name");
            boolean fontLacks = FONT.glyphId(cp) == 0;
            boolean baseDraws = LatteX.renderWithDiagnostics(b).diagnostics().detail().isEmpty();
            assertEquals(fontLacks && baseDraws, flagged, at + ": rewritten exactly when the font lacks it and draws its base");
            assertEquals(fontLacks && !baseDraws, neither, at + ": kept as typed exactly when the font draws neither");
            if (neither) {
                kept++;
            }
            if (flagged) {
                rewritten++;
                assertEquals(pp("x" + (kind == 0 ? "^" : "_") + "{" + b + "}"), pp("x" + ch), at);
            } else {
                assertTrue(pp("x" + ch).contains(String.format("U+%04X", cp)), at + " kept as the font's glyph");
            }
        }
        assertEquals(163, MathParser.SCRIPT_CHARS.size(), "Unicode's one-character <super>/<sub> decompositions in U+00A0-U+2DFF");
        assertEquals(103, rewritten, "of which the bundled font lacks the character and draws the base");
        assertEquals(10, kept, "and of which it draws neither: kept as typed, so a box names what was typed");
    }

    @Test
    void noScriptCharacterTheTableMissesInTheRange() {
        // every code point named as a superscript, subscript or modifier small/capital letter
        // whose compatibility decomposition is one other character is in the table
        for (int cp = 0x00A0; cp < 0x2E00; cp++) {
            String name = Character.getName(cp);
            if (name == null || !(name.startsWith("SUPERSCRIPT ") || name.contains("SUBSCRIPT ")
                    || name.startsWith("MODIFIER LETTER SMALL ") || name.startsWith("MODIFIER LETTER CAPITAL "))) {
                continue;
            }
            String ch = new String(Character.toChars(cp));
            String d = Normalizer.normalize(ch, Normalizer.Form.NFKD);
            if (d.equals(ch) || d.codePointCount(0, d.length()) != 1) {
                continue;
            }
            assertTrue(MathParser.SCRIPT_CHARS.containsKey(cp), String.format("U+%04X %s is missing from the table", cp, name));
        }
    }
}
