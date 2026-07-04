package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathVariant.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 font-variant alphabets ({@code \mathbb \mathcal \mathfrak \mathbf
 * \mathsf \mathit \mathtt \mathscr \boldsymbol \bm}).
 *
 * <p>Three guarantees:
 * <ul>
 *   <li><strong>Mapping</strong> — the base&rarr;math-alphanumeric code-point
 *       remap (block offset + Letterlike-Symbols exceptions) is correct.</li>
 *   <li><strong>STIX coverage</strong> — every letter/digit/Greek we <em>map</em>
 *       resolves to a real bundled glyph (no {@code .notdef}); a gap is named, so
 *       we never reach for a glyph we cannot draw.</li>
 *   <li><strong>Containment</strong> — a variant expression renders real glyph
 *       paths and stays within the minimal, sanitizer-safe SVG alphabet.</li>
 * </ul>
 */
class FontVariantTest {

    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "use", "defs", "symbol", "text", "foreignObject", "script", "style",
        "animate", "image", "a");

    // ------------------------------------------------------------------
    // Mapping correctness — block offsets + Letterlike exceptions.
    // ------------------------------------------------------------------

    @Test
    void mapsContiguousBlockByOffset() {
        // Uppercase A / lowercase a anchor each style's Latin run.
        assertEquals(0x1D400, MathVariant.map(Style.BOLD, 'A'));
        assertEquals(0x1D41A, MathVariant.map(Style.BOLD, 'a'));
        assertEquals(0x1D5A0, MathVariant.map(Style.SANS, 'A'));
        assertEquals(0x1D670, MathVariant.map(Style.MONO, 'A'));
        assertEquals(0x1D434, MathVariant.map(Style.ITALIC, 'A'));
        assertEquals(0x1D44E, MathVariant.map(Style.ITALIC, 'a'));
        // \mathbf x -> bold x = U+1D431.
        assertEquals(0x1D431, MathVariant.map(Style.BOLD, 'x'));
    }

    @Test
    void resolvesLetterlikeExceptions() {
        // Double-struck: C H N P Q R Z live in the Letterlike Symbols block.
        assertEquals(0x2102, MathVariant.map(Style.BLACKBOARD, 'C'));
        assertEquals(0x210D, MathVariant.map(Style.BLACKBOARD, 'H'));
        assertEquals(0x2115, MathVariant.map(Style.BLACKBOARD, 'N'));
        assertEquals(0x211D, MathVariant.map(Style.BLACKBOARD, 'R'));
        assertEquals(0x2124, MathVariant.map(Style.BLACKBOARD, 'Z'));
        // A double-struck letter with no hole comes from the block (A = U+1D538).
        assertEquals(0x1D538, MathVariant.map(Style.BLACKBOARD, 'A'));
        // Script: several upper + three lower are Letterlike.
        assertEquals(0x2112, MathVariant.map(Style.SCRIPT, 'L'));
        assertEquals(0x2133, MathVariant.map(Style.SCRIPT, 'M'));
        assertEquals(0x210B, MathVariant.map(Style.SCRIPT, 'H'));
        assertEquals(0x212F, MathVariant.map(Style.SCRIPT, 'e'));
        assertEquals(0x210A, MathVariant.map(Style.SCRIPT, 'g'));
        // Fraktur: C H I R Z are Letterlike.
        assertEquals(0x212D, MathVariant.map(Style.FRAKTUR, 'C'));
        assertEquals(0x210C, MathVariant.map(Style.FRAKTUR, 'H'));
        assertEquals(0x2111, MathVariant.map(Style.FRAKTUR, 'I'));
        assertEquals(0x211C, MathVariant.map(Style.FRAKTUR, 'R'));
        // Italic small h is the reserved Planck-constant slot U+210E.
        assertEquals(0x210E, MathVariant.map(Style.ITALIC, 'h'));
    }

    @Test
    void mapsDigitsOnlyWhereTheStyleDefinesThem() {
        assertEquals(0x1D7D8, MathVariant.map(Style.BLACKBOARD, '0'));
        assertEquals(0x1D7CE, MathVariant.map(Style.BOLD, '0'));
        assertEquals(0x1D7E2, MathVariant.map(Style.SANS, '0'));
        assertEquals(0x1D7F6, MathVariant.map(Style.MONO, '0'));
        // Script / fraktur / italic have no math digits — the digit stays ASCII.
        assertEquals('5', MathVariant.map(Style.SCRIPT, '5'));
        assertEquals('5', MathVariant.map(Style.FRAKTUR, '5'));
        assertEquals('5', MathVariant.map(Style.ITALIC, '5'));
    }

    @Test
    void mapsGreekOnlyForBoldsymbolAndItalic() {
        // \boldsymbol{\beta}: beta (U+03B2) -> bold beta U+1D6C3.
        assertEquals(0x1D6C3, MathVariant.map(Style.BOLDSYMBOL, 0x03B2));
        // Bold uppercase Gamma (U+0393) -> U+1D6AA.
        assertEquals(0x1D6AA, MathVariant.map(Style.BOLDSYMBOL, 0x0393));
        // Bold phi symbol (\phi = U+03D5) -> the variant-phi slot U+1D6DF.
        assertEquals(0x1D6DF, MathVariant.map(Style.BOLDSYMBOL, 0x03D5));
        // Italic Greek exists too: italic beta = U+1D6FD.
        assertEquals(0x1D6FD, MathVariant.map(Style.ITALIC, 0x03B2));
        // \mathbf leaves Greek upright (LaTeX behaviour): beta unchanged.
        assertEquals(0x03B2, MathVariant.map(Style.BOLD, 0x03B2));
        // Styles with no math Greek leave it unchanged.
        assertEquals(0x03B2, MathVariant.map(Style.BLACKBOARD, 0x03B2));
    }

    @Test
    void nonLetterAtomsPassThrough() {
        // '+' has no math-alphanumeric form in any style.
        assertEquals('+', MathVariant.map(Style.BOLD, '+'));
        assertEquals('=', MathVariant.map(Style.SCRIPT, '='));
    }

    // ------------------------------------------------------------------
    // The parser produces remapped ordinary atoms (no new node kind).
    // ------------------------------------------------------------------

    @Test
    void parserRewritesAtomsInPlace() {
        // \mathbb{R} -> a single Atom carrying U+211D (ℝ).
        Atom r = (Atom) MathParser.parse("\\mathbb{R}");
        assertEquals(0x211D, r.codePoint());
        // Brace-optional single-token form: \mathfrak g -> fraktur g (block, U+1D524).
        Atom g = (Atom) MathParser.parse("\\mathfrak g");
        assertEquals(0x1D524, g.codePoint());
        // \boldsymbol{\beta} -> bold beta atom.
        Atom beta = (Atom) MathParser.parse("\\boldsymbol{\\beta}");
        assertEquals(0x1D6C3, beta.codePoint());
    }

    // ------------------------------------------------------------------
    // STIX coverage — every mapped code point resolves to a real glyph.
    // ------------------------------------------------------------------

    @Test
    void everyMappedLetterResolvesToARealStixGlyph() {
        SfntFont font = SfntFont.loadBundled();
        List<String> gaps = new ArrayList<>();
        for (Style style : Style.values()) {
            // Latin letters, always defined.
            for (char c = 'A'; c <= 'Z'; c++) {
                probe(font, gaps, style, c);
            }
            for (char c = 'a'; c <= 'z'; c++) {
                probe(font, gaps, style, c);
            }
            // Digits + Greek only where the style maps them (map() returns the
            // base otherwise, so an unmapped base ASCII digit is skipped here).
            for (char c = '0'; c <= '9'; c++) {
                int mapped = MathVariant.map(style, c);
                if (mapped != c) {
                    probeCp(font, gaps, style, c, mapped);
                }
            }
            for (int cp = 0x391; cp <= 0x3C9; cp++) {
                if (cp == 0x3A2 || (cp > 0x3A9 && cp < 0x3B1)) {
                    continue;
                }
                int mapped = MathVariant.map(style, cp);
                if (mapped != cp) {
                    probeCp(font, gaps, style, cp, mapped);
                }
            }
        }
        assertTrue(gaps.isEmpty(),
            "STIX Two Math has no glyph for these mapped variant code points: " + gaps
            + " — add them to a fallback set (NEVER emit a <text> fallback).");
    }

    private static void probe(SfntFont font, List<String> gaps, Style style, int baseCp) {
        probeCp(font, gaps, style, baseCp, MathVariant.map(style, baseCp));
    }

    private static void probeCp(SfntFont font, List<String> gaps, Style style,
                                int baseCp, int mappedCp) {
        if (font.glyphId(mappedCp) == 0) {
            gaps.add("%s of U+%04X -> U+%04X".formatted(style, baseCp, mappedCp));
        }
    }

    // ------------------------------------------------------------------
    // Render sanity + containment.
    // ------------------------------------------------------------------

    private static void assertRendersGlyphs(String latex) {
        String svg = LatteX.render(latex);
        assertTrue(svg.contains("<path"), "renders at least one glyph path: " + latex);
        assertTrue(svg.contains("viewBox="), "has a viewBox: " + latex);
    }

    @Test
    void eachVariantRenders() {
        assertRendersGlyphs("\\mathbb{ABCNRZ}");
        assertRendersGlyphs("\\mathcal{L}");
        assertRendersGlyphs("\\mathscr{F}");
        assertRendersGlyphs("\\mathfrak{g}");
        assertRendersGlyphs("\\mathbf{x} + \\mathbf{123}");
        assertRendersGlyphs("\\mathsf{XYZ}");
        assertRendersGlyphs("\\mathit{f}");
        assertRendersGlyphs("\\mathtt{code}");
        assertRendersGlyphs("\\boldsymbol{\\beta}");
        assertRendersGlyphs("\\bm{\\alpha}");
    }

    @Test
    void variantExpressionStaysInMinimalAlphabet() {
        String svg = LatteX.render(
            "\\mathbb{R}^n \\to \\mathcal{L}(\\mathfrak{g}), \\quad "
            + "\\boldsymbol{\\beta} = \\mathbf{A}\\mathsf{x} + \\mathit{c}");
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
