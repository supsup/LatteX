package com.lattex.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Correctness gates for the immutable font hot-path caches (plan 725c1488, Marlow
 * audit LTX-03): {@code SfntFont.outline} memoization, and the cmap format-12 binary
 * search that replaces the old linear group scan.
 *
 * <ul>
 *   <li><b>Cache-hit-equals-miss</b>: {@code outline(g)} on the 2nd call is EQUAL to
 *       the 1st across a wide glyph-id sweep (a memoization bug that returned a wrong
 *       or stale outline would diverge here).</li>
 *   <li><b>cmap binary-search == linear scan</b>: a SHA-256 golden over
 *       {@code glyphId(cp)} for a full code-point sweep (BMP + non-BMP math planes +
 *       misses + segment boundaries), pinned from the PRISTINE linear-scan tree. The
 *       binary search must reproduce it byte-for-byte.</li>
 *   <li><b>Concurrency smoke</b>: parallel first-access to {@code outline} for the same
 *       and for different glyph ids yields the correct outline for each — the caches are
 *       on the shared immutable font, so a first-access race must not corrupt them.</li>
 * </ul>
 */
class FontCacheCorrectnessTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    /**
     * SHA-256 of {@code glyphId(cp)} for cp in [0, 0x2FFFF], captured from the pristine
     * pre-caching (linear-scan) tree. The binary-search cmap must reproduce this exactly —
     * this IS the "binary search returns the SAME glyphId as the old linear scan" proof
     * across the whole sweep (including non-BMP math alphanumerics and the miss regions).
     */
    private static final String CMAP_SWEEP_GOLDEN =
        "f954bda2c3507da317ae35fce7b2498be7db744ccc8a9e1e50090ae4c8c0bbfe";

    private static final int SWEEP_MAX = 0x2FFFF;

    static String cmapSweepHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int cp = 0; cp <= SWEEP_MAX; cp++) {
                md.update((cp + ":" + FONT.glyphId(cp) + ";").getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void cmapBinarySearchMatchesPinnedLinearGolden() {
        assertEquals(CMAP_SWEEP_GOLDEN, cmapSweepHash(),
            "cmap glyphId sweep changed vs the pinned linear-scan golden — the binary "
            + "search is not equivalent. If PRISTINE-capturing, re-pin to: " + cmapSweepHash());
    }

    @Test
    void cmapKnownBoundariesResolve() {
        // A concrete oracle in addition to the hash: known glyphs, a non-BMP math char,
        // and guaranteed misses (unassigned / out-of-range).
        assertTrue(FONT.glyphId('x') > 0, "x resolves");
        assertTrue(FONT.glyphId('2') > 0, "2 resolves");
        // Mathematical bold capital A (U+1D400) — a non-BMP code point only a format-12
        // subtable covers; exercises the binary search past the BMP.
        assertTrue(FONT.glyphId(0x1D400) > 0, "non-BMP math bold A resolves");
        assertEquals(0, FONT.glyphId(0x10FFFF), "top-of-Unicode unmapped → .notdef");
        assertEquals(0, FONT.glyphId(0x0E00), "an unmapped BMP gap → .notdef");
    }

    @Test
    void outlineCacheHitEqualsMissAcrossGlyphSweep() {
        int n = FONT.numGlyphs();
        int step = Math.max(1, n / 600); // sample ~600 glyphs across the whole font
        int checked = 0;
        for (int g = 0; g < n; g += step) {
            GlyphOutline first = FONT.outline(g);
            GlyphOutline second = FONT.outline(g);
            assertEquals(first, second, "outline(" + g + ") 2nd call equals 1st");
            // A memoized immutable artifact SHOULD hand back the same instance on a hit.
            assertSame(first, second, "outline(" + g + ") is memoized (same instance)");
            checked++;
        }
        assertTrue(checked > 100, "sweep must cover breadth, checked only " + checked);
    }

    @Test
    void distinctGlyphsHaveDistinctOutlinesNotCollapsed() {
        // Guard against a cache keyed wrongly (e.g. constant key): two different inked
        // glyphs must not share an outline.
        int gx = FONT.glyphId('x');
        int g2 = FONT.glyphId('2');
        assertNotEquals(FONT.outline(gx), FONT.outline(g2),
            "distinct glyphs must not collapse to one cached outline");
    }

    @Test
    void concurrentFirstAccessYieldsCorrectOutlines() throws Exception {
        // Expected outlines from the already-warm shared FONT; the concurrency target is a
        // freshly-loaded font whose cache is COLD, so the parallel reads below are genuine
        // first accesses that race to populate the same/different keys.
        int gx = FONT.glyphId('x');
        int g2 = FONT.glyphId('2');
        int gPlus = FONT.glyphId('+');
        SfntFont cold = SfntFont.loadBundled();
        GlyphOutline expectX = FONT.outline(gx);
        GlyphOutline expect2 = FONT.outline(g2);
        GlyphOutline expectPlus = FONT.outline(gPlus);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int which = t % 3;
            tasks.add(() -> {
                for (int i = 0; i < 500; i++) {
                    int g = which == 0 ? gx : which == 1 ? g2 : gPlus;
                    GlyphOutline expected = which == 0 ? expectX : which == 1 ? expect2 : expectPlus;
                    if (!cold.outline(g).equals(expected)) {
                        return false;
                    }
                }
                return true;
            });
        }
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();
        for (Future<Boolean> f : results) {
            assertTrue(f.get(), "concurrent first-access returned a correct outline");
        }
        // Post-storm, a serial read still matches — the concurrent population left a
        // correct, non-corrupt cache entry.
        assertEquals(expectX, cold.outline(gx));
    }
}
