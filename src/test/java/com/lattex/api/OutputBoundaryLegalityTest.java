package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathSyntaxException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan cfd12523 — output-boundary legality + public-boundary validation
 * (Marlow audit LTX-06 + LTX-07 + LTX-15). ONE code-point legality policy at
 * every output boundary (SVG text, MathML, aria/a11y labels, data-* values):
 * C0 controls other than legal XML whitespace are STRIPPED (matching the SVG
 * path's pre-existing behavior); UNPAIRED SURROGATES fail LOUD; legal
 * whitespace is preserved. Plus size-entry validation and record invariants.
 *
 * <p>Mixed-fixture rule (Conf, lattex/126): every boundary matrix carries a
 * POSITIVE (clean) instance beside the hostile ones, so a containment assertion
 * over inputs that all fail proves nothing about the surface still working.
 */
class OutputBoundaryLegalityTest {

    private static void assertNoC0(String s, String where) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            assertTrue(c >= 0x20 || c == '\t' || c == '\n' || c == '\r',
                where + ": C0 control 0x" + Integer.toHexString(c) + " leaked");
        }
    }

    // ---- MathML boundary (LTX-06) ----

    @Test
    void mathmlStripsC0AndNul() {
        // POSITIVE: clean input still renders legal, well-formed MathML.
        String clean = LatteX.toMathML("x y");
        assertTrue(clean.contains("<mi>x</mi>"), "clean MathML still emitted");
        assertNoC0(clean, "mathml clean");
        // HOSTILE: a NUL between atoms must be stripped, not carried raw.
        String nul = LatteX.toMathML("x\u0000y");
        assertFalse(nul.contains("\u0000"), "NUL must not survive into MathML");
        assertNoC0(nul, "mathml nul");
        // Other C0 controls (BEL, unit separator) also stripped.
        assertNoC0(LatteX.toMathML("a\u0007b\u001f"), "mathml BEL/US");
    }

    @Test
    void mathmlPreservesLegalWhitespace() {
        // \t and \n are legal XML 1.0 characters and must be preserved.
        String mml = LatteX.toMathML("\\text{a\tb\nc}");
        assertTrue(mml.contains("\t"), "tab preserved in MathML text");
        assertTrue(mml.contains("\n"), "newline preserved in MathML text");
    }

    @Test
    void mathmlUnpairedSurrogateFailsLoud() {
        assertThrows(MathSyntaxException.class, () -> LatteX.toMathML("x\uD800y"),
            "lone high surrogate must be rejected loud");
        assertThrows(MathSyntaxException.class, () -> LatteX.toMathML("x\uDC00y"),
            "lone low surrogate must be rejected loud");
        // POSITIVE: a legal astral pair (𝔸, U+1D538) is preserved, not rejected.
        assertDoesNotThrow(() -> LatteX.toMathML("\\text{\uD835\uDD38}"),
            "a well-formed surrogate PAIR must pass");
    }

    // ---- SVG text boundary ----

    @Test
    void svgStripsC0() {
        // Pre-existing behavior, re-pinned through the shared policy.
        String svg = LatteX.render("x\u0000y");
        assertNoC0(svg, "svg nul");
        // POSITIVE beside it.
        assertNoC0(LatteX.render("x y"), "svg clean");
    }

    @Test
    void svgUnpairedSurrogateFailsLoud() {
        assertThrows(MathSyntaxException.class, () -> LatteX.render("x\uD800y"),
            "SVG aria path must reject a lone surrogate loud");
    }

    // ---- a11y label boundary (LTX-15 / stored-raw normalization) ----

    @Test
    void a11yLabelStripsC0() {
        String html = LatteX.renderStyledHtml("\\lx[a11y.label=\"a\u0000b\"]{ x }");
        assertTrue(html.contains("aria-label=\""), "aria-label emitted");
        assertNoC0(html, "styled html a11y nul");
        // POSITIVE: a clean label survives unchanged.
        assertTrue(LatteX.renderStyledHtml("\\lx[a11y.label=\"a b\"]{ x }")
            .contains("aria-label=\"a b\""), "clean a11y label preserved");
    }

    @Test
    void a11yLabelStillHtmlEscaped() {
        // Storing raw + escaping at the boundary must keep the metachar escaping.
        String html = LatteX.renderStyledHtml("\\lx[a11y.label=\"a<b>&c\"]{ x }");
        assertTrue(html.contains("aria-label=\"a&lt;b&gt;&amp;c\""),
            "a11y label HTML-escaped at the boundary: " + html);
    }

    @Test
    void a11yLabelUnpairedSurrogateFailsLoud() {
        assertThrows(MathSyntaxException.class,
            () -> LatteX.renderStyledHtml("\\lx[a11y.label=\"a\uD800b\"]{ x }"),
            "a lone surrogate in an a11y label must be rejected loud");
    }

    // ---- data-* attribute boundary ----

    @Test
    void dataGraphExprStripsC0() {
        String html = LatteX.renderStyledHtml("\\lx[graph.domain=-3..3]{ x\u0000y }");
        assertNoC0(html, "styled html graph-expr");
        // POSITIVE: a normal plottable body carries through.
        assertTrue(LatteX.renderStyledHtml("\\lx[graph.domain=-3..3]{ x^2 }")
            .contains("data-lx-graph-expr="), "graph-expr still stamped");
    }

    // ---- renderFragment size validation (LTX-07) ----

    @Test
    void renderFragmentRejectsNonFiniteAndNonPositiveSize() {
        // POSITIVE beside the rejects (mixed fixture): a real size renders.
        MathFragment ok = LatteX.renderFragment("x", 40.0);
        assertTrue(ok.widthPx() > 0 && Double.isFinite(ok.widthPx()), "valid size renders finite");
        for (double bad : new double[] {Double.NaN, 0.0, -1.0,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                () -> LatteX.renderFragment("x", bad),
                "renderFragment must reject size " + bad);
        }
    }

    @Test
    void renderFragmentRejectsSizeAboveCeiling() {
        // Consistent with RenderOptions' scale ceiling: MAX_SCALE * base display size.
        double ceiling = RenderOptions.MAX_SCALE * LatteX.DISPLAY_FONT_SIZE;
        assertDoesNotThrow(() -> LatteX.renderFragment("x", ceiling), "ceiling accepted");
        assertThrows(IllegalArgumentException.class,
            () -> LatteX.renderFragment("x", ceiling + 1.0),
            "a size above the ceiling must be rejected");
    }

    // ---- record invariants (LTX-15) ----

    @Test
    void renderedMathDefensivelyCopiesAndRejectsNull() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("data-lx-fx-hover", "glow");
        LatteX.RenderedMath rm = new LatteX.RenderedMath("<svg/>", mutable);
        mutable.put("data-lx-fx-click", "sprout"); // must NOT leak in
        assertEquals(1, rm.containerAttrs().size(), "attrs defensively copied");
        assertThrows(UnsupportedOperationException.class,
            () -> rm.containerAttrs().put("k", "v"), "attrs immutable");
        assertThrows(NullPointerException.class,
            () -> new LatteX.RenderedMath(null, Map.of()), "null svg rejected");
        assertThrows(NullPointerException.class,
            () -> new LatteX.RenderedMath("<svg/>", null), "null map rejected");
    }

    @Test
    void mathFragmentEnforcesInvariants() {
        assertDoesNotThrow(() -> new MathFragment("<g/>", 1.0, 2.0, 0.0, "", "<math/>"),
            "valid fragment constructs");
        assertThrows(NullPointerException.class,
            () -> new MathFragment(null, 1, 1, 1, "", ""), "null innerSvg rejected");
        assertThrows(NullPointerException.class,
            () -> new MathFragment("<g/>", 1, 1, 1, "", null), "null mathml rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new MathFragment("<g/>", -1, 1, 1, "", ""), "negative width rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new MathFragment("<g/>", 1, Double.NaN, 1, "", ""), "NaN height rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new MathFragment("<g/>", 1, 1, Double.POSITIVE_INFINITY, "", ""),
            "infinite depth rejected");
    }

    @Test
    void inlineSvgResultEnforcesFiniteNonnegativeMetrics() {
        assertDoesNotThrow(() -> new InlineSvgResult("<svg/>", 0.0, 1.0), "valid constructs");
        assertThrows(NullPointerException.class,
            () -> new InlineSvgResult(null, 0, 0), "null svg rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new InlineSvgResult("<svg/>", -0.1, 1), "negative depth rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new InlineSvgResult("<svg/>", 0, Double.NaN), "NaN height rejected");
    }

    @Test
    void renderResultRejectsNullDiagnostics() {
        Diagnostics d = new Diagnostics(Outcome.OK, "emit", "ok", -1, "", -1, "");
        assertDoesNotThrow(() -> new RenderResult("<svg/>", d), "valid constructs");
        assertThrows(NullPointerException.class,
            () -> new RenderResult("<svg/>", null), "null diagnostics rejected");
    }
}
