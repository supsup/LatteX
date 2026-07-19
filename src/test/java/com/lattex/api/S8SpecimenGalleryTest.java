package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/gallery-specimen.html} — a categorised specimen
 * grid of every SUPPORTED construct + symbol the renderer can draw today, each
 * cell showing the LaTeX source above its rendered SVG. A one-glance "what
 * LatteX can render" page, regenerated from live output by the
 * {@code generateExamples} task so it cannot drift from the emitter.
 *
 * <p>The specimen list is <em>curated</em> from what {@link com.lattex.parse.MathParser}
 * accepts (there is no programmatic supported-symbol API yet). The harness is a
 * simple category → specimen table: adding a symbol later is one line here.
 */
@Tag("examples") // page generator: runs in normal `test` (writes build/examples, all
                 // assertions execute in CI) AND under `generateExamples`, which writes
                 // the tracked examples/ (plan 32148cc8 S2, reviewer F1)
class S8SpecimenGalleryTest {

    /** One rendered cell: LaTeX source + a human label. */
    private record Specimen(String latex, String label) {
    }

    /** A titled group of specimens. */
    private record Category(String title, String blurb, List<Specimen> specimens) {
    }

    private static final List<Category> CATEGORIES = List.of(
        new Category("Fractions", "\\frac — nested to any depth", List.of(
            new Specimen("\\frac{a+b}{c}", "\\frac{a+b}{c}"),
            new Specimen("\\frac{1}{1+\\frac{1}{x}}", "continued fraction"),
            new Specimen("\\frac{\\partial f}{\\partial x}", "partial derivative"))),

        new Category("Roots", "\\sqrt — square and indexed", List.of(
            new Specimen("\\sqrt{x^2+1}", "square root"),
            new Specimen("\\sqrt[n]{x+y}", "n-th root"),
            new Specimen("\\sqrt[3]{\\frac{a}{b}}", "cube root of a fraction"))),

        new Category("Scripts", "^ superscript · _ subscript", List.of(
            new Specimen("x^2", "superscript"),
            new Specimen("x_i", "subscript"),
            new Specimen("x_i^2", "both at once"),
            new Specimen("e^{-x}", "grouped exponent"))),

        new Category("Big operators", "\\sum \\int \\prod — with limits", List.of(
            new Specimen("\\sum_{i=1}^{n} a_i", "summation"),
            new Specimen("\\int_0^\\infty e^{-x}\\,dx", "integral"),
            new Specimen("\\prod_{k=1}^{n} k", "product"),
            new Specimen("\\int\\nolimits_a^b f", "\\nolimits"))),

        new Category("Delimiters", "\\left … \\right — auto-scaled", List.of(
            new Specimen("\\left(\\frac{x^2}{y^3}\\right)", "parentheses"),
            new Specimen("\\left[ a+b \\right]", "brackets"),
            new Specimen("\\left\\{ x \\right\\}", "braces"),
            new Specimen("\\left\\langle x \\right\\rangle", "angle brackets"),
            new Specimen("\\left\\lfloor x \\right\\rfloor", "floor"),
            new Specimen("\\left\\lceil x \\right\\rceil", "ceiling"),
            new Specimen("\\left| x \\right|", "vertical bars"))),

        new Category("Greek — lowercase", "the LaTeX lowercase set", greek(List.of(
            "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
            "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "rho", "sigma",
            "tau", "upsilon", "phi", "chi", "psi", "omega"))),

        new Category("Greek — uppercase", "the LaTeX uppercase set", greek(List.of(
            "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Phi",
            "Psi", "Omega"))),

        new Category("Binary operators", "class BIN", List.of(
            new Specimen("a \\times b", "\\times"),
            new Specimen("a \\cdot b", "\\cdot"),
            new Specimen("a \\pm b", "\\pm"),
            new Specimen("a + b", "+"),
            new Specimen("a - b", "-"))),

        new Category("Relations", "class REL", List.of(
            new Specimen("a \\leq b", "\\leq"),
            new Specimen("a \\geq b", "\\geq"),
            new Specimen("a \\neq b", "\\neq"),
            new Specimen("a \\approx b", "\\approx"),
            new Specimen("a \\equiv b", "\\equiv"),
            new Specimen("a \\to b", "\\to"),
            new Specimen("x \\in A", "\\in"),
            new Specimen("A \\subset B", "\\subset"))),

        new Category("Symbols & dots", "ORD / INNER", List.of(
            new Specimen("\\infty", "\\infty"),
            new Specimen("\\partial", "\\partial"),
            new Specimen("\\nabla", "\\nabla"),
            new Specimen("1 + \\cdots + n", "\\cdots"),
            new Specimen("a_1, \\ldots, a_n", "\\ldots"))),

        new Category("Accents", "\\hat \\vec \\bar \\dot — glyph accents over a base", List.of(
            new Specimen("\\hat{a}", "\\hat{a}"),
            new Specimen("\\vec{v}", "\\vec{v}"),
            new Specimen("\\bar{z}", "\\bar{z}"),
            new Specimen("\\tilde{n}", "\\tilde{n}"),
            new Specimen("\\dot{x}", "\\dot{x}"),
            new Specimen("\\ddot{x}", "\\ddot{x}"),
            new Specimen("\\check{s}", "\\check{s}"),
            new Specimen("\\acute{e}", "\\acute{e}"))),

        new Category("Wide accents & bars", "stretchy accents sized to their base", List.of(
            new Specimen("\\widehat{abc}", "\\widehat"),
            new Specimen("\\widetilde{xyz}", "\\widetilde"),
            new Specimen("\\overline{x+y}", "\\overline"),
            new Specimen("\\underline{a+b}", "\\underline"),
            new Specimen("\\overrightarrow{AB}", "\\overrightarrow"),
            new Specimen("\\vec{F} = m\\vec{a}", "accents in an expression"))),

        new Category("Named operators", "\\sin \\lim \\det — upright, limit-aware", List.of(
            new Specimen("\\sin x", "\\sin"),
            new Specimen("\\cos^2\\theta + \\sin^2\\theta", "beside-scripts"),
            new Specimen("\\lim_{x\\to\\infty}", "\\lim (under-limit)"),
            new Specimen("\\det(A)", "\\det"),
            new Specimen("\\log_2 n", "\\log with base"),
            new Specimen("\\exp(-x)", "\\exp"),
            new Specimen("\\gcd(a,b)", "\\gcd"),
            new Specimen("\\operatorname{lcm}(a,b)", "\\operatorname"))),

        new Category("Text mode", "\\text \\textbf \\mathrm — upright words in math", List.of(
            new Specimen("\\text{if } n \\text{ even}", "\\text (spaces kept)"),
            new Specimen("\\textbf{bold}", "\\textbf"),
            new Specimen("\\textit{italic}", "\\textit"),
            new Specimen("\\mathrm{d}x", "\\mathrm (roman d)"),
            new Specimen("v_{\\text{max}}", "text in a subscript"))),

        new Category("Font variants", "\\mathbb \\mathcal \\mathfrak — math alphabets", List.of(
            new Specimen("\\mathbb{RCQZ}", "blackboard bold"),
            new Specimen("\\mathcal{LFH}", "calligraphic"),
            new Specimen("\\mathfrak{gAB}", "fraktur"),
            new Specimen("\\mathbf{xyz}", "bold"),
            new Specimen("\\mathsf{ABC}", "sans-serif"),
            new Specimen("\\mathit{abc}", "math italic"),
            new Specimen("\\mathtt{code}", "monospace"),
            new Specimen("\\boldsymbol{\\beta}", "bold Greek"))),

        new Category("Arrows", "class REL — the arrow family", macros(List.of(
            "to", "gets", "leftrightarrow", "Rightarrow", "Leftarrow",
            "Leftrightarrow", "mapsto", "hookrightarrow", "longrightarrow",
            "uparrow", "downarrow", "nearrow", "searrow", "rightsquigarrow",
            "twoheadrightarrow", "rightleftharpoons"))),

        new Category("More relations", "class REL — order, similarity, sets", macros(List.of(
            "cong", "sim", "simeq", "propto", "asymp", "prec", "succ",
            "preceq", "succeq", "ll", "gg", "supset", "subseteq", "supseteq",
            "sqsubseteq", "sqsupseteq", "models", "vdash", "perp", "parallel",
            "ni", "notin"))),

        new Category("More operators", "class BIN — circled, lattice, dagger", macros(List.of(
            "mp", "div", "ast", "star", "circ", "bullet", "oplus", "ominus",
            "otimes", "oslash", "odot", "cap", "cup", "uplus", "sqcap", "sqcup",
            "wedge", "vee", "dagger", "ddagger", "wr", "amalg", "setminus"))),

        new Category("More symbols", "ORD — logic, blackboard, misc", macros(List.of(
            "forall", "exists", "nexists", "neg", "emptyset", "varnothing",
            "aleph", "hbar", "ell", "wp", "Re", "Im", "top", "bot", "angle",
            "triangle", "flat", "sharp", "natural", "clubsuit", "diamondsuit",
            "heartsuit", "spadesuit"))),

        new Category("Everything together", "dense display expressions", List.of(
            new Specimen(
                "\\sum_{i=1}^{n} \\frac{\\sqrt{\\alpha_i^2 + \\beta_i^2}}{\\gamma}"
                    + " \\leq \\left( \\int_0^\\infty e^{-x}\\,dx \\right)",
                "sum of scaled roots bounded by an integral"),
            new Specimen(
                "\\lim_{n\\to\\infty} \\left( 1 + \\frac{x}{n} \\right)^n"
                    + " = \\exp(x), \\quad x \\in \\mathbb{R}",
                "a limit definition of exp over the reals"),
            new Specimen(
                "\\hat{\\beta} = \\operatorname{argmin}_{\\beta}"
                    + " \\sum_i \\left( y_i - \\vec{x}_i^\\top \\beta \\right)^2",
                "least-squares estimator with accents & \\operatorname"))));

