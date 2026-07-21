package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The DOUBLE GATE for the fx.unfold numeric-expansion pass (Charles ruling,
 * opt-out by default). The pass pre-renders iff (host flag ON) AND (author opted in
 * with an {@code fx.*=unfold} directive). This proves BOTH gates.
 */
class UnfoldGateTest {

    private static final String SUM_UNFOLD = "\\lx[fx.click=unfold]{\\sum_{i=1}^{4} f(i)}";
    private static final String PLAIN_SUM = "\\sum_{i=1}^{4} f(i)";
    private static final RenderOptions FLAG_ON = RenderOptions.defaults().withInteractiveExpansion(true);

    @Test
    void gate1_hostFlagOffWithDirective_degradesInert() {
        // Flag OFF (the default) + an fx.click=unfold directive: the sum typesets
        // normally, NO payload svg, NO marker — the effect just doesn't arm.
        String off = LatteX.renderStyledHtml(SUM_UNFOLD); // default = flag OFF
        assertEquals(1, count(off, "<svg"), "flag-off must NOT pre-render a payload svg: " + off);
        assertFalse(off.contains("data-lx-fx-expand"), "flag-off must stamp NO expand marker: " + off);
        assertFalse(off.contains("lx-fx-expanded"), "flag-off must emit NO payload wrapper: " + off);
        // The directive itself still rides the container (inert), as any fx does.
        assertTrue(off.contains("data-lx-fx-click=\"unfold\""), "the directive still rides inert: " + off);
        // Explicit flag-off via opts is identical to the default.
        assertEquals(off, LatteX.renderStyledHtml(SUM_UNFOLD, RenderOptions.defaults()));
    }

    @Test
    void gate1_flagOffInnerSvgIsByteIdenticalToPlainRender() {
        // The visible math with the flag off is byte-identical to rendering the sum
        // with no unfold at all — the pass added zero cost/surface to the SVG.
        String off = LatteX.renderStyledHtml(SUM_UNFOLD);
        String innerSvg = off.substring(off.indexOf("<svg"), off.indexOf("</svg>") + "</svg>".length());
        assertEquals(LatteX.render(PLAIN_SUM), innerSvg,
            "flag-off unfold svg must equal a plain sum render");
    }

    @Test
    void gate2_flagOnPlainSum_isByteIdenticalToNoFlag() {
        // Flag ON but the equation did NOT opt in (no fx.*=unfold): byte-identical to
        // today — the flag touches ONLY opted-in equations.
        assertEquals(LatteX.renderStyledHtml(PLAIN_SUM),
            LatteX.renderStyledHtml(PLAIN_SUM, FLAG_ON),
            "flag-on must not change a plain (non-opted-in) sum");
        // And a plain \lx sum with a DIFFERENT effect is likewise untouched.
        String glow = "\\lx[fx.enter=glow]{\\sum_{i=1}^{4} f(i)}";
        assertEquals(LatteX.renderStyledHtml(glow), LatteX.renderStyledHtml(glow, FLAG_ON));
    }

    @Test
    void bothGatesOpen_theEffectFires() {
        // Flag ON + opted in: the payload is pre-rendered and the marker stamped.
        String on = LatteX.renderStyledHtml(SUM_UNFOLD, FLAG_ON);
        assertEquals(2, count(on, "<svg"), "both gates open must pre-render the payload: " + on);
        assertTrue(on.contains("data-lx-fx-expand=\"4\""), "expected the expand marker: " + on);
    }

    @Test
    void flagOnButSumUnsupported_degradesInert() {
        // Flag ON + opted in, but the sum is out of scope (symbolic bound): inert —
        // no payload, no marker, even with both gates open.
        String on = LatteX.renderStyledHtml("\\lx[fx.click=unfold]{\\sum_{i=1}^{n} f(i)}", FLAG_ON);
        assertEquals(1, count(on, "<svg"), "unsupported sum must NOT pre-render: " + on);
        assertFalse(on.contains("data-lx-fx-expand"), "unsupported sum must stamp no marker: " + on);
    }

