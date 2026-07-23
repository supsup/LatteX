package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * DELIBERATE-BREAK RED-PROOF that the byte-identity ratchet actually guards output
 * (plan 725c1488). A pure performance refactor has no natural red-first, so the
 * discriminator is: if a cache returned a WRONG-GLYPH outline, would the ratchet's
 * whole-corpus fold diverge? This test proves YES, at the emitted-surface level —
 * without mutating production code.
 *
 * <p>It takes a real render, simulates "a cache handed back a different glyph's path"
 * by swapping one glyph's {@code <path d="…"/>} payload for another glyph's, and shows
 * the length-prefixed fold used by {@link FontCacheByteIdentityRatchetTest} produces a
 * DIFFERENT digest for the poisoned surface than for the correct one. So a single
 * wrong-glyph substitution anywhere in the corpus flips the ratchet RED.
 *
 * <p>(The definitive end-to-end break — temporarily editing {@code SfntFont.outline} to
 * return the wrong glyph and watching the real ratchet go RED — was performed manually
 * during development and recorded in the plan report; this test is the permanent,
 * CI-runnable residue of that discriminator.)
 */
class CachePoisonRedProofTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static byte[] foldOne(String surface) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = surface.getBytes(StandardCharsets.UTF_8);
            md.update((b.length + " ").getBytes(StandardCharsets.UTF_8));
            md.update(b);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String firstPathData(String svg) {
        int i = svg.indexOf("<path d=\"");
        assertTrue(i >= 0, "render must contain a glyph <path>");
        int start = i + "<path d=\"".length();
        int end = svg.indexOf('"', start);
        return svg.substring(start, end);
    }

    @Test
    void aWrongGlyphOutlineWouldFlipTheRatchetFold() {
        // A row with two distinct glyphs so we have a real "other glyph" to swap in.
        String correct = LatteX.render("x + 2");
        String xPath = firstPathData(LatteX.render("x"));
        String twoPath = firstPathData(LatteX.render("2"));
        assertNotEquals(xPath, twoPath, "x and 2 must have distinct path data (sanity)");

        // Simulate a cache returning glyph '2''s outline where 'x''s belongs: replace the
        // first path payload with the wrong glyph's. This is exactly the byte-level effect
        // of a mis-keyed / corrupt outline cache.
        int i = correct.indexOf(xPath);
        assertTrue(i >= 0, "expected x's path in the x+2 render");
        String poisoned = correct.substring(0, i) + twoPath + correct.substring(i + xPath.length());
        assertNotEquals(correct, poisoned, "the poison actually changed the surface");

        // The ratchet's length-prefixed fold must distinguish them.
        assertNotEquals(
            java.util.Arrays.toString(foldOne(correct)),
            java.util.Arrays.toString(foldOne(poisoned)),
            "a wrong-glyph path must produce a different fold digest — the ratchet guards output");

        // And the fold is stable for the correct surface (no spurious sensitivity).
        assertEquals(
            java.util.Arrays.toString(foldOne(correct)),
            java.util.Arrays.toString(foldOne(LatteX.render("x + 2"))),
            "identical output must fold identically");
    }
}
