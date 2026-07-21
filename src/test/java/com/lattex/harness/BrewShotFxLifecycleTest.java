package com.lattex.harness;

import com.brewshot.BrewShot;
import com.brewshot.MiniJson;
import com.lattex.api.CancelPreviewPageTest;
import com.lattex.api.EffectsPageTest;
import com.lattex.api.ThreadPreviewPageTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
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

    /** The browser fixtures, regenerated from CURRENT sources into {@code build/}
     * by {@link #buildFixturesFromCurrentSources()} — the lifecycle pins exercise
     * the live runtime, never a stale committed page (reviewer F2). */
    private static Path pagesDir() {
        return Path.of("build", "examples").toAbsolutePath();
    }

    /** Rebuild the example pages this class loads from the live runtime sources,
     * into {@code build/examples}, BEFORE any capture runs (reviewer F2). */
    @BeforeAll
    static void buildFixturesFromCurrentSources() throws IOException {
        Files.createDirectories(pagesDir());
        Files.writeString(pagesDir().resolve("effects.html"),
            EffectsPageTest.buildEffectsHtml(), StandardCharsets.UTF_8);
        Files.writeString(pagesDir().resolve("thread-preview.html"),
            ThreadPreviewPageTest.buildThreadPreviewHtml(), StandardCharsets.UTF_8);
        Files.writeString(pagesDir().resolve("cancel-preview.html"),
            CancelPreviewPageTest.buildCancelPreviewHtml(false), StandardCharsets.UTF_8);
        Files.writeString(pagesDir().resolve("cancel-preview-reduced.html"),
            CancelPreviewPageTest.buildCancelPreviewHtml(true), StandardCharsets.UTF_8);
    }

    /** Reference-image sink (build-local, git-ignored) for the eyeball captures. */
    private static Path refsDir() throws IOException {
        Path dir = Path.of("build", "brewshot-refs").toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void hologramAndNeonsignTearDownOnScroll() throws Exception {
        BrowserGate.browserPin();
        Path page = pagesDir().resolve("effects.html"); // built from current sources in @BeforeAll

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
        BrowserGate.browserPin();
        Path page = pagesDir().resolve("thread-preview.html"); // built from current sources in @BeforeAll

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

    @Test
    void cancelStrikesTheExactlyTwicePairAndSettlesToAGhost() throws Exception {
        BrowserGate.browserPin();
        Path page = pagesDir().resolve("cancel-preview.html"); // built from current sources in @BeforeAll

        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.open(page.toUri().toString());

            // \frac{x}{x} renders exactly two glyph <path>s (the bar is a <rect>); capture
            // the count up front so the containment check (no new inner <path>) is real.
            Object beforePaths = chrome.eval("document.querySelectorAll('.lx-math svg path').length");
            assertEquals(2.0, beforePaths, "the \\frac{x}{x} fixture has two glyph paths");

            // Mid-run (strike drawn, before the puff completes): the body strike overlay
            // must exist — and it is a BODY element, never inside the inner <svg>.
            chrome.settle(260);
            Object mid = chrome.eval("""
                (function () {
                  return {
                    overlays: document.querySelectorAll('[data-lx-fx-overlay="cancel"]').length,
                    innerOverlay: document.querySelectorAll(
                      '.lx-math svg [data-lx-fx-overlay]').length
                  };
                })()
                """);
            assertTrue(((Double) MiniJson.get(mid, "overlays")) >= 1,
                "the cancel strike overlay must be on the body while the strike shows");
            assertEquals(0.0, MiniJson.get(mid, "innerOverlay"),
                "the strike overlay must NOT be inside the inner <svg> (containment)");

            // Settle well past the whole timeline (strike 200 + hold 240 + puff 410 +
            // fade 300 ≈ 1.15s): both x's reach the grayed ghost, the overlay is gone,
            // and the inner <svg> gained NO new <path>.
            chrome.settle(1400);
            chrome.screenshot(refsDir().resolve("cancel-preview.png"));

            Object after = chrome.eval("""
                (function () {
                  var ps = document.querySelectorAll('.lx-math svg path');
                  var op = [];
                  ps.forEach(function (p) { op.push(p.style.opacity); });
                  return {
                    pathCount: ps.length,
                    opacities: op.join(','),
                    overlays: document.querySelectorAll('[data-lx-fx-overlay="cancel"]').length
                  };
                })()
                """);
            assertEquals(2.0, MiniJson.get(after, "pathCount"),
                "cancel must add NO new inner <path> (the strike rides a body overlay)");
            assertEquals("0.18,0.18", MiniJson.get(after, "opacities"),
                "both cancelled x's must settle to the grayed ghost (opacity 0.18)");
            assertEquals(0.0, MiniJson.get(after, "overlays"),
                "the strike overlay must tear down after the puff, leaving only the ghost");
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), chrome.errors(),
                "fx.cancel threw on the preview page");
        }
    }

    @Test
    void cancelReducedMotionSnapsToTheStaticStruckGhost() throws Exception {
        BrowserGate.browserPin();
        // The reduced fixture stubs matchMedia BEFORE the runtime loads (BrewShot has no
        // media-emulation hook), so the runtime boots in prefers-reduced-motion mode.
        Path page = pagesDir().resolve("cancel-preview-reduced.html");

        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.open(page.toUri().toString());
            chrome.settle(250); // reduced = instant end-state, no frames to wait on
            chrome.screenshot(refsDir().resolve("cancel-preview-reduced.png"));

            Object state = chrome.eval("""
                (function () {
                  var ps = document.querySelectorAll('.lx-math svg path');
                  var op = [];
                  ps.forEach(function (p) { op.push(p.style.opacity); });
                  return {
                    pathCount: ps.length,
                    opacities: op.join(','),
                    overlays: document.querySelectorAll('[data-lx-fx-overlay="cancel"]').length
                  };
                })()
                """);
            assertEquals(2.0, MiniJson.get(state, "pathCount"), "reduced fixture has two glyph paths");
            assertEquals("0.18,0.18", MiniJson.get(state, "opacities"),
                "reduced motion must snap both x's to the grayed ghost, no animation");
            assertTrue(((Double) MiniJson.get(state, "overlays")) >= 1,
                "reduced motion keeps the static strike overlay as the end-state");
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), chrome.errors(),
                "fx.cancel reduced path threw on the preview page");
        }
    }
}
