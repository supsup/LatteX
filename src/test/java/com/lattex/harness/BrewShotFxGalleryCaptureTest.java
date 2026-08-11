package com.lattex.harness;

import com.brewshot.BrewShot;
import com.lattex.api.LatteX;
import com.lattex.api.RenderOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BrewShot capture lane for the production effects that had no committed gallery
 * specimen: inkdrop, refraction, cancel, unfold, and substitute.
 *
 * <p>Ordinary {@code test} runs write into {@code build/brewshot-refs}; the
 * explicit {@code generateExamples} task sets {@code lattex.examples.write} and
 * writes the reviewed references into {@code examples/}. Fixtures are generated
 * from the current renderer/runtime into a class-owned temp directory, so the
 * browser never exercises a stale committed page and cannot race other example
 * generators.
 */
@Tag("capture")
class BrewShotFxGalleryCaptureTest {

    private static final String INKDROP = "[data-lx-fx-enter=\"inkdrop\"]";
    private static final String REFRACTION = "[data-lx-fx-hover=\"refraction\"]";
    private static final String CANCEL = "[data-lx-fx-enter=\"cancel\"]";
    private static final String UNFOLD = "[data-lx-fx-click=\"unfold\"]";
    private static final String SUBSTITUTE = "[data-lx-fx-click=\"substitute\"]";

    @TempDir
    static Path fixturesDir;

    @BeforeAll
    static void buildFixturesFromCurrentSources() throws IOException {
        writeFixture("inkdrop.html", "fx.enter=inkdrop",
            LatteX.renderStyledHtml("\\lx[fx.enter=inkdrop]{\\int_a^b f(x)\\,dx}"));
        writeFixture("refraction.html", "fx.hover=refraction",
            LatteX.renderStyledHtml("\\lx[fx.hover=refraction]{\\frac{\\sin x}{x}}"));
        writeFixture("cancel.html", "fx.enter=cancel",
            LatteX.renderStyledHtml("\\lx[fx.enter=cancel]{\\frac{x}{x}}"));
        writeFixture("unfold.html", "fx.click=unfold",
            LatteX.renderStyledHtml("\\lx[fx.click=unfold]{\\sum_{i=1}^{4} f(i)}",
                RenderOptions.defaults().withInteractiveExpansion(true)));
        writeFixture("substitute.html", "fx.click=substitute",
            LatteX.renderStyledHtml(
                "\\lx[fx.click=substitute, fx.substitute-to=3]{x^2 + 2x + 1}",
                RenderOptions.defaults().withInteractiveExpansion(true)));
    }

    private static void writeFixture(String file, String label, String fragment)
            throws IOException {
        String html = "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>LatteX gallery capture — " + label + "</title>\n<style>\n"
            + LatteX.fxStylesCss() + "\n"
            + "html,body{height:100%;}body{margin:0;display:grid;place-items:center;"
            + "background:#eceef1;color:#171a1f;font-family:system-ui,sans-serif;}\n"
            + ".capture-stage{width:760px;height:400px;box-sizing:border-box;padding:36px;"
            + "display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "gap:38px;background:#fff;border:1px solid #d8dce1;border-radius:20px;"
            + "box-shadow:0 16px 48px rgba(20,24,30,.12);overflow:hidden;}\n"
            + ".capture-label{margin:0;font:600 18px ui-monospace,monospace;color:#8b5b19;}\n"
            + ".equation{min-width:600px;min-height:170px;display:grid;place-items:center;}\n"
            + ".lx-math{font-size:1.15rem;}.lx-math svg{height:7rem;max-width:620px;}\n"
            + "</style></head><body><main class=\"capture-stage\">\n"
            + "<p class=\"capture-label\">" + label + "</p>\n"
            + "<div class=\"equation\">" + fragment + "</div>\n"
            + "</main><script>window.startLatteXFx=function(){"
            + "if(window.__lattexFxStarted){return;}window.__lattexFxStarted=true;\n"
            + LatteX.fxRuntimeJs() + "\n};</script>\n"
            + "</body></html>\n";
        Files.writeString(fixturesDir.resolve(file), html, StandardCharsets.UTF_8);
    }

