package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * L7 — inline baseline metrics (plan lattex-inline-baseline). The consumer contract
 * decided at lattex/126: depth as DATA, no style attribute baked into the SVG, and
 * the metrics SVG byte-identical to the plain inline render (one pipeline).
 */
class InlineSvgResultTest {

    @Test
    void metricsSvgIsByteIdenticalToPlainInlineRender() {
        // One pipeline, no drift: the compat pin against the ORIGINAL expression.
        String latex = "\\frac{a}{b} + y_i^2";
        assertEquals(LatteX.render(latex, RenderOptions.defaults().inline()),
            LatteX.renderInlineResult(latex).svg());
        assertEquals(LatteX.renderInline(latex), LatteX.renderInlineResult(latex).svg());
    }

    @Test
    void depthReflectsInkBelowTheBaseline() {
        // 'y' descends below the baseline; 'x' barely does. The depth is what the
        // embedder lowers the SVG by — it must be real, not zero.
        double yDepth = LatteX.renderInlineResult("y").depthEm();
        double xDepth = LatteX.renderInlineResult("x").depthEm();
        assertTrue(yDepth > 0.05, "descender ink below baseline (got " + yDepth + ")");
        assertTrue(yDepth > xDepth, "y descends deeper than x");
        // A subscript pushes ink further below than the bare letter.
        assertTrue(LatteX.renderInlineResult("x_2").depthEm() > xDepth,
            "subscript increases depth");
    }

    @Test
    void heightReflectsInkAboveTheBaseline() {
        assertTrue(LatteX.renderInlineResult("x^2").heightEm()
                > LatteX.renderInlineResult("x").heightEm(),
            "superscript increases height");
    }

    @Test
    void metricsAreNeverNegative() {
        InlineSvgResult r = LatteX.renderInlineResult("\\frac{x^3}{3}");
        assertTrue(r.depthEm() >= 0.0);
        assertTrue(r.heightEm() >= 0.0);
    }

    @Test
    void noStyleAttributeIsBakedIntoTheSvg() {
        // The consumer contract's load-bearing half: the embedding layer owns the
        // vertical-align; the served SVG stays style-attr-free (sanitizer-safe,
        // composes with fx enter-transforms on the wrapper).
        assertFalse(LatteX.renderInlineResult("y_i").svg().contains("style="),
            "no style attribute on inline SVG");
    }
}
