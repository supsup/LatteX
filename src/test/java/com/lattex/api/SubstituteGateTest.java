package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The DOUBLE GATE for the fx.substitute pass — the same opt-out-by-default contract
 * {@link UnfoldGateTest} pins for unfold, because the two effects share one host flag
 * ({@link RenderOptions#interactiveExpansion()}) and one pre-render seam. The pass runs
 * iff (host flag ON) AND (author opted in with an {@code fx.*=substitute} directive).
 */
class SubstituteGateTest {

    private static final String SUBST =
        "\\lx[fx.click=substitute, fx.substitute-to=3]{x^2 + 2x + 1}";
    private static final String PLAIN = "x^2 + 2x + 1";
    private static final RenderOptions FLAG_ON =
        RenderOptions.defaults().withInteractiveExpansion(true);

    @Test
    void gate1_hostFlagOffWithDirective_degradesInert() {
        String off = LatteX.renderStyledHtml(SUBST);   // default = flag OFF
        assertEquals(1, count(off, "<svg"), "flag-off must NOT pre-render a payload svg: " + off);
        assertFalse(off.contains("data-lx-fx-substitute"), "flag-off stamps no marker: " + off);
        assertFalse(off.contains("data-lx-var"), "flag-off stamps no varmap: " + off);
        assertFalse(off.contains("lx-fx-substituted"), "flag-off emits no payload wrapper: " + off);
        // The directive itself still rides the container inert, as any fx does.
        assertTrue(off.contains("data-lx-fx-click=\"substitute\""),
            "the directive still rides inert: " + off);
        assertEquals(off, LatteX.renderStyledHtml(SUBST, RenderOptions.defaults()));
    }

    @Test
    void gate1_flagOffInnerSvgIsByteIdenticalToPlainRender() {
        String off = LatteX.renderStyledHtml(SUBST);
        String innerSvg = off.substring(off.indexOf("<svg"), off.indexOf("</svg>") + 6);
        assertEquals(LatteX.render(PLAIN), innerSvg,
            "flag-off substitute svg must equal a plain render — the pass added zero surface");
    }

    @Test
    void gate2_flagOnWithoutTheDirective_isByteIdenticalToToday() {
        // The flag alone must touch NOTHING. This is the regression that would catch the
        // pass leaking onto the default render path.
        assertEquals(LatteX.renderStyledHtml(PLAIN), LatteX.renderStyledHtml(PLAIN, FLAG_ON));
        String glow = "\\lx[fx.enter=glow]{x^2 + 2x + 1}";
        assertEquals(LatteX.renderStyledHtml(glow), LatteX.renderStyledHtml(glow, FLAG_ON));
    }

    @Test
    void bothGatesOpen_theEffectFires() {
        String on = LatteX.renderStyledHtml(SUBST, FLAG_ON);
        assertEquals(2, count(on, "<svg"), "both gates open must pre-render the payload: " + on);
        assertTrue(on.contains("data-lx-fx-substitute=\"3\""), "expected the target marker: " + on);
        assertTrue(on.contains("lx-fx-substituted"), "expected the payload wrapper: " + on);
        // The varmap addresses the two x's by their <path> positions in the COLLAPSED svg.
        assertTrue(on.matches("(?s).*data-lx-var=\"78:[0-9]+,[0-9]+\".*"),
            "expected a two-index varmap for code point 78 (x): " + on);
    }

    @Test
    void markerAndVarmapAreStampedTogetherOrNotAtAll() {
        // A marker without a varmap names a flip the runtime cannot locate; a varmap
        // without a marker names addresses with no target. Neither half is ever alone.
        String on = LatteX.renderStyledHtml(SUBST, FLAG_ON);
        assertEquals(on.contains("data-lx-fx-substitute="), on.contains("data-lx-var="),
            "marker and varmap must be present together: " + on);
        String off = LatteX.renderStyledHtml(SUBST);
        assertEquals(off.contains("data-lx-fx-substitute="), off.contains("data-lx-var="),
            "…and absent together: " + off);
    }

    @Test
    void flagOnButNoTarget_degradesInert() {
        // fx.click=substitute with no fx.substitute-to: nothing to substitute TO.
        String on = LatteX.renderStyledHtml("\\lx[fx.click=substitute]{x + 1}", FLAG_ON);
        assertEquals(1, count(on, "<svg"), "a directive with no target must not pre-render: " + on);
        assertFalse(on.contains("data-lx-fx-substitute"), "…and stamps no marker: " + on);
    }

    @Test
    void flagOnButAmbiguousVariable_degradesInert() {
        // Two distinct letters and no fx.substitute-var: refuse rather than pick one.
        String on = LatteX.renderStyledHtml(
            "\\lx[fx.click=substitute, fx.substitute-to=3]{x + y}", FLAG_ON);
        assertEquals(1, count(on, "<svg"), "an ambiguous variable must not pre-render: " + on);
        assertFalse(on.contains("data-lx-fx-substitute"), "…and stamps no marker: " + on);
    }

    @Test
    void explicitVariableResolvesTheAmbiguity() {
        String on = LatteX.renderStyledHtml(
            "\\lx[fx.click=substitute, fx.substitute-to=3, fx.substitute-var=x]{x + y}", FLAG_ON);
        assertEquals(2, count(on, "<svg"), "an explicit variable arms the effect: " + on);
        assertTrue(on.contains("data-lx-var=\"78:"), "the varmap addresses x (78), not y: " + on);
    }

    @Test
    void overCapSubstitutedPayload_degradesInertNotThrows() {
        // The same fail-inert boundary unfold has: a formula whose COLLAPSED form renders
        // fine but whose substituted form would exceed SvgEmitter's cap must not break the
        // whole render. An optional payload never makes valid math throw.
        //
        // The two arms are sized from a measured boundary, not guessed, and they are
        // PROVABLY DISTINCT — which is what stops this test going vacuous. At 600 x's the
        // payload really is produced; at 900 the collapsed form is still comfortably valid
        // (~565k chars, well under the 2,000,000 cap) but the 6-digit target with its
        // inserted \cdot separators pushes the payload past it. So the single-svg result
        // below is caused BY THE CAP, not by an effect that never armed in the first place.
        String positiveControl = "\\lx[fx.click=substitute, fx.substitute-to=999999]{"
            + "x".repeat(600) + "}";
        assertEquals(2, count(LatteX.renderStyledHtml(positiveControl, FLAG_ON), "<svg"),
            "POSITIVE CONTROL: at this size the payload must actually render — otherwise the "
                + "over-cap arm below proves nothing");

        String overCap = "\\lx[fx.click=substitute, fx.substitute-to=999999]{"
            + "x".repeat(900) + "}";
        String off = LatteX.renderStyledHtml(overCap);
        assertEquals(1, count(off, "<svg"), "the collapsed form must itself be valid");
        String on = assertDoesNotThrow(() -> LatteX.renderStyledHtml(overCap, FLAG_ON),
            "arming substitute on an over-cap payload must degrade inert, never break the math");
        assertEquals(1, count(on, "<svg"), "over-cap payload must not be emitted: len=" + on.length());
        assertFalse(on.contains("data-lx-fx-substitute"), "…and stamps no marker");
        assertEquals(off, on, "an over-cap degrade must equal the flag-off collapsed render");
    }

    @Test
    void invalidTargetFailsLoudAtParseTime() {
        // The VALUE grammar is a parse-time contract, not a silent degrade: a malformed
        // fx.substitute-to is an author error and says so, the way fx.duration does.
        // (Contrast the INERT cases above, which are well-formed directives on bodies the
        // pass cannot serve — a different thing from a value that is not a value.)
        assertTrue(assertThrowsMessage("\\lx[fx.click=substitute, fx.substitute-to=abc]{x}")
            .contains("fx.substitute-to"), "the error must name the offending key");
        assertTrue(assertThrowsMessage("\\lx[fx.click=substitute, fx.substitute-to=12345678]{x}")
            .contains("fx.substitute-to"), "an over-long target is refused too");
        assertTrue(assertThrowsMessage(
            "\\lx[fx.click=substitute, fx.substitute-to=3, fx.substitute-var=xy]{x}")
            .contains("fx.substitute-var"), "a multi-letter variable is refused");
    }

    private static String assertThrowsMessage(String latex) {
        try {
            LatteX.renderStyledHtml(latex, FLAG_ON);
        } catch (RuntimeException expected) {
            return String.valueOf(expected.getMessage());
        }
        throw new AssertionError("expected a loud failure for: " + latex);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
