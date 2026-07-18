package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/graph.html} — the LatteX <strong>graph-plot</strong>
 * demo. Every graphable expression is a {@code \lx[graph.*]{…}} macro rendered
 * live through {@link LatteX#renderStyledHtml(String)}: the styled math becomes an
 * affordance-free inner {@code <svg>} wrapped in a trusted
 * {@code <span class="lx-math" data-lx-graph-…>} container. A small, self-contained
 * page-side runtime (inlined below) reads those container attributes and plots the
 * function into a floating popup — a page element that is deliberately richer than
 * the math SVG (a {@code <canvas>}), and entirely SEPARATE from it.
 *
 * <p><strong>Containment.</strong> The plotting metadata (expression / domain /
 * open-mode) rides ONLY the container span as {@code data-lx-graph-*}; it is never
 * emitted into the {@code <svg>}. This test asserts that: for every rendered
 * specimen it extracts the inner {@code <svg>} and fails if any plotting /
 * {@code data-} / {@code on*} / {@code <script>} string appears inside it. The
 * math SVG stays within the minimal {@code svg/g/path/rect} alphabet exactly as
 * {@link S8SpecimenGalleryTest} / the S8 containment guard require.
 *
 * <p>Like the other page generators the file is regenerated (and asserted) on every
 * build, so the demo can never drift from the actual emitter output.
 */
@Tag("examples") // generator, not a test: runs under `generateExamples`, not `test` (plan 32148cc8 S2)
class GraphPageTest {

    /** One graphable specimen: the {@code \lx} source + a human caption. */
    private record Plot(String latex, String caption) {
    }

    // graph.open=single → one transient popup, reused; click-away / Esc dismiss it.
    private static final List<Plot> SINGLE = List.of(
        new Plot("\\lx[graph.domain=-10..10, graph.open=single]{f(x) = x^2 - 3}",
            "A parabola crossing the axis — click it, the popup floats beside the math"),
        new Plot("\\lx[graph.domain=-6..6, graph.open=single]{\\sin{x}}",
            "\\sin over a custom window; click away (or Esc) to dismiss"),
        new Plot("\\lx[graph.domain=-4..4, graph.open=single]{2x - 1}",
            "A line — the single popup is reused, never stacked"));

    // graph.open=multi → a fresh persistent popup per click; ✕ / Esc close them,
    // click-away does NOT. They cascade so several coexist for comparison.
    private static final List<Plot> MULTI = List.of(
        new Plot("\\lx[graph.domain=-10..10, graph.open=multi]{y = \\frac{1}{x}}",
            "A hyperbola — the asymptote is a gap, not a spike; open it several times"),
        new Plot("\\lx[graph.domain=-3..3, graph.open=multi]{x^3 - 3x}",
            "A cubic on a tight window — open it alongside the hyperbola; they stack + stay"),
        new Plot("\\lx[graph.domain=-2..4, graph.open=multi]{\\frac{x^2}{2} - x}",
            "Another parabola — persistent popups let you compare curves side by side"));

    // A plain (non-graphable) \lx: no graph.* options, so no popup — proves the
    // runtime only wires the expressions the author marked plottable.
    private static final Plot PLAIN =
        new Plot("\\lx{ \\frac{a+b}{c} }", "Plain \\lx math — no graph.* options, so it does not plot");

    // Inline-in-prose graphable expression (open=multi).
    private static final String INLINE =
        "\\lx[graph.domain=-5..5, graph.open=multi]{x^2 - 2x - 1}";

    @Test
    void writesGraphPage() throws IOException {
        StringBuilder singleCells = new StringBuilder();
        int graphable = 0;
        for (Plot p : SINGLE) {
            singleCells.append(card(p)).append('\n');
            graphable++;
        }
        StringBuilder multiCells = new StringBuilder();
        for (Plot p : MULTI) {
            multiCells.append(card(p)).append('\n');
            graphable++;
        }
        String plainCell = card(PLAIN);
        String inlineHtml = LatteX.renderStyledHtml(INLINE);
        graphable++;

        String html = page(singleCells.toString(), multiCells.toString(), plainCell, inlineHtml);
        Path out = Path.of("examples", "graph.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        // ---- golden-output assertions ----
        String written = Files.readString(out);
        assertTrue(Files.size(out) > 0, "graph.html non-empty");
        assertTrue(written.contains("<svg"), "embeds rendered math SVGs");
        // Container carries the plotting metadata.
        assertTrue(written.contains("data-lx-graph-domain=\"-3..3\""), "carries a custom domain");
        assertTrue(written.contains("data-lx-graph-open=\"single\""), "has a single-open example");
        assertTrue(written.contains("data-lx-graph-open=\"multi\""), "has a multi-open example");
        assertTrue(written.contains("data-lx-graph-expr="), "carries the plottable expression");
        // Runtime is inlined and self-contained (no external assets).
        assertTrue(written.contains("lx-graph-popup"), "inlines the plotting runtime");
        assertTrue(written.contains("makeEvaluator"), "inlines the safe expression evaluator");
        assertFalse(written.contains("<script src"), "no external script sources");
        assertFalse(written.contains("<link"), "no external stylesheets");
        // 7 graphable specimens (3 single + 3 multi + 1 inline).
        assertEquals(7, graphable, "3 single + 3 multi + 1 inline graphable specimens");

        // ---- CONTAINMENT: every inner <svg> is affordance-free ----
        int svgsChecked = 0;
        for (String svg : innerSvgs(written)) {
            svgsChecked++;
            String lower = svg.toLowerCase(Locale.ROOT);
            for (String affordance : new String[] {"data-", "data-lx", "graph", "onclick",
                    "onmouseover", "<script", "javascript:", "canvas", "popup"}) {
                assertFalse(lower.contains(affordance.toLowerCase(Locale.ROOT)),
                    "inner math SVG must be affordance-free; found \"" + affordance + "\" in: " + svg);
            }
            assertAlphabetContained(svg);
        }
        assertTrue(svgsChecked >= 8, "checked every embedded math SVG, got " + svgsChecked);
    }

    // -------------------------------------------------------------------------
    // HTML fragments.
    // -------------------------------------------------------------------------

    private static String card(Plot p) {
        String html = LatteX.renderStyledHtml(p.latex());
        return """
                <figure class="card">
                  <div class="render">
            %s
                  </div>
                  <figcaption>
                    <span class="cap">%s</span>
                    <code class="src">%s</code>
                  </figcaption>
                </figure>""".formatted(indent(html, "        "),
                escapeHtml(p.caption()), escapeHtml(p.latex()));
    }

    private static String page(String single, String multi, String plain, String inline) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LatteX — graph-plot demo</title>
            %s
            %s
            </head>
            <body>
              <main>
                <section class="hero">
                  <p class="eyebrow">clean-room · pure Java 25 · zero dependencies</p>
                  <h1 class="wordmark">Latte<span class="x">X</span> · graph plots</h1>
                  <p class="tagline">Click a graphable expression to plot it. The math stays a
                    sanitizer-safe <code>&lt;svg&gt;</code>; the plot is a separate page-side popup.</p>
                  <p class="note"><strong>single</strong> opens one transient popup (click away or
                    press <kbd>Esc</kbd> to dismiss). <strong>multi</strong> opens a persistent popup
                    with a close <span class="times">&times;</span>; several stack so you can compare —
                    click-away leaves them open. Open DevTools for <code>[lx-graph]</code> logs.</p>
                </section>

                <section class="band">
                  <header class="band-head">
                    <h2>graph.open=single</h2>
                    <p>One reused popup that dismisses on click-away / <kbd>Esc</kbd>.</p>
                  </header>
                  <div class="grid">
            %s
                  </div>
                </section>

                <section class="band">
                  <header class="band-head">
                    <h2>graph.open=multi</h2>
                    <p>Persistent, stackable popups — open several and compare the curves.</p>
                  </header>
                  <div class="grid">
            %s
                  </div>
                </section>

                <section class="band">
                  <header class="band-head">
                    <h2>Not everything plots</h2>
                    <p>A plain <code>\\lx</code> with no <code>graph.*</code> options is just math —
                      clicking it does nothing.</p>
                  </header>
                  <div class="grid">
            %s
                  </div>
                </section>

                <section class="band">
                  <header class="band-head"><h2>Inline, in prose</h2></header>
                  <p class="prose">The renderer plots inline snippets too: the quadratic
                    %s plots on click, and its popup floats beside the
                    snippet — never squeezed to the line's width. It is a
                    <code>graph.open=multi</code> expression, so it stacks with the others above.</p>
                </section>

                <footer class="foot">
                  <p class="foot-mark">Latte<span class="x">X</span></p>
                  <p class="foot-fine">Math rendered live from LaTeX · STIX Two Math (OFL) ·
                    plotting runtime is vanilla JS over <code>data-lx-graph-*</code> container attributes</p>
                </footer>
              </main>
            %s
            </body>
            </html>
            """.formatted(STYLE, "", indentBlock(single, "    "), indentBlock(multi, "    "),
                indentBlock(plain, "    "), inline, RUNTIME);
    }

    // -------------------------------------------------------------------------
    // Style — theme-aware (light + dark). Kept a raw constant (literal '%' safe).
    // -------------------------------------------------------------------------
    private static final String STYLE = """
        <style>
          :root {
            color-scheme: light dark;
            --bg:#eceef1; --bg-2:#e3e6ea; --panel:#ffffff; --ink:#171a1f;
            --muted:#5b6570; --faint:#8a939d; --line:#d8dce1;
            --accent:#1f7d72; --accent-2:#2aa596; --chip:#f1f3f5; --chip-line:#e0e4e8;
            --shadow:0 1px 2px rgba(20,24,30,.04), 0 8px 30px rgba(20,24,30,.06);
            --plot-bg:#fbfdfc; --plot-line:#eef2f1; --plot-grid:#e9efed;
            --plot-axis:#9fb3ae; --plot-curve:#1f7d72;
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg:#0e1116; --bg-2:#0a0d11; --panel:#161a20; --ink:#e9ecf1;
              --muted:#97a1ad; --faint:#6b7480; --line:#262c34;
              --accent:#2aa596; --accent-2:#3fc4b3; --chip:#1b2027; --chip-line:#2a313a;
              --shadow:0 1px 2px rgba(0,0,0,.3), 0 12px 34px rgba(0,0,0,.45);
              --plot-bg:#10151a; --plot-line:#232b33; --plot-grid:#20272f;
              --plot-axis:#4a5b62; --plot-curve:#3fc4b3;
            }
          }
          :root[data-theme="light"] {
            color-scheme: light;
            --bg:#eceef1; --bg-2:#e3e6ea; --panel:#ffffff; --ink:#171a1f;
            --muted:#5b6570; --faint:#8a939d; --line:#d8dce1;
            --accent:#1f7d72; --accent-2:#2aa596; --chip:#f1f3f5; --chip-line:#e0e4e8;
            --shadow:0 1px 2px rgba(20,24,30,.04), 0 8px 30px rgba(20,24,30,.06);
            --plot-bg:#fbfdfc; --plot-line:#eef2f1; --plot-grid:#e9efed;
            --plot-axis:#9fb3ae; --plot-curve:#1f7d72;
          }
          :root[data-theme="dark"] {
            color-scheme: dark;
            --bg:#0e1116; --bg-2:#0a0d11; --panel:#161a20; --ink:#e9ecf1;
            --muted:#97a1ad; --faint:#6b7480; --line:#262c34;
            --accent:#2aa596; --accent-2:#3fc4b3; --chip:#1b2027; --chip-line:#2a313a;
            --shadow:0 1px 2px rgba(0,0,0,.3), 0 12px 34px rgba(0,0,0,.45);
            --plot-bg:#10151a; --plot-line:#232b33; --plot-grid:#20272f;
            --plot-axis:#4a5b62; --plot-curve:#3fc4b3;
          }

          * { box-sizing: border-box; }
          body {
            margin:0; color:var(--ink);
            background:radial-gradient(1200px 620px at 50% -8%, var(--bg) 0%, var(--bg-2) 62%);
            font-family:ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
            line-height:1.55; -webkit-font-smoothing:antialiased;
          }
          main { max-width:1080px; margin:0 auto; padding:0 1.5rem 6rem; overflow-x:hidden; }
          code, kbd { font-family:ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
          kbd { font-size:.82em; background:var(--chip); border:1px solid var(--chip-line);
                border-radius:5px; padding:.02em .35em; }

          .render { display:flex; align-items:center; justify-content:center; width:100%;
                    color:var(--ink); overflow-x:auto; }
          .render svg { display:block; width:auto; height:auto; max-width:100%; max-height:96px; }

          .hero { text-align:center; padding:4.5rem 0 2rem; }
          .eyebrow { font-family:ui-monospace, monospace; font-size:.72rem; letter-spacing:.22em;
                     text-transform:uppercase; color:var(--accent); margin:0 0 1.2rem; }
          .wordmark { font-size:clamp(2.4rem,7vw,3.8rem); font-weight:800; letter-spacing:-.04em;
                      line-height:1; margin:0; }
          .wordmark .x { background:linear-gradient(160deg, var(--accent-2), var(--accent));
                         -webkit-background-clip:text; background-clip:text; color:transparent; }
          .tagline { max-width:56ch; margin:1.1rem auto 0; color:var(--muted);
                     font-size:clamp(1rem,2vw,1.15rem); }
          .note { max-width:64ch; margin:1.2rem auto 0; color:var(--muted); font-size:.92rem; }
          .times { color:var(--accent); font-weight:700; }

          .band { margin-top:3.5rem; }
          .band-head { max-width:60ch; margin:0 0 1.4rem; }
          .band-head h2 { font-size:1.4rem; letter-spacing:-.02em; margin:0 0 .35rem;
                          font-family:ui-monospace, monospace; }
          .band-head p { margin:0; color:var(--muted); font-size:.98rem; }

          .grid { display:grid; gap:1rem; grid-template-columns:repeat(auto-fill, minmax(280px, 1fr)); }
          .card { margin:0; display:flex; flex-direction:column; gap:1.1rem;
                  background:var(--panel); border:1px solid var(--line); border-radius:14px;
                  padding:1.6rem 1.25rem 1.05rem; box-shadow:var(--shadow);
                  transition:border-color .16s ease, transform .16s ease; }
          .card:hover { border-color:var(--accent); transform:translateY(-2px); }
          .card .render { flex:1; min-height:92px; }
          .card figcaption { display:flex; flex-direction:column; gap:.5rem; align-items:flex-start; }
          .card .cap { font-size:.88rem; font-weight:600; color:var(--ink); }
          .card .src { font-size:.7rem; color:var(--muted); background:var(--chip);
                       border:1px solid var(--chip-line); border-radius:6px; padding:.28rem .5rem;
                       max-width:100%; overflow-x:auto; white-space:nowrap; }

          .prose { font-size:1.08rem; line-height:2.1; color:var(--ink); max-width:64ch; }

          /* A graphable expression advertises itself as clickable. */
          .lx-math { display:inline-block; border-radius:5px; padding:0 .12em;
                     transition:background .12s ease, box-shadow .12s ease; vertical-align:middle; }
          .lx-math[data-lx-graph-expr] { cursor:pointer; }
          .lx-math[data-lx-graph-expr]:hover,
          .lx-math[data-lx-graph-expr]:focus-visible {
            background:color-mix(in srgb, var(--accent) 14%, transparent); outline:none; }
          .lx-math[data-lx-graph-expr]:focus-visible {
            box-shadow:0 0 0 2px color-mix(in srgb, var(--accent) 55%, transparent); }

          /* The floating plot popup — a page element, NEVER inside the math SVG. */
          .lx-graph-popup { position:fixed; z-index:1050; background:var(--panel);
            border:1px solid var(--line); border-radius:12px; box-shadow:0 12px 40px rgba(0,0,0,.28);
            padding:.6rem; width:300px; color:var(--ink);
            opacity:0; transform:translateY(2px); transition:opacity .12s ease, transform .12s ease; }
          .lx-graph-popup:not([hidden]) { opacity:1; transform:none; }
          .lx-graph-popup[hidden] { display:none; }
          .lx-graph-multi { border-top:3px solid var(--accent); }
          .lx-graph-head { display:flex; align-items:center; justify-content:space-between;
            gap:.5rem; margin:0 0 .4rem; }
          .lx-graph-eq { font-family:ui-monospace, monospace; font-size:.72rem; color:var(--ink);
            overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
          .lx-graph-close { border:none; background:transparent; cursor:pointer; color:var(--muted);
            font-size:1.15rem; line-height:1; padding:.05rem .35rem; border-radius:6px; }
          .lx-graph-close:hover { background:var(--chip); color:var(--ink); }
          .lx-graph-close:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }
          .lx-graph-popup canvas { display:block; border-radius:8px; background:var(--plot-bg);
            border:1px solid var(--plot-line); }
          .lx-graph-dom { font-family:ui-monospace, monospace; font-size:.6rem; color:var(--faint);
            margin:.35rem 0 0; text-align:right; }

          .lx-toast { position:fixed; left:50%; bottom:2rem; transform:translateX(-50%) translateY(8px);
            background:#171a1f; color:#fff; padding:.6rem 1rem; border-radius:9px; font-size:.85rem;
            box-shadow:0 8px 28px rgba(0,0,0,.24); opacity:0; z-index:1100; pointer-events:none;
            transition:opacity .2s ease, transform .2s ease; }
          .lx-toast.show { opacity:1; transform:translateX(-50%) translateY(0); }

          .foot { margin-top:4.5rem; padding-top:2rem; border-top:1px solid var(--line); text-align:center; }
          .foot-mark { font-weight:800; font-size:1.3rem; letter-spacing:-.03em; margin:0 0 .6rem; }
          .foot-mark .x { background:linear-gradient(160deg, var(--accent-2), var(--accent));
            -webkit-background-clip:text; background-clip:text; color:transparent; }
          .foot-fine { margin:0; color:var(--faint); font-size:.74rem;
            font-family:ui-monospace, monospace; letter-spacing:.03em; }

          @media (prefers-reduced-motion: reduce) {
            .lx-math, .lx-graph-popup, .lx-toast, .card { transition:none !important; }
          }
        </style>""";

    // -------------------------------------------------------------------------
    // Runtime — vanilla JS, self-contained, backslash-free by construction (LaTeX
    // command names are matched WITHOUT their leading backslash; the one raw
    // backslash the evaluator needs is built via String.fromCharCode(92)). Kept a
    // raw constant so its many literal quotes/percent-free body need no escaping.
    //
    // It reads the container's data-lx-graph-* attributes and plots into a popup
    // that is a SEPARATE page element (a <canvas>) — the math <svg> is never
    // touched, read-only, and stays affordance-free.
    // -------------------------------------------------------------------------
    private static final String RUNTIME = """
        <script>
        (function () {
          "use strict";

          var BS = String.fromCharCode(92); // a single backslash, no source escapes
          var TAB = String.fromCharCode(9);

          function isDigit(c) { return c >= "0" && c <= "9"; }
          function isAlpha(c) { return (c >= "a" && c <= "z") || (c >= "A" && c <= "Z"); }

          var FUNCS = { sin: Math.sin, cos: Math.cos, tan: Math.tan, exp: Math.exp, ln: Math.log };
          var KNOWN = { frac: 1, sqrt: 1, pi: 1, sin: 1, cos: 1, tan: 1, exp: 1, ln: 1 };

          // Turn a demo-scope LaTeX expression into a numeric f(x). Uses the RHS after
          // the first "=" (or the whole thing). Recursive descent over the raw chars;
          // every node is a hand-built numeric closure — a string is NEVER evaluated as
          // code. Anything outside the grammar throws, and the caller toasts.
          function makeEvaluator(latexRaw) {
            var eq = latexRaw.indexOf("=");
            var s = (eq >= 0 ? latexRaw.slice(eq + 1) : latexRaw);
            var n = s.length, pos = 0;

            function ws() { while (pos < n && (s[pos] === " " || s[pos] === TAB)) { pos++; } }
            function peek() { ws(); return pos < n ? s[pos] : ""; }
            function readCmd() { pos++; var st = pos; while (pos < n && isAlpha(s[pos])) { pos++; } return s.slice(st, pos); }
            function peekCmd() {
              ws();
              if (pos < n && s[pos] === BS) {
                var p = pos + 1, st = p;
                while (p < n && isAlpha(s[p])) { p++; }
                return s.slice(st, p);
              }
              return "";
            }
            function expect(ch) { ws(); if (pos < n && s[pos] === ch) { pos++; return; } throw new Error("expected " + ch); }
            function atFactorStart() {
              var c = peek();
              if (c === "") { return false; }
              if (isDigit(c) || c === "." || c === "(" || c === "{") { return true; }
              if (c === "x" || c === "e") { return true; }
              if (c === BS) { return !!KNOWN[peekCmd()]; }
              return false;
            }

            function add(a, b) { return function (x) { return a(x) + b(x); }; }
            function sub(a, b) { return function (x) { return a(x) - b(x); }; }
            function mul(a, b) { return function (x) { return a(x) * b(x); }; }
            function div(a, b) { return function (x) { return a(x) / b(x); }; }
            function pw(a, b)  { return function (x) { return Math.pow(a(x), b(x)); }; }

            function parseExpr() {
              var node = parseTerm();
              while (true) {
                var c = peek();
                if (c === "+") { pos++; node = add(node, parseTerm()); }
                else if (c === "-") { pos++; node = sub(node, parseTerm()); }
                else { break; }
              }
              return node;
            }
            function parseTerm() {
              var node = parsePower();
              while (true) {
                var c = peek();
                if (c === "*") { pos++; node = mul(node, parsePower()); continue; }
                if (c === "/") { pos++; node = div(node, parsePower()); continue; }
                if (c === BS) {
                  var cmd = peekCmd();
                  if (cmd === "cdot" || cmd === "times") { readCmd(); node = mul(node, parsePower()); continue; }
                }
                if (atFactorStart()) { node = mul(node, parsePower()); continue; }
                break;
              }
              return node;
            }
            function parsePower() {
              var base = parseUnary();
              ws();
              if (pos < n && s[pos] === "^") { pos++; return pw(base, parsePower()); }
              return base;
            }
            function parseUnary() {
              var c = peek();
              if (c === "+") { pos++; return parseUnary(); }
              if (c === "-") { pos++; var u = parseUnary(); return function (x) { return -u(x); }; }
              return parseAtom();
            }
            function number() {
              var st = pos;
              while (pos < n && isDigit(s[pos])) { pos++; }
              if (pos < n && s[pos] === ".") { pos++; while (pos < n && isDigit(s[pos])) { pos++; } }
              var v = parseFloat(s.slice(st, pos));
              return function () { return v; };
            }
            function parseArg() {
              ws();
              if (pos < n && s[pos] === "{") { pos++; var e = parseExpr(); expect("}"); return e; }
              return parseAtom();
            }
            function parseCommandAtom() {
              var cmd = readCmd();
              if (cmd === "frac") { var a = parseArg(); var b = parseArg(); return div(a, b); }
              if (cmd === "sqrt") {
                ws();
                if (pos < n && s[pos] === "[") { throw new Error("n-th root not supported"); }
                var g = parseArg();
                return function (x) { return Math.sqrt(g(x)); };
              }
              if (cmd === "pi") { return function () { return Math.PI; }; }
              var F = FUNCS[cmd];
              if (F) { var arg = parseArg(); return function (x) { return F(arg(x)); }; }
              throw new Error("unsupported command: " + cmd);
            }
            function parseAtom() {
              ws();
              var c = pos < n ? s[pos] : "";
              if (c === "") { throw new Error("unexpected end"); }
              if (c === "(") { pos++; var e = parseExpr(); expect(")"); return e; }
              if (c === "{") { pos++; var g = parseExpr(); expect("}"); return g; }
              if (isDigit(c) || c === ".") { return number(); }
              if (c === "x") { pos++; return function (x) { return x; }; }
              if (c === "e") { pos++; return function () { return Math.E; }; }
              if (c === BS) { return parseCommandAtom(); }
              throw new Error("unexpected " + c);
            }

            var fn = parseExpr();
            ws();
            if (pos < n) { throw new Error("trailing: " + s.slice(pos)); }
            if (typeof fn(1) !== "number") { throw new Error("non-numeric result"); }
            return fn;
          }

          function parseDomain(str) {
            var def = [-10, 10];
            if (!str) { return def; }
            var k = str.indexOf("..");
            if (k < 0) { return def; }
            var a = parseFloat(str.slice(0, k));
            var b = parseFloat(str.slice(k + 2));
            if (!isFinite(a) || !isFinite(b) || a === b) { return def; }
            return a < b ? [a, b] : [b, a];
          }

          function pct(sorted, p) {
            if (!sorted.length) { return 0; }
            var idx = Math.round((p / 100) * (sorted.length - 1));
            idx = Math.min(sorted.length - 1, Math.max(0, idx));
            return sorted[idx];
          }

          function cssVar(el, name, fallback) {
            var v = getComputedStyle(el).getPropertyValue(name);
            return (v && v.trim()) || fallback;
          }

          // Sample f across [a,b], percentile-trim the Y window (so 1/x near 0 does not
          // blow up), draw grid + axes + curve. The line breaks at any non-finite or
          // out-of-view sample, so an asymptote is a gap, not a spike. Colors come from
          // theme-aware CSS vars, so the plot tracks light/dark.
          function drawPlot(canvas, fn, a, b) {
            var dpr = window.devicePixelRatio || 1;
            var W = 280, H = 200;
            canvas.width = Math.round(W * dpr);
            canvas.height = Math.round(H * dpr);
            canvas.style.width = W + "px";
            canvas.style.height = H + "px";
            var ctx = canvas.getContext("2d");
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.clearRect(0, 0, W, H);

            var padL = 6, padR = 6, padT = 8, padB = 6;
            var N = 480, i, x, y;
            var xs = [], ys = [], finite = [];
            for (i = 0; i <= N; i++) {
              x = a + (b - a) * i / N;
              y = fn(x);
              xs.push(x); ys.push(y);
              if (isFinite(y)) { finite.push(y); }
            }
            finite.sort(function (p, q) { return p - q; });
            var lo, hi;
            if (!finite.length) { lo = -1; hi = 1; }
            else { lo = pct(finite, 2); hi = pct(finite, 98); }
            if (lo === hi) { lo -= 1; hi += 1; }
            if (lo > 0) { lo = 0; }
            if (hi < 0) { hi = 0; }
            var padY = (hi - lo) * 0.08 || 1;
            var y0 = lo - padY, y1 = hi + padY;

            function PX(xx) { return padL + (xx - a) / (b - a) * (W - padL - padR); }
            function PY(yy) { return H - padB - (yy - y0) / (y1 - y0) * (H - padT - padB); }

            var VN = 8, HN = 6, g;
            ctx.strokeStyle = cssVar(canvas, "--plot-grid", "#e9efed"); ctx.lineWidth = 1;
            ctx.beginPath();
            for (g = 0; g <= VN; g++) { var gx = padL + (W - padL - padR) * g / VN; ctx.moveTo(gx, padT); ctx.lineTo(gx, H - padB); }
            for (g = 0; g <= HN; g++) { var gy = padT + (H - padT - padB) * g / HN; ctx.moveTo(padL, gy); ctx.lineTo(W - padR, gy); }
            ctx.stroke();

            ctx.strokeStyle = cssVar(canvas, "--plot-axis", "#9fb3ae"); ctx.lineWidth = 1.2;
            ctx.beginPath();
            if (a < 0 && b > 0) { ctx.moveTo(PX(0), padT); ctx.lineTo(PX(0), H - padB); }
            if (y0 < 0 && y1 > 0) { ctx.moveTo(padL, PY(0)); ctx.lineTo(W - padR, PY(0)); }
            ctx.stroke();

            ctx.strokeStyle = cssVar(canvas, "--plot-curve", "#1f7d72"); ctx.lineWidth = 2; ctx.lineJoin = "round";
            ctx.beginPath();
            var penUp = true;
            for (i = 0; i < xs.length; i++) {
              y = ys[i];
              var inView = isFinite(y) && y >= y0 && y <= y1;
              if (inView) {
                var Xp = PX(xs[i]), Yp = PY(y);
                if (penUp) { ctx.moveTo(Xp, Yp); penUp = false; } else { ctx.lineTo(Xp, Yp); }
              } else { penUp = true; }
            }
            ctx.stroke();
          }

          function graphMarkup() {
            return "<div class=\\"lx-graph-head\\"><span class=\\"lx-graph-eq\\"></span>"
              + "<button type=\\"button\\" class=\\"lx-graph-close\\" aria-label=\\"Close graph\\">&#215;</button></div>"
              + "<canvas></canvas><p class=\\"lx-graph-dom\\"></p>";
          }

          function fillGraph(p, latex, fn, a, b) {
            p.querySelector(".lx-graph-eq").textContent = latex;
            p.querySelector(".lx-graph-dom").textContent = "x in [" + a + ", " + b + "]";
            drawPlot(p.querySelector("canvas"), fn, a, b);
          }

          function clampInto(left, top, pw, ph) {
            var margin = 8;
            left = Math.max(margin, Math.min(left, window.innerWidth - pw - margin));
            top = Math.max(margin, Math.min(top, window.innerHeight - ph - margin));
            return [left, top];
          }

          // ---- single mode: one transient popup, reused and repositioned ----
          var gpop = null, gActiveEl = null;

          function ensureGraph() {
            if (gpop) { return gpop; }
            gpop = document.createElement("div");
            gpop.className = "lx-graph-popup";
            gpop.hidden = true;
            gpop.setAttribute("role", "dialog");
            gpop.setAttribute("aria-label", "Equation graph");
            gpop.setAttribute("tabindex", "-1");
            gpop.innerHTML = graphMarkup();
            gpop.addEventListener("click", function (ev) { ev.stopPropagation(); });
            gpop.querySelector(".lx-graph-close").addEventListener("click", function () { closeGraph(true); });
            document.body.appendChild(gpop);
            return gpop;
          }

          function positionGraph(el) {
            var r = el.getBoundingClientRect();
            var pw = gpop.offsetWidth, ph = gpop.offsetHeight, gap = 10;
            var left = r.left + r.width / 2 - pw / 2;
            var top = r.bottom + gap;
            if (top + ph > window.innerHeight - 8) {
              var above = r.top - ph - gap;
              top = (above >= 8) ? above : Math.max(8, window.innerHeight - ph - 8);
            }
            var xy = clampInto(left, top, pw, ph);
            gpop.style.left = Math.round(xy[0]) + "px";
            gpop.style.top = Math.round(xy[1]) + "px";
          }

          function openGraph(el, latex, fn, a, b) {
            ensureGraph();
            gActiveEl = el;
            gpop.hidden = false;
            fillGraph(gpop, latex, fn, a, b);
            positionGraph(el);
            gpop.focus();
          }

          function closeGraph(restore) {
            if (!gpop || gpop.hidden) { return; }
            gpop.hidden = true;
            var el = gActiveEl; gActiveEl = null;
            if (restore && el) { el.focus(); }
          }

          // ---- multi mode: independent, persistent, cascading popups ----
          var multiPops = [];

          function openGraphMulti(el, latex, fn, a, b) {
            // Already open for this element? Focus that popup instead of stacking a duplicate.
            for (var i = 0; i < multiPops.length; i++) {
              if (multiPops[i].__lxSourceEl === el) { multiPops[i].focus(); return; }
            }
            var p = document.createElement("div");
            p.className = "lx-graph-popup lx-graph-multi";
            p.setAttribute("role", "dialog");
            p.setAttribute("aria-label", "Equation graph");
            p.setAttribute("tabindex", "-1");
            p.innerHTML = graphMarkup();
            p.addEventListener("click", function (ev) { ev.stopPropagation(); });
            p.querySelector(".lx-graph-close").addEventListener("click", function () { closeMulti(p, true); });
            document.body.appendChild(p);
            fillGraph(p, latex, fn, a, b);
            positionMulti(p, el);
            p.__lxSourceEl = el;
            multiPops.push(p);
            p.focus();
          }

          function positionMulti(p, el) {
            var r = el.getBoundingClientRect();
            var pw = p.offsetWidth, ph = p.offsetHeight, gap = 10;
            var step = 24 * multiPops.length;
            var xy = clampInto(r.left + r.width / 2 - pw / 2 + step, r.bottom + gap + step, pw, ph);
            p.style.left = Math.round(xy[0]) + "px";
            p.style.top = Math.round(xy[1]) + "px";
          }

          function closeMulti(p, restore) {
            var idx = multiPops.indexOf(p);
            if (idx >= 0) { multiPops.splice(idx, 1); }
            if (p.parentNode) { p.parentNode.removeChild(p); }
            if (restore) {
              var top = multiPops[multiPops.length - 1];
              if (top) { top.focus(); }
            }
          }

          function focusedMulti() {
            var active = document.activeElement;
            for (var i = multiPops.length - 1; i >= 0; i--) {
              if (multiPops[i].contains(active)) { return multiPops[i]; }
            }
            return null;
          }

          function toast(msg) {
            var t = document.createElement("div");
            t.className = "lx-toast"; t.textContent = msg;
            document.body.appendChild(t);
            requestAnimationFrame(function () { t.classList.add("show"); });
            setTimeout(function () {
              t.classList.remove("show");
              setTimeout(function () { if (t.parentNode) { t.parentNode.removeChild(t); } }, 300);
            }, 1600);
          }

          function doGraph(el) {
            var latex = el.getAttribute("data-lx-graph-expr") || "";
            var mode = el.getAttribute("data-lx-graph-open") === "multi" ? "multi" : "single";
            var fn;
            try { fn = makeEvaluator(latex); }
            catch (e) {
              console.log("[lx-graph] cannot plot " + JSON.stringify(latex) + ": " + e.message);
              toast("Can't plot this yet");
              return;
            }
            var dom = parseDomain(el.getAttribute("data-lx-graph-domain"));
            var eq = latex.indexOf("=");
            var shown = (eq >= 0 ? latex.slice(eq + 1) : latex).trim();
            console.log("[lx-graph] plot " + shown + " over [" + dom[0] + ", " + dom[1] + "] mode=" + mode);
            if (mode === "multi") { openGraphMulti(el, latex, fn, dom[0], dom[1]); }
            else { openGraph(el, latex, fn, dom[0], dom[1]); }
          }

          function init() {
            var els = document.querySelectorAll(".lx-math[data-lx-graph-expr]");
            console.log("[lx-graph] init: " + els.length + " graphable expressions");
            Array.prototype.forEach.call(els, function (el) {
              el.setAttribute("tabindex", "0");
              el.setAttribute("role", "button");
              el.setAttribute("aria-label", "Plot " + (el.getAttribute("data-lx-graph-expr") || "expression"));
              function trigger(ev) { ev.stopPropagation(); doGraph(el); }
              el.addEventListener("click", trigger);
              el.addEventListener("keydown", function (ev) {
                if (ev.key === "Enter" || ev.key === " " || ev.key === "Spacebar") { ev.preventDefault(); trigger(ev); }
              });
            });

            // Click-away dismisses the transient (single) popup; persistent multi
            // popups stopPropagation, so they are NOT closed by an outside click.
            document.addEventListener("click", function () { closeGraph(false); });
            document.addEventListener("keydown", function (ev) {
              if (ev.key !== "Escape") { return; }
              if (gpop && !gpop.hidden && gpop.contains(document.activeElement)) { closeGraph(true); return; }
              var m = focusedMulti() || multiPops[multiPops.length - 1];
              if (m) { closeMulti(m, true); return; }
              if (gpop && !gpop.hidden) { closeGraph(true); }
            });
            // Scroll/resize dismiss the transient popup; multi popups are fixed + persist.
            window.addEventListener("scroll", function () { closeGraph(false); }, true);
            window.addEventListener("resize", function () { closeGraph(false); });
          }

          if (document.readyState === "loading") { document.addEventListener("DOMContentLoaded", init); }
          else { init(); }
        })();
        </script>""";

    // -------------------------------------------------------------------------
    // Containment helpers (mirror LxRenderTest / S8LeftContainmentTest).
    // -------------------------------------------------------------------------

    private static final java.util.Set<String> ALLOWED_ELEMENTS =
        java.util.Set.of("svg", "g", "path", "rect");
    private static final java.util.Set<String> ALLOWED_ATTRS = java.util.Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label",
        "transform", "fill", "d", "stroke", "stroke-width", "x", "y");

    /** Extracts every {@code <svg>…</svg>} embedded in the page. */
    private static List<String> innerSvgs(String html) {
        List<String> out = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int start = html.indexOf("<svg", from);
            if (start < 0) { break; }
            int end = html.indexOf("</svg>", start);
            assertTrue(end > start, "each <svg> is closed");
            out.add(html.substring(start, end + "</svg>".length()));
            from = end + 6;
        }
        return out;
    }

    private static void assertAlphabetContained(String svg) {
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(svg);
        while (tag.find()) {
            String element = tag.group(1);
            assertTrue(ALLOWED_ELEMENTS.contains(element), "element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=").matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(ALLOWED_ATTRS.contains(attr.group(1)),
                    "attribute out of alphabet: " + attr.group(1));
            }
        }
    }

    private static String indent(String block, String pad) {
        return block.lines().map(l -> pad + l).reduce((a, b) -> a + "\n" + b).orElse(block);
    }

    private static String indentBlock(String block, String pad) {
        return block.lines().map(l -> l.isEmpty() ? l : pad + l)
            .reduce((a, b) -> a + "\n" + b).orElse(block);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
