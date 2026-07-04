package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode.Atom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tier-1a symbol breadth: every command the parser can emit maps to a real STIX
 * Two Math glyph (no {@code .notdef}), and a representative expression per
 * category parses, lays out, and emits real glyph paths within the minimal,
 * sanitizer-safe SVG alphabet.
 *
 * <p>The coverage test is the "log the gap" discipline made executable: if STIX
 * lacks a glyph for a code point in the symbol table, this test names it so the
 * entry can be removed (never faked with a {@code <text>} fallback).
 */
class SymbolCoverageTest {

    // The minimal, sanitizer-safe alphabet (must match RenderTest / containment).
    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "use", "defs", "symbol", "text", "foreignObject", "script", "style",
        "animate", "image", "a");

    // ------------------------------------------------------------------
    // STIX coverage: every symbol-table code point must resolve to a glyph.
    // ------------------------------------------------------------------

    @Test
    void everySymbolResolvesToARealStixGlyph() {
        SfntFont font = SfntFont.loadBundled();
        Map<String, Integer> table = MathParser.allSymbolCodePointsForTest();
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, Integer> e : table.entrySet()) {
            int gid = font.glyphId(e.getValue());
            if (gid == 0) {
                gaps.add("%s (U+%04X)".formatted(e.getKey(), e.getValue()));
            }
        }
        assertTrue(gaps.isEmpty(),
            "STIX Two Math has no glyph for: " + gaps + " — remove or remap these "
            + "(NEVER emit a <text> fallback).");
        assertTrue(table.size() > 250, "the T1a table is broad: " + table.size() + " commands");
    }

    // ------------------------------------------------------------------
    // Per-category render sanity: parse -> layout -> emit, real paths, no throw.
    // ------------------------------------------------------------------

    private static void assertRendersGlyphs(String latex) {
        String svg = LatteX.render(latex);
        assertTrue(svg.contains("<path"), "renders at least one glyph path: " + latex);
        assertTrue(svg.contains("viewBox="), "has a viewBox: " + latex);
    }

    @Test
    void relationsRender() {
        assertRendersGlyphs("a \\leq b \\geq c \\equiv d \\sim e \\simeq f");
        assertRendersGlyphs("x \\prec y \\succ z \\ll w \\gg v \\subseteq u \\supseteq t");
        assertRendersGlyphs("a \\parallel b \\perp c \\models d \\vdash e \\propto f");
    }

    @Test
    void binaryOperatorsRender() {
        assertRendersGlyphs("a \\pm b \\mp c \\times d \\div e \\cdot f \\ast g");
        assertRendersGlyphs("a \\oplus b \\ominus c \\otimes d \\oslash e \\odot f");
        assertRendersGlyphs("a \\cap b \\cup c \\sqcap d \\sqcup e \\wedge f \\vee g \\setminus h");
    }

    @Test
    void arrowsRender() {
        assertRendersGlyphs("a \\to b \\gets c \\mapsto d \\leftarrow e \\rightarrow f");
        assertRendersGlyphs("a \\Leftarrow b \\Rightarrow c \\leftrightarrow d \\Leftrightarrow e");
        assertRendersGlyphs("a \\longleftarrow b \\longrightarrow c \\hookrightarrow d "
            + "\\rightleftharpoons e \\leadsto f");
    }

    @Test
    void greekVariantsRender() {
        assertRendersGlyphs("\\varepsilon \\vartheta \\varphi \\varpi \\varrho \\varsigma \\digamma");
        assertRendersGlyphs("\\Gamma \\Delta \\Theta \\Upsilon \\Phi \\Psi \\Omega");
    }

    @Test
    void miscOrdinariesRender() {
        assertRendersGlyphs("\\infty \\partial \\nabla \\hbar \\ell \\Re \\Im \\aleph");
        assertRendersGlyphs("\\forall \\exists \\nexists \\emptyset \\angle \\triangle "
            + "\\neg \\top \\bot");
    }

    @Test
    void dotsRender() {
        assertRendersGlyphs("1 \\cdots n");
        assertRendersGlyphs("a \\vdots b \\ddots c \\ldots d \\dots e");
    }

    @Test
    void bigOperatorsRender() {
        assertRendersGlyphs("\\bigoplus \\bigotimes \\bigcup \\bigcap \\coprod \\oint");
    }

    @Test
    void delimitersAsSymbolsRender() {
        assertRendersGlyphs("\\langle f \\rangle \\lfloor x \\rfloor \\lceil y \\rceil");
    }

    // ------------------------------------------------------------------
    // Negations: explicit precomposed relations + the \not prefix.
    // ------------------------------------------------------------------

    @Test
    void explicitNegationsRender() {
        assertRendersGlyphs("a \\nleq b \\ngeq c \\nmid d \\notin e \\neq f");
    }

    @Test
    void notPrefixProducesPrecomposedNegation() {
        // \not= is ≠ (U+2260); \not\in is ∉ (U+2209); \not\subset is ⊄ (U+2284).
        Atom ne = (Atom) MathParser.parse("\\not=");
        org.junit.jupiter.api.Assertions.assertEquals(0x2260, ne.codePoint());
        Atom notin = (Atom) MathParser.parse("\\not\\in");
        org.junit.jupiter.api.Assertions.assertEquals(0x2209, notin.codePoint());
        assertRendersGlyphs("A \\not\\subset B");
    }

    @Test
    void notPrefixOnUnsupportedTargetFailsLoudly() {
        // \not on a symbol with no precomposed negation must throw (never fake it).
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\not\\alpha"));
        assertTrue(e.getMessage().contains("not"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // Security: a broad symbol expression stays within the minimal alphabet.
    // ------------------------------------------------------------------

    @Test
    void broadSymbolExpressionStaysInMinimalAlphabet() {
        String svg = LatteX.render(
            "\\forall \\varepsilon > 0 \\; \\exists \\delta : \\alpha \\oplus \\beta "
            + "\\leq \\gamma \\Rightarrow x \\in \\langle a, b \\rangle");
        for (String forbidden : FORBIDDEN_ELEMENTS) {
            assertFalse(svg.contains("<" + forbidden), "must not emit <" + forbidden + ">");
        }
        assertFalse(svg.contains("href"), "must not emit any href");
        assertFalse(svg.contains("data:"), "must not emit any data: URI");

        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)").matcher(svg);
        while (tag.find()) {
            assertTrue(ALLOWED_ELEMENTS.contains(tag.group(1)),
                "element out of alphabet: <" + tag.group(1) + ">");
        }
    }
}
