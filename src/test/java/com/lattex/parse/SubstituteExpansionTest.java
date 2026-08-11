package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
