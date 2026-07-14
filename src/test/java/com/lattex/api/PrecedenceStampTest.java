package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The {@code precedence} effect's producer stamp: {@link LatteX#tryRenderMath} /
 * {@link LatteX#renderStyledHtml} attach the {@code data-lx-groupmap} sidecar ONLY when a
 * {@code precedence} effect is authored AND the expression has genuine fence nesting.
 *
 * <p>The MUST-DEGRADE battery (seam sign-off lattex/169, Fixpoint's fixture ask) is the
 * load-bearing half: ABSENCE assertions carry the pedagogy-safety weight — a partial or
 * wrong-precedence cascade is worse than none, so an ambiguous/flat expression must emit
 * NO groupmap at all. Presence tests only prove the happy path.
 */
class PrecedenceStampTest {

    private static final Pattern GROUPMAP =
        Pattern.compile("data-lx-groupmap=\"([^\"]*)\"");

    private static String groupmapAttr(String latex) {
        Matcher m = GROUPMAP.matcher(LatteX.renderStyledHtml(latex));
        return m.find() ? m.group(1) : null;
    }

    // -- happy path: precedence effect + real nesting → a grammar-valid sidecar ---------

    @Test
    void precedenceEffectWithFenceNestingStampsAGrammarValidGroupmap() {
        String gm = groupmapAttr("\\lx[fx.hover=precedence]{\\left(a + b\\right) + c}");
        assertTrue(gm != null && gm.matches("^[0-9]+:[0-9]+(,[0-9]+)*(;[0-9]+:[0-9]+(,[0-9]+)*)*$"),
            "grammar-valid groupmap on a nested precedence expression: " + gm);
        // The record seam agrees with the string seam (one producer).
        Optional<LatteX.RenderedMath> rm =
            LatteX.tryRenderMath("\\lx[fx.hover=precedence]{\\left(a + b\\right) + c}");
        assertTrue(rm.isPresent() && gm.equals(rm.get().containerAttrs().get("data-lx-groupmap")),
            "record seam stamps the same groupmap as the string seam");
    }

    // -- MUST-DEGRADE: absence assertions (the safety load) -----------------------------

    @Test
    void precedenceOnAParenFreeExpressionEmitsNoGroupmap() {
        // No fence nesting → no order-of-operations structure to cascade → NO attribute
        // (the effect degrades to a single static highlight, never a fake cascade).
        assertFalse(LatteX.renderStyledHtml("\\lx[fx.hover=precedence]{a + b \\times c}")
            .contains("data-lx-groupmap"), "flat expression must not carry a groupmap");
    }

    @Test
    void precedenceOnImplicitMultiplicationEmitsNoGroupmap() {
        // `ab` (implicit multiplication) has no fence nesting and — in the eventual
        // operator-ranking cut — is a genuine ambiguity. Either way v1 emits nothing.
        assertFalse(LatteX.renderStyledHtml("\\lx[fx.hover=precedence]{ab + c}")
            .contains("data-lx-groupmap"), "implicit multiplication must degrade to no groupmap");
    }

    @Test
    void precedenceOnAFunctionApplicationEmitsNoGroupmap() {
        // \sin x — function application, a v2 ambiguity; no fences here so v1 emits nothing.
        assertFalse(LatteX.renderStyledHtml("\\lx[fx.hover=precedence]{\\sin x + y}")
            .contains("data-lx-groupmap"), "function application must degrade to no groupmap");
    }

    @Test
    void precedenceOnALoneFenceGroupEmitsNoGroupmap() {
        // A single paren group with nothing outside it has only one rank → nothing to
        // sequence → no groupmap (no "evaluate then combine" step to show).
        assertFalse(LatteX.renderStyledHtml("\\lx[fx.hover=precedence]{\\left(a + b\\right)}")
            .contains("data-lx-groupmap"), "a lone fenced group must not carry a groupmap");
    }

    @Test
    void scriptsAndFractionsDoNotByThemselvesTriggerAGroupmap() {
        // a^2 + \frac{b}{c} : scripts and fractions are NOT precedence fences (v1), so with
        // no \left..\right anywhere the expression stays flat → no groupmap.
        assertFalse(LatteX.renderStyledHtml("\\lx[fx.hover=precedence]{a^2 + \\frac{b}{c}}")
            .contains("data-lx-groupmap"), "scripts/fractions alone do not drive the v1 cascade");
    }

    @Test
    void aNonPrecedenceEffectNeverStampsAGroupmapEvenWithNesting() {
        // The sidecar is gated on the effect being present, not merely on nesting existing —
        // a glow/thread expression with parens carries no groupmap (wasted bytes otherwise).
        assertFalse(LatteX.renderStyledHtml("\\lx[fx.hover=glow]{\\left(a + b\\right) + c}")
            .contains("data-lx-groupmap"), "groupmap only rides a precedence effect");
        assertFalse(LatteX.renderStyledHtml("\\left(a + b\\right) + c")
            .contains("data-lx-groupmap"), "a plain (no-fx) render carries no groupmap");
    }
}
