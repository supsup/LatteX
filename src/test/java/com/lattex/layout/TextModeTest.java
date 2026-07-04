package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
import com.lattex.parse.MathParser;
import org.junit.jupiter.api.Test;

/**
 * Tier-1d {@code \text}-mode tests: the {@code \text}/{@code \textrm}/{@code
 * \textbf}/{@code \textit}/{@code \texttt} family and math-mode {@code \mathrm}
 * parse to a {@link TextRun}, render as upright (or shaped) words with their
 * inter-word spaces preserved, and stay strictly within the minimal SVG
 * alphabet — words are filled glyph {@code <path>}s, never an SVG {@code <text>}
 * element.
 */
class TextModeTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), SIZE);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    // ------------------------------------------------------------------
    // Parse: text-family commands build TextRuns; spaces are significant.
    // ------------------------------------------------------------------

    @Test
    void textRunPreservesInterWordSpaces() {
        // A leading/trailing space inside the braces must survive (math mode would
        // strip it) — this is the defining difference of text mode.
        MathNode node = MathParser.parse("\\text{if } n \\text{ is even}");
        assertTrue(node instanceof MathNode.MathList, "a row of text + math");
        var items = ((MathNode.MathList) node).items();
        assertEquals(3, items.size());
        assertEquals(new TextRun("if ", TextStyle.ROMAN), items.get(0));
        assertEquals(new TextRun(" is even", TextStyle.ROMAN), items.get(2));
    }

    @Test
    void textFamilyMapsToTheRightShape() {
        assertEquals(new TextRun("abc", TextStyle.ROMAN),
            MathParser.parse("\\text{abc}"));
        assertEquals(new TextRun("abc", TextStyle.ROMAN),
            MathParser.parse("\\textrm{abc}"));
        assertEquals(new TextRun("abc", TextStyle.BOLD),
            MathParser.parse("\\textbf{abc}"));
        assertEquals(new TextRun("abc", TextStyle.ITALIC),
            MathParser.parse("\\textit{abc}"));
        assertEquals(new TextRun("abc", TextStyle.MONO),
            MathParser.parse("\\texttt{abc}"));
    }

    @Test
    void mathrmIsUprightRomanInMath() {
        // \mathrm{d}x — the derivative operator: upright roman d, then a math x.
        MathNode node = MathParser.parse("\\mathrm{d}x");
        var items = ((MathNode.MathList) node).items();
        assertEquals(new TextRun("d", TextStyle.ROMAN), items.get(0));
        assertTrue(items.get(1) instanceof MathNode.Atom);
    }

    @Test
    void emptyTextArgumentIsAllowed() {
        assertEquals(new TextRun("", TextStyle.ROMAN), MathParser.parse("\\text{}"));
    }

    @Test
    void textRunTakesAScript() {
        // \text{lots}^2 — a text run is an ordinary nucleus, so a superscript attaches.
        MathNode node = MathParser.parse("\\text{lots}^2");
        assertTrue(node instanceof MathNode.SupSub);
        assertEquals(new TextRun("lots", TextStyle.ROMAN),
            ((MathNode.SupSub) node).base());
    }

    @Test
    void textCommandWithoutBraceFailsCleanly() {
        assertTrue(assertThrowsSyntax("\\text x").getMessage().contains("text"));
    }

    // ------------------------------------------------------------------
    // Layout: upright glyphs, real spaces, shape variants distinct.
    // ------------------------------------------------------------------

    @Test
    void romanUsesTheUprightAsciiGlyph() {
        Layout l = layout("\\text{x}");
        assertEquals(1, l.glyphs().size());
        assertEquals(FONT.glyphId('x'), l.glyphs().get(0).glyphId(),
            "\\text uses the font's plain (upright roman) x, not a math-italic variant");
    }

    @Test
    void shapeVariantsUseDistinctGlyphs() {
        int roman = layout("\\text{x}").glyphs().get(0).glyphId();
        int bold = layout("\\textbf{x}").glyphs().get(0).glyphId();
        int italic = layout("\\textit{x}").glyphs().get(0).glyphId();
        int mono = layout("\\texttt{x}").glyphs().get(0).glyphId();

        // Each shape resolves to the Mathematical Alphanumeric variant expected.
        assertEquals(FONT.glyphId(0x1D431), bold, "bold x = U+1D431");
        assertEquals(FONT.glyphId(0x1D465), italic, "italic x = U+1D465");
        assertEquals(FONT.glyphId(0x1D6A1), mono, "mono x = U+1D6A1");
        // And they are genuinely four different glyphs (real shaping, not a fallback).
        assertNotEquals(roman, bold);
        assertNotEquals(roman, italic);
        assertNotEquals(roman, mono);
    }

    @Test
    void italicSmallHUsesThePlanckHole() {
        // Mathematical Italic small h (U+1D455) is a Unicode reserved hole → U+210E.
        assertEquals(FONT.glyphId(0x210E), layout("\\textit{h}").glyphs().get(0).glyphId());
    }

    @Test
    void spacesAddRealWidth() {
        // A run WITH an inter-word space is strictly wider than the same letters
        // without it — the space renders as a real gap, not nothing.
        double spaced = layout("\\text{a b}").width();
        double packed = layout("\\text{ab}").width();
        assertTrue(spaced > packed + 1.0,
            "the inter-word space adds real width (" + spaced + " vs " + packed + ")");
    }

    @Test
    void spacesEmitNoInk() {
        // "a b" has two ink glyphs (a, b) — the space contributes advance, no path.
        assertEquals(2, layout("\\text{a b}").glyphs().size());
    }

    // ------------------------------------------------------------------
    // End-to-end: renders in-alphabet (glyph paths, never <text>).
    // ------------------------------------------------------------------

    @Test
    void rendersWordsAsGlyphPathsNeverSvgText() {
        String svg = LatteX.render("\\text{if } n \\text{ is even}");
        assertTrue(svg.contains("<path"), "words are drawn as filled glyph paths");
        assertFalse(svg.contains("<text"), "NEVER an SVG <text> element");
        assertFalse(svg.contains("<use"), "no <use> indirection");
        assertFalse(svg.contains("href"), "no href");
        assertTrue(svg.contains("aria-label=\"if  n  is even\"")
                || svg.contains("aria-label=\"if"),
            "the a11y label carries the words; got: " + ariaLabel(svg));
    }

    @Test
    void boldAndMathrmRenderInAlphabet() {
        for (String latex : new String[] {
                "\\textbf{Bold}", "\\textit{Slanted}", "\\texttt{mono}",
                "\\mathrm{d}x", "\\text{area} = \\pi r^2"}) {
            String svg = LatteX.render(latex);
            assertTrue(svg.contains("<path"), latex + " draws glyph paths");
            assertFalse(svg.contains("<text"), latex + " emits no <text>");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static com.lattex.parse.MathSyntaxException assertThrowsSyntax(String latex) {
        try {
            MathParser.parse(latex);
        } catch (com.lattex.parse.MathSyntaxException e) {
            return e;
        }
        throw new AssertionError("expected MathSyntaxException for [" + latex + "]");
    }

    private static String ariaLabel(String svg) {
        int i = svg.indexOf("aria-label=\"");
        if (i < 0) {
            return "(none)";
        }
        int j = svg.indexOf('"', i + 12);
        return svg.substring(i + 12, j);
    }
}
