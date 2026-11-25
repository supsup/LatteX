package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/// Plan fd362dd3 (lattex project): the aria-label says a number as a number, reads the font's
/// own script characters as the script they are, and names a combining mark. Repros from the
/// crew's showcase pages (Fixpoint 35627, Marlow 35628): on main 5cb804f7 e^{\pi\sqrt{163}} read
/// "... the square root of 1 6 3" and M=E+\frac{4{,}372}{E} read "4 , 3 7 2". Reviews: Lattice
/// 35715, Fixpoint 35732 (nine defects, each pinned below). Every assertion is a pinned string;
/// where a raw form must read as its command form, both are pinned to the same string.
class AriaNumbersAndMarksTest {

    private static String label(String latex) {
        String svg = LatteX.render(latex);
        int a = svg.indexOf("aria-label=\"") + "aria-label=\"".length();
        return svg.substring(a, svg.indexOf('"', a));
    }

    private static void reads(String expected, String... inputs) {
        for (String in : inputs) {
            assertEquals(expected, label(in), in);
        }
    }

    @Test
    void theShowcaseReprosReadNumbersAsNumbers() {
        reads("e to the power of π the square root of 163", "e^{\\pi\\sqrt{163}}");
        reads("M = E + the fraction 4,372 over E + the fraction 96,256 over E squared + ⋯",
            "M=E+\\frac{4{,}372}{E}+\\frac{96{,}256}{E^2}+\\cdots");
    }

    @Test
    void decimalsAndExponentsAreOneNumber() {
        reads("0.5", "0.5");
        reads("3.14159", "3.14159");
        reads("x to the power of 12", "x^{12}");
        reads("2 to the power of 10", "2^{10}");
        reads("1.5.2", "1.5.2");
        reads("3,14", "3{,}14");
        // your-call questions (35732), decided: a leading point joins; a thin space separates
        reads(".5", ".5");
        reads("1 000", "1\\,000");
    }

    @Test
    void aScriptOnTheLastDigitCarriesTheWholeNumber() {
        reads("10 to the power of - 3", "10^{-3}");
        reads("10 to the power of 10", "10^{10}");
        reads("12 squared", "12^2");
        reads("1.5 squared", "1.5^{2}");
        reads("10 sub i", "10_{i}");
    }

    @Test
    void digitsOfAnyNumberingSystemJoinButSystemsDoNotMix() {
        reads("𝟏𝟐", "\\mathbf{12}");
        // the font draws neither Arabic-Indic nor full-width digits: they still read as one
        // number (and never join ASCII digits), and the mark says they are boxes (14579fe4)
        reads("٣٤ (missing glyph: U+0663, U+0664)", "٣٤");
        reads("1 ٣ (missing glyph: U+0663)", "1٣");
        reads("１２ (missing glyph: U+FF11, U+FF12)", "１２");
    }

    @Test
    void onePictureOneLabel() {
        // Fixpoint 35732 item 7: \mathpunct{,} draws as the bare comma, so it reads as one; a
        // digit-only group draws as its digits, so it joins the number
        reads("1 , 2", "1,2", "1\\mathpunct{,}2");
        reads("f ( 1 , 2 )", "f(1,2)");
        reads("4 , 372", "4,372");
        reads("a . b", "a.b");
        reads("1234", "1{23}4");
        reads("123", "1{2}3");
    }

    @Test
    void aMarkAfterANumberSitsOverItsLastDigit() {
        // item 1: the bar's ink is over the 6 only, as 0.1\bar{6} draws
        reads("0.1 bar over 6", "0.16̄", "0.1\\bar{6}");
        reads("1 dot over 2", "12̇", "1\\dot{2}");
        reads("0.1 vector 6", "0.16⃗", "0.1\\vec{6}");
        // and when the mark carries a script (Lattice review of f94842f)
        reads("0.1 bar over 6 squared", "0.16̄^2", "0.1\\bar{6}^2", "0.16̄²");
        reads("1 bar over 2 to the power of 3", "12̄^3", "1\\bar{2}^3");
    }

    @Test
    void theFontsScriptCharactersReadAsTheirCommandForms() {
        reads("x squared", "x²", "x^{2}");
        reads("x to the power of 3", "x³", "x^{3}");
        reads("x to the power of n", "xⁿ", "x^{n}");
        reads("x sub 12", "x₁₂", "x_{12}");
        reads("x sub 3 to the power of 2", "x²₃", "x₃²", "x^2_3");
        reads("x sub 4 to the power of 23", "x²₄³", "x^{23}_{4}");
        // item 9: the content reads as the command form's content
        reads("x to the power of - 1", "x⁻¹", "x^{-1}");
        // a character the font lacks, set by plan 14579fe4 as a real script
        reads("x sub n", "xₙ", "x_{n}");
    }

