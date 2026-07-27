package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class InteractiveMathTest {

    private static final Diagnostics OK = new Diagnostics(
        Outcome.OK, "emit", "Rendered successfully.", -1, "", -1, "");
    private static final Pattern MARKUP_TAG = Pattern.compile(
        "<(/?)([a-zA-Z][a-zA-Z0-9]*)([^<>]*)>");
    private static final Pattern MARKUP_ATTRIBUTE = Pattern.compile(
        "([a-zA-Z][a-zA-Z-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Map<String, Set<String>> ALLOWED_ATTRIBUTES = Map.of(
        "figure", Set.of("class", "data-lx-transition", "data-lx-duration"),
        "figcaption", Set.of("class"),
        "div", Set.of("class", "data-lx-state"),
        "span", Set.of("class"),
        "button", Set.of("class", "type", "aria-expanded"),
        "svg", Set.of("viewBox", "width", "height", "xmlns", "role", "aria-label"),
        "g", Set.of("transform", "fill"),
        "path", Set.of("d", "fill", "stroke", "stroke-width", "transform"),
        "rect", Set.of("x", "y", "width", "height", "fill"));

    @Test
    void assemblesTwoExactStaticEndpointsWithoutTouchingTheirBytes() {
        String from = LatteX.render("x^2 + 1");
        String to = LatteX.render("\\frac{1}{x}");

        InteractiveResult result = InteractiveMath.render("x^2 + 1", "\\frac{1}{x}");

        assertEquals(InteractiveResult.Status.INTERACTIVE, result.status());
        assertTrue(result.interactive());
        assertEquals(Outcome.OK, result.fromDiagnostics().outcome());
        assertEquals(Outcome.OK, result.toDiagnostics().outcome());
        assertEquals(1, occurrences(result.html(), from),
            "the independently rendered initial SVG must be embedded byte-for-byte once");
        assertEquals(1, occurrences(result.html(), to),
            "the independently rendered alternate SVG must be embedded byte-for-byte once");
        assertEquals(2, occurrences(result.html(), "<svg "));
        assertTrue(result.html().contains("data-lx-duration=\"240\""));
        assertTrue(result.html().contains("type=\"button\" aria-expanded=\"false\""));
    }

    @Test
    void wrapperIsFixedInertMarkupAndNeverCarriesRawLatex() {
        String fromSource = "\\frac{secretAlpha}{x}";
        String toSource = "\\sqrt{secretBeta}";
        String html = InteractiveMath.render(fromSource, toSource).html();
        String lower = html.toLowerCase(java.util.Locale.ROOT);

        assertFalse(html.contains(fromSource));
        assertFalse(html.contains(toSource));
        assertFalse(html.contains("\\frac"));
        assertFalse(html.contains("\\sqrt"));
        for (String forbidden : List.of("<script", "<style", "javascript:",
                "xlink:", " href=", " onload=", " onclick=", " style=", " id=")) {
            assertFalse(lower.contains(forbidden), "forbidden wrapper token: " + forbidden);
        }

        assertAllowedMarkup(html);

        String hostileFrom = "\\text{<script onload=x>}";
        String hostileTo = "\\text{<img onerror=x>}";
        assertTrue(LatteX.render(hostileFrom).startsWith("<svg "),
            "the hostile source must reach the existing static emitter");
        assertTrue(LatteX.render(hostileTo).startsWith("<svg "));
        InteractiveResult hostileResult = InteractiveMath.render(hostileFrom, hostileTo);
        assertEquals(InteractiveResult.Status.FAILED, hostileResult.status());
        assertEquals("interactive-validate", hostileResult.fromDiagnostics().stage());
        assertEquals("interactive-validate", hostileResult.toDiagnostics().stage());
        String hostile = hostileResult.html();
        assertEquals("", hostile, "mutant endpoints must never produce a partial wrapper");
        String hostileLower = hostile.toLowerCase(java.util.Locale.ROOT);
        assertFalse(hostileLower.contains("<script"));
        assertFalse(hostileLower.contains("<img"));
        assertFalse(hostileLower.contains(" onload="));
        assertFalse(hostileLower.contains(" onerror="));

        InteractiveResult hostileFallback = InteractiveMath.render(hostileFrom, "x");
        assertEquals(InteractiveResult.Status.STATIC_FALLBACK, hostileFallback.status());
        assertEquals(LatteX.render("x"), hostileFallback.html());
    }

    @Test
    void oneBadEndpointFallsBackToTheOtherExactStaticSvg() {
        String valid = LatteX.render("x + 1");

        InteractiveResult badAlternate = InteractiveMath.render("x + 1", "\\notACommand{x}");
        assertEquals(InteractiveResult.Status.STATIC_FALLBACK, badAlternate.status());
        assertEquals(valid, badAlternate.html());
        assertEquals(Outcome.OK, badAlternate.fromDiagnostics().outcome());
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, badAlternate.toDiagnostics().outcome());

        InteractiveResult badInitial = InteractiveMath.render("\\notACommand{x}", "x + 1");
        assertEquals(InteractiveResult.Status.STATIC_FALLBACK, badInitial.status());
        assertEquals(valid, badInitial.html());
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, badInitial.fromDiagnostics().outcome());
        assertEquals(Outcome.OK, badInitial.toDiagnostics().outcome());
    }

    @Test
    void renderedOutputCapUsesTheTypedStaticFallbackChannel() {
        String overCapButInputBounded = "\\boxed{}".repeat(10_000);
        String valid = LatteX.render("y");

        InteractiveResult result = InteractiveMath.render(overCapButInputBounded, "y");

        assertEquals(InteractiveResult.Status.STATIC_FALLBACK, result.status());
        assertEquals(valid, result.html());
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, result.fromDiagnostics().outcome());
        assertEquals(Outcome.OK, result.toDiagnostics().outcome());
    }

    @Test
    void totalFailureNeverReturnsAPartialWrapper() {
        InteractiveResult result = InteractiveMath.render("\\notACommand", "\\alsoUnknown");
        assertEquals(InteractiveResult.Status.FAILED, result.status());
        assertEquals("", result.html());
        assertFalse(result.interactive());
    }

    @Test
    void nullInputsAndOptionsUseTypedFailureOrStaticFallbackChannels() {
        InteractiveResult missingInitial = InteractiveMath.render(null, "x");
        assertEquals(InteractiveResult.Status.STATIC_FALLBACK, missingInitial.status());
        assertEquals(LatteX.render("x"), missingInitial.html());
        assertEquals(Outcome.PARSE_ERROR, missingInitial.fromDiagnostics().outcome());

        InteractiveResult missingBoth = InteractiveMath.render(null, null);
        assertEquals(InteractiveResult.Status.FAILED, missingBoth.status());
        assertEquals(Outcome.PARSE_ERROR, missingBoth.fromDiagnostics().outcome());

        InteractiveResult missingOptions = InteractiveMath.render("x", "y", null);
        assertEquals(InteractiveResult.Status.FAILED, missingOptions.status());
        assertEquals("interactive-options", missingOptions.fromDiagnostics().stage());
    }

    @Test
    void durationBoundsAreExact() {
        assertEquals(0, new InteractiveOptions(RenderOptions.defaults(), 0).durationMillis());
        assertEquals(2_000,
            new InteractiveOptions(RenderOptions.defaults(), 2_000).durationMillis());
        assertThrows(IllegalArgumentException.class,
            () -> new InteractiveOptions(RenderOptions.defaults(), -1));
        assertThrows(IllegalArgumentException.class,
            () -> new InteractiveOptions(RenderOptions.defaults(), 2_001));
        assertThrows(NullPointerException.class, () -> new InteractiveOptions(null, 240));
    }

    @Test
    void fluidRenderOptionsAreRefusedAtTheCurrentS8Boundary() {
        RenderOptions fluid = RenderOptions.defaults().withFluid(true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new InteractiveOptions(fluid, 240));
        assertTrue(error.getMessage().contains("S8"));
        assertTrue(error.getMessage().contains("inline style"));
    }

    @Test
    void fixedRenderOptionsRemainExactAtBothEndpoints() {
        RenderOptions renderOptions = RenderOptions.defaults()
            .withScale(1.4)
            .withColor(Color.parse("#c0392b"));
        InteractiveOptions options = new InteractiveOptions(renderOptions, 2_000);

        InteractiveResult result = InteractiveMath.render("x^2", "y_1", options);

        assertEquals(InteractiveResult.Status.INTERACTIVE, result.status());
        assertEquals(1, occurrences(result.html(), LatteX.render("x^2", renderOptions)));
        assertEquals(1, occurrences(result.html(), LatteX.render("y_1", renderOptions)));
        assertTrue(result.html().contains("data-lx-duration=\"2000\""));
        assertAllowedMarkup(result.html());
    }

    @Test
    void combinedSourceBudgetHasBelowAtAndAbovePins() {
        InteractiveResult below = InteractiveMath.render(
            "x", " ".repeat(InteractiveMath.MAX_TOTAL_SOURCE_CHARS - 2));
        assertTrue(below.status() != InteractiveResult.Status.FAILED,
            "one character below the combined cap must reach endpoint rendering");

        InteractiveResult at = InteractiveMath.render(
            "x", " ".repeat(InteractiveMath.MAX_TOTAL_SOURCE_CHARS - 1));
        assertTrue(at.status() != InteractiveResult.Status.FAILED,
            "the exact combined cap must remain admitted");

        InteractiveResult above = InteractiveMath.render(
            "x", " ".repeat(InteractiveMath.MAX_TOTAL_SOURCE_CHARS));
        assertEquals(InteractiveResult.Status.FAILED, above.status());
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, above.fromDiagnostics().outcome());
        assertEquals("interactive-input", above.fromDiagnostics().stage());
    }

    @Test
    void aggregateComponentCapIsIndependentOfEachEndpointCap() {
        String minimal = safeSvg();
        int wrapperChars = InteractiveMath.assemble(minimal, minimal, 240).length()
            - (2 * minimal.length());
        int firstEndpointChars = (InteractiveMath.MAX_COMPONENT_CHARS - wrapperChars) / 2;
        int secondEndpointChars = InteractiveMath.MAX_COMPONENT_CHARS - wrapperChars
            - firstEndpointChars;
        String exactFirst = paddedSafeSvg(firstEndpointChars);
        String exactSecond = paddedSafeSvg(secondEndpointChars);
        assertEquals(InteractiveMath.MAX_COMPONENT_CHARS,
            InteractiveMath.assemble(exactFirst, exactSecond, 240).length());
        assertEquals(InteractiveMath.MAX_COMPONENT_CHARS - 1,
            InteractiveMath.assemble(exactFirst, paddedSafeSvg(secondEndpointChars - 1), 240)
                .length());
        assertThrows(IllegalArgumentException.class, () -> InteractiveMath.assemble(
            exactFirst, paddedSafeSvg(secondEndpointChars + 1), 240));

        String endpointAtCap = paddedSafeSvg(InteractiveMath.MAX_ENDPOINT_CHARS);
        assertEquals(InteractiveMath.MAX_ENDPOINT_CHARS, endpointAtCap.length());
        assertTrue(InteractiveMath.assemble(endpointAtCap, minimal, 240).length()
            < InteractiveMath.MAX_COMPONENT_CHARS);

        String endpointBelowCap = paddedSafeSvg(InteractiveMath.MAX_ENDPOINT_CHARS - 1);
        assertTrue(InteractiveMath.assemble(endpointBelowCap, minimal, 240).length()
            < InteractiveMath.MAX_COMPONENT_CHARS);

        String endpointTooLarge = paddedSafeSvg(InteractiveMath.MAX_ENDPOINT_CHARS + 1);
        assertThrows(IllegalArgumentException.class,
            () -> InteractiveMath.assemble(endpointTooLarge, safeSvg(), 240));
    }

    @Test
    void runtimeSvgBackstopAcceptsTheRealEmitterAndRejectsInjectionMutants() {
        for (String latex : List.of("x", "\\frac{a+b}{c}",
                "\\begin{array}{c|c}1&2\\\\\\hline 3&4\\end{array}",
                "\\text{if } x < y")) {
            assertTrue(InteractiveMath.staticSvgSafe(LatteX.render(latex)), latex);
        }

        String safe = safeSvg();
        assertTrue(InteractiveMath.staticSvgSafe(safe));
        for (String mutant : List.of(
                safe.replace("</svg>", "<script></script></svg>"),
                safe.replace("role=\"img\"", "role=\"img\" onload=\"boom()\""),
                safe.replace("role=\"img\"", "role=\"img\" href=\"https://example.test\""),
                safe.replace("role=\"img\"",
                    "role=\"img\" style=\"background:url(example)\""),
                safe.replace("<path", "<circle"),
                safe.replace("<path d=\"M0 0\"/>", "<path d=\"M0 0\"></path>"),
                safe.replace("<path d=\"M0 0\"/>",
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\""
                        + " width=\"1\" height=\"1\" role=\"img\" aria-label=\"nested\">"
                        + "</svg>"),
                safe.replace("aria-label=\"x\"", "aria-label=\"&#120;\""),
                safe.replace("width=\"1\"", "width=\"1\" width=\"1\""),
                safe.replace("width=\"1\" height", "width=\"1\"height"),
                safe.replace(" d=\"M0 0\"", ""),
                safe.replace("<path", "raw-text<path"),
                safe.substring(0, safe.length() - 1))) {
            assertFalse(InteractiveMath.staticSvgSafe(mutant), "mutant must fail: " + mutant);
        }
    }

    @Test
    void separateAssetsAreExactClasspathResourcesAndAvoidTheExistingFxRuntime()
            throws IOException {
        String js = InteractiveMath.runtimeJs();
        String css = InteractiveMath.stylesCss();
        assertEquals(resource("/com/lattex/interactive/lattex-interactive.js"), js);
        assertEquals(resource("/com/lattex/interactive/lattex-interactive.css"), css);
        assertTrue(js.contains("global.LatteXInteractive"));
        assertTrue(css.contains(".lx-transition--ready"));
        assertFalse(js.contains("lattex-fx"));
        assertFalse(css.contains("lattex-fx"));
        assertFalse(js.contains("inner" + "HTML"));

        String nativeResources = resource(
            "/META-INF/native-image/com.lattex/lattex/resource-config.json");
        assertTrue(nativeResources.contains(
            "\\\\Qcom/lattex/interactive/lattex-interactive.js\\\\E"));
        assertTrue(nativeResources.contains(
            "\\\\Qcom/lattex/interactive/lattex-interactive.css\\\\E"));
    }

    @Test
    void resultRecordRejectsImpossiblePartialStates() {
        Diagnostics bad = new Diagnostics(Outcome.PARSE_ERROR, "parse", "bad", -1, "", -1, "");
        assertThrows(IllegalArgumentException.class, () -> new InteractiveResult(
            "", InteractiveResult.Status.INTERACTIVE, OK, OK, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new InteractiveResult(
            "", InteractiveResult.Status.STATIC_FALLBACK, OK, bad, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new InteractiveResult(
            "partial", InteractiveResult.Status.FAILED, bad, bad, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new InteractiveResult(
            "component", InteractiveResult.Status.INTERACTIVE, OK, bad, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new InteractiveResult(
            "fallback", InteractiveResult.Status.STATIC_FALLBACK, bad, bad, "bad"));
    }

    private static String safeSvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\""
            + " width=\"1\" height=\"1\" role=\"img\" aria-label=\"x\">"
            + "<path d=\"M0 0\"/></svg>";
    }

    private static String paddedSafeSvg(int targetLength) {
        String prefix = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\""
            + " width=\"1\" height=\"1\" role=\"img\" aria-label=\"";
        String suffix = "\"></svg>";
        if (targetLength < prefix.length() + suffix.length()) {
            throw new IllegalArgumentException("target too short");
        }
        return prefix + "a".repeat(targetLength - prefix.length() - suffix.length()) + suffix;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void assertAllowedMarkup(String html) {
        Matcher tags = MARKUP_TAG.matcher(html);
        int tagCount = 0;
        while (tags.find()) {
            tagCount++;
            String element = tags.group(2);
            Set<String> allowed = ALLOWED_ATTRIBUTES.get(element);
            assertNotNull(allowed,
                "element escaped fixed outer + static-inner alphabets: " + element);
            if (!tags.group(1).isEmpty()) {
                assertTrue(tags.group(3).isBlank(), "closing tag carried data: " + tags.group());
                continue;
            }

            String attributes = tags.group(3).trim();
            if (attributes.endsWith("/")) {
                attributes = attributes.substring(0, attributes.length() - 1).trim();
            }
            Matcher matcher = MARKUP_ATTRIBUTE.matcher(attributes);
            Set<String> names = new HashSet<>();
            int cursor = 0;
            while (cursor < attributes.length()) {
                while (cursor < attributes.length()
                        && Character.isWhitespace(attributes.charAt(cursor))) {
                    cursor++;
                }
                matcher.region(cursor, attributes.length());
                assertTrue(matcher.lookingAt(),
                    "attribute syntax escaped the fixed serializer: " + tags.group());
                String name = matcher.group(1);
                assertTrue(allowed.contains(name),
                    "attribute escaped the per-element allowlist: " + element + "." + name);
                assertTrue(names.add(name),
                    "duplicate attribute escaped the fixed serializer: " + element + "." + name);
                cursor = matcher.end();
                if (cursor < attributes.length()) {
                    assertTrue(Character.isWhitespace(attributes.charAt(cursor)),
                        "attributes require whitespace separation: " + tags.group());
                }
            }
        }
        assertTrue(tagCount > 10, "the allowlist assertion must scan real endpoint markup");
        assertEquals(tagCount, occurrences(html, "<"),
            "every markup opener must be consumed by the allowlist parser");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = InteractiveMathTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
