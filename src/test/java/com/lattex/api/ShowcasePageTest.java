package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/showcase.html} — the LatteX landing page. A single,
 * self-contained, browser-openable page whose every piece of math is rendered
 * live through {@link LatteX#render(String)}, so the showcase can never drift
 * from the actual emitter: it is regenerated (and asserted) on every build,
 * exactly like {@link S8SpecimenGalleryTest} and {@link GalleryPageTest}.
 *
 * <p>Design direction — "Ink &amp; Amber": a cool deep-slate neutral with a
 * slight blue bias and a single warm amber accent (a nod to the "Latte" in the
 * name without lapsing into cream). Editorial type hierarchy; the rendered math
 * is the star and is given room. Theme-aware (light + dark via
 * {@code prefers-color-scheme}); the {@code #000000} glyph paths are inverted in
 * dark mode via a wrapper filter.
 *
 * <p>No parser/layout/emitter code is touched — this is pure presentation over
 * the public render API. All SVGs are self-contained (the emitter's minimal
 * {@code svg/g/path/rect} alphabet), so the page embeds no external assets.
 */
class ShowcasePageTest {

    /** One rendered equation: LaTeX source + a human label/caption. */
    private record Eq(String latex, String label) {
    }

    // --- Hero: one large, gorgeous equation (the Gaussian integral identity). ---
    private static final String HERO_EQ = "\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}";

    // --- Section 2: ~8 hero examples spanning the renderer's range. ---
    private static final List<Eq> HERO_GRID = List.of(
        new Eq("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", "The quadratic formula"),
        new Eq("\\int_0^\\infty e^{-x}\\,dx = 1", "A definite integral with limits"),
        new Eq("\\frac{1}{1 + \\frac{1}{1 + \\frac{1}{x}}}", "A continued fraction"),
        new Eq("\\sqrt[3]{x^2 + y^2}", "An indexed radical"),
        new Eq("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", "A summation identity"),
        new Eq("\\frac{\\frac{a}{b}}{\\frac{c}{d}} = \\frac{ad}{bc}", "A fraction tower"),
        new Eq("e^{i\\pi} + 1 = 0", "Euler's identity"),
        new Eq("\\left( \\sum_{k=0}^{n} \\frac{x^k}{k} \\right)", "Auto-scaled delimiters"));

    /** A named strip of single-symbol specimens (macro name below each glyph). */
    private record Strip(String title, List<String> macros) {
    }

    // --- Section 3: a dense sampling of the 250+ symbol table. ---
    private static final List<Strip> STRIPS = List.of(
        new Strip("Relations", List.of(
            "leq", "geq", "neq", "approx", "equiv", "cong", "sim", "propto",
            "subset", "supset", "subseteq", "supseteq", "in", "ni", "prec", "succ")),
        new Strip("Operators", List.of(
            "times", "cdot", "pm", "mp", "div", "ast", "star", "oplus",
            "otimes", "ominus", "odot", "cap", "cup", "wedge", "vee", "setminus")),
        new Strip("Arrows", List.of(
            "to", "gets", "leftrightarrow", "Rightarrow", "Leftarrow", "Leftrightarrow",
            "mapsto", "hookrightarrow", "longrightarrow", "uparrow", "downarrow",
            "nearrow", "searrow", "rightsquigarrow", "twoheadrightarrow", "rightleftharpoons")),
        new Strip("Greek — lowercase", List.of(
            "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
            "lambda", "mu", "nu", "xi", "pi", "rho", "sigma", "tau", "phi", "chi",
            "psi", "omega")),
        new Strip("Greek — uppercase", List.of(
            "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Phi", "Psi", "Omega")),
        new Strip("Symbols", List.of(
            "infty", "partial", "nabla", "forall", "exists", "emptyset", "aleph",
            "hbar", "Re", "Im", "ell", "top")));

    @Test
    void writesShowcasePage() throws IOException {
        int svgCount = 0;

        // Hero.
        String heroSvg = LatteX.render(HERO_EQ);
        svgCount++;

        // Hero grid.
        StringBuilder gridCells = new StringBuilder();
        for (Eq eq : HERO_GRID) {
            String svg = LatteX.render(eq.latex());
            gridCells.append(gridCell(eq.label(), eq.latex(), svg)).append('\n');
            svgCount++;
        }

        // Symbol strips.
        StringBuilder strips = new StringBuilder();
        for (Strip strip : STRIPS) {
            StringBuilder tiles = new StringBuilder();
            for (String macro : strip.macros()) {
                String svg = LatteX.render("\\" + macro);
                tiles.append(symbolTile(macro, svg)).append('\n');
                svgCount++;
            }
            strips.append(strip(strip.title(), tiles.toString()));
        }

        String html = page(heroSvg, gridCells.toString(), strips.toString());
        Path out = Path.of("examples", "showcase.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        // Golden-output assertions: the page really embeds every live SVG.
        String written = Files.readString(out);
        assertTrue(Files.size(out) > 0, "showcase.html non-empty");
        assertTrue(written.contains("<svg"), "showcase embeds SVGs");
        assertEquals(svgCount, countOccurrences(written, "<svg"),
            "one rendered SVG per hero + grid + symbol specimen");
        assertTrue(written.contains("gallery-specimen.html"),
            "links to the full specimen gallery");
        assertTrue(svgCount >= 60,
            "a broad showcase (hero + 8 examples + a dense symbol strip), got " + svgCount);
    }

    // -------------------------------------------------------------------------
    // HTML fragments (self-contained, single file, no external assets).
    // -------------------------------------------------------------------------

    private static String gridCell(String label, String latex, String svg) {
        return """
                <figure class="card">
                  <div class="render">
            %s
                  </div>
                  <figcaption>
                    <span class="cap">%s</span>
                    <code class="src">%s</code>
                  </figcaption>
                </figure>""".formatted(indent(svg, "        "), escapeHtml(label), escapeHtml(latex));
    }

    private static String symbolTile(String macro, String svg) {
        return """
                <figure class="glyph" title="\\%s">
                  <div class="render">
            %s
                  </div>
                  <code>\\%s</code>
                </figure>""".formatted(escapeHtml(macro), indent(svg, "        "), escapeHtml(macro));
    }

    private static String strip(String title, String tiles) {
        return """
            <section class="strip">
              <h3>%s</h3>
              <div class="glyphs">
            %s
              </div>
            </section>
            """.formatted(escapeHtml(title), indentBlock(tiles, "    "));
    }

    private static String page(String heroSvg, String gridCells, String strips) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LatteX — clean-room LaTeX to SVG math</title>
              %s
            </head>
            <body>
              <main>

                <section class="hero">
                  <p class="eyebrow">clean-room · pure Java 25 · zero dependencies</p>
                  <h1 class="wordmark">Latte<span class="x">X</span></h1>
                  <p class="tagline">Clean-room LaTeX&nbsp;&rarr;&nbsp;SVG math, sanitizer-safe by construction.</p>
                  <div class="hero-eq">
                    <div class="render">
            %s
                    </div>
                    <code class="hero-src">%s</code>
                  </div>
                </section>

                <section class="band">
                  <header class="band-head">
                    <h2>It renders real math</h2>
                    <p>Every glyph here was drawn live by the pipeline — font&rarr;parse&rarr;layout&rarr;SVG —
                       for this page. Nothing is a screenshot.</p>
                  </header>
                  <div class="grid">
            %s
                  </div>
                </section>

                <section class="band">
                  <header class="band-head">
                    <h2>250&plus; symbols</h2>
                    <p>Relations, operators, arrows, Greek, and more — a taste of the table below.
                       See the full <a href="gallery-specimen.html">specimen gallery</a>.</p>
                  </header>
            %s
                </section>

                <section class="band">
                  <header class="band-head">
                    <h2>Why it's different</h2>
                  </header>
                  <div class="cards">
                    <article class="feature">
                      <h3>Sanitizer-safe by construction</h3>
                      <p>Output is a minimal alphabet — only <code>svg</code>, <code>g</code>,
                         <code>path</code>, and <code>rect</code>. No scripts, no <code>text</code>,
                         no external refs, no <code>data:</code> URIs. A build-time containment
                         test keeps it honest.</p>
                    </article>
                    <article class="feature">
                      <h3>Zero dependencies · pure Java 25</h3>
                      <p>No TeX install, no headless browser, no native math library. Just the JDK
                         and a bundled OFL math font parsed in-process. Deterministic, offline,
                         reproducible.</p>
                    </article>
                    <article class="feature">
                      <h3>Native CLI</h3>
                      <p>A GraalVM-built <code>lattex</code> binary takes LaTeX on argv or stdin and
                         writes SVG to stdout — shell out from any language, or fall back to the JVM.</p>
                    </article>
                    <article class="feature">
                      <h3>Semantic <code>\\lx</code> layer <span class="soon">coming</span></h3>
                      <p>A forthcoming semantic markup layer and interactive affordances build on the
                         same safe substrate — richer meaning, still zero new attack surface.</p>
                    </article>
                  </div>
                </section>

                <footer class="foot">
                  <p class="foot-mark">Latte<span class="x">X</span></p>
                  <nav>
                    <a href="../QUICKSTART.md">Quickstart</a>
                    <a href="../RELEASE_NOTES.md">Release notes</a>
                    <a href="../README.md">Readme</a>
                    <a href="gallery-specimen.html">Specimen gallery</a>
                  </nav>
                  <p class="foot-fine">Rendered live from LaTeX · STIX Two Math (OFL) · clean-room pipeline</p>
                </footer>

              </main>
            </body>
            </html>
            """.formatted(STYLE, indent(heroSvg, "        "), escapeHtml(HERO_EQ),
                indentBlock(gridCells, "    "), indentBlock(strips, "    "));
    }

    // -------------------------------------------------------------------------
    // Style. Kept as a raw constant (never run through String.formatted) so its
    // many literal '%' need no doubling.
    // -------------------------------------------------------------------------
    private static final String STYLE = """
        <style>
          :root {
            color-scheme: light dark;
            --bg:      #eceef1;
            --bg-2:    #e3e6ea;
            --panel:   #ffffff;
            --ink:     #171a1f;
            --muted:   #5b6570;
            --faint:   #8a939d;
            --line:    #d8dce1;
            --accent:  #b9761b;
            --accent-2:#d68a2a;
            --chip:    #f1f3f5;
            --chip-line:#e0e4e8;
            --glyph-invert: 0;
            --shadow: 0 1px 2px rgba(20,24,30,.04), 0 8px 30px rgba(20,24,30,.06);
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg:      #0e1116;
              --bg-2:    #0a0d11;
              --panel:   #161a20;
              --ink:     #e9ecf1;
              --muted:   #97a1ad;
              --faint:   #6b7480;
              --line:    #262c34;
              --accent:  #e6a24c;
              --accent-2:#f0b96b;
              --chip:    #1b2027;
              --chip-line:#2a313a;
              --glyph-invert: 1;
              --shadow: 0 1px 2px rgba(0,0,0,.3), 0 12px 34px rgba(0,0,0,.45);
            }
          }
          :root[data-theme="light"] {
            color-scheme: light;
            --bg:#eceef1; --bg-2:#e3e6ea; --panel:#ffffff; --ink:#171a1f;
            --muted:#5b6570; --faint:#8a939d; --line:#d8dce1;
            --accent:#b9761b; --accent-2:#d68a2a; --chip:#f1f3f5; --chip-line:#e0e4e8;
            --glyph-invert:0;
            --shadow: 0 1px 2px rgba(20,24,30,.04), 0 8px 30px rgba(20,24,30,.06);
          }
          :root[data-theme="dark"] {
            color-scheme: dark;
            --bg:#0e1116; --bg-2:#0a0d11; --panel:#161a20; --ink:#e9ecf1;
            --muted:#97a1ad; --faint:#6b7480; --line:#262c34;
            --accent:#e6a24c; --accent-2:#f0b96b; --chip:#1b2027; --chip-line:#2a313a;
            --glyph-invert:1;
            --shadow: 0 1px 2px rgba(0,0,0,.3), 0 12px 34px rgba(0,0,0,.45);
          }

          * { box-sizing: border-box; }
          html { -webkit-text-size-adjust: 100%; }
          body {
            margin: 0;
            background:
              radial-gradient(1200px 620px at 50% -8%, var(--bg) 0%, var(--bg-2) 62%, var(--bg-2) 100%);
            color: var(--ink);
            font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
            line-height: 1.55;
            -webkit-font-smoothing: antialiased;
          }
          main { max-width: 1120px; margin: 0 auto; padding: 0 1.5rem 6rem; overflow-x: hidden; }
          code { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
          a { color: var(--accent); text-decoration: none; }
          a:hover { text-decoration: underline; }

          /* All rendered glyph SVGs: fit their cell; invert #000 paths in dark. */
          .render { display: flex; align-items: center; justify-content: center; width: 100%; }
          .render svg {
            display: block; width: auto; height: auto; max-width: 100%;
            filter: invert(var(--glyph-invert));
          }

          /* ---- Hero ---- */
          .hero { text-align: center; padding: 5.5rem 0 3.5rem; }
          .eyebrow {
            font-family: ui-monospace, monospace; font-size: .72rem; letter-spacing: .24em;
            text-transform: uppercase; color: var(--accent); margin: 0 0 1.4rem;
          }
          .wordmark {
            font-size: clamp(3.4rem, 12vw, 6.5rem); font-weight: 800; letter-spacing: -.045em;
            line-height: .95; margin: 0; color: var(--ink);
          }
          .wordmark .x {
            background: linear-gradient(160deg, var(--accent-2), var(--accent));
            -webkit-background-clip: text; background-clip: text; color: transparent;
          }
          .tagline {
            max-width: 40ch; margin: 1.1rem auto 0; color: var(--muted);
            font-size: clamp(1rem, 2.1vw, 1.2rem);
          }
          .hero-eq {
            margin: 3rem auto 0; max-width: 760px;
            background: var(--panel); border: 1px solid var(--line); border-radius: 18px;
            box-shadow: var(--shadow); padding: 2.75rem 2rem 1.75rem;
            display: flex; flex-direction: column; align-items: center; gap: 1.5rem;
          }
          .hero-eq .render { overflow-x: auto; }
          .hero-eq .render svg { height: clamp(58px, 11vw, 104px); }
          .hero-src {
            font-size: .78rem; color: var(--faint); background: var(--chip);
            border: 1px solid var(--chip-line); border-radius: 7px; padding: .35rem .7rem;
            max-width: 100%; overflow-x: auto; white-space: nowrap;
          }

          /* ---- Bands / section headers ---- */
          .band { margin-top: 4.5rem; }
          .band-head { max-width: 60ch; margin: 0 0 1.8rem; }
          .band-head h2 { font-size: 1.55rem; letter-spacing: -.02em; margin: 0 0 .5rem; }
          .band-head p { margin: 0; color: var(--muted); font-size: 1rem; }

          /* ---- Hero grid (8 examples) ---- */
          .grid {
            display: grid; gap: 1rem;
            grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
          }
          .card {
            margin: 0; display: flex; flex-direction: column; gap: 1.25rem;
            background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
            padding: 1.75rem 1.25rem 1.1rem; box-shadow: var(--shadow);
            transition: border-color .16s ease, transform .16s ease;
          }
          .card:hover { border-color: var(--accent); transform: translateY(-3px); }
          .card .render { flex: 1; min-height: 92px; overflow-x: auto; }
          .card .render svg { max-height: 92px; }
          .card figcaption { display: flex; flex-direction: column; gap: .55rem; align-items: flex-start; }
          .card .cap { font-size: .9rem; font-weight: 600; color: var(--ink); }
          .card .src {
            font-size: .72rem; color: var(--muted); background: var(--chip);
            border: 1px solid var(--chip-line); border-radius: 6px; padding: .28rem .5rem;
            max-width: 100%; overflow-x: auto; white-space: nowrap;
          }

          /* ---- Symbol strips ---- */
          .strip { margin-top: 2rem; }
          .strip h3 {
            font-family: ui-monospace, monospace; font-size: .72rem; letter-spacing: .16em;
            text-transform: uppercase; color: var(--faint); margin: 0 0 .9rem;
            padding-bottom: .5rem; border-bottom: 1px solid var(--line);
          }
          .glyphs {
            display: grid; gap: .5rem;
            grid-template-columns: repeat(auto-fill, minmax(78px, 1fr));
          }
          .glyph {
            margin: 0; display: flex; flex-direction: column; align-items: center; gap: .5rem;
            background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
            padding: .85rem .4rem .5rem;
            transition: border-color .14s ease, background .14s ease;
          }
          .glyph:hover { border-color: var(--accent); }
          .glyph .render { height: 34px; }
          .glyph .render svg { max-height: 34px; }
          .glyph code { font-size: .64rem; color: var(--faint); white-space: nowrap; }

          /* ---- Feature cards ---- */
          .cards {
            display: grid; gap: 1rem;
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
          }
          .feature {
            background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
            padding: 1.5rem 1.4rem; box-shadow: var(--shadow);
          }
          .feature h3 { margin: 0 0 .6rem; font-size: 1.02rem; letter-spacing: -.01em; }
          .feature p { margin: 0; color: var(--muted); font-size: .93rem; }
          .feature code {
            font-size: .86em; color: var(--ink);
            background: var(--chip); border: 1px solid var(--chip-line);
            border-radius: 5px; padding: .04em .3em;
          }
          .soon {
            font-family: ui-monospace, monospace; font-size: .58rem; letter-spacing: .12em;
            text-transform: uppercase; color: var(--accent); border: 1px solid var(--accent);
            border-radius: 999px; padding: .1em .55em; margin-left: .35em; vertical-align: middle;
          }

          /* ---- Footer ---- */
          .foot {
            margin-top: 5rem; padding-top: 2.5rem; border-top: 1px solid var(--line);
            text-align: center;
          }
          .foot-mark { font-weight: 800; font-size: 1.35rem; letter-spacing: -.03em; margin: 0 0 1rem; }
          .foot-mark .x {
            background: linear-gradient(160deg, var(--accent-2), var(--accent));
            -webkit-background-clip: text; background-clip: text; color: transparent;
          }
          .foot nav { display: flex; flex-wrap: wrap; gap: 1.5rem; justify-content: center; }
          .foot nav a { font-size: .92rem; }
          .foot-fine {
            margin: 1.6rem 0 0; color: var(--faint); font-size: .74rem;
            font-family: ui-monospace, monospace; letter-spacing: .04em;
          }

          /* ---- Motion: a single subtle hero reveal ---- */
          @media (prefers-reduced-motion: no-preference) {
            .hero > * { animation: rise .7s cubic-bezier(.2,.7,.2,1) both; }
            .hero .wordmark  { animation-delay: .04s; }
            .hero .tagline   { animation-delay: .10s; }
            .hero .hero-eq   { animation-delay: .16s; }
            @keyframes rise {
              from { opacity: 0; transform: translateY(14px); }
              to   { opacity: 1; transform: translateY(0); }
            }
          }
        </style>""";

    // -------------------------------------------------------------------------
    // Small helpers (mirrors S8SpecimenGalleryTest).
    // -------------------------------------------------------------------------

    private static String indent(String block, String pad) {
        return block.lines().map(l -> pad + l)
            .reduce((a, b) -> a + "\n" + b).orElse(block);
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
