package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Plan fd362dd3 (lattex project): the aria-label says a number as a number, reads the font's
/// own script characters as the script they are, and names a combining mark. Repros from the
/// crew's showcase pages (Fixpoint 35627, Marlow 35628): on main 5cb804f7 e^{\pi\sqrt{163}} read
/// "... the square root of 1 6 3" and M=E+\frac{4{,}372}{E} read "4 , 3 7 2".
class AriaNumbersAndMarksTest {

    private static String label(String latex) {
        String svg = LatteX.render(latex);
        int a = svg.indexOf("aria-label=\"") + "aria-label=\"".length();
        return svg.substring(a, svg.indexOf('"', a));
    }

    @Test
    void theShowcaseReprosReadNumbersAsNumbers() {
        assertEquals("e to the power of π the square root of 163", label("e^{\\pi\\sqrt{163}}"));
        assertEquals("M = E + the fraction 4,372 over E + the fraction 96,256 over E squared + ⋯",
            label("M=E+\\frac{4{,}372}{E}+\\frac{96{,}256}{E^2}+\\cdots"));
    }

    @Test
    void decimalsAndExponentsAreOneNumber() {
        assertEquals("0.5", label("0.5"));
        assertEquals("3.14159", label("3.14159"));
        assertEquals("x to the power of 12", label("x^{12}"));
        assertEquals("2 to the power of 10", label("2^{10}"));
        assertEquals("1.5.2", label("1.5.2"), "a section number reads whole");
        assertEquals("3,14", label("3{,}14"), "a braced decimal comma");
    }

    @Test
    void theFontsScriptCharactersInEitherOrderReadAsTheExplicitScripts() {
        // Lattice review of 5e956f64: x²₃ read 'x squared sub 3' and x₃² 'x sub 3 squared'
        assertEquals(label("x^2_3"), label("x\u00B2\u2083"));
        assertEquals(label("x^2_3"), label("x\u2083\u00B2"));
        assertEquals("x sub 3 to the power of 2", label("x\u00B2\u2083"));
        assertEquals(label("x^{23}_{4}"), label("x\u00B2\u2084\u00B3"));
        assertEquals(label("x^{2}"), label("x\u00B2"), "one kind alone, as before");
        assertEquals(label("x_{12}"), label("x\u2081\u2082"));
    }

    @Test
    void aScriptOnTheLastDigitCarriesTheWholeNumber() {
        // TeX attaches a script to the last digit only; the label must not split the number
        assertEquals("10 to the power of - 3", label("10^{-3}"));
        assertEquals("10 to the power of 10", label("10^{10}"));
        assertEquals("12 squared", label("12^2"));
        assertEquals("1.5 squared", label("1.5^{2}"));
        assertEquals("10 sub i", label("10_{i}"));
    }

    @Test
    void digitsOfAnyNumberingSystemJoinButSystemsDoNotMix() {
        assertEquals("\uD835\uDFCF\uD835\uDFD0", label("\\mathbf{12}"));
        assertEquals("\uFF11\uFF12", label("\uFF11\uFF12"));
        assertEquals("\u0663\u0664", label("\u0663\u0664"));
        assertEquals("1 \u0663", label("1\u0663"));
    }

    @Test
    void aBareCommaOrPointBetweenNonDigitsStaysASeparator() {
        assertEquals("f ( 1 , 2 )", label("f(1,2)"));
        assertEquals("4 , 372", label("4,372"), "unbraced: TeX's list separator, not a group separator");
        assertEquals("a . b", label("a.b"));
    }

    @Test
    void theFontsOwnScriptCharactersReadAsTheirScripts() {
        assertEquals(label("x^{2}"), label("x²"));
        assertEquals(label("x^{3}"), label("x³"));
        assertEquals(label("x^{n}"), label("xⁿ"));
        assertEquals(label("x_{12}"), label("x₁₂"));
    }

    @Test
    void aCombiningMarkReadsAsItsAccentCommand() {
        assertEquals(label("\\bar{x}"), label("x̄"));
        assertEquals(label("\\hat{x}"), label("x̂"));
        assertEquals(label("\\vec{v}"), label("v⃗"));
    }
}
