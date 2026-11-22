package com.lattex.harness;

import com.brewshot.BrewShot;
import com.lattex.api.FxKnobsPreviewPageTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** References for human inspection; these are not randomized-pixel goldens. */
@Tag("capture")
class BrewShotFxKnobsCaptureTest {
    @TempDir
    Path fixtures;

    @Test
    void tunedLightningRecordsTrustedClickPlaybackAndReturnsToTheIntactEquation() throws Exception {
        BrowserGate.browserPin();
        Path page = writeFixture();
        Path out = refsOut().resolve("lightning-knobs.gif");
        AtomicBoolean canvasObserved = new AtomicBoolean();
        try (BrewShot chrome = BrewShot.launch(1000, 880)) {
            chrome.reducedMotion("no-preference");
            chrome.open(page.toUri().toString());
            chrome.settle(120);
            assertVisibleEquation(chrome, "lightning-tuned");
            assertEquals("4:150", chrome.eval("""
                (function(){var el=document.querySelector('#lightning-tuned .lx-math');
                return el.getAttribute('data-lx-fx-bolts')+':'+el.getAttribute('data-lx-fx-intensity');})()
                """));
            chrome.recordGifElement(".fx-knobs-stage", 32, 30, 55, 900, 0.8,
                frame -> {
                    if (frame == 2) chrome.click("#lightning-tuned .lx-math");
                    if (frame >= 3 && frame <= 10 && Boolean.TRUE.equals(chrome.eval("document.querySelectorAll('canvas').length>0"))) canvasObserved.set(true);
                }, out);
            assertTrue(canvasObserved.get(), "the trusted click must create the real lightning canvas");
            chrome.settle(750);
            assertEquals(0.0, chrome.eval("document.querySelectorAll('canvas').length"),
                "lightning must clean up its overlay");
            assertVisibleEquation(chrome, "lightning-tuned");
            assertEquals(List.of(), chrome.errors(), "tuned lightning threw in Chrome");
        }
        assertArtifact(out);
    }

    @Test
    void zeroStormIsVisibleAndItsPageButtonEnablesThatSameElementThroughPublicPlay() throws Exception {
        BrowserGate.browserPin();
        Path page = writeFixture();
        Path disabled = refsOut().resolve("storm-disabled.png");
        Path enabled = refsOut().resolve("storm-enabled.gif");
        AtomicBoolean canvasObserved = new AtomicBoolean();
        try (BrewShot chrome = BrewShot.launch(1000, 880)) {
            chrome.reducedMotion("no-preference");
            chrome.open(page.toUri().toString());
            chrome.settle(120);
            chrome.eval("""
                window.__knobStorm=document.querySelector('#storm-zero .lx-math');
                window.__knobStormSvg=window.__knobStorm.querySelector('svg').outerHTML; true
                """);
            assertEquals("0", chrome.eval("window.__knobStorm.getAttribute('data-lx-fx-intensity')"));
            assertEquals("", chrome.eval("window.__knobStorm.getAttribute('style')||''"),
                "an initially disabled-only target has no initialization style writes");
            chrome.click("#storm-zero .lx-math");
            chrome.settle(120);
            assertEquals(0.0, chrome.eval("document.querySelectorAll('canvas').length"),
                "zero must not create a canvas on real interaction");
            assertEquals(0.0, chrome.eval("document.querySelectorAll('[data-lx-fx-overlay]').length"));
            assertVisibleEquation(chrome, "storm-zero");
            chrome.screenshot(disabled);
            chrome.recordGifElement(".fx-knobs-stage", 42, 30, 55, 900, 0.8,
                frame -> {
                    if (frame == 2) chrome.click("#enable-storm");
                    if (frame >= 3 && frame <= 15 && Boolean.TRUE.equals(chrome.eval("document.querySelectorAll('canvas').length>0"))) canvasObserved.set(true);
                }, enabled);
            assertTrue(canvasObserved.get(), "the page button must play the initially-zero storm");
            assertEquals(true, chrome.eval(
                "window.__knobStorm===document.querySelector('#storm-zero .lx-math')"),
                "late enable must use the original initialized-at-zero element");
            assertEquals("100", chrome.eval("window.__knobStorm.getAttribute('data-lx-fx-intensity')"));
            chrome.settle(2200);
            assertEquals(0.0, chrome.eval("document.querySelectorAll('canvas').length"));
            assertEquals(0.0, chrome.eval("document.querySelectorAll('[data-lx-fx-overlay]').length"));
            assertEquals(true, chrome.eval(
                "window.__knobStorm.querySelector('svg').outerHTML===window.__knobStormSvg"),
                "playback must leave the equation's inner SVG intact");
            assertVisibleEquation(chrome, "storm-zero");
            assertEquals(List.of(), chrome.errors(), "zero-to-positive storm threw in Chrome");
        }
        assertArtifact(disabled);
        assertArtifact(enabled);
    }

    private Path writeFixture() throws IOException {
        Path page = fixtures.resolve("fx-knobs.html");
        Files.writeString(page, FxKnobsPreviewPageTest.buildFxKnobsPreviewHtml(), StandardCharsets.UTF_8);
        return page;
    }

    private static void assertVisibleEquation(BrewShot chrome, String id) throws Exception {
        assertEquals(true, chrome.eval("""
            (function(){var svg=document.querySelector('#%ID% .lx-math svg');
            if(!svg)return false;var r=svg.getBoundingClientRect(),n=svg;
            while(n&&n.nodeType===1){var s=getComputedStyle(n);
              if(s.display==='none'||s.visibility==='hidden'||Number(s.opacity)===0)return false;
              n=n.parentElement;}
            return r.width>0&&r.height>0&&r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth;})()
            """.replace("%ID%", id)), "the equation must remain visible");
    }

    private static Path refsOut() throws IOException {
        Path dir = Boolean.getBoolean("lattex.examples.write")
            ? Path.of("examples").toAbsolutePath()
            : Path.of("build", "brewshot-refs").toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }

    private static void assertArtifact(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "capture missing: " + path);
        assertTrue(Files.size(path) > 5_000, "suspiciously small capture: " + path);
    }
}
