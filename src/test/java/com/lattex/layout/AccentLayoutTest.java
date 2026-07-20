package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T1b accent-layout geometry tests — asserts that every accent command renders
 * (no exception), places the accent above the base (or the underline below it),
 * centres it over the base, stretches wide accents to span the base, and keeps
 * the output inside the sanitizer-safe SVG alphabet.
 */
class AccentLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;
    private static final double SCALE = SIZE / FONT.unitsPerEm();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), SIZE);
        MathNode node = MathParser.parse(latex);
        return LayoutEngine.layout(node, ctx);
    }

    /** Every accent command the parser accepts renders + stays in-alphabet. */
    private static final List<String> NARROW = List.of(
        "hat", "bar", "vec", "dot", "ddot", "tilde", "check",
        "breve", "acute", "grave", "mathring");

    @Test
    void everyNarrowAccentRendersInAlphabet() {
        for (String cmd : NARROW) {
            String latex = "\\" + cmd + "{a}";
            Layout l = layout(latex);
            // Base 'a' + at least one accent glyph.
            assertTrue(l.glyphs().size() >= 2, cmd + ": base + accent glyph present");
            // The accent ink rises above the base ink top (more-negative y).
            assertTrue(l.minY() < -1.0, cmd + ": accent sits above the baseline");
            assertAlphabet(LatteX.render(latex));
        }
    }

    @Test
    void accentSitsAboveBaseAndIsCentred() {
        Layout l = layout("\\hat{a}");
        double baseTop = -baseHeightOf('a');
        // The accent glyph is the one whose ink is highest (most negative y).
        PositionedGlyph accent = l.glyphs().stream()
            .min((p, q) -> Double.compare(p.baselineY(), q.baselineY())).orElseThrow();
        PositionedGlyph base = l.glyphs().stream()
            .filter(g -> g.glyphId() == FONT.glyphId('a')).findFirst().orElseThrow();
        assertTrue(accent.baselineY() <= 0.0, "accent raised at or above the baseline");
        // Accent ink centre lands near the base's top-accent attachment point.
        double baseAccentX = FONT.topAccentAttachment(FONT.glyphId('a')) * SCALE;
        var ao = FONT.outline(accent.glyphId());
        double accentCentre = accent.originX() + SCALE * (ao.xMin() + ao.xMax()) / 2.0;
        assertEquals(baseAccentX, accentCentre, 1.5, "accent centred over the base attachment");
        // Sanity: the accent's ink top clears the base ink top.
        double accentInkTop = accent.baselineY() - SCALE * ao.yMax();
        assertTrue(accentInkTop < baseTop, "accent ink is above the base ink top");
    }

    @Test
    void wideAccentStretchesToSpanTheBase() {
        // A wide base selects a wider MATH horizontal variant than the narrow hat.
        Layout narrow = layout("\\widehat{i}");
        Layout wide = layout("\\widehat{AAA}");
        int narrowAccentGid = topGlyph(narrow).glyphId();
        int wideAccentGid = topGlyph(wide).glyphId();
        assertTrue(wideAccentGid != narrowAccentGid,
            "a wider base selects a wider hat variant");
        // The wide hat's ink spans most of the base width.
        double baseWidth = baseWidthOf(wide);
        var o = FONT.outline(wideAccentGid);
        double accentInkWidth = SCALE * (o.xMax() - o.xMin());
        assertTrue(accentInkWidth > 0.5 * baseWidth,
            "wide accent ink covers most of the base width");
        assertAlphabet(LatteX.render("\\widehat{AAA}"));
    }

    @Test
    void widetildeAndArrowsRender() {
        for (String latex : List.of(
                "\\widetilde{AAA}", "\\overrightarrow{AB}",
                "\\overleftarrow{AB}", "\\overleftrightarrow{AB}")) {
            Layout l = layout(latex);
            assertTrue(l.glyphs().size() >= 3, latex + ": base glyphs + accent present");
            assertTrue(l.minY() < -1.0, latex + ": accent above the baseline");
            assertAlphabet(LatteX.render(latex));
        }
    }

    @Test
    void overlineIsARuleAboveTheBase() {
        Layout l = layout("\\overline{a}");
        assertEquals(1, l.rules().size(), "overline is a single rule");
        Rule bar = l.rules().get(0);
        assertTrue(bar.y() < -baseHeightOf('a'), "overline rule sits above the base ink top");
        assertTrue(bar.width() > 0 && bar.height() > 0, "rule has extent");
        assertAlphabet(LatteX.render("\\overline{a}"));
    }

    @Test
    void underlineIsARuleBelowTheBase() {
        Layout l = layout("\\underline{a}");
        assertEquals(1, l.rules().size(), "underline is a single rule");
        Rule bar = l.rules().get(0);
        assertTrue(bar.y() > 0, "underline rule sits below the baseline");
        assertAlphabet(LatteX.render("\\underline{a}"));
    }

    @Test
    void accentComposesWithOtherConstructs() {
        // Accents nest with scripts, fractions, and each other without throwing.
        for (String latex : List.of(
                "\\hat{a}^2", "\\frac{\\vec{v}}{\\bar{x}}",
                "\\hat{\\hat{a}}", "\\overline{a+b}", "\\sqrt{\\hat{x}}",
                "\\underparen{a+b}")) {
            Layout l = layout(latex);
            assertTrue(l.width() > 0 && l.height() > 0, latex + ": non-degenerate box");
            assertAlphabet(LatteX.render(latex));
        }
    }

    @Test
    void underparenSitsBelowTheBaseAndIsCentred() {
        // The mirror of accentSitsAboveBaseAndIsCentred/overlineIsARuleAboveTheBase:
        // \\underparen is a GLYPH (not a rule, unlike \\underline), so this asserts the
        // ink-below-the-base relationship the way wideAccentStretchesToSpanTheBase
        // asserts width — on the actual positioned glyph, not just a bounding box.
        Layout l = layout("\\underparen{a}");
        double baseBottom = baseDepthOf('a');
        // Unlike the over case, an under-accent's own baseline commonly ties the
        // base's (both 0.0: 'a' has near-zero depth, well under accentBaseHeight),
        // so picking "highest/lowest y" can't distinguish accent from base — use
        // glyphAccentBox's draw order instead (base first, then the accent piece).
        PositionedGlyph accent = bottomGlyph(l);
        PositionedGlyph base = l.glyphs().stream()
            .filter(g -> g.glyphId() == FONT.glyphId('a')).findFirst().orElseThrow();
        assertTrue(accent.baselineY() >= 0.0, "accent lowered at or below the baseline");
        // Accent ink centre lands near the base's horizontal centre (no per-glyph
        // "bottom attachment" table exists in OpenType MATH, unlike the over case).
        double baseWidth = FONT.advanceWidth(base.glyphId()) * SCALE;
        double baseCentreX = base.originX() + baseWidth / 2.0;
        var ao = FONT.outline(accent.glyphId());
        double accentCentre = accent.originX() + SCALE * (ao.xMin() + ao.xMax()) / 2.0;
        assertEquals(baseCentreX, accentCentre, 2.0, "accent centred under the base");
        // Sanity: the accent's ink bottom clears the base ink bottom (its depth).
        double accentInkBottom = accent.baselineY() - SCALE * ao.yMin();
        assertTrue(accentInkBottom > baseBottom, "accent ink is below the base ink bottom");
        assertAlphabet(LatteX.render("\\underparen{a}"));
    }

    @Test
    void underparenStretchesToSpanTheBase() {
        // The mirror of wideAccentStretchesToSpanTheBase: a wider base selects a
        // wider MATH horizontal variant of U+23DD than the narrow one.
        Layout narrow = layout("\\underparen{i}");
        Layout wide = layout("\\underparen{AAA}");
        int narrowAccentGid = bottomGlyph(narrow).glyphId();
        int wideAccentGid = bottomGlyph(wide).glyphId();
        assertTrue(wideAccentGid != narrowAccentGid,
            "a wider base selects a wider under-parenthesis variant");
        double baseWidth = baseWidthOf(wide);
        var o = FONT.outline(wideAccentGid);
        double accentInkWidth = SCALE * (o.xMax() - o.xMin());
        assertTrue(accentInkWidth > 0.5 * baseWidth,
            "wide accent ink covers most of the base width");
        assertAlphabet(LatteX.render("\\underparen{AAA}"));
    }

    // ---- helpers ---------------------------------------------------------

    private static double baseHeightOf(int cp) {
        var o = FONT.outline(FONT.glyphId(cp));
        return o.yMax() * SCALE;
    }

    /** The mirror of {@link #baseHeightOf}: how far a glyph's ink reaches below the baseline. */
    private static double baseDepthOf(int cp) {
        var o = FONT.outline(FONT.glyphId(cp));
        return -o.yMin() * SCALE;
    }

    private static double baseWidthOf(Layout l) {
        double minX = l.glyphs().stream().mapToDouble(PositionedGlyph::originX).min().orElse(0);
        double maxX = l.glyphs().stream().mapToDouble(PositionedGlyph::originX).max().orElse(0);
        return maxX - minX;
    }

    private static PositionedGlyph topGlyph(Layout l) {
        return l.glyphs().stream()
            .min((p, q) -> Double.compare(p.baselineY(), q.baselineY())).orElseThrow();
    }

    /**
     * The mirror of {@link #topGlyph}: an under-accent's glyph. Unlike the over
     * case, a "most positive baselineY" search can tie with the base (an
     * under-accent's own baseline is 0.0 whenever the base's depth doesn't exceed
     * {@code accentBaseHeight}, true for most bases), so this instead relies on
     * draw order: {@code glyphAccentBox} always draws the base first, then appends
     * the accent piece(s) — the last glyph in the list is always part of the accent.
     */
    private static PositionedGlyph bottomGlyph(Layout l) {
        List<PositionedGlyph> glyphs = l.glyphs();
        return glyphs.get(glyphs.size() - 1);
    }

    // ---- alphabet guard (mirrors LayoutS4Test) ---------------------------

    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final Set<String> ALLOWED_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label",
        "transform", "fill", "d", "stroke", "stroke-width", "x", "y");
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "use", "defs", "symbol", "text", "foreignObject", "script", "style",
        "animate", "image", "a");

    private static void assertAlphabet(String svg) {
        for (String forbidden : FORBIDDEN_ELEMENTS) {
            assertFalse(svg.contains("<" + forbidden), "must not emit <" + forbidden + ">");
        }
        assertFalse(svg.contains("href"), "no href");
        assertFalse(svg.contains("data:"), "no data: URI");
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(svg);
        while (tag.find()) {
            String element = tag.group(1);
            assertTrue(ALLOWED_ELEMENTS.contains(element), "element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=").matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(ALLOWED_ATTRS.contains(attr.group(1)),
                    "attribute out of alphabet on <" + element + ">: " + attr.group(1));
            }
        }
    }
}
