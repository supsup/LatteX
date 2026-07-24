package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * COLD-CACHE concurrency gate for the {@code glyphPathData} memo added by the font/emission
 * caching refactor (plan 725c1488, Marlow audit LTX-03). The existing outline-cache
 * concurrency test ({@code FontCacheCorrectnessTest#concurrentFirstAccessYieldsCorrectOutlines})
 * covers the OUTLINE cache only; the new {@link SfntFont#glyphPathData(int, IntFunction)}
 * cache — shared on the immutable font, first-accessed concurrently by the three emission
 * consumers (SVG / glyphmap / groupmap) within a single render — was never raced from COLD.
 *
 * <p>This test races that memo COLD: for each probed glyph id it uses a FRESH
 * {@link SfntFont#loadBundled()} whose {@code glyphPathData} cache is empty, then releases
 * N threads through a {@link CyclicBarrier} so they all reach the same cold key at the same
 * instant. The producer is the REAL production one (the closure {@code SvgEmitter} passes,
 * {@code gid -> GlyphPath.toPathData(font.outline(gid))}), wrapped to count invocations.
 *
 * <p>It asserts, without pre-warming the cold font's path cache:
 * <ul>
 *   <li><b>identical values</b>: every racing thread observes the same path string, equal to
 *       an independently computed reference;</li>
 *   <li><b>exactly-once producer</b>: the wrapped producer's invocation counter is exactly 1
 *       for the cold key — {@code computeIfAbsent} collapses the storm to a single successful
 *       producer run, so no thread sees a half-built or duplicate value.</li>
 * </ul>
 */
class GlyphPathCacheColdConcurrencyTest {

    /** The genuine emission producer, per {@code SvgEmitter}: pure function of the glyph id. */
    private static String realPathData(SfntFont font, int gid) {
        return GlyphPath.toPathData(font.outline(gid));
    }

    @Test
    void coldGlyphPathCacheRacedByManyThreadsRunsProducerExactlyOnce() throws Exception {
        SfntFont ref = SfntFont.loadBundled();
        // A spread of inked glyph ids (letters, a digit, an operator) so the race is not a
        // single lucky key; each is raced on its own fresh cold font.
        int[] glyphIds = {
            ref.glyphId('x'), ref.glyphId('y'), ref.glyphId('2'),
            ref.glyphId('+'), ref.glyphId('='), ref.glyphId('a'),
        };

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int gid : glyphIds) {
                assertTrue(gid > 0, "probe glyph id must be inked, got " + gid);
                // Reference value computed independently (a separate warm font); the COLD
                // font under test is never touched until the concurrent storm below.
                String expected = realPathData(ref, gid);

                SfntFont cold = SfntFont.loadBundled();
                AtomicInteger producerCalls = new AtomicInteger(0);
                IntFunction<String> countingProducer = g -> {
                    producerCalls.incrementAndGet();
                    return realPathData(cold, g);
                };

                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Callable<String>> jobs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    jobs.add(() -> {
                        // All threads block here, then hit the cold key simultaneously.
                        barrier.await();
                        return cold.glyphPathData(gid, countingProducer);
                    });
                }

                List<Future<String>> results = pool.invokeAll(jobs);
                for (Future<String> f : results) {
                    assertEquals(expected, f.get(),
                        "every racing thread must see the identical cached path for glyph " + gid);
                }
                // The load-bearing assertion: the cold-key storm collapsed to ONE producer run.
                assertEquals(1, producerCalls.get(),
                    "glyphPathData producer must run exactly once for cold glyph " + gid
                        + " despite " + threads + " simultaneous first-accessors");
                // Post-storm serial read still returns the same value (non-corrupt entry).
                assertEquals(expected, cold.glyphPathData(gid, countingProducer),
                    "post-race serial read matches; a cache hit must not re-run the producer");
                assertEquals(1, producerCalls.get(),
                    "a post-race cache HIT must not invoke the producer again for glyph " + gid);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
