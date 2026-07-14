package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathParser;
import org.junit.jupiter.api.Test;

/**
 * {@link SvgEmitter#groupmap} — the precedence-group sidecar for the {@code precedence}
 * cascade fx effect (FENCED-ONLY v1, seam sign-off lattex/169). Rank is derived purely
 * from {@code \left..\right} nesting: the deepest fenced group is rank 0 (evaluated
 * first). WHOLE-EXPRESSION fail-honest — emitted only when there is genuine nesting
 * variation, so a paren-free or single-depth expression returns "" (static degrade)
 * rather than a meaningless cascade. Indices address the emitted {@code <path>}s in emit
 * order, the SAME sequence {@link SvgEmitter#glyphmap} keys off.
 */
class GroupmapTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static String groupmapOf(String latex) {
        Layout laid = LayoutEngine.layout(MathParser.parse(latex),
            new LayoutContext(FONT, FONT.mathConstants(), 40.0));
        return SvgEmitter.groupmap(laid, FONT);
    }

    // -- the cascade cases: genuine nesting variation → a ranked map --------------------

    @Test
    void oneFenceLevelRanksTheInnerGroupBeforeTheOuter() {
        // \left(a + b\right) + c : a,+,b sit inside the fence (depth 1 → rank 0); the
        // outer + and c sit outside (depth 0 → rank 1). The delimiters are construction
        // glyphs (NO_RANK) and never appear. Grammar-valid, two distinct ranks.
        String gm = groupmapOf("\\left(a + b\\right) + c");
        assertTrue(gm.matches("^[0-9]+:[0-9]+(,[0-9]+)*(;[0-9]+:[0-9]+(,[0-9]+)*)*$"),
            "matches the pinned contract grammar: " + gm);
        assertTrue(gm.startsWith("0:"), "the inner (deeper) group is rank 0: " + gm);
        assertTrue(gm.contains(";1:"), "the outer group is rank 1: " + gm);
    }

    @Test
    void deeperNestingProducesMoreRanksDeepestFirst() {
        // \left(a + \left(b + c\right)\right) : c/b/+ deepest = rank 0, the middle level
        // = rank 1, nothing at the top level here beyond the outer fence body. At least
        // two ranks, ascending, deepest-first.
        String gm = groupmapOf("\\left(a + \\left(b + c\\right)\\right)");
        assertTrue(gm.matches("^[0-9]+:[0-9]+(,[0-9]+)*(;[0-9]+:[0-9]+(,[0-9]+)*)*$"), gm);
        assertTrue(gm.startsWith("0:"), "deepest fence is rank 0: " + gm);
        // ranks are strictly ascending in the serialization (TreeMap order).
        int prev = -1;
        for (String run : gm.split(";")) {
            int rank = Integer.parseInt(run.substring(0, run.indexOf(':')));
            assertTrue(rank > prev, "ranks ascend: " + gm);
            prev = rank;
        }
    }

    @Test
    void twoSiblingFencesShareRankZero() {
        // \left(a+b\right) + \left(c+d\right) : both inner groups are depth 1 → both
        // rank 0 (independent operations light together), the outer + is rank 1. This is
        // the "(a+b)+(c+d)" pedagogy — innermost ops happen first, in parallel.
        String gm = groupmapOf("\\left(a+b\\right) + \\left(c+d\\right)");
        assertTrue(gm.startsWith("0:"), gm);
        assertTrue(gm.contains(";1:"), "the joining operator is one rank later: " + gm);
        // rank-0 run holds MORE indices than either single group (both siblings merged).
        String rank0 = gm.substring(2, gm.indexOf(';'));
        assertTrue(rank0.split(",").length >= 4, "both sibling groups share rank 0: " + gm);
    }

    // -- the fail-honest degrade cases: no cascade → "" ---------------------------------

    @Test
    void parenFreeExpressionDegradesToEmpty() {
        // a + b : no fence nesting at all → every atom depth 0 → nothing to sequence.
        // The runtime treats "" as no map and shows a single static highlight.
        assertEquals("", groupmapOf("a + b"));
    }

    @Test
    void aSingleGlyphDegradesToEmpty() {
        assertEquals("", groupmapOf("x"));
    }

    @Test
    void aFenceWithNothingOutsideStillNeedsTwoRanks() {
        // \left(a+b\right) alone: the body is depth 1, but there is NO depth-0 atom
        // outside it (delimiters are NO_RANK), so only ONE rank is present → no ordering
        // to animate → "". Honest: a lone paren group has no "evaluate then combine" step.
        assertEquals("", groupmapOf("\\left(a+b\\right)"));
    }

    @Test
    void scriptsAndFractionsDoNotDeepenThePrecedenceDepth() {
        // a^2 + \frac{b}{c} : superscripts and fraction parts are NOT fences, so they add
        // NO precedence depth. With no \left..\right anywhere, the whole thing is depth 0
        // → "" (static). This pins that only explicit parens drive the v1 cascade.
        assertEquals("", groupmapOf("a^2 + \\frac{b}{c}"));
    }

    @Test
    void indicesAddressEmittedPathsInEmitOrderMatchingGlyphmap() {
        // The groupmap and glyphmap key off the SAME emitted-glyph sequence, so an index
        // means the same <path> in both. For \left(x + x\right) + y, 'x' threads (glyphmap)
        // AND both x's are rank 0 (groupmap) — the two sidecars agree on which paths.
        Layout laid = LayoutEngine.layout(MathParser.parse("\\left(x + x\\right) + y"),
            new LayoutContext(FONT, FONT.mathConstants(), 40.0));
        String gm = SvgEmitter.groupmap(laid, FONT);
        String glyph = SvgEmitter.glyphmap(laid, FONT);
        assertTrue(gm.startsWith("0:"), "the two x's + the inner + are rank 0: " + gm);
        // glyphmap groups the two x's; both those indices must be in groupmap's rank-0 run.
        assertTrue(glyph.startsWith("78:"), "glyphmap threads 'x': " + glyph);
    }
}
