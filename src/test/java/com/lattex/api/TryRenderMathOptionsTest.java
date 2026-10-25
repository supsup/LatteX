package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code tryRenderMath(latex, RenderOptions)} (plan lattex-seam-containment-parity): the
 * render-coupled seam honours the HOST options exactly as its parity-pinned sibling
 * {@link LatteX#renderStyledHtml(String, RenderOptions)} does. Without the overload a consumer
 * needing preset macros or fluid sizing had to fall back to the split render + deprecated
 * {@code fxContainerAttrs} path, reopening the glyphmap-desync bug the seam exists to close.
 *
 * <p>Every parity pin uses NON-default options and first proves those options change the
 * output, so a seam that silently dropped them could not pass by matching a default render.
 */
class TryRenderMathOptionsTest {

    /** Host options touching every field the throwing render path honours. */
    private static RenderOptions host() {
        return RenderOptions.defaults()
            .withMacros(Map.of("third", "\\frac{1}{3}"))
            .withColor(RenderOptions.parseColor("#c0392b"))
            .withScale(1.5)
            .withFluid(true);
    }

    @Test
    void presetMacrosAreThreaded() {
        String latex = "\\third + y";
        assertTrue(LatteX.tryRenderMath(latex).isEmpty(),
            "control: without the preset the macro is an unknown command");
        Optional<LatteX.RenderedMath> rm = LatteX.tryRenderMath(latex, host());
        assertTrue(rm.isPresent(), "the host's preset macro expands through the seam");
        assertEquals(LatteX.render(latex, host()), rm.get().svg());
    }

    @Test
    void hostStylingAndFluidMatchTheThrowingAndStyledPaths() {
        String latex = "\\frac{a}{b} + \\third";
        String svg = LatteX.tryRenderMath(latex, host()).orElseThrow().svg();

        // Non-vacuity: these options really change the SVG.
        assertNotEquals(LatteX.render("\\frac{a}{b} + \\frac{1}{3}"), svg, "host options change the output");
        assertTrue(svg.contains("max-width:"), "fluid sizing reached the seam's <svg>: " + svg);
        assertTrue(svg.contains("#c0392b"), "host color reached the seam's fills");

        assertEquals(LatteX.render(latex, host()), svg, "byte-identical to render(latex, opts)");
        assertTrue(LatteX.renderStyledHtml(latex, host()).contains(svg),
            "the SAME svg renderStyledHtml(latex, opts) wraps");
    }

    @Test
    void lxWrapperStillOverridesStylingWhileFluidStaysAHostFlag() {
        String latex = "\\lx[style.color=#2e86c1,fx.hover=thread]{\\third + x + x}";
        LatteX.RenderedMath rm = LatteX.tryRenderMath(latex, host()).orElseThrow();

        assertEquals(LatteX.render(latex, host()), rm.svg(),
            "wrapper styling + host macros + host fluid, exactly as render(latex, opts)");
        assertTrue(rm.svg().contains("max-width:"), "fluid is host-only and survives the wrapper");
        String glyphmap = rm.containerAttrs().get("data-lx-glyphmap");
        assertTrue(glyphmap != null && !glyphmap.isEmpty(), "thread effect -> sidecar present");
        assertTrue(LatteX.renderStyledHtml(latex, host()).contains("data-lx-glyphmap=\"" + glyphmap + "\""),
            "PARITY: the seam's sidecar is the stamp renderStyledHtml(latex, opts) emits: " + glyphmap);
    }

    @Test
    void oneArgFormEqualsTwoArgWithDefaultsOnAnEffectSource() {
        String latex = "\\lx[fx.hover=thread,fx.enter=fade]{x + x^2}";
        assertEquals(LatteX.tryRenderMath(latex, RenderOptions.defaults()), LatteX.tryRenderMath(latex));
    }

    @Test
    void textMathStyleFromTheHostReachesTheSeam() {
        RenderOptions inline = host().inline();
        String latex = "\\frac{a}{b} + \\sum_{i=1}^{n} i";
        String svg = LatteX.tryRenderMath(latex, inline).orElseThrow().svg();
        assertNotEquals(LatteX.render(latex, host()), svg, "control: text style changes the layout");
        assertEquals(LatteX.render(latex, inline), svg, "byte-identical to render(latex, opts.inline())");
    }

    private static final String UNFOLD = "\\lx[fx.click=unfold]{\\sum_{i=1}^{4} f(i)}";
    private static final String SUBSTITUTE = "\\lx[fx.click=substitute, fx.substitute-to=3]{x^2 + 2x + 1}";

    /**
     * The seam NEVER arms interactive expansion: with the host flag set it returns exactly the
     * flag-off result, which is what renderStyledHtml produces with the flag off (its own
     * javadoc: byte-identical to flag-on for any formula without an unfold or substitute
     * directive). An unfold/substitute source degrades inert here, as it does on a flag-off
     * page; a host that wants the effect uses renderStyledHtml. Refusing the flag instead
     * would throw on every input, including "x", and a consumer wrapper that catches
     * RuntimeException would swallow that and fall back to the split path this seam replaces.
     */
    @Test
    void interactiveExpansionIsInertNeverArmedAndNeverRefused() {
        RenderOptions flagOn = host().withInteractiveExpansion(true);
        // Non-vacuity: the flag is live for the sibling on these very sources.
        assertTrue(LatteX.renderStyledHtml(UNFOLD, flagOn).contains("data-lx-fx-expand=\""),
            "control: renderStyledHtml arms unfold with the flag on");
        assertTrue(LatteX.renderStyledHtml(SUBSTITUTE, flagOn).contains("data-lx-fx-substitute=\""),
            "control: renderStyledHtml arms substitute with the flag on");

        for (String latex : java.util.List.of("x", "\\frac{a}{b} + \\third", UNFOLD, SUBSTITUTE)) {
            Optional<LatteX.RenderedMath> on = LatteX.tryRenderMath(latex, flagOn);
            assertTrue(on.isPresent(), latex + ": the flag never refuses or fails the render");
            assertEquals(LatteX.tryRenderMath(latex, host()), on, latex + ": flag-on == flag-off");
            Map<String, String> attrs = on.get().containerAttrs();
            for (String armed : java.util.List.of("data-lx-fx-expand", "data-lx-fx-substitute", "data-lx-var")) {
                assertTrue(!attrs.containsKey(armed), latex + ": the seam never stamps " + armed + "; attrs=" + attrs);
            }
            assertTrue(LatteX.renderStyledHtml(latex, host()).contains(on.get().svg()),
                latex + ": the SAME svg renderStyledHtml wraps with the flag off");
        }
    }

    @Test
    void nullOptionsAreRejected() {
        assertThrows(NullPointerException.class, () -> LatteX.tryRenderMath("x", null));
    }

    @Test
    void anOverflowThroughTheOptionsOverloadIsStillEmpty() {
        Object rendered = StackStarved.assertSucceedsOnRoomyStack("tryRenderMath(DEEP, host())",
            () -> LatteX.tryRenderMath(StackStarved.DEEP, host()));
        assertTrue(((Optional<?>) rendered).isPresent(), "deep input renders on a roomy stack");
        StackStarved.assertFixtureOverflows("the bare parse the overload runs first",
            () -> com.lattex.parse.MathParser.parse(StackStarved.DEEP, host().macros()));
        StackStarved.Outcome o = StackStarved.run(() -> LatteX.tryRenderMath(StackStarved.DEEP, host()));
        assertTrue(!o.threw() && Optional.empty().equals(o.value()),
            "the options overload shares the containment boundary: " + o.describe());
    }
}
