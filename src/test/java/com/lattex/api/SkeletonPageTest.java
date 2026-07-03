package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Drives the whole pipeline and writes the walking-skeleton HTML page to
 * {@code build/skeleton/x-squared.html} — a static, browser-openable artifact
 * that seeds the future S8 gallery.
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
              <title>LatteX M0 — x squared</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 3rem; color: #111; background: #fafafa; }
                figure { display: inline-block; margin: 0; padding: 1.5rem 2rem; background: #fff;
                         border: 1px solid #ddd; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.06); }
                figcaption { margin-top: 1rem; font-size: .9rem; color: #555; text-align: center; }
                .src { font-family: ui-monospace, monospace; color: #000; }
                h1 { font-size: 1.1rem; font-weight: 600; }
              </style>
            </head>
            <body>
              <h1>LatteX walking skeleton</h1>
              <figure>
            %s
                <figcaption>Rendered from LaTeX <span class="src">x^2</span> &mdash; STIX Two Math, clean-room pipeline</figcaption>
              </figure>
            </body>
            </html>
            """.formatted(indent(svg));

        Path out = Path.of("build", "skeleton", "x-squared.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        assertTrue(Files.exists(out), "HTML page written");
        assertTrue(Files.size(out) > 0, "HTML page non-empty");
        String written = Files.readString(out);
        assertTrue(written.contains("<svg"), "page embeds the SVG");
        assertTrue(written.contains("x^2"), "page shows the source caption");
    }

    private static String indent(String svg) {
        return svg.lines().map(l -> "    " + l).reduce((a, b) -> a + "\n" + b).orElse(svg);
    }
}
