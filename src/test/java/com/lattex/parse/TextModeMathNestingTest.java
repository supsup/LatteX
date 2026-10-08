package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/// Plan c432f899: LatteX nests math inside `\text`, as LaTeX does.
///
/// Every formula below is a verbatim display formula from the coderefs/math research
/// corpus (138,172 formulas, measured on main 85c1ed7), where each one was refused with
/// "Unknown command in \text: ... commands are not expanded in text". One test per form:
/// `\(...\)` re-entering math, the five text-mode accents found in names, and
/// `\ref`/`\eqref` inside a text run. A census pins that the refusal's own advice
/// (`$...$` inside every text-family command) renders.
class TextModeMathNestingTest {

    private static String pp(String latex) {
        return MathParserTest.pp(MathParser.parse(latex));
    }

    // ---- form 1: \(...\) re-enters math inside a text run ---------------------

    @Test
    void parenMathInsideTextRendersTheCorpusTheorem() {
        String corpus = "\\begin{gathered} \\text{If \\(K\\) is categorical in some"
            + " \\(\\lambda\\ge H(K)\\),}\\\\ \\text{then it is categorical in every"
            + " \\(\\mu\\ge H(K)\\).} \\end{gathered}";
        String dollars = "\\begin{gathered} \\text{If $K$ is categorical in some"
            + " $\\lambda\\ge H(K)$,}\\\\ \\text{then it is categorical in every"
            + " $\\mu\\ge H(K)$.} \\end{gathered}";
        assertEquals(pp(dollars), pp(corpus), "\\(...\\) is the same toggle as $...$");
        assertEquals(LatteX.render(dollars), LatteX.render(corpus));
    }

    @Test
    void parenMathInsideTextSplitsLikeDollar() {
        assertEquals("L(Txt[ROMAN](for ) A(ν,ORD) Txt[ROMAN](-almost every ))",
            pp("\\text{for \\(\\nu\\)-almost every }"));
    }

    @Test
    void parenMathInsideHboxRendersTheCorpusFormula() {
        String corpus = "\\mathcal E_{w_0}(\\xi)\\ge \\Delta_{\\mathrm{ev}}\\int w_0|\\xi|^2"
            + " \\quad\\hbox{if \\(\\xi\\) is even and }\\int w_0\\xi=0.";
        assertEquals(pp(corpus.replace("\\(\\xi\\)", "$\\xi$")), pp(corpus));
    }

    @Test
    void parenMathKeepsInnerGroupsAndNestedText() {
        assertEquals(pp("\\frac{12}{34}"), pp("\\text{\\(\\frac{12}{34}\\)}"));
        assertEquals(pp("\\text{$x$}"), pp("\\text{\\(\\text{\\(x\\)}\\)}"),
            "a nested \\text inside a \\(...\\) span pairs its own \\) first");
    }

    @Test
    void escapedBackslashBeforeParenIsNotAToggle() {
        // `\\(` is the control symbol \\ followed by a literal '(' - never an opener.
        // \\ is not a text-mode escape, so this stays the existing loud refusal.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{a\\\\(b\\)}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text"), e.getMessage());
    }

    @Test
    void unpairedParenOpenerIsAPositionedError() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{broken \\(x}"));
        assertTrue(e.getMessage().contains("Unpaired '\\('"), e.getMessage());
    }

