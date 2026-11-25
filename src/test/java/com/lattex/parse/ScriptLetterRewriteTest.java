package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.RenderResult;
import com.lattex.font.SfntFont;
import java.text.Normalizer;
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
        // a cluster with no rewrite keeps its own CHAR tokens and their own offsets
        String run = "\u00B2".repeat(20000);
        long t0 = System.nanoTime();
        String p = pp("x" + run);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(p.startsWith("L(A(x,ORD) A(U+00B2,ORD)"), p.substring(0, 60));
        assertTrue(ms < 5000, "20,000 script characters took " + ms + " ms");
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

    @Test
    void textModeIsNotRewritten() {
        // a text run is taken raw; the rewrite is a math-mode lexer rule
        assertEquals("Txt[ROMAN](a\u1D62b)", pp("\\text{a\u1D62b}"));
    }

    @Test
    void theTableMatchesUnicodeAndTheBundledFont() {
        int rewritten = 0;
        for (var e : MathParser.SCRIPT_CHARS.entrySet()) {
            int cp = e.getKey();
            int kind = e.getValue()[0];
            int base = e.getValue()[1];
            boolean flagged = e.getValue()[2] == 1;
            String ch = new String(Character.toChars(cp));
            String b = new String(Character.toChars(base));
            String at = String.format("U+%04X", cp);
            assertEquals(b, Normalizer.normalize(ch, Normalizer.Form.NFKD), at + ": base is its compatibility decomposition");
            String name = Character.getName(cp);
            assertEquals(kind == 1, name.contains("SUBSCRIPT"), at + " " + name + ": kind agrees with the name");
            boolean fontLacks = FONT.glyphId(cp) == 0;
            boolean baseDraws = LatteX.renderWithDiagnostics(b).diagnostics().detail().isEmpty();
            assertEquals(fontLacks && baseDraws, flagged, at + ": rewritten exactly when the font lacks it and draws its base");
            if (flagged) {
                rewritten++;
                assertEquals(pp("x" + (kind == 0 ? "^" : "_") + "{" + b + "}"), pp("x" + ch), at);
            } else {
                assertTrue(pp("x" + ch).contains(String.format("U+%04X", cp)), at + " kept as the font's glyph");
            }
        }
        assertEquals(163, MathParser.SCRIPT_CHARS.size(), "Unicode's one-character <super>/<sub> decompositions in U+00A0-U+2DFF");
        assertEquals(103, rewritten, "of which the bundled font lacks the character and draws the base");
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
