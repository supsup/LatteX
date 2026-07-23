package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/// Text-mode control-symbol decoding (plan d2f3447c, Marlow audit LTX-12 — the
/// follow-up to the text-flatten fix of plan 08eed9a5).
///
/// {@link SilentFlattenRegressionTest} pinned defect 1: a MULTI-letter command
/// inside `\text{…}` (`\frac`, `\eqref`, …) failed loud instead of silently
/// flattening. But a single-char escape that is NOT a letter — `\%`, `\#`, `\_`,
/// `\&` — fell through neither loud nor decoded: it kept its literal backslash
/// (`\text{50\%}` served "50\%" instead of "50%"). That is the residual this
/// plan closes: every backslash escape in `\text` now either DECODES (the
/// documented control-symbol set) or FAILS LOUD (everything else) — never a
/// silent third option.
///
/// Coverage spans every surface literalText's output reaches from one shared
/// traversal: the parser's own tree (`MathParserTest.pp`), Presentation-MathML
/// (`<mtext>`), and the accessible description — which is also the only textual
/// content the rendered SVG carries (`aria-label`; glyphs themselves are vector
/// paths, not DOM text nodes), so `ariaOf(LatteX.render(...))` is the SVG-surface
/// check the audit calls for.
class TextControlSymbolTest {

    private static String ariaOf(String svg) {
        int i = svg.indexOf("aria-label=\"");
        int j = svg.indexOf('"', i + "aria-label=\"".length());
        return svg.substring(i + "aria-label=\"".length(), j);
    }

    // ---- red-first: the two cases cited in the audit ----------------------

    @Test
    void percentDecodesNotKeepsTheBackslash() {
        // Pre-fix this served "50\%" (backslash retained) — neither correct
        // LaTeX control-symbol behavior nor fail-loud.
        assertEquals("Txt[ROMAN](50%)",
            MathParserTest.pp(MathParser.parse("\\text{50\\%}")));
        assertEquals("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
                + "<mtext>50%</mtext></math>",
            LatteX.toMathML("\\text{50\\%}"));
        assertEquals("50%", ariaOf(LatteX.render("\\text{50\\%}")));
    }

    @Test
    void hashDecodesNotKeepsTheBackslash() {
        // Pre-fix this served "tag\#1".
        assertEquals("Txt[ROMAN](tag#1)",
            MathParserTest.pp(MathParser.parse("\\text{tag\\#1}")));
        assertEquals("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
                + "<mtext>tag#1</mtext></math>",
            LatteX.toMathML("\\text{tag\\#1}"));
        assertEquals("tag#1", ariaOf(LatteX.render("\\text{tag\\#1}")));
    }

    // ---- every documented control symbol decodes, on every surface --------

    @Test
    void everySupportedControlSymbolDecodesInTheParseTree() {
        assertEquals("Txt[ROMAN](50%)", MathParserTest.pp(MathParser.parse("\\text{50\\%}")));
        assertEquals("Txt[ROMAN](tag#1)", MathParserTest.pp(MathParser.parse("\\text{tag\\#1}")));
        assertEquals("Txt[ROMAN](a_b)", MathParserTest.pp(MathParser.parse("\\text{a\\_b}")));
        assertEquals("Txt[ROMAN](R&D)", MathParserTest.pp(MathParser.parse("\\text{R\\&D}")));
        assertEquals("Txt[ROMAN](cost $5)", MathParserTest.pp(MathParser.parse("\\text{cost \\$5}")));
        // \{ and \} decode to LITERAL braces — distinct from bare {}, which stay
        // invisible grouping (mixed fixture: escaped vs. unescaped in one input).
        assertEquals("Txt[ROMAN]({set})", MathParserTest.pp(MathParser.parse("\\text{\\{set\\}}")));
        assertEquals("Txt[ROMAN](grouped)", MathParserTest.pp(MathParser.parse("\\text{{grouped}}")));
    }

    @Test
    void everySupportedControlSymbolDecodesInMathML() {
        assertTrue(LatteX.toMathML("\\text{50\\%}").contains("<mtext>50%</mtext>"));
        assertTrue(LatteX.toMathML("\\text{tag\\#1}").contains("<mtext>tag#1</mtext>"));
        assertTrue(LatteX.toMathML("\\text{a\\_b}").contains("<mtext>a_b</mtext>"));
        assertTrue(LatteX.toMathML("\\text{R\\&D}").contains("<mtext>R&amp;D</mtext>"),
            "MathML text content is still XML-escaped after decoding");
        assertTrue(LatteX.toMathML("\\text{\\{set\\}}").contains("<mtext>{set}</mtext>"));
    }

    @Test
    void everySupportedControlSymbolDecodesInTheAccessibleDescriptionAndSvg() {
        assertEquals("50%", ariaOf(LatteX.render("\\text{50\\%}")));
        assertEquals("tag#1", ariaOf(LatteX.render("\\text{tag\\#1}")));
        assertEquals("a_b", ariaOf(LatteX.render("\\text{a\\_b}")));
        // The aria-label is an XML attribute value, so & is escaped there too —
        // the SAME escaping toMathML applies, just via SvgEmitter's own escape().
        assertEquals("R&amp;D", ariaOf(LatteX.render("\\text{R\\&D}")));
        assertEquals("{set}", ariaOf(LatteX.render("\\text{\\{set\\}}")));
    }

    @Test
    void thinSpaceDecodesToAPlainSpaceNotARejectedEscape() {
        // Pre-fix: \mathrm{J\,s} served "J\,s" (backslash-comma retained). Real
        // corpus formulas (wild-corpus.tsv calculus-physics rows) rely on \, for
        // unit spacing inside \mathrm{...} — decode, don't reject.
        assertEquals("Txt[ROMAN](J s)", MathParserTest.pp(MathParser.parse("\\mathrm{J\\,s}")));
        assertTrue(LatteX.toMathML("\\mathrm{J\\,s}").contains("<mtext>J s</mtext>"));
        assertEquals("J s", ariaOf(LatteX.render("\\mathrm{J\\,s}")));
    }

    // ---- unsupported escapes fail loud, never silently keep the backslash --

    @Test
    void backslashBackslashFailsLoudNotDecodedToASpaceOrLineBreak() {
        // \\ is a line break in standard LaTeX text mode; a TextRun has no
        // line-break primitive (single-line layout), so there is no faithful
        // decode target. It fails loud like any other unsupported escape rather
        // than silently becoming a space or vanishing.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{line1\\\\line2}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text"), e.getMessage());
    }

    @Test
    void anUnmappedControlSymbolFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{a\\^b}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text: \\^"), e.getMessage());
    }

    @Test
    void aTrailingDanglingBackslashFailsLoud() {
        // Pre-fix this silently kept a lone trailing backslash character.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{oops\\}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text"), e.getMessage());
    }

    // ---- MIXED fixture: a decoded positive beside a rejected negative -----

    @Test
    void aDecodedSymbolBesideAnUnsupportedOneInTheSameArgumentStillFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{50\\% off, save\\\\more}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text"), e.getMessage());
    }

    @Test
    void decodingWorksInTheSplitPathBesideANestedMathSpanToo() {
        // The $…$-split path calls literalText on each literal segment too —
        // decoding must not be a fast-path-only behavior.
        assertEquals("L(Txt[ROMAN](50%, x=) A(x,ORD) Txt[ROMAN]( wins))",
            MathParserTest.pp(MathParser.parse("\\text{50\\%, x=$x$ wins}")));
    }
}
