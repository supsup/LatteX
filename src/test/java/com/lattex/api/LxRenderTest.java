package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link LatteX#render(String)} applies a top-level {@code \lx}
 * wrapper's style (color / scale / math-style) to the SVG, and that
 * {@link LatteX#renderStyledHtml(String)} wraps that SVG in a trusted
 * {@code <span class="lx-math">} container carrying the fx/semantic annotations as
 * {@code data-lx-*} attributes — while the inner SVG stays affordance-free (⊆ the
 * minimal alphabet, no {@code data-*} / fx / script strings).
 */
class LxRenderTest {

    // ---- style.* folds into the SVG via the L1 render path ----

    @Test
    void lxColorFoldsIntoFill() {
        String svg = LatteX.render("\\lx[style.color=#c0392b]{ \\frac{a+b}{c} }");
        assertTrue(svg.contains("fill=\"#c0392b\""), "\\lx color folds into fill");
    }

    @Test
    void lxScaleGrowsGeometry() {
        double w1 = viewBoxWidth(LatteX.render("\\lx[style.scale=1]{ \\frac{a+b}{c} }"));
        double w2 = viewBoxWidth(LatteX.render("\\lx[style.scale=2]{ \\frac{a+b}{c} }"));
        assertTrue(w2 > 1.8 * w1, "2x scale roughly doubles width: " + w1 + " -> " + w2);
    }

    @Test
    void lxMathStyleChangesLayout() {
        // A big-operator with limits: display stacks them (tall), text sets them
        // beside as scripts (wide, short) — so the two renders differ.
        String display = LatteX.render("\\lx[style.mathstyle=display]{ \\sum_{i=1}^{n} i }");
        String text = LatteX.render("\\lx[style.mathstyle=text]{ \\sum_{i=1}^{n} i }");
        assertFalse(viewBox(display).equals(viewBox(text)),
            "display vs text math-style produce different geometry");
    }

    @Test
    void lxStyleOverridesPassedOptions() {
        // The \lx source style wins over the RenderOptions argument.
        String svg = LatteX.render("\\lx[style.color=#1f7d72]{ x }",
            RenderOptions.defaults().withColor(Color.parse("#c0392b")));
        assertTrue(svg.contains("fill=\"#1f7d72\""), "\\lx style overrides opts");
    }

    // ---- fx / semantics NEVER reach the SVG (they ride the container) ----

    @Test
    void fxAndSemanticsAreNotEmittedIntoSvg() {
        String svg = LatteX.render(
            "\\lx[concept=normalized_score, fx.hover=pulse, fx.duration=250ms, "
                + "a11y.label=\"secret label\", data.role=header]{ \\frac{a+b}{c} }");
        for (String leak : new String[] {"pulse", "250ms", "concept", "normalized_score",
                "data-lx", "data-role", "header"}) {
            assertFalse(svg.contains(leak), "annotation must not be emitted into the SVG: " + leak);
        }
        assertAlphabetContained(svg);
    }

    // ---- renderStyledHtml: the trusted container carries the metadata ----

    @Test
    void containerStampsSemanticAndFxAttributes() {
        String html = LatteX.renderStyledHtml(
            "\\lx[intent=ratio, concept=normalized_score, fx.enter=boom, fx.hover=pulse, "
                + "fx.duration=250ms, a11y.label=\"a b\", data.role=header]{ \\frac{a+b}{c} }");
        assertTrue(html.startsWith("<span class=\"lx-math\""), "opens with the .lx-math span");
        assertTrue(html.endsWith("</span>"), "closes the span");
        assertTrue(html.contains("data-lx-intent=\"ratio\""));
        assertTrue(html.contains("data-lx-concept=\"normalized_score\""));
        assertTrue(html.contains("data-lx-fx-enter=\"boom\""));
        assertTrue(html.contains("data-lx-fx-hover=\"pulse\""));
        assertTrue(html.contains("data-lx-fx-duration=\"250ms\""));
        assertTrue(html.contains("data-lx-role=\"header\""));
        assertTrue(html.contains("aria-label=\"a b\""));
        assertTrue(html.contains("<svg"), "embeds the inner SVG");
    }

    @Test
    void plainExpressionGetsBareContainer() {
        String html = LatteX.renderStyledHtml("x^2");
        assertTrue(html.startsWith("<span class=\"lx-math\">"), "bare container, no data attrs");
        assertFalse(html.contains("data-lx"), "no annotations on a plain expression");
    }

    @Test
    void innerSvgStaysAffordanceFree() {
        String html = LatteX.renderStyledHtml(
            "\\lx[intent=ratio, fx.click=glow, fx.duration=300ms, "
                + "a11y.label=\"secret\", data.role=widget]{ \\frac{a+b}{c} }");
        String svg = innerSvg(html);
        // The SVG must carry ZERO affordance / annotation strings — those live only
        // on the trusted container span.
        for (String affordance : new String[] {"data-", "data-lx", "onclick", "onmouseover",
                "<script", "javascript:", "glow", "300ms", "ratio", "secret", "widget"}) {
            assertFalse(svg.toLowerCase(Locale.ROOT).contains(affordance.toLowerCase(Locale.ROOT)),
                "inner SVG must be affordance-free; found: " + affordance);
        }
        assertAlphabetContained(svg);
    }

    // ---- helpers ----

    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final Set<String> ALLOWED_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label",
        "transform", "fill", "d", "stroke", "stroke-width", "x", "y");

    /** Extracts the inner {@code <svg>…</svg>} from the container HTML. */
    private static String innerSvg(String html) {
        int start = html.indexOf("<svg");
        int end = html.indexOf("</svg>");
        assertTrue(start >= 0 && end > start, "container embeds an <svg>");
        return html.substring(start, end + "</svg>".length());
    }

    private static void assertAlphabetContained(String svg) {
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(svg);
        while (tag.find()) {
            String element = tag.group(1);
            assertTrue(ALLOWED_ELEMENTS.contains(element), "element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=").matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(ALLOWED_ATTRS.contains(attr.group(1)),
                    "attribute out of alphabet: " + attr.group(1));
            }
        }
    }

    private static String viewBox(String svg) {
        Matcher m = Pattern.compile("viewBox=\"([^\"]+)\"").matcher(svg);
        assertTrue(m.find(), "svg has a viewBox");
        return m.group(1);
    }

    private static double viewBoxWidth(String svg) {
        Matcher m = Pattern.compile("viewBox=\"([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg has a viewBox");
        return Double.parseDouble(m.group(3));
    }
}
