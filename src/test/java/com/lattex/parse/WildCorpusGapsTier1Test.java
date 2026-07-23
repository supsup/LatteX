package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.ClassOverride;
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
    // 3. \mathopen \mathclose \mathord \mathbin \mathrel \mathpunct — atom-class
    // wrappers LaTeXML emits to pin down TeX's inter-atom spacing explicitly.
    // ------------------------------------------------------------------

    @Test
    void atomClassWrappersOverrideSpacing() {
        // A realistic LaTeXML-emitted shape: an operator name whose scripts are
        // explicitly bracketed as \mathopen/\mathclose (a common normal-form after
        // macro expansion), plus a symbol re-classed as a binary operator.
        assertRendersGlyphs(
            "f\\mathopen{(}x\\mathclose{)} \\mathbin{\\cdot} y \\mathrel{\\sim} z, \\mathpunct{;}");
        ClassOverride ord = (ClassOverride) MathParser.parse("\\mathord{x}");
        assertEquals(MathClass.ORD, ord.forcedClass());
        ClassOverride bin = (ClassOverride) MathParser.parse("\\mathbin{x}");
        assertEquals(MathClass.BIN, bin.forcedClass());
    }

    @Test
    void mathopenWithEmptyContentIsAZeroWidthOpenMarker() {
        // LaTeXML emits \mathopen{} spontaneously (no content) as a bare class
        // marker — must not throw "expects an argument".
        assertRendersGlyphs("\\mathopen{} x + y");
    }

    // ------------------------------------------------------------------
    // 4. Upright Greek (upgreek package): \\upalpha \\uptau \\upbeta etc. — the
    // ISO-80000 upright-Greek convention common in physics/chemistry corpora.
    // ------------------------------------------------------------------

    @Test
    void uprightGreekRenders() {
        // \\upalpha alongside \mathcal (the legacy {\cal ...} form lands in the
        // NEXT chunk; the wild-corpus fixture for the combined shape is added
        // there once both are available).
        assertRendersGlyphs("\\mathcal{L}(\\upalpha)");
        assertRendersGlyphs("\\uptau_{\\uprho} = \\upbeta \\cdot \\upmu");
        Atom upalpha = (Atom) MathParser.parse("\\upalpha");
        assertEquals(MathClass.ORD, upalpha.mathClass());
        // Upright Greek must be a DIFFERENT glyph than the default (slanted)
        // \alpha — STIX Two Math's base Greek block is drawn italic; the only
        // genuinely upright Greek it ships is the Mathematical Bold Greek run
        // already used by \boldsymbol/\bm (MathVariant.Style.BOLDSYMBOL).
        Atom alpha = (Atom) MathParser.parse("\\alpha");
        assertNotEquals(alpha.codePoint(), upalpha.codePoint());
    }

    @Test
    void uprightGreekVariantSymbolsRender() {
        assertRendersGlyphs("\\upvarepsilon \\upvartheta \\upvarpi \\upvarrho \\upvarsigma \\upvarphi");
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