    private static Path refsOut() throws IOException {
        Path dir = Boolean.getBoolean("lattex.examples.write")
            ? Path.of("examples").toAbsolutePath()
            : Path.of("build", "brewshot-refs").toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void inkdropUsesTheCompositorStreamSoBodyOverlaysStayInFrame() throws Exception {
        BrowserGate.browserPin();
        Path out = refsOut().resolve("inkdrop.gif");

        try (BrewShot chrome = BrewShot.launch(900, 600)) {
            chrome.reducedMotion("no-preference");
            chrome.open(fixturesDir.resolve("inkdrop.html").toUri().toString());
            assertEquals(false, chrome.eval("!!window.LatteXFx"),
                "the gated runtime must leave an intact opening before recording starts");
            chrome.eval("setTimeout(window.startLatteXFx,250); true");

            int frames = chrome.recordGifStream(2_000, 70, 650, 720, out);
            assertTrue(frames >= 4,
                "inkdrop compositor stream must contain multiple changing frames, saw " + frames);
            assertEquals(true, chrome.eval("!!document.querySelector('" + INKDROP + "').__lxInk"),
                "the real enter path must have armed inkdrop");
            assertEquals(0.0, chrome.eval(
                "document.querySelectorAll('[data-lx-fx-overlay=\"inkdrop\"]').length"),
                "inkdrop body overlays must clean up after the bloom");
            assertEquals(List.of(), chrome.errors(), "inkdrop capture threw in the browser");
        }
        assertArtifact(out);
    }

    @Test
    void refractionReceivesARealMovingPointerDuringCapture() throws Exception {
        BrowserGate.browserPin();
        Path out = refsOut().resolve("refraction.gif");

        try (BrewShot chrome = BrewShot.launch(900, 600)) {
            chrome.reducedMotion("no-preference");
            chrome.open(fixturesDir.resolve("refraction.html").toUri().toString());
            chrome.eval("window.startLatteXFx(); true");
            chrome.settle(150);
            double[] box = chrome.elementBox(REFRACTION);
            byte[] before = chrome.screenshotElement(".capture-stage", 0.8);
            int frames = 46;
            chrome.recordGifElement(".capture-stage", frames, 25, 80, 650, 0.8,
                frame -> {
                    if (frame < 2) {
                        return;
                    }
                    double progress = (frame - 2) / (double) (frames - 3);
                    chrome.mouse(box[0] + box[2] * (0.12 + progress * 0.76),
                        box[1] + box[3] * 0.52);
                }, out);

            byte[] after = chrome.screenshotElement(".capture-stage", 0.8);
            assertFalse(Arrays.equals(before, after),
                "the real pointer stream must visibly move/arm the glass lens");
            assertEquals(true,
                chrome.eval("!!document.querySelector('" + REFRACTION + "').__lxLensArmed"),
                "refraction must be armed by trusted BrewShot mouse input");
            assertEquals(List.of(), chrome.errors(), "refraction capture threw in the browser");
        }
        assertArtifact(out);
    }

    @Test
    void cancelRecordsTheDeterministicStrikeAndGhost() throws Exception {
        BrowserGate.browserPin();
        Path out = refsOut().resolve("cancel.gif");

        try (BrewShot chrome = BrewShot.launch(900, 600)) {
            chrome.reducedMotion("no-preference");
            chrome.open(fixturesDir.resolve("cancel.html").toUri().toString());
            assertEquals(",", chrome.eval(
                "Array.from(document.querySelectorAll('" + CANCEL
                    + " svg path')).map(p=>p.style.opacity).join(',')"),
                "cancel capture must begin from the intact pair");

            chrome.recordGifElement(".capture-stage", 48, 25, 80, 650, 0.8,
                frame -> {
                    if (frame == 2) {
                        chrome.eval("window.startLatteXFx(); true");
                    }
                }, out);
            assertEquals("0.18,0.18", chrome.eval(
                "Array.from(document.querySelectorAll('" + CANCEL
                    + " svg path')).map(p=>p.style.opacity).join(',')"),
                "cancel must settle both matching factors to the deterministic ghost");
            assertEquals(0.0, chrome.eval(
                "document.querySelectorAll('[data-lx-fx-overlay=\"cancel\"]').length"),
                "the strike overlay must be gone after the captured animation");
            assertEquals(List.of(), chrome.errors(), "cancel capture threw in the browser");
        }
        assertArtifact(out);
    }

    @Test
    void unfoldRecordsTheFlagEnabledClickToggleInBothDirections() throws Exception {
        BrowserGate.browserPin();
        Path out = refsOut().resolve("unfold.gif");
        AtomicBoolean expandedObserved = new AtomicBoolean();
        AtomicBoolean collapsedAgainObserved = new AtomicBoolean();

        try (BrewShot chrome = BrewShot.launch(900, 600)) {
            chrome.reducedMotion("no-preference");
            chrome.open(fixturesDir.resolve("unfold.html").toUri().toString());
            chrome.eval("window.startLatteXFx(); true");
            chrome.settle(150);
            assertEquals(2.0,
                chrome.eval("document.querySelectorAll('" + UNFOLD + " svg').length"),
                "the capture fixture must have the flag-enabled pre-rendered payload");

            chrome.recordGifElement(".capture-stage", 42, 30, 80, 650, 0.8,
                frame -> {
                    if (frame == 3 || frame == 23) {
                        chrome.click(UNFOLD);
                    } else if (frame == 14) {
                        expandedObserved.set(Boolean.TRUE.equals(chrome.eval(
                            "!document.querySelector('" + UNFOLD
                                + " > .lx-fx-expanded').hidden")));
                    } else if (frame == 34) {
                        collapsedAgainObserved.set(Boolean.TRUE.equals(chrome.eval(
                            "!document.querySelector('" + UNFOLD
                                + " > svg').hidden")));
                    }
                }, out);

            assertTrue(expandedObserved.get(),
                "the first trusted click must reveal the pre-rendered expanded terms");
            assertTrue(collapsedAgainObserved.get(),
                "the second trusted click must collapse back to the bounded sum");
            assertEquals(List.of(), chrome.errors(), "unfold capture threw in the browser");
        }
        assertArtifact(out);
    }

    @Test
    void substituteRecordsTheVariableFlippingToItsValueAndBack() throws Exception {
        BrowserGate.browserPin();
        Path out = refsOut().resolve("substitute.gif");
        AtomicBoolean substitutedObserved = new AtomicBoolean();
        AtomicBoolean variableFormAgainObserved = new AtomicBoolean();

        try (BrewShot chrome = BrewShot.launch(900, 600)) {
            chrome.reducedMotion("no-preference");
            chrome.open(fixturesDir.resolve("substitute.html").toUri().toString());
            chrome.eval("window.startLatteXFx(); true");
            chrome.settle(150);
            assertEquals(2.0,
                chrome.eval("document.querySelectorAll('" + SUBSTITUTE + " svg').length"),
                "the capture fixture must have the flag-enabled pre-rendered payload");
            // The sidecar is what makes this a substitution rather than a swap; if it were
            // missing the capture would still LOOK fine, so assert it before recording.
            assertEquals(true,
                chrome.eval("!!document.querySelector('" + SUBSTITUTE + "')"
                    + ".getAttribute('data-lx-var')"),
                "the fixture must carry the varmap the effect dims through");

            chrome.recordGifElement(".capture-stage", 42, 30, 80, 650, 0.8,
                frame -> {
                    if (frame == 3 || frame == 23) {
                        chrome.click(SUBSTITUTE);
                    } else if (frame == 14) {
                        substitutedObserved.set(Boolean.TRUE.equals(chrome.eval(
                            "!document.querySelector('" + SUBSTITUTE
                                + " > .lx-fx-substituted').hidden")));
                    } else if (frame == 34) {
                        variableFormAgainObserved.set(Boolean.TRUE.equals(chrome.eval(
                            "!document.querySelector('" + SUBSTITUTE + " > svg').hidden")));
                    }
                }, out);

            assertTrue(substitutedObserved.get(),
                "the first trusted click must reveal the pre-rendered substituted form");
            assertTrue(variableFormAgainObserved.get(),
                "the second trusted click must return to the variable form");
            assertEquals(List.of(), chrome.errors(), "substitute capture threw in the browser");
        }
        assertArtifact(out);
    }

    private static void assertArtifact(Path out) throws IOException {
        assertTrue(Files.isRegularFile(out), "BrewShot did not write " + out);
        assertTrue(Files.size(out) > 5_000, "suspiciously small BrewShot GIF at " + out);
    }
}
