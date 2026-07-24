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
        // A backslash immediately before the terminal brace, \text{oops\}, is an
        // ESCAPED literal brace (\}) — so the argument is unterminated and fails
        // loud as an unbalanced brace. (Before the lexTextArgument brace-escape
        // fix this same input mis-closed early on the escaped brace and served a
        // lone trailing backslash into literalText, which then reported the
        // "trailing '\' with nothing to escape" error — Marlow noted that old
        // test passed only via the very brace-counting bug under repair, so the
        // genuine dangling-brace shape is asserted here instead.)
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{oops\\}"));
        assertTrue(e.getMessage().contains("Unbalanced brace in \\text"), e.getMessage());
    }

    // ---- INDEPENDENT escaped-brace gates (Marlow exact-tip review of f4c0df90) --
    // Each of these fails on the pre-fix lexTextArgument (which counted \{ and \}
    // toward structural brace depth) and passes after it. They are NOT the
    // balanced \{...\} case, whose two counting errors cancelled and hid the bug.

    @Test
    void aStandaloneEscapedLeftBraceIsALiteralBraceOnEverySurface() {
        // Pre-fix: \{ wrongly incremented depth, so the argument looked
        // unterminated -> "Unbalanced brace in \text argument".
        assertEquals("Txt[ROMAN]({)", MathParserTest.pp(MathParser.parse("\\text{\\{}")));
        assertTrue(LatteX.toMathML("\\text{\\{}").contains("<mtext>{</mtext>"));
        assertEquals("{", ariaOf(LatteX.render("\\text{\\{}")));
    }

    @Test
    void aStandaloneEscapedRightBraceIsALiteralBraceOnEverySurface() {
        // Pre-fix: \} wrongly decremented depth to 0 and closed the argument
        // early, leaving a lone trailing backslash -> trailing-backslash error.
        assertEquals("Txt[ROMAN](})", MathParserTest.pp(MathParser.parse("\\text{\\}}")));
        assertTrue(LatteX.toMathML("\\text{\\}}").contains("<mtext>}</mtext>"));
        assertEquals("}", ariaOf(LatteX.render("\\text{\\}}")));
    }

    @Test
    void anEscapedRightBraceEmbeddedInOrdinaryTextIsALiteralBraceOnEverySurface() {
        // Pre-fix: \} closed the argument at 'a\', dropping "b}" and reporting a
        // trailing-backslash error.
        assertEquals("Txt[ROMAN](a}b)", MathParserTest.pp(MathParser.parse("\\text{a\\}b}")));
        assertTrue(LatteX.toMathML("\\text{a\\}b}").contains("<mtext>a}b</mtext>"));
        assertEquals("a}b", ariaOf(LatteX.render("\\text{a\\}b}")));
    }

    @Test
    void anEscapedLeftBraceEmbeddedInOrdinaryTextIsALiteralBraceOnEverySurface() {
        // Pre-fix: \{ incremented depth, so the closing brace only returned to
        // depth 1 and the argument looked unterminated -> "Unbalanced brace".
        assertEquals("Txt[ROMAN](a{b)", MathParserTest.pp(MathParser.parse("\\text{a\\{b}")));
        assertTrue(LatteX.toMathML("\\text{a\\{b}").contains("<mtext>a{b</mtext>"));
        assertEquals("a{b", ariaOf(LatteX.render("\\text{a\\{b}")));
    }

    // ---- MIXED fixture: a decoded positive beside a rejected negative -----

    // ---- REGRESSION gate: an EVEN backslash run before a brace, inside a nested
    // $…$ math span (Marlow exact-tip review 469 of 400002b; plan d2f3447c) -------
    // The escaped-brace fix (400002b) special-cased ONLY a '\' immediately followed
    // by '{'/'}' and did not consume a control symbol atomically. So in \\{ it copied
    // the first '\' normally, then mistook the SECOND '\' + '{' for an escaped literal
    // brace — dropping that '{'s structural role. But \\ is ONE control symbol (a
    // \substack row separator; $…$ re-enters math mode where \\ is valid) and the
    // brace AFTER it must stay structural. lexTextArgument now consumes '\' + its
    // escaped token as one unit (mirroring the top-level lexer), so these parse again.
    //
    // Both repros are asserted against main's behaviour (exit 0, the structure below)
    // AND against their DIRECT nested-math equivalent — the same math parsed straight,
    // without the \text{$…$} re-parse — which cannot regress via lexTextArgument and
    // pins that the text-embedded span yields an identical tree on every surface.

    @Test
    void evenBackslashRunBeforeBraceInNestedMathParsesTwoRowSubstack_parseTree() {
        // \text{$\substack{a\\{b}}$}: main -> two-row substack {a}{b}; tip pre-fix
        // threw "Unpaired '$' in \text argument". The whole \text is a single math
        // span, so it collapses to just the substack node — identical to the direct
        // \substack{a\\{b}}.
        String embedded = MathParserTest.pp(MathParser.parse("\\text{$\\substack{a\\\\{b}}$}"));
        String direct = MathParserTest.pp(MathParser.parse("\\substack{a\\\\{b}}"));
        assertEquals("Mat[SUBSTACK](A(a,ORD)\\\\A(b,ORD))", embedded);
        assertEquals(direct, embedded, "text-embedded span must match the direct math");
    }

    @Test
    void trailingEvenBackslashRunInNestedMathParsesSubstack_parseTree() {
        // \text{$\substack{a\\}$}: main -> substack with a leading row 'a' and a
        // trailing row separator (a single 'a' row in the tree); tip pre-fix threw
        // "Unbalanced brace in \text argument".
        String embedded = MathParserTest.pp(MathParser.parse("\\text{$\\substack{a\\\\}$}"));
        String direct = MathParserTest.pp(MathParser.parse("\\substack{a\\\\}"));
        assertEquals("Mat[SUBSTACK](A(a,ORD))", embedded);
        assertEquals(direct, embedded, "text-embedded span must match the direct math");
    }

    @Test
    void evenBackslashRunBeforeBraceInNestedMathParsesTwoRowSubstack_mathML() {
        String embedded = LatteX.toMathML("\\text{$\\substack{a\\\\{b}}$}");
        assertEquals(LatteX.toMathML("\\substack{a\\\\{b}}"), embedded);
        assertEquals("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
                + "<mtable><mtr><mtd><mi>a</mi></mtd></mtr>"
                + "<mtr><mtd><mi>b</mi></mtd></mtr></mtable></math>",
            embedded);
    }

    @Test
    void trailingEvenBackslashRunInNestedMathParsesSubstack_mathML() {
        String embedded = LatteX.toMathML("\\text{$\\substack{a\\\\}$}");
        assertEquals(LatteX.toMathML("\\substack{a\\\\}"), embedded);
        assertEquals("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
                + "<mtable><mtr><mtd><mi>a</mi></mtd></mtr></mtable></math>",
            embedded);
    }

    @Test
    void evenBackslashRunBeforeBraceInNestedMathRendersSameAsDirectMath_svgAria() {
        // SVG/ARIA surface: rendering no longer throws (exit 0) and the accessible
        // description of the text-embedded span equals that of the direct math —
        // the structural match the audit calls for on the render surface.
        String embedded = LatteX.render("\\text{$\\substack{a\\\\{b}}$}");
        assertTrue(embedded.contains("<svg"), "renders an SVG (no exception)");
        assertEquals(ariaOf(LatteX.render("\\substack{a\\\\{b}}")), ariaOf(embedded));
    }

    @Test
    void trailingEvenBackslashRunInNestedMathRendersSameAsDirectMath_svgAria() {
        String embedded = LatteX.render("\\text{$\\substack{a\\\\}$}");
        assertTrue(embedded.contains("<svg"), "renders an SVG (no exception)");
        assertEquals(ariaOf(LatteX.render("\\substack{a\\\\}")), ariaOf(embedded));
    }

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
