package com.lattex.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates {@code examples/thread-preview.html} — the fx.thread demo with a
 * HAND-STAMPED {@code data-lx-glyphmap}, so the semantic-effect runtime is visually
 * smokable BEFORE the emit-side map builder exists (it waits on the layout
 * {@code codePoint} threading, RFC lattex/34→35). The fixture uses a flat expression
 * ({@code x + x + x}) whose emit order is trivially the reading order, and the test
 * VERIFIES that assumption instead of trusting it: exactly five {@code <path>}s, so
 * the stamped map {@code 78:0,2,4;2b:1,3} addresses real indices.
 *
 * <p>Hover any {@code x} on the page: all three light up and pop while the plusses
 * recede; hover a {@code +}: the two plusses thread. Leave the svg: restore.
 */
@Tag("examples") // page generator: runs in normal `test` (writes build/examples, keeping
                 // the glyph-count fixture assertion in CI) AND under `generateExamples`,
                 // which writes the tracked examples/ (plan 32148cc8 S2, reviewer F1)
// Public so the BrewShot harness (com.lattex.harness) can call buildThreadPreviewHtml()
// to regenerate the browser fixture from current sources (reviewer F2).
public class ThreadPreviewPageTest {

    private static final String LATEX = "x + x + x";
    private static final String GLYPHMAP = "78:0,2,4;2b:1,3"; // x → 0,2,4 ; + → 1,3

    @Test
    void writesThreadPreviewPage() throws IOException {
        String svg = LatteX.render(LATEX);

        // Verify the fixture's order assumption: a flat 5-glyph expression emits
        // exactly 5 paths, so the hand-stamped indices are in-bounds and aligned.
        Matcher paths = Pattern.compile("<path ").matcher(svg);
        int count = 0;
        while (paths.find()) {
            count++;
        }
        assertEquals(5, count, "fixture expects one path per glyph of '" + LATEX + "'");

        String html = buildThreadPreviewHtml();
        Path out = ExampleOutputs.dir().resolve("thread-preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 1000);
    }

    /**
     * Builds {@code thread-preview.html} from the CURRENT runtime sources — the math
     * rendered live via {@link LatteX#render(String)} and the page wired with the
     * bundled {@link LatteX#fxRuntimeJs()} / {@link LatteX#fxStylesCss()}. Public and
     * assertion-free so the BrewShot harness can regenerate the browser fixture into
     * {@code build/} straight from live sources before it captures (reviewer F2).
     */
    public static String buildThreadPreviewHtml() {
        String svg = LatteX.render(LATEX);
        String wrapper = "<div class=\"lx-math math-display\" data-lx-fx-enter=\"thread\""
            + " data-lx-glyphmap=\"" + GLYPHMAP + "\">" + svg + "</div>";

        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<title>fx.thread preview — hover a glyph</title>\n"
            + "<style>\n" + LatteX.fxStylesCss() + "\n"
            + "body { font-family: system-ui; max-width: 40rem; margin: 3rem auto; }\n"
            + ".lx-math svg { height: 4rem; }\n"
            + "</style></head><body>\n"
            + "<h1>fx.thread — the first semantic effect</h1>\n"
            + "<p>Hover any <em>x</em>: every x lights up and pops while the rest recedes."
            + " Hover a +: the plusses thread. The glyph map here is HAND-STAMPED"
            + " (emit-side stamping ships with the layout codePoint threading).</p>\n"
            + wrapper + "\n"
            + "<script>\n" + LatteX.fxRuntimeJs() + "\n</script>\n"
            + "</body></html>\n";
    }

    /** Drift-guard: the runtime's glyphmap grammar literal matches the contract's. */
    @Test
    void runtimeGrammarMatchesTheContractPin() {
        String js = LatteX.fxRuntimeJs();
        assertTrue(js.contains("^[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*$"),
            "lattex-fx.js must validate data-lx-glyphmap against the exact contract grammar"
                + " (container-output-contract.md) — the sidecar is defense-in-depth on both ends");
    }
}
