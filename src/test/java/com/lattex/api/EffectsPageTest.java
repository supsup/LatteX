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
 * wirer read {@code data-lx-fx-enter}/{@code -hover}/{@code -click},
 * {@code data-lx-fx-duration} and {@code data-lx-fx-glow-color} and play the
 * matching effect on load / hover / click. {@code glow} honours the author's
 * {@code fx.glow-color} (via a CSS custom property, defaulting to
 * {@code currentColor}). {@code lightning} is special-cased: not a keyframe on the
 * element but a page-side, pointer-events-none {@code <canvas>} overlay appended to
 * {@code <body>} on which jagged bolts arc in from the left/right viewport edges and
 * converge on the element — the inner {@code <svg>} is never touched. {@code storm}
 * ("night lightning") extends that idea: it first drops the whole scene to near-black
 * behind a radial "spotlight" backdrop that stays transparent over the still-lit
 * target, then converging bolts strike and each strike FLASHES the dark backdrop
 * bright while the equation glows progressively hotter (white-hot at the climax),
 * before the page restores — all via SEPARATE body overlays, never the {@code <svg>}.
 * {@code prefers-reduced-motion} softens or skips motion. No external
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
        new Fx("\\lx[fx.hover=glow, fx.glow-color=#e0a13a]{ \\zeta(s) }",
            "fx.hover=glow, fx.glow-color=#e0a13a — custom amber halo", "hover me"),
        new Fx("\\lx[fx.hover=lightning]{ \\sum_{k} s_k }",
            "fx.hover=lightning — bolts converge on it from both edges", "hover — lightning converges"),
        new Fx("\\lx[fx.click=lightning, fx.glow-color=#7fd4ff]{ \\nabla \\times E }",
            "fx.click=lightning, fx.glow-color=#7fd4ff — icy confluence on click", "click me"),
        new Fx("\\lx[fx.hover=storm]{ E = mc^2 }",
            "fx.hover=storm — the screen darkens, bolts light it up",
            "hover — night lightning; the equation heats white-hot"),
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
        // glow-color option: stamped on the container + wired to the CSS var the glow
        // keyframe reads (so unset = currentColor, set = the author's colour).
        assertTrue(written.contains("data-lx-fx-glow-color=\"#e0a13a\""),
            "amber glow-color stamped on the container");
        assertTrue(written.contains("--lx-glow-color"), "glow-color wired to the CSS var");
        assertTrue(written.contains("var(--lx-glow-color, currentColor)"),
            "glow keyframe reads the glow-color var, defaulting to currentColor");
        // lightning: parsed + stamped like any effect, and special-cased as a page-side
        // canvas overlay (never a keyframe on / inside the element's SVG).
        assertTrue(written.contains("data-lx-fx-hover=\"lightning\""),
            "lightning stamped as a hover effect");
        assertFalse(written.contains("@keyframes lx-lightning"),
            "lightning is an overlay, NOT a CSS keyframe on the element");
        assertTrue(written.contains("function lightning"), "lightning overlay routine embedded");
        assertTrue(written.contains("createElement('canvas')"),
            "lightning draws on a page-side canvas overlay");
        // storm: night-lightning — parsed + stamped like any effect, special-cased as
        // page-side overlays (dark backdrop + flash veil + bolt canvas), never a
        // keyframe on / inside the element's SVG. Reuses the lightning bolt code.
        assertTrue(written.contains("data-lx-fx-hover=\"storm\""),
            "storm stamped as a hover effect");
        assertFalse(written.contains("@keyframes lx-storm"),
            "storm is an overlay, NOT a CSS keyframe on the element");
        assertTrue(written.contains("function storm"), "storm overlay routine embedded");
        assertTrue(written.contains("radial-gradient(circle"),
            "storm darkens via a radial spotlight backdrop (transparent over the element)");
        assertTrue(written.contains("heatFilter"),
            "storm heats the equation's glow across the strike sequence");
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
          /* glow halo colour = --lx-glow-color when the author set fx.glow-color,
             else currentColor (unset = today's theme-aware-ink behaviour). */
          @keyframes lx-glow {
            0%, 100% { filter: drop-shadow(0 0 0 var(--lx-glow-color, currentColor)); }
            50%      { filter: drop-shadow(0 0 12px var(--lx-glow-color, currentColor)); }
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
          // CSS-keyframe effect vocabulary — mirrors the Effect enum's keyframe half
          // (boom|pulse|fade|glow|none). 'lightning' and 'storm' are special-cased
          // below: they are page-side body overlays, NOT keyframes on the element.
          var VOCAB = { boom: 1, pulse: 1, fade: 1, glow: 1, none: 1 };
          var reduced = window.matchMedia
            && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

          // Resolve the strike/halo colour: the author's fx.glow-color if set (and not
          // the literal 'currentColor', which canvas can't resolve), else the element's
          // computed ink colour so it matches the on-page glyphs.
          function resolveColor(el) {
            var c = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
            if (c && c.toLowerCase() !== 'currentcolor') { return c; }
            var ink = getComputedStyle(el).color;
            return ink || '#e6a24c';
          }

          // A jagged polyline from (x0,y0) to (x1,y1): interior points are pushed off
          // the straight line by a random perpendicular jitter, so every strike differs.
          function jagged(x0, y0, x1, y1, segs, jitter) {
            var dx = x1 - x0, dy = y1 - y0;
            var len = Math.sqrt(dx * dx + dy * dy) || 1;
            var nx = -dy / len, ny = dx / len; // unit normal
            var pts = [];
            for (var i = 0; i <= segs; i++) {
              var t = i / segs;
              var x = x0 + dx * t, y = y0 + dy * t;
              if (i > 0 && i < segs) {
                var off = (Math.random() * 2 - 1) * jitter;
                x += nx * off; y += ny * off;
              }
              pts.push([x, y]);
            }
            return pts;
          }

          function stroke(ctx, pts, upto) {
            var n = Math.max(2, Math.min(pts.length, upto || pts.length));
            if (n < 2) { return; }
            ctx.beginPath();
            ctx.moveTo(pts[0][0], pts[0][1]);
            for (var i = 1; i < n; i++) { ctx.lineTo(pts[i][0], pts[i][1]); }
            ctx.stroke();
          }

          // The confluence: two rivers of lightning arc IN from the left- and right-mid
          // viewport edges and converge on the element's centre, flash, then fade. A
          // lazily-created, pointer-events-none body overlay — never inside the <svg>.
          function lightning(el) {
            var r = el.getBoundingClientRect();
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            var vw = window.innerWidth, vh = window.innerHeight;
            var color = resolveColor(el);
            var sources = [[0, vh / 2], [vw, vh / 2]]; // left-mid + right-mid edges

            var canvas = document.createElement('canvas');
            var dpr = window.devicePixelRatio || 1;
            canvas.width = Math.round(vw * dpr);
            canvas.height = Math.round(vh * dpr);
            canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
              + 'pointer-events:none;z-index:2147483647;';
            document.body.appendChild(canvas);
            var ctx = canvas.getContext('2d');
            ctx.scale(dpr, dpr);

            // Reduced motion: one faint static arc from each side, no flicker, no flash.
            if (reduced) {
              ctx.globalAlpha = 0.45;
              ctx.strokeStyle = color;
              ctx.lineWidth = 2;
              sources.forEach(function (s) { stroke(ctx, jagged(s[0], s[1], cx, cy, 8, 6)); });
              setTimeout(function () { canvas.remove(); }, 400);
              return;
            }

            var DRAW = 180, HOLD = 90, FADE = 350;
            function drawBolts(progress) {
              ctx.clearRect(0, 0, vw, vh);
              ctx.strokeStyle = color;
              ctx.shadowColor = color;
              ctx.shadowBlur = 12;
              sources.forEach(function (s) {
                for (var k = 0; k < 2; k++) { // 2 forked bolts per side
                  var pts = jagged(s[0], s[1], cx, cy, 12, 18 + k * 10);
                  ctx.lineWidth = 2 + k;
                  ctx.globalAlpha = 0.9;
                  stroke(ctx, pts, Math.floor(pts.length * progress));
                }
              });
              if (progress > 0.85) { // bright convergence flash blob
                var rad = 10 + Math.random() * 6;
                var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, rad);
                g.addColorStop(0, color);
                g.addColorStop(1, 'rgba(0,0,0,0)');
                ctx.globalAlpha = 1;
                ctx.fillStyle = g;
                ctx.beginPath(); ctx.arc(cx, cy, rad, 0, Math.PI * 2); ctx.fill();
              }
            }

            var t0 = performance.now();
            function frame(now) {
              var e = now - t0;
              if (e < DRAW) { drawBolts(e / DRAW); requestAnimationFrame(frame); }        // draw in + flicker
              else if (e < DRAW + HOLD) { drawBolts(1); requestAnimationFrame(frame); }   // hold, still re-jagging
              else if (e < DRAW + HOLD + FADE) {                                            // fade the whole overlay
                canvas.style.opacity = String(1 - (e - DRAW - HOLD) / FADE);
                requestAnimationFrame(frame);
              } else { canvas.remove(); }
            }
            requestAnimationFrame(frame);
          }

          // NIGHT LIGHTNING. Reuses the bolt code, but first drops the whole scene to
          // near-black behind a radial "spotlight" that stays transparent over the
          // target (so the equation stays lit), converges bolts on the centre, and on
          // EACH strike FLASHES the dark backdrop bright — like lightning at night —
          // then restores the page. The darken backdrop, the white flash veil and the
          // bolt canvas are three SEPARATE pointer-events-none body overlays; nothing
          // is ever written into the element's <svg> (the containment contract holds).
          function storm(el) {
            var r = el.getBoundingClientRect();
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            var vw = window.innerWidth, vh = window.innerHeight;
            var color = resolveColor(el);
            var sources = [[0, vh / 2], [vw, vh / 2], [cx, 0]]; // left, right, top edges
            // Spotlight radius: clears the element plus a soft margin.
            var spot = Math.max(r.width, r.height) / 2 + 60;

            // (1) DARKEN — a fixed radial backdrop: transparent over the element,
            // near-black at the edges. A CSS var drives the edge darkness so a strike
            // can momentarily lift it. Fades in fast (~150ms).
            var backdrop = document.createElement('div');
            backdrop.style.cssText = 'position:fixed;inset:0;pointer-events:none;'
              + 'z-index:2147483645;opacity:0;transition:opacity 150ms ease;background:'
              + 'radial-gradient(circle ' + spot + 'px at ' + cx + 'px ' + cy + 'px,'
              + ' rgba(2,4,9,0) 0%, rgba(2,4,9,0) 58%,'
              + ' rgba(2,4,9,var(--lx-dark,0.94)) 100%);';
            document.body.appendChild(backdrop);

            // (2) FLASH VEIL — a whitish layer brightest in the dark surround (a
            // transparent hole over the element), pulsed on each strike so the night
            // "lights up" without washing out the spotlit equation.
            var flash = document.createElement('div');
            flash.style.cssText = 'position:fixed;inset:0;pointer-events:none;'
              + 'z-index:2147483646;opacity:0;transition:opacity 70ms ease;background:'
              + 'radial-gradient(circle ' + (spot + 40) + 'px at ' + cx + 'px ' + cy + 'px,'
              + ' rgba(226,238,255,0) 0%, rgba(226,238,255,0) 52%,'
              + ' rgba(226,238,255,0.6) 100%);';
            document.body.appendChild(flash);

            // The target GLOWS HOTTER as bolts charge into it: a heat level 0..1 maps
            // to a growing coloured halo + a white-hot core, warming toward white as
            // it peaks. Biased to the author's glow-color if set, else a heated amber.
            var authored = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
            var heatTint = (authored && authored.toLowerCase() !== 'currentcolor')
              ? color : '#ffb24d';
            function heatFilter(level) {
              var outer = 6 + level * 22;                 // coloured halo: 6 → 28px
              var warm = 4 + level * 16;                  // warm/amber mid layer
              var core = level * 12;                      // white-hot core (0 at rest)
              return 'drop-shadow(0 0 ' + outer + 'px ' + color + ')'
                + ' drop-shadow(0 0 ' + warm + 'px ' + heatTint + ')'
                + ' drop-shadow(0 0 ' + core + 'px rgba(255,255,255,'
                + (0.35 + level * 0.6).toFixed(3) + '))';
            }
            // Smooth the ramp between strike bumps (restored on cleanup).
            var filter0 = el.style.filter, trans0 = el.style.transition;
            el.style.transition = 'filter 170ms ease';
            el.style.filter = heatFilter(0.15); // subtle base glow while the sky darkens

            // (3) The bolt canvas, on top of both overlays.
            var canvas = document.createElement('canvas');
            var dpr = window.devicePixelRatio || 1;
            canvas.width = Math.round(vw * dpr);
            canvas.height = Math.round(vh * dpr);
            canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
              + 'pointer-events:none;z-index:2147483647;';
            document.body.appendChild(canvas);
            var sctx = canvas.getContext('2d');
            sctx.scale(dpr, dpr);

            function cleanup() {
              backdrop.remove(); flash.remove(); canvas.remove();
              el.style.filter = filter0; el.style.transition = trans0;
            }

            // Reduced motion: no darkening flurry, no strobing, no heat ramp. A brief
            // soft dim + one faint static arc + a soft STEADY glow, then restore.
            if (reduced) {
              backdrop.style.setProperty('--lx-dark', '0.5');
              el.style.filter = heatFilter(0.3); // gentle steady glow, no pulsing
              requestAnimationFrame(function () { backdrop.style.opacity = '1'; });
              sctx.globalAlpha = 0.4; sctx.strokeStyle = color; sctx.lineWidth = 2;
              stroke(sctx, jagged(0, vh / 2, cx, cy, 8, 6));
              setTimeout(function () { backdrop.style.opacity = '0'; }, 520);
              setTimeout(cleanup, 950);
              return;
            }

            requestAnimationFrame(function () { backdrop.style.opacity = '1'; });

            // One strike: quickly draw converging jagged bolts to the centre, then at
            // convergence FLASH the backdrop bright (~70ms) — drop its darkness, pulse
            // the white veil, AND heat the equation hotter (level) — before settling
            // back to dark while the equation holds its new, hotter glow.
            function strike(level, done) {
              var DRAW = 130, t0 = performance.now();
              function frame(now) {
                var p = Math.min(1, (now - t0) / DRAW);
                sctx.clearRect(0, 0, vw, vh);
                sctx.strokeStyle = color; sctx.shadowColor = color; sctx.shadowBlur = 14;
                sources.forEach(function (s) {
                  for (var k = 0; k < 2; k++) { // 2 forked bolts per source
                    var pts = jagged(s[0], s[1], cx, cy, 12, 18 + k * 10);
                    sctx.lineWidth = 2 + k; sctx.globalAlpha = 0.9;
                    stroke(sctx, pts, Math.floor(pts.length * p));
                  }
                });
                if (p < 1) { requestAnimationFrame(frame); return; }
                backdrop.style.setProperty('--lx-dark', '0.55'); // night lights up
                flash.style.opacity = '1';
                el.style.filter = heatFilter(level); // charge pulses the math hotter
                setTimeout(function () {
                  backdrop.style.setProperty('--lx-dark', '0.94'); // settle back to dark
                  flash.style.opacity = '0';
                  sctx.clearRect(0, 0, vw, vh);
                  done();
                }, 70);
              }
              requestAnimationFrame(frame);
            }

            // ~3 strikes with a dark gap between; each strikes hotter than the last,
            // peaking (white-hot) at the climax. Then fade the backdrop + canvas out
            // (~400ms), COOL the equation back to normal, and restore cleanly.
            var LEVELS = [0.45, 0.72, 1.0]; // subtle → hot → white-hot climax
            var i = 0;
            function next() {
              if (i >= LEVELS.length) {
                backdrop.style.transition = 'opacity 400ms ease';
                canvas.style.transition = 'opacity 400ms ease';
                el.style.transition = 'filter 400ms ease';
                backdrop.style.opacity = '0'; canvas.style.opacity = '0';
                el.style.filter = heatFilter(0); // cool back down as the storm fades
                setTimeout(cleanup, 420);
                return;
              }
              strike(LEVELS[i++], function () { setTimeout(next, 200); });
            }
            setTimeout(next, 170); // let the darken settle before the first strike
          }

          // Play a trigger's effect. lightning/storm → their overlay routines;
          // everything else is a one-shot CSS keyframe (reset first so it can replay
          // on re-trigger).
          function play(el, name, dur) {
            if (name === 'lightning') { lightning(el); return; }
            if (name === 'storm') { storm(el); return; }
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
              var glowColor = el.getAttribute('data-lx-fx-glow-color');

              // Author-set glow colour drives both the glow keyframe (via the CSS var)
              // and the lightning strike colour.
              if (glowColor) { el.style.setProperty('--lx-glow-color', glowColor); }

              // enter: play once on load.
              if (enter) {
                play(el, enter, dur);
                // enter=fade holds opacity:1 only via the animation's `both` fill; a
                // LATER transform effect (hover/click) reassigns el.style.animation and
                // drops that hold, so the element would revert to its base opacity:0 and
                // vanish. Pin opacity to 1 once the first animation ends so it can't.
                if (enter === 'fade') {
                  el.addEventListener('animationend', function pin() {
                    el.style.opacity = '1';
                    el.removeEventListener('animationend', pin);
                  });
                }
              }
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
