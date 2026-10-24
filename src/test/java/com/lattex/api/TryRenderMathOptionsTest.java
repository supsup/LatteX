package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
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
    void theOneArgFormIsTheDefaultsOverload() {
        String latex = "\\lx[fx.hover=thread,fx.enter=fade]{x + x^2}";
        assertEquals(LatteX.tryRenderMath(latex, RenderOptions.defaults()), LatteX.tryRenderMath(latex));
    }

    @Test
    void interactiveExpansionIsRefusedLoudNotDroppedSilently() {
        // The record has no slot for the pre-rendered hidden sibling payload that the flag
        // produces, so honouring the flag is impossible here; dropping it would be the silent
        // divergence from renderStyledHtml this overload must not have. Exactly IAE, not the
        // LatteXException subtype: this is a host-configuration error, not an author one.
        IllegalArgumentException ex = assertThrowsExactly(IllegalArgumentException.class,
            () -> LatteX.tryRenderMath("x", RenderOptions.defaults().withInteractiveExpansion(true)));
        assertTrue(ex.getMessage().contains("renderStyledHtml"), "names the path that honours it: " + ex.getMessage());
    }

    @Test
    void nullOptionsAreRejected() {
        assertThrows(NullPointerException.class, () -> LatteX.tryRenderMath("x", null));
    }

    @Test
    void anOverflowThroughTheOptionsOverloadIsStillEmpty() {
        assertTrue(LatteX.tryRenderMath(StackStarved.DEEP, host()).isPresent(), "deep input renders normally");
        StackStarved.assertFixtureOverflows("the bare parse the overload runs first",
            () -> com.lattex.parse.MathParser.parse(StackStarved.DEEP, host().macros()));
        StackStarved.Outcome o = StackStarved.run(() -> LatteX.tryRenderMath(StackStarved.DEEP, host()));
        assertTrue(!o.threw() && Optional.empty().equals(o.value()),
            "the options overload shares the containment boundary: " + o.describe());
    }
}
