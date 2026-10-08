package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import org.junit.jupiter.api.Test;

/**
 * Plan 720cd87e, item 1: a braced subformula {@code {...}} in a math list is an ORD atom
 * for spacing (TeXbook ch. 17, "a math formula enclosed in braces ... is treated as an
 * Ord atom"; Appendix G rule 17 builds it as a box). So {@code 152{,}320} is the tight
 * thousands separator papers write, and {@code a{+}b} sets the plus with no binary glue.
 *
 * <p>Before the fix a ONE-item group collapsed to its item, which kept its own class:
 * {@code 152{,}320} was exactly as wide as {@code 152,320}. Every assertion is a width
 * against the same row spelled with {@code \mathord}, the one form whose Ord class is not
 * in question, plus the bare spelling as the control that makes it discriminate.
 */
class BracedGroupOrdTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final LayoutContext CTX =
        new LayoutContext(FONT, FONT.mathConstants(), 40.0);
    private static final double EPS = 1e-9;

    private static double width(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex), CTX).width();
    }

    @Test
    void bracedCommaIsATightThousandsSeparator() {
        double braced = width("152{,}320");
        assertEquals(width("152\\mathord{,}320"), braced, EPS,
            "152{,}320 must lay out exactly as 152\\mathord{,}320");
        assertTrue(braced < width("152,320") - 1.0,
            "152{,}320 must be narrower than 152,320 (no thin space after the punctuation)");
    }

    @Test
    void bracedBinaryRelationAndPunctuationLoseTheirClassGlue() {
        for (String atom : new String[] {"+", "=", ",", ";", "\\times", "\\le", "\\to", "-"}) {
            String braced = "a{" + atom + "}b";
            assertEquals(width("a\\mathord{" + atom + "}b"), width(braced), EPS,
                braced + " must space as a\\mathord{" + atom + "}b");
            assertTrue(width(braced) < width("a" + atom + " b") - 1.0,
                braced + " must be narrower than the bare a" + atom + " b");
        }
    }

    @Test
    void bracedPeriodIsOrdLikeTheBarePeriod() {
        // '.' is already Ord (TeX mathcode "013A), so the brace changes nothing: a pin,
        // with the \mathord spelling as the reference.
        assertEquals(width("3\\mathord{.}14"), width("3{.}14"), EPS);
        assertEquals(width("3.14"), width("3{.}14"), EPS);
    }

    @Test
    void aBracedMultiAtomGroupIsOneOrdAtom() {
        // The group's interior keeps its own spacing; outside it the group is Ord.
        assertEquals(width("x\\mathord{a+b}y"), width("x{a+b}y"), EPS);
        assertEquals(width("x\\mathord{a+b}=y"), width("x{a+b}=y"), EPS);
        // A braced named operator is Ord: no thin Op-Ord space before the argument.
        assertEquals(width("\\mathord{\\sin}x"), width("{\\sin}x"), EPS);
        assertTrue(width("{\\sin}x") < width("\\sin x") - 1.0);
        // A braced \left..\right is Ord, not Inner.
        assertEquals(width("a\\mathord{\\left(b\\right)}c"), width("a{\\left(b\\right)}c"), EPS);
    }

    @Test
    void theClassCarryingFormsAreUnchanged() {
        // {}= : the empty group is Ord and the relation keeps its thick glue.
        assertEquals(width("\\mathord{}=b"), width("{}=b"), EPS);
        assertTrue(width("{}=b") > width("{}\\mathord{=}b") + 1.0);
        // \mathop / \mathbin / \mathrel with a braced argument keep their forced class:
        // a command argument's braces delimit the argument, they do not make a subformula.
        assertTrue(width("x\\mathrel{y}z") > width("xyz") + 1.0);
        assertTrue(width("x\\mathbin{+}z") > width("x\\mathord{+}z") + 1.0);
        assertEquals(width("x\\mathop{y}z"), width("x\\mathop y z"), EPS);
        // \overset's base keeps its class (amsmath reads it as the base's class).
        assertTrue(width("a\\overset{!}{=}b") > width("a\\mathord{\\overset{!}{=}}b") + 1.0);
        // An unbraced \left..\right is still Inner.
        assertTrue(width("a\\left(b\\right)c") > width("a\\mathord{\\left(b\\right)}c") + 1.0);
        // A script argument is its own list: x^{+} and x^+ draw the same.
        assertEquals(width("x^+"), width("x^{+}"), EPS);
        assertEquals(width("\\frac{+}{2}"), width("\\frac+2"), EPS);
    }

    @Test
    void whatTheWrapperLeavesAlone() {
        // An Ord atom in braces stays a bare atom: its scripts attach exactly as without
        // the braces (italic correction, math kerns), so {x}^2 draws as x^2.
        assertEquals(LayoutEngine.layout(MathParser.parse("x^2"), CTX).glyphs(),
            LayoutEngine.layout(MathParser.parse("{x}^2"), CTX).glyphs());
        // A braced large operator keeps its display size: the same glyph as a bare \sum.
        var bare = LayoutEngine.layout(MathParser.parse("\\sum"), CTX).glyphs();
        var braced = LayoutEngine.layout(MathParser.parse("{\\sum}"), CTX).glyphs();
        assertEquals(bare.get(0).glyphId(), braced.get(0).glyphId());
        // ... and its scripts go beside the Ord group, not above and below it.
        var g = LayoutEngine.layout(MathParser.parse("{\\sum}_{i}"), CTX).glyphs();
        int sumGlyph = bare.get(0).glyphId();
        var sum = g.stream().filter(p -> p.glyphId() == sumGlyph).findFirst().orElseThrow();
        var i = g.stream().filter(p -> p.sourceCodePoint() == 'i').findFirst().orElseThrow();
        assertTrue(i.originX() > sum.originX() + 1.0, "the subscript sits beside the sum");
    }
}
