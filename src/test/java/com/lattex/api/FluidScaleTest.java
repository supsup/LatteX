package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathSyntaxException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Scale-to-fit display math ({@code RenderOptions.fluid}) — the responsive-sizing
 * opt-in. Three pinned invariants:
 *
 * <ol>
 *   <li><strong>Default-off is byte-identical.</strong> With {@code fluid=false} (the
 *       default) every entry point's output carries no fluid marker and
 *       {@code render(latex)} == {@code render(latex, defaults())} byte-for-byte.</li>
 *   <li><strong>Fluid is ONE presentation rule, nothing else.</strong> With
 *       {@code fluid=true} the display {@code <svg>} gains exactly one sizing
 *       {@code style} rule ({@code width:100%;max-width:<natural>px;height:auto});
 *       removing that one attribute restores the fixed-size output byte-for-byte, so
 *       the viewBox — and every glyph — is provably unchanged.</li>
 *   <li><strong>Inline/fragment immunity.</strong> {@code renderInlineResult} /
 *       {@code renderFragment} baselines stay fixed-size regardless of the flag (they
 *       are structurally flagless), and the {@code \lx} sub-language cannot smuggle
 *       the flag in from author source.</li>
 * </ol>
 */
class FluidScaleTest {

    /** The one fluid marker: a sizing style rule with a numeric natural-width cap. */
    private static final Pattern FLUID_STYLE = Pattern.compile(
        " style=\"width:100%;max-width:(-?[0-9.]+)px;height:auto\"");

    private static final List<String> FIXTURES = List.of(
        "\\frac{a+b}{c}",
        "E = mc^2",
        "\\sum_{i=1}^{n} \\frac{\\sqrt{\\alpha_i^2 + \\beta_i^2}}{\\gamma}"
            + " \\leq \\left( \\int_0^\\infty e^{-x}\\,dx \\right)",
        "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}");

    // ------------------------------------------------------------------
    // 1. Default-off byte-identity.
    // ------------------------------------------------------------------

    @Test
    void defaultsAreFluidOffAndByteIdentical() {
        assertFalse(RenderOptions.defaults().fluid(), "fluid must default OFF");
        for (String latex : FIXTURES) {
            String plain = LatteX.render(latex);
            assertEquals(plain, LatteX.render(latex, RenderOptions.defaults()),
                "defaults render must be byte-identical to render(latex) for " + latex);
            assertEquals(plain, LatteX.render(latex, RenderOptions.defaults().withFluid(false)),
                "explicit fluid=false must be byte-identical for " + latex);
            assertFalse(plain.contains("style="),
                "default render must carry NO style attribute for " + latex);
        }
        // The styled-HTML path with defaults is fluid-free too.
        String html = LatteX.renderStyledHtml("\\frac{a+b}{c}");
        assertFalse(html.contains("style="), "default styled HTML must carry no style attribute");
    }

    // ------------------------------------------------------------------
    // 2. Fluid-on: one sizing rule; viewBox (and everything else) unchanged.
    // ------------------------------------------------------------------

    @Test
    void fluidDisplayCarriesTheOneSizingRuleAndNothingElse() {
        RenderOptions fluid = RenderOptions.defaults().withFluid(true);
        for (String latex : FIXTURES) {
            String fixed = LatteX.render(latex);
            String responsive = LatteX.render(latex, fluid);

            Matcher m = FLUID_STYLE.matcher(responsive);
            assertTrue(m.find(), "fluid render must carry the sizing style rule for " + latex);
            assertFalse(m.find(), "exactly ONE sizing rule, never more, for " + latex);

            // The max-width cap IS the natural width — never upscale past it.
            m.reset().find();
            assertEquals(" width=\"" + m.group(1) + "\"", widthAttr(responsive),
                "max-width cap must equal the natural width attribute for " + latex);

            // Strip the one style attribute → byte-identical to the fixed-size render:
            // proves the viewBox, width/height fallback attrs, and every inner element
            // are untouched, in one assertion.
            assertEquals(fixed, m.replaceFirst(""),
                "fluid must differ ONLY by the one sizing rule for " + latex);

            // And the viewBox explicitly, since it is the aspect-ratio contract.
            assertEquals(viewBoxOf(fixed), viewBoxOf(responsive),
                "viewBox must be unchanged by fluid for " + latex);
        }
    }

