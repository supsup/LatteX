package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/effects.html} — the LatteX {@code \lx} <em>fx effect
 * runtime</em> demo. Every specimen is rendered live through
 * {@link LatteX#renderStyledHtml(String)}, so the page can never drift from the
 * actual emitter/container contract: it is regenerated (and asserted)
 * on every build, exactly like {@link ShowcasePageTest} and
 * {@link S8SpecimenGalleryTest}.
 *
 * <p><strong>Containment contract (the reason this page exists).</strong> The
 * inner {@code <svg>} stays affordance-free — only {@code svg/g/path/rect}, no
 * {@code data-*}/{@code on*}/{@code <script>}/{@code <style>}. All animation
 * rides the PAGE-SIDE runtime keyed off the {@code <span class="lx-math">}
 * container's {@code data-lx-fx-*} attributes. This test asserts that split: the
 * container carries the fx metadata; the extracted SVG carries none of it.
 *
 * <p><strong>Runtime.</strong> A tiny inline stylesheet ({@code @keyframes} for
 * {@code boom}/{@code pulse}/{@code fade}/{@code glow}) plus a small vanilla-JS
 * wirer read {@code data-lx-fx-enter}/{@code -hover}/{@code -click} and
 * {@code data-lx-fx-duration} and play the matching keyframe on load / hover /
 * click. {@code prefers-reduced-motion} softens or skips motion. No external
 * assets; the glyph paths fill with {@code currentColor} so they inherit the
 * page's theme-aware ink (light + dark via {@code prefers-color-scheme}).
 *
 * <p>No parser/layout/emitter code is touched — this is pure presentation +
 * page-side runtime over the public {@code renderStyledHtml} API.
 */
class EffectsPageTest {

    /**
     * One fx specimen: the {@code \lx[…]{…}} source, a human caption, and a
     * short "how to trigger it" hint shown on the card.
     */
    private record Fx(String latex, String caption, String hint) {
    }

    // --- The specimens. Each isolates a trigger×effect so the page reads as a
    // clean matrix (plus one combined finale that wires all three triggers). ---
    private static final List<Fx> SPECIMENS = List.of(
        new Fx("\\lx[fx.enter=fade]{ x^2 }",
            "fx.enter=fade — fades in once on load", "on load · reload to replay"),
        new Fx("\\lx[fx.enter=boom]{ E = mc^2 }",
            "fx.enter=boom — scale-burst on load", "on load · reload to replay"),
        new Fx("\\lx[fx.hover=pulse]{ \\frac{a+b}{c} }",
            "fx.hover=pulse — one scale pulse each hover", "hover me"),
        new Fx("\\lx[fx.hover=glow, fx.duration=600ms]{ e^{-x} }",
            "fx.hover=glow, fx.duration=600ms — slow ink glow", "hover me"),
        new Fx("\\lx[fx.click=boom]{ \\sqrt{x^2 + 1} }",
            "fx.click=boom — scale-punch that settles", "click me"),
        new Fx("\\lx[fx.click=glow]{ \\sum_{i=1}^{n} i }",
            "fx.click=glow — glow pulse on click", "click me"),
        new Fx("\\lx[fx.enter=fade, fx.hover=pulse, fx.click=boom, fx.duration=400ms]"
            + "{ \\int_0^\\infty e^{-x}\\,dx }",
            "all three — fade in, pulse on hover, boom on click", "load · hover · click"));

    @Test
    void writesEffectsPage() throws IOException {
        StringBuilder cards = new StringBuilder();
        int svgCount = 0;

        for (Fx fx : SPECIMENS) {
            String fragment = LatteX.renderStyledHtml(fx.latex());
            svgCount++;

            // --- Containment guard: the fx metadata rides the CONTAINER, and the
            // extracted inner <svg> stays affordance-free (no data-*/on*/script/style). ---
            assertTrue(fragment.startsWith("<span class=\"lx-math\""),
                "fragment is a container span: " + fragment);
            String svg = extractSvg(fragment);
            assertAffordanceFree(fx.latex(), svg);
            // The container really does carry the fx attribute the caption promises.
            assertTrue(fragment.substring(0, fragment.indexOf("<svg")).contains("data-lx-fx-"),
                "container carries a data-lx-fx-* attribute for [" + fx.latex() + "]");

            cards.append(card(fx, fragment)).append('\n');
        }

        String html = page(cards.toString());
        Path out = Path.of("examples", "effects.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        // --- Golden-output assertions ---
        String written = Files.readString(out);
        assertTrue(Files.size(out) > 0, "effects.html non-empty");
        // Count emitter output specifically (`<svg xmlns…`), not the word "svg"
        // in page chrome (the tagline mentions <svg>, a CSS comment does too).
        assertEquals(svgCount, countOccurrences(written, "<svg xmlns"),
            "one rendered SVG per specimen");
        // The runtime is embedded inline (CSS keyframes + the JS wirer).
        assertTrue(written.contains("@keyframes lx-boom"), "boom keyframes embedded");
        assertTrue(written.contains("@keyframes lx-pulse"), "pulse keyframes embedded");
        assertTrue(written.contains("@keyframes lx-fade"), "fade keyframes embedded");
        assertTrue(written.contains("@keyframes lx-glow"), "glow keyframes embedded");
        assertTrue(written.contains("data-lx-fx-enter"), "reads the enter trigger attr");
        assertTrue(written.contains("data-lx-fx-hover"), "reads the hover trigger attr");
        assertTrue(written.contains("data-lx-fx-click"), "reads the click trigger attr");
        assertTrue(written.contains("data-lx-fx-duration"), "reads the duration attr");
        assertTrue(written.contains("prefers-reduced-motion"), "respects reduced motion");
        // Whole-page containment: the ONLY <script>/<style>/data-*/on* live in the
        // trusted <head> runtime — never inside any rendered <svg>. Re-scan each SVG.
        for (String svg : allSvgs(written)) {
            assertAffordanceFree("page svg", svg);
        }
    }

    // -------------------------------------------------------------------------
    // Containment helpers.
    // -------------------------------------------------------------------------

    /** The {@code <svg>…</svg>} substring of a rendered fragment. */
    private static String extractSvg(String fragment) {
        int start = fragment.indexOf("<svg");
        int end = fragment.indexOf("</svg>");
        assertTrue(start >= 0 && end > start, "fragment contains an <svg>…</svg>");
        return fragment.substring(start, end + "</svg>".length());
    }

    /** Asserts a rendered SVG carries no page-side affordances (the L-containment split). */
    private static void assertAffordanceFree(String latex, String svg) {
        String lower = svg.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("data-"), "inner SVG must carry no data-* attr [" + latex + "]");
        assertFalse(lower.contains("<script"), "inner SVG must carry no <script> [" + latex + "]");
        assertFalse(lower.contains("<style"), "inner SVG must carry no <style> [" + latex + "]");
        assertFalse(lower.contains("javascript:"), "inner SVG must carry no javascript: [" + latex + "]");
        assertFalse(Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(svg).find(),
            "inner SVG must carry no on*= handler [" + latex + "]");
    }

    /** Every {@code <svg>…</svg>} block in the page. */
    private static List<String> allSvgs(String html) {
        var list = new java.util.ArrayList<String>();
        // Match emitter output specifically (`<svg xmlns…`) so page-chrome text
        // that merely mentions "<svg>" (a CSS comment, the tagline) isn't scanned.
        for (int i = html.indexOf("<svg xmlns"); i >= 0; i = html.indexOf("<svg xmlns", i + 1)) {
            int end = html.indexOf("</svg>", i);
            list.add(html.substring(i, end + "</svg>".length()));
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // HTML fragments (self-contained, single file, no external assets).
    // -------------------------------------------------------------------------

    private static String card(Fx fx, String fragment) {
        return """
                <figure class="card">
                  <div class="render">
            %s
                  </div>
                  <figcaption>
                    <span class="cap">%s</span>
                    <code class="src">%s</code>
                    <span class="hint">%s</span>
                  </figcaption>
                </figure>""".formatted(
                indent(fragment, "        "), escapeHtml(fx.caption()),
                escapeHtml(fx.latex()), escapeHtml(fx.hint()));
    }

    private static String page(String cards) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LatteX — \\lx fx effect runtime</title>
              %s
              %s
            </head>
            <body>
              <main>

                <section class="hero">
                  <p class="eyebrow">clean-room · pure Java 25 · zero dependencies</p>
                  <h1 class="wordmark">Latte<span class="x">X</span> &middot; fx runtime</h1>
                  <p class="tagline">Every animation rides the trusted
                     <code>&lt;span class="lx-math"&gt;</code> container — the inner
                     <code>&lt;svg&gt;</code> stays affordance-free
                     (<code>svg/g/path/rect</code> only). Effects are pure page-side
                     CSS&nbsp;+&nbsp;a tiny vanilla-JS wirer that reads
                     <code>data-lx-fx-*</code>.</p>
                </section>

                <section class="band">
                  <div class="grid">
            %s
                  </div>
                </section>

                <footer class="foot">
                  <p class="foot-mark">Latte<span class="x">X</span></p>
                  <nav>
                    <a href="showcase.html">Showcase</a>
                    <a href="gallery-specimen.html">Specimen gallery</a>
                    <a href="../README.md">Readme</a>
                  </nav>
                  <p class="foot-fine">Rendered live via renderStyledHtml · effects on the container, never the SVG · reduced-motion aware</p>
                </footer>

              </main>
            </body>
            </html>
            """.formatted(STYLE, SCRIPT, indentBlock(cards, "    "));
    }

    // -------------------------------------------------------------------------
    // Style — page chrome (Ink & Amber, matching the showcase) + the fx runtime
    // keyframes. Kept as a raw constant (never run through String.formatted) so
    // its many literal '%' (keyframe stops) need no doubling.
    // -------------------------------------------------------------------------
    private static final String STYLE = """
        <style>
          :root {
            color-scheme: light dark;
            --bg:#eceef1; --bg-2:#e3e6ea; --panel:#ffffff; --ink:#171a1f;
            --muted:#5b6570; --faint:#8a939d; --line:#d8dce1;
            --accent:#b9761b; --accent-2:#d68a2a; --chip:#f1f3f5; --chip-line:#e0e4e8;
            --shadow: 0 1px 2px rgba(20,24,30,.04), 0 8px 30px rgba(20,24,30,.06);
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg:#0e1116; --bg-2:#0a0d11; --panel:#161a20; --ink:#e9ecf1;
              --muted:#97a1ad; --faint:#6b7480; --line:#262c34;
              --accent:#e6a24c; --accent-2:#f0b96b; --chip:#1b2027; --chip-line:#2a313a;
              --shadow: 0 1px 2px rgba(0,0,0,.3), 0 12px 34px rgba(0,0,0,.45);
            }
          }
          :root[data-theme="light"] {
            color-scheme: light;
            --bg:#eceef1; --bg-2:#e3e6ea; --panel:#ffffff; --ink:#171a1f;
            --muted:#5b6570; --faint:#8a939d; --line:#d8dce1;
            --accent:#b9761b; --accent-2:#d68a2a; --chip:#f1f3f5; --chip-line:#e0e4e8;
            --shadow: 0 1px 2px rgba(20,24,30,.04), 0 8px 30px rgba(20,24,30,.06);
          }
          :root[data-theme="dark"] {
            color-scheme: dark;
            --bg:#0e1116; --bg-2:#0a0d11; --panel:#161a20; --ink:#e9ecf1;
            --muted:#97a1ad; --faint:#6b7480; --line:#262c34;
            --accent:#e6a24c; --accent-2:#f0b96b; --chip:#1b2027; --chip-line:#2a313a;
            --shadow: 0 1px 2px rgba(0,0,0,.3), 0 12px 34px rgba(0,0,0,.45);
          }

          * { box-sizing: border-box; }
          html { -webkit-text-size-adjust: 100%; }
          body {
            margin: 0;
            background: radial-gradient(1200px 620px at 50% -8%, var(--bg) 0%, var(--bg-2) 62%, var(--bg-2) 100%);
            color: var(--ink);
            font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
            line-height: 1.55; -webkit-font-smoothing: antialiased;
          }
          main { max-width: 1080px; margin: 0 auto; padding: 0 1.5rem 6rem; overflow-x: hidden; }
          code { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
          a { color: var(--accent); text-decoration: none; }
          a:hover { text-decoration: underline; }

          /* Rendered glyph SVGs fill with currentColor, so they inherit the
             container's theme-aware ink — dark ink in light mode, light in dark. */
          .render { display: flex; align-items: center; justify-content: center; width: 100%; color: var(--ink); }
          .render svg { display: block; width: auto; height: auto; max-width: 100%; max-height: 96px; }

          /* ---- Hero ---- */
          .hero { text-align: center; padding: 4.5rem 0 2.5rem; }
          .eyebrow {
            font-family: ui-monospace, monospace; font-size: .72rem; letter-spacing: .24em;
            text-transform: uppercase; color: var(--accent); margin: 0 0 1.2rem;
          }
          .wordmark {
            font-size: clamp(2rem, 6vw, 3.2rem); font-weight: 800; letter-spacing: -.03em;
            line-height: 1; margin: 0; color: var(--ink);
          }
          .wordmark .x {
            background: linear-gradient(160deg, var(--accent-2), var(--accent));
            -webkit-background-clip: text; background-clip: text; color: transparent;
          }
          .tagline { max-width: 60ch; margin: 1.1rem auto 0; color: var(--muted); font-size: 1rem; }
          .tagline code {
            font-size: .86em; color: var(--ink); background: var(--chip);
            border: 1px solid var(--chip-line); border-radius: 5px; padding: .04em .3em;
          }

          /* ---- Grid of specimen cards ---- */
          .band { margin-top: 2.5rem; }
          .grid { display: grid; gap: 1rem; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
          .card {
            margin: 0; display: flex; flex-direction: column; gap: 1.25rem;
            background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
            padding: 2rem 1.4rem 1.2rem; box-shadow: var(--shadow);
            transition: border-color .16s ease;
          }
          .card:hover { border-color: var(--accent); }
          /* overflow visible so a boom scale-burst / glow drop-shadow isn't clipped. */
          .card .render { flex: 1; min-height: 110px; overflow: visible; }
          .card figcaption { display: flex; flex-direction: column; gap: .55rem; align-items: flex-start; }
          .card .cap { font-size: .86rem; font-weight: 600; color: var(--ink); }
          .card .src {
            font-size: .72rem; color: var(--muted); background: var(--chip);
            border: 1px solid var(--chip-line); border-radius: 6px; padding: .28rem .5rem;
            max-width: 100%; overflow-x: auto; white-space: nowrap;
          }
          .card .hint {
            font-family: ui-monospace, monospace; font-size: .62rem; letter-spacing: .1em;
            text-transform: uppercase; color: var(--accent); font-weight: 600;
          }

          /* ---- Footer ---- */
          .foot { margin-top: 4rem; padding-top: 2.5rem; border-top: 1px solid var(--line); text-align: center; }
          .foot-mark { font-weight: 800; font-size: 1.25rem; letter-spacing: -.03em; margin: 0 0 1rem; }
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

          /* =====================================================================
             fx EFFECT RUNTIME — page-side, keyed off the container's data-lx-fx-*.
             The animation lives ENTIRELY here + the JS below; the inner <svg> is
             untouched (affordance-free). Transforms need an inline-block box.
             ===================================================================== */
          .lx-math { display: inline-block; transform-origin: center center; }
          /* enter=fade starts hidden so the JS fade-in has somewhere to come from
             (no flash-of-visible before the animation is wired). */
          .lx-math[data-lx-fx-enter="fade"] { opacity: 0; }

          @keyframes lx-boom {
            0%   { transform: scale(1); }
            30%  { transform: scale(1.45); }
            60%  { transform: scale(.92); }
            100% { transform: scale(1); }
          }
          @keyframes lx-pulse {
            0%, 100% { transform: scale(1); }
            50%      { transform: scale(1.14); }
          }
          @keyframes lx-fade {
            0%   { opacity: 0; }
            100% { opacity: 1; }
          }
          /* glow uses currentColor, so the halo inherits the theme-aware ink. */
          @keyframes lx-glow {
            0%, 100% { filter: drop-shadow(0 0 0 currentColor); }
            50%      { filter: drop-shadow(0 0 12px currentColor); }
          }

          @media (prefers-reduced-motion: reduce) {
            /* Soften: no motion. Held-visible states must still be legible. */
            .lx-math { animation: none !important; }
            .lx-math[data-lx-fx-enter="fade"] { opacity: 1; }
          }
        </style>""";

    // -------------------------------------------------------------------------
    // The runtime JS. Vanilla, no libs. Reads the container's data-lx-fx-*
    // attributes and plays the matching keyframe on the right trigger. Kept raw
    // (never String.formatted) — no '%' to double, and the '{' braces stay literal.
    // -------------------------------------------------------------------------
    private static final String SCRIPT = """
        <script>
        (function () {
          // Closed effect vocabulary — mirrors the Effect enum (boom|pulse|fade|glow|none).
          var VOCAB = { boom: 1, pulse: 1, fade: 1, glow: 1, none: 1 };
          var reduced = window.matchMedia
            && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

          // Play a one-shot keyframe, resetting first so it can replay on re-trigger.
          function play(el, name, dur) {
            if (!VOCAB[name] || name === 'none') { return; }
            if (reduced) { return; }
            el.style.animation = 'none';
            void el.offsetWidth; // force reflow so re-assigning replays it
            el.style.animation = 'lx-' + name + ' ' + dur + ' ease both';
          }

          function init() {
            var els = document.querySelectorAll('.lx-math');
            Array.prototype.forEach.call(els, function (el) {
              var enter = el.getAttribute('data-lx-fx-enter');
              var hover = el.getAttribute('data-lx-fx-hover');
              var click = el.getAttribute('data-lx-fx-click');
              var dur = el.getAttribute('data-lx-fx-duration') || '400ms';

              // enter: play once on load.
              if (enter) { play(el, enter, dur); }
              // hover: play on each mouseenter.
              if (hover) {
                el.addEventListener('mouseenter', function () { play(el, hover, dur); });
              }
              // click: play on each click (and show it's interactive).
              if (click) {
                el.style.cursor = 'pointer';
                el.addEventListener('click', function () { play(el, click, dur); });
              }
            });
          }

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
          } else {
            init();
          }
        })();
        </script>""";

    // -------------------------------------------------------------------------
    // Small helpers (mirror ShowcasePageTest / S8SpecimenGalleryTest).
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
