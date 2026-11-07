package com.lattex.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current-source knob specimens; ordinary tests write build/examples only. */
@Tag("examples")
public class FxKnobsPreviewPageTest {
    public static final String LIGHTNING =
        "\\lx[fx.click=lightning, fx.bolts=4, fx.intensity=150]{\\nabla^2\\phi=0}";
    public static final String STORM =
        "\\lx[fx.hover=storm, fx.glow-color=#5caaff, fx.intensity=0]{i\\hbar\\,\\partial_t\\psi}";

    @Test
    void writesKnobPreviewWithTunedLightningAndAnInitiallyDisabledVisibleStorm() throws IOException {
        var lightning = LatteX.tryRenderMath(LIGHTNING).orElseThrow();
        var storm = LatteX.tryRenderMath(STORM).orElseThrow();
        assertEquals("4", lightning.containerAttrs().get("data-lx-fx-bolts"));
        assertEquals("150", lightning.containerAttrs().get("data-lx-fx-intensity"));
        assertEquals("0", storm.containerAttrs().get("data-lx-fx-intensity"));
        assertEquals(LatteX.tryRenderMath(
            "\\lx[fx.click=lightning]{\\nabla^2\\phi=0}").orElseThrow().svg(), lightning.svg(),
            "knobs change the wrapper, never the equation's inner SVG");
        assertEquals(LatteX.tryRenderMath(
            "\\lx[fx.hover=storm, fx.glow-color=#5caaff]{i\\hbar\\,\\partial_t\\psi}")
            .orElseThrow().svg(), storm.svg(), "zero intensity leaves the equation intact");
        String html = buildFxKnobsPreviewHtml();
        assertTrue(html.contains("id=\"enable-storm\""), "the zero-to-enabled example needs its page control");
        Path out = ExampleOutputs.dir().resolve("fx-knobs.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }

    /** Public so the browser fixture comes from current renderer/runtime source. */
    public static String buildFxKnobsPreviewHtml() {
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>LatteX lightning and storm knobs</title>\n<style>\n"
            + LatteX.fxStylesCss() + "\n"
            + """
                body{margin:0;background:#edf0f5;color:#142033;font-family:system-ui,sans-serif;}
                .fx-knobs-stage{max-width:850px;margin:16px auto;padding:16px;}
                h1{font-size:26px;margin:0 0 10px;}h2{font-size:20px;margin:0 0 10px;}p{line-height:1.5;margin:8px 0;}
                .demo-card{margin:16px 0;padding:16px;background:#fff;border:1px solid #cbd5e1;border-radius:16px;}
                .equation{display:grid;place-items:center;min-height:78px;}
                .lx-math svg{height:3.4rem;max-width:700px;}
                pre{margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.5;}
                button{padding:10px 16px;border:0;border-radius:8px;background:#214c91;color:#fff;font:inherit;cursor:pointer;}
                </style></head><body><main class="fx-knobs-stage">
                <h1>Lightning and storm: two numeric knobs</h1>
                <p>Click the first equation for four bolts per source at 150% intensity.
                The second equation begins visible with its storm switched off.</p>
                <section class="demo-card" id="lightning-tuned">
                <h2>Lightning · 4 bolts · 150%</h2><pre>
                """
            + escaped(LIGHTNING) + "</pre><div class=\"equation\">"
            + LatteX.renderStyledHtml(LIGHTNING) + "</div></section>\n"
            + """
                <section class="demo-card" id="storm-zero">
                <h2>Storm · initially 0%</h2><pre>
                """
            + escaped(STORM) + "</pre><div class=\"equation\">"
            + LatteX.renderStyledHtml(STORM) + "</div>\n"
            + """
                <p id="storm-mode" role="status">Effect off; the equation stays visible.</p>
                <button type="button" id="enable-storm">Enable storm at 100%</button>
                <p>The button changes this same wrapper's intensity to 100 and calls
                <code>LatteXFx.play(element)</code>. Changing the attribute alone does not
                install the hover listener that was skipped at zero.</p>
                </section></main><script>
                """
            + LatteX.fxRuntimeJs() + "\n"
            + """
                document.getElementById('enable-storm').addEventListener('click', function () {
                  var el = document.querySelector('#storm-zero .lx-math');
                  el.setAttribute('data-lx-fx-intensity', '100');
                  window.LatteXFx.play(el);
                  document.getElementById('storm-mode').textContent = 'Storm enabled at 100%; click the button to replay.';
                });
                </script></body></html>
                """;
    }

    private static String escaped(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
