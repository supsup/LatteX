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
 * Generates {@code examples/unfold-preview.html} — the fx.click=unfold demo, the
 * FOURTH semantic effect (after {@code thread}/{@code precedence}/{@code cancel}) and
 * the first that needs LatteX to *compute* new material. Unlike the other semantic
 * previews, this one rides the DOUBLE GATE ({@link UnfoldGateTest}): the fixture is
 * built with the host flag ON — {@link RenderOptions#withInteractiveExpansion(boolean)}
 * — so {@link com.lattex.parse.SumExpansion} actually pre-renders the expanded terms
 * into the hidden sibling {@code <svg>} payload the runtime toggles on click.
 *
 * <p>{@code \sum_{i=1}^{4} f(i)} is a supported bounded sum (literal-integer bounds,
 * single-letter index, bare summand, under the 12-term cap), so both gates open and
 * the payload arms: two {@code <svg>}s, {@code data-lx-fx-expand="4"}, and the
 * {@code .lx-fx-expanded} wrapper carrying {@code hidden} pre-click.
 */
@Tag("examples") // page generator: runs in normal `test` (writes build/examples, keeping
                 // the svg-count + marker assertions in CI) AND under
                 // `generateExamples`, which writes the tracked examples/ (plan 32148cc8 S2)
// Public so the BrewShot harness (com.lattex.harness) can call buildUnfoldPreviewHtml()
// to regenerate the browser fixture from current sources.
public class UnfoldPreviewPageTest {

    private static final String LATEX = "\\lx[fx.click=unfold]{\\sum_{i=1}^{4} f(i)}";

    @Test
    void writesUnfoldPreviewPage() throws IOException {
        String fragment = LatteX.renderStyledHtml(LATEX, RenderOptions.defaults().withInteractiveExpansion(true));

        // Both gates open + a supported sum: the collapsed <svg> plus the pre-rendered
        // expanded payload <svg> — exactly two.
        Matcher svgs = Pattern.compile("<svg").matcher(fragment);
        int count = 0;
        while (svgs.find()) {
            count++;
        }
        assertEquals(2, count, "fixture expects the collapsed svg plus the pre-rendered payload svg: " + fragment);

        assertTrue(fragment.contains("data-lx-fx-expand=\"4\""),
            "unfold must stamp the term-count marker for the 4-term sum: " + fragment);
        assertTrue(fragment.contains("class=\"lx-fx-expanded\""),
            "the expanded payload must be wrapped in .lx-fx-expanded: " + fragment);
        assertTrue(fragment.contains("<span class=\"lx-fx-expanded\" hidden>"),
            "the payload wrapper must carry hidden pre-click (the runtime clears it on toggle): " + fragment);

        String html = buildUnfoldPreviewHtml();
        Path out = ExampleOutputs.dir().resolve("unfold-preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 1000);
    }

    /**
     * Builds {@code unfold-preview.html} from the CURRENT runtime sources — the math
     * rendered live via {@link LatteX#renderStyledHtml(String, RenderOptions)} WITH the
     * host's {@link RenderOptions#interactiveExpansion()} flag ON (so the payload actually
     * pre-renders), and the page wired with the bundled {@link LatteX#fxRuntimeJs()} /
     * {@link LatteX#fxStylesCss()}. Public + assertion-free so the BrewShot harness can
     * regenerate the browser fixture into {@code build/} straight from live sources.
     */
    public static String buildUnfoldPreviewHtml() {
        String fragment = LatteX.renderStyledHtml(LATEX, RenderOptions.defaults().withInteractiveExpansion(true));
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<title>fx.unfold preview — click to bloom the sum</title>\n"
            + "<style>\n" + LatteX.fxStylesCss() + "\n"
            + "body { font-family: system-ui; max-width: 40rem; margin: 3rem auto; }\n"
            + ".lx-math svg { height: 5rem; }\n"
            + "</style>\n</head><body>\n"
            + "<h1>fx.unfold — click the equation to expand it</h1>\n"
            + "<p>Click the sum below to bloom <code>\\sum_{i=1}^{4} f(i)</code> open into"
            + " its explicit terms, <code>f(1)+f(2)+f(3)+f(4)</code>. The expanded form was"
            + " pre-rendered by LatteX (the host's interactive-expansion flag is on for this"
            + " page); the runtime just toggles the two sibling svgs, hiding the outgoing one"
            + " immediately and fading the incoming one in. Click again to collapse back.</p>\n"
            + fragment + "\n"
            + "<script>\n" + LatteX.fxRuntimeJs() + "\n</script>\n"
            + "</body></html>\n";
    }
}
