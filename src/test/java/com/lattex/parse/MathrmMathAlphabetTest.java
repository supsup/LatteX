package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
import org.junit.jupiter.api.Test;

/// `\mathrm` is a MATH alphabet, not a text command (audit-20261012 finding 1).
///
/// It used to sit in `Symbols.TEXT_COMMANDS`, so the lexer captured its argument raw
/// and `\mathrm{CaCO_3}` drew a literal underscore: the wild-corpus rows
/// `\mathrm{m/s^2}`, `\mathrm{J\,K^{-1}}`, `\mathrm{m\,s^{-1}}` and `\mathrm{CaCO_3}`
/// were all "OK" on a ratchet that only looks for a `<path`. LaTeX reads the argument as
/// math: scripts attach, commands run, spaces are ignored. Each surface is asserted (tree,
/// MathML, accessible label), and the controls pin what must NOT move: a plain
/// `\mathrm` word is still the one roman `TextRun` it always was, and the real text
/// commands still take their argument literally.
class MathrmMathAlphabetTest {

    private static String pp(String latex) {
        return MathParserTest.pp(MathParser.parse(latex));
    }

    private static String ariaOf(String svg) {
        int i = svg.indexOf("aria-label=\"");
        int j = svg.indexOf('"', i + "aria-label=\"".length());
        return svg.substring(i + "aria-label=\"".length(), j);
    }

    // ---- red-first: the corpus rows cited in the audit ---------------------

    @Test
    void aSubscriptInsideMathrmIsARealSubscript() {
        // Pre-fix: Txt[ROMAN](CaCO_3), <mtext>CaCO_3</mtext>, aria "CaCO_3".
        assertEquals("L(Txt[ROMAN](CaC) SS(A(O,ORD,rm),_A(3,ORD,rm)))", pp("\\mathrm{CaCO_3}"));
        String mathml = LatteX.toMathML("\\mathrm{CaCO_3}");
        assertTrue(mathml.contains("<msub><mi mathvariant=\"normal\">O</mi><mn>3</mn></msub>"),
            mathml);
        assertFalse(mathml.contains("_"), "no literal underscore reaches MathML: " + mathml);
        assertEquals("CaC O sub 3", ariaOf(LatteX.render("\\mathrm{CaCO_3}")));
    }

    @Test
    void aSuperscriptInsideMathrmIsARealSuperscript() {
        // Pre-fix: Txt[ROMAN](m/s^2), the caret drawn as a glyph.
        assertEquals("L(Txt[ROMAN](m) A(/,ORD,rm) SS(A(s,ORD,rm),^A(2,ORD,rm)))",
            pp("\\mathrm{m/s^2}"));
        String mathml = LatteX.toMathML("\\mathrm{m/s^2}");
        assertTrue(mathml.contains("<msup><mi mathvariant=\"normal\">s</mi><mn>2</mn></msup>"),
            mathml);
        assertFalse(mathml.contains("^"), "no literal caret reaches MathML: " + mathml);
        assertEquals("m / s squared", ariaOf(LatteX.render("\\mathrm{m/s^2}")));
    }

    @Test
    void aBracedScriptAndAThinSpaceInsideMathrmAreMath() {
        // Pre-fix: Txt[ROMAN](J K^-1), the braces dropped and the caret kept.
        assertEquals(
            "L(Txt[ROMAN](J) Sp(3.0) SS(A(K,ORD,rm),^L(A(-,BIN,rm) A(1,ORD,rm))))",
            pp("\\mathrm{J\\,K^{-1}}"));
        // \, is math mode's thin space here, not text mode's word space.
        assertEquals("L(Txt[ROMAN](J) Sp(3.0) Txt[ROMAN](s))", pp("\\mathrm{J\\,s}"));
        String mathml = LatteX.toMathML("\\mathrm{J\\,s}");
        assertTrue(mathml.contains("<mtext>J</mtext>") && mathml.contains("<mtext>s</mtext>"),
            mathml);
        assertFalse(mathml.contains("<mtext>J s</mtext>"), mathml);
        assertEquals("J s", ariaOf(LatteX.render("\\mathrm{J\\,s}")));
    }

    @Test
    void commandsRunAndSpacesAreIgnoredInsideMathrm() {
        // Pre-fix: "Unknown command in \mathrm: \alpha".
        assertEquals(MathVariant.upright(MathParser.parse("\\alpha")),
            MathParser.parse("\\mathrm{\\alpha}"));
        // Math mode ignores spaces; pre-fix this was the two-word run "a b".
        assertEquals(new TextRun("ab", TextStyle.ROMAN), MathParser.parse("\\mathrm{a b}"));
    }

    // ---- controls: what must not move --------------------------------------

    @Test
    void aPlainMathrmWordIsStillOneRomanTextRun() {
        assertEquals(new TextRun("MSE", TextStyle.ROMAN), MathParser.parse("\\mathrm{MSE}"));
        assertEquals(new TextRun("H2O", TextStyle.ROMAN), MathParser.parse("\\mathrm{H2O}"));
        assertEquals("L(Txt[ROMAN](d) A(x,ORD))", pp("\\mathrm{d}x"));
        assertEquals("SS(A(M,ORD),_Txt[ROMAN](u))", pp("M_{\\mathrm u}"));
        assertTrue(LatteX.toMathML("\\mathrm{MSE}").contains("<mtext>MSE</mtext>"));
        assertEquals("MSE", ariaOf(LatteX.render("\\mathrm{MSE}")));
    }

    @Test
    void theTextCommandsStillTakeTheirArgumentLiterally() {
        // \text and \textrm are TEXT mode in LaTeX: spaces kept, no scripts, no commands.
        assertEquals("Txt[ROMAN](a b)", pp("\\text{a b}"));
        assertEquals("Txt[ROMAN](x^2)", pp("\\textrm{x^2}"));
        assertEquals("Txt[ROMAN](J s)", pp("\\text{J\\,s}"));
        assertFalse(CommandRegistry.hasGrammar("mathrm", CommandRegistry.GrammarKind.TEXT_ARGUMENT));
        for (String text : new String[] {"text", "textrm", "textnormal", "hbox", "textbf",
                "textit", "texttt"}) {
            assertTrue(CommandRegistry.hasGrammar(text, CommandRegistry.GrammarKind.TEXT_ARGUMENT),
                "\\" + text + " is a text-mode command");
        }
    }
}
