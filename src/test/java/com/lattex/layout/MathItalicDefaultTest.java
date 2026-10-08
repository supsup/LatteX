package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The math-mode default alphabet (plan a85ff403): TeX sets a math letter in MATH ITALIC and
 * maps {@code -} to the minus sign (TeXbook ch. 17 and App. B/F: Latin letters and lowercase
 * Greek are family-1 math italic; {@code -} carries mathcode "2200, the minus of family 2).
 * LatteX used to draw both from their ASCII code points — the upright roman letter and the
 * short hyphen — so every variable read as text and every subtraction as a hyphen.
 *
 * <p>Asserted on GLYPH IDS read from the real bundled font, not on rendered strings, so each
 * test names the exact glyph a token must draw. The upright set (roman words, text, digits,
 * uppercase Greek, the legacy {@code \rm}) is pinned beside the italic set, so a default that
 * leaked into them would fail here too.
 */
class MathItalicDefaultTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    /** The glyph ids of a formula in reading (left-to-right) order. */
    private static List<Integer> glyphs(String latex) {
        return layout(latex).glyphs().stream()
            .sorted(Comparator.comparingDouble(PositionedGlyph::originX))
            .map(PositionedGlyph::glyphId)
            .toList();
    }

    private static int gid(int codePoint) {
        int g = FONT.glyphId(codePoint);
        assertNotEquals(0, g, "bundled font has no glyph for U+" + Integer.toHexString(codePoint));
        return g;
    }

    // ------------------------------------------------------------------
    // The two defects.
    // ------------------------------------------------------------------

    @Test
    void aPlainLetterDrawsTheMathItalicGlyph() {
        assertEquals(List.of(gid(0x1D465)), glyphs("x"), "x is MATHEMATICAL ITALIC SMALL X");
        assertEquals(List.of(gid(0x1D449)), glyphs("V"), "V is MATHEMATICAL ITALIC CAPITAL V");
        assertNotEquals(gid('x'), gid(0x1D465), "non-vacuity: the two glyphs differ");
    }

    @Test
    void aMathHyphenDrawsTheMinusSign() {
        assertEquals(List.of(gid(0x1D44E), gid(0x2212), gid(0x1D44F)), glyphs("a-b"));
        assertEquals(gid(0x2212), glyphs("-1").get(0), "unary minus is the minus sign too");
        assertEquals(gid(0x2212), glyphs("x^{-1}").get(1), "and in a script");
        assertNotEquals(gid('-'), gid(0x2212), "non-vacuity: the two glyphs differ");
    }

    // ------------------------------------------------------------------
    // The rest of TeX's default alphabet.
    // ------------------------------------------------------------------

    @Test
    void italicHUsesThePlanckConstantSlot() {
        // U+1D455 is a reserved hole in the Mathematical Italic run; Unicode puts italic h at ℎ.
        assertEquals(List.of(gid(0x210E)), glyphs("h"));
    }

    @Test
    void lowercaseGreekIsItalicAndUppercaseGreekIsUpright() {
        assertEquals(List.of(gid(0x1D6FC)), glyphs("\\alpha"));
        assertEquals(List.of(gid(0x1D714)), glyphs("\\omega"));
        // The TeX variant shapes are family-1 letters too: \epsilon is ϵ, \phi is ϕ.
        assertEquals(List.of(gid(0x1D716)), glyphs("\\epsilon"));
        assertEquals(List.of(gid(0x1D719)), glyphs("\\phi"));
        // ∂ keeps its own glyph (see LayoutEngine.isLowercaseGreek for why it is out of scope).
        assertEquals(List.of(gid(0x2202)), glyphs("\\partial"));
        // Uppercase Greek is family 0 in plain TeX: upright.
        assertEquals(List.of(gid(0x0393)), glyphs("\\Gamma"));
        assertEquals(List.of(gid(0x03A9)), glyphs("\\Omega"));
    }

    @Test
    void dotlessIAndJAreItalic() {
        assertEquals(List.of(gid(0x1D6A4)), glyphs("\\imath"));
        assertEquals(List.of(gid(0x1D6A5)), glyphs("\\jmath"));
    }

    @Test
    void everyDefaultMappedCharacterHasARealGlyph() {
        // The layout falls back to the typed glyph when the font lacks the shaped one; for
        // the bundled font that fallback must never be what a reader sees. Read the drawn
        // glyph for every character the default touches and require it to differ from the
        // ASCII/Greek base glyph (i.e. the italic one was found, not the fallback).
        StringBuilder gaps = new StringBuilder();
        List<Integer> sources = new ArrayList<>();
        for (int c = 'A'; c <= 'Z'; c++) {
            sources.add(c);
        }
        for (int c = 'a'; c <= 'z'; c++) {
            sources.add(c);
        }
        for (int c = 0x03B1; c <= 0x03C9; c++) {
            sources.add(c);
        }
        sources.addAll(List.of(0x03F5, 0x03D1, 0x03F0, 0x03D5, 0x03F1, 0x03D6, 0x0131, 0x0237));
        for (int cp : sources) {
            List<Integer> drawn = glyphs(new String(Character.toChars(cp)));
            if (drawn.size() != 1 || drawn.get(0) == FONT.glyphId(cp) || drawn.get(0) == 0) {
                gaps.append(" U+").append(Integer.toHexString(cp));
            }
        }
        assertEquals("", gaps.toString(), "characters drawn without their math-italic glyph");
    }

    @Test
    void scriptsReadTheItalicNucleusMetrics() {
        // Italic correction and the TopRight math-kern now come from the glyph that is DRAWN.
        // For f, V and P the superscript's x is advance + italic correction + TopRight kern of
        // the ITALIC glyph (TeXbook App. G rule 17; OpenType MATH MathKernInfo), read from the
        // real tables here — the upright glyph's numbers would put it somewhere else.
        double scale = 40.0 / FONT.unitsPerEm();
        for (int[] pair : new int[][] {{'f', 0x1D453}, {'V', 0x1D449}, {'P', 0x1D443}}) {
            int italicGid = gid(pair[1]);
            String latex = (char) pair[0] + "^2";
            Layout l = layout(latex);
            PositionedGlyph base = l.glyphs().get(0);
            PositionedGlyph sup = l.glyphs().get(1);
            assertEquals(italicGid, base.glyphId(), latex);
            // The TopRight staircase is read at the superscript's bottom ink edge.
            var kern = FONT.mathKernInfo(italicGid);
            double supDepth = Math.max(0.0, -FONT.outline(sup.glyphId()).yMin() * sup.scale());
            int h = (int) Math.round((-sup.baselineY() - supDepth) / scale);
            double kernUnits = kern == null || kern.topRight() == null
                ? 0.0 : kern.topRight().kernAtHeight(h);
            double expected = (FONT.advanceWidth(italicGid) + FONT.italicCorrection(italicGid)
                + kernUnits) * scale;
            double upright = (FONT.advanceWidth(gid(pair[0])) + FONT.italicCorrection(gid(pair[0])))
                * scale;
            assertEquals(expected, sup.originX(), 0.5, latex + " superscript x");
            assertNotEquals(upright, sup.originX(), 0.01,
                latex + ": the superscript must not sit where the upright glyph would put it");
        }
    }

    @Test
    void digitsStayUpright() {
        assertEquals(List.of(gid('1'), gid('2')), glyphs("12"));
    }

    // ------------------------------------------------------------------
    // What must NOT change.
    // ------------------------------------------------------------------

    @Test
    void mathrmTextAndOperatorNamesKeepTheirUprightGlyphs() {
        assertEquals(List.of(gid('x')), glyphs("\\mathrm{x}"));
        assertEquals(List.of(gid('d')), glyphs("{\\rm d}"), "the legacy \\rm switch is upright");
        assertEquals(List.of(gid('s'), gid('i'), gid('n'), gid(0x1D465)), glyphs("\\sin x"));
        assertEquals(List.of(gid('l'), gid('c'), gid('m')), glyphs("\\operatorname{lcm}"));
        assertEquals(List.of(gid('a'), gid('-'), gid('b')), glyphs("\\text{a-b}"),
            "text keeps its hyphen and its roman letters");
    }

    @Test
    void theRomanSwitchDoesNotStraightenGreekOrTheMinus() {
        // TeX's \rm selects family 0 for class-7 characters only — Latin letters and digits.
        // \alpha and the minus have fixed families and ignore it.
        assertEquals(List.of(gid('a'), gid(0x2212), gid(0x1D6FC)), glyphs("{\\rm a-\\alpha}"));
    }

    @Test
    void explicitVariantsAreUnchanged() {
        assertEquals(List.of(gid(0x1D431)), glyphs("\\mathbf{x}"));
        assertEquals(List.of(gid(0x1D5D1)), glyphs("\\mathsf{x}"));
        assertEquals(List.of(gid(0x211D)), glyphs("\\mathbb{R}"));
        // \mathit and the default are the same alphabet for letters, as in TeX.
        assertEquals(glyphs("x"), glyphs("\\mathit{x}"));
    }

    // ------------------------------------------------------------------
    // The emitted SVG, and the surfaces that key on the SOURCE code point.
    // ------------------------------------------------------------------

    private static final Pattern PATH_D = Pattern.compile("<path d=\"([^\"]*)\"");

    private static List<String> pathData(String svg) {
        List<String> out = new ArrayList<>();
        Matcher m = PATH_D.matcher(svg);
        while (m.find()) {
            out.add(m.group(1));
        }
        assertFalse(out.isEmpty(), "no glyph paths in " + svg);
        return out;
    }

    @Test
    void theSvgOfAPlainLetterIsTheMathitSvgNotTheMathrmSvg() {
        assertEquals(pathData(LatteX.render("\\mathit{x}")), pathData(LatteX.render("x")));
        assertNotEquals(pathData(LatteX.render("\\mathrm{x}")), pathData(LatteX.render("x")));
        assertEquals(pathData(LatteX.render("a\u2212b")), pathData(LatteX.render("a-b")),
            "a typed '-' and a pasted U+2212 draw the same ink");
    }

    @Test
    void tokenIdentityStillKeysOnTheTypedCodePoint() {
        // The glyphmap and the accessible label describe what the AUTHOR typed; only the
        // drawn glyph moved. x is 0x78 in the sidecar, not 0x1d465.
        String threaded = LatteX.renderStyledHtml("\\lx[fx.hover=thread]{x + x}");
        assertTrue(threaded.contains("data-lx-glyphmap=\"78:0,2\""), threaded);
        assertTrue(LatteX.render("a-b").contains("aria-label=\"a - b\""), LatteX.render("a-b"));
    }

    @Test
    void mathmlAgreesWithTheGlyphs() {
        // MathML Core already draws a single-character <mi> in italic, so plain letters need
        // no attribute; the upright ones say so, and the minus is the minus.
        assertTrue(LatteX.toMathML("x").contains("<mi>x</mi>"), LatteX.toMathML("x"));
        assertTrue(LatteX.toMathML("a-b").contains("<mo>\u2212</mo>"), LatteX.toMathML("a-b"));
        assertFalse(LatteX.toMathML("a-b").contains("<mo>-</mo>"), LatteX.toMathML("a-b"));
        assertTrue(LatteX.toMathML("\\Gamma").contains("<mi mathvariant=\"normal\">\u0393</mi>"),
            LatteX.toMathML("\\Gamma"));
        assertTrue(LatteX.toMathML("{\\rm d}").contains("<mi mathvariant=\"normal\">d</mi>"),
            LatteX.toMathML("{\\rm d}"));
        assertTrue(LatteX.toMathML("\\alpha").contains("<mi>\u03B1</mi>"),
            LatteX.toMathML("\\alpha"));
    }
}