    @Test
    void overCapExpandedPayload_degradesInertNotThrows() {
        // review lattex-308 blocker 1: a formula whose COLLAPSED form renders fine but whose
        // EXPANDED form would exceed SvgEmitter's char cap must NOT break the whole render.
        // The optional payload degrades INERT (collapsed svg kept, no payload, no marker) —
        // arming an optional effect must never make a previously-valid formula throw.
        // 12 terms x a 300-glyph summand blows the expanded SVG past the 2,000,000-char cap
        // (Lattice's exact-tip repro), while the collapsed sum renders well under it.
        String src = "\\lx[fx.click=unfold]{\\sum_{i=1}^{12} " + "x".repeat(300) + "}";
        // Collapsed render (flag off / default) succeeds — establishes the formula is valid.
        String off = LatteX.renderStyledHtml(src);
        assertEquals(1, count(off, "<svg"), "collapsed render must succeed: len=" + off.length());
        // Flag ON: must STILL succeed (degrade inert), not throw the cap MathSyntaxException.
        String on = assertDoesNotThrow(() -> LatteX.renderStyledHtml(src, FLAG_ON),
            "arming unfold on an over-cap expansion must degrade inert, never break the collapsed math");
        assertEquals(1, count(on, "<svg"), "over-cap expansion must NOT emit a payload svg: len=" + on.length());
        assertFalse(on.contains("data-lx-fx-expand"), "over-cap expansion must stamp no marker");
        assertFalse(on.contains("lx-fx-expanded"), "over-cap expansion must emit no payload wrapper");
        // The inert degrade is byte-identical to the flag-off collapsed render.
        assertEquals(off, on, "an over-cap degrade must equal the flag-off collapsed render");
    }

    @Test
    void overload_honorsHostMacros() {
        // review lattex-308 blocker 2: the (String, RenderOptions) overload must expand preset
        // macros BEFORE parsing, exactly like render(String, opts). A host notation pack + unfold
        // must not fail with "Unknown command" before the effect can arm.
        RenderOptions opts = RenderOptions.defaults()
            .withMacros(java.util.Map.of("sq", "#1^2"))
            .withInteractiveExpansion(true);
        String src = "\\lx[fx.click=unfold]{\\sum_{i=1}^{4} \\sq{i}}";
        String html = assertDoesNotThrow(() -> LatteX.renderStyledHtml(src, opts),
            "the overload must expand host macros before parsing (\\sq), not throw Unknown command");
        // Both gates open + a supported sum -> the payload is pre-rendered.
        assertEquals(2, count(html, "<svg"), "macro-expanded unfold should pre-render the payload: " + html);
        assertTrue(html.contains("data-lx-fx-expand=\"4\""), "expected the expand marker: " + html);
    }

    @Test
    void overload_honorsHostStyle() {
        // The overload must honor the host's non-default scale/color/mathStyle when the source
        // has no \lx wrapper (a \lx wrapper still overrides, as documented). scale is the
        // observable pin — it, color, and mathStyle all flow through the same `style` variable
        // the fix now defaults to `opts` (was RenderOptions.defaults(), which ignored the host).
        String plain = "\\sum_{i=1}^{4} f(i)";  // no \lx wrapper -> host style applies
        RenderOptions styled = RenderOptions.defaults().withScale(2.0);
        String host = LatteX.renderStyledHtml(plain, styled);
        String innerSvg = host.substring(host.indexOf("<svg"), host.indexOf("</svg>") + "</svg>".length());
        // The inner svg must MATCH render(latex, opts) under the same host style...
        assertEquals(LatteX.render(plain, styled), innerSvg,
            "renderStyledHtml inner svg must honor the host style, matching render(latex, opts)");
        // ...and DIFFER from the default-style render (proving the option is actually applied).
        assertNotEquals(LatteX.render(plain, RenderOptions.defaults()), innerSvg,
            "a non-default host scale must actually change the svg (else the option is ignored)");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
