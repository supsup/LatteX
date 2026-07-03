package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Drives the whole pipeline and writes the walking-skeleton HTML page to the
 * tracked {@code examples/x-squared.html} — a static, browser-openable artifact,
 * checked in so it can be opened straight from a checkout. It is the pipeline's
 * genuine output (a golden file): re-running regenerates it, so a diff here means
 * the emitter's output changed and should be reviewed. S8 will graduate this into
 * a proper regenerated gallery with a staleness guard; for M0 it is a single page.
 */
class SkeletonPageTest {

    @Test
    void writesBrowserablePage() throws IOException {
        String svg = LatteX.render("x^2");

        String html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LatteX — x^2</title>
              <style>
                :root { color-scheme: light; }
                body { font-family: ui-sans-serif, system-ui, sans-serif; margin: 0; min-height: 100vh;
                       display: grid; place-items: center; background: #f4f6f6; color: #1a1f21; }
                main { text-align: center; padding: 3rem 1.5rem; }
                .eyebrow { font-family: ui-monospace, monospace; font-size: .72rem; letter-spacing: .18em;
                           text-transform: uppercase; color: #1f7d72; margin: 0 0 1.5rem; }
                .card { display: inline-flex; flex-direction: column; align-items: center; gap: 1.25rem;
                        background: #fff; border: 1px solid #e3e8e7; border-radius: 14px;
                        padding: 2.5rem 3rem; box-shadow: 0 6px 24px rgba(0,0,0,.06); }
                .src { font-family: ui-monospace, monospace; font-size: .95rem; color: #3a4749;
                       background: #eef2f1; border: 1px solid #e0e6e4; border-radius: 7px; padding: .4rem .8rem; }
                .render svg { height: 150px; width: auto; display: block; }
                figcaption { font-family: ui-monospace, monospace; font-size: .68rem; letter-spacing: .1em;
                             text-transform: uppercase; color: #8a9896; }
              </style>
            </head>
            <body>
              <main>
                <p class="eyebrow">LatteX &middot; walking skeleton</p>
                <figure class="card">
                  <span class="src">x^2</span>
                  <div class="render">
            %s
                  </div>
                  <figcaption>rendered from LaTeX x^2 &middot; STIX Two Math &middot; clean-room pipeline</figcaption>
                </figure>
              </main>
            </body>
            </html>
            """.formatted(indent(svg));

        Path out = Path.of("examples", "x-squared.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        assertTrue(Files.exists(out), "HTML page written");
        assertTrue(Files.size(out) > 0, "HTML page non-empty");
        String written = Files.readString(out);
        assertTrue(written.contains("<svg"), "page embeds the SVG");
        assertTrue(written.contains("x^2"), "page shows the source caption");
    }

    private static String indent(String svg) {
        return svg.lines().map(l -> "        " + l).reduce((a, b) -> a + "\n" + b).orElse(svg);
    }
}
