package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import org.junit.jupiter.api.Test;

/**
 * LatteX plan e8c87953 "wild-corpus gaps tier 1": six parser-coverage classes
 * pulled straight from real arxiv-harvested LaTeX (LaTeXML output, legacy TeX
 * font switches, ISO-style upright Greek) that today fail loud with a named
 * {@link MathSyntaxException} instead of rendering. Each class below is a
 * realistic expression from that class (not a minimal token), red-first: the
 * class was seen throwing before the corresponding parser addition landed.
 *
 * <p>The original raw survey-corpus file that seeded this plan did not survive;
 * these fixtures are reconstructed straight from the class descriptions in the
 * plan body.
 */
class WildCorpusGapsTier1Test {

    private static void assertRendersGlyphs(String latex) {
        String svg = LatteX.render(latex);
        assertTrue(svg.contains("<path"), "renders at least one glyph path: " + latex);
        assertTrue(svg.contains("viewBox="), "has a viewBox: " + latex);
    }

    // ------------------------------------------------------------------
    // 1. \# — cardinality notation (e.g. group order of an elliptic curve
    // over a finite field): \#E(\mathbb{F}_p). Trivial ordinary-symbol class.
    // ------------------------------------------------------------------

    @Test
    void cardinalityHashRenders() {
        assertRendersGlyphs("\\#E(\\mathbb{F}_p)");
        Atom hash = (Atom) MathParser.parse("\\#");
        assertEquals('#', hash.codePoint());
        assertEquals(MathClass.ORD, hash.mathClass());
    }

    // ------------------------------------------------------------------
    // Negative control: an unrelated unknown command OUTSIDE the six classes
    // must still fail loud with the same MathSyntaxException shape after every
    // addition in this file — additive changes must never widen what parses.
    // ------------------------------------------------------------------

    @Test
    void unrelatedUnknownCommandStillFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\totallynotarealcommand{x}"));
        assertTrue(e.getMessage().contains("Unknown command"), e.getMessage());
        assertTrue(e.isUnsupportedConstruct(), "classified as an unsupported construct");
    }
}
