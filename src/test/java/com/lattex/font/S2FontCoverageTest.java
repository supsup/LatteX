package com.lattex.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * S2 coverage tests: composite glyphs, full MathConstants, MathKern, per-glyph
 * italics/top-accent, and MathVariants — parsed from the bundled STIX Two Math
 * font. M0's {@link SfntFontTest} covers the walking-skeleton basics; this class
 * adds the S2 font layer without disturbing those.
 */
class S2FontCoverageTest {

    private static SfntFont font() {
        return SfntFont.loadBundled();
    }

    // -- composite glyphs ---------------------------------------------------

    @Test
    void eAcuteIsCompositeAndAssemblesToNonEmptyOutline() {
        SfntFont f = font();
        int gid = f.glyphId(0x00E9); // 'é'
        assertTrue(gid > 0, "é maps to a glyph");

        List<Integer> comps = f.compositeComponents(gid);
        assertEquals(2, comps.size(), "é = base 'e' + acute accent, two components");
        // Components must themselves be real glyphs.
        for (int c : comps) {
            assertTrue(c > 0 && c < f.numGlyphs(), "component glyph id in range: " + c);
        }

        GlyphOutline o = f.outline(gid);
        assertFalse(o.isEmpty(), "composite outline is non-empty");
        // 'e' contributes 2 contours, the acute 1 → the assembled glyph has >= 3.
        assertTrue(o.contours().size() >= 3, "assembled contours: " + o.contours().size());
        assertTrue(o.xMax() > o.xMin() && o.yMax() > o.yMin(), "positive bbox");

        int totalPoints = o.contours().stream().mapToInt(ct -> ct.points().size()).sum();
        assertTrue(totalPoints > 10, "assembled glyph has real points: " + totalPoints);
    }

    @Test
    void everyGlyphYieldsAnOutlineAndCompositesAreNonEmpty() {
        SfntFont f = font();
        int composites = 0;
        for (int g = 0; g < f.numGlyphs(); g++) {
            GlyphOutline o = f.outline(g); // must never throw (simple OR composite)
            assertNotNull(o);
            if (!f.compositeComponents(g).isEmpty()) {
                composites++;
                assertFalse(o.isEmpty(),
                    "composite glyph " + g + " must assemble to a non-empty outline");
                assertFalse(o.contours().isEmpty(), "composite " + g + " has contours");
            }
        }
        assertTrue(composites > 100, "STIX Two Math has many composites: " + composites);
    }

    // -- metrics ------------------------------------------------------------

    @Test
    void advancesAndLeftSideBearingsReadableForAllGlyphs() {
        SfntFont f = font();
        for (int g = 0; g < f.numGlyphs(); g++) {
            assertTrue(f.advanceWidth(g) >= 0, "advance non-negative for " + g);
            f.leftSideBearing(g); // must not throw for any glyph id
        }
        int gx = f.glyphId('x');
        assertTrue(f.advanceWidth(gx) > 0, "x has a positive advance");
    }

    // -- full MathConstants -------------------------------------------------

    @Test
    void mathConstantsFullyParsedWithKnownStixValues() {
        MathConstants m = font().mathConstants();
        // Values pinned to STIX Two Math (unitsPerEm = 1000).
        assertEquals(70, m.scriptPercentScaleDown(), "scriptPercentScaleDown");
        assertEquals(55, m.scriptScriptPercentScaleDown(), "scriptScriptPercentScaleDown");
        assertEquals(258, m.axisHeight(), "axisHeight");
        assertEquals(68, m.fractionRuleThickness(), "fractionRuleThickness");
        assertEquals(68, m.radicalRuleThickness(), "radicalRuleThickness");
        assertEquals(480, m.accentBaseHeight(), "accentBaseHeight");
        assertEquals(360, m.superscriptShiftUp(), "superscriptShiftUp");
        assertEquals(55, m.radicalDegreeBottomRaisePercent(), "radicalDegreeBottomRaisePercent");

        // The whole table read past its end without skewing: late fields are sane.
        assertTrue(m.delimitedSubFormulaMinHeight() > 0, "delimitedSubFormulaMinHeight");
        assertTrue(m.displayOperatorMinHeight() > m.delimitedSubFormulaMinHeight(),
            "displayOperatorMinHeight");
        assertTrue(m.subscriptShiftDown() > 0, "subscriptShiftDown");
        assertTrue(m.overbarRuleThickness() > 0, "overbarRuleThickness");
        assertTrue(m.underbarRuleThickness() > 0, "underbarRuleThickness");
        assertTrue(m.stackGapMin() > 0, "stackGapMin");
        assertTrue(m.radicalKernBeforeDegree() > 0, "radicalKernBeforeDegree");
    }

    // -- italics correction + top-accent attachment -------------------------

