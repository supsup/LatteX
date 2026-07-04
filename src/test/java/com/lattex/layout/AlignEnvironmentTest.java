package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Geometry tests for the aligned-equation environments ({@code align}/{@code aligned}/
 * {@code gather}). The load-bearing invariant is the alignment point: in {@code align},
 * every equation's relation lines up in a single vertical column even when the
 * left-hand sides differ in width. {@code gather} instead centres each row and applies
 * no {@code &} alignment.
 */
class AlignEnvironmentTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        MathNode node = MathParser.parse(latex);
        return LayoutEngine.layout(node, ctx);
    }

    /** The x-origins of every '=' glyph in the layout, in document order. */
    private static List<Double> equalsGlyphXs(Layout l) {
        int eqGid = FONT.glyphId('=');
        return l.glyphs().stream()
            .filter(g -> g.glyphId() == eqGid)
            .map(PositionedGlyph::originX)
            .toList();
    }

    // ---- align: the alignment point --------------------------------------

    @Test
    void alignLinesUpRelationsAcrossUnequalLeftHandSides() {
        // Row 0's LHS is a single narrow 'a'; row 1's LHS is a wide 'xyz'. Because
        // column 0 is right-aligned, both LHSs end at the same right edge, so both
        // '=' signs (column 1, left-aligned) start at the SAME x — the alignment point.
        Layout l = layout("\\begin{align}a&=b\\\\xyz&=w\\end{align}");
        List<Double> eqXs = equalsGlyphXs(l);
        assertEquals(2, eqXs.size(), "one '=' per equation row");
        assertTrue(Math.abs(eqXs.get(0) - eqXs.get(1)) < 0.001,
            "the two relations line up in one column: " + eqXs);
    }

    @Test
    void alignStraddlesTheBaselineAndStacksRows() {
        Layout l = layout("\\begin{align}a&=b\\\\c&=d\\end{align}");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() < -1.0), "a top row above the axis");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() > 1.0), "a bottom row below the axis");
        // No enclosing delimiters — align is bare.
        assertTrue(l.rules().isEmpty(), "align emits no rules");
    }

    @Test
    void alignAcceptsAnEmptyContinuationLeftHandSide() {
        // The classic derivation shape: the second line elides the LHS (just "&= …").
        // The empty first cell must not throw and must still align on the relation.
        Layout l = layout("\\begin{align}f(x)&=x^2\\\\&=x\\cdot x\\end{align}");
        List<Double> eqXs = equalsGlyphXs(l);
        assertEquals(2, eqXs.size(), "both rows carry a relation");
        assertTrue(Math.abs(eqXs.get(0) - eqXs.get(1)) < 0.001, "continuation line aligns: " + eqXs);
    }

    @Test
    void alignSupportsSeveralEquationsPerRow() {
        // Two equation pairs on one line: x=1  y=2 over a=3  b=4. Four '=' total,
        // and the two per-column relations each line up (cols 1 and 3).
        Layout l = layout("\\begin{align}x&=1&y&=2\\\\a&=3&b&=4\\end{align}");
        int eqGid = FONT.glyphId('=');
        List<Double> eqXs = l.glyphs().stream()
            .filter(g -> g.glyphId() == eqGid)
            .sorted((p, q) -> Double.compare(p.originX(), q.originX()))
            .map(PositionedGlyph::originX)
            .toList();
        assertEquals(4, eqXs.size(), "four relations");
        // Sorted by x, the four '=' fall into two aligned columns (two near-equal pairs).
        assertTrue(Math.abs(eqXs.get(0) - eqXs.get(1)) < 0.001, "first-equation column aligns");
        assertTrue(Math.abs(eqXs.get(2) - eqXs.get(3)) < 0.001, "second-equation column aligns");
        assertTrue(eqXs.get(2) - eqXs.get(1) > 10.0, "a wide gap separates the two equations");
    }

    // ---- gather: centred, no alignment -----------------------------------

    @Test
    void gatherCentresEachRowWithNoAlignmentColumn() {
        // gather has no '&' — each row is a single centred cell. A short row over a
        // long row: the short row's centre must sit near the long row's centre.
        Layout l = layout("\\begin{gather}a=b\\\\x+y=z+w\\end{gather}");
        double minX = l.glyphs().stream().mapToDouble(PositionedGlyph::originX).min().orElseThrow();
        double maxX = l.glyphs().stream().mapToDouble(PositionedGlyph::originX).max().orElseThrow();
        double mid = (minX + maxX) / 2.0;
        double topMid = rowCentreX(l, true);
        double bottomMid = rowCentreX(l, false);
        assertTrue(Math.abs(topMid - mid) < 6.0 && Math.abs(bottomMid - mid) < 6.0,
            "both rows are centred about the same vertical axis");
    }

    /** The horizontal centre of the top (above-axis) or bottom (below-axis) row. */
    private static double rowCentreX(Layout l, boolean topRow) {
        var xs = l.glyphs().stream()
            .filter(g -> topRow ? g.baselineY() < 0 : g.baselineY() > 0)
            .mapToDouble(PositionedGlyph::originX);
        var stats = xs.summaryStatistics();
        return (stats.getMin() + stats.getMax()) / 2.0;
    }

    // ---- surface forms + containment -------------------------------------

    @Test
    void starredAndAlignedFormsParseAndRender() {
        assertAlphabet(LatteX.render("\\begin{align*}E&=mc^2\\end{align*}"));
        assertAlphabet(LatteX.render("\\begin{aligned}a&=b\\\\c&=d\\end{aligned}"));
        assertAlphabet(LatteX.render("\\begin{gather*}a=b\\\\c=d\\end{gather*}"));
    }

    @Test
    void alignedNestsInsideOtherMath() {
        // aligned is the in-line variant — it must compose inside a larger formula.
        assertAlphabet(LatteX.render("x=\\begin{aligned}a&=b\\\\c&=d\\end{aligned}"));
    }

    @Test
    void alignStaysInTheSvgAlphabet() {
        assertAlphabet(LatteX.render("\\begin{align}\\int_0^1 x\\,dx&=\\frac{1}{2}\\\\\\sum_{k=1}^n k&=\\frac{n(n+1)}{2}\\end{align}"));
    }

    // ---- alphabet guard (same minimal set as MatrixLayoutTest) -----------

    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final Set<String> ALLOWED_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label",
        "transform", "fill", "d", "stroke", "stroke-width", "x", "y");
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "use", "defs", "symbol", "text", "foreignObject", "script", "style",
        "animate", "image", "a");

    private static void assertAlphabet(String svg) {
        for (String forbidden : FORBIDDEN_ELEMENTS) {
            assertFalse(svg.contains("<" + forbidden), "must not emit <" + forbidden + ">");
        }
        assertFalse(svg.contains("href"), "no href");
        assertFalse(svg.contains("data:"), "no data: URI");
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(svg);
        while (tag.find()) {
            String element = tag.group(1);
            assertTrue(ALLOWED_ELEMENTS.contains(element), "element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=\\s*\"[^\"]*\"")
                .matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(ALLOWED_ATTRS.contains(attr.group(1)),
                    "attribute out of alphabet on <" + element + ">: " + attr.group(1));
            }
        }
    }
}
