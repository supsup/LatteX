package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/// Nested inline math inside a text-family argument: an unescaped `$…$` span in
/// `\text{…}` re-enters math mode (LaTeX's text-mode toggle), so
/// `\text{if $x>0$ then}` splits into literal TextRuns and recursively-parsed
/// math segments. `\$` stays a literal; an unpaired `$` is a positioned error;
/// the inner parse continues the outer depth so `$`-nesting can't blow the stack.
class TextNestedMathTest {

    @Test
    void aMathSpanInsideTextReentersMathMode() {
        assertEquals("L(Txt[ROMAN](if ) L(A(x,ORD) A(>,REL) A(0,ORD)) Txt[ROMAN]( then))",
            MathParserTest.pp(MathParser.parse("\\text{if $x>0$ then}")));
    }

    @Test
    void plainTextWithoutDollarIsTheByteIdenticalSingleRun() {
        // The fast path: no unescaped $ → the exact pre-split node shape.
        assertEquals("Txt[ROMAN](50 apples)",
            MathParserTest.pp(MathParser.parse("\\text{50 apples}")));
    }

    @Test
    void escapedDollarRendersALiteralDollarAndNeverToggles() {
        // \$ is the literal-dollar escape: no math span, and the backslash is
        // consumed — the run carries a plain $ (matches LaTeX; now that an
        // unescaped $ toggles, this is the only way to write a dollar in text).
        assertEquals("Txt[ROMAN](cost $5)",
            MathParserTest.pp(MathParser.parse("\\text{cost \\$5}")));
    }

    @Test
    void escapedDollarBesideARealSpanUnescapesInTheSplitPathToo() {
        assertEquals("L(Txt[ROMAN](pay $2 for ) A(x,ORD))",
            MathParserTest.pp(MathParser.parse("\\text{pay \\$2 for $x$}")));
    }

    @Test
    void multipleMathSpansEachReenter() {
        assertEquals("L(Txt[ROMAN](for ) A(x,ORD) Txt[ROMAN]( and ) A(y,ORD))",
            MathParserTest.pp(MathParser.parse("\\text{for $x$ and $y$}")));
    }

    @Test
    void nestedMathParsesFullCommands() {
        // The inner span is a full recursive parse, not a char copy — a fraction works.
        assertEquals("L(Txt[ROMAN](half is ) Frac(A(1,ORD),A(2,ORD)))",
            MathParserTest.pp(MathParser.parse("\\text{half is $\\frac{1}{2}$}")));
    }

    @Test
    void otherTextFamiliesSplitToo() {
        assertEquals("L(Txt[BOLD](bold ) A(x,ORD))",
            MathParserTest.pp(MathParser.parse("\\textbf{bold $x$}")));
    }

    @Test
    void unpairedDollarThrowsAPositionedError() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{broken $x}"));
        assertTrue(e.getMessage().contains("Unpaired '$'"), e.getMessage());
    }

    @Test
    void aBadInnerSpanSurfacesAsAClearNestedError() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{oops $\\frac{1}$ end}"));
        assertTrue(e.getMessage().contains("nested math"), e.getMessage());
    }

    @Test
    void emptySpanContributesNothing() {
        assertEquals("L(Txt[ROMAN](a) Txt[ROMAN](b))",
            MathParserTest.pp(MathParser.parse("\\text{a$$b}")));
    }

    @Test
    void deepDollarTextNestingFailsCleanlyNotStackOverflow() {
        // The inner parse CONTINUES the outer parser's depth, so pathological
        // \text{$\text{$…}$} nesting hits MAX_DEPTH as a clean MathSyntaxException —
        // never a StackOverflowError (the PARSER-BUG class the corpus outlaws).
        StringBuilder sb = new StringBuilder();
        int levels = 600; // > MAX_DEPTH (512)
        sb.append("x".repeat(0));
        for (int i = 0; i < levels; i++) {
            sb.append("\\text{$");
        }
        sb.append("y");
        for (int i = 0; i < levels; i++) {
            sb.append("$}");
        }
        assertThrows(MathSyntaxException.class, () -> MathParser.parse(sb.toString()));
    }

    @Test
    void rendersInAlphabetEndToEnd() {
        String svg = LatteX.render("\\text{if $x>0$ then } y");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"), "in-alphabet only");
    }

    @Test
    void nestedSpanRendersLikeTheManualForm() {
        // The math segment renders byte-identically to the same expression composed
        // manually beside plain text runs — the split is pure structure, no styling
        // leak from the surrounding text family into the math span.
        String nested = LatteX.render("\\text{n } x^2");
        String viaSplit = LatteX.render("\\text{n $x^2$}");
        assertEquals(nested, viaSplit, "split math == the same math outside the text run");
    }

    @Test
    void scriptsAttachToTheWholeTextGroup() {
        // \text{...}^2 superscripts the whole group, exactly as before the split.
        MathNode node = MathParser.parse("\\text{a $x$}^2");
        assertInstanceOf(MathNode.SupSub.class, node);
    }
}
