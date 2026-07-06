package com.lattex.harness;

import com.brewshot.BrewShot;
import com.brewshot.MiniJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Real-browser pins for the fx-runtime LIFECYCLE fixes (deep-review HIGHs,
 * plan 72d8d80a) and the thread glyphmap runtime — the behaviors that were
 * previously verified only by operator eyeball.
 *
 * <p>hologram: had NO teardown (permanent setInterval + window listeners +
 * body overlay). The fix routes it through scrollKillable. Pinned here: after
 * a real scroll, the effect's element flag clears, its body scanline overlay
 * is removed, and its idle transform stops mutating.
 *
 * <p>neonsign: had no re-entry guard and an unkillable flicker loop. Pinned:
 * the guard flag arms on play and CLEARS after scroll (teardown ran).
 *
 * <p>thread: the glyphmap-driven emphasis (hover one x → all x's bold) had no
 * behavioral test at all. Pinned end-to-end: mouseenter on a mapped glyph
 * bolds exactly its codepoint-group mates; mouseleave restores every path.
 */
class BrewShotFxLifecycleTest {

    private static Path examples() {
        return Path.of("examples").toAbsolutePath();
    }

    @Test
    void hologramAndNeonsignTearDownOnScroll() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping browser pin");
        Path page = examples().resolve("effects.html");
        assumeTrue(Files.exists(page), "examples/effects.html not generated");
        assumeTrue(Files.readString(page).contains("data-lx-fx-overlay"),
            "stale examples/effects.html (predates the current runtime) — "
                + "full-suite runs regenerate it first (class ordering)");

        // Short viewport so the page is scrollable (the kill needs a REAL scroll).
        try (BrewShot chrome = BrewShot.launch(1200, 800)) {
            chrome.open(page.toUri().toString());
            // enter effects play immediately on load; give the hologram boot
            // sequence (6 x 55ms) time to finish arming.
            chrome.settle(800);

            Object armed = chrome.eval("""
                (function () {
                  var holo = document.querySelector('[data-lx-fx-enter="hologram"]');
                  var neon = document.querySelector('[data-lx-fx-enter="neonsign"]');
                  return {
                    holoArmed: !!(holo && holo._lxHolo),
                    neonArmed: !!(neon && neon.__lxNeon),
                    scanOverlays: document.querySelectorAll(
                      '[data-lx-fx-overlay="hologram"]').length
                  };
                })()
                """);
            assertEquals(true, MiniJson.get(armed, "holoArmed"),
                "hologram should be armed after load");
            assertEquals(true, MiniJson.get(armed, "neonArmed"),
                "neonsign guard should be set after load");
            assertTrue(((Double) MiniJson.get(armed, "scanOverlays")) >= 1,
                "hologram scanline overlay should be in <body>");

            // A real viewport move (> the 4px threshold) must end both shows.
            chrome.eval("window.scrollTo(0, 600); true");
            chrome.settle(400);

            Object after = chrome.eval("""
                (function () {
                  var holo = document.querySelector('[data-lx-fx-enter="hologram"]');
                  var neon = document.querySelector('[data-lx-fx-enter="neonsign"]');
                  return {
                    holoArmed: !!(holo && holo._lxHolo),
                    neonArmed: !!(neon && neon.__lxNeon),
                    holoTransform: holo ? holo.style.transform : 'missing',
                    scanOverlays: document.querySelectorAll(
                      '[data-lx-fx-overlay="hologram"]').length
                  };
                })()
                """);
            assertEquals(false, MiniJson.get(after, "holoArmed"),
                "hologram must tear down on scroll (the review-HIGH leak)");
            assertEquals(false, MiniJson.get(after, "neonArmed"),
                "neonsign must release its guard on scroll");
            assertEquals(0.0, MiniJson.get(after, "scanOverlays"),
                "the hologram scanline overlay must be removed from <body>");
            assertEquals("", MiniJson.get(after, "holoTransform"),
                "hologram teardown must restore the element transform");
        }
    }

    @Test
    void threadGlyphmapBoldsExactlyTheHoveredCodepointGroup() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping browser pin");
        Path page = examples().resolve("thread-preview.html");
        assumeTrue(Files.exists(page), "examples/thread-preview.html not generated");

        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.open(page.toUri().toString());
            chrome.settle(300);

            // The preview stamps GLYPHMAP "78:0,2,4;2b:1,3" over "x + x + x":
            // paths 0,2,4 are the x's, 1,3 the plus signs.
            Object boldCount = chrome.eval("""
                (function () {
                  var ps = document.querySelectorAll('.lx-math svg path');
                  if (ps.length !== 5) { return 'expected 5 paths, saw ' + ps.length; }
                  ps[0].dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
                  var bold = [];
                  ps.forEach(function (p, i) {
                    if (p.style.strokeWidth === '28') { bold.push(i); }
                  });
                  return bold.join(',');
                })()
                """);
            assertEquals("0,2,4", boldCount,
                "hovering the first x must bold exactly the x-group (glyphmap runtime)");

            Object restored = chrome.eval("""
                (function () {
                  var svg = document.querySelector('.lx-math svg');
                  svg.dispatchEvent(new MouseEvent('mouseleave', {bubbles: false}));
                  var stray = [];
                  svg.querySelectorAll('path').forEach(function (p, i) {
                    if (p.style.strokeWidth || p.style.opacity === '0.22') { stray.push(i); }
                  });
                  return stray.join(',');
                })()
                """);
            assertEquals("", restored,
                "mouseleave must restore every glyph (no stray emphasis)");
        }
    }
}
