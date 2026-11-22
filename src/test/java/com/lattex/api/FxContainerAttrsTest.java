package com.lattex.api;

import com.lattex.parse.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Drift-guard for the pinned Producer API (container-output-contract.md → "Producer
/// API — fxContainerAttrs"): the output must use ONLY the contract's seven
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

    @Test
    void explicitNumericKnobsHaveOneOrderedStampAcrossMapStringAndWrapper() {
        for (String effect : new String[]{"lightning", "storm"}) {
            String omitted = "\\lx[fx.hover=" + effect + ",fx.duration=250ms,fx.glow-color=#abc]{x^2+1}";
            LatteX.RenderedMath baseline = LatteX.tryRenderMath(omitted).orElseThrow();
            for (int[] values : new int[][]{{1, 0}, {2, 100}, {8, 300}}) {
                String latex = "\\lx[fx.hover=" + effect + ",fx.duration=250ms,fx.glow-color=#abc,"
                    + "fx.bolts=" + values[0] + ",fx.intensity=" + values[1] + "]{x^2+1}";
                LatteX.RenderedMath rendered = LatteX.tryRenderMath(latex).orElseThrow();
                Map<String, String> attrs = rendered.containerAttrs();
                assertEquals(Set.of("data-lx-fx-hover", "data-lx-fx-duration", "data-lx-fx-glow-color",
                    "data-lx-fx-bolts", "data-lx-fx-intensity"), attrs.keySet());
                assertEquals(Integer.toString(values[0]), attrs.get("data-lx-fx-bolts"));
                assertEquals(Integer.toString(values[1]), attrs.get("data-lx-fx-intensity"));
                String expected = " data-lx-fx-hover=\"" + effect + "\" data-lx-fx-duration=\"250ms\""
                    + " data-lx-fx-glow-color=\"#aabbcc\" data-lx-fx-bolts=\"" + values[0]
                    + "\" data-lx-fx-intensity=\"" + values[1] + "\"";
                StringBuilder joined = new StringBuilder();
                attrs.forEach((name, value) -> joined.append(' ').append(name).append("=\"")
                    .append(value).append('"'));
                assertEquals(expected, joined.toString(), "record iteration order");
                assertEquals(expected, LatteX.fxContainerAttrs(latex), "string seam");
                assertEquals("<span class=\"lx-math\"" + expected + ">" + rendered.svg() + "</span>",
                    LatteX.renderStyledHtml(latex), "wrapper and map use the same stamp");
                assertEquals(baseline.svg(), rendered.svg(), "numeric knobs leave inner SVG unchanged");
            }
        }
    }

    @Test
    void eachKnobStampsOnlyWhenExplicitIncludingDefaultsAndZero() {
        for (String effect : new String[]{"lightning", "storm"}) {
            String prefix = "\\lx[fx.hover=" + effect;
            for (String suffix : new String[]{",fx.bolts=2", ",fx.intensity=100", ",fx.intensity=0"}) {
                String latex = prefix + suffix + "]{x}";
                Map<String, String> attrs = LatteX.tryRenderMath(latex).orElseThrow().containerAttrs();
                boolean bolts = suffix.startsWith(",fx.bolts");
                assertEquals(bolts, attrs.containsKey("data-lx-fx-bolts"));
                assertEquals(!bolts, attrs.containsKey("data-lx-fx-intensity"));
                assertEquals(2, attrs.size(), "only the effect and explicit knob");
                String stamp = LatteX.fxContainerAttrs(latex);
                assertTrue(stamp.contains(bolts ? "data-lx-fx-bolts=\"2\""
                    : "data-lx-fx-intensity=\"" + (suffix.endsWith("=0") ? "0" : "100") + "\""));
            }
            Map<String, String> omitted = LatteX.tryRenderMath(prefix + "]{x}").orElseThrow().containerAttrs();
            assertFalse(omitted.containsKey("data-lx-fx-bolts"));
            assertFalse(omitted.containsKey("data-lx-fx-intensity"));
            assertEquals(" data-lx-fx-hover=\"" + effect + "\"", LatteX.fxContainerAttrs(prefix + "]{x}"));
        }
        assertEquals(" data-lx-fx-bolts=\"2\" data-lx-fx-intensity=\"0\"",
            LatteX.fxContainerAttrs("\\lx[fx.bolts=\"002\",fx.intensity=\"000\"]{x}"),
            "explicit decimal values are canonical ASCII, even without a trigger");
    }

    @Test
    void omittedKnobsMatchExactPredecessorWrapperAndSvgBytes() throws IOException {
        String fixture;
        try (InputStream stream = getClass().getResourceAsStream("/com/lattex/fx/absent-knobs-golden.html")) {
            assertNotNull(stream, "immutable predecessor fixture must be present");
            fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Pattern row = Pattern.compile("<template data-case=\"([^\"]+)\" data-input-base64=\"([^\"]+)\""
            + " data-svg-base64=\"([^\"]+)\">(.*?)</template>", Pattern.DOTALL);
        Matcher rows = row.matcher(fixture);
        Set<String> cases = new HashSet<>();
        int count = 0;
        while (rows.find()) {
            count++;
            String id = rows.group(1);
            assertTrue(cases.add(id), "duplicate predecessor case " + id);
            String input = new String(Base64.getDecoder().decode(rows.group(2)), StandardCharsets.UTF_8);
            byte[] expectedSvg = Base64.getDecoder().decode(rows.group(3));
            byte[] expectedWrapper = rows.group(4).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expectedWrapper, LatteX.renderStyledHtml(input).getBytes(StandardCharsets.UTF_8),
                "exact predecessor wrapper bytes for case " + id);
            LatteX.RenderedMath rendered = LatteX.tryRenderMath(input).orElseThrow();
            assertArrayEquals(expectedSvg, rendered.svg().getBytes(StandardCharsets.UTF_8),
                "exact predecessor inner SVG bytes for case " + id);
            assertFalse(rendered.containerAttrs().containsKey("data-lx-fx-bolts"));
            assertFalse(rendered.containerAttrs().containsKey("data-lx-fx-intensity"));
        }
        assertEquals(6, count, "all six retained predecessor rows must be exercised");
        assertEquals(Set.of("0", "1", "2", "3", "4", "5"), cases);
    }
}
