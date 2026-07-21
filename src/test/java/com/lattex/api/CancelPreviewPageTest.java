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
 * Generates {@code examples/cancel-preview.html} — the fx.cancel demo, the third
 * SEMANTIC effect (after {@code thread}/{@code precedence}). Unlike the thread preview
 * (whose glyphmap was hand-stamped before the emit-side builder existed), cancel rides
 * the REAL producer: {@code renderStyledHtml("\\lx[fx.enter=cancel]{\\frac{x}{x}}")}
 * auto-stamps {@code data-lx-glyphmap} through the {@code usesGlyphmap} gate (the same
 * gate {@code thread} uses — zero new attribute), so this fixture also proves the
 * stamping wiring end-to-end.
 *
 * <p>The fraction {@code \frac{x}{x}} emits exactly two glyph {@code <path>}s (the two
 * {@code x}s; the fraction bar is a {@code <rect>}, not a {@code <path>}), sharing the
 * source code point {@code x} (0x78), so the auto-stamped map is {@code 78:0,1} — a
 * code point occurring EXACTLY TWICE, i.e. a cancelling pair. On load the two {@code x}s
 * strike out and puff away to a grayed ghost.
 */
@Tag("examples") // page generator: runs in normal `test` (writes build/examples, keeping
                 // the glyph-count + auto-stamp assertions in CI) AND under
                 // `generateExamples`, which writes the tracked examples/ (plan 32148cc8 S2)
// Public so the BrewShot harness (com.lattex.harness) can call buildCancelPreviewHtml()
// to regenerate the browser fixture from current sources.
public class CancelPreviewPageTest {

    private static final String LATEX = "\\lx[fx.enter=cancel]{\\frac{x}{x}}";
    private static final Pattern GLYPHMAP = Pattern.compile("data-lx-glyphmap=\"([^\"]*)\"");

    @Test
    void writesCancelPreviewPage() throws IOException {
        String fragment = LatteX.renderStyledHtml(LATEX);

        // \frac{x}{x} emits two glyph <path>s (the x's); the fraction bar is a <rect>.
        Matcher paths = Pattern.compile("<path ").matcher(fragment);
        int count = 0;
        while (paths.find()) {
            count++;
        }
        assertEquals(2, count, "fixture expects one path per x of \\frac{x}{x} (bar is a <rect>)");

        // The stamping gate (usesGlyphmap → THREAD || CANCEL) auto-stamps the sidecar for
        // a cancel effect, so the map arrives from the real producer, not a hand stamp.
        assertTrue(fragment.contains("data-lx-fx-enter=\"cancel\""),
            "cancel rides the container as data-lx-fx-enter");
        Matcher gm = GLYPHMAP.matcher(fragment);
        assertTrue(gm.find(), "fx.enter=cancel must auto-stamp data-lx-glyphmap (the usesGlyphmap gate)");
        assertEquals("78:0,1", gm.group(1),
            "the two x's (0x78) form the exactly-twice cancelling pair at path indices 0,1");

        String html = buildCancelPreviewHtml(false);
        Path out = ExampleOutputs.dir().resolve("cancel-preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 1000);
    }

    /**
     * Builds {@code cancel-preview.html} from the CURRENT runtime sources — the math
     * rendered live via {@link LatteX#renderStyledHtml(String)} (which auto-stamps the
     * glyphmap) and the page wired with the bundled {@link LatteX#fxRuntimeJs()} /
     * {@link LatteX#fxStylesCss()}. Public + assertion-free so the BrewShot harness can
     * regenerate the browser fixture into {@code build/} straight from live sources.
     *
     * @param forceReduced when true, a {@code matchMedia} stub is injected in {@code <head>}
     *     BEFORE the runtime script so the runtime boots in prefers-reduced-motion mode
     *     (BrewShot has no media-emulation hook; this fixture-level stub is how the
     *     reduced-motion static end-state is captured/pinned in a real browser).
     */
    public static String buildCancelPreviewHtml(boolean forceReduced) {
        String fragment = LatteX.renderStyledHtml(LATEX);
        String reducedStub = forceReduced
            ? "<script>window.matchMedia = function () { return { matches: true,"
                + " addEventListener: function () {}, addListener: function () {} }; };</script>\n"
            : "";
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<title>fx.cancel preview — the exactly-twice strike</title>\n"
            + "<style>\n" + LatteX.fxStylesCss() + "\n"
            + "body { font-family: system-ui; max-width: 40rem; margin: 3rem auto; }\n"
            + ".lx-math svg { height: 5rem; }\n"
            + "</style>\n" + reducedStub + "</head><body>\n"
            + "<h1>fx.cancel — the third semantic effect</h1>\n"
            + "<p>On load the two <em>x</em>s of <code>\\frac{x}{x}</code> — the same source"
            + " token occurring exactly twice — strike out and puff away to a grayed ghost."
            + " The glyphmap is auto-stamped by the real producer (no hand stamp).</p>\n"
            + fragment + "\n"
            + "<script>\n" + LatteX.fxRuntimeJs() + "\n</script>\n"
            + "</body></html>\n";
    }
}
