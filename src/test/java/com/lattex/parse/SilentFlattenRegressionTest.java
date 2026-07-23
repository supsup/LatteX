package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/// Regression pins for the two silent-WRONG flatten defects (plan 08eed9a5):
///
/// 1. An unknown/unexpandable command inside `\text{…}` was emitted as LITERAL
///    flattened characters (braces dropped), silently — `\text{blah \frac{a}{b}}`
///    served "blah \fracab" and `\text{…\eqref{elliptic}}` served "\eqrefelliptic".
///    The supported-in-text set is EXPLICIT: plain text runs, `\$` (the
///    literal-dollar escape), invisible grouping braces, and nested math via
///    `$…$`. Any command token (`\` + letters) in a literal segment fails loud.
///
/// 2. The optional `[t]`/`[b]`/`[c]` position argument of `aligned`/`split`
///    rendered as visible math content ("row 1: [ t ] a, = b") instead of being
///    parsed — inconsistent with `array`, whose `[t]` fails loud. It is now
///    parsed and IGNORED for display (LatteX renders no surrounding baseline to
///    align against); anything else in the bracket fails loud like array does.
class SilentFlattenRegressionTest {

    // ---- defect 1: commands inside \text fail loud, never flatten --------

    @Test
    void unknownCommandInsideTextFailsLoudNotFlattened() {
        // Repro verbatim (0.11.0 served aria-label "x ∈ blah \fracab").
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x \\in \\text{blah \\frac{a}{b}}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text: \\frac"),
            e.getMessage());
    }

    @Test
    void eqrefInsideTextFailsLoudNotServedAsLiteral() {
        // The real-world hit: \eqref in a trailing text note was served as the
        // literal characters "\eqrefelliptic".
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("u = 0 \\text{ on the boundary, see \\eqref{elliptic}}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text: \\eqref"),
            e.getMessage());
    }

    @Test
    void commandInLiteralSegmentBesideAMathSpanFailsLoudToo() {
        // The split path (text with $…$ spans) validates its literal segments the
        // same way the fast path does.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{for $x$ see \\eqref{elliptic}}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text: \\eqref"),
            e.getMessage());
    }

    @Test
    void otherTextFamiliesFailLoudAndNameTheirOwnCommand() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\textbf{bold \\alpha}"));
        assertTrue(e.getMessage().contains("Unknown command in \\textbf: \\alpha"),
            e.getMessage());
    }

    // ---- defect 2: aligned/split optional position argument --------------

    @Test
    void alignedPositionArgumentIsParsedAndIgnoredNotRendered() {
        // Repro verbatim (0.11.0 served aria-label "row 1: [ t ] a, = b").
        assertEquals(
            MathParserTest.pp(MathParser.parse("\\begin{aligned} a&=b \\end{aligned}")),
            MathParserTest.pp(MathParser.parse("\\begin{aligned}[t] a&=b \\end{aligned}")),
            "[t] is a position argument, not row content");
    }

    @Test
    void alignedPositionArgumentNeverReachesTheAccessibleLabel() {
        String svg = LatteX.render("\\begin{aligned}[t] a&=b \\end{aligned}");
        assertFalse(svg.contains("[ t ]"),
            "the position argument must not surface as content: " + ariaOf(svg));
    }

    @Test
    void allThreePositionLettersAreAcceptedOnAlignedAndSplit() {
        for (String pos : new String[] {"t", "b", "c"}) {
            assertEquals(
                MathParserTest.pp(MathParser.parse("\\begin{aligned} a&=b \\end{aligned}")),
                MathParserTest.pp(MathParser.parse(
                    "\\begin{aligned}[" + pos + "] a&=b \\end{aligned}")),
                "[" + pos + "] on aligned");
            assertEquals(
                MathParserTest.pp(MathParser.parse("\\begin{split} a&=b \\end{split}")),
                MathParserTest.pp(MathParser.parse(
                    "\\begin{split}[" + pos + "] a&=b \\end{split}")),
                "[" + pos + "] on split");
        }
    }

    @Test
    void aBadPositionArgumentFailsLoudLikeArraysSpecErrors() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{aligned}[x] a&=b \\end{aligned}"));
        assertTrue(e.getMessage().contains("aligned")
                && e.getMessage().contains("position"), e.getMessage());
        MathSyntaxException e2 = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{aligned}[tb] a&=b \\end{aligned}"));
        assertTrue(e2.getMessage().contains("position"), e2.getMessage());
        MathSyntaxException e3 = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{aligned}[t a&=b \\end{aligned}"));
        assertTrue(e3.getMessage().contains("position"), e3.getMessage());
    }

    // ---- MIXED fixture: the legitimate cases stay exactly as they are ----

    @Test
    void legitimateTextAndAlignedCasesStayGreen() {
        // Plain words: the single literal run, spaces preserved.
        assertEquals("Txt[ROMAN](plain words)",
            MathParserTest.pp(MathParser.parse("\\text{plain words}")));
        // Math-in-text via $…$ still re-enters math mode.
        assertEquals("L(Txt[ROMAN](let ) A(u,ORD) Txt[ROMAN]( vanish))",
            MathParserTest.pp(MathParser.parse("\\text{let $u$ vanish}")));
        // \$ stays the literal-dollar escape, grouping braces stay invisible.
        assertEquals("Txt[ROMAN](cost $5)",
            MathParserTest.pp(MathParser.parse("\\text{cost \\$5}")));
        assertEquals("Txt[ROMAN](a b c)",
            MathParserTest.pp(MathParser.parse("\\text{a {b} c}")));
        // A command inside a nested math span is FULLY parsed, not a text token.
        assertEquals(MathParserTest.pp(MathParser.parse("\\frac{1}{2}")),
            MathParserTest.pp(MathParser.parse("\\text{$\\frac{1}{2}$}")));
        // aligned WITHOUT a position argument parses exactly as before.
        String svg = LatteX.render("\\begin{aligned} a&=b \\\\ c&=d \\end{aligned}");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(ariaOf(svg).contains("row 1: a, = b"), ariaOf(svg));
    }

    private static String ariaOf(String svg) {
        int i = svg.indexOf("aria-label=\"");
        int j = svg.indexOf('"', i + "aria-label=\"".length());
        return svg.substring(i + "aria-label=\"".length(), j);
    }
}