    @Test
    void strayParenCloserStillFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{a \\) b}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text: \\)"), e.getMessage());
    }

    @Test
    void deepParenTextNestingHitsTheDepthGuard() {
        StringBuilder sb = new StringBuilder();
        int levels = 600; // > MAX_DEPTH (512)
        for (int i = 0; i < levels; i++) {
            sb.append("\\text{\\(");
        }
        sb.append('y');
        for (int i = 0; i < levels; i++) {
            sb.append("\\)}");
        }
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(sb.toString()));
        assertTrue(e.getMessage().contains("nesting too deep"), e.getMessage());
    }

    // ---- census: the refusal's own advice renders --------------------------------

    @Test
    void theRefusalsAdviceRendersInEveryTextFamilyCommand() {
        // "commands are not expanded in text; wrap math in $...$" - for every text-family
        // command, following that advice must render, and \(...\) must be the same tree.
        Symbols.TEXT_COMMANDS.forEach((command, style) -> {
            String dollar = "\\" + command + "{if $\\lambda\\ge H(K)$ then}";
            String paren = "\\" + command + "{if \\(\\lambda\\ge H(K)\\) then}";
            String svg = LatteX.render(dollar);
            assertTrue(svg.startsWith("<svg"), "\\" + command + " $...$ renders");
            assertEquals(pp(dollar), pp(paren), "\\" + command + " \\(...\\) == $...$");
            assertEquals(svg, LatteX.render(paren), "\\" + command + " same SVG");
        });
    }

    // ---- form 2: text-mode accents emit the precomposed character ----------------

    @Test
    void umlautInTextIsThePrecomposedCharacter() {
        String corpus = pp("\\kappa_0=[\\theta-\\epsilon\\delta v]\\ \\text{K\\\"ahler}");
        assertTrue(corpus.endsWith("Txt[ROMAN](Kähler))"), corpus);
        assertEquals("Txt[ROMAN]( is Kähler)", pp("\\hbox{ is K\\\"ahler}"));
    }

    @Test
    void acuteInTextBareAndBracedFromTheCorpus() {
        assertEquals("Txt[ROMAN]( is finite étale)", pp("\\text{ is finite \\'etale}"));
        assertEquals("Txt[ROMAN]( is finite étale)", pp("\\text{ is finite \\'{e}tale}"));
    }

    @Test
    void graveCircumflexAndTildeAccentsCompose() {
        assertEquals("Txt[ROMAN](à la île Señor)", pp("\\text{\\`a la \\^ile Se\\~nor}"));
        assertEquals("Txt[ROMAN](Poincaré)", pp("\\textrm{Poincar\\'e}"));
        assertEquals("Txt[ROMAN](Martínez)", pp("\\text{Mart\\'{\\i}nez}"),
            "\\i is the dotless-i base LaTeX authors accent");
    }

    @Test
    void accentWithNoPrecomposedCharacterFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{\\\"q}"));
        assertTrue(e.getMessage().contains("no precomposed character"), e.getMessage());
    }

    @Test
    void accentWithNothingToAccentFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{a\\'}"));
        assertTrue(e.getMessage().contains("Unknown command in \\text"), e.getMessage());
    }

    @Test
    void accentedTextRendersInAlphabet() {
        String svg = LatteX.render("\\omega\\text{ K\\\"ahler, finite \\'etale}");
        assertTrue(svg.startsWith("<svg"), svg);
        assertFalse(svg.contains("<text"), "glyphs stay filled paths");
    }

    // ---- form 3: \ref / \eqref inside text ----------------------------------------

    @Test
    void eqrefInsideTextRendersTheUnresolvedMarkerLikeMathMode() {
        assertEquals("Txt[ROMAN](the root-protection condition (??) holds)",
            pp("\\text{the root-protection condition \\eqref{eq:root-protection} holds}"));
        assertEquals("Txt[ROMAN](the two densities in (??))",
            pp("\\hbox{the two densities in \\eqref{eq:M4}}"));
    }

    @Test
    void refInsideTextAfterATieRendersTheMarkerAndTheTieIsASpace() {
        assertEquals("Txt[ROMAN](from Proposition ??)",
            pp("\\text{from Proposition~\\ref{prop:prime-minor-arcs}}"));
    }

    @Test
    void refWithoutABracedKeyFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{see \\ref}"));
        assertTrue(e.getMessage().contains("\\ref"), e.getMessage());
    }

    @Test
    void theKeyNeverLeaksIntoOutput() {
        String svg = LatteX.render("\\text{see \\eqref{elliptic}}");
        String mathml = LatteX.toMathML("\\text{see \\eqref{elliptic}}");
        assertFalse(mathml.contains("elliptic"), mathml);
        assertTrue(mathml.contains("see (??)"), mathml);
        assertTrue(svg.startsWith("<svg"));
    }

    // ---- form 4: \mathrm{\acute et} - undecided, stays a clear refusal ------------

    @Test
    void mathAccentInsideMathrmStaysAClearRefusal() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("H^j_{\\mathrm{\\acute et}}(X)"));
        assertTrue(e.getMessage().contains("Unknown command in \\mathrm: \\acute"),
            e.getMessage());
    }
}
