package com.lattex.api;

import com.lattex.parse.Effect;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Drift-guard for the pinned Producer API (container-output-contract.md → "Producer
/// API — fxContainerAttrs"): the output must use ONLY the contract's five
/// `data-lx-fx-*` attributes with contract-shaped values, never the semantic/a11y
/// attributes that ride renderStyledHtml's own container. Failing here means the seam
/// drifted from the contract the Stafficy sanitizer allow-list is synced to — the
/// stamped attrs would be silently stripped in /docs.
class FxContainerAttrsTest {

    private static final Pattern ATTR = Pattern.compile(" ([a-z-]+)=\"([^\"]*)\"");
    private static final Pattern DURATION = Pattern.compile("^[0-9]{1,5}ms$");
    private static final Pattern GLOW = Pattern.compile("^(currentColor|#[0-9a-fA-F]{3,8})$");

    @Test
    void plainMathYieldsEmpty() {
        assertEquals("", LatteX.fxContainerAttrs("x^2 + 1"));
    }

    @Test
    void lxWithoutFxYieldsEmpty() {
        assertEquals("", LatteX.fxContainerAttrs("\\lx[style.scale=1.2]{ x^2 }"));
        assertEquals("", LatteX.fxContainerAttrs("\\lx[intent=function, a11y.label=\"q\"]{ x^2 }"));
    }

    @Test
    void fxAttrsAreLeadingSpacePrefixed_contractOrder_andFxOnly() {
        String attrs = LatteX.fxContainerAttrs(
            "\\lx[fx.enter=fade, fx.hover=glow, fx.click=boom, fx.duration=250ms,"
                + " fx.glow-color=#e0a13a, intent=function, a11y.label=\"quad\"]{ x^2 }");
        assertTrue(attrs.startsWith(" "), "leading-space-prefixed run");
        assertEquals(" data-lx-fx-enter=\"fade\" data-lx-fx-hover=\"glow\""
            + " data-lx-fx-click=\"boom\" data-lx-fx-duration=\"250ms\""
            + " data-lx-fx-glow-color=\"#e0a13a\"", attrs);
    }

    @Test
    void everyEmittedAttrIsContractShaped_acrossTheEffectVocabulary() {
        for (Effect e : Effect.values()) {
            if (e == Effect.NONE) {
                continue;
            }
            String attrs = LatteX.fxContainerAttrs(
                "\\lx[fx.hover=" + e.token() + ", fx.duration=400ms, fx.glow-color=currentColor]{ x }");
            Matcher m = ATTR.matcher(attrs);
            int found = 0;
            while (m.find()) {
                found++;
                String name = m.group(1), value = m.group(2);
                switch (name) {
                    case "data-lx-fx-enter", "data-lx-fx-hover", "data-lx-fx-click" ->
                        assertEquals(e.token(), value);
                    case "data-lx-fx-duration" ->
                        assertTrue(DURATION.matcher(value).matches(), "duration shape: " + value);
                    case "data-lx-fx-glow-color" ->
                        assertTrue(GLOW.matcher(value).matches(), "glow shape: " + value);
                    default -> throw new AssertionError(
                        "fxContainerAttrs emitted a non-contract attribute: " + name
                            + " (the Stafficy sanitizer would strip it — contract drift)");
                }
            }
            assertTrue(found >= 3, "hover+duration+glow expected for " + e.token());
        }
    }

    @Test
    void renderStyledHtmlCarriesExactlyTheSameFxRun() {
        // The wrapper form and the seam form share one stamping source — pin it.
        String latex = "\\lx[fx.click=shatter, fx.duration=400ms]{ x^2 }";
        String attrs = LatteX.fxContainerAttrs(latex);
        assertTrue(LatteX.renderStyledHtml(latex).contains(attrs),
            "renderStyledHtml's opening tag must embed the identical fx attr run");
    }
}
