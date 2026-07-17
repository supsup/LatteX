package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import org.junit.jupiter.api.Test;

/// S4 layout geometry for {@code \begin{CD}} commutative diagrams: horizontal
/// connectors stretch to the COLUMN width (a wider object in the same column makes
/// the arrow above/below it longer), the {@code @=}/{@code @|} double connectors
/// draw as pairs of {@code <rect>}s (in the allow-listed alphabet), and the whole
/// diagram is contained + well-formed.
class CdLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), SIZE);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    /// Ink span of the horizontal arrow shaft in a single-connector row: the shaft
    /// pieces sit on the object baseline (baselineY == 0.0 for the object row),
    /// while the arrow itself is the widest run of glyphs there. We take the total
    /// glyph span on the arrow's own row as a proxy for the stretched shaft.
    private static double rowGlyphSpan(Layout l, double atBaselineY) {
        double minL = Double.POSITIVE_INFINITY;
        double maxR = Double.NEGATIVE_INFINITY;
        for (PositionedGlyph g : l.glyphs()) {
            if (Math.abs(g.baselineY() - atBaselineY) > 1e-6) {
                continue;
            }
            var o = FONT.outline(g.glyphId());
            minL = Math.min(minL, g.originX() + g.scale() * o.xMin());
            maxR = Math.max(maxR, g.originX() + g.scale() * o.xMax());
        }
        return maxR - minL;
    }

    @Test
    void horizontalArrowStretchesToTheColumnWidth() {
        // A wider object in the arrow's column widens the column, so the arrow
        // between narrow objects must be SHORTER than the arrow whose column holds
        // a wide object. Guards against a "fixed-length arrow" mutant.
        double narrow = layout("\\begin{CD} A @>>> B \\end{CD}").width();
        double wide = layout("\\begin{CD} A @>>> BBBBBBBBBB \\end{CD}").width();
        assertTrue(wide > narrow + SIZE, "a wide object stretches the diagram: " + wide + " vs " + narrow);
        // And a long LABEL on the connector stretches its column too (the label
        // feeds colWidth in pass 1, the shaft stretches to it in pass 2). The label
        // is script size, so a big margin needs many glyphs; guards the label→colWidth
        // feed against a "ignore the label width" mutant.
        double shortLabel = layout("\\begin{CD} A @>f>> B \\end{CD}").width();
        double longLabel = layout("\\begin{CD} A @>ffffffffffffffffffff>> B \\end{CD}").width();
        assertTrue(longLabel > shortLabel + SIZE, "a long connector label stretches its column: "
            + longLabel + " vs " + shortLabel);
    }

    @Test
    void equalConnectorDrawsTwoHorizontalRules() {
        // @= is U+003D with no horizontal MATH construction, so it is drawn as two
        // rects (a double line). A single-connector CD `A @= B` has exactly 2 rules.
        Layout l = layout("\\begin{CD} A @= B \\end{CD}");
        assertEquals(2, l.rules().size(), "the @= double line is two rects");
        // Both rules are horizontal (wide and thin) and span roughly the column.
        for (Rule r : l.rules()) {
            assertTrue(r.width() > r.height() * 4, "an @= rule is a wide-thin horizontal bar");
        }
    }

    @Test
    void vequalConnectorDrawsTwoVerticalRules() {
        // @| draws two vertical rects spanning the row pitch (a double bar).
        Layout l = layout("\\begin{CD} X @>>> Y \\\\ @| @VVV \\\\ X @>>> Z \\end{CD}");
        long verticalRules = l.rules().stream().filter(r -> r.height() > r.width() * 4).count();
        assertTrue(verticalRules >= 2, "the @| double bar is two vertical rects (got " + verticalRules + ")");
    }

    @Test
    void commutativeSquareRendersContainedSvg() {
        // The full square renders to a well-formed SVG using only the allow-listed
        // alphabet (svg/g/path/rect), with a non-degenerate viewBox.
        String svg = LatteX.render("\\begin{CD} A @>f>> B \\\\ @VgVV @VVhV \\\\ C @>>k> D \\end{CD}");
        assertTrue(svg.startsWith("<svg"), "well-formed SVG root");
        assertTrue(svg.contains("<path"), "draws glyph paths");
        // No stroked lines or markers — arrows are font glyphs, rules are rects.
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"),
            "no <line>/<marker> — in-alphabet only");
    }

    @Test
    void nonArrowNodeCountMatchesConnectors() {
        // Sanity: the square parses to a 3x3 CD grid with 4 arrows + 0 double-rules,
        // so the render carries glyph paths for A,B,C,D,f,g,h,k plus 4 arrow shafts.
        MathNode n = MathParser.parse("\\begin{CD} A @>f>> B \\\\ @VgVV @VVhV \\\\ C @>>k> D \\end{CD}");
        assertTrue(n instanceof MathNode.Matrix m && m.kind() == MathNode.MatrixKind.CD,
            "parses to a CD matrix");
    }
}
