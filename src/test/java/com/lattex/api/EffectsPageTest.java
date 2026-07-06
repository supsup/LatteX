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
        new Fx("\\lx[fx.enter=handscribe]{ e^{i\\pi} + 1 = 0 }",
            "fx.enter=handscribe — the equation writes itself, stroke by stroke",
            "load — watch it ink in"),
        new Fx("\\lx[fx.enter=hologram]{ e^{i\\pi} = -1 }",
            "fx.enter=hologram — flickers in as a cyan wireframe hologram",
            "load — scanlines + chromatic jitter"),
        new Fx("\\lx[fx.enter=neonsign, fx.glow-color=#ff3b6b]{ \\text{OPEN} }",
            "fx.enter=neonsign — buzzes to life, one glyph flickers forever",
            "load — stutters on, then a broken tube keeps flickering"),
        new Fx("\\lx[fx.enter=crystallize]{ \\nabla^2 \\psi = 0 }",
            "fx.enter=crystallize — a frost front freezes the equation in, then sparkles",
            "load — watch it freeze over"),
        new Fx("\\lx[fx.enter=blueprint]{ a^2 + b^2 = c^2 }",
            "fx.enter=blueprint — drafts itself in white linework on a blueprint field",
            "load — watch it draft in"),
        new Fx("\\lx[fx.enter=wobble]{ x^2 + y^2 = r^2 }",
            "fx.enter=wobble — the glyphs jiggle like jelly, rippling left to right",
            "load — watch it wobble"),
        new Fx("\\lx[fx.enter=gravwell]{ \\sum_{k=0}^{n} a_k x^k }",
            "fx.enter=gravwell — click a glyph; its neighbours fall toward it, then snap back",
            "click any glyph — it becomes a gravity well"),
        new Fx("\\lx[fx.enter=matrixrain]{ e^{i\\pi}+1=0 }",
            "fx.enter=matrixrain — green digital rain falls, then decrypts into the equation",
            "load — watch the code rain resolve into math"),
        new Fx("\\lx[fx.click=supernova]{ E = mc^2 }",
            "fx.click=supernova — collapses to a point, detonates, re-condenses from the dust",
            "click me — re-detonates each click"),
        new Fx("\\lx[fx.enter=inkdrop]{ \\int_a^b f(x)\\,dx }",
            "fx.enter=inkdrop — an ink drop falls, splats, and the equation blooms out of it",
            "load — watch it grow out of the ink"),
        new Fx("\\lx[fx.hover=diffusion]{ \\nabla \\cdot \\vec{E} }",
            "fx.hover=diffusion — dissolves into ink-in-water on hover, reassembles on leave",
            "hover — watch it diffuse away · leave — it swirls back"),
        new Fx("\\lx[fx.hover=refraction]{ \\frac{\\sin x}{x} }",
            "fx.hover=refraction — a glassy lens glides under the cursor, bending the glyphs",
            "hover · move across"),
        new Fx("\\lx[fx.click=teleport]{ \\psi(x,t) }",
            "fx.click=teleport — dematerializes into a particle beam, then re-coalesces",
            "click — beam out, then back in"),
        new Fx("\\lx[fx.click=shatter]{ x^2 - y^2 = (x-y)(x+y) }",
            "fx.click=shatter — cracks to glass shards that hang in zero-g, reassembles on the next click",
            "click — shatter · click again — reassemble"),
        new Fx("\\lx[fx.hover=glitch]{ \\det(A - \\lambda I) = 0 }",
            "fx.hover=glitch — the red/cyan channels rip apart, slices shear, then snap back",
            "hover — datamosh"),
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
        // handscribe: parsed + stamped like any effect, special-cased as a page-side
        // stroke-draw routine (NOT a keyframe on the element). It toggles presentation
        // (stroke / fill / stroke-dashoffset) on the EXISTING inner-<svg> paths only.
        assertTrue(written.contains("data-lx-fx-enter=\"handscribe\""),
            "handscribe stamped as an enter effect");
        assertFalse(written.contains("@keyframes lx-handscribe"),
            "handscribe is a JS stroke-draw, NOT a CSS keyframe on the element");
        assertTrue(written.contains("function handscribe"), "handscribe routine embedded");
        assertTrue(written.contains("strokeDashoffset"),
            "handscribe inks glyphs in via stroke-dashoffset");
        // The 6-effect batch (hologram/neonsign/crystallize/blueprint/wobble/gravwell):
        // each is a page-side JS routine (NOT a keyframe on the element), stamped like any
        // effect, touching only the container + existing paths (+ body overlays).
        assertTrue(written.contains("data-lx-fx-enter=\"hologram\""), "hologram stamped");
        assertFalse(written.contains("@keyframes lx-hologram"),
            "hologram is a JS routine, not a keyframe on the element");
        assertTrue(written.contains("function hologram"), "hologram routine embedded");
        assertTrue(written.contains("repeating-linear-gradient(0deg"),
            "hologram lays cyan scanlines via a body overlay");
        assertTrue(written.contains("data-lx-fx-enter=\"neonsign\""), "neonsign stamped");
        assertFalse(written.contains("@keyframes lx-neonsign"),
            "neonsign is a JS glow/flicker routine, not a keyframe");
        assertTrue(written.contains("function neonsign"), "neonsign routine embedded");
        assertTrue(written.contains("broken.style.opacity"),
            "neonsign flickers one existing path (the broken tube)");
        assertTrue(written.contains("data-lx-fx-enter=\"crystallize\""), "crystallize stamped");
        assertFalse(written.contains("@keyframes lx-crystallize"),
            "crystallize is a JS freeze routine, not a keyframe");
        assertTrue(written.contains("function crystallize"), "crystallize routine embedded");
        assertTrue(written.contains("mix-blend-mode:screen"),
            "crystallize sweeps a frosted body-level pane");
        assertTrue(written.contains("data-lx-fx-enter=\"blueprint\""), "blueprint stamped");
        assertFalse(written.contains("@keyframes lx-blueprint"),
            "blueprint is a JS stroke-draw, not a keyframe");
        assertTrue(written.contains("function blueprint"), "blueprint routine embedded");
        assertTrue(written.contains("lx-blueprint"),
            "blueprint flips the container to the .lx-blueprint drafting field");
        assertTrue(written.contains("data-lx-fx-enter=\"wobble\""), "wobble stamped");
        assertFalse(written.contains("@keyframes lx-wobble"),
            "wobble is a JS autonomous jiggle, not a keyframe");
        assertTrue(written.contains("function wobble"), "wobble routine embedded");
        assertTrue(written.contains("wobbleAmp"),
            "wobble drives an autonomous damped rotation jiggle");
        assertTrue(written.contains("data-lx-fx-enter=\"gravwell\""), "gravwell stamped");
        assertFalse(written.contains("@keyframes lx-gravwell"),
            "gravwell is a JS click routine, not a keyframe");
        assertTrue(written.contains("function gravwell"), "gravwell routine embedded");
        assertTrue(written.contains("__lxWellArmed"),
            "gravwell arms click handlers on the glyph paths");
        // batch-2 (matrixrain/supernova/inkdrop/diffusion/refraction/teleport): page-side JS
        // routines (NOT keyframes), stamped like any effect, touching only the container +
        // existing paths (+ body-level canvas/overlay/filter defs).
        assertTrue(written.contains("data-lx-fx-enter=\"matrixrain\""), "matrixrain stamped");
        assertFalse(written.contains("@keyframes lx-matrixrain"),
            "matrixrain is a JS canvas routine, not a keyframe");
        assertTrue(written.contains("function matrixrain"), "matrixrain routine embedded");
        assertTrue(written.contains("アイウエオ"),
            "matrixrain rains katakana/digit columns over the element's box");
        assertTrue(written.contains("data-lx-fx-click=\"supernova\""), "supernova stamped");
        assertFalse(written.contains("@keyframes lx-supernova"),
            "supernova is a JS overlay routine, not a keyframe");
        assertTrue(written.contains("function supernova"), "supernova routine embedded");
        assertTrue(written.contains("createRadialGradient"),
            "supernova draws its detonation flash on a body canvas");
        assertTrue(written.contains("data-lx-fx-enter=\"inkdrop\""), "inkdrop stamped");
        assertFalse(written.contains("@keyframes lx-inkdrop"),
            "inkdrop is a JS splat routine, not a keyframe");
        assertTrue(written.contains("function inkdrop"), "inkdrop routine embedded");
        assertTrue(written.contains("__lxInk"),
            "inkdrop blooms the math out of the settled ink");
        assertTrue(written.contains("data-lx-fx-hover=\"diffusion\""), "diffusion stamped");
        assertFalse(written.contains("@keyframes lx-diffusion"),
            "diffusion is a JS filter routine, not a keyframe");
        assertTrue(written.contains("function diffusion"), "diffusion routine embedded");
        assertTrue(written.contains("feDisplacementMap"),
            "diffusion drives an feDisplacementMap scale to dissolve the glyphs");
        assertTrue(written.contains("data-lx-fx-hover=\"refraction\""), "refraction stamped");
        assertFalse(written.contains("@keyframes lx-refraction"),
            "refraction is a JS pointer-tracked lens, not a keyframe");
        assertTrue(written.contains("function refraction"), "refraction routine embedded");
        assertTrue(written.contains("backdropFilter"),
            "refraction lens reads as glass via a backdrop-filter overlay");
        assertTrue(written.contains("data-lx-fx-click=\"teleport\""), "teleport stamped");
        assertFalse(written.contains("@keyframes lx-teleport"),
            "teleport is a JS beam routine, not a keyframe");
        assertTrue(written.contains("function teleport"), "teleport routine embedded");
        assertTrue(written.contains("globalCompositeOperation = 'lighter'"),
            "teleport blooms its beam particles additively");
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
            """.formatted(styleBlock(), scriptBlock(), indentBlock(cards, "    "));
    }

    // -------------------------------------------------------------------------
    // Style — page chrome (Ink & Amber, matching the showcase) + the fx runtime
    // keyframes. Kept as a raw constant (never run through String.formatted) so
    // its many literal '%' (keyframe stops) need no doubling.
    // -------------------------------------------------------------------------
    private static final String CHROME_CSS = """
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
        """;

    // -------------------------------------------------------------------------
    // The runtime JS. Vanilla, no libs. Reads the container's data-lx-fx-*
    // attributes and plays the matching keyframe on the right trigger. Kept raw
    // (never String.formatted) — no '%' to double, and the '{' braces stay literal.
    // -------------------------------------------------------------------------
    /// The effects.html <style> = demo chrome + the bundled fx layer (LatteX.fxStylesCss()).
    private static String styleBlock() {
        return "<style>\n" + CHROME_CSS + "\n" + com.lattex.api.LatteX.fxStylesCss() + "\n</style>";
    }

    /// The effects.html <script> = the bundled fx runtime (LatteX.fxRuntimeJs()).
    private static String scriptBlock() {
        return "<script>\n" + com.lattex.api.LatteX.fxRuntimeJs() + "\n</script>";
    }

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
