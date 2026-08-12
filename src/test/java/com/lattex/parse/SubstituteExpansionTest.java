package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubstituteExpansion} — the one-variable numeric-substitution
 * pass. Same load-bearing oracle {@link SumExpansionTest} uses: a substitution is
 * STRUCTURALLY IDENTICAL to the parse of the hand-written substituted form (records'
 * {@code equals}), so the pre-rendered payload is byte-identical to typing it out.
 * Plus the fail-INERT battery, which is where this pass differs most from unfold: it
 * has to decide WHICH variable, and refuse when the answer is not unique.
 */
class SubstituteExpansionTest {

    @Test
    void substitutesEveryOccurrence_structurallyIdenticalToHandWritten() {
        Optional<SubstituteExpansion.Result> r = expand("x^2 + 2x + 1", 3);
        assertTrue(r.isPresent(), "x^2 + 2x + 1 with a single variable should substitute");
        assertEquals('x', r.get().variableCodePoint());
        assertEquals(2, r.get().occurrences(), "two x's: the squared one and the 2x");
        // Note the \cdot: 2x is an implicit PRODUCT, so substituting 3 must not produce
        // the digit string 23. See juxtaposedDigitsBecomeAnExplicitProduct below.
        assertEquals(parse("3^2 + 2 \\cdot 3 + 1"), r.get().substituted(),
            "the substituted tree equals the parse of the hand-written form");
    }

    @Test
    void juxtaposedDigitsBecomeAnExplicitProduct() {
        // Implicit multiplication is written by ADJACENCY, but adjacency between DIGITS is
        // positional notation. Without the guard, 2x with x=3 renders "23" -- not a glitch
        // but a different, false number, under a directive that looks like it worked.
        assertEquals(parse("2 \\cdot 3"), expand("2x", 3).get().substituted());
        assertEquals(parse("2 \\cdot 10"), expand("2x", 10).get().substituted());
        // ... and ONLY at a substitution boundary: digits already adjacent in the source
        // are one literal and must stay one.
        assertEquals(parse("12 + 3"), expand("12 + x", 3).get().substituted());
    }

    @Test
    void multiDigitAndNegativeTargetsSpliceAsDigitAtoms() {
        assertEquals(parse("10 + 10"), expand("x + x", 10).get().substituted());
        assertEquals(parse("-12"), expand("x", -12).get().substituted());
    }

    @Test
    void substitutesInsideFractionRadicalSupSubAndFences() {
        assertEquals(parse("\\frac{2}{2}"), expand("\\frac{x}{x}", 2).get().substituted());
        assertEquals(parse("\\sqrt{5}"), expand("\\sqrt{x}", 5).get().substituted());
        assertEquals(parse("(7 + 7)"), expand("(x + x)", 7).get().substituted());
        // t_x holds TWO letters (t and x), so it needs the variable named explicitly.
        assertEquals(parse("t_4"),
            SubstituteExpansion.expand(parse("t_x"), 4, (int) 'x').get().substituted());
    }

    @Test
    void operatorNameLettersAreStructurallyImmune() {
        // The 'i' in \sin is an OperatorName, not an Atom, so it is neither DETECTED as
        // the variable nor REPLACED. \sin(x) therefore has exactly one variable: x.
        Optional<SubstituteExpansion.Result> r = expand("\\sin(x)", 0);
        assertTrue(r.isPresent(), "\\sin(x) has exactly one Atom letter: x");
        assertEquals('x', r.get().variableCodePoint());
        assertEquals(parse("\\sin(0)"), r.get().substituted(),
            "\\sin survives intact — a regex over the raw LaTeX would have corrupted it");
    }

    @Test
    void inertWhenThereIsNoVariable() {
        assertInert("1 + 2", 3);
        assertInert("\\frac{1}{2}", 3);
    }

    @Test
    void inertWhenTheVariableIsAmbiguousAndUnnamed() {
        // Two distinct letters and no fx.substitute-var: refuse rather than pick one.
        assertInert("x + y", 3);
        assertInert("a x^2 + b x + c", 3);
    }

    @Test
    void explicitVariableDisambiguates() {
        Optional<SubstituteExpansion.Result> r =
            SubstituteExpansion.expand(parse("x + y"), 3, (int) 'x');
        assertTrue(r.isPresent(), "an explicit variable resolves the ambiguity");
        assertEquals('x', r.get().variableCodePoint());
        assertEquals(1, r.get().occurrences());
        assertEquals(parse("3 + y"), r.get().substituted(), "only x moves; y is untouched");

        assertEquals(parse("x + 3"),
            SubstituteExpansion.expand(parse("x + y"), 3, (int) 'y').get().substituted());
    }

    @Test
    void inertWhenTheExplicitVariableIsNotInTheBody() {
        // The load-bearing refusal: substituting an absent variable would render a payload
        // IDENTICAL to the source, presenting an author typo as a working effect. A silent
        // no-op is the one outcome worse than an inert directive.
        assertTrue(SubstituteExpansion.expand(parse("x + 1"), 3, (int) 'z').isEmpty(),
            "fx.substitute-var=z on a body with no z must be INERT, not a no-op payload");
    }

    @Test
    void inertOnANodeKindThePassDoesNotTraverse() {
        // A \sum body holds a BigOperator, which AtomSubstitution refuses; the detection
        // walk refuses it identically, so a variable can never hide in an untraversed
        // subtree and come back un-substituted.
        assertInert("\\sum_{i=1}^{3} i", 3);
    }


