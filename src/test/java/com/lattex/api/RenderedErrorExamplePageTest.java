package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Generates the reviewed {@code examples/rendered-error.html} specimen. */
@Tag("examples")
public class RenderedErrorExamplePageTest {

    private static final String BROKEN_SOURCE = "\\frac{a + b}{c} + \\fract{x}{y}";

    @Test
    void writesRenderedErrorExampleFromTheCurrentRenderer() throws IOException {
        RenderResult result = renderedFailure();
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, result.diagnostics().outcome());
        assertFalse(result.svg().isBlank());
        assertTrue(result.svg().contains("aria-label=\"LatteX render error\""));

        Path out = ExampleOutputs.dir().resolve("rendered-error.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, buildRenderedErrorHtml(), StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 2_000);
    }

    /** Current-source fixture used independently by the BrewShot browser pin. */
    public static String buildRenderedErrorHtml() {
        String svg = renderedFailure().svg();
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>LatteX rendered error card</title>\n<style>\n"
            + "*{box-sizing:border-box}html,body{min-height:100%}body{margin:0;display:grid;"
            + "place-items:center;background:linear-gradient(145deg,#f7f3ed,#e8eef2);"
            + "color:#272320;font-family:Inter,ui-sans-serif,system-ui,sans-serif}\n"
            + ".capture-stage{width:900px;padding:52px 58px 56px;background:#fffdf9;"
            + "border:1px solid #d9d1c5;border-radius:24px;box-shadow:0 24px 70px "
            + "rgba(64,45,24,.14)}\n"
            + ".eyebrow{margin:0 0 10px;color:#a33a2d;font:700 13px/1.2 "
            + "ui-monospace,monospace;letter-spacing:.12em;text-transform:uppercase}\n"
            + "h1{margin:0;font-size:32px;letter-spacing:-.03em}p{max-width:700px;"
            + "font-size:17px;line-height:1.55;color:#625b53}.formula{margin-top:28px;"
            + "padding:28px;background:#fff;border:1px solid #eadfd4;border-radius:16px}"
            + ".formula svg{display:block;width:100%;height:auto}code{font-family:"
            + "ui-monospace,monospace;color:#7f2f26}\n"
            + "</style></head><body><main class=\"capture-stage\">\n"
            + "<p class=\"eyebrow\">Host opt-in · diagnostics preserved</p>\n"
            + "<h1>A broken formula can fail in place</h1>\n"
            + "<p><code>withRenderedErrors(true)</code> turns a failed diagnostic render "
            + "into bounded, inert SVG geometry. The default remains an empty SVG, and "
            + "the original typed diagnostics remain available to the host.</p>\n"
            + "<div class=\"formula\">" + svg + "</div>\n"
            + "</main></body></html>\n";
    }

    private static RenderResult renderedFailure() {
        return LatteX.renderWithDiagnostics(BROKEN_SOURCE,
            RenderOptions.defaults().withRenderedErrors(true));
    }
}
