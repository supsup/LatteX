package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
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
    // 2. subarray — routed to \substack's single-column-stack machinery.
    // LaTeXML expands \substack into \begin{subarray}{c}...\end{subarray}, so
    // this shape is common in harvested corpora (a limit stack under \sum).
    // ------------------------------------------------------------------

    @Test
    void subarrayUnderSumRendersLikeSubstack() {
        assertRendersGlyphs(
            "\\sum_{\\begin{subarray}{c} i \\ge 0 \\\\ j \\le n \\end{subarray}} a_{ij}");
        Matrix m = (Matrix) MathParser.parse(
            "\\begin{subarray}{c} i \\ge 0 \\\\ j \\le n \\end{subarray}");
        assertEquals(MatrixKind.SUBSTACK, m.kind());
        assertEquals(1, m.columnAligns().size());
        assertEquals(ColumnAlign.CENTER, m.columnAligns().get(0));
        assertEquals(2, m.rows().size());
    }

    @Test
    void subarrayLColspecLeftAligns() {
        Matrix m = (Matrix) MathParser.parse(
            "\\begin{subarray}{l} k \\in S \\\\ k \\neq 0 \\end{subarray}");
        assertEquals(ColumnAlign.LEFT, m.columnAligns().get(0));
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
