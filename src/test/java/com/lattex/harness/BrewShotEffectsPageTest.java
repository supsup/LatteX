package com.lattex.harness;

import com.brewshot.BrewShot;
import com.brewshot.MiniJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-browser pin of the placement-compose invariant (the "blob class") plus
 * committed reference screenshots for the example pages.
 *
 * <p>THE BLOB PIN: every LatteX glyph {@code <path>} carries its placement as
 * an SVG transform ATTRIBUTE ({@code translate(..) scale(0.04 -0.04)}). Effects
 * that animate glyphs must COMPOSE their CSS transform delta with that
 * placement (lattex-fx.js setPathDelta + the transform-origin 0 0 pin). Both
 * halves regressed once each before being smoke-caught by hand; this test makes
 * the regression mechanical: while enter effects animate, no glyph's rendered
 * bounding box may exceed ~2x its own SVG's box. A compose regression renders
 * glyphs at raw font units (~25x) and fails loudly here.
 *
 * <p>THE SCREENSHOTS: full-page PNGs written next to the example .html files
 * (examples/effects.png, examples/thread-preview.png) as visual references —
 * regenerate alongside the pages; they are references, not byte-goldens
 * (animation frames differ run to run).
 *
 * <p>Skips (does not fail) when no local Chrome exists or the example page has
 * not been generated (EffectsPageTest/ThreadPreviewPageTest write them).
 */
class BrewShotEffectsPageTest {

    /** JS audit: any glyph path whose box exceeds ~2x its svg box is a blob. */
    private static final String BLOB_AUDIT = """
        (function () {
          var bad = [];
          document.querySelectorAll('.lx-math svg').forEach(function (svg, si) {
            var s = svg.getBoundingClientRect();
            if (!s.width || !s.height) { return; }
            svg.querySelectorAll('path').forEach(function (p, pi) {
              var r = p.getBoundingClientRect();
              if (r.width > s.width * 2 + 24 || r.height > s.height * 2 + 24) {
                bad.push('svg#' + si + ' path#' + pi + ' '
                  + Math.round(r.width) + 'x' + Math.round(r.height)
                  + ' vs svg ' + Math.round(s.width) + 'x' + Math.round(s.height));
              }
            });
          });
          return bad;
        })()
        """;

    private static Path examples() {
        return Path.of("examples").toAbsolutePath();
    }

    @Test
    void effectsPageRendersWithoutBlobsAndWritesReferenceScreenshot() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping browser pin");
        Path page = examples().resolve("effects.html");
        assumeTrue(Files.exists(page), "examples/effects.html not generated");
        assumeTrue(Files.readString(page).contains("data-lx-fx-overlay"),
            "stale examples/effects.html (predates the current runtime) — "
                + "full-suite runs regenerate it first (class ordering)");

        try (BrewShot chrome = BrewShot.launch(1200, 900)) {
            chrome.open(page.toUri().toString());

            // Audit twice: early (enter effects mid-animation — deltas active,
            // so the compose path is genuinely exercised) and settled.
            chrome.settle(350);
            Object early = chrome.eval(BLOB_AUDIT);
            chrome.settle(1200);
            Object settled = chrome.eval(BLOB_AUDIT);

            chrome.screenshot(examples().resolve("effects.png"));

            assertEquals(List.of(), early,
                "glyph blobs mid-animation (placement-compose regressed?)");
            assertEquals(List.of(), settled,
                "glyph blobs after settle (placement or origin pin regressed?)");

            // Non-vacuity: the audit must actually have scanned a real page.
            Object svgCount = chrome.eval(
                "document.querySelectorAll('.lx-math svg').length");
            assertTrue(((Double) svgCount) >= 20,
                "expected the full specimen grid, saw " + svgCount + " svgs");
        }
    }

    @Test
    void threadPreviewScreenshotAlongsideItsHtml() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping browser pin");
        Path page = examples().resolve("thread-preview.html");
        assumeTrue(Files.exists(page), "examples/thread-preview.html not generated");

        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.open(page.toUri().toString());
            chrome.settle(300);
            // Arm the thread emphasis on the first mapped glyph so the reference
            // image shows the effect doing its job, not just resting math.
            chrome.eval("""
                (function () {
                  var p = document.querySelector('.lx-math svg path');
                  if (p) { p.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true})); }
                  return true;
                })()
                """);
            chrome.settle(250);
            chrome.screenshot(examples().resolve("thread-preview.png"));
        }
    }
}
