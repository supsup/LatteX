package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link SvgEmitter#glyphmap} — the token-identity sidecar that turns the {@code
 * thread} fx effect from a hand-rigged demo into renderer output. Indices must
 * address the emitted {@code <path>}s in emit order; repeated tokens group.
 */
class GlyphmapTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex),
            new LayoutContext(FONT, FONT.mathConstants(), 40.0));
    }

    private static String glyphmapOf(String latex) {
        return SvgEmitter.glyphmap(layout(latex), FONT);
    }

    @Test
    void groupsRepeatedTokensByCodePointInEmitOrder() {
        // x + x : 'x' is U+0078 at emitted-path indices 0 and 2; '+' (index 1) is
        // unique, so it forms no run.
        assertEquals("78:0,2", glyphmapOf("x + x"));
    }

    @Test
    void groupsAcrossScriptsAndMultipleTokens() {
        // x^2 + x^2 : emit order x,2,+,x,2 -> x(U+78)@0,3 ; 2(U+32)@1,4 ; +@2 unique.
        assertEquals("78:0,3;32:1,4", glyphmapOf("x^2 + x^2"));
    }

    @Test
    void aUniqueGlyphHasNoThreadGroup() {
        assertEquals("", glyphmapOf("x"));       // one glyph, nothing to thread
        assertEquals("", glyphmapOf("a + b"));   // all tokens distinct
    }

    @Test
    void matchesTheContractGrammarAndAddressesRealPaths() {
        // The runtime validates against /^[0-9a-f]+:[0-9]+(,[0-9]+)*(;...)*$/ and
        // refuses any index >= the <path> count. Cross-check both here.
        String latex = "a x + a x - a";  // 'a' x3, 'x' x2
        String map = glyphmapOf(latex);
        assertTrue(map.matches("^[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*$"),
            "glyphmap must match the runtime's contract grammar: " + map);
        int pathCount = 0;
        Matcher m = Pattern.compile("<path\\b").matcher(com.lattex.api.LatteX.render(latex));
        while (m.find()) {
            pathCount++;
        }
        for (String run : map.split(";")) {
            for (String idx : run.split(":")[1].split(",")) {
                assertTrue(Integer.parseInt(idx) < pathCount,
                    "index " + idx + " out of bounds (" + pathCount + " paths) in " + map);
            }
        }
    }

    @Test
    void renderStyledHtmlStampsTheGlyphmapOnlyForAThreadEffect() {
        // The wiring payoff: a \lx thread effect now produces a REAL sidecar (not a
        // hand-stamped fixture), so the container drives the thread runtime.
        String threaded = com.lattex.api.LatteX.renderStyledHtml("\\lx[fx.hover=thread]{x + x}");
        assertTrue(threaded.contains("data-lx-glyphmap=\"78:0,2\""),
            "thread effect must stamp the token-identity sidecar: " + threaded);
        // Non-thread effects and plain math never stamp it (inert + wasted bytes).
        assertFalse(com.lattex.api.LatteX.renderStyledHtml("\\lx[fx.hover=glow]{x + x}")
            .contains("data-lx-glyphmap"), "non-thread effect must not stamp a glyphmap");
        assertFalse(com.lattex.api.LatteX.renderStyledHtml("x + x").contains("data-lx-glyphmap"),
            "plain math must not stamp a glyphmap");
    }

    @Test
    void indicesIdentifyTheSameGlyphOnASkippableCorpus() {
        // The strong coupling check: corpora where the glyphmap index != glyph position,
        // so a mis-counted skip would land on the WRONG path. x\phantom{y}x -> 78:0,1
        // (phantom inkless, skipped); \sum x + x -> 78:1,3 (\sum is inked but NO_SOURCE at
        // index 0); \frac{x}{x}+x -> 78:0,1,3 (the fraction bar is a <rect>, not a path).
        // Every index in a run must point to BYTE-IDENTICAL path data (the same glyph).
        for (String latex : java.util.List.of("x \\phantom{y} x", "\\sum x + x", "\\frac{x}{x} + x")) {
            String map = glyphmapOf(latex);
            java.util.List<String> paths = pathData(com.lattex.api.LatteX.render(latex));
            for (String run : map.split(";")) {
                String[] idxs = run.split(":")[1].split(",");
                String glyph0 = paths.get(Integer.parseInt(idxs[0]));
                for (String idx : idxs) {
                    assertEquals(glyph0, paths.get(Integer.parseInt(idx)),
                        "run " + run + " in [" + latex + "] points to differing glyph outlines "
                            + "— a skip miscount would land on the wrong path");
                }
            }
        }
    }

    private static java.util.List<String> pathData(String svg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("<path d=\"([^\"]*)\"").matcher(svg);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
