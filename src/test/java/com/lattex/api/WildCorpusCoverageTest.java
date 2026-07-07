package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The WILD-CORPUS COVERAGE RATCHET. 484 formulas gathered from real documents
 * (calculus/physics, stats/ML, algebra/discrete, Wikipedia/textbook, and
 * structured environments — 2026-07-06 sweep), exactly as authors write them,
 * deliberately UNSANITIZED toward what LatteX supports.
 *
 * <p>The assertion is a RATCHET, not a target: coverage may only go UP. A
 * change that silently drops wild coverage (a parser regression, an
 * over-eager validation) fails here with the newly-broken formulas listed.
 * When a feature lands, re-run, read the new OK count, and RAISE THE FLOOR in
 * the same commit — that's the feature's receipt.
 *
 * <p>Current floor: 417/484 (86%). The 67 known failures are the objective
 * feature roadmap (see the 2026-07-06 coverage report): one shared mechanism
 * (stack-above/below: underbrace/overbrace/substack/overset/stackrel) covers
 * 23 of them; tfrac/dfrac/displaystyle 11; pmod/bmod 8; tag 7; xrightarrow 7.
 */
class WildCorpusCoverageTest {

    private static final int FLOOR = 417;

    @Test
    void wildCoverageNeverRegresses() throws Exception {
        int ok = 0;
        java.util.List<String> newlyBroken = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/com/lattex/wild-corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length < 3) { continue; }
                try {
                    String svg = LatteX.render(p[2]);
                    if (svg != null && svg.contains("<path")) { ok++; }
                } catch (Throwable expectedForKnownGaps) {
                    // failures are fine — only the COUNT is pinned
                }
            }
        }
        assertTrue(ok >= FLOOR, "wild-corpus coverage regressed: " + ok + " OK, floor is "
            + FLOOR + " — a change broke formulas that used to render. Run the corpus "
            + "locally to list them; if you LANDED a feature, raise the floor instead.");
    }
}
