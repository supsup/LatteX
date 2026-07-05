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
          .lx-math[data-lx-fx-enter="inkdrop"] { opacity: 0; } /* JS blooms it out of the ink */

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
            .lx-math[data-lx-fx-enter="inkdrop"] { opacity: 1; }
          }

          /* blueprint: the container becomes an engineer's drafting field — a deep
             cyan-blue ground under a faint white grid. The JS then stroke-draws the
             glyphs in white + fades transient guide marks; under reduced motion this
             static look is all that shows. */
          .lx-math.lx-blueprint {
            color: #eaf3ff;
            padding: .5rem .7rem;
            border-radius: 6px;
            box-shadow: inset 0 0 0 1px rgba(226, 238, 255, .18);
            background:
              repeating-linear-gradient(0deg,  rgba(226,238,255,.10) 0 1px, transparent 1px 14px),
              repeating-linear-gradient(90deg, rgba(226,238,255,.10) 0 1px, transparent 1px 14px),
              linear-gradient(180deg, #0b3a66, #08294a);
          }

          /* hologram: scrolls the scanline body-overlay's gradient (3px period → 6px
             loop). Rides the overlay, never the element — hologram itself is JS-driven. */
          @keyframes lx-holo-scan {
            0%   { background-position: 0 0; }
            100% { background-position: 0 6px; }
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
            // Bolts read as LIGHT, not dark ink — a glowing electric strike against the
            // night. Electric blue-white by default, or the author's glow-color if set;
            // the halo (shadow) glows too so they blaze rather than draw as black cracks.
            var boltColor = (authored && authored.toLowerCase() !== 'currentcolor')
              ? authored : '#eaf3ff';
            var boltGlow = (authored && authored.toLowerCase() !== 'currentcolor')
              ? authored : '#8fc2ff';
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
              sctx.globalAlpha = 0.55; sctx.strokeStyle = boltColor;
              sctx.shadowColor = boltGlow; sctx.shadowBlur = 10; sctx.lineWidth = 2;
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
                sctx.strokeStyle = boltColor; sctx.shadowColor = boltGlow; sctx.shadowBlur = 16;
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

          // The equation writes itself: ink each glyph <path> in stroke-by-stroke,
          // staggered left-to-right, then settle to the filled glyph. Special-cased like
          // lightning/storm — but this one toggles PRESENTATION attributes on the existing
          // inner-<svg> paths (stroke / fill / stroke-dashoffset); it adds NO element to the
          // SVG, so the containment contract is unchanged.
          function handscribe(el) {
            var svg = el.querySelector('svg');
            if (!svg) { return; }
            var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
            if (!paths.length || reduced) { return; } // reduced motion: leave it static
            paths.sort(function (a, b) {
              try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
            });
            var DRAW = 460, FILL = 260, STAGGER = 55;
            paths.forEach(function (p, i) {
              var len;
              try { len = p.getTotalLength() || 100; } catch (e) { len = 100; }
              p.style.stroke = 'currentColor';
              p.style.strokeWidth = '1.5';
              p.style.fill = 'transparent';
              p.style.strokeDasharray = len;
              p.style.strokeDashoffset = len;
              setTimeout(function () {
                p.style.transition = 'stroke-dashoffset ' + DRAW + 'ms ease, fill '
                  + FILL + 'ms ease ' + Math.round(DRAW * 0.6) + 'ms';
                p.style.strokeDashoffset = '0';
                p.style.fill = 'currentColor';
                setTimeout(function () { // settle to a pristine filled glyph
                  p.style.stroke = '';
                  p.style.strokeWidth = '';
                  p.style.strokeDasharray = '';
                  p.style.strokeDashoffset = '';
                  p.style.transition = '';
                }, DRAW + FILL + 120);
              }, i * STAGGER);
            });
          }

          // HOLOGRAM. Flickers in as a cyan wireframe: scanlines, RGB-split jitter,
          // parallax tilt, flicker dropouts, then a subtle idle loop. Tints/filters/
          // transforms the container el and drives a pointer-events-none scanline body
          // overlay; nothing reaches the inner <svg>.
          function hologram(el) {
            if (el._lxHolo) { return; }
            el._lxHolo = true;
            var authored = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
            var tint = (authored && authored.toLowerCase() !== 'currentcolor')
              ? authored : '#5fe8ff';
            el.style.color = tint;
            function aberr(px) {
              return 'drop-shadow(' + px + 'px 0 0 rgba(255,44,120,.5))'
                + ' drop-shadow(' + (-px) + 'px 0 0 rgba(60,220,255,.55))'
                + ' drop-shadow(0 0 7px ' + tint + ')';
            }
            el.style.filter = aberr(1.2);
            var scan = document.createElement('div');
            scan.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'
              + 'mix-blend-mode:screen;background:repeating-linear-gradient(0deg,'
              + ' rgba(95,232,255,.14) 0px, rgba(95,232,255,.14) 1px,'
              + ' transparent 1px, transparent 3px);';
            document.body.appendChild(scan);
            el._lxHolo = scan;
            function place() {
              var r = el.getBoundingClientRect();
              scan.style.left = r.left + 'px'; scan.style.top = r.top + 'px';
              scan.style.width = r.width + 'px'; scan.style.height = r.height + 'px';
            }
            place();
            window.addEventListener('scroll', place, true);
            window.addEventListener('resize', place);
            if (reduced) { return; }
            scan.style.animation = 'lx-holo-scan 1.1s linear infinite';
            el.style.transition = 'transform 1400ms ease-in-out, opacity 90ms linear';
            var seq = ['0', '1', '.2', '1', '.5', '1'], j = 0;
            (function boot() {
              if (j < seq.length) { el.style.opacity = seq[j++]; setTimeout(boot, 55); return; }
              var dir = 1;
              setInterval(function () {
                place();
                dir = -dir;
                el.style.transform = 'perspective(440px) rotateY(' + (dir * 6) + 'deg)';
                if (Math.random() < 0.5) {
                  el.style.filter = aberr(3 + Math.random() * 3);
                  setTimeout(function () { el.style.filter = aberr(1.2); }, 90);
                }
                if (Math.random() < 0.28) {
                  el.style.opacity = '.4';
                  setTimeout(function () { el.style.opacity = '1'; }, 70);
                }
              }, 900);
            })();
          }

          // NEON SIGN. Buzzes to life: failed stuttering ignitions, then a steady hum —
          // one glyph left flickering forever. Drives a drop-shadow bloom + opacity on the
          // container and toggles opacity on ONE existing inner-<svg> path; no <svg> edits.
          function neonsign(el) {
            var color = resolveColor(el);
            function glow(on) {
              var core = 2 + on * 1;
              var near = 4 + on * 5;
              var halo = 6 + on * 13;
              return 'drop-shadow(0 0 ' + core + 'px rgba(255,255,255,'
                  + (0.3 + on * 0.5).toFixed(3) + '))'
                + ' drop-shadow(0 0 ' + near + 'px rgba(255,255,255,'
                  + (on * 0.35).toFixed(3) + '))'
                + ' drop-shadow(0 0 ' + halo + 'px ' + color + ')';
            }
            if (reduced) { el.style.filter = glow(1); return; }
            el.style.transition = 'filter 40ms linear, opacity 40ms linear';
            var seq = [
              [0,   0.35, 1],
              [70,  0,    0.25],
              [150, 0.8,  1],
              [210, 0,    0.25],
              [340, 0.5,  1],
              [430, 1,    1]
            ];
            seq.forEach(function (s) {
              setTimeout(function () {
                el.style.opacity = String(s[2]);
                el.style.filter = glow(s[1]);
              }, s[0]);
            });
            var paths = el.querySelectorAll('svg path');
            if (!paths.length) { return; }
            var broken = paths[Math.floor(paths.length / 2)];
            broken.style.transition = 'opacity 60ms linear';
            function flicker() {
              var dropout = Math.random() < 0.35;
              broken.style.opacity = dropout
                ? (Math.random() < 0.5 ? '0.15' : '0.6') : '1';
              var next = dropout ? 40 + Math.random() * 90 : 300 + Math.random() * 1500;
              setTimeout(flicker, next);
            }
            setTimeout(flicker, 600);
          }

          // CRYSTALLIZE. A frost front sweeps across (a body-level frost pane, clip-swept),
          // an icy blue-white tint fits over the glyphs (filter on el), then sparkle motes
          // pop. Filter/clip on el + separate body overlays; no element into the inner <svg>.
          function crystallize(el) {
            var svg = el.querySelector('svg');
            if (!svg) { return; }
            var gleam = resolveColor(el);
            var icy = 'brightness(1.14) saturate(0.6) sepia(0.18) hue-rotate(165deg)';
            var trans0 = el.style.transition;
            if (reduced) {
              el.style.filter = icy + ' drop-shadow(0 0 4px #cfeaff)';
              return;
            }
            var r = el.getBoundingClientRect();
            var pane = document.createElement('div');
            pane.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'
              + 'left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;'
              + 'height:' + r.height + 'px;opacity:0.85;mix-blend-mode:screen;'
              + 'clip-path:inset(0 100% 0 0);transition:clip-path 900ms ease;'
              + 'background:linear-gradient(115deg, rgba(207,234,255,0.55) 0%,'
              + ' rgba(255,255,255,0.35) 45%, rgba(160,205,245,0.5) 100%),'
              + ' repeating-linear-gradient(60deg, rgba(255,255,255,0.12) 0 6px,'
              + ' rgba(255,255,255,0) 6px 13px);';
            document.body.appendChild(pane);
            el.style.transition = 'filter 900ms ease';
            el.style.filter = icy + ' blur(1.4px) drop-shadow(0 0 5px ' + gleam + ')';
            requestAnimationFrame(function () {
              pane.style.clipPath = 'inset(0 0 0 0)';
              el.style.filter = icy + ' blur(0px) drop-shadow(0 0 6px #d6f0ff)';
            });
            var motes = [];
            for (var i = 0; i < 7; i++) {
              (function (i) {
                var s = document.createElement('div');
                var sz = 3 + Math.random() * 3;
                var px = r.left + Math.random() * r.width;
                var py = r.top + Math.random() * r.height;
                s.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;'
                  + 'left:' + px + 'px;top:' + py + 'px;width:' + sz + 'px;height:' + sz
                  + 'px;margin:' + (-sz / 2) + 'px 0 0 ' + (-sz / 2) + 'px;border-radius:50%;'
                  + 'background:#fff;box-shadow:0 0 6px 2px #cfeaff;opacity:0;'
                  + 'transform:scale(0.2);transition:transform 260ms ease, opacity 260ms ease;';
                document.body.appendChild(s);
                motes.push(s);
                setTimeout(function () {
                  s.style.opacity = '1'; s.style.transform = 'scale(1.3)';
                  setTimeout(function () {
                    s.style.opacity = '0'; s.style.transform = 'scale(0.4)';
                  }, 200);
                }, 620 + i * 55);
              })(i);
            }
            setTimeout(function () {
              pane.remove();
              motes.forEach(function (m) { m.remove(); });
              el.style.transition = 'filter 500ms ease';
              el.style.filter = 'brightness(1.05) saturate(0.85) drop-shadow(0 0 3px #cfeaff)';
              setTimeout(function () { el.style.transition = trans0; }, 520);
            }, 1300);
          }

          // BLUEPRINT. The container flips to a cyan drafting field (.lx-blueprint), the
          // glyphs stroke-draw in white linework, and a body-level guide overlay (frame,
          // tangent circles, centre lines) fades as they resolve. Toggles presentation on
          // the existing paths + a separate body overlay; no element into the inner <svg>.
          function blueprint(el) {
            el.classList.add('lx-blueprint');
            var svg = el.querySelector('svg');
            var paths = svg
              ? Array.prototype.slice.call(svg.querySelectorAll('path'))
              : [];
            var WHITE = '#eaf3ff';
            if (reduced) {
              paths.forEach(function (p) { p.style.fill = WHITE; });
              return;
            }
            var r = el.getBoundingClientRect();
            var w = r.width, h = r.height, cx = w / 2, cy = h / 2;
            var rad = Math.min(w, h) / 2;
            var guide = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            guide.setAttribute('width', w); guide.setAttribute('height', h);
            guide.setAttribute('fill', 'none');
            guide.setAttribute('stroke', 'rgba(226,238,255,0.55)');
            guide.setAttribute('stroke-width', '1');
            guide.style.cssText = 'position:fixed;pointer-events:none;overflow:visible;'
              + 'z-index:2147483647;opacity:0;transition:opacity 300ms ease;'
              + 'left:' + r.left + 'px;top:' + r.top + 'px;'
              + 'width:' + w + 'px;height:' + h + 'px;';
            guide.innerHTML =
              '<rect x="1" y="1" width="' + (w - 2) + '" height="' + (h - 2)
                + '" stroke-dasharray="4 4"/>'
              + '<circle cx="' + cx + '" cy="' + cy + '" r="' + rad + '"/>'
              + '<circle cx="' + cx + '" cy="' + cy + '" r="' + (rad * 0.62) + '"/>'
              + '<line x1="0" y1="' + cy + '" x2="' + w + '" y2="' + cy + '"/>'
              + '<line x1="' + cx + '" y1="0" x2="' + cx + '" y2="' + h + '"/>';
            document.body.appendChild(guide);
            requestAnimationFrame(function () { guide.style.opacity = '1'; });
            var DRAW = 620, STAGGER = 60, last = 0;
            paths.sort(function (a, b) {
              try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
            });
            paths.forEach(function (p, i) {
              var len;
              try { len = p.getTotalLength() || 100; } catch (e) { len = 100; }
              var delay = i * STAGGER;
              if (delay > last) { last = delay; }
              p.style.stroke = WHITE;
              p.style.strokeWidth = '1.2';
              p.style.fill = 'none';
              p.style.strokeDasharray = len;
              p.style.strokeDashoffset = len;
              setTimeout(function () {
                p.style.transition = 'stroke-dashoffset ' + DRAW
                  + 'ms ease, fill 300ms ease ' + DRAW + 'ms';
                p.style.strokeDashoffset = '0';
                p.style.fill = WHITE;
              }, delay);
            });
            var total = last + DRAW + 300;
            setTimeout(function () { guide.style.opacity = '0'; }, Math.max(0, total - 260));
            setTimeout(function () { guide.remove(); }, total + 120);
          }

          // WOBBLE. On its trigger the glyphs jiggle like jelly: each does an AUTONOMOUS
          // damped VERTICAL bob decaying over ~1.4s, staggered left-to-right so it ripples
          // across the equation. Pure translateY (origin-independent) — NO rotation (which
          // read as an eraser sweep) and NO transform-box (which left a stray horizontal line);
          // it toggles style.transform on the existing paths only — nothing touches the <svg>.
          function wobble(el) {
            if (reduced) { return; }
            var svg = el.querySelector('svg');
            if (!svg) { return; }
            var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
            if (!paths.length) { return; }
            paths.sort(function (a, b) {          // left-to-right so the ripple reads
              try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
            });
            var DUR = 1400; // ms of wobble per glyph
            paths.forEach(function (p, i) {
              setTimeout(function () {                     // stagger → the ripple
                var wobbleAmp = 5 + Math.random() * 2.5;   // peak PX of bounce
                var freq = 5 + Math.random() * 2;          // oscillations over the life
                var t0 = performance.now();
                function step(now) {
                  var t = (now - t0) / DUR;                // 0..1
                  if (t >= 1) { p.style.transform = ''; return; }   // pristine at rest
                  var env = (1 - t) * (1 - t);             // ease-out decay envelope
                  var ty = -Math.sin(t * freq * Math.PI * 2) * wobbleAmp * env;
                  p.style.transform = 'translateY(' + ty.toFixed(2) + 'px)';
                  requestAnimationFrame(step);
                }
                requestAnimationFrame(step);
              }, i * 50);
            });
          }

          // GRAVWELL. Armed on enter: wire a click listener onto each glyph path. On click,
          // the source glyph becomes a gravity well — every other glyph SPIRALS in toward it
          // (radius collapsing as the angle sweeps) and SHRINKS + spins as it falls, then
          // unwinds back out. rAF-driven; only toggles transform on the existing paths (set
          // while animating, cleared at rest); no element into the inner <svg>.
          function gravwell(el) {
            var svg = el.querySelector('svg');
            if (!svg || reduced) { return; }
            if (el.__lxWellArmed) { return; }
            el.__lxWellArmed = true;
            var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
            if (paths.length < 2) { return; }
            function centre(p) {
              var b;
              try { b = p.getBBox(); } catch (e) { return null; }
              return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
            }
            var R0 = 46;       // reach: glyphs within ~this radius feel the well
            var SWIRL = 3.4;   // radians of spiral swept on the way in
            var SHRINK = 0.82; // how small a fully-pulled glyph gets
            var IN = 640, HOLD = 130, BACK = 560;
            var raf = 0;
            paths.forEach(function (src) {
              src.style.cursor = 'pointer';
              src.addEventListener('click', function () {
                var s = centre(src);
                if (!s) { return; }
                if (raf) { cancelAnimationFrame(raf); raf = 0; }
                // Capture each non-source glyph's start polar coords about the well.
                var movers = [];
                paths.forEach(function (p) {
                  if (p === src) { return; }
                  var g = centre(p); if (!g) { return; }
                  var dx = g.x - s.x, dy = g.y - s.y;          // well → glyph
                  var r0 = Math.sqrt(dx * dx + dy * dy); if (r0 < 1e-3) { return; }
                  var fall = Math.min(1, (R0 * R0) / (r0 * r0));
                  if (fall < 0.03) { return; }                 // too far to matter
                  p.style.transformBox = 'fill-box';
                  p.style.transformOrigin = 'center';
                  p.style.transition = '';                     // rAF drives it, no CSS tween
                  movers.push({ p: p, gx: g.x, gy: g.y, r0: r0,
                    th0: Math.atan2(dy, dx), fall: fall });
                });
                if (!movers.length) { return; }
                // Once the glyphs have fallen in, the SOURCE collapses into an eclipse orb —
                // a dark disc with a glowing corona — parked over the source glyph (a body-
                // level overlay; the source path is dimmed beneath it), released on unwind.
                var sr = src.getBoundingClientRect();
                var osz = Math.max(15, Math.max(sr.width, sr.height) * 1.15);
                function makeOrb() {
                  if (el.__lxOrb) { return; }
                  var o = document.createElement('div');
                  o.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'
                    + 'border-radius:50%;left:' + (sr.left + sr.width / 2 - osz / 2) + 'px;'
                    + 'top:' + (sr.top + sr.height / 2 - osz / 2) + 'px;'
                    + 'width:' + osz + 'px;height:' + osz + 'px;opacity:0;transform:scale(.2);'
                    + 'background:radial-gradient(circle,#000 54%,rgba(0,0,0,.35) 66%,transparent 73%);'
                    + 'box-shadow:0 0 14px 3px rgba(255,238,190,.95),0 0 36px 11px rgba(255,175,80,.5);'
                    + 'transition:opacity 200ms ease,transform 280ms cubic-bezier(.2,1.35,.4,1);';
                  document.body.appendChild(o);
                  el.__lxOrb = o;
                  src.style.transition = 'opacity 200ms ease';
                  src.style.opacity = '0.06';                  // the glyph collapses into the orb
                  requestAnimationFrame(function () {
                    if (el.__lxOrb === o) { o.style.opacity = '1'; o.style.transform = 'scale(1)'; }
                  });
                }
                function killOrb() {
                  var o = el.__lxOrb;
                  if (!o) { return; }
                  el.__lxOrb = null;
                  o.style.opacity = '0'; o.style.transform = 'scale(.2)';
                  src.style.opacity = '';                      // the source glyph returns
                  setTimeout(function () { if (o.parentNode) { o.parentNode.removeChild(o); } }, 300);
                }
                killOrb();                                     // clear any lingering orb on re-click
                var t0 = performance.now();
                function frame(now) {
                  var e = now - t0, k;
                  if (e < IN) { var u = e / IN; k = u * u * (3 - 2 * u); }   // ease in to 1
                  else if (e < IN + HOLD) { k = 1; makeOrb(); }             // eclipse orb forms
                  else {
                    var d = (e - IN - HOLD) / BACK;
                    killOrb();                                 // release: the orb dissolves
                    if (d >= 1) {                              // done → pristine at rest
                      movers.forEach(function (m) {
                        m.p.style.transform = '';
                        m.p.style.transformBox = '';
                        m.p.style.transformOrigin = '';
                      });
                      raf = 0; return;
                    }
                    k = 1 - d * d * (3 - 2 * d);               // unwind back out
                  }
                  movers.forEach(function (m) {
                    var pull = m.fall * k;                     // 0..1 depth into the well
                    var r = m.r0 * (1 - 0.92 * pull);          // radius collapses inward
                    var th = m.th0 + SWIRL * pull;             // angle sweeps → the spiral
                    var tx = (s.x + r * Math.cos(th)) - m.gx;  // offset from the glyph's rest
                    var ty = (s.y + r * Math.sin(th)) - m.gy;
                    var sc = 1 - SHRINK * pull;                // shrinks as it spirals in
                    var spin = SWIRL * pull * 57.2958;         // the glyph spins with the swirl
                    m.p.style.transform = 'translate(' + tx.toFixed(2) + 'px,' + ty.toFixed(2)
                      + 'px) rotate(' + spin.toFixed(1) + 'deg) scale(' + sc.toFixed(3) + ')';
                  });
                  raf = requestAnimationFrame(frame);
                }
                raf = requestAnimationFrame(frame);
              });
            });
          }

          // MATRIXRAIN. "Digital rain" falls DOWN — but BEHIND the equation, which stays fully
          // visible in front. A child <canvas> at z-index:-1 covers the element's box (with
          // headroom above so streams fall INTO it); the rain is tinted to the equation's OWN
          // ink colour (matches the theme) — an author's fx.glow-color overrides. A
          // destination-out fade tapers each column to TRANSPARENT (no dark box behind the math).
          function matrixrain(el) {
            if (reduced) { return; }
            var svg = el.querySelector('svg');
            if (!svg) { return; }
            var authored = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
            var ink = (authored && authored.toLowerCase() !== 'currentcolor')
              ? authored : resolveColor(el);
            var r = el.getBoundingClientRect();
            if (!r.width || !r.height) { return; }
            var pos0 = el.style.position;
            if (getComputedStyle(el).position === 'static') { el.style.position = 'relative'; }
            var padTop = Math.round(r.height * 1.4);   // headroom so streams fall INTO the box
            var W = r.width, H = r.height + padTop;
            var canvas = document.createElement('canvas');
            var dpr = window.devicePixelRatio || 1;
            canvas.width = Math.round(W * dpr);
            canvas.height = Math.round(H * dpr);
            canvas.style.cssText = 'position:absolute;pointer-events:none;z-index:-1;'
              + 'left:0;top:' + (-padTop) + 'px;width:' + W + 'px;height:' + H + 'px;'
              + 'opacity:0;transition:opacity 400ms ease;';
            el.appendChild(canvas);                    // BEHIND the glyphs (z-index:-1)
            var ctx = canvas.getContext('2d');
            ctx.scale(dpr, dpr);
            requestAnimationFrame(function () { canvas.style.opacity = '1'; });
            var GLYPHS = ('アイウエオカキクケコサシスセソタチツテトナニヌネノ'
              + '0123456789=+-<>{}πΔΣ√').split('');
            function rnd() { return GLYPHS[(Math.random() * GLYPHS.length) | 0]; }
            var FONT = 14;
            ctx.font = FONT + 'px ui-monospace, "SF Mono", Menlo, monospace';
            ctx.textBaseline = 'top';
            var cols = Math.max(1, Math.floor(W / (FONT * 0.72)));
            var colW = W / cols;
            var rows = Math.max(1, Math.ceil(H / FONT) + 1);
            var drops = [];
            for (var c = 0; c < cols; c++) {
              drops.push({ y: -Math.floor(Math.random() * rows),
                v: 0.30 + Math.random() * 0.45, lastRow: -999 });
            }
            var raf = 0, timers = [], done = false;
            function stop() {
              if (done) { return; }
              done = true;
              if (raf) { cancelAnimationFrame(raf); raf = 0; }
              timers.forEach(clearTimeout); timers.length = 0;
              canvas.remove();
              el.style.position = pos0;
            }
            var t0 = performance.now(), RAIN = 2000;
            function frame(now) {
              if (done) { return; }
              ctx.globalCompositeOperation = 'destination-out';   // fade the tail to TRANSPARENT
              ctx.globalAlpha = 1;
              ctx.fillStyle = 'rgba(0,0,0,0.10)';
              ctx.fillRect(0, 0, W, H);
              ctx.globalCompositeOperation = 'source-over';
              ctx.globalAlpha = 0.92;
              ctx.fillStyle = ink;
              for (var c = 0; c < cols; c++) {
                var d = drops[c], row = Math.floor(d.y);
                if (row !== d.lastRow && row >= 0) {              // new glyph only on row advance
                  ctx.fillText(rnd(), c * colW, row * FONT);
                  d.lastRow = row;
                }
                d.y += d.v;
                if (row * FONT > H && Math.random() > 0.975) {
                  d.y = -1; d.lastRow = -999; d.v = 0.30 + Math.random() * 0.45;
                }
              }
              if (now - t0 < RAIN) { raf = requestAnimationFrame(frame); return; }
              raf = 0;
              canvas.style.opacity = '0';
              timers.push(setTimeout(stop, 440));
            }
            raf = requestAnimationFrame(frame);
          }

          // SUPERNOVA. Click to detonate: the equation COLLAPSES to a hot point, then
          // DETONATES on a body <canvas> — radial flash + expanding shockwave ring + stardust
          // flung with gravity/drag + fading trails — then RE-CONDENSES from the dust.
          function supernova(el) {
            if (el.__lxNova) { return; }
            el.__lxNova = true;
            var r = el.getBoundingClientRect();
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            var color = resolveColor(el);
            var palette = ['#ffffff', '#ffe9b0', '#ffab4d', color, '#7fd4ff'];
            var trans0 = el.style.transition, tf0 = el.style.transform, fil0 = el.style.filter;
            function restore() {
              el.__lxNova = false;
              el.style.transition = trans0; el.style.transform = tf0; el.style.filter = fil0;
            }
            if (reduced) {
              var veil = document.createElement('div');
              veil.style.cssText = 'position:fixed;inset:0;pointer-events:none;'
                + 'z-index:2147483647;opacity:0;transition:opacity 120ms ease;background:'
                + 'radial-gradient(circle 120px at ' + cx + 'px ' + cy + 'px,'
                + ' rgba(255,255,255,0.7) 0%, rgba(255,255,255,0) 70%);';
              document.body.appendChild(veil);
              requestAnimationFrame(function () { veil.style.opacity = '1'; });
              setTimeout(function () { veil.style.opacity = '0'; }, 130);
              setTimeout(function () { veil.remove(); el.__lxNova = false; }, 340);
              return;
            }
            el.style.transition = 'transform 170ms cubic-bezier(.6,0,.9,.2), filter 170ms ease';
            el.style.transform = 'scale(0.1)';
            el.style.filter = 'brightness(2.6) drop-shadow(0 0 10px ' + color + ')';
            var vw = window.innerWidth, vh = window.innerHeight;
            var dpr = window.devicePixelRatio || 1;
            var canvas = document.createElement('canvas');
            canvas.width = Math.round(vw * dpr);
            canvas.height = Math.round(vh * dpr);
            canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
              + 'pointer-events:none;z-index:2147483647;';
            document.body.appendChild(canvas);
            var ctx = canvas.getContext('2d');
            ctx.scale(dpr, dpr);
            var N = 64, parts = [];
            for (var i = 0; i < N; i++) {
              var ang = (i / N) * Math.PI * 2 + Math.random() * 0.35;
              var speed = 120 + Math.random() * 340;
              parts.push({ x: cx, y: cy, px: cx, py: cy,
                vx: Math.cos(ang) * speed, vy: Math.sin(ang) * speed,
                life: 1, decay: 0.6 + Math.random() * 0.7,
                col: palette[(Math.random() * palette.length) | 0],
                size: 1 + Math.random() * 2 });
            }
            var GRAV = 220, DRAG = 0.86;
            var LIFE = 900, DETONATE = 170;
            var raf = 0, timers = [], t0 = 0, lastT = 0;
            function cleanup() {
              if (raf) { cancelAnimationFrame(raf); raf = 0; }
              timers.forEach(clearTimeout); timers.length = 0;
              canvas.remove(); restore();
            }
            function frame(now) {
              var e = now - t0;
              var dt = lastT ? Math.min(0.05, (now - lastT) / 1000) : 0.016;
              lastT = now;
              ctx.clearRect(0, 0, vw, vh);
              var fa = Math.max(0, 1 - e / 180);
              if (fa > 0) {
                var fr = 60 + e * 0.5;
                var fg = ctx.createRadialGradient(cx, cy, 0, cx, cy, fr);
                fg.addColorStop(0, 'rgba(255,255,255,' + (0.95 * fa).toFixed(3) + ')');
                fg.addColorStop(0.4, 'rgba(255,220,150,' + (0.6 * fa).toFixed(3) + ')');
                fg.addColorStop(1, 'rgba(255,160,60,0)');
                ctx.fillStyle = fg;
                ctx.beginPath(); ctx.arc(cx, cy, fr, 0, Math.PI * 2); ctx.fill();
              }
              var sa = Math.max(0, 1 - e / 520);
              if (sa > 0) {
                ctx.strokeStyle = 'rgba(255,210,150,' + (0.8 * sa).toFixed(3) + ')';
                ctx.lineWidth = 1 + 5 * sa;
                ctx.beginPath(); ctx.arc(cx, cy, e * 0.9, 0, Math.PI * 2); ctx.stroke();
              }
              for (var i = 0; i < parts.length; i++) {
                var p = parts[i];
                if (p.life <= 0) { continue; }
                p.px = p.x; p.py = p.y;
                p.vy += GRAV * dt;
                var d = Math.pow(DRAG, dt * 60);
                p.vx *= d; p.vy *= d;
                p.x += p.vx * dt; p.y += p.vy * dt;
                p.life -= p.decay * dt;
                ctx.strokeStyle = p.col;
                ctx.globalAlpha = Math.max(0, p.life);
                ctx.lineWidth = p.size;
                ctx.beginPath(); ctx.moveTo(p.px, p.py); ctx.lineTo(p.x, p.y); ctx.stroke();
              }
              ctx.globalAlpha = 1;
              if (e < LIFE) { raf = requestAnimationFrame(frame); } else { cleanup(); }
            }
            timers.push(setTimeout(function () {
              el.style.transition = 'transform 720ms cubic-bezier(.16,1.4,.3,1), filter 640ms ease';
              el.style.transform = tf0 || 'scale(1)';
              el.style.filter = fil0;
              t0 = performance.now();
              raf = requestAnimationFrame(frame);
            }, DETONATE));
          }

          // INKDROP. The equation grows out of a falling ink splat: a dark drop falls, hits
          // the centre, splats into an irregular blot flinging spatter, and the glyphs bloom
          // up out of the settled ink. Body-level drop/splat/spatter overlays, removed on cleanup.
          function inkdrop(el) {
            if (el.__lxInk) { return; }
            el.__lxInk = true;
            var ink = resolveColor(el);
            var trans0 = el.style.transition, to0 = el.style.transformOrigin,
                pos0 = el.style.position, z0 = el.style.zIndex, tf0 = el.style.transform;
            if (reduced) { el.style.opacity = '1'; return; }
            var r = el.getBoundingClientRect();
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            var timers = [];
            function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
            el.style.opacity = '0';
            el.style.position = 'relative';
            el.style.zIndex = '2147483647';
            el.style.transformOrigin = (r.width / 2) + 'px ' + r.height + 'px';
            el.style.transform = 'scale(.32)';
            var FALL = 84, DROP = 260, SPLAT = 210, BLOOM = 440;
            var dSize = 15;
            var drop = document.createElement('div');
            drop.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;background:' + ink
              + ';left:' + (cx - dSize / 2) + 'px;top:' + (cy - dSize / 2 - FALL) + 'px;'
              + 'width:' + dSize + 'px;height:' + dSize + 'px;opacity:.94;'
              + 'border-radius:62% 62% 60% 60% / 82% 82% 54% 54%;transform:scale(.7,1.15);'
              + 'transition:transform ' + DROP + 'ms cubic-bezier(.55,.06,.9,.35);';
            document.body.appendChild(drop);
            var bW = Math.max(30, Math.min(r.width * 0.9, 130)), bH = bW * 0.66;
            var splat = document.createElement('div');
            splat.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483645;background:' + ink
              + ';left:' + cx + 'px;top:' + cy + 'px;width:' + bW + 'px;height:' + bH + 'px;'
              + 'margin:' + (-bH / 2) + 'px 0 0 ' + (-bW / 2) + 'px;opacity:0;'
              + 'border-radius:46% 54% 62% 38% / 58% 44% 56% 42%;transform:scale(0);'
              + 'transition:transform ' + SPLAT + 'ms cubic-bezier(.2,1.35,.4,1), opacity 180ms ease;';
            document.body.appendChild(splat);
            var spatter = [];
            requestAnimationFrame(function () {
              drop.style.transform = 'translateY(' + FALL + 'px) scale(1,.82)';
            });
            later(function () {
              drop.style.opacity = '0';
              splat.style.opacity = '1';
              splat.style.transform = 'scale(1)';
              for (var i = 0; i < 5; i++) {
                (function (i) {
                  var ang = Math.PI * 2 * (i / 5) + Math.random() * 0.8;
                  var dist = 16 + Math.random() * 24, sz = 3 + Math.random() * 4;
                  var d = document.createElement('div');
                  d.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483645;background:' + ink
                    + ';left:' + cx + 'px;top:' + cy + 'px;width:' + sz + 'px;height:' + sz + 'px;'
                    + 'margin:' + (-sz / 2) + 'px 0 0 ' + (-sz / 2) + 'px;border-radius:50%;opacity:.85;'
                    + 'transition:transform 300ms cubic-bezier(.15,.7,.3,1), opacity 520ms ease;';
                  document.body.appendChild(d);
                  spatter.push(d);
                  requestAnimationFrame(function () {
                    d.style.transform = 'translate(' + (Math.cos(ang) * dist).toFixed(1) + 'px,'
                      + (Math.sin(ang) * dist).toFixed(1) + 'px) scale('
                      + (0.4 + Math.random() * 0.6).toFixed(2) + ')';
                  });
                })(i);
              }
            }, DROP);
            later(function () {
              el.style.transition = 'opacity ' + BLOOM + 'ms ease, transform '
                + BLOOM + 'ms cubic-bezier(.2,1.12,.35,1)';
              el.style.opacity = '1';
              el.style.transform = 'scale(1)';
              splat.style.transition = 'opacity ' + BLOOM + 'ms ease, transform ' + BLOOM + 'ms ease';
              splat.style.transform = 'scale(1.18)';
              splat.style.opacity = '0';
              spatter.forEach(function (d) { d.style.opacity = '0'; });
            }, DROP + SPLAT);
            later(function () {
              timers.length = 0;
              drop.remove(); splat.remove();
              spatter.forEach(function (d) { d.remove(); });
              el.style.transition = trans0; el.style.transform = tf0;
              el.style.transformOrigin = to0; el.style.position = pos0; el.style.zIndex = z0;
            }, DROP + SPLAT + BLOOM + 140);
          }

          // A module-scoped counter so each element's diffusion filter gets a unique id
          // (multiple specimens on the page must not share one <filter>).
          var __lxDiffuseSeq = 0;

          // DIFFUSION. On hover the equation dissolves into ink-in-water — a turbulent
          // spreading blur — and reassembles on leave (reversible). A runtime body-level
          // hidden <svg> holds an feTurbulence→feDisplacementMap filter; el.style.filter
          // points at it and a single rAF loop drives the map's scale + opacity; removed idle.
          function diffusion(el) {
            if (reduced) { return; }
            var st = el.__lxDiffuse;
            if (!st) {
              st = el.__lxDiffuse =
                { p: 0, dir: 1, raf: 0, last: 0, holder: null, map: null };
              el.addEventListener('mouseleave', function () {
                st.dir = -1;
                if (!st.raf) { st.last = 0; st.raf = requestAnimationFrame(step); }
              });
            }
            st.dir = 1;
            ensureFilter();
            if (!st.raf) { st.last = 0; st.raf = requestAnimationFrame(step); }
            function ensureFilter() {
              if (st.holder) { return; }
              var seq = ++__lxDiffuseSeq;
              var id = 'lx-diffuse-' + seq;
              var holder =
                document.createElementNS('http://www.w3.org/2000/svg', 'svg');
              holder.setAttribute('aria-hidden', 'true');
              holder.setAttribute('width', '0');
              holder.setAttribute('height', '0');
              holder.style.cssText =
                'position:absolute;width:0;height:0;overflow:hidden;';
              holder.innerHTML =
                '<defs><filter id="' + id
                  + '" x="-50%" y="-50%" width="200%" height="200%">'
                + '<feTurbulence type="fractalNoise" baseFrequency="0.02"'
                  + ' numOctaves="2" seed="' + seq + '" result="lx-noise"/>'
                + '<feDisplacementMap in="SourceGraphic" in2="lx-noise" scale="0"'
                  + ' xChannelSelector="R" yChannelSelector="G"/>'
                + '</filter></defs>';
              document.body.appendChild(holder);
              st.holder = holder;
              st.map = holder.querySelector('feDisplacementMap');
              el.style.filter = 'url(#' + id + ')';
            }
            function step(now) {
              if (!st.last) { st.last = now; }
              var dt = Math.min(64, now - st.last);
              st.last = now;
              st.p += st.dir * dt / 520;
              if (st.p > 1) { st.p = 1; }
              if (st.p < 0) { st.p = 0; }
              if (st.map) { st.map.setAttribute('scale', (st.p * 30).toFixed(2)); }
              el.style.opacity = (1 - st.p * 0.82).toFixed(3);
              var settled = (st.dir > 0 && st.p >= 1) || (st.dir < 0 && st.p <= 0);
              if (!settled) { st.raf = requestAnimationFrame(step); return; }
              st.raf = 0;
              if (st.dir < 0 && st.p <= 0) {
                el.style.filter = '';
                el.style.opacity = '';
                if (st.holder) { st.holder.remove(); st.holder = null; st.map = null; }
              }
            }
          }

          // REFRACTION. Armed on hover: a small glassy lens (a body-level, pointer-events-none
          // <div> whose backdrop-filter blurs + lifts the glyphs beneath it) follows the
          // pointer across the equation, torn down on pointerleave. Touches nothing in the <svg>.
          function refraction(el) {
            if (reduced) { return; }
            if (el.__lxLensArmed) { return; }
            el.__lxLensArmed = true;
            var lens = null;
            function makeLens(r) {
              var d = Math.max(34, Math.min(120, r.height * 1.6));
              var n = document.createElement('div');
              n.setAttribute('aria-hidden', 'true');
              var s = n.style;
              s.position = 'fixed';
              s.left = '0'; s.top = '0';
              s.width = d + 'px'; s.height = d + 'px';
              s.marginLeft = (-d / 2) + 'px';
              s.marginTop = (-d / 2) + 'px';
              s.borderRadius = '50%';
              s.pointerEvents = 'none';
              s.zIndex = '2147483646';
              s.willChange = 'transform';
              s.backdropFilter = 'blur(1.3px) brightness(1.14) contrast(1.06) saturate(1.12)';
              s.webkitBackdropFilter = s.backdropFilter;
              s.background = 'radial-gradient(circle at 34% 30%, rgba(255,255,255,.38),'
                + ' rgba(255,255,255,.06) 42%, rgba(255,255,255,0) 62%)';
              s.boxShadow = 'inset 0 0 0 1px rgba(255,255,255,.35),'
                + ' inset 0 -6px 12px rgba(0,0,0,.10), 0 3px 10px rgba(0,0,0,.18)';
              s.transition = 'opacity 140ms ease';
              s.opacity = '0';
              document.body.appendChild(n);
              requestAnimationFrame(function () { if (n.parentNode) { n.style.opacity = '1'; } });
              return n;
            }
            function move(ev) {
              if (!lens) { lens = makeLens(el.getBoundingClientRect()); }
              lens.style.transform = 'translate(' + ev.clientX + 'px,' + ev.clientY + 'px)';
            }
            function leave() {
              el.removeEventListener('pointermove', move);
              el.removeEventListener('pointerleave', leave);
              el.__lxLensArmed = false;
              var dead = lens; lens = null;
              if (dead) {
                dead.style.opacity = '0';
                setTimeout(function () {
                  if (dead.parentNode) { dead.parentNode.removeChild(dead); }
                }, 160);
              }
            }
            el.addEventListener('pointermove', move);
            el.addEventListener('pointerleave', leave);
          }

          // TELEPORT. A transporter beam-out/in. On click the equation DEMATERIALIZES — el
          // fades out while a body <canvas> over its box draws a rising column of shimmering
          // cyan/white particles behind a soft vertical beam — holds a beat, then REMATERIALIZES
          // as the particles descend/converge and el fades in. opacity/filter on el + a canvas.
          function teleport(el) {
            if (el.__lxTeleporting) { return; }
            el.__lxTeleporting = true;
            var color = resolveColor(el);
            var filter0 = el.style.filter, trans0 = el.style.transition, op0 = el.style.opacity;
            var timers = [];
            function restore() {
              el.style.transition = trans0; el.style.filter = filter0;
              el.style.opacity = op0 || '1'; el.__lxTeleporting = false;
            }
            if (reduced) {
              el.style.transition = 'opacity 240ms ease';
              el.style.opacity = '0';
              timers.push(setTimeout(function () {
                el.style.opacity = '1';
                timers.push(setTimeout(restore, 260));
              }, 420));
              return;
            }
            var r = el.getBoundingClientRect();
            var dpr = window.devicePixelRatio || 1;
            var pad = 30;
            var W = r.width, H = r.height + pad * 2;
            var canvas = document.createElement('canvas');
            canvas.width = Math.round(W * dpr);
            canvas.height = Math.round(H * dpr);
            canvas.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;'
              + 'left:' + r.left + 'px;top:' + (r.top - pad) + 'px;'
              + 'width:' + W + 'px;height:' + H + 'px;';
            document.body.appendChild(canvas);
            var ctx = canvas.getContext('2d');
            ctx.scale(dpr, dpr);
            var N = Math.max(90, Math.min(320, Math.round(W * 1.4)));
            var particles = [];
            for (var i = 0; i < N; i++) {
              particles.push({
                x: Math.random() * W,
                y: pad + Math.random() * r.height,
                vx: (Math.random() * 2 - 1) * 0.35,
                vy: -(0.4 + Math.random() * 1.6),
                size: 0.6 + Math.random() * 1.9,
                life: Math.random(),
                c: Math.random() < 0.5 ? '255,255,255' : '150,238,255'
              });
            }
            function reseed() {
              for (var i = 0; i < particles.length; i++) {
                var p = particles[i];
                p.x = Math.random() * W;
                p.y = Math.random() * pad;
                p.vx = (W / 2 - p.x) / (r.height + pad) * (0.6 + Math.random() * 0.8);
                p.vy = 0.5 + Math.random() * 1.7;
                p.life = Math.random();
              }
            }
            var OUT = 620, HOLD = 240, IN = 620, END = OUT + HOLD + IN;
            el.style.transition = 'opacity ' + OUT + 'ms ease, filter ' + OUT + 'ms ease';
            el.style.filter = 'drop-shadow(0 0 6px ' + color + ')';
            el.style.opacity = '0';
            var raf = 0, inStarted = false, t0 = performance.now();
            function cleanup() {
              if (raf) { cancelAnimationFrame(raf); raf = 0; }
              timers.forEach(clearTimeout); timers.length = 0;
              canvas.remove();
              restore();
            }
            function draw(now) {
              var t = now - t0;
              if (t >= OUT + HOLD && !inStarted) {
                inStarted = true;
                el.style.transition = 'opacity ' + IN + 'ms ease, filter ' + IN + 'ms ease';
                el.style.filter = filter0 || 'none';
                el.style.opacity = '1';
                reseed();
              }
              var phase = t < OUT ? 0 : (t < OUT + HOLD ? 1 : 2);
              var g = phase === 0 ? Math.min(1, t / OUT)
                    : phase === 1 ? 1
                    : Math.max(0, 1 - (t - OUT - HOLD) / IN);
              ctx.clearRect(0, 0, W, H);
              ctx.globalCompositeOperation = 'lighter';
              var beam = ctx.createLinearGradient(0, 0, 0, H);
              beam.addColorStop(0, 'rgba(150,238,255,0)');
              beam.addColorStop(0.5, 'rgba(150,238,255,' + (0.22 * g).toFixed(3) + ')');
              beam.addColorStop(1, 'rgba(150,238,255,0)');
              ctx.fillStyle = beam;
              ctx.fillRect(W / 2 - 7, 0, 14, H);
              for (var i = 0; i < particles.length; i++) {
                var p = particles[i];
                p.x += p.vx; p.y += p.vy; p.life += 0.03;
                var tw = 0.5 + 0.5 * Math.sin(p.life * 6.283 + i);
                var a = Math.max(0, g * tw * 0.9);
                if (a <= 0.01) { continue; }
                ctx.beginPath();
                ctx.fillStyle = 'rgba(' + p.c + ',' + a.toFixed(3) + ')';
                ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
                ctx.fill();
              }
              if (t >= END) { cleanup(); return; }
              raf = requestAnimationFrame(draw);
            }
            raf = requestAnimationFrame(draw);
          }

          // Play a trigger's effect. lightning/storm/handscribe (+ hologram/neonsign/
          // crystallize/blueprint/wobble/gravwell) → their JS routines;
          // everything else is a one-shot CSS keyframe (reset first so it can replay
          // on re-trigger).
          function play(el, name, dur) {
            if (name === 'lightning') { lightning(el); return; }
            if (name === 'storm') { storm(el); return; }
            if (name === 'handscribe') { handscribe(el); return; }
            if (name === 'hologram') { hologram(el); return; }
            if (name === 'neonsign') { neonsign(el); return; }
            if (name === 'crystallize') { crystallize(el); return; }
            if (name === 'blueprint') { blueprint(el); return; }
            if (name === 'wobble') { wobble(el); return; }
            if (name === 'gravwell') { gravwell(el); return; }
            if (name === 'matrixrain') { matrixrain(el); return; }
            if (name === 'supernova') { supernova(el); return; }
            if (name === 'inkdrop') { inkdrop(el); return; }
            if (name === 'diffusion') { diffusion(el); return; }
            if (name === 'refraction') { refraction(el); return; }
            if (name === 'teleport') { teleport(el); return; }
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
