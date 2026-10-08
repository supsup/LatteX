package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 720cd87e, item 4: primes.
 *
 * <p>THE CONSTRUCTION (TeXbook ch. 16 and Appendix B): a math-mode {@code '} is
 * {@code ^\prime}; consecutive primes join ONE superscript, and a following {@code ^}
 * joins that same superscript. LatteX already built exactly that, so those cases are
 * pinned here as identities against the spelled-out {@code ^{\prime...}} form.
 *
 * <p>THE GLYPH: in an OpenType math font the cmap prime U+2032 is a TEXT prime, drawn
 * already raised (STIX Two Math: ink from 0.399 to 0.703 em). A TeX engine running the
 * same font (XeTeX/LuaTeX with unicode-math) sets the script font with the {@code ssty}
 * feature, which swaps in the prime designed for superscript use ({@code minute.ssty}:
 * ink 0.085 to 0.527 em, larger), playing the part of Computer Modern's {@code \prime}.
 * LatteX superscripted the raised text prime, so {@code u'} drew a small prime floating
 * well above the u. The fix takes the font's ssty form for primes set in a script style.
 */
class PrimePlacementTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double EPS = 1e-9;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    private static List<PositionedGlyph> atoms(Layout l, int cp) {
        return l.glyphs().stream().filter(g -> g.sourceCodePoint() == cp)
            .sorted(Comparator.comparingDouble(PositionedGlyph::originX)).toList();
    }

    private static double inkTop(PositionedGlyph g) {
        return g.baselineY() - g.scale() * FONT.outline(g.glyphId()).yMax();
    }

    private static double inkBottom(PositionedGlyph g) {
        return g.baselineY() - g.scale() * FONT.outline(g.glyphId()).yMin();
    }

    private static double inkLeft(PositionedGlyph g) {
        return g.originX() + g.scale() * FONT.outline(g.glyphId()).xMin();
    }

    private static double inkRight(PositionedGlyph g) {
        return g.originX() + g.scale() * FONT.outline(g.glyphId()).xMax();
    }

    @Test
    void primesAreTeXsSuperscriptConstruction() {
        // Identity of the whole layout, glyph for glyph, against the spelled-out form.
        String[][] pairs = {
            {"f'", "f^{\\prime}"},
            {"u''", "u^{\\prime\\prime}"},
            {"f'''", "f^{\\prime\\prime\\prime}"},
            {"x'^2", "x^{\\prime 2}"},
            {"f''_n", "f^{\\prime\\prime}_n"},
            {"f_n''", "f^{\\prime\\prime}_n"},
            {"f'(x)", "f^{\\prime}(x)"},
        };
        for (String[] p : pairs) {
            Layout a = layout(p[0]);
            Layout b = layout(p[1]);
            assertEquals(b.glyphs(), a.glyphs(), p[0] + " must draw exactly " + p[1]);
        }
    }

    @Test
    void aSuperscriptPrimeSitsDownAgainstItsNucleus() {
        // TeX's \prime is drawn tall from near its baseline, so once raised as a
        // superscript its foot sits inside the x-height band of the nucleus. The raised
        // text prime put its foot above the u entirely.
        Layout l = layout("u'");
        PositionedGlyph u = atoms(l, 'u').get(0);
        PositionedGlyph prime = atoms(l, 0x2032).get(0);
        assertTrue(inkBottom(prime) > inkTop(u),
            "the prime's foot must reach below the top of the u (y-down: bottom "
                + inkBottom(prime) + " vs u top " + inkTop(u) + ")");
        // and its ink is taller than a quarter em at 40px
        assertTrue(inkBottom(prime) - inkTop(prime) > 10.0,
            "prime ink height " + (inkBottom(prime) - inkTop(prime)));
    }

    @Test
    void consecutivePrimesDoNotCollide() {
        Layout l = layout("u''");
        List<PositionedGlyph> ps = atoms(l, 0x2032);
        assertEquals(2, ps.size());
        assertTrue(inkRight(ps.get(0)) <= inkLeft(ps.get(1)),
            "the two primes' ink must not overlap");
    }

    @Test
    void onlyScriptStylePrimesTakeTheSstyForm() {
        // Confluence's F1 (lattex/1022): a mutant applying ssty in EVERY style was caught only
        // by the symbol-index golden. Pin it directly: a prime set at text or display size
        // keeps the cmap text prime; script and scriptscript take ssty levels 1 and 2.
        int base = FONT.glyphId(0x2032);
        int s1 = FONT.scriptStyleAlternate(base, 1);
        int s2 = FONT.scriptStyleAlternate(base, 2);
        assertEquals(base, atoms(layout("\\prime"), 0x2032).get(0).glyphId(), "text style keeps the text prime");
        assertEquals(base, atoms(layout("\\displaystyle\\prime"), 0x2032).get(0).glyphId(),
            "display style keeps the text prime");
        assertEquals(s1, atoms(layout("x^\\prime"), 0x2032).get(0).glyphId(), "script style takes ssty 1");
        assertEquals(s2, atoms(layout("x^{y^\\prime}"), 0x2032).get(0).glyphId(), "scriptscript takes ssty 2");
    }
}
