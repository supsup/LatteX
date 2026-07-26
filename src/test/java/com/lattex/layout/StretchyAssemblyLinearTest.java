package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.Outcome;
import com.lattex.api.RenderResult;
import com.lattex.font.GlyphPart;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathSyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 5a594a59 (Marlow audit LTX-02): the stretchy-glyph assembly repeat count is
 * now derived in closed form ({@link LayoutEngine#assemblyRepetitions}) and the built
 * pieces are charged to the L10 layout-box budget, replacing the old
 * {@code rep=1}..fixpoint {@code expand+rescan} loop (O(R²)).
 *
 * <p>The correctness bar: the closed form must select EXACTLY the rep the old loop's
 * fixpoint selects for every input. This suite proves it by running BOTH the old loop
 * logic (as a faithful reference over the real {@link LayoutEngine#expandAssembly} +
 * {@link LayoutEngine#assemblySpanDesign} helpers) and the closed form across a broad
 * sweep of {@code (fixed-span, extender-advance, overlap, minSize)} tuples, plus:
 * a byte-identity ladder of rendered {@code \overrightarrow} sizes vs pre-change golden
 * hashes; and a budget-breach fixture with its just-under sibling.
 */
class StretchyAssemblyLinearTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    // ------------------------------------------------------------------
    // Fixtures — synthetic assemblies (the algebra is font-independent).
    // ------------------------------------------------------------------

    private static GlyphPart fixed(int advance) {
        return new GlyphPart(1, advance, advance, advance, 0);
    }

    private static GlyphPart extender(int advance) {
        return new GlyphPart(2, advance, advance, advance, GlyphPart.EXTENDER_FLAG);
    }

    /**
     * Faithful reference for the ORIGINAL loop's SELECTION: rep starts at 1 and steps
     * while {@code span(rep) < minSize}, exactly the old {@code while} condition. Returns
     * {@link LayoutEngine#DIVERGENT} if it fails to terminate within {@code cap}
     * iterations (the non-positive-step case the old code would spin on forever).
     *
     * <p>Span is computed by {@link #spanOf} — an O(parts) closed sum that
     * {@link #referenceSpanMatchesTheRealBuildAndScanHelpers()} proves is bit-identical
     * to the real {@code assemblySpanDesign(expandAssembly(parts, rep), overlap)} the old
     * loop used. That equivalence lets the reference avoid the old O(R²) list rebuild
     * (which is precisely the defect under repair) while staying faithful to its result.
     */
    private static long loopRep(List<GlyphPart> parts, int overlap, int minSize, long cap) {
        boolean hasExtender = parts.stream().anyMatch(GlyphPart::isExtender);
        long rep = 1;
        while (hasExtender && spanOf(parts, overlap, rep) < minSize) {
            rep++;
            if (rep > cap) {
                return LayoutEngine.DIVERGENT; // did not terminate within the cap
            }
        }
        return rep;
    }

    /** Long-form span(rep) = A + rep·B; equivalence to the real helpers is test-pinned. */
    private static long spanOf(List<GlyphPart> parts, int overlap, long rep) {
        long sum = 0;
        long count = 0;
        for (GlyphPart p : parts) {
            long times = p.isExtender() ? rep : 1;
            sum += (long) p.fullAdvance() * times;
            count += times;
        }
        return sum - (long) overlap * (count - 1);
    }

    @Test
    void referenceSpanMatchesTheRealBuildAndScanHelpers() {
        // Pins the fast reference span against the ACTUAL build+scan the old loop ran,
        // so loopRep is a faithful stand-in for the original code, not a re-derivation.
        List<List<GlyphPart>> shapes = List.of(
            List.of(fixed(30), extender(100)),
            List.of(fixed(0), extender(100), extender(40), fixed(250)),
            List.of(extender(12)),
            List.of(fixed(100), fixed(50)));
        for (List<GlyphPart> parts : shapes) {
            for (int overlap : new int[] {0, 7, 40, 100, 250}) {
                for (int rep = 1; rep <= 40; rep++) {
                    int real = LayoutEngine.assemblySpanDesign(
                        LayoutEngine.expandAssembly(parts, rep), overlap);
                    assertEquals((long) real, spanOf(parts, overlap, rep),
                        "spanOf drift: parts=" + describe(parts)
                        + " overlap=" + overlap + " rep=" + rep);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Part 1 — closed form == old loop, broad sweep (red-first history: this
    // sweep was first written against the CURRENT code with a naive closed form
    // and watched to FAIL before the correct linear form was committed).
    // ------------------------------------------------------------------

    @Test
    void closedFormMatchesLoopAcrossBroadSweep() {
        // Iteration cap is far above every FINITE rep this sweep can produce, so a
        // reference DIVERGENT is genuine divergence (non-positive step), never a
        // finite-but-large false positive.
        final long cap = 200_000;
        int tuples = 0;
        int divergentSeen = 0;
        int repOneSeen = 0;
        int boundarySeen = 0;
        int largeRepSeen = 0;

        int[] fixedAdvances = {0, 30, 100, 250};      // 0 ⇒ a zero-width fixed end
        int[][] extenderSets = {
            {},                 // no extenders  → rep must be 1
            {100},              // one extender
            {100, 40},          // two extenders (mixed advances)
            {12},               // tiny extender  → drives large reps / divergence
        };
        // minSize span includes negatives, zero, tiny, exact boundaries, and large.
        int[] minSizes = {-5, 0, 1, 50, 99, 100, 101, 199, 200, 201, 400, 999, 5000, 40_000};

        for (int fa : fixedAdvances) {
            for (int nf : new int[] {0, 1, 2}) {
                for (int[] extAdv : extenderSets) {
                    List<GlyphPart> parts = new ArrayList<>();
                    for (int i = 0; i < nf; i++) {
                        parts.add(fixed(fa));
                    }
                    for (int adv : extAdv) {
                        parts.add(extender(adv));
                    }
                    if (parts.isEmpty()) {
                        continue; // an assembly always has at least one part
                    }
                    // Overlaps from 0 up to just past the largest advance — deliberately
                    // includes overlap ≥ advance (the non-positive-step edge).
                    int maxAdv = Math.max(fa, extAdv.length == 0 ? 0
                        : java.util.Arrays.stream(extAdv).max().getAsInt());
                    for (int overlap = 0; overlap <= maxAdv + 5; overlap += 7) {
                        for (int minSize : minSizes) {
                            long expected = loopRep(parts, overlap, minSize, cap);
                            long actual = LayoutEngine.assemblyRepetitions(parts, overlap, minSize);
                            assertEquals(expected, actual,
                                "rep mismatch: parts=" + describe(parts)
                                + " overlap=" + overlap + " minSize=" + minSize);
                            tuples++;
                            if (actual == LayoutEngine.DIVERGENT) {
                                divergentSeen++;
                            } else if (actual == 1) {
                                repOneSeen++;
                            } else {
                                if (actual >= 100) {
                                    largeRepSeen++;
                                }
                                // Exact-boundary detection: minSize equals span(rep).
                                if (spanOf(parts, overlap, actual) == minSize) {
                                    boundarySeen++;
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("STRETCHY_SWEEP tuples=" + tuples
            + " divergent=" + divergentSeen + " repOne=" + repOneSeen
            + " boundary=" + boundarySeen + " largeRep=" + largeRepSeen);
        // The sweep must actually exercise each interesting region, else it is vacuous
        // (mixed-fixture discipline: a negative class needs a positive instance too).
        assertTrue(tuples > 3_000, "sweep too small: " + tuples);
        assertTrue(divergentSeen > 0, "sweep never hit the divergent (non-positive-step) case");
        assertTrue(repOneSeen > 0, "sweep never hit the rep==1 case");
        assertTrue(boundarySeen > 0, "sweep never hit an exact minSize==span boundary");
        assertTrue(largeRepSeen > 0, "sweep never hit a large rep (≥100)");
    }

    private static String describe(List<GlyphPart> parts) {
        StringBuilder sb = new StringBuilder("[");
        for (GlyphPart p : parts) {
            sb.append(p.isExtender() ? "E" : "F").append(p.fullAdvance()).append(' ');
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------
    // Part 1b — pinned edge cases (explicit, human-readable).
    // ------------------------------------------------------------------

    @Test
    void noExtenderIsAlwaysRepOne() {
        List<GlyphPart> parts = List.of(fixed(100), fixed(50));
        // Even with a minSize far above the fixed span, the loop never repeats → rep 1.
        assertEquals(1, LayoutEngine.assemblyRepetitions(parts, 10, 1_000_000));
        assertEquals(1, LayoutEngine.assemblyRepetitions(parts, 0, 5));
    }

    @Test
    void spanAlreadyReachesTargetIsRepOne() {
        List<GlyphPart> parts = List.of(fixed(100), extender(100));
        // span(1) = 200 - overlap. minSize below that ⇒ rep 1.
        assertEquals(1, LayoutEngine.assemblyRepetitions(parts, 0, 200)); // exact: span(1)=200
        assertEquals(1, LayoutEngine.assemblyRepetitions(parts, 0, 199));
    }

    @Test
    void nonPositiveStepDivergesExactlyAsTheLoopWould() {
        // Single extender, overlap == advance ⇒ B = advance - overlap = 0: every added
        // extender is fully eaten by the overlap, span never grows. The OLD loop spins
        // forever; the closed form returns DIVERGENT. Confirm the loop truly diverges.
        List<GlyphPart> parts = List.of(fixed(100), extender(60));
        int overlap = 60; // == extender advance ⇒ step 0
        int minSize = 10_000; // unreachable
        assertEquals(LayoutEngine.DIVERGENT,
            LayoutEngine.assemblyRepetitions(parts, overlap, minSize));
        // Reference loop, bounded: it never terminates → reports DIVERGENT at the cap.
        assertEquals(LayoutEngine.DIVERGENT, loopRep(parts, overlap, minSize, 50_000));

        // overlap > advance ⇒ B < 0 (span SHRINKS with rep): also divergent.
        List<GlyphPart> parts2 = List.of(fixed(100), extender(50));
        assertEquals(LayoutEngine.DIVERGENT,
            LayoutEngine.assemblyRepetitions(parts2, 80, 10_000));
        assertEquals(LayoutEngine.DIVERGENT, loopRep(parts2, 80, 10_000, 50_000));
    }

    @Test
    void nonPositiveStepButTargetAlreadyMetIsRepOneNotDivergent() {
        // B ≤ 0 but span(1) already ≥ minSize ⇒ the loop never iterates ⇒ rep 1
        // (divergence only when the target is actually unreachable).
        List<GlyphPart> parts = List.of(fixed(100), extender(60));
        int overlap = 60; // step 0
        // span(1) = 100 + 60 - 60 = 100. minSize ≤ 100 ⇒ rep 1.
        assertEquals(1, LayoutEngine.assemblyRepetitions(parts, overlap, 100));
        assertEquals(1L, loopRep(parts, overlap, 100, 50_000));
        assertEquals(1, LayoutEngine.assemblyRepetitions(parts, overlap, 50));
    }

    @Test
    void degenerateLargeButFiniteRepIsPreservedNotClamped() {
        // B a tiny positive step (advance just above overlap) + a huge minSize ⇒ a
        // very large but FINITE rep. The closed form must return it verbatim (the
        // budget, not a clamp, is what protects the call site). Verified by the
        // minimality invariant span(rep-1) < minSize ≤ span(rep) — i.e. exactly the
        // rep the loop would select — WITHOUT running the loop 10^8 times.
        List<GlyphPart> parts = List.of(fixed(0), extender(101));
        int overlap = 100;          // B = 101 - 100 = 1
        int minSize = 90_000_000;   // A = 0 (nf=1, v·(nf-1)=0); need = 90_000_000
        long rep = LayoutEngine.assemblyRepetitions(parts, overlap, minSize);
        // A=0, B=1 ⇒ rep = ceil(90_000_000 / 1) = 90_000_000.
        assertEquals(90_000_000L, rep);
        assertTrue(spanOf(parts, overlap, rep) >= minSize, "span(rep) reaches target");
        assertTrue(spanOf(parts, overlap, rep - 1) < minSize, "rep is minimal (rep-1 falls short)");
    }

    @Test
    void exactBoundaryEqualitySelectsThatRep() {
        // Construct minSize == span(rep) exactly for rep = 3 and confirm the closed
        // form picks 3 (the loop stops on ≥, so equality is satisfied at 3, not 4).
        List<GlyphPart> parts = List.of(fixed(20), extender(50)); // nf=1, ne=1
        int overlap = 10;
        // A = 20 - 10*0 = 20 ; B = 50 - 10 = 40 ; span(rep) = 20 + 40*rep.
        int minSize = 20 + 40 * 3; // = 140, exactly span(3)
        assertEquals(3L, LayoutEngine.assemblyRepetitions(parts, overlap, minSize));
        assertEquals(3L, loopRep(parts, overlap, minSize, 1000));
        // One design-unit higher ⇒ rep must step to 4.
        assertEquals(4L, LayoutEngine.assemblyRepetitions(parts, overlap, minSize + 1));
        assertEquals(4L, loopRep(parts, overlap, minSize + 1, 1000));
    }

    // ------------------------------------------------------------------
    // Part 2 — budget: generated pieces charged to the L10 box budget, with a
    // just-under sibling (mixed-fixture rule).
    // ------------------------------------------------------------------

    @Test
    void assemblyBudgetBreachTripsAndJustUnderPasses() {
        // A = 0 (single zero fixed end), B = advance = 100, overlap 0 ⇒
        // rep = ceil(minSize/100), pieceCount = 1 (fixed) + rep.
        List<GlyphPart> parts = List.of(fixed(0), extender(100));
        int overlap = 0;

        // Just UNDER the 100k budget: rep ≈ 49_999, ~50_000 pieces → builds fine.
        resetBudget();
        int underMinSize = 100 * 49_999;                     // rep = 49_999
        List<GlyphPart> built = LayoutEngine.expandBudgetedAssembly(parts, overlap, underMinSize);
        assertEquals(50_000, built.size(), "just-under sibling builds the full stack");

        // Just OVER: rep ≈ 120_000 → ~120_001 pieces > 100k budget → typed cap trip.
        resetBudget();
        int overMinSize = 100 * 120_000;                     // rep = 120_000
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> LayoutEngine.expandBudgetedAssembly(parts, overlap, overMinSize));
        assertTrue(ex.isCapExceeded(), "assembly-budget trip is typed as a resource cap");
        assertTrue(ex.getMessage().contains("box budget"), ex.getMessage());
    }

    @Test
    void divergentAssemblyTripsTheBudgetInsteadOfHanging() {
        // Non-positive step (overlap ≥ advance) with an unreachable target: the old
        // loop would hang; expandBudgetedAssembly must trip the budget promptly.
        resetBudget();
        List<GlyphPart> parts = List.of(fixed(100), extender(60));
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> LayoutEngine.expandBudgetedAssembly(parts, 60, 10_000));
        assertTrue(ex.isCapExceeded(), "divergent assembly trips a resource cap");
        assertTrue(ex.getMessage().contains("box budget"), ex.getMessage());
    }

    /** Reset the per-thread L10 box budget to a small deterministic residual. */
    private static void resetBudget() {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        LayoutEngine.layout(new Atom('x', MathClass.ORD), ctx); // resets to 0, lays 1 box
    }

    // ------------------------------------------------------------------
    // Part 3 — byte-identity of rendered output vs pre-change golden hashes.
    // Goldens captured from the CURRENT (pre-change) code before the edit; the
    // closed form selecting the same rep ⇒ identical expanded stack ⇒ identical SVG.
    // These \overrightarrow bodies exercise the assembly path (~1 arrow piece per
    // ~few body chars). 3072+ char bodies already trip the emitter byte cap and
    // are covered separately below.
    // ------------------------------------------------------------------

    private static final Map<Integer, String> GOLDEN_SHA256 = Map.of(
        256,  "13e2a7aba683d2e13e9700efd317eba460de2c7387e71ec89bd578b6917fe130",
        512,  "e9ce681c95de46de918115bfb088c9699fb513220b7e877b2638367269bfc927",
        1024, "663aa6ace35618c6331147e3d493f777b5f150f5a9081598c079d4a4583ce79c",
        2048, "065c99230487769b0483ed45a52aa379e1c5bf8511c792f7e22f01971e211cf9");

    @Test
    void overrightarrowLadderIsByteIdenticalToPreChangeRenders() {
        GOLDEN_SHA256.forEach((n, expectedHash) -> {
            String latex = "\\overrightarrow{" + "a".repeat(n) + "}";
            RenderResult r = LatteX.renderWithDiagnostics(latex);
            assertEquals(Outcome.OK, r.diagnostics().outcome(), "n=" + n + " renders OK");
            assertEquals(expectedHash, sha256(r.svg()),
                "n=" + n + ": rendered SVG must be byte-identical to the pre-change golden");
        });
    }

    @Test
    void ladderAbovePreChangeCapStaysCapped() {
        // 3072 / 4096 tripped the emitter output cap before the change; still must.
        for (int n : new int[] {3072, 4096}) {
            String latex = "\\overrightarrow{" + "a".repeat(n) + "}";
            RenderResult r = LatteX.renderWithDiagnostics(latex);
            assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, r.diagnostics().outcome(),
                "n=" + n + " stays output-capped");
            assertEquals("", r.svg(), "n=" + n + " emits no SVG");
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