    @Test
    void italicsCorrectionAndTopAccentQueryablePerGlyph() {
        SfntFont f = font();
        int italicHits = 0;
        int accentHits = 0;
        for (int g = 0; g < f.numGlyphs(); g++) {
            if (f.italicCorrection(g) != 0) {
                italicHits++;
            }
            if (f.topAccentAttachment(g) != 0) {
                accentHits++;
            }
        }
        assertTrue(italicHits > 0, "some glyphs carry italics correction: " + italicHits);
        assertTrue(accentHits > 0, "some glyphs carry a top-accent attachment: " + accentHits);
    }

    // -- MathKernInfo -------------------------------------------------------

    @Test
    void mathKernStaircaseParsesAndLooksUpByHeight() {
        SfntFont f = font();
        MathKern found = null;
        for (int g = 0; g < f.numGlyphs() && found == null; g++) {
            MathKernInfo ki = f.mathKernInfo(g);
            if (ki != null && ki.topRight() != null && !ki.topRight().correctionHeights().isEmpty()) {
                found = ki.topRight();
            }
        }
        assertNotNull(found, "at least one glyph has a top-right kern staircase");

        // Structural invariant: kernValues = correctionHeights + 1 (one per band).
        assertEquals(found.correctionHeights().size() + 1, found.kernValues().size(),
            "kernValues has one entry per height band");

        // A very-low attachment lands in the first band; a very-high one in the last.
        assertEquals(found.kernValues().get(0), found.kernAtHeight(Integer.MIN_VALUE),
            "below all heights -> first kern");
        assertEquals(found.kernValues().get(found.kernValues().size() - 1),
            found.kernAtHeight(Integer.MAX_VALUE), "above all heights -> last kern");
    }

    // -- MathVariants -------------------------------------------------------

    @Test
    void parenHasVerticalVariantsAndAnAssembly() {
        SfntFont f = font();
        int gid = f.glyphId('('); // U+0028, a stretchy delimiter
        MathGlyphConstruction vc = f.verticalVariants(gid);
        assertNotNull(vc, "( has a vertical construction");

        assertTrue(vc.variants().size() > 1, "multiple pre-drawn size variants");
        int prev = -1;
        for (MathGlyphVariant v : vc.variants()) {
            assertTrue(v.glyphId() > 0 && v.glyphId() < f.numGlyphs(), "variant glyph in range");
            assertTrue(v.advanceMeasurement() >= prev, "variant sizes are non-decreasing");
            prev = v.advanceMeasurement();
        }

        assertTrue(vc.hasAssembly(), "( has a part assembly for unbounded stretch");
        GlyphAssembly asm = vc.assembly();
        assertFalse(asm.parts().isEmpty(), "assembly has parts");
        assertTrue(asm.parts().stream().anyMatch(GlyphPart::isExtender),
            "assembly has at least one repeatable extender part");
        for (GlyphPart p : asm.parts()) {
            assertTrue(p.glyphId() > 0 && p.glyphId() < f.numGlyphs(), "part glyph in range");
            assertTrue(p.fullAdvance() > 0, "part has a positive extent");
        }

        // Connector overlap is a font-wide non-negative constant.
        assertTrue(f.minConnectorOverlap() >= 0, "minConnectorOverlap");
    }

    @Test
    void variantAtLeastPicksSmallestSufficientVariant() {
        SfntFont f = font();
        MathGlyphConstruction vc = f.verticalVariants(f.glyphId('('));
        assertNotNull(vc);
        List<MathGlyphVariant> vs = vc.variants();

        // A tiny target is satisfied by the first (smallest) variant.
        OptionalInt small = vc.variantAtLeast(1);
        assertTrue(small.isPresent(), "some variant reaches a 1-unit target");
        assertEquals(vs.get(0).glyphId(), small.getAsInt(), "smallest sufficient variant");

        // A target above the largest pre-drawn variant is not satisfiable -> caller
        // must fall back to the assembly.
        int biggest = vs.get(vs.size() - 1).advanceMeasurement();
        assertTrue(vc.variantAtLeast(biggest + 1).isEmpty(),
            "no pre-drawn variant exceeds the largest; assembly is the fallback");
    }

    @Test
    void horizontalConstructionsParseWhenPresent() {
        SfntFont f = font();
        // Not every font has horizontal stretch glyphs; assert the query is safe
        // for every glyph and that any construction found parses coherently.
        int found = 0;
        for (int g = 0; g < f.numGlyphs(); g++) {
            MathGlyphConstruction hc = f.horizontalVariants(g);
            if (hc != null) {
                found++;
                for (MathGlyphVariant v : hc.variants()) {
                    assertTrue(v.glyphId() > 0 && v.glyphId() < f.numGlyphs());
                }
                if (hc.assembly() != null) {
                    for (GlyphPart p : hc.assembly().parts()) {
                        assertTrue(p.glyphId() >= 0 && p.glyphId() < f.numGlyphs());
                    }
                }
            }
        }
        assertTrue(found >= 0, "horizontal-variant scan completed without error");
    }
}
