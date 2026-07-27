package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The render-coupled seam (seam sign-off lattex 163→165): {@link LatteX#tryRenderMath}
 * returns SVG + container attributes from ONE parse+layout, so the {@code data-lx-glyphmap}
 * sidecar's path indices can never desync from the emitted paths — the structural reason the
 * parse-only {@code fxContainerAttrs} seam left {@code thread} inert on the split consumer.
 */
class RenderedMathSeamTest {

    @Test
    void plainExpressionRendersWithEmptyAttrsAndSvgIdenticalToRender() {
        Optional<LatteX.RenderedMath> rm = LatteX.tryRenderMath("\\frac{a}{b}");

        assertTrue(rm.isPresent());
        assertTrue(rm.get().containerAttrs().isEmpty(), "no effects -> no container attrs");
        assertEquals(LatteX.render("\\frac{a}{b}"), rm.get().svg(),
            "the record's SVG is byte-identical to the plain render path");
    }

    @Test
    void authoredEffectRidesTheAttrMapWithContractPinnedKeys() {
        Optional<LatteX.RenderedMath> rm = LatteX.tryRenderMath("\\lx[fx.hover=glow]{x^2}");

        assertTrue(rm.isPresent());
        Map<String, String> attrs = rm.get().containerAttrs();
        assertEquals("glow", attrs.get("data-lx-fx-hover"));
        assertFalse(attrs.containsKey("data-lx-glyphmap"), "no thread effect -> no sidecar bytes");
    }

    @Test
    void threadEffectCarriesAGrammarValidGlyphmapMatchingRenderStyledHtml() {
        String latex = "\\lx[fx.hover=thread]{x + x^2}";

        Optional<LatteX.RenderedMath> rm = LatteX.tryRenderMath(latex);

        assertTrue(rm.isPresent());
        String glyphmap = rm.get().containerAttrs().get("data-lx-glyphmap");
        assertTrue(glyphmap != null && !glyphmap.isEmpty(), "thread effect -> sidecar present");
        // The pinned contract grammar (container-output-contract.md).
        assertTrue(glyphmap.matches("^[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*$"),
            "sidecar matches the contract grammar: " + glyphmap);
        // PARITY PIN: the record's sidecar equals the one renderStyledHtml stamps — one
        // layout, one producer, two public shapes that cannot drift.
        String styled = LatteX.renderStyledHtml(latex);
        assertTrue(styled.contains("data-lx-glyphmap=\"" + glyphmap + "\""),
            "record sidecar matches the renderStyledHtml stamp: " + glyphmap);
        // And the record's SVG is the same emission the map indices address.
        assertEquals(LatteX.render(latex), rm.get().svg());
    }

    /**
     * REGRESSION (live bug, Stafficy /docs): the seam had its OWN literal
     * {@code containsValue(Effect.THREAD)} gate instead of the shared {@code usesGlyphmap}
     * (THREAD or CANCEL), so a {@code cancel}-authored expression came back through
     * {@code tryRenderMath} with its fx attributes present but the glyphmap sidecar ABSENT.
     * {@code cancel} reads that sidecar to find the code point occurring exactly twice, so the
     * effect went silently inert on the split-wrapper consumer — no exception, no warning.
     *
     * <p>This must exercise the SEAM, not {@link LatteX#renderStyledHtml}: the wrapper path
     * always used the shared gate and stayed green throughout the bug.
     */
    @Test
    void cancelEffectThroughTheSeamCarriesTheGlyphmapSidecar() {
        String latex = "\\lx[fx.enter=cancel]{\\frac{x}{x}}";

        Optional<LatteX.RenderedMath> rm = LatteX.tryRenderMath(latex);

        assertTrue(rm.isPresent());
        Map<String, String> attrs = rm.get().containerAttrs();
        assertEquals("cancel", attrs.get("data-lx-fx-enter"),
            "the hook rides the seam's attr map");
        String glyphmap = attrs.get("data-lx-glyphmap");
        assertTrue(glyphmap != null && !glyphmap.isEmpty(),
            "cancel through tryRenderMath MUST carry a non-empty data-lx-glyphmap — the hook"
                + " without the sidecar is a silently inert effect; attrs=" + attrs);
        assertTrue(glyphmap.matches("^[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*$"),
            "sidecar matches the contract grammar: " + glyphmap);
        // The payload cancel actually consumes: 0x78 ('x') at exactly two path indices.
        assertTrue(glyphmap.contains("78:0,1"),
            "the two x's form the exactly-twice cancelling pair at path indices 0,1: " + glyphmap);
        // PARITY PIN: the seam's sidecar is byte-identical to the wrapper path's stamp — one
        // gate, one producer, so the two public shapes cannot drift again.
        assertTrue(LatteX.renderStyledHtml(latex).contains("data-lx-glyphmap=\"" + glyphmap + "\""),
            "seam sidecar == renderStyledHtml stamp: " + glyphmap);
    }

    @Test
    void anyFailureYieldsEmptyNeverAHalfPair() {
        assertTrue(LatteX.tryRenderMath("\\frac{a}{").isEmpty(), "parse error -> empty");
        assertTrue(LatteX.tryRenderMath("\\notacommand{x}").isEmpty(), "unknown command -> empty");
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedStringSeamIsTheJoinedAttrMapSoTheFormsCannotDrift() {
        String latex = "\\lx[fx.hover=glow,fx.enter=fade]{y}";
        Optional<LatteX.RenderedMath> rm = LatteX.tryRenderMath(latex);
        assertTrue(rm.isPresent());

        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> e : rm.get().containerAttrs().entrySet()) {
            joined.append(' ').append(e.getKey()).append("=\"").append(e.getValue()).append('"');
        }
        assertEquals(joined.toString(), LatteX.fxContainerAttrs(latex),
            "string seam == joined record map (one producer)");
    }
}