    @Test
    void aSubstitutedSupSubBaseStillNeedsTheExplicitProduct() {
        // Lattice, lattex/851 (BLOCKING): the juxtaposition guard was ATOM-level, so a
        // compound node whose BASE became digits slipped past it. `2x^2` with x=3 produced a
        // tree byte-identical to the parse of `23^2` -- twenty-three squared, not two times
        // three squared. Silent, meaning-changing, and green under 956 tests because no case
        // put a coefficient in front of a POWERED variable.
        assertEquals(parse("2 \\cdot 3^2"), expand("2x^2", 3).get().substituted(),
            "a coefficient before a powered variable stays a PRODUCT");
        assertNotEquals(parse("23^2"), expand("2x^2", 3).get().substituted(),
            "revert-provable: without boundary facts this equals the parse of 23^2");
    }

    @Test
    void aSourceDigitBaseIsNotABoundaryJustBecauseTheExponentWasSubstituted() {
        // Lattice, lattex/861 finding 1 (BLOCKING): the first fix derived the boundary fact
        // from "did ANY descendant change", so substituting only the EXPONENT falsely marked
        // the SOURCE base digit as substituted. `23^x` with x=2 became `2 \cdot 3^2` -- two
        // times three squared, where the author wrote twenty-three raised to x. The 851 fix
        // and this defect are the same mistake pointing opposite ways: one missed a real
        // boundary, this one invented a boundary that was never there.
        assertEquals(parse("23^2"), expand("23^x", 2).get().substituted(),
            "a literal 23 stays twenty-three when only the exponent is substituted");
        assertNotEquals(parse("2 \\cdot 3^2"), expand("23^x", 2).get().substituted(),
            "revert-provable: deriving the fact from any-descendant-changed splits the 23");
    }

    @Test
    void aNegativeSubstitutionUnderAPowerIsGroupedNotLeftBare() {
        // Lattice, lattex/861 finding 2 (BLOCKING): a negative replacement does not LEAD with
        // a digit, so the seam guard stayed silent and `2x^2` with x=-3 rendered `2-3^2` --
        // read as two MINUS three squared. Two separate meaning changes in one payload: the
        // missing product, and `-3^2` being -(3^2) under ordinary precedence when the
        // substitution means (-3)^2. Grouping fixes both at once, which is why it is the
        // remedy rather than only inserting a dot.
        // The oracle is the \left...\right form, NOT parse("2(-3)^2"), and that difference is
        // the finding rather than a convenience. Plain parens parse to bare OPEN/CLOSE atoms
        // with the exponent hanging off the CLOSING PAREN atom; \left...\right parses to a
        // Fenced node with the exponent on the whole group. The two render alike, but only the
        // second says STRUCTURALLY that the square applies to (-3) — which is the exact
        // semantics this fix exists to preserve, so pinning the weaker shape would pin a
        // picture of the answer. The substituted tree is byte-identical to the \left form, so
        // this is still the suite's structural-identity oracle, aimed at the parse that means
        // what we mean.
        assertEquals(parse("2\\left(-3\\right)^2"), expand("2x^2", -3).get().substituted(),
            "a negative base under a power is fenced, so the square applies to (-3)");
        assertNotEquals(parse("2-3^2"), expand("2x^2", -3).get().substituted(),
            "revert-provable: ungrouped, this reads as a subtraction");
    }

    @Test
    void aNegativeSubstitutionWithNoPowerNeedsNoGrouping() {
        // The CONTROL for the test above, and the reason the grouping is scoped to a SupSub
        // base rather than applied to every negative: with no exponent there is no precedence
        // to protect, so parenthesising would be noise. Narrow the remedy to the case that
        // needs it or the fix becomes its own defect.
        assertEquals(parse("2 \\cdot -3"), expand("2x", -3).get().substituted(),
            "no power, no precedence hazard: the product guard alone is enough");
    }

    @Test
    void boundaryFactsTravelOnlyWhereGroupingIsInvisible() {
        // The fix carries boundary facts out of the transform rather than wrapping every
        // nested node in \cdot. Fences, fractions and radicals already DRAW a boundary, so a
        // digit inside them cannot be misread as positional notation with an outside sibling
        // -- inserting a dot there would be noise. A SupSub base has no such boundary: 3^2
        // sits on the baseline exactly where a digit would.
        assertEquals(parse("2(3)"), expand("2(x)", 3).get().substituted(),
            "parenthesised: visible grouping, no dot added");
        assertEquals(parse("2\\frac{3}{4}"), expand("2\\frac{x}{4}", 3).get().substituted(),
            "fraction: visible grouping, no dot added");
        assertEquals(parse("2\\sqrt{3}"), expand("2\\sqrt{x}", 3).get().substituted(),
            "radical: visible grouping, no dot added");
    }

    @Test
    void aSupSubWithNoScriptsStillTrailsIntoAFollowingDigit() {
        // The trailing edge only concatenates when nothing is raised after it. With a sup
        // present the last glyph is lifted off the baseline and cannot run into a digit.
        assertEquals(parse("3 \\cdot 2"), expand("x2", 3).get().substituted(),
            "substituted digit followed by a source digit still needs the product");
    }

    // ---- helpers -----------------------------------------------------------

    private static MathNode parse(String latex) {
        return MathParser.parse(latex);
    }

    private static Optional<SubstituteExpansion.Result> expand(String latex, int target) {
        return SubstituteExpansion.expand(parse(latex), target, null);
    }

    private static void assertInert(String latex, int target) {
        assertTrue(expand(latex, target).isEmpty(),
            latex + " should degrade INERT (Optional.empty)");
    }
}
