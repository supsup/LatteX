package com.lattex.api;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Drift-guard for the CSS-only-invisibility fix (Fixpoint review 5744 followup):
/// a page that loads lattex-fx.css but never runs lattex-fx.js must show the math
/// plainly (effects inert), never hide it. Mechanism: every enter-hide rule keys on
/// the `html.lx-fx` root marker that the RUNTIME stamps as its first act — no JS,
/// no marker, no hide. This test fails the build if a future effect's pre-hide is
/// added without the prefix (reintroducing the hole) or the runtime stops stamping.
class FxCssOnlyVisibilityTest {

    /// Any selector that hides an enter-effect container. Written loosely on purpose:
    /// it matches the RULE SHAPE (an [data-lx-fx-enter=...] selector whose block sets
    /// opacity: 0), wherever it appears.
    private static final Pattern HIDE_RULE = Pattern.compile(
        "(?m)^([^@{}]*\\[data-lx-fx-enter=[^\\]]+][^{}]*)\\{[^}]*opacity:\\s*0\\s*;[^}]*}");

    @Test
    void everyEnterHideRuleIsRootMarkerScoped() {
        String css = LatteX.fxStylesCss();
        Matcher m = HIDE_RULE.matcher(css);
        int hides = 0;
        while (m.find()) {
            hides++;
            String selector = m.group(1).trim();
            assertTrue(selector.startsWith("html.lx-fx "),
                "enter-hide rule is NOT scoped to the runtime's root marker — CSS "
                    + "without JS would hide the math forever: \"" + selector + "\"");
        }
        assertTrue(hides >= 3, "expected the fade/inkdrop/constellation pre-hides, found " + hides);
    }

    @Test
    void runtimeStampsTheRootMarkerAsItsFirstAct() {
        String js = LatteX.fxRuntimeJs();
        assertTrue(js.contains("documentElement.classList.add('lx-fx')"),
            "fx runtime must stamp html.lx-fx (the enter-hide rules key on it)");
        // First act: the stamp must precede the vocabulary/init machinery.
        assertTrue(js.indexOf("classList.add('lx-fx')") < js.indexOf("var VOCAB"),
            "the root-marker stamp must run before anything else in the runtime");
    }
}
