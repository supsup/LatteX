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
    void aNegativeSubstitutionUnderASUBSCRIPTStillNeedsTheProductMarker() {
        // Lattice, lattex/863 (BLOCKING). `2x_2` with x=-3 rendered `2-3_2` — a SUBTRACTION
        // where a product was meant.
        //
        // The bug lived in my own comment as much as my code. I wrote that "a subscript
        // carries no precedence hazard", and scoped the remedy to `sup != null` on the
        // strength of it. That sentence is TRUE and IRRELEVANT: there is no precedence
        // hazard INSIDE the SupSub — `-3_2` really is negative-three-sub-two — but the
        // hazard is the juxtaposition OUTSIDE it, where the coefficient `2` now abuts a
        // leading minus. Reasoning about the subtree, I never looked at the seam.
        //
        // Note what the control above could not catch: `2x` and `2x_2` differ only in
        // whether the substituted atom is bare or wears a subscript, and only the bare form
        // was tested. A control that stops one structural step short of the defect passes
        // for the same reason the defect survives.
        // The remedy is a FENCE rather than an inserted product, and the reason is the
        // second hazard: `-3_2` and `(-3)_2` are not the same value. A subscript on a
        // numeral commonly denotes a RADIX, so `-3_2` binds the subscript to the 3 and
        // negates the result, while the substitution means the whole -3 carries it. They
        // render identically. A `\cdot` would fix the subtraction reading and leave that
        // one standing — I tried exactly that first, and the tree it produced disagreed
        // with the parse of its own rendering, which is what surfaced the radix reading.
        assertEquals(parse("2\\left(-3\\right)_2"), expand("2x_2", -3).get().substituted(),
            "a newly negative SUBSCRIPTED base is fenced: product preserved, radix unambiguous");
        assertNotEquals(parse("2-3_2"), expand("2x_2", -3).get().substituted(),
            "revert-provable: ungrouped, `2-3_2` reads as a subtraction");
    }

    @Test
    void aPositiveSubstitutionUnderASubscriptKeepsItsDigitSeamGuard() {
        // The POSITIVE CONTROL for the test above, and it is the one that proves the new
        // arm is about the MINUS rather than about subscripts in general: the digit-leading
        // seam was already handled here and must stay handled.
        assertEquals(parse("2 \\cdot 3_2"), expand("2x_2", 3).get().substituted(),
            "a digit-leading subscripted base was already a product and stays one");
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

    @Test
    void aNegativeSubstitutionAfterANONDIGITOperandStillNeedsTheProduct() {
        // Lattice, lattex/883 finding 1 (BLOCKING). The seam guard fired on endsWithDigit()
        // alone, so a negative value was guarded after `2` and unguarded after everything
        // else: `ax` with x=-3 produced atoms structurally identical to the parse of `a-3`.
        // The source adjacency means multiplication; the payload showed subtraction.
        //
        // The existing `2x` control could not expose this precisely BECAUSE its neighbour is
        // a digit — it takes the endsWithDigit branch and inserts the product for the wrong
        // reason. A control whose subject reaches the right answer down the wrong path is
        // worse than no control: it reports the guard working when the guard is not involved.
        assertEquals(parse("a \\cdot -3"), sub("ax", -3, 'x'),
            "a negative after a VARIABLE is still a product, not a subtraction");
        assertEquals(parse("(a) \\cdot -3"), sub("(a)x", -3, 'x'),
            "...and after a fenced operand");
        assertNotEquals(parse("a-3"), sub("ax", -3, 'x'),
            "revert-provable: unguarded this equals the parse of `a-3`");
    }

    @Test
    void theProductIsNOTInsertedAfterAnOperatorOrAtTheStart() {
        // THE OVER-APPLICATION CONTROL, and the reason the guard tests the math CLASS of the
        // left neighbour rather than merely "is something there". After a BIN or REL the minus
        // is a SIGN, not a seam, and `1 + \cdot -3` is not mathematics. Widening a remedy past
        // its hazard is how the previous fix in this file became the next bug.
        assertEquals(parse("1+-3"), sub("1+x", -3, 'x'), "after `+` the minus is a sign");
        assertEquals(parse("-3"), sub("x", -3, 'x'), "at the start there is no seam at all");
    }

    @Test
    void colourIsPaintAndDoesNotHideASeam() {
        // Lattice, lattex/883 finding 2 (BLOCKING). Colored sat with Fenced/Fraction/Radical
        // in the "draws its own boundary" arm. It draws no boundary — \textcolor{red}{2} puts
        // a 2 on the baseline exactly where any 2 would be — so the 851 digit-string defect
        // reappeared behind a colour, and a coloured negative base earned neither seam nor
        // fence.
        assertEquals(parse("\\textcolor{red}{2} \\cdot 3"), sub("\\textcolor{red}{2}x", 3, 'x'),
            "a coloured digit is still a digit at the seam");
        assertNotEquals(parse("\\textcolor{red}{2}3"), sub("\\textcolor{red}{2}x", 3, 'x'),
            "revert-provable: untreated this is the digit string 23, wearing a colour");
        assertEquals(parse("2 \\cdot \\textcolor{red}{3}^2"), sub("2\\textcolor{red}{x}^2", 3, 'x'),
            "a coloured substituted base exports its digit seam");
    }

    @Test
    void aColouredNegativeBaseUnderAPowerIsFencedLikeAnUncolouredOne() {
        // The wrapper half of the 861/863 fence. Same expression, same value, one \textcolor
        // apart — and before this fix the coloured form silently produced `2-3^2` while the
        // bare form produced `2(-3)^2`. THE PAIR IS THE POINT: neither assertion alone shows
        // that colour was the variable, which is what Lattice asked the control to prove.
        assertEquals(sub("2x^2", -3, 'x'), stripColour(sub("2\\textcolor{red}{x}^2", -3, 'x')),
            "with the colour removed, the coloured result is the uncoloured result");
        assertNotEquals(parse("2-3^2"), sub("2\\textcolor{red}{x}^2", -3, 'x'),
            "revert-provable: untreated the coloured form is a subtraction");
    }

    @Test
    void aChangeAWAYFromTheEdgeIsNotAnEdgeBoundary() {
        // Lattice, lattex/887 finding 1 (BLOCKING). Provenance was derived from `substituted`
        // — "did ANY descendant change" — which is a different question from "did the EDGE
        // glyph change". Substituting x=4 in 2\textcolor{red}{3+x} leaves the leading glyph as
        // the SOURCE digit 3, yet the coarse test reported a substituted leading digit and
        // inserted a product before the wrapper.
        //
        // THIS IS THE 861 DEFECT, REPRODUCED BY ME. The SupSub arm was corrected for exactly
        // this ("a boundary invented where none exists changes meaning as surely as a boundary
        // missed") and I copied the neighbouring MathList arm's older shape into the new
        // Colored one. The fix is now uniform across all three wrappers rather than per-arm.
        assertEquals(parse("2\\textcolor{red}{3+4}"), sub("2\\textcolor{red}{3+x}", 4, 'x'),
            "a change away from the leading edge invents no boundary");
        assertEquals(parse("2\\textcolor{red}{3+4}"), sub("2\\textcolor{red}{3+x}", 4, 'x'),
            "...and the leading 3 stays a source digit, so no product is inserted");
        assertNotEquals(parse("2 \\cdot \\textcolor{red}{3+4}"), sub("2\\textcolor{red}{3+x}", 4, 'x'),
            "revert-provable: subtree-wide provenance inserts a spurious product here");
    }

    @Test
    void aTransparentWrapperEndingInANOPERATORIsNotAMultiplicand() {
        // Lattice, lattex/887 finding 2 (BLOCKING). endsWithMultiplicand tested for a bare
        // Atom, so every Colored/MathList tail counted as an operand — and those wrappers are
        // transparent, so they can end in an operator. \textcolor{red}{+} followed by a
        // substituted -3 produced `+ \cdot -3`.
        //
        // MY OWN ASYMMETRY: I had just made Colored see-through for the LEADING edge (does it
        // start with a digit or a minus) and left it opaque for the TRAILING math class.
        // Transparency is a property of the wrapper, not of the direction you look through it.
        assertEquals(parse("\\textcolor{red}{+}-3"), sub("\\textcolor{red}{+}x", -3, 'x'),
            "a coloured operator is still an operator: the minus is a sign, not a seam");
        assertNotEquals(parse("\\textcolor{red}{+} \\cdot -3"), sub("\\textcolor{red}{+}x", -3, 'x'),
            "revert-provable: an opaque tail test yields the nonsensical `+ \\cdot -3`");
    }

    // ---- helpers -----------------------------------------------------------

    private static MathNode parse(String latex) {
        return MathParser.parse(latex);
    }

    private static Optional<SubstituteExpansion.Result> expand(String latex, int target) {
        return SubstituteExpansion.expand(parse(latex), target, null);
    }

    private static MathNode sub(String latex, int target, char var) {
        return SubstituteExpansion.expand(parse(latex), target, (int) var).get().substituted();
    }

    /** Drop every Colored wrapper so a coloured tree can be compared to its bare twin. */
    private static MathNode stripColour(MathNode n) {
        return switch (n) {
            case MathNode.Colored c -> stripColour(c.body());
            case MathNode.MathList ml -> new MathNode.MathList(
                ml.items().stream().map(SubstituteExpansionTest::stripColour).toList());
            case MathNode.SupSub s -> new MathNode.SupSub(stripColour(s.base()),
                s.sup() == null ? null : stripColour(s.sup()),
                s.sub() == null ? null : stripColour(s.sub()));
            case MathNode.Fenced f -> new MathNode.Fenced(
                f.leftDelim(), stripColour(f.body()), f.rightDelim());
            default -> n;
        };
    }

    private static void assertInert(String latex, int target) {
        assertTrue(expand(latex, target).isEmpty(),
            latex + " should degrade INERT (Optional.empty)");
    }
}
