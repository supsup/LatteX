package com.lattex.harness;

import com.brewshot.BrewShot;
import com.brewshot.MiniJson;
import com.lattex.api.EffectsPageTest;
import com.lattex.api.ThreadPreviewPageTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
 * <p>THE SCREENSHOTS: full-page PNGs captured as visual references — written
 * next to the example .html files (examples/effects.png, examples/thread-preview.png)
 * when run via {@code generateExamples}, into {@code build/brewshot-refs} during
 * {@code test}; they are references, not byte-goldens (animation frames differ run
 * to run).
 *
 * <p><strong>Fixtures from CURRENT sources (reviewer F2).</strong> The pages the
 * browser loads are regenerated into {@code build/examples} from the live runtime
 * sources ({@link EffectsPageTest#buildEffectsHtml()} /
 * {@link ThreadPreviewPageTest#buildThreadPreviewHtml()}) in a {@code @BeforeAll} —
 * never the checked-in {@code examples/*.html}. So a change to lattex-fx.js /
 * lattex-fx.css / the page template is exercised by this pin immediately; it can
 * no longer pass against a stale committed page.
 *
 * <p>Skips (does not fail) when no local Chrome exists.
 */
@Tag("capture") // browser pin stays in `test` (reference writes land in build/);
                // `generateExamples` re-runs it writing beside the pages (plan 32148cc8 S2)
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

    /** The browser fixtures, regenerated from CURRENT sources into {@code build/}
     * by {@link #buildFixturesFromCurrentSources()}. The pins load THESE, never the
     * tracked {@code examples/*.html}, so a stale committed page can't green a
     * broken runtime (reviewer F2). */
    private static Path pagesDir() {
        return Path.of("build", "examples").toAbsolutePath();
    }

    /** Rebuild the example pages the browser will load from the live runtime
     * sources, into {@code build/examples}, BEFORE any capture runs (reviewer F2). */
    @BeforeAll
    static void buildFixturesFromCurrentSources() throws IOException {
        Files.createDirectories(pagesDir());
        Files.writeString(pagesDir().resolve("effects.html"),
            EffectsPageTest.buildEffectsHtml(), StandardCharsets.UTF_8);
        Files.writeString(pagesDir().resolve("thread-preview.html"),
            ThreadPreviewPageTest.buildThreadPreviewHtml(), StandardCharsets.UTF_8);
    }

    /** Where captured references land: beside the pages when regenerating
     * (`generateExamples` sets {@code -Dlattex.examples.write=true}), into
     * {@code build/brewshot-refs} during `test` so the working tree stays
     * clean (plan 32148cc8 S2). */
    private static Path refsOut() throws java.io.IOException {
        Path dir = Boolean.getBoolean("lattex.examples.write")
            ? examples() : Path.of("build", "brewshot-refs").toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void effectsPageRendersWithoutBlobsAndWritesReferenceScreenshot() throws Exception {
        BrowserGate.browserPin();
        Path page = pagesDir().resolve("effects.html"); // built from current sources in @BeforeAll

        try (BrewShot chrome = BrewShot.launch(1200, 900)) {
            chrome.open(page.toUri().toString());

            // Audit twice: early (enter effects mid-animation — deltas active,
            // so the compose path is genuinely exercised) and settled.
            chrome.settle(350);
            Object early = chrome.eval(BLOB_AUDIT);
            chrome.settle(1200);
            Object settled = chrome.eval(BLOB_AUDIT);

            chrome.screenshot(refsOut().resolve("effects.png"));

            assertEquals(List.of(), early,
                "glyph blobs mid-animation (placement-compose regressed?)");
            assertEquals(List.of(), settled,
                "glyph blobs after settle (placement or origin pin regressed?)");

            // The page must render with ZERO uncaught JS exceptions — the fx
            // runtime is 2000 lines of browser JS; this is its error telemetry.
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), chrome.errors(),
                "fx runtime threw on the effects page");

            // Non-vacuity: the audit must actually have scanned a real page.
            Object svgCount = chrome.eval(
                "document.querySelectorAll('.lx-math svg').length");
            assertTrue(((Double) svgCount) >= 20,
                "expected the full specimen grid, saw " + svgCount + " svgs");
        }
    }

    @Test
    void threadPreviewScreenshotAlongsideItsHtml() throws Exception {
        BrowserGate.browserPin();
        Path page = pagesDir().resolve("thread-preview.html"); // built from current sources in @BeforeAll

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
            chrome.screenshot(refsOut().resolve("thread-preview.png"));
        }
    }
}
