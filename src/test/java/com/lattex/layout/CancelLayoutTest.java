package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Geometry of the {@code cancel} strike family — the diagonal strike(s) span the
/// body's ink bounding box corner-to-corner (both diagonals for {@code \xcancel}), and
/// {@code \cancelto}'s target value lands beyond the strike tip at a smaller size. The
/// strikes emit as filled {@code <path>} polygons, inside the SVG alphabet.
class CancelLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex),
            new LayoutContext(FONT, FONT.mathConstants(), SIZE));
    }

    private static List<Rule> polygons(Layout l) {
        return l.rules().stream().filter(Rule::isPolygon).toList();
    }

    /** The (x,y) of the vertex with the smallest x, then the vertex with the largest x. */
    private static double[] endpointsByX(Rule poly) {
        double[] p = poly.polygon();
        int lo = 0;
        int hi = 0;
        for (int i = 0; i < p.length; i += 2) {
            if (p[i] < p[lo]) {
                lo = i;
            }
            if (p[i] > p[hi]) {
                hi = i;
            }
        }
        return new double[] {p[lo], p[lo + 1], p[hi], p[hi + 1]};
    }

    @Test
    void cancelStrikeIsOneUpDiagonalSpanningTheBodyInkBox() {
        Layout body = layout("ab");
        Layout l = layout("\\cancel{ab}");
        List<Rule> polys = polygons(l);
        assertEquals(1, polys.size(), "\\cancel draws exactly one diagonal strike");

        // The strike's bounding box encloses the body ink box corner-to-corner
        // (a small overshoot pushes it slightly past every edge).
        Rule s = polys.get(0);
        double eps = 1e-6;
        assertTrue(s.x() <= body.minX() + eps, "strike reaches the body's left ink edge");
        assertTrue(s.x() + s.width() >= body.maxX() - eps, "strike reaches the right edge");
        assertTrue(s.y() <= body.minY() + eps, "strike reaches the top ink edge");
        assertTrue(s.y() + s.height() >= body.maxY() - eps, "strike reaches the bottom edge");

        // Up diagonal "/": the left end sits LOW (larger y, y-down) and the right end HIGH.
        double[] e = endpointsByX(s);
        assertTrue(e[1] > e[3], "up diagonal: left end is lower than the right end");
    }

    @Test
    void bcancelStrikeIsADownDiagonal() {
        Rule s = polygons(layout("\\bcancel{ab}")).get(0);
        double[] e = endpointsByX(s);
        // Down diagonal "\": the left end sits HIGH (smaller y) and the right end LOW.
        assertTrue(e[1] < e[3], "down diagonal: left end is higher than the right end");
    }

    @Test
    void xcancelDrawsBothDiagonals() {
        List<Rule> polys = polygons(layout("\\xcancel{ab}"));
        assertEquals(2, polys.size(), "\\xcancel draws two crossing strikes");
        boolean hasUp = false;
        boolean hasDown = false;
        for (Rule s : polys) {
            double[] e = endpointsByX(s);
            if (e[1] > e[3]) {
                hasUp = true;   // "/"
            }
            if (e[1] < e[3]) {
                hasDown = true; // "\"
            }
        }
        assertTrue(hasUp && hasDown, "one up diagonal and one down diagonal (an X)");
    }

    @Test
    void canceltoAddsAStrikeAnArrowheadAndASmallerValueBeyondTheTip() {
        Layout l = layout("\\cancelto{0}{x}");
        // Strike + arrowhead are both polygons (>= 2); \cancel{x} has just the strike.
        assertTrue(polygons(l).size() >= 2, "\\cancelto adds an arrowhead polygon beside the strike");

        double bodyWidth = layout("x").width();
        // The value glyph is set at script size — smaller than the full-size body glyph —
        // and placed beyond the body's right edge (the strike tip).
        double maxBodyScale = 0.0;
        double minGlyphScale = Double.MAX_VALUE;
        double rightmostSmallGlyphX = Double.NEGATIVE_INFINITY;
        for (PositionedGlyph g : l.glyphs()) {
            maxBodyScale = Math.max(maxBodyScale, g.scale());
            minGlyphScale = Math.min(minGlyphScale, g.scale());
        }
        assertTrue(minGlyphScale < maxBodyScale,
            "the \\cancelto value is smaller than the body: " + minGlyphScale + " vs " + maxBodyScale);
        for (PositionedGlyph g : l.glyphs()) {
            if (g.scale() == minGlyphScale) {
                rightmostSmallGlyphX = Math.max(rightmostSmallGlyphX, g.originX());
            }
        }
        assertTrue(rightmostSmallGlyphX > bodyWidth - 1e-6,
            "the value lands beyond the strike tip (x=" + rightmostSmallGlyphX
                + " past bodyWidth=" + bodyWidth + ")");
    }

    @Test
    void cancelRendersInAlphabetAsFilledPaths() {
        String svg = LatteX.render("\\xcancel{ab}");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(svg.contains("<path"), "the strikes draw filled paths");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker") && !svg.contains("stroke="),
            "in-alphabet only: no <line>, <marker>, or stroke");
    }
}
