package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.Color;
import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Actual construction/copy work, including geometry with no textual AST payload. */
class GeometryWorkBudgetTest {
    private static final SfntFont FONT = SfntFont.loadBundled();

    private static LayoutContext context() {
        return new LayoutContext(FONT, FONT.mathConstants(), 40.0);
    }

    private static Layout layout(String source) {
        return LayoutEngine.layout(MathParser.parse(source), context());
    }

    private static MathNode.Matrix grid(MathNode cell, int vertical, MathNode.RowRule horizontal) {
        return new MathNode.Matrix(List.of(List.of(cell)), List.of(MathNode.ColumnAlign.CENTER),
            List.of(vertical, 0), List.of(horizontal, MathNode.RowRule.NONE),
            MathNode.Fenced.NULL_DELIMITER, MathNode.Fenced.NULL_DELIMITER, MathNode.MatrixKind.ARRAY);
    }

    private static void assertWorkCap(Runnable action) {
        MathSyntaxException failure = assertThrows(MathSyntaxException.class, action::run);
        assertTrue(failure.isCapExceeded(), failure.getMessage());
        assertTrue(failure.getMessage().contains("output work budget"), failure.getMessage());
    }

    @Test
    void ordinaryRulesAndRepeatedSeparatorsKeepTheirInk() {
        // Separator material and a vertical rule cannot share one column boundary.
        // Put the repeated separator between two real columns, with outer bars apart.
        String source = "\\begin{array}{|c@{\\begin{array}{||c|}x\\end{array}}c|}"
            + "a&b\\\\c&d\\end{array}";
        Layout result = layout(source);
        assertEquals(6, result.glyphs().size(), "four cells plus the separator on BOTH rows");
        assertEquals(8, result.rules().size(), "two outer bars plus three separator bars per row");
        assertTrue(layout("\\begin{array}{c}\\hdashline x\\end{array}").rules().size() > 0);
        String colored = "\\textcolor{red}{" + source + "}";
        assertEquals(LatteX.render(colored), LatteX.render(colored),
            "ordinary repeated renders remain byte-identical");
    }

    @Test
    void excessiveVerticalCountRefusesBeforeItsProducerLoop() {
        assertWorkCap(() -> LayoutEngine.layout(
            grid(MathNode.Atom.ord('x'), Integer.MAX_VALUE, MathNode.RowRule.NONE), context()));
        assertEquals(1, layout("x").glyphs().size());
    }

