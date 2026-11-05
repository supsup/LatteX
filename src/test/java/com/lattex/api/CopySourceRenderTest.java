package com.lattex.api;

import com.lattex.parse.MathSyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopySourceRenderTest {
    private static final RenderOptions OFF = RenderOptions.defaults();
    private static final RenderOptions ON = OFF.withCopySource(true);
    private static final Pattern CARRIER = Pattern.compile(" data-lx-source=\"([A-Za-z0-9_-]*)\"");

    @Test
    void emptyOriginalKeepsRenderLegalityButOmitsTheCopyAffordance() {
        var oldDisplay = LatteX.tryRenderMath("", OFF);
        var newDisplay = LatteX.tryRenderMath("", ON);
        assertEquals(oldDisplay.map(LatteX.RenderedMath::svg), newDisplay.map(LatteX.RenderedMath::svg));
        newDisplay.ifPresent(result -> assertFalse(result.containerAttrs().containsKey("data-lx-source")));
        var oldInline = LatteX.tryRenderInlineMath("", OFF);
        var newInline = LatteX.tryRenderInlineMath("", ON);
        assertEquals(oldInline.map(LatteX.RenderedInlineMath::result), newInline.map(LatteX.RenderedInlineMath::result));
        newInline.ifPresent(result -> assertFalse(result.containerAttrs().containsKey("data-lx-source")));
        if (oldDisplay.isPresent()) {
            assertEquals(LatteX.renderStyledHtml("", OFF), LatteX.renderStyledHtml("", ON));
        }
    }

    @Test
    void sourceIsAbsentByDefaultAndNeverStampedInsideSvg() {
        for (String source : List.of("x+y", "\\lx[fx.click=pulse]{x+y}")) {
            assertFalse(LatteX.renderStyledHtml(source).contains("data-lx-source"));
            assertEquals(LatteX.renderStyledHtml(source, OFF),
                LatteX.renderStyledHtml(source, ON.withCopySource(false)));
            assertFalse(LatteX.tryRenderMath(source).orElseThrow().containerAttrs().containsKey("data-lx-source"));
            assertEquals(LatteX.render(source, OFF), LatteX.render(source, ON));
            assertFalse(LatteX.render(source, ON).contains("data-lx-source"));
        }
    }

    @Test
    void styledAndCoupledDisplayPreserveTheExactOriginalInput() {
        for (String source : List.of("x\r\n+y", "x\r+y", "x\n+y", " \tx\r\n+y \r\n",
                "\\lx[style.scale=lg,fx.hover=pulse]{x+y}")) {
            String html = LatteX.renderStyledHtml(source, ON);
            var match = CARRIER.matcher(html);
            assertTrue(match.find());
            assertEquals(source, decode(match.group(1)));
            assertFalse(match.find(), "exactly one host carrier");
            var coupled = LatteX.tryRenderMath(source, ON).orElseThrow();
            assertEquals(source, decode(coupled.containerAttrs().get("data-lx-source")));
            assertEquals(LatteX.render(source, OFF), coupled.svg());
            assertEquals(LatteX.renderStyledHtml(source, OFF), CARRIER.matcher(html).replaceFirst(""));
        }
    }

    @Test
    void sourcePrecedesMacroExpansionAndIncludesTheAuthorWrapper() {
        RenderOptions options = ON.withMacros(Map.of("half", "\\frac{1}{2}"));
        for (String source : List.of("\\half+x", "\\lx[style.color=#123456]{\\half+x}")) {
            assertEquals(source, decode(LatteX.tryRenderMath(source, options).orElseThrow()
                .containerAttrs().get("data-lx-source")));
            assertEquals(source, decode(LatteX.tryRenderInlineMath(source, options).orElseThrow()
                .containerAttrs().get("data-lx-source")));
            var match = CARRIER.matcher(LatteX.renderStyledHtml(source, options));
            assertTrue(match.find());
            assertEquals(source, decode(match.group(1)));
        }
    }

    @Test
    void inlineUsesItsOriginalSvgAndBaselineAndNeverUsesFluidSizing() {
        for (String source : List.of("y_i", "\\frac{x^2}{y}", "\\lx[fx.hover=thread]{x+x}")) {
            InlineSvgResult legacy = LatteX.renderInlineResult(source);
            var coupled = LatteX.tryRenderInlineMath(source, ON.withFluid(true)).orElseThrow();
            assertEquals(legacy, coupled.result());
            assertEquals(source, decode(coupled.containerAttrs().get("data-lx-source")));
            assertFalse(coupled.result().svg().contains("style="));
            Map<String, String> offAttrs = LatteX.tryRenderInlineMath(source, OFF).orElseThrow().containerAttrs();
            var withoutSource = new java.util.LinkedHashMap<>(coupled.containerAttrs());
            withoutSource.remove("data-lx-source");
            assertEquals(offAttrs, withoutSource);
        }
    }

    @Test
    void inlineSidecarsAndSourceAreImmutableAndRenderCoupled() {
        String source = "\\lx[fx.hover=thread]{x+x}";
        var result = LatteX.tryRenderInlineMath(source, ON).orElseThrow();
        assertTrue(result.containerAttrs().containsKey("data-lx-glyphmap"));
        assertThrows(UnsupportedOperationException.class, () -> result.containerAttrs().put("data-lx-source", "bad"));
        var input = new java.util.LinkedHashMap<>(result.containerAttrs());
        var copied = new LatteX.RenderedInlineMath(result.result(), input);
        input.clear();
        assertEquals(result.containerAttrs(), copied.containerAttrs());
    }

    @Test
    void omittedPayloadDoesNotChangeRenderingLegalityOrFallback() {
        String overCap = "x" + " ".repeat(4096);
        assertEquals(LatteX.renderStyledHtml(overCap, OFF), LatteX.renderStyledHtml(overCap, ON));
        for (String source : List.of(overCap, "x\u0000", "x\uD800")) {
            var oldDisplay = LatteX.tryRenderMath(source, OFF);
            var newDisplay = LatteX.tryRenderMath(source, ON);
            assertEquals(oldDisplay.map(LatteX.RenderedMath::svg), newDisplay.map(LatteX.RenderedMath::svg));
            newDisplay.ifPresent(result -> assertFalse(result.containerAttrs().containsKey("data-lx-source")));
            var oldInline = LatteX.tryRenderInlineMath(source, OFF);
            var newInline = LatteX.tryRenderInlineMath(source, ON);
            assertEquals(oldInline.map(LatteX.RenderedInlineMath::result), newInline.map(LatteX.RenderedInlineMath::result));
            newInline.ifPresent(result -> assertFalse(result.containerAttrs().containsKey("data-lx-source")));
        }
    }

    @Test
    void sourceCannotBeForgedThroughTheAuthorDataNamespace() {
        String forged = "\\lx[data.source=calc_12]{x^2}";
        assertThrows(MathSyntaxException.class, () -> LatteX.renderStyledHtml(forged, OFF));
        assertThrows(MathSyntaxException.class, () -> LatteX.renderStyledHtml(forged, ON));
        assertTrue(LatteX.tryRenderInlineMath(forged, ON).isEmpty());
        assertTrue(LatteX.tryRenderMath(forged, ON).isEmpty());
        assertFalse(LatteX.renderStyledHtml("\\lx[data.provenance=calc_12]{x^2}", OFF).isEmpty());
    }

    @Test
    void inlineFailureAndNullOptionsKeepTheExistingSeamPolicy() {
        assertTrue(LatteX.tryRenderInlineMath("\\frac{", ON).isEmpty());
        assertThrows(NullPointerException.class, () -> LatteX.tryRenderInlineMath("x", null));
    }

    private static String decode(String carrier) {
        return new String(Base64.getUrlDecoder().decode(carrier), StandardCharsets.UTF_8);
    }
}
