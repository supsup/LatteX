package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.MathKernInfo;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

/**
 * L9 — OpenType math-kern cut-ins applied to script placement (plan
 * lattex-mathkern-scripts). SfntFont has parsed the per-corner staircases since M0;
 * this pins that layout actually CONSUMES them: a subscript on a slanted glyph tucks
 * into the BottomRight staircase, a superscript on an overhanging glyph clears the
 * TopRight one. Expected values are read from the REAL STIX tables at runtime, so
 * the test is a genuine consumption pin, not a hardcode.
 */
class MathKernScriptsTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    /** The leftmost glyph whose id differs from the base's — the script glyph. */
    private static PositionedGlyph scriptGlyph(Layout l, int baseGid) {
        // Without this, a baseGid the layout never drew makes the filter return the BASE
        // itself (at x = 0), and "the subscript tucks in" passes vacuously, which is exactly
        // what happened when math letters moved to their italic glyphs (plan a85ff403).
        assertTrue(l.glyphs().stream().anyMatch(g -> g.glyphId() == baseGid),
            "the expected base glyph is drawn");
        return l.glyphs().stream()
            .filter(g -> g.glyphId() != baseGid)
            .min(Comparator.comparingDouble(PositionedGlyph::originX))
            .orElseThrow();
    }

    @Test
    void subscriptTucksIntoTheBottomRightStaircase() {
        // STIX italic 'V' carries a real BottomRight staircase with NEGATIVE kerns — the
        // canonical V-subscript tuck. Without the kern the subscript sits at the
        // full advance; with it, strictly left of that.
        int vGid = FONT.glyphId(0x1D449); // math-mode V draws MATHEMATICAL ITALIC CAPITAL V
        MathKernInfo kern = FONT.mathKernInfo(vGid);
        assertNotNull(kern, "STIX V has kern info");
        assertNotNull(kern.bottomRight(), "STIX V has a BottomRight staircase");

        Layout l = layout("V_1");
        PositionedGlyph sub = scriptGlyph(l, vGid);
        double baseAdvancePx = FONT.advanceWidth(vGid) * (40.0 / FONT.unitsPerEm());
        assertTrue(sub.originX() < baseAdvancePx - 0.5,
            "subscript tucks in: x=" + sub.originX() + " vs advance=" + baseAdvancePx);
    }

    @Test
    void superscriptClearsAPositiveTopRightKern() {
        // STIX italic 'W' carries a constant POSITIVE TopRight kern (+70 units): the
        // superscript must be pushed RIGHT past italic correction, never tucked. (This was
        // upright 'f', +82, while math letters drew the upright glyph; the italic f has no
        // TopRight staircase at all — plan a85ff403.)
        int wGid = FONT.glyphId(0x1D44A); // math-mode W draws MATHEMATICAL ITALIC CAPITAL W
        MathKernInfo kern = FONT.mathKernInfo(wGid);
        assertNotNull(kern);
        assertNotNull(kern.topRight());
        double scale = 40.0 / FONT.unitsPerEm();
        double expectedKernPx = kern.topRight().kernAtHeight(0) * scale; // constant staircase
        assertTrue(expectedKernPx > 0, "precondition: a positive TopRight kern");

        Layout l = layout("W^2");
        PositionedGlyph sup = scriptGlyph(l, wGid);
        double baseAdvancePx = FONT.advanceWidth(wGid) * scale;
        double italicPx = FONT.italicCorrection(wGid) * scale;
        assertEquals(baseAdvancePx + italicPx + expectedKernPx, sup.originX(), 0.01,
            "superscript x = advance + italic + TopRight kern (from the real table)");
    }

    @Test
    void glyphWithoutKernInfoIsUnchanged() {
        // STIX italic 'M' has NO right-corner kern: placement must be the pre-L9 formula
        // exactly — the no-kern path is byte-stable. (This was upright 'J'; the italic J
        // carries a BottomRight staircase — plan a85ff403.)
        int mGid = FONT.glyphId(0x1D440); // math-mode M draws MATHEMATICAL ITALIC CAPITAL M
        MathKernInfo mKern = FONT.mathKernInfo(mGid);
        assertTrue(mKern == null || (mKern.topRight() == null && mKern.bottomRight() == null),
            "precondition: M has no right-corner staircases");
        Layout l = layout("M_1");
        PositionedGlyph sub = scriptGlyph(l, mGid);
        double baseAdvancePx = FONT.advanceWidth(mGid) * (40.0 / FONT.unitsPerEm());
        assertEquals(baseAdvancePx, sub.originX(), 0.01, "no kern -> bare advance");
    }
}
