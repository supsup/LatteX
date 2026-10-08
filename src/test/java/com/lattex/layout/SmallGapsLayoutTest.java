package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Geometry for plan fc988bc4's overlaid and stretched glyphs. An overlay is
 * pinned by its EXTENT, not by "it rendered" (the lesson of b3f198f2, where a
 * misplaced arrow passed two reviews on render-succeeds tests): the {@code \not}
 * slash sits centred on, and inside, the relation it strikes; a {@code \big}
 * arrow spans the fixed size its level names; {@code @{...}} material sits in
 * the boundary between its columns.
 */
class SmallGapsLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;
    private static final double EPS = 1e-6;

    private static Layout layout(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex),
            new LayoutContext(FONT, FONT.mathConstants(), SIZE));
    }

    private static PositionedGlyph glyphOf(Layout l, int cp) {
        int gid = FONT.glyphId(cp);
        List<PositionedGlyph> hits = l.glyphs().stream().filter(g -> g.glyphId() == gid).toList();
        assertEquals(1, hits.size(), "exactly one U+" + Integer.toHexString(cp));
        return hits.get(0);
    }

    /** {inkLeft, inkTop, inkRight, inkBottom} in user space (y-down). */
    private static double[] ink(PositionedGlyph g) {
        GlyphOutline o = FONT.outline(g.glyphId());
        return new double[] {
            g.originX() + g.scale() * o.xMin(), g.baselineY() - g.scale() * o.yMax(),
            g.originX() + g.scale() * o.xMax(), g.baselineY() - g.scale() * o.yMin()};
    }

    // ---- \not overstrike ----------------------------------------------------

    @Test
    void notSlashIsCentredOnAndInsideTheRelationItStrikes() {
        for (int[] probe : new int[][] {{0x22A5, 0}, {0x2A7D, 1}, {0x2266, 2}}) {
            String src = switch (probe[1]) {
                case 0 -> "a\\not\\perp b";
                case 1 -> "a\\nleqslant b";
                default -> "a\\nleqq b";
            };
            Layout l = layout(src);
            PositionedGlyph rel = glyphOf(l, probe[0]);
            PositionedGlyph slash = glyphOf(l, 0x0338);
            double[] r = ink(rel);
            double[] s = ink(slash);
            double relAdvanceLeft = rel.originX();
            double relAdvanceRight = rel.originX() + rel.scale() * FONT.advanceWidth(rel.glyphId());
            // Horizontally: centred on the relation's ink and inside its advance box.
            assertEquals((r[0] + r[2]) / 2, (s[0] + s[2]) / 2, 0.01 * SIZE, src + ": slash centred");
            assertTrue(s[0] >= relAdvanceLeft - EPS && s[2] <= relAdvanceRight + EPS,
                src + ": slash ink " + s[0] + ".." + s[2] + " inside the relation box "
                    + relAdvanceLeft + ".." + relAdvanceRight);
            // Vertically: the slash crosses the whole relation, and stays within the
            // line's ascender/descender (it never pokes above 0.8em or below 0.3em).
            // (≦ is taller than the slash STIX draws for ≠ by ~0.06em at each end.)
            assertTrue(s[1] <= r[1] + 0.08 * SIZE, src + ": slash reaches the relation's top");
            assertTrue(s[3] >= r[3] - 0.08 * SIZE, src + ": slash reaches the relation's bottom");
            assertTrue(s[1] >= -0.8 * SIZE && s[3] <= 0.3 * SIZE, src + ": slash within the line");
            // The whole layout's ink box contains the slash (nothing clipped).
            assertTrue(s[0] >= l.minX() - EPS && s[2] <= l.maxX() + EPS
                && s[1] >= l.minY() - EPS && s[3] <= l.maxY() + EPS, src + ": inside the ink box");
        }
    }

    @Test
    void notSlashMatchesThePrecomposedNegationExtent() {
        // The overstrike draws the same slash STIX draws inside the precomposed U+2260.
        double[] neq = ink(glyphOf(layout("\\neq"), 0x2260));
        double[] slash = ink(glyphOf(layout("\\not\\perp"), 0x0338));
        assertEquals(neq[1], slash[1], 0.02 * SIZE, "same top as the slash in \\neq");
        assertEquals(neq[3], slash[3], 0.02 * SIZE, "same bottom as the slash in \\neq");
    }

    // ---- \big arrows -----------------------------------------------------------

    @Test
    void bigArrowsSpanTheirLevelCentredOnTheAxis() {
        double axis = FONT.mathConstants().axisHeight() * SIZE / FONT.unitsPerEm();
        double[] span = {0, 1.2, 1.8, 2.4, 3.0};
        String[] names = {"", "big", "Big", "bigg", "Bigg"};
        for (int level = 1; level <= 4; level++) {
            Layout l = layout("\\" + names[level] + "\\downarrow");
            double inkH = l.maxY() - l.minY();
            assertTrue(inkH >= span[level] * SIZE - 0.5,
                names[level] + ": ink " + inkH + " reaches the level's " + span[level] + "em");
            assertTrue(inkH <= span[level] * SIZE * 1.35,
                names[level] + ": ink " + inkH + " is not wildly over the level");
            double centre = (l.minY() + l.maxY()) / 2;
            assertEquals(-axis, centre, 0.05 * SIZE, names[level] + ": centred on the axis");
        }
        // The plain arrow stays its natural size.
        Layout plain = layout("\\downarrow");
        assertTrue(plain.maxY() - plain.minY() < 1.2 * SIZE);
    }

    // ---- @{...} --------------------------------------------------------------

    @Test
    void atMaterialSitsBetweenItsColumnsOnEachRow() {
        Layout l = layout("\\begin{array}{c@{\\to}c}a&b\\\\c&d\\end{array}");
        int arrow = FONT.glyphId(0x2192);
        List<PositionedGlyph> arrows = l.glyphs().stream().filter(g -> g.glyphId() == arrow).toList();
        assertEquals(2, arrows.size(), "one arrow per row");
        PositionedGlyph a = l.glyphs().stream()
            .filter(g -> g.glyphId() == FONT.glyphId(0x1D44E)).findFirst().orElseThrow();
        PositionedGlyph b = l.glyphs().stream()
            .filter(g -> g.glyphId() == FONT.glyphId(0x1D44F)).findFirst().orElseThrow();
        PositionedGlyph first = arrows.stream()
            .min((x, y) -> Double.compare(x.baselineY(), y.baselineY())).orElseThrow();
        assertEquals(a.baselineY(), first.baselineY(), EPS, "on the row's baseline");
        double aRight = a.originX() + a.scale() * FONT.advanceWidth(a.glyphId());
        double arrowRight = first.originX() + first.scale() * FONT.advanceWidth(first.glyphId());
        assertTrue(first.originX() >= aRight - EPS, "after column 1");
        assertTrue(b.originX() >= arrowRight - EPS, "before column 2");
        // @ REPLACES the intercolumn space: the arrow abuts both cells (cells are
        // centred in equal-width columns of one glyph each, so no slack).
        assertEquals(aRight, first.originX(), 0.02 * SIZE, "no \\arraycolsep before the material");
        assertEquals(arrowRight, b.originX(), 0.02 * SIZE, "no \\arraycolsep after the material");
    }
}
