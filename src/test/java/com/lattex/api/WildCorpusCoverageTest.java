package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The WILD-CORPUS COVERAGE RATCHET. 502 formulas gathered from real documents
 * (calculus/physics, stats/ML, algebra/discrete, Wikipedia/textbook, structured
 * environments, and the 2026-07-23 math.PR follow-up), exactly as authors write
 * them, deliberately UNSANITIZED toward what LatteX supports.
 *
 * <p>Each row carries a STATUS: {@code OK} (rendered at sweep time — the
 * PASS-SET) or {@code GAP} (a known failure, the feature roadmap). The pins:
 *
 * <ul>
 *   <li><b>Every OK row must still render</b> — a regression fails NAMING the
 *       newly-broken formulas. (A bare count-floor had a swap hole: break 5
 *       old + fix 5 new = same count = green. Fixpoint, lattex/47.)</li>
 *   <li><b>GAP rows may flip to OK</b> — that's a feature landing. Flip the
 *       status in the same commit; the flipped rows join the pass-set and can
 *       never silently regress again. That status-flip IS the ratchet.</li>
 * </ul>
 */
class WildCorpusCoverageTest {

    /// The enforced pass-set floor, named so it can be CITED rather than re-typed.
    ///
    /// It was a bare literal inside the assertion, which is how the README came to claim
    /// 484/484 four releases after the corpus reached 502: nothing connected the prose to
    /// the pin, so the prose could only be kept true by someone remembering to. Now
    /// {@code ReadmeCorpusFigureTest} reads THIS constant, so the doc and the ratchet cannot
    /// disagree without a red test (plan 398daca1 item 1).
    static final int PASS_SET_FLOOR = 502;

    @Test
    void everyPassSetFormulaStillRenders() throws Exception {
        List<String> broken = new ArrayList<>();
        int okRows = 0;
        int newlyRendering = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/com/lattex/wild-corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 4);
                if (p.length < 4) { continue; }
                boolean renders;
                try {
                    String svg = LatteX.render(p[3]);
                    renders = svg != null && svg.contains("<path");
                } catch (Throwable t) {
                    renders = false;
                }
                if ("OK".equals(p[0])) {
                    okRows++;
                    if (!renders) { broken.add(p[3]); }
                } else if (renders) {
                    newlyRendering++; // a GAP closed — flip its status to OK!
                }
            }
        }
        assertTrue(okRows >= PASS_SET_FLOOR, "pass-set shrank in the TSV itself: " + okRows
            + " OK rows (current floor " + PASS_SET_FLOOR
            + "; started at 417) — statuses may only flip GAP->OK");
        assertTrue(broken.isEmpty(), broken.size()
            + " previously-rendering formulas REGRESSED:\n  "
            + String.join("\n  ", broken.subList(0, Math.min(10, broken.size())))
            + (broken.size() > 10 ? "\n  ... and " + (broken.size() - 10) + " more" : ""));
        if (newlyRendering > 0) {
            System.out.println("wild-corpus: " + newlyRendering + " GAP row(s) now render"
                + " — flip their status to OK to ratchet them into the pass-set.");
        }
    }
}