    private static List<Specimen> greek(List<String> names) {
        return names.stream().map(n -> new Specimen("\\" + n, "\\" + n)).toList();
    }

    /** Turns a macro-name list into one specimen per macro (label = the macro). */
    private static List<Specimen> macros(List<String> names) {
        return greek(names);
    }

    @Test
    void writesSpecimenGallery() throws IOException {
        int specimenCount = 0;
        StringBuilder sections = new StringBuilder();
        for (Category cat : CATEGORIES) {
            StringBuilder cells = new StringBuilder();
            for (Specimen s : cat.specimens()) {
                String svg = LatteX.render(s.latex());
                cells.append(cell(s.label(), svg)).append('\n');
                specimenCount++;
            }
            sections.append(section(cat.title(), cat.blurb(), cells.toString()));
        }

        String html = page(specimenCount, sections.toString());
        Path out = ExampleOutputs.dir().resolve("gallery-specimen.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        String written = Files.readString(out);
        assertTrue(written.contains("<svg"), "specimen gallery embeds SVGs");
        assertEquals(specimenCount, countOccurrences(written, "<svg"),
            "one rendered SVG per specimen");
        assertTrue(specimenCount >= 60, "a broad specimen sheet, got " + specimenCount);
    }

    // ---- HTML template (self-contained, single file, no external assets) ----

    private static String cell(String label, String svg) {
        return """
                <figure class="cell">
                  <div class="render">
            %s
                  </div>
                  <code class="src">%s</code>
                </figure>""".formatted(indent(svg), escapeHtml(label));
    }

    private static String section(String title, String blurb, String cells) {
        return """
            <section class="cat">
              <header class="cat-head">
                <h2>%s</h2>
                <span class="blurb">%s</span>
              </header>
              <div class="grid">
            %s
              </div>
            </section>
            """.formatted(escapeHtml(title), escapeHtml(blurb),
                indentBlock(cells, "        "));
    }

    private static String page(int count, String sections) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LatteX — specimen gallery</title>
              %s
            </head>
            <body>
              <main>
                <header class="masthead">
                  <p class="eyebrow">LatteX · clean-room LaTeX → SVG</p>
                  <h1>Specimen gallery</h1>
                  <p class="lede">Every construct and symbol the renderer can draw today —
                    %d specimens, each rendered live to a self-contained
                    <code>svg/g/path/rect</code> subset. Regenerated on every build.</p>
                </header>
            %s
              </main>
            </body>
            </html>
            """.formatted(STYLE, count, indentBlock(sections, "    "));
    }

    private static final String STYLE = """
        <style>
            :root {
              color-scheme: light dark;
              --bg: #f5f7f7; --panel: #ffffff; --ink: #16211f; --muted: #6b7d79;
              --line: #e2e8e6; --accent: #16897a; --chip: #eef3f1; --chip-line: #dde5e2;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #0e1413; --panel: #161d1c; --ink: #e8efec; --muted: #8ba09a;
                --line: #26302e; --accent: #4fd1bd; --chip: #1b2422; --chip-line: #2b3634;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--ink);
                   font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
                   line-height: 1.5; }
            main { max-width: 1080px; margin: 0 auto; padding: 3.5rem 1.5rem 5rem; }
            .masthead { text-align: center; margin-bottom: 3rem; }
            .eyebrow { font-family: ui-monospace, monospace; font-size: .72rem;
                       letter-spacing: .2em; text-transform: uppercase;
                       color: var(--accent); margin: 0 0 .6rem; }
            h1 { font-size: 2.4rem; margin: 0 0 .8rem; letter-spacing: -.02em; }
            .lede { max-width: 56ch; margin: 0 auto; color: var(--muted); font-size: 1.02rem; }
            .lede code { font-family: ui-monospace, monospace; font-size: .92em;
                         color: var(--ink); }
            .cat { margin-top: 2.8rem; }
            .cat-head { display: flex; align-items: baseline; gap: .9rem;
                        padding-bottom: .7rem; margin-bottom: 1.4rem;
                        border-bottom: 1px solid var(--line); }
            .cat-head h2 { font-size: 1.15rem; margin: 0; letter-spacing: -.01em; }
            .blurb { font-family: ui-monospace, monospace; font-size: .74rem;
                     color: var(--muted); }
            .grid { display: grid; gap: 1rem;
                    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); }
            .cell { display: flex; flex-direction: column; align-items: center;
                    justify-content: space-between; gap: 1rem; margin: 0;
                    background: var(--panel); border: 1px solid var(--line);
                    border-radius: 12px; padding: 1.5rem 1rem 1rem;
                    transition: border-color .15s ease, transform .15s ease; }
            .cell:hover { border-color: var(--accent); transform: translateY(-2px); }
            /* glyph paths fill with currentColor, inheriting the container's
               theme-aware ink color — no invert filter needed. */
            .render { flex: 1; display: flex; align-items: center; justify-content: center;
                      min-height: 78px; width: 100%; color: var(--ink); }
            .render svg { max-height: 84px; max-width: 100%; width: auto; height: auto;
                          display: block; }
            .src { font-family: ui-monospace, monospace; font-size: .72rem;
                   color: var(--muted); background: var(--chip);
                   border: 1px solid var(--chip-line); border-radius: 6px;
                   padding: .3rem .55rem; max-width: 100%;
                   overflow-x: auto; white-space: nowrap; }
          </style>""";

    private static String indent(String svg) {
        return svg.lines().map(l -> "        " + l)
            .reduce((a, b) -> a + "\n" + b).orElse(svg);
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