    @Test
    void nonfiniteDashedGeometryIsTypedWithoutLooping() {
        for (double width : List.of(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertWorkCap(() -> LayoutEngine.layout(
                grid(new MathNode.Spacing(width), 0, MathNode.RowRule.DASHED), context()));
        }
        assertEquals(1, layout("x").glyphs().size());
    }

    @Test
    void repeatedBoxStampsChargeCumulativelyAcrossDestinations() {
        // This bounded ~100k-copy discriminator is safe even with accounting removed;
        // the 10M-copy public witness belongs only in OutputWorkBudgetTest's 48m fork.
        Rule polygon = Rule.polygon(new double[] {0, 0, 1, 0, 0, 1}, null);
        Box shared = new Box(List.of(), Collections.nCopies(1_000, polygon), 1, 1, 0);
        RenderWorkBudget.withLayoutBudget(() -> {
            for (int i = 0; i < 100; i++) {
                List<Rule> destination = new ArrayList<>();
                shared.drawInto(new ArrayList<>(), destination, i, 0);
                assertEquals(1_000, destination.size());
            }
            List<Rule> refusedDestination = new ArrayList<>();
            assertWorkCap(() -> shared.drawInto(new ArrayList<>(), refusedDestination, 0, 0));
            assertTrue(refusedDestination.isEmpty(), "precheck precedes polygon cloning/translation");
            assertWorkCap(() -> polygon.paintedWith(Color.BLACK));
            return null;
        });
    }

    @Test
    void ruleCapacityPrecheckRefusesBeforeAnyGlyphOrRuleAppend() {
        // Construct the shared originals unbound, leaving exactly one rule slot below.
        PositionedGlyph glyph = new PositionedGlyph(1, 0, 0, 1);
        Rule polygon = Rule.polygon(new double[] {0, 0, 1, 0, 0, 1}, null);
        Box shared = new Box(List.of(glyph), List.of(polygon, polygon), 1, 1, 0);
        RenderWorkBudget.withLayoutBudget(() -> {
            for (int i = 0; i < 99_999; i++) {
                new Rule(0, 0, 1, 1);
            }
            List<PositionedGlyph> glyphs = new ArrayList<>();
            List<Rule> rules = new ArrayList<>();
            assertWorkCap(() -> shared.drawInto(glyphs, rules, 0, 0));
            // Without the rule precheck, the glyph and first rule would be appended
            // before the second rule's canonical constructor refuses the stamp.
            assertTrue(glyphs.isEmpty(), "rule precheck must precede every glyph append");
            assertTrue(rules.isEmpty(), "rule precheck must precede every rule append");
            return null;
        });
    }

    @Test
    void glyphCapacityPrecheckRefusesBeforeAnyGlyphOrRuleAppend() {
        // Two requested copies must refuse with one slot left, not after a partial stamp.
        PositionedGlyph glyph = new PositionedGlyph(1, 0, 0, 1);
        Rule rule = new Rule(0, 0, 1, 1);
        Box shared = new Box(List.of(glyph, glyph), List.of(rule), 1, 1, 0);
        RenderWorkBudget.withLayoutBudget(() -> {
            for (int i = 0; i < 399_999; i++) {
                new PositionedGlyph(1, 0, 0, 1);
            }
            List<PositionedGlyph> glyphs = new ArrayList<>();
            List<Rule> rules = new ArrayList<>();
            assertWorkCap(() -> shared.drawInto(glyphs, rules, 0, 0));
            // Removing only the glyph precheck permits one append before refusal.
            assertTrue(glyphs.isEmpty(), "glyph precheck must precede every glyph append");
            assertTrue(rules.isEmpty(), "glyph precheck must precede every rule append");
            return null;
        });
    }

    @Test
    void glyphCopiesAndColorRebuildsUseTheSameCumulativeAllowance() {
        PositionedGlyph original = new PositionedGlyph(1, 0, 0, 1);
        Box shared = new Box(Collections.nCopies(1_000, original), List.of(), 1, 1, 0);
        RenderWorkBudget.withLayoutBudget(() -> {
            for (int i = 0; i < 400; i++) {
                List<PositionedGlyph> destination = new ArrayList<>();
                shared.drawInto(destination, new ArrayList<>(), i, 0);
                assertEquals(1_000, destination.size());
            }
            assertWorkCap(() -> original.paintedWith(Color.BLACK));
            assertWorkCap(() -> shared.drawInto(new ArrayList<>(), new ArrayList<>(), 0, 0));
            return null;
        });
    }

    @Test
    void nestedAndExceptionalBindingsRestoreOuterAndUnboundLifetimes() {
        RenderWorkBudget.withLayoutBudget(() -> {
            for (int i = 0; i < 100_000; i++) {
                new Rule(0, 0, 1, 1);
            }
            assertWorkCap(() -> new Rule(0, 0, 1, 1));
            assertThrows(IllegalStateException.class, () -> RenderWorkBudget.withLayoutBudget(() -> {
                new Rule(0, 0, 1, 1); // Fresh inner budget, even though the outer is exhausted.
                assertEquals(1, layout("x").glyphs().size()); // A real nested layout binding.
                throw new IllegalStateException("inner failure");
            }));
            assertWorkCap(() -> new Rule(0, 0, 1, 1)); // Outer exhaustion was restored.
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> RenderWorkBudget.withLayoutBudget(() -> {
            throw new IllegalArgumentException("outer failure");
        }));
        // Ordinary direct record construction outside a render remains unrestricted.
        new Rule(0, 0, 1, 1);
        new PositionedGlyph(1, 0, 0, 1);
        assertEquals(1, layout("x").glyphs().size());
    }

    @Test
    void concurrentRendersHaveIndependentCumulativeAllowances() throws Exception {
        CountDownLatch together = new CountDownLatch(2);
        CountDownLatch firstCharged = new CountDownLatch(1);
        CountDownLatch secondCharged = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() ->
                independentWork(together, null, firstCharged, secondCharged));
            var second = executor.submit(() ->
                independentWork(together, firstCharged, secondCharged, null));
            assertEquals(70_000, first.get(10, TimeUnit.SECONDS).intValue());
            assertEquals(70_000, second.get(10, TimeUnit.SECONDS).intValue());
        }
    }

    private static int independentWork(CountDownLatch together, CountDownLatch predecessor,
                                       CountDownLatch charged, CountDownLatch successor) {
        return RenderWorkBudget.withLayoutBudget(() -> {
            // Both bindings stay active through both batches. Serialize the charges so a
            // shared-counter mutant cannot pass through scheduling or lost-update races.
            together.countDown();
            await(together);
            if (predecessor != null) {
                await(predecessor);
            }
            try {
                for (int i = 0; i < 70_000; i++) {
                    new Rule(0, 0, 1, 1);
                }
            } finally {
                charged.countDown();
            }
            if (successor != null) {
                await(successor);
            }
            assertEquals(1, layout("x").glyphs().size());
            return 70_000;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "budget binding rendezvous timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("budget binding rendezvous interrupted", interrupted);
        }
    }
}