    @Test
    void aScriptRunWithNoBaseIsReadWhole() {
        // item 2: an isotope's prescript, at the start or after an operator
        reads("superscript 235 U", "²³⁵U");
        reads("x + superscript 235 U", "x+²³⁵U", "x\\mathbin{+}²³⁵U");
        // an operator by its EFFECTIVE class is no base either (Lattice review of f94842f)
        reads("x = superscript 235 U", "x=²³⁵U", "x\\mathrel{=}²³⁵U");
        reads("x , superscript 235 U", "x,²³⁵U", "x\\mathpunct{,}²³⁵U");
        reads("x ( superscript 235 U )", "x(²³⁵U)", "x\\mathopen{(}²³⁵U)");
    }

    @Test
    void aCharacterDrawnAsTheBoxIsNeverReadAsAScript() {
        // item 4: the label must not describe ink that is not there (U+10FC: the font has
        // neither it nor its base, so it stays as typed and draws the box)
        reads("x ჼ (missing glyph: U+10FC)", "xჼ");
    }

    @Test
    void anOrdinalIndicatorIsALetter() {
        reads("1 º", "1º");
    }

    @Test
    void aMarkOrScriptCharacterThatCarriesAScriptReadsInPlace() {
        // item 3: x, U+0304, ^2 read 'x', a bare mark, 'squared'
        reads("bar over x squared", "x̄^2", "x̄²", "\\bar{x}^2");
        reads("x squared to the power of 3", "x²^3");
    }

    @Test
    void aCombiningMarkReadsAsItsAccentCommand() {
        reads("bar over x", "x̄", "\\bar{x}");
        reads("hat over x", "x̂", "\\hat{x}");
        reads("vector v", "v⃗", "\\vec{v}");
    }

    @Test
    void manyMarksOnOneWordAreJoinedInLinearWork() {
        // item 5: x then 80,000 marks took 649 ms (15 ms on main): each mark rebuilt the word.
        // A million marks, past any source cap, through the label builder itself.
        List<MathNode> row = new ArrayList<>();
        row.add(new MathNode.Atom('x', MathNode.MathClass.ORD, false));
        for (int k = 0; k < 1_000_000; k++) {
            row.add(new MathNode.Atom(0x0304, MathNode.MathClass.ORD, false));
        }
        MathNode.MathList list = new MathNode.MathList(row);
        String label = Assertions.assertTimeoutPreemptively(Duration.ofMillis(1500), () -> LatteX.describe(list));
        assertTrue(label.endsWith("bar over bar over x"), label.substring(Math.max(0, label.length() - 40)));
        assertEquals(1_000_000 * "bar over ".length() + 1, label.length());
    }

    @Test
    void aMarkLandsOnTheScriptItIsDrawnOver() {
        // Fixpoint review of 17e5c34, B3: the bar after a superscript two is drawn over the 2
        reads("x to the power of bar over 2", "x\u00B2\u0304", "x^2\u0304");
        reads("bar over x squared", "x\u0304\u00B2", "x\u0304^2", "\\bar{x}^2");
        reads("12 to the power of bar over 3", "12^3\u0304");
        reads("x to the power of 2 bar over 3", "x^{23}\u0304");
        // a point left at the end of a split number is its own word, as the command form reads it
        reads("0 . bar over 3", "0.3\u0304", "0.\\bar{3}");
        reads("1 . bar over 5", "1.5\u0304");
    }

    @Test
    void alternatingMarksAndScriptsAreLinear() {
        // B1: x then (U+0304, U+00B2) repeated copied the growing word on every pair
        List<MathNode> row = new ArrayList<>();
        row.add(new MathNode.Atom('x', MathNode.MathClass.ORD, false));
        for (int k = 0; k < 200_000; k++) {
            row.add(new MathNode.Atom(0x0304, MathNode.MathClass.ORD, false));
            row.add(new MathNode.Atom(0x00B2, MathNode.MathClass.ORD, false));
        }
        MathNode.MathList list = new MathNode.MathList(row);
        String label = Assertions.assertTimeoutPreemptively(Duration.ofMillis(1500), () -> LatteX.describe(list));
        // each bar after the first is drawn over the 2 before it; the last 2 has none
        assertTrue(label.startsWith("bar over x to the power of bar over 2 to the power of bar over 2 "), label.substring(0, 80));
        assertTrue(label.endsWith(" squared"), label.substring(label.length() - 40));
    }

    @Test
    void theCheapOnesFromTheSameReview() {
        reads("1.5", "1\\mathord{.}5", "1.5");
        reads("123", "1{}23", "123");
        reads(".5 squared", ".5^2");
        reads("superscript 2", "\u00B2");
        reads("x sub 1 to the power of 2", "x_1\u00B2", "x_1^2");
        // an operator, a \big delimiter or a big operator is no base, coloured or not
        reads("x + \u0304", "x+\u0304", "x\\textcolor{red}{+}\u0304");
        assertTrue(!label("\\sum\u00B2").contains("squared"), label("\\sum\u00B2"));
        assertTrue(!label("x\\big(\u00B2U").contains("squared"), label("x\\big(\u00B2U"));
    }
}