    @Test
    void fluidStyledHtmlSizesTheSvgNotTheContainer() {
        String latex = "\\lx[fx.hover=glow]{ \\frac{a+b}{c} }";
        String fixed = LatteX.renderStyledHtml(latex, RenderOptions.defaults());
        String responsive = LatteX.renderStyledHtml(latex, RenderOptions.defaults().withFluid(true));

        // The sizing rides the <svg>; the <span class="lx-math"> opening tag is
        // byte-identical (the container-attribute contract is untouched).
        assertEquals(openTagOf(fixed), openTagOf(responsive),
            "the container opening tag must be unchanged by fluid");
        Matcher m = FLUID_STYLE.matcher(responsive);
        assertTrue(m.find(), "fluid styled HTML must carry the sizing rule on the svg");
        assertEquals(fixed, m.replaceFirst(""),
            "fluid styled HTML must differ only by the one sizing rule");
    }

    // ------------------------------------------------------------------
    // 3. Inline/fragment immunity.
    // ------------------------------------------------------------------

    @Test
    void inlineAndFragmentBaselinesStayFixedSize() {
        for (String latex : FIXTURES) {
            // renderInlineResult takes no options — structurally flagless. Its svg
            // must stay fixed-size: numeric width/height attributes, no style rule.
            String inline = LatteX.renderInlineResult(latex).svg();
            assertFalse(inline.contains("style="),
                "inline render must never carry a sizing style rule for " + latex);
            assertTrue(widthAttr(inline).matches(" width=\"-?[0-9.]+\""),
                "inline width must stay a fixed numeric attribute for " + latex);

            // renderFragment emits no <svg> wrapper at all — nothing to size.
            String fragment = LatteX.renderFragment(latex, 24.0).innerSvg();
            assertFalse(fragment.contains("<svg"), "fragment has no svg wrapper: " + latex);
            assertFalse(fragment.contains("style="),
                "fragment must never carry a style rule for " + latex);
        }
    }

    @Test
    void fluidIsHostOnlyNeverAnLxKey() {
        // fluid is a HOST flag, not part of the \lx sub-language: an author cannot
        // switch a page to responsive sizing from inside a formula. Unknown keys
        // fail loud at parse time.
        assertThrows(MathSyntaxException.class,
            () -> LatteX.render("\\lx[style.fluid=true]{ x^2 }"),
            "style.fluid must be rejected as an unknown \\lx key");
        // And a \lx style override does not disturb the host's fluid choice.
        String responsive = LatteX.render("\\lx[style.scale=lg]{ x^2 }",
            RenderOptions.defaults().withFluid(true));
        assertTrue(FLUID_STYLE.matcher(responsive).find(),
            "host fluid=true must apply even when \\lx overrides the styling");
    }

    @Test
    void withFluidRoundTripsAndComposes() {
        RenderOptions on = RenderOptions.defaults().withFluid(true);
        assertTrue(on.fluid());
        assertFalse(on.withFluid(false).fluid());
        // Orthogonal knobs survive the copy, and other with* copies keep fluid.
        assertTrue(on.withScale(2.0).fluid());
        assertTrue(on.withInteractiveExpansion(true).fluid());
        assertEquals(2.0, on.withScale(2.0).scale());
        assertFalse(on.withFluid(true).interactiveExpansion(),
            "withFluid must not disturb interactiveExpansion");
    }

    // ------------------------------------------------------------------

    private static String widthAttr(String svg) {
        Matcher m = Pattern.compile(" width=\"[^\"]*\"").matcher(svg);
        assertTrue(m.find(), "svg must carry a width attribute");
        return m.group();
    }

    private static String viewBoxOf(String svg) {
        Matcher m = Pattern.compile("viewBox=\"([^\"]*)\"").matcher(svg);
        assertTrue(m.find(), "svg must carry a viewBox");
        return m.group(1);
    }

    private static String openTagOf(String html) {
        Matcher m = Pattern.compile("^<span\\b[^>]*>").matcher(html);
        assertTrue(m.find(), "styled HTML must open with the lx-math span");
        return m.group();
    }
}
