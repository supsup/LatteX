package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SumExpansion} — the bounded {@code \sum} numeric-expansion
 * pass. The load-bearing oracle: an expansion is STRUCTURALLY IDENTICAL to the parse
 * of the hand-written explicit terms (records' {@code equals}), so the pre-rendered
 * payload is byte-identical to typing the terms out. Plus the fail-INERT battery.
 */
class SumExpansionTest {

    @Test
    void expandsSumIntoExplicitTerms_structurallyIdenticalToHandWritten() {
        Optional<SumExpansion.Result> r = SumExpansion.expand(parse("\\sum_{i=1}^{4} f(i)"));
        assertTrue(r.isPresent(), "\\sum_{i=1}^{4} f(i) should expand");
        assertEquals(4, r.get().termCount());
        // The decisive property: the expanded tree equals the parse of the explicit terms.
        assertEquals(parse("f(1)+f(2)+f(3)+f(4)"), r.get().expanded());
    }

    @Test
    void bareIndexSummand_andNonUnitStart() {
        assertEquals(parse("1+2+3+4"), SumExpansion.expand(parse("\\sum_{i=1}^{4} i")).get().expanded());
        assertEquals(parse("2+3+4+5"), SumExpansion.expand(parse("\\sum_{k=2}^{5} k")).get().expanded());
    }

    @Test
    void multiDigitBoundSplicesDigits() {
        Optional<SumExpansion.Result> r = SumExpansion.expand(parse("\\sum_{i=1}^{10} i"));
        assertTrue(r.isPresent());
        assertEquals(10, r.get().termCount());
        assertEquals(parse("1+2+3+4+5+6+7+8+9+10"), r.get().expanded());
    }

    @Test
    void subscriptSummandSubstitutesInsideTheSub() {
        // t_i → t_1 + t_2 + t_3 (the index inside a subscript is substituted on the tree).
        assertEquals(parse("t_1 + t_2 + t_3"),
            SumExpansion.expand(parse("\\sum_{i=1}^{3} t_i")).get().expanded());
    }

    @Test
    void inertOnNonSumOperator() {
        assertInert("\\int_0^4 x");
        assertInert("\\prod_{i=1}^{4} i");
    }

    @Test
    void inertOnSymbolicOrInfiniteBounds() {
        assertInert("\\sum_{i=1}^{n} f(i)");     // symbolic upper
        assertInert("\\sum_{i=1}^{\\infty} f(i)"); // infinite upper
        assertInert("\\sum_{i=a}^{4} f(i)");      // symbolic start
    }

    @Test
    void inertOnBadIndexOrRange() {
        assertInert("\\sum f(i)");                 // no bounds at all
        assertInert("\\sum_{i=1}^{4}");            // no summand
        assertInert("\\sum_{i=4}^{1} f(i)");       // end < start
        assertInert("\\sum_{i,j=1}^{4} f(i)");     // multi-index
        assertInert("\\sum_i^4 f(i)");             // lower not v=int
    }

    @Test
    void inertOverTermCap() {
        // 12 terms is the cap (inclusive); 13 degrades inert.
        assertTrue(SumExpansion.expand(parse("\\sum_{i=1}^{12} i")).isPresent());
        assertInert("\\sum_{i=1}^{13} i");
        assertInert("\\sum_{i=1}^{99999} i");      // hostile — resource guard
    }

    @Test
    void coefficientSummandKeepsTheProductExplicit() {
        // REGRESSION (found 2026-08-11 while building fx.substitute, which shares this
        // pass). A coefficient makes the summand an implicit PRODUCT written by adjacency:
        // `2i` with i=1 spliced naively is `21`. The whole expansion then read
        // "21 + 22 + 23" — byte-identical to the parse of that literal digit string, and a
        // plausible-looking sum that is simply false. It shipped green because no case in
        // this file had a coefficient. The guard inserts an explicit \cdot at the boundary.
        Optional<SumExpansion.Result> r = SumExpansion.expand(parse("\\sum_{i=1}^{3} 2i"));
        assertTrue(r.isPresent(), "a coefficient summand still expands");
        assertEquals(parse("2\\cdot 1 + 2\\cdot 2 + 2\\cdot 3"), r.get().expanded(),
            "the terms are PRODUCTS, not the digit strings 21/22/23");
        // Revert-provable: without the guard this expansion equals the parse of "21+22+23".
        assertNotEquals(parse("21+22+23"), r.get().expanded());
    }

    @Test
    void multiDigitIndexAgainstACoefficientAlsoStaysAProduct() {
        // The boundary the guard has to get right when the spliced run is two digits wide.
        assertEquals(parse("2\\cdot 9 + 2\\cdot 10"),
            SumExpansion.expand(parse("\\sum_{i=9}^{10} 2i")).get().expanded());
    }

    @Test
    void adjacentSourceDigitsAreOneLiteralAndAreNeverSplit() {
        // The guard must fire ONLY at a substitution boundary. `12i` is twelve times i,
        // not one-times-two-times-i, so the 1 and 2 stay a single literal.
        assertEquals(parse("12\\cdot 1 + 12\\cdot 2"),
            SumExpansion.expand(parse("\\sum_{i=1}^{2} 12i")).get().expanded());
    }

    @Test
    void inertOnTopLevelTrailingBinaryOperator() {
        // Q5: `\sum f(i) + C` must NOT fold +C into every term — degrade inert.
        assertInert("\\sum_{i=1}^{4} f(i) + C");
        assertInert("\\sum_{i=1}^{4} i = S");
        assertInert("\\sum_{i=1}^{4} i - 1");
    }


    @Test
    void aPoweredIndexWithACoefficientStaysAProduct() {
        // Lattice, lattex/851: the SHARED pass carried the same hole, so unfold had it too.
        // `\sum_{i=1}^{2} 2i^2` expanded to a tree byte-identical to the parse of
        // "21^2 + 22^2". One fix in AtomSubstitution repairs both effects, which is the
        // second time the extraction has paid for itself.
        assertEquals(parse("2\\cdot 1^2 + 2\\cdot 2^2"),
            SumExpansion.expand(parse("\\sum_{i=1}^{2} 2i^2")).get().expanded(),
            "coefficient times a POWERED index is a product, not concatenation");
        assertNotEquals(parse("21^2 + 22^2"),
            SumExpansion.expand(parse("\\sum_{i=1}^{2} 2i^2")).get().expanded());
    }

    // ---- helpers -----------------------------------------------------------

    private static MathNode parse(String latex) {
        return MathParser.parse(latex);
    }

    private static void assertInert(String latex) {
        assertTrue(SumExpansion.expand(parse(latex)).isEmpty(),
            latex + " should degrade INERT (Optional.empty)");
    }
}
