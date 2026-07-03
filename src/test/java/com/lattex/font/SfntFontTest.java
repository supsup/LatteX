package com.lattex.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Sanity checks that the bundled STIX Two Math font parses correctly. */
class SfntFontTest {

    private static final int GLYPH_X = 'x'; // U+0078
    private static final int GLYPH_2 = '2'; // U+0032

    @Test
    void fontLoadsFromResources() {
        SfntFont font = SfntFont.loadBundled();
        assertNotNull(font);
        // STIX Two Math is a 1000 upm font.
        assertEquals(1000, font.unitsPerEm(), "unitsPerEm");
        assertTrue(font.numGlyphs() > 100, "numGlyphs should be substantial");
    }

    @Test
    void xAndTwoResolveToDistinctGlyphs() {
        SfntFont font = SfntFont.loadBundled();
        int gx = font.glyphId(GLYPH_X);
        int g2 = font.glyphId(GLYPH_2);
        assertTrue(gx > 0, "x maps to a glyph");
        assertTrue(g2 > 0, "2 maps to a glyph");
        assertNotEquals(gx, g2, "x and 2 are different glyphs");
    }

    @Test
    void outlinesAreNonEmptyWithRealContours() {
        SfntFont font = SfntFont.loadBundled();
        for (int cp : new int[] {GLYPH_X, GLYPH_2}) {
            int gid = font.glyphId(cp);
            GlyphOutline outline = font.outline(gid);
            assertFalse(outline.isEmpty(), "outline for U+%04X non-empty".formatted(cp));
            assertFalse(outline.contours().isEmpty(), "has contours");
            int totalPoints = outline.contours().stream().mapToInt(c -> c.points().size()).sum();
            assertTrue(totalPoints >= 4, "contour has real points: " + totalPoints);
            assertTrue(outline.xMax() > outline.xMin(), "bbox has positive width");
            assertTrue(outline.yMax() > outline.yMin(), "bbox has positive height");
        }
    }

    @Test
    void advancesArePositive() {
        SfntFont font = SfntFont.loadBundled();
        assertTrue(font.advanceWidth(font.glyphId(GLYPH_X)) > 0, "x advance");
        assertTrue(font.advanceWidth(font.glyphId(GLYPH_2)) > 0, "2 advance");
    }

    @Test
    void mathConstantsAreSane() {
        SfntFont font = SfntFont.loadBundled();
        MathConstants mc = font.mathConstants();
        assertTrue(mc.scriptPercentScaleDown() > 0 && mc.scriptPercentScaleDown() <= 100,
            "scriptPercentScaleDown in (0,100]: " + mc.scriptPercentScaleDown());
        assertTrue(mc.superscriptShiftUp() > 0 && mc.superscriptShiftUp() < font.unitsPerEm(),
            "superscriptShiftUp positive and sub-em: " + mc.superscriptShiftUp());
    }
}
