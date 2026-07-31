package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link SvgEmitter#groupmap} as a PRODUCER-SIDE contract (plan a0cf41ff), not merely
 * a behavioral spec. {@link GroupmapTest} exercises the ranking logic (which glyph lands in
 * which rank, deepest-first); this class exists purely so a change to the SERIALIZED
 * GRAMMAR — the separator, the joiner, the digit encoding — breaks LatteX's own build
 * before it ever reaches a downstream consumer, rather than silently drifting.
 *
 * <p>The downstream consumer is the Stafficy MD-&gt;HTML sanitizer's
 * {@code MathMarkerConverter.GROUPMAP_VALUE} (stafficy repo, {@code modules/content}),
 * which enforces {@link #CONSUMER_GROUPMAP_GRAMMAR} and STRIPS the {@code data-lx-groupmap}
 * attribute outright when a value fails to match — a silent loss of the precedence-cascade
 * effect, not a visible error. Grammar drift on the producer side must fail loud HERE.
 *
 * <p><b>Capped-path assertion (task item 4d) — intentionally omitted.</b>
 * {@link OutputCapPostconditionTest#theCappedBuilderFailsClosedRegardlessOfSource} already
 * documents that the sidecar surface is "not reachable through the bounded layout" — driving
 * a REAL {@code \left..\right} corpus large enough to push the ranked-index serialization
 * past {@link SvgEmitter#MAX_OUTPUT_CHARS} (2,000,000 chars) would need on the order of
 * hundreds of thousands of distinct fence ranks, which is not a reasonable unit-test fixture
 * (parse/layout cost alone, independent of this contract). Grammar-safety of a REFUSED
 * (thrown) capped path is moot anyway: {@link SvgEmitter.CappedBuilder#checked()} never
 * returns a partial string on overflow — it throws {@code MathSyntaxException} instead
 * (pinned generically by {@code theCappedBuilderFailsClosedRegardlessOfSource}), so there is
 * no "truncated mid-run string" code path for groupmap to leak in the first place.
 */
class GroupmapGrammarContractTest {

    /**
     * Pinned copy of the Stafficy sanitizer's {@code MathMarkerConverter.GROUPMAP_VALUE}
     * (stafficy repo, {@code modules/content}). If a change here is intentional, the
     * consumer must be updated IN THE SAME WINDOW or fence-nested math loses its cascade
     * attribute silently (the sanitizer strips non-conforming values).
     */
    static final Pattern CONSUMER_GROUPMAP_GRAMMAR =
        Pattern.compile("^[0-9]+:[0-9]+(,[0-9]+)*(;[0-9]+:[0-9]+(,[0-9]+)*)*$");

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static String groupmapOf(String latex) {
        Layout laid = LayoutEngine.layout(MathParser.parse(latex),
            new LayoutContext(FONT, FONT.mathConstants(), 40.0));
        return SvgEmitter.groupmap(laid, FONT);
    }

    /** Parses the {@code rank} field out of each {@code ;}-joined run, in serialized order. */
    private static List<Integer> ranksOf(String gm) {
        List<Integer> ranks = new ArrayList<>();
        for (String run : gm.split(";")) {
            ranks.add(Integer.parseInt(run.substring(0, run.indexOf(':'))));
        }
        return ranks;
    }

    /** Asserts the grammar match AND that ranks are strictly ascending, at the output surface. */
    private static void assertConformsToConsumerGrammar(String gm, String context) {
        assertTrue(CONSUMER_GROUPMAP_GRAMMAR.matcher(gm).matches(),
            "groupmap output for " + context + " violates the pinned consumer grammar: " + gm);
        int prev = -1;
        for (int rank : ranksOf(gm)) {
            assertTrue(rank > prev, "ranks must ascend in serialized order for " + context + ": " + gm);
            prev = rank;
        }
    }

    // -- genuine 2+ rank fixtures: non-empty output must match the grammar exactly ------

    @Test
    void aTwoRankFenceCascadeMatchesTheConsumerGrammar() {
        // Reuses the GroupmapTest idiom/fixture (oneFenceLevelRanksTheInnerGroupBeforeTheOuter):
        // \left(a + b\right) + c -> rank 0 (inner + delimiters), rank 1 (outer + c).
        String gm = groupmapOf("\\left(a + b\\right) + c");
        assertTrue(!gm.isEmpty(), "expected a genuine cascade, got empty: " + gm);
        assertConformsToConsumerGrammar(gm, "\\left(a + b\\right) + c");
        assertTrue(gm.startsWith("0:"), gm);
    }

    @Test
    void aThreeRankFenceCascadeMatchesTheConsumerGrammar() {
        // A third nesting level beyond GroupmapTest's two-level fixture
        // (deeperNestingProducesMoreRanksDeepestFirst), so this pins a >2-rank serialization
        // specifically (multiple ';'-joined runs, not just one join).
        String gm = groupmapOf("\\left(a + \\left(b + \\left(c + d\\right)\\right)\\right) + e");
        assertTrue(!gm.isEmpty(), "expected a genuine cascade, got empty: " + gm);
        assertConformsToConsumerGrammar(gm, "triple-nested fence + trailing term");
        assertEquals(4, gm.split(";").length, "three fence levels + the outer term is 4 ranks: " + gm);
    }

    @Test
    void siblingFencesShareARankAndStillMatchTheGrammar() {
        // Reuses the GroupmapTest fixture (twoSiblingFencesShareRankZero): two independent
        // fenced groups merge into the SAME rank-0 run, exercising a comma-joined run with
        // more than the minimum indices.
        String gm = groupmapOf("\\left(a+b\\right) + \\left(c+d\\right)");
        assertTrue(!gm.isEmpty(), gm);
        assertConformsToConsumerGrammar(gm, "sibling fences");
    }

    // -- fail-honest degrade cases: never a non-matching non-empty value ----------------

    @Test
    void emptyCascadeLayoutsReturnExactlyEmptyStringNeverAMalformedValue() {
        // Reuses the exact fail-honest fixtures GroupmapTest already established:
        // parenFreeExpressionDegradesToEmpty, aSingleGlyphDegradesToEmpty,
        // aFenceWithNothingOutsideStillNeedsTwoRanks, scriptsAndFractionsDoNotDeepenThePrecedenceDepth.
        for (String latex : new String[] {
                "a + b",
                "x",
                "\\left(a+b\\right)",
                "a^2 + \\frac{b}{c}",
        }) {
            String gm = groupmapOf(latex);
            assertEquals("", gm, "a no-cascade layout must return exactly \"\", not a near-miss value: " + latex);
        }
    }

    // -- corpus sweep: every REAL corpus layout's non-empty output must match -----------

    /**
     * Drives the vendored parse corpus (same resource + tier semantics as
     * {@link CorpusRenderSweepTest} and {@link OutputCapPostconditionTest#loadCorpus}) through
     * {@link SvgEmitter#groupmap} directly. Most rows legitimately degrade to {@code ""} (no
     * fence-nesting variation) — this is not a bug, see the fail-honest cases above — but ANY
     * non-empty output, from ANY real formula in the corpus, must conform to the pinned
     * consumer grammar with strictly-ascending ranks. This is the breadth check; the fixtures
     * above are the guaranteed-non-empty depth check.
     */
    @Test
    void everyNonEmptyCorpusGroupmapMatchesTheConsumerGrammar() throws IOException {
        List<String[]> corpus = loadCorpus();
        List<String> failures = new ArrayList<>();
        int swept = 0;
        int nonEmpty = 0;
        for (String[] row : corpus) {
            String tier = row[0];
            String latex = row[1];
            boolean mustLayOut = tier.equals("PARSES-NOW");
            boolean mayLayOut = tier.equals("NEEDS-S4-LAYOUT");
            if (!mustLayOut && !mayLayOut) {
                continue; // NEEDS-PARSER-NODE / NEEDS-FONT-STYLE rows don't parse at all
            }
            swept++;
            String gm;
            try {
                gm = groupmapOf(latex);
            } catch (RuntimeException e) {
                if (mustLayOut) {
                    failures.add("PARSES-NOW row threw for groupmap(): [" + latex + "]: " + e);
                }
                continue; // NEEDS-S4-LAYOUT: a caught RuntimeException is the accepted degrade
            }
            if (gm.isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (!CONSUMER_GROUPMAP_GRAMMAR.matcher(gm).matches()) {
                failures.add("[" + latex + "] violates the consumer grammar: " + gm);
                continue;
            }
            int prev = -1;
            for (int rank : ranksOf(gm)) {
                if (rank <= prev) {
                    failures.add("[" + latex + "] ranks do not ascend: " + gm);
                    break;
                }
                prev = rank;
            }
        }

        if (!failures.isEmpty()) {
            fail("groupmap grammar contract violated (" + failures.size() + "):\n  "
                + String.join("\n  ", failures));
        }
        // Non-vacuity: the sweep must really cover the corpus breadth, never an accidentally
        // empty filter (same floor CorpusRenderSweepTest/OutputCapPostconditionTest use).
        assertTrue(swept >= 130, "expected the full renderable corpus, swept only " + swept);
    }

    private static List<String[]> loadCorpus() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = GroupmapGrammarContractTest.class.getClassLoader()
                .getResourceAsStream("com/lattex/parse/corpus.tsv")) {
            assertNotNull(in, "vendored corpus resource missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length >= 3) {
                    rows.add(new String[] {cols[0].trim(), cols[2]});
                }
            }
        }
        return rows;
    }
}
