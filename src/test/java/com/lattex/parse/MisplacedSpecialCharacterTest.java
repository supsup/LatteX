package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.Outcome;
import com.lattex.api.RenderResult;
import com.lattex.parse.MathNode.Matrix;
import java.util.List;
import org.junit.jupiter.api.Test;

/// TeX's special characters are never silently drawn as glyphs (audit-20261012 finding 7).
///
/// `&`, `%`, `#` and `$` have no meaning as a math character. Each used to fall through
/// to the ordinary-atom path, so `a & b`, `x % note`, `#` and `$$x^2$$` rendered, with
/// the special (and, for `%`, the whole comment) drawn as math. Unbalanced braces,
/// unknown commands and a stray `\\` already refused loudly; these four now do too,
/// with a caret and the escape that does draw the glyph.
///
/// The controls are the other half: every place one of the four IS meaningful keeps
/// working (a column separator, a macro parameter, a hex colour, the whole-input `$…$`
/// wrapper, text mode), and the escaped forms still draw.
class MisplacedSpecialCharacterTest {

    private static final List<String> SPECIALS = List.of("&", "%", "#", "$");

    private static String pp(String latex) {
        return MathParserTest.pp(MathParser.parse(latex));
    }

    private static MathSyntaxException refused(String latex) {
        return assertThrows(MathSyntaxException.class, () -> MathParser.parse(latex), latex);
    }

    // ---- red-first: the four inputs cited in the audit ---------------------

    @Test
    void theAuditInputsAreRefusedNotDrawn() {
        // Pre-fix each parsed: L(A(a,ORD) A(&,ORD) A(b,ORD)), the comment as atoms,
        // A(#,ORD), and four dollar atoms around x squared.
        assertEquals(2, refused("a & b").offset());
        assertEquals(2, refused("x % note").offset());
        assertEquals(0, refused("#").offset());
        assertEquals(0, refused("$$x^2$$").offset());
        assertTrue(refused("a & b").getMessage().startsWith("Misplaced '&'"));
        assertTrue(refused("x % note").getMessage().startsWith("Unexpected '%'"));
        assertTrue(refused("#").getMessage().startsWith("Unexpected '#'"));
        assertTrue(refused("$$x^2$$").getMessage().startsWith("Unexpected '$'"));
    }

    @Test
    void eachRefusalPointsAtTheCharacterAndNamesItsEscape() {
        for (String special : SPECIALS) {
            MathSyntaxException e = refused("ab " + special + " c");
            assertEquals(3, e.offset(), special + ": the caret is on the character");
            assertTrue(e.getMessage().contains("write \\" + special + " for"),
                special + ": the refusal names the escape: " + e.getMessage());
            assertFalse(e.isUnsupportedConstruct(),
                special + " is malformed input, not an unsupported command");
            assertFalse(e.caretString().isEmpty(), special);
        }
    }

    @Test
    void aSpecialIsRefusedWhereverAnAtomIsExpected() {
        // In a group, as a one-token argument, in a script, inside \left..\right.
        assertEquals(8, refused("\\frac{a & b}{c}").offset());
        assertEquals(5, refused("\\sqrt%").offset());
        assertEquals(2, refused("x^#").offset());
        assertEquals(9, refused("\\left( a $ b \\right)").offset());
        // An unclosed or mid-formula dollar is not a wrapper, so it is not stripped
        // and not drawn.
        assertEquals(0, refused("$x^2").offset());
        assertEquals(1, refused("a$ + $b").offset());
    }

    @Test
    void theDiagnosticRenderClassifiesItAndNeverThrows() {
        for (String special : SPECIALS) {
            RenderResult r = assertDoesNotThrow(
                () -> LatteX.renderWithDiagnostics("a " + special + " b"), special);
            assertEquals(Outcome.PARSE_ERROR, r.diagnostics().outcome(), special);
            assertEquals(2, r.diagnostics().offset(), special);
        }
        assertEquals(Outcome.OK, LatteX.renderWithDiagnostics("a \\& b").diagnostics().outcome());
    }

    // ---- controls: where each character IS meaningful ----------------------

    @Test
    void theEscapedFormsStillDrawTheirGlyph() {
        assertEquals("L(A(a,ORD) A(&,ORD) A(b,ORD))", pp("a \\& b"));
        assertEquals("L(A(5,ORD) A(0,ORD) A(%,ORD))", pp("50\\%"));
        assertEquals("A(#,ORD)", pp("\\#"));
        assertEquals("L(A($,ORD) A(5,ORD))", pp("\\$5"));
    }

    @Test
    void anAmpersandStillSeparatesColumns() {
        Matrix grid = assertInstanceOf(Matrix.class,
            MathParser.parse("\\begin{matrix}a&b\\\\c&d\\end{matrix}"));
        assertEquals(2, grid.columnCount());
        assertDoesNotThrow(() -> MathParser.parse("\\begin{aligned}a&=b\\\\&=c\\end{aligned}"));
        assertDoesNotThrow(() -> MathParser.parse("\\bordermatrix{&1&2\\\\1&a&b\\\\2&c&d}"));
        // The cell boundary still ends a declaration and an argument; neither reads it.
        assertDoesNotThrow(() -> MathParser.parse("\\begin{matrix}\\displaystyle a&b\\end{matrix}"));
        assertTrue(refused("\\begin{matrix}\\frac1&2\\end{matrix}").getMessage()
            .contains("expects an argument"));
    }

    @Test
    void aHashStillMeansAMacroParameterOrAHexColour() {
        assertEquals(MathParser.parse("x^{2}"),
            MathParser.parse("\\newcommand{\\sq}[1]{#1^{2}} \\sq x"));
        assertDoesNotThrow(() -> MathParser.parse("\\textcolor{#ff8800}{z}"));
        // A '#' that survives into the formula is refused by the parser, as a stray
        // one in a definition body is by the expander.
        refused("\\newcommand{\\sq}[1]{#1^{2}} \\sq{#}");
    }

    @Test
    void theWholeInputDollarWrapperAndTextModeAreUntouched() {
        assertEquals(MathParser.parse("x^2"), MathParser.parse("$x^2$"));
        assertEquals("L(Txt[ROMAN](a ) A(x,ORD) Txt[ROMAN]( b))", pp("\\text{a $x$ b}"));
        // Text mode reads its own characters: a bare special there is literal text.
        assertEquals("Txt[ROMAN](50% off)", pp("\\text{50% off}"));
        assertEquals("Txt[ROMAN](R&D #1)", pp("\\text{R&D #1}"));
    }

    @Test
    void ordinaryPunctuationIsStillAnAtom() {
        // Only TeX's four special characters are refused; "other" characters are atoms.
        assertEquals("L(A(a,ORD) A(/,ORD) A(b,ORD))", pp("a/b"));
        assertEquals("L(A(n,ORD) A(!,ORD))", pp("n!"));
        assertEquals("L(A(a,ORD) A(|,ORD) A(b,ORD))", pp("a|b"));
        assertEquals("L(A(a,ORD) A(@,ORD) A(b,ORD))", pp("a@b"));
    }
}
