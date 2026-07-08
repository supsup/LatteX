package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/showcase.html} — "what LatteX renders", a curated
 * tour of the pass-set: bread-and-butter through structured environments,
 * including the newest arrivals (the trivial-macro tier). Every formula here
 * is ALSO in the wild-corpus pass-set, so the page can never advertise
 * something a regression broke: the ratchet guards the marketing.
 *
 * <p>Regenerated every suite run (the effects.html pattern); the committed
 * copy is the browsable artifact.
 */
class ShowcasePageTest {

    private record Item(String caption, String latex) { }

    private static final List<Item> TOUR = List.of(
        new Item("The definition of the derivative",
            "\\lim_{h\\to 0}\\frac{f(x+h)-f(x)}{h}"),
        new Item("Euler, obligatory",
            "e^{i\\pi} + 1 = 0"),
        new Item("The Basel problem",
            "\\sum_{n=1}^\\infty \\frac{1}{n^2} = \\frac{\\pi^2}{6}"),
        new Item("A Gaussian integral",
            "\\int_{-\\infty}^{\\infty} e^{-ax^2}\\,dx = \\sqrt{\\frac{\\pi}{a}}"),
        new Item("Taylor series",
            "f(x) = \\sum_{n=0}^{\\infty} \\frac{f^{(n)}(a)}{n!}(x-a)^n"),
        new Item("Bayes' theorem",
            "P(A \\mid B) = \\frac{P(B \\mid A)\\,P(A)}{P(B)}"),
        new Item("A matrix determinant (curl mnemonic)",
            "\\nabla\\times\\mathbf{F} = \\begin{vmatrix} \\hat{\\mathbf{i}} & \\hat{\\mathbf{j}} & \\hat{\\mathbf{k}} \\\\ \\frac{\\partial}{\\partial x} & \\frac{\\partial}{\\partial y} & \\frac{\\partial}{\\partial z} \\\\ F_x & F_y & F_z \\end{vmatrix}"),
        new Item("A piecewise definition (cases)",
            "|x| = \\begin{cases} x & \\text{if } x \\ge 0 \\\\ -x & \\text{if } x < 0 \\end{cases}"),
        new Item("An aligned derivation",
            "\\begin{align} (x+1)^2 &= x^2 + 2x + 1 \\\\ &= x(x+2) + 1 \\end{align}"),
        new Item("Quantum bra-ket",
            "\\langle m|n\\rangle = \\delta_{mn}"),
        new Item("Fermat's little theorem — \\pmod, new tonight",
            "a^{p-1} \\equiv 1 \\pmod{p}"),
        new Item("Euclid's algorithm — \\bmod, new tonight",
            "\\gcd(a, b) = \\gcd(b,\\ a \\bmod b)"),
        new Item("Little-o — \\iff, new tonight",
            "f = o(g) \\iff \\lim_{n \\to \\infty} \\frac{f(n)}{g(n)} = 0"),
        new Item("Semantic dots — \\dotsb vs \\dotsc, new tonight",
            "x_1 + x_2 + \\dotsb + x_n \\quad \\text{vs} \\quad x_1, x_2, \\dotsc, x_n"),
        new Item("The expectation every ML paper opens with",
            "\\mathbb{E}[X] = \\sum_{i=1}^{n} x_i \\, p(x_i)"),
        new Item("The stack mechanism — \\underbrace, new in 0.3.0",
            "\\underbrace{1 + 2 + \\dots + n}_{n \\text{ terms}} = \\frac{n(n+1)}{2}"),
        new Item("Multi-line conditions — \\substack, new in 0.3.0",
            "\\sum_{\\substack{0 \\le i \\le n \\\\ i \\text{ odd}}} i^2"),
        new Item("A labelled definition — \\stackrel, new in 0.3.0",
            "f(x) \\stackrel{\\text{def}}{=} \\limsup_{n \\to \\infty} f_n(x)"),
        new Item("A commutative diagram — \\xrightarrow over an array, new in 0.3.1",
            "\\begin{array}{ccc} X & \\stackrel{f}{\\longrightarrow} & Y \\\\ \\downarrow & & \\downarrow \\\\ X/{\\sim} & \\xrightarrow{\\bar{f}} & Y/{\\sim} \\end{array}"));

    @Test
    void writesShowcasePage() throws IOException {
        StringBuilder cards = new StringBuilder();
        for (Item item : TOUR) {
            String svg = LatteX.render(item.latex());
            assertTrue(svg.contains("<path"), "showcase item must render: " + item.latex());
            cards.append("<figure class=\"card\"><div class=\"render\">").append(svg)
                .append("</div><figcaption>").append(item.caption())
                .append("</figcaption><code>")
                .append(item.latex().replace("&", "&amp;").replace("<", "&lt;"))
                .append("</code></figure>\n");
        }
        String page = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>LatteX — what it renders</title>
            <style>
              body { font-family: system-ui, sans-serif; background: #f4f5f7;
                     margin: 0; padding: 2.5rem 1.5rem; color: #171a1f; }
              main { max-width: 880px; margin: 0 auto; }
              h1 { font-size: 1.7rem; } p.lede { color: #5b6570; max-width: 60ch; }
              .card { background: #fff; border: 1px solid #e0e4e8; border-radius: 10px;
                      padding: 1.2rem 1.4rem; margin: 0.9rem 0; }
              .render { overflow-x: auto; padding: 0.4rem 0; }
              figcaption { color: #5b6570; font-size: 0.85rem; margin-top: 0.5rem; }
              code { display: block; margin-top: 0.4rem; font-size: 0.72rem;
                     color: #8a939d; overflow-x: auto; }
              @media (prefers-color-scheme: dark) {
                body { background: #0e1116; color: #e9ecf1; }
                .card { background: #161a20; border-color: #262c34; }
                .render svg { color: #e9ecf1; }
              }
            </style></head><body><main>
            <h1>LatteX — what it renders</h1>
            <p class="lede">A curated tour of the wild-corpus pass-set: every formula
            on this page is regression-locked by the coverage ratchet, so the page
            can never advertise something a change broke. Current wild coverage:
            477/484 real-world formulas (98.6%).</p>
            """ + cards + "</main></body></html>\n";
        Path out = Path.of("examples", "showcase.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, page);
        assertTrue(Files.size(out) > 5_000);
    }
}
