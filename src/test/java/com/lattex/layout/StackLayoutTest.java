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
 * S4 layout geometry for the shared stack mechanism: {@code \\underbrace} /
 * {@code \overbrace} (a horizontally-stretched brace plus a script-size label),
 * {@code \stackrel} / {@code \overset} / {@code \\underset} (a script-size mark
 * over/under the base), and {@code \substack} (a single-column script-size grid).
 *
 * <p>The geometry is derived clean-room from Knuth's TeXbook (the {@code \mathop}
 * limit rules) and the OpenType MATH {@code stackGapMin} constant.
 */
class StackLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), SIZE);
        MathNode node = MathParser.parse(latex);
        return LayoutEngine.layout(node, ctx);
    }

    /** The clearance (user units) between the base's ink top and the mark's ink bottom. */
    private static double overGap(Layout l) {
        // The base glyph sits on the baseline (|baselineY| smallest); the over-mark
        // is the topmost glyph (most-negative baselineY).
        PositionedGlyph base = l.glyphs().stream()
            .min(Comparator.comparingDouble(g -> Math.abs(g.baselineY()))).orElseThrow();
        PositionedGlyph mark = l.glyphs().stream()
            .min(Comparator.comparingDouble(PositionedGlyph::baselineY)).orElseThrow();
        GlyphOutline bo = FONT.outline(base.glyphId());
        GlyphOutline mo = FONT.outline(mark.glyphId());
        double baseInkTop = base.baselineY() - base.scale() * bo.yMax();
        double markInkBottom = mark.baselineY() - mark.scale() * mo.yMin();
        return baseInkTop - markInkBottom; // > 0 when the mark clears the base
    }

    // ---- The stack gap (mutant target) -----------------------------------

    @Test
    void overMarkClearsTheBaseByTheStackGap() {
        // \overset stacks the annotation above the base separated by stackGapMin.
        // MUTANT: delete the `- gap` term in stackBox's up-loop and this clearance
        // collapses to ~0 (the mark's ink bottom meets the base's ink top), failing
        // the assertion below.
        Layout l = layout("\\overset{a}{M}");
        assertEquals(2, l.glyphs().size(), "base M + annotation a");
        double gap = overGap(l);
        double expected = FONT.mathConstants().stackGapMin() * (SIZE / FONT.unitsPerEm());
        assertTrue(gap > 0.4 * expected,
            "the over-mark must clear the base by ~stackGapMin (" + expected
                + "), got " + gap);
    }

    @Test
    void oversetIsTallerThanTheBareBase() {
        // The stacked form reaches higher than the base alone by at least the gap.
        double stacked = -layout("\\overset{a}{M}").minY();
        double bare = -layout("M").minY();
        assertTrue(stacked > bare, "an over-mark raises the top above the bare base");
    }

    // ---- Script sizing ----------------------------------------------------

    @Test
    void substackCellsAreSetAtScriptSize() {
        // A \substack cell is one style down (script), so its glyphs are drawn at a
        // smaller scale than the same letter at the outer (display) size.
        double outer = layout("x").glyphs().get(0).scale();
        double inner = layout("\\substack{x\\\\y}").glyphs().stream()
            .mapToDouble(PositionedGlyph::scale).max().orElseThrow();
        assertTrue(inner < outer, "substack cells are script-size (" + inner + " < " + outer + ")");
    }

    @Test
    void oversetAnnotationIsScriptSize() {
        double base = layout("\\overset{a}{M}").glyphs().stream()
            .min(Comparator.comparingDouble(g -> Math.abs(g.baselineY()))).orElseThrow().scale();
        double mark = layout("\\overset{a}{M}").glyphs().stream()
            .min(Comparator.comparingDouble(PositionedGlyph::baselineY)).orElseThrow().scale();
        assertTrue(mark < base, "the annotation is script-size relative to the base");
    }

    // ---- Class implications ----------------------------------------------

    @Test
    void stackrelIsRelClassButOversetKeepsTheBaseClass() {
        // \stackrel's result is Rel, so an ordinary neighbour gets thick (5mu)
        // relation spacing on each side; the same content as an \overset keeps the
        // base's Ord class (no inter-atom spacing), so the stackrel form is wider.
        double stackrel = layout("a\\stackrel{x}{y}b").maxX();
        double overset = layout("a\\overset{x}{y}b").maxX();
        double mu = SIZE / 18.0; // 1mu in user units (baseScale * unitsPerEm == SIZE)
        assertTrue(stackrel > overset + 6.0 * mu,
            "stackrel (Rel) draws relation spacing that overset (Ord) does not: "
                + stackrel + " vs " + overset);
    }

    // ---- The brace (honest font construction) ----------------------------

    @Test
    void underbraceDrawsABraceAndLabelBelowTheBase() {
        // \\underbrace{ab}_{n}: base a,b on the baseline, a stretched brace below
        // them, and the label n below the brace — so there are more glyphs than the
        // three content characters, and the ink reaches well below the baseline.
        Layout l = layout("\\underbrace{ab}_{n}");
        assertTrue(l.glyphs().size() >= 4,
            "base(2) + brace(>=1) + label(1), got " + l.glyphs().size());
        assertTrue(l.maxY() > 5.0, "the brace and label extend below the baseline");
        // A glyph sits strictly below the baseline (the brace / label), not just the
        // base row near y=0.
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() > 5.0),
            "a brace/label glyph is placed below the base");
    }

    @Test
    void overbraceDrawsAboveTheBase() {
        Layout l = layout("\\overbrace{ab}^{n}");
        assertTrue(l.glyphs().size() >= 4, "base + brace + label");
        assertTrue(-l.minY() > 5.0, "the brace and label extend above the baseline");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() < -5.0),
            "a brace/label glyph is placed above the base");
    }

    @Test
    void wideUnderbraceStretchesTheBraceToTheBase() {
        // A wide base yields a wider brace than a narrow one — the brace tracks the
        // base width via the OpenType MATH horizontal construction (honest glyphs).
        double wide = layout("\\underbrace{aaaaaaaa}").maxX();
        double narrow = layout("\\underbrace{aa}").maxX();
        assertTrue(wide > narrow, "the brace stretches with the base width");
    }
}
