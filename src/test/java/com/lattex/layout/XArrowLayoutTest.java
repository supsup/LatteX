package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

/**
 * S4 layout geometry for the extensible labelled arrows — amsmath's
 * {@code xrightarrow[below]{above}} / {@code xleftarrow} (written here without
 * the backslash). The inverse of the stack-brace mechanism: the BASE (the arrow,
 * via the OpenType MATH horizontal glyph construction on U+2192 / U+2190)
 * stretches to the script-size labels, never shorter than its natural length.
 */
class XArrowLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), SIZE);
        MathNode node = MathParser.parse(latex);
        return LayoutEngine.layout(node, ctx);
    }

    private static double width(String latex) {
        Layout l = layout(latex);
        return l.maxX() - l.minX();
    }

    /// Ink span of the ARROW GLYPH ITSELF (not the composite box). The arrow
    /// assembly pieces sit on the baseline (baselineY == 0.0); the labels are
    /// raised/dropped, so filtering to baselineY == 0.0 isolates the arrow. This
    /// is what actually stretches — the box width tracks the label via the final
    /// `max(arrowBox.width(), labelWidth)` even when the arrow does NOT stretch,
    /// so a box-width assertion is vacuous against the drop-label-width mutant.
    private static double arrowSpan(String latex) {
        Layout l = layout(latex);
        double minL = Double.POSITIVE_INFINITY;
        double maxR = Double.NEGATIVE_INFINITY;
        for (PositionedGlyph g : l.glyphs()) {
            if (g.baselineY() != 0.0) {
                continue;
            }
            GlyphOutline o = FONT.outline(g.glyphId());
            minL = Math.min(minL, g.originX() + g.scale() * o.xMin());
            maxR = Math.max(maxR, g.originX() + g.scale() * o.xMax());
        }
        return maxR - minL;
    }

    // ---- The stretch (mutant target) --------------------------------------

    @Test
    void arrowGrowsWithItsLabel() {
        // The ARROW GLYPH must stretch to the label width. MUTANT: drop the
        // label-width term from the stretch target in xArrowBox (target =
        // natural + 2*pad) and the arrow stays natural under both labels — the
        // arrow SPAN stops tracking the label, failing this assertion. (A box-
        // width check would NOT catch it: the box width still grows with the
        // label via the final max(arrowBox.width(), labelWidth).)
        double longLabel = arrowSpan("\\xrightarrow{aaaaaaaaaa}");
        double shortLabel = arrowSpan("\\xrightarrow{a}");
        assertTrue(longLabel > shortLabel + 1.0,
            "a longer label must stretch the ARROW itself: " + longLabel + " vs " + shortLabel);
    }

    @Test
    void belowLabelAloneAlsoStretchesTheArrow() {
        double wide = arrowSpan("\\xrightarrow[aaaaaaaaaa]{}");
        double narrow = arrowSpan("\\xrightarrow[a]{}");
        assertTrue(wide > narrow + 1.0,
            "the optional below label also drives the arrow stretch: " + wide + " vs " + narrow);
    }

    // ---- Minimum length ----------------------------------------------------

    @Test
    void xarrowIsWiderThanThePlainArrowButNeverShorterThanNatural() {
        double natural = width("\\rightarrow");
        double xarrow = width("\\xrightarrow{d}");
        assertTrue(xarrow > natural,
            "xrightarrow{d} must exceed the natural arrow (" + natural + "), got " + xarrow);
        // Even an empty label never shrinks the arrow below its natural length.
        double bare = width("\\xrightarrow{}");
        assertTrue(bare >= natural,
            "a bare xrightarrow{} is never shorter than the natural arrow ("
                + natural + "), got " + bare);
    }

    // ---- Label placement ---------------------------------------------------

    @Test
    void aboveLabelSitsAboveTheArrowAtScriptSizeAndCentred() {
        Layout l = layout("\\xrightarrow{f}");
        // The label glyph is the topmost (most-negative baselineY) glyph; the
        // arrow pieces sit on the baseline (y == 0).
        PositionedGlyph label = l.glyphs().stream()
            .min(Comparator.comparingDouble(PositionedGlyph::baselineY)).orElseThrow();
        assertTrue(label.baselineY() < 0.0, "the label is raised above the arrow");
        double arrowScale = l.glyphs().stream()
            .filter(g -> g.baselineY() == 0.0)
            .mapToDouble(PositionedGlyph::scale).max().orElseThrow();
        assertTrue(label.scale() < arrowScale, "the label is script-size");
        // Centred: the label ink's centre is near the arrow's ink centre. The
        // layout centres on advance widths (like every stack), so asymmetric side
        // bearings shift ink centres by a few units — 0.1em of tolerance still
        // catches a flush-left/right label (off by ~half the width difference).
        GlyphOutline lo = FONT.outline(label.glyphId());
        double labelCenter = label.originX()
            + label.scale() * (lo.xMin() + lo.xMax()) / 2.0;
        double boxCenter = (l.minX() + l.maxX()) / 2.0;
        assertTrue(Math.abs(labelCenter - boxCenter) < 0.1 * SIZE,
            "label centred over the arrow: " + labelCenter + " vs " + boxCenter);
    }

    @Test
    void twoLabelCasePlacesOneAboveAndOneBelow() {
        Layout l = layout("\\xrightarrow[g]{f}");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() < -1.0),
            "an above-label glyph is raised over the arrow");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() > 1.0),
            "a below-label glyph is dropped under the arrow");
    }

    // ---- xleftarrow mirrors -------------------------------------------------

    @Test
    void xleftarrowMirrorsTheRightArrow() {
        Layout right = layout("\\xrightarrow{ff}");
        Layout left = layout("\\xleftarrow{ff}");
        // Same stretch behaviour (same label -> same width within a hair) but a
        // different arrowhead glyph set (the head points the other way).
        assertEquals(right.maxX() - right.minX(), left.maxX() - left.minX(), 2.0);
        var rightGids = right.glyphs().stream().map(PositionedGlyph::glyphId).sorted().toList();
        var leftGids = left.glyphs().stream().map(PositionedGlyph::glyphId).sorted().toList();
        assertTrue(!rightGids.equals(leftGids),
            "left and right arrows draw different arrowhead glyphs");
        double wide = arrowSpan("\\xleftarrow{aaaaaaaaaa}");
        double narrow = arrowSpan("\\xleftarrow{a}");
        assertTrue(wide > narrow + 1.0, "the xleftarrow GLYPH stretches with its label too");
    }

    // ---- Spacing class (REL) ------------------------------------------------

    @Test
    void xarrowSpacesAsARelation() {
        // ORD-REL and REL-ORD boundaries each get a thick (5mu) space, so the row
        // a<arrow>b is wider than the sum of its parts by ~10mu.
        double row = width("a\\xrightarrow{f}b");
        double parts = width("a") + width("\\xrightarrow{f}") + width("b");
        double mu = SIZE / 18.0; // 1mu in user units at this size
        assertTrue(row > parts + 9.0 * mu,
            "REL spacing on both sides (~10mu expected): row=" + row + " parts=" + parts);
    }
}
