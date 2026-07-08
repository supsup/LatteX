package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // The same corpus the SVG renderer sweeps: MathML must be well-formed XML for
        // every real-world formula, or the interop surface can't be trusted.
        int checked = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/com/lattex/wild-corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 4);
                if (p.length < 4 || !"OK".equals(p[0])) {
                    continue;
                }
                assertWellFormed(LatteX.toMathML(p[3]));
                checked++;
            }
        }
        assertTrue(checked > 400, "expected to sweep the whole OK corpus, only did " + checked);
    }
}
