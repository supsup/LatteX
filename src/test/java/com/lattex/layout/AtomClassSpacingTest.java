package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import org.junit.jupiter.api.Test;

/**
 * The load-bearing property of the atom-class wrappers ({@code \mathopen}
 * {@code \mathclose} {@code \mathord} {@code \mathbin} {@code \mathrel}
 * {@code \mathpunct}): they change nothing about the GLYPHS and everything about
 * the INTER-ATOM GLUE the enclosing row inserts around them.
 *
 * <p>Every assertion here is a width DIFFERENCE against the same row written with
 * a plain ordinary atom, so it pins the TeXbook Ch.18 spacing table (thin 3mu /
 * medium 4mu / thick 5mu) rather than merely "the command parsed". A wrapper that
 * was accepted but laid out transparently — the obvious wrong implementation —
 * makes every difference zero and fails here.
 */
class AtomClassSpacingTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double FONT_SIZE = 40.0;
    private static final LayoutContext CTX =
        new LayoutContext(FONT, FONT.mathConstants(), FONT_SIZE);
    /** One math unit in user units at display style (18mu = 1em). */
    private static final double MU = CTX.mu();
    private static final double EPS = 1e-9;

    private static double width(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex), CTX).width();
    }

    @Test
    void eachForcedClassInsertsItsOwnTeXbookGlue() {
        // Baseline: three bare ordinary atoms. Ord-Ord spacing is 0 in every style,
        // so this is exactly the sum of the three advance widths.
        double ord = width("xyz");
        assertTrue(ord > 0, "baseline row has ink");

        // \mathord forces the class the middle atom already had -> no change at all.
        assertEquals(ord, width("x\\mathord{y}z"), EPS,
            "\\mathord{y} between two Ord atoms must not move anything");

        // Ord|Bin and Bin|Ord are both MEDIUM (4mu) -> +8mu across the row.
        assertEquals(ord + 8 * MU, width("x\\mathbin{y}z"), EPS,
            "\\mathbin must take medium binary-operator glue on both sides");

        // Ord|Rel and Rel|Ord are both THICK (5mu) -> +10mu.
        assertEquals(ord + 10 * MU, width("x\\mathrel{y}z"), EPS,
            "\\mathrel must take thick relation glue on both sides");

        // Ord|Punct is 0, Punct|Ord is THIN (3mu) -> +3mu, and only on the right.
        assertEquals(ord + 3 * MU, width("x\\mathpunct{y}z"), EPS,
            "\\mathpunct must take thin glue after it and none before it");

        // Ord|Open and Open|Ord are both 0; likewise Ord|Close and Close|Ord.
        assertEquals(ord, width("x\\mathopen{y}z"), EPS,
            "an Open atom is tight on both sides");
        assertEquals(ord, width("x\\mathclose{y}z"), EPS,
            "a Close atom is tight on both sides");

        // The four classes are mutually distinguishable, not just non-zero.
        assertTrue(width("x\\mathord{y}z") < width("x\\mathpunct{y}z"));
        assertTrue(width("x\\mathpunct{y}z") < width("x\\mathbin{y}z"));
        assertTrue(width("x\\mathbin{y}z") < width("x\\mathrel{y}z"));
    }

    @Test
    void theWrapperOverridesTheBodysOwnClassRatherThanAddingToIt() {
        // \cdot is natively a BIN atom, so "x\cdot z" already carries 8mu of medium
        // glue. Forcing it to REL must REPLACE that with 10mu (a +2mu delta), and
        // forcing it to ORD must remove the glue entirely — proving classOf reports
        // the forced class instead of consulting the body.
        double nativeBin = width("x\\cdot z");

        assertEquals(nativeBin + 2 * MU, width("x\\mathrel{\\cdot}z"), EPS,
            "\\mathrel must REPLACE \\cdot's medium Bin glue with thick Rel glue"
                + " (4mu -> 5mu a side), not stack onto it");
        assertEquals(nativeBin - 8 * MU, width("x\\mathord{\\cdot}z"), EPS,
            "\\mathord must strip \\cdot's binary-operator glue entirely");
        assertEquals(nativeBin, width("x\\mathbin{\\cdot}z"), EPS,
            "re-forcing the class \\cdot already has is a no-op");
    }

    @Test
    void theGlyphsThemselvesAreUntouchedByTheWrapper() {
        // Layout-transparency: same glyph count, same advance widths — the wrapper
        // may only move things by the row's glue, never restyle or re-size them.
        Layout bare = LayoutEngine.layout(MathParser.parse("y"), CTX);
        Layout wrapped = LayoutEngine.layout(MathParser.parse("\\mathrel{y}"), CTX);
        assertEquals(bare.glyphs().size(), wrapped.glyphs().size());
        assertEquals(bare.width(), wrapped.width(), EPS,
            "a wrapper alone in a formula has no neighbour to space against");
        assertEquals(bare.glyphs().get(0).glyphId(), wrapped.glyphs().get(0).glyphId());
        assertEquals(bare.glyphs().get(0).scale(), wrapped.glyphs().get(0).scale(), EPS);
    }

    @Test
    void conditionalGlueStillVanishesInScriptStyles() {
        // The forced class flows through the SAME conditional-space rule as a native
        // atom: medium/thick glue is suppressed in script styles (TeXbook Ch.18), so
        // a \mathbin inside a superscript must not widen it.
        LayoutContext script = CTX.superscript();
        double ord = LayoutEngine.layout(MathParser.parse("xyz"), script).width();
        double bin = LayoutEngine.layout(MathParser.parse("x\\mathbin{y}z"), script).width();
        assertEquals(ord, bin, EPS,
            "medium binary glue is a conditional space and vanishes in script style");
    }

    @Test
    void aForcedBinStillObeysTeXsBinReclassification() {
        // TeX demotes a Bin with no valid left operand to Ord (Appendix G / §726).
        // A FORCED Bin is a Bin like any other, so a leading \mathbin{y} must be
        // demoted too — it must not smuggle glue past the reclassification pass.
        assertEquals(width("yz"), width("\\mathbin{y}z"), EPS,
            "a row-leading forced Bin is reclassified to Ord");
        // ...and the same demotion right after a relation.
        assertEquals(width("x=yz"), width("x=\\mathbin{y}z"), EPS,
            "a forced Bin directly after a Rel is reclassified to Ord");
    }
}
