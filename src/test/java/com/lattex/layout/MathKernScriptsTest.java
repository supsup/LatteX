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
        return l.glyphs().stream()
            .filter(g -> g.glyphId() != baseGid)
            .min(Comparator.comparingDouble(PositionedGlyph::originX))
            .orElseThrow();
    }

    @Test
    void subscriptTucksIntoTheBottomRightStaircase() {
        // STIX 'V' carries a real BottomRight staircase with NEGATIVE kerns — the
        // canonical V-subscript tuck. Without the kern the subscript sits at the
        // full advance; with it, strictly left of that.
        int vGid = FONT.glyphId('V');
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
        // STIX 'f' carries a constant POSITIVE TopRight kern (+82 units): the
        // superscript must be pushed RIGHT past italic correction, never tucked.
        int fGid = FONT.glyphId('f');
        MathKernInfo kern = FONT.mathKernInfo(fGid);
        assertNotNull(kern);
        assertNotNull(kern.topRight());
        double scale = 40.0 / FONT.unitsPerEm();
        double expectedKernPx = kern.topRight().kernAtHeight(0) * scale; // constant staircase

        Layout l = layout("f^2");
        PositionedGlyph sup = scriptGlyph(l, fGid);
        double baseAdvancePx = FONT.advanceWidth(fGid) * scale;
        double italicPx = FONT.italicCorrection(fGid) * scale;
        assertEquals(baseAdvancePx + italicPx + expectedKernPx, sup.originX(), 0.01,
            "superscript x = advance + italic + TopRight kern (from the real table)");
    }

    @Test
    void glyphWithoutKernInfoIsUnchanged() {
        // STIX 'J' has NO kern info: placement must be the pre-L9 formula exactly —
        // the no-kern path is byte-stable.
        int jGid = FONT.glyphId('J');
        MathKernInfo jKern = FONT.mathKernInfo(jGid);
        assertTrue(jKern == null || (jKern.topRight() == null && jKern.bottomRight() == null),
            "precondition: J has no right-corner staircases");
        Layout l = layout("J_1");
        PositionedGlyph sub = scriptGlyph(l, jGid);
        double baseAdvancePx = FONT.advanceWidth(jGid) * (40.0 / FONT.unitsPerEm());
        assertEquals(baseAdvancePx, sub.originX(), 0.01, "no kern -> bare advance");
    }
}
