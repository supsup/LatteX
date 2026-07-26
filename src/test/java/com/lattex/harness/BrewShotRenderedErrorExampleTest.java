package com.lattex.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brewshot.BrewShot;
import com.lattex.api.RenderedErrorExamplePageTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-browser visual receipt for the opt-in rendered diagnostic card. */
@Tag("capture")
class BrewShotRenderedErrorExampleTest {

    @TempDir
    Path fixtures;

    @Test
    void currentErrorCardRendersAndWritesTheBrewShotReference() throws Exception {
        BrowserGate.browserPin();
        Path page = fixtures.resolve("rendered-error.html");
        Files.writeString(page, RenderedErrorExamplePageTest.buildRenderedErrorHtml(),
            StandardCharsets.UTF_8);
        Path out = refsOut().resolve("rendered-error.png");

        try (BrewShot chrome = BrewShot.launch(1100, 760)) {
            chrome.open(page.toUri().toString());
            chrome.settle(150);
            assertEquals(1.0, chrome.eval("document.querySelectorAll('.formula svg').length"));
            assertEquals(0.0, chrome.eval("document.querySelectorAll('text,use,script').length"));
            assertTrue(((Double) chrome.eval(
                "document.querySelector('.formula svg').getBoundingClientRect().height")) > 100.0);
            chrome.screenshot(out);
            assertEquals(List.of(), chrome.errors(), "rendered-error example threw in Chrome");
        }

        assertTrue(Files.isRegularFile(out));
        assertTrue(Files.size(out) > 5_000, "suspiciously small BrewShot reference: " + out);
    }

    private static Path refsOut() throws IOException {
        Path dir = Boolean.getBoolean("lattex.examples.write")
            ? Path.of("examples").toAbsolutePath()
            : Path.of("build", "brewshot-refs").toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }
}
