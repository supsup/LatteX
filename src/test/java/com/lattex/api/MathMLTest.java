package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathSyntaxException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/**
 * {@link LatteX#toMathML} — Presentation-MathML from the same parse tree as the SVG.
 * The load-bearing property is that it's always WELL-FORMED XML (an interop/a11y
 * surface that emits malformed markup is a liability), verified across the whole
 * wild corpus via the JDK's XML parser.
 */
class MathMLTest {

    /// Overload carrying the corpus coordinate, so a sweep failure names the ROW that produced it
    /// rather than only the emitted XML (plan bc50471c).
    private static void assertWellFormed(String xml, String where) {
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }, () -> "MathML must be well-formed XML at " + where + ": " + xml);
    }

    private static void assertWellFormed(String xml) {
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }, "MathML must be well-formed XML: " + xml);
    }

    @Test
    void mapsCoreConstructsToTheRightMathMLElements() {
        assertTrue(LatteX.toMathML("x")
            .startsWith("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"));
        // digit -> mn, letter -> mi, script -> msup
        assertTrue(LatteX.toMathML("x^2").contains("<msup><mi>x</mi><mn>2</mn></msup>"),
            LatteX.toMathML("x^2"));
        assertTrue(LatteX.toMathML("\\frac{a}{b}").contains("<mfrac><mi>a</mi><mi>b</mi></mfrac>"));
        assertTrue(LatteX.toMathML("\\sqrt{x}").contains("<msqrt>"));
        assertTrue(LatteX.toMathML("\\sqrt[3]{x}").contains("<mroot>"));
        assertTrue(LatteX.toMathML("\\sum_{i=1}^n i").contains("<munderover>"));
        assertTrue(LatteX.toMathML("\\left( x \\right)").contains("fence=\"true\""));
        assertTrue(LatteX.toMathML("x_i").contains("<msub>"));
    }

    @Test
    void escapesXmlSpecialCharacters() {
        String ml = LatteX.toMathML("a < b > c"); // < and > are relation atoms
        assertTrue(ml.contains("&lt;"), "'<' must be escaped: " + ml);
        assertTrue(ml.contains("&gt;"), "'>' must be escaped: " + ml);
        assertWellFormed(ml);
    }

    @Test
    void everyOkCorpusRowEmitsWellFormedMathML() throws Exception {
        // THE SAME CORPUS THE SVG RENDERER SWEEPS -- and until plan bc50471c it was not.
        // This comment claimed sameness while the code read /com/lattex/wild-corpus.tsv, where
        // all four SVG sweeps (CorpusRenderSweepTest, OutputCapPostconditionTest,
        // FontCacheByteIdentityRatchetTest, GroupmapGrammarContractTest) read
        // com/lattex/parse/corpus.tsv. A guard whose comment asserts a sameness it does not have
        // is why the rule-accent crash never reddened here.
        //
        // The two corpora are NOT the same shape, so this is a re-parse and not a path swap: this
        // file is tab-separated tier/group/latex/description with # comments, latex in COLUMN 2,
        // where wild-corpus.tsv put it in column 3 behind an "OK" flag. Swapping only the path
        // would have matched nothing and failed on the count -- a red for the wrong reason.
        int checked = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("com/lattex/parse/corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length < 3 || !"PARSES-NOW".equals(cols[0].trim())) {
                    continue;
                }
                String latex = cols[2];
                // Name the corpus LINE, as CorpusRenderSweepTest does, so a red points at its input.
                assertWellFormed(LatteX.toMathML(latex), "corpus.tsv:" + lineNo + " [" + latex + "]");
                checked++;
            }
        }
        assertTrue(checked > 150,
            "expected to sweep the whole PARSES-NOW corpus, only did " + checked);
    }

    @Test
    void toMathMLThrowsOnlyLatteXException() throws Exception {
        // STEP 4 of plan bc50471c: the public surface's exception boundary. Anything escaping
        // toMathML must be a LatteXException; a raw RuntimeException is a leak.
        //
        // THE ASSERTION IS ON LatteXException AND NOT ON IllegalArgumentException, DELIBERATELY.
        // LatteXException EXTENDS IllegalArgumentException, so asserting the superclass would have
        // been satisfied by the very defect this plan fixes: the rule-accent crash threw a BARE
        // IllegalArgumentException ("Not a valid Unicode code point: 0xFFFFFFFF") out of mo(). An
        // assertion both the fixed and the broken code satisfy distinguishes neither.
        java.util.List<String> inputs = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("com/lattex/parse/corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length >= 3) {
                    inputs.add(cols[2]);
                }
            }
        }
        // Malformed and edge inputs alongside the corpus: the boundary must hold on the failure
        // path too, which is where a raw exception is most likely to escape.
        inputs.addAll(java.util.List.of(
            "x^", "\\frac{a}", "", "   ", "\\overline{}", "\\underline{}",
            "\\begin{matrix}", "\\notacommand{x}", "{", "}"));

        for (String latex : inputs) {
            try {
                LatteX.toMathML(latex);
            } catch (LatteXException expected) {
                // the declared boundary
            } catch (Throwable leaked) {
                throw new AssertionError("toMathML leaked a non-LatteXException for ["
                    + latex + "]: " + leaked.getClass().getName() + ": " + leaked.getMessage(),
                    leaked);
            }
        }
    }

    @Test
    void malformedInputFailsLoudNotSilentlyEmpty() {
        // toMathML shares parse() — malformed input must throw, not emit junk MathML.
        assertThrows(MathSyntaxException.class, () -> LatteX.toMathML("x^"));
        assertThrows(MathSyntaxException.class, () -> LatteX.toMathML("\\frac{a}"));
    }

    @Test
    void delimitedMatricesWrapTheTableInFences() {
        // pmatrix carries ( ) delimiters -> stretchy mo fences, so a screen reader hears
        // the brackets instead of a bare table.
        String pm = LatteX.toMathML("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}");
        assertTrue(pm.contains("<mtable>") && pm.contains("fence=\"true\""),
            "pmatrix should wrap its table in mo fences: " + pm);
        assertWellFormed(pm);
        // a plain matrix (no delimiters) stays a bare mtable — no phantom fences.
        String plain = LatteX.toMathML("\\begin{matrix} a & b \\end{matrix}");
        assertFalse(plain.contains("fence=\"true\""), "plain matrix has no fences: " + plain);
    }

    @Test
    void nolimitsPutsScriptsBesideNotAboveBelow() {
        // \sum_{i}^{n} keeps limits above/below (munderover); an explicit \nolimits sets
        // them to the side (msubsup), matching the SVG.
        assertTrue(LatteX.toMathML("\\sum_{i=1}^n i").contains("<munderover>"));
        String nolim = LatteX.toMathML("\\int\\nolimits_a^b f");
        assertTrue(nolim.contains("<msubsup>"), "\\nolimits should emit msubsup: " + nolim);
    }

    @Test
    void cancelFamilyMapsToMencloseStrikesAndCanceltoUsesTheArrowNotation() {
        // Plain strikes carry the diagonal-strike notations.
        assertTrue(LatteX.toMathML("\\cancel{x}").contains("notation=\"updiagonalstrike\""),
            LatteX.toMathML("\\cancel{x}"));
        assertTrue(LatteX.toMathML("\\bcancel{x}").contains("notation=\"downdiagonalstrike\""),
            LatteX.toMathML("\\bcancel{x}"));
        assertTrue(LatteX.toMathML("\\xcancel{x}")
                .contains("notation=\"updiagonalstrike downdiagonalstrike\""),
            LatteX.toMathML("\\xcancel{x}"));

        // \cancelto uses MathML 4's recommended northeastarrow notation (NOT a plain
        // diagonal strike), and still carries its target value accessibly (as the
        // superscript on the arrowed body).
        String to = LatteX.toMathML("\\cancelto{0}{x^2}");
        assertTrue(to.contains("notation=\"northeastarrow\""),
            "\\cancelto should use menclose northeastarrow: " + to);
        assertFalse(to.contains("notation=\"updiagonalstrike\""),
            "\\cancelto is an arrow, not a plain strike: " + to);
        assertTrue(to.contains("<msup>") && to.contains("<mn>0</mn>"),
            "\\cancelto keeps its target value accessible: " + to);
        assertWellFormed(to);
        assertWellFormed(LatteX.toMathML("\\xcancel{\\frac{a}{b}}"));
    }
}
