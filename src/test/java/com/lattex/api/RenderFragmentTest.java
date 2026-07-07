package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link LatteX#renderFragment} — the embeddable inner-markup + box-metrics
 * backend for a consumer that composes math inline on a shared baseline.
 */
class RenderFragmentTest {

    private static final double SIZE = 40.0;

    // The fragment stays inside the g/path/rect alphabet — NO <svg> wrapper/aria.
    private static final Set<String> FRAGMENT_ELEMENTS = Set.of("g", "path", "rect");
    private static final Set<String> FRAGMENT_ATTRS = Set.of(
        "transform", "fill",        // g / path
        "d",                        // path
        "x", "y", "width", "height"); // rect

    @Test
    void singleGlyphFragmentHasPathAndNoSvgWrapper() {
        MathFragment f = LatteX.renderFragment("x", SIZE);
        assertTrue(f.innerSvg().contains("<path"), "fragment carries a glyph <path>");
        assertFalse(f.innerSvg().contains("<svg"), "fragment must NOT wrap in <svg>");
        assertFalse(f.innerSvg().contains("viewBox"), "fragment must NOT carry a viewBox");
        assertFalse(f.innerSvg().contains("aria-label"), "fragment must NOT carry aria");
        assertFalse(f.innerSvg().contains("role="), "fragment must NOT carry role");
    }

    @Test
    void fractionFragmentContainsTheBarRect() {
        // The load-bearing case: fractions (and roots) emit their bar as a <rect>.
        MathFragment f = LatteX.renderFragment("\\frac{a}{b}", SIZE);
        assertTrue(f.innerSvg().contains("<rect"),
            "the fraction bar must survive into the fragment as a <rect>");
    }

    @Test
    void metricsAreSaneForAscendingGlyph() {
        MathFragment f = LatteX.renderFragment("x", SIZE);
        assertTrue(f.widthPx() > 0, "an inked glyph has positive advance width: " + f.widthPx());
        assertTrue(f.heightPx() > 0,
            "x has ink above the baseline, so heightPx > 0: " + f.heightPx());
        assertTrue(f.depthPx() >= 0, "depth is a non-negative magnitude: " + f.depthPx());
    }

    @Test
    void fractionHasBothHeightAndDepth() {
        MathFragment f = LatteX.renderFragment("\\frac{a}{b}", SIZE);
        assertTrue(f.widthPx() > 0, "width > 0: " + f.widthPx());
        assertTrue(f.heightPx() > 0,
            "the numerator rises above the baseline: " + f.heightPx());
        assertTrue(f.depthPx() > 0,
            "the denominator drops below the baseline: " + f.depthPx());
    }

    @Test
    void textcolorFillIsPreservedOnThePath() {
        MathFragment f = LatteX.renderFragment("\\textcolor{red}{x}", SIZE);
        assertTrue(f.innerSvg().contains("fill=\"#ff0000\""),
            "a \\textcolor{red} glyph keeps its per-path fill in the fragment: " + f.innerSvg());
    }

    @Test
    void fragmentStaysInsideTheMinimalAlphabet() {
        String inner = LatteX.renderFragment("\\frac{a}{b}", SIZE).innerSvg();
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(inner);
        int tags = 0;
        while (tag.find()) {
            tags++;
            String element = tag.group(1);
            assertTrue(FRAGMENT_ELEMENTS.contains(element),
                "fragment element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=").matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(FRAGMENT_ATTRS.contains(attr.group(1)),
                    "fragment attribute out of alphabet on <" + element + ">: " + attr.group(1));
            }
        }
        assertTrue(tags >= 3, "scanned the g + glyph paths + bar rect: " + tags);
    }

    /**
     * Byte-consistency pin: the fragment's inner glyphs/rules are IDENTICAL to what
     * {@link LatteX#render} emits for the same input (modulo the {@code <svg>}
     * wrapper + the viewBox re-base). The glyph {@code <path d>} outline data is
     * position-independent, so the d-set must match exactly — proving there is no
     * forked emit path.
     */
    @Test
    void innerPathDataMatchesFullRender() {
        List<String> fromRender = pathData(LatteX.render("\\frac{a}{b}"));
        List<String> fromFragment = pathData(LatteX.renderFragment("\\frac{a}{b}", SIZE).innerSvg());
        assertEquals(fromRender, fromFragment,
            "fragment paints the same glyph outlines, in the same order, as render()");
        assertFalse(fromFragment.isEmpty(), "sanity: there ARE glyph paths to compare");
    }

    private static List<String> pathData(String svg) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<path d=\"([^\"]*)\"").matcher(svg);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
