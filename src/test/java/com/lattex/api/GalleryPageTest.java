package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the whole pipeline for the S4 constructs (fractions, roots, scripts,
 * spaced rows) and writes browser-openable HTML pages to the tracked
 * {@code examples/} directory — genuine golden output, regenerated on every {@code generateExamples} run.
 * A diff in these files means the emitter's geometry changed and should be
 * reviewed. Mirrors {@link SkeletonPageTest}'s single-page pattern, scaled up to
 * a small gallery so the S4 work is visible from a checkout.
 */
@Tag("examples") // page generator: runs in normal `test` (writes build/examples, all
                 // assertions execute in CI) AND under `generateExamples`, which writes
                 // the tracked examples/ (plan 32148cc8 S2, reviewer F1)
class GalleryPageTest {

    private record Example(String file, String latex, String title, String caption) {
    }

    private static final List<Example> EXAMPLES = List.of(
        new Example("fraction.html", "\\frac{a+b}{c}", "LatteX — fraction",
            "a fraction, numerator/denominator centred on the math axis"),
        new Example("sqrt.html", "\\sqrt{x^2+1}", "LatteX — square root",
            "a radical: surd glyph + over-bar clearing the radicand"),
        new Example("scripts.html", "x_i^2", "LatteX — scripts",
            "simultaneous subscript and superscript"),
        new Example("sum.html", "\\sum_{i=1}^{10} t_i", "LatteX — summation",
            "a big operator: display-size sigma, limits stacked above/below"),
        new Example("integral.html", "\\int_0^\\infty e^{-x}\\,dx", "LatteX — integral",
            "an integral: enlarged sign, limits set beside as scripts"),
        new Example("delimiters.html", "\\left(\\frac{x^2}{y^3}\\right)", "LatteX — delimiters",
            "scaled \\left(..\\right) delimiters hugging the fraction"));

    @Test
    void writesSingleConstructPages() throws IOException {
        for (Example ex : EXAMPLES) {
            String svg = LatteX.render(ex.latex());
            String html = page(ex.title(), card(ex.latex(), svg, ex.caption()));
            Path out = ExampleOutputs.dir().resolve(ex.file());
            Files.createDirectories(out.getParent());
            Files.writeString(out, html);
            assertTrue(Files.size(out) > 0, ex.file() + " non-empty");
            String written = Files.readString(out);
            assertTrue(written.contains("<svg"), ex.file() + " embeds the SVG");
            assertTrue(written.contains("viewBox="), ex.file() + " SVG has a viewBox");
        }
    }

    @Test
    void writesCombinedGallery() throws IOException {
        List<String> latexes = List.of(
            "\\frac{a+b}{c}",
            "x_i^2",
            "\\sqrt{x^2+1}",
            "\\sqrt[n]{x+y}",
            "\\frac{\\frac{1}{x}+\\frac{1}{y}}{y-z}",
            "k_n^2 + 1",
            "a + b = c",
            "\\sum_{i=1}^{10} t_i",
            "\\int_0^\\infty e^{-x}\\,dx",
            "\\left(\\frac{x^2}{y^3}\\right)");

        StringBuilder cards = new StringBuilder();
        for (String latex : latexes) {
            cards.append(card(latex, LatteX.render(latex),
                "rendered from LaTeX · STIX Two Math · clean-room S4 layout"));
            cards.append('\n');
        }
        String html = galleryPage("LatteX — S4 gallery", cards.toString());
        Path out = ExampleOutputs.dir().resolve("gallery.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        String written = Files.readString(out);
        assertTrue(written.contains("<svg"), "gallery embeds SVGs");
        // one SVG per example.
        assertTrue(countOccurrences(written, "<svg") == latexes.size(),
            "one rendered SVG per gallery example");
    }

    // ---- HTML templates (self-contained, single-file, no external assets) ----

    private static String card(String latex, String svg, String caption) {
        return """
                <figure class="card">
                  <span class="src">%s</span>
                  <div class="render">
            %s
                  </div>
                  <figcaption>%s</figcaption>
                </figure>""".formatted(escapeHtml(latex), indent(svg), escapeHtml(caption));
    }

    private static String page(String title, String card) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              %s
            </head>
            <body>
              <main>
                <p class="eyebrow">LatteX &middot; S4 layout</p>
            %s
              </main>
            </body>
            </html>
            """.formatted(title, STYLE, indentBlock(card, "    "));
    }

    private static String galleryPage(String title, String cards) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              %s
            </head>
            <body>
              <main class="gallery">
                <p class="eyebrow">LatteX &middot; S4 layout &middot; fractions, roots, scripts, operators, delimiters</p>
                <div class="grid">
            %s
                </div>
              </main>
            </body>
            </html>
            """.formatted(title, STYLE, indentBlock(cards, "        "));
    }

    private static final String STYLE = """
        <style>
            :root { color-scheme: light; }
            body { font-family: ui-sans-serif, system-ui, sans-serif; margin: 0; min-height: 100vh;
                   display: grid; place-items: center; background: #f4f6f6; color: #1a1f21; }
            main { text-align: center; padding: 3rem 1.5rem; }
            .eyebrow { font-family: ui-monospace, monospace; font-size: .72rem; letter-spacing: .18em;
                       text-transform: uppercase; color: #1f7d72; margin: 0 0 1.5rem; }
            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
                    gap: 1.5rem; max-width: 900px; }
            .card { display: inline-flex; flex-direction: column; align-items: center; gap: 1.25rem;
                    background: #fff; border: 1px solid #e3e8e7; border-radius: 14px;
                    padding: 2rem 2.5rem; box-shadow: 0 6px 24px rgba(0,0,0,.06); }
            .src { font-family: ui-monospace, monospace; font-size: .9rem; color: #3a4749;
                   background: #eef2f1; border: 1px solid #e0e6e4; border-radius: 7px; padding: .4rem .8rem; }
            .render svg { height: 120px; width: auto; display: block; }
            figcaption { font-family: ui-monospace, monospace; font-size: .62rem; letter-spacing: .08em;
                         text-transform: uppercase; color: #8a9896; max-width: 22ch; }
          </style>""";

    private static String indent(String svg) {
        return svg.lines().map(l -> "        " + l).reduce((a, b) -> a + "\n" + b).orElse(svg);
    }

    private static String indentBlock(String block, String pad) {
        return block.lines().map(l -> l.isEmpty() ? l : pad + l)
            .reduce((a, b) -> a + "\n" + b).orElse(block);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
