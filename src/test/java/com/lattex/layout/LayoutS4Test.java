package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S4 layout geometry tests — asserts that fractions, roots, scripts, and rows
 * produce sane box geometry (rule at the math axis; sub below / sup above the
 * baseline; radicand under the bar; inter-atom spacing on the row) and that every
 * new construct still emits ONLY the sanitizer-safe SVG alphabet (rules included).
 */
class LayoutS4Test {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        MathNode node = MathParser.parse(latex);
        return LayoutEngine.layout(node, ctx);
    }

    // ---- Fraction --------------------------------------------------------

    @Test
    void fractionRuleSitsBetweenNumeratorAndDenominator() {
        Layout l = layout("\\frac{a}{b}");
        assertEquals(1, l.rules().size(), "one fraction bar");
        Rule bar = l.rules().get(0);
        assertTrue(bar.width() > 0, "bar has width");
        assertTrue(bar.height() > 0, "bar has thickness");
        // Bar centred near the math axis (above the baseline → negative y).
        double axis = FONT.mathConstants().axisHeight() * (40.0 / FONT.unitsPerEm());
        assertTrue(bar.y() < 0, "bar is above the baseline");
        assertTrue(Math.abs((bar.y() + bar.height() / 2.0) + axis) < 1.0,
            "bar centre is on the math axis");

        assertEquals(2, l.glyphs().size(), "numerator 'a' and denominator 'b'");
        PositionedGlyph num = l.glyphs().get(0);
        PositionedGlyph den = l.glyphs().get(1);
        assertTrue(num.baselineY() < bar.y(), "numerator baseline is above the bar");
        assertTrue(den.baselineY() > bar.y() + bar.height(), "denominator baseline is below the bar");
    }

    @Test
    void nestedFractionRenders() {
        // This parser requires braced \frac args (no \frac1x shorthand), so the
        // brief's nested example is written in fully-braced form.
        String nested = "\\frac{\\frac{1}{x}+\\frac{1}{y}}{y-z}";
        Layout l = layout(nested);
        // outer bar + two inner bars.
        assertEquals(3, l.rules().size(), "outer + two inner fraction bars");
        assertTrue(l.width() > 0 && l.height() > 0, "non-degenerate bbox");
        assertAlphabet(LatteX.render(nested));
    }

    // ---- Scripts ---------------------------------------------------------

    @Test
    void subAndSupStraddleTheBaseline() {
        Layout l = layout("x_i^2");
        assertEquals(3, l.glyphs().size(), "base x, sub i, sup 2");
        double baseScale = 40.0 / FONT.unitsPerEm();

        PositionedGlyph base = l.glyphs().get(0);
        assertEquals(0.0, base.baselineY(), 1e-9, "base sits on the baseline");
        assertEquals(baseScale, base.scale(), 1e-9, "base at full scale");

        boolean sawSup = false;
        boolean sawSub = false;
        for (PositionedGlyph g : l.glyphs()) {
            if (g == base) {
                continue;
            }
            assertTrue(g.scale() < baseScale, "script is shrunk");
            if (g.baselineY() < 0) {
                sawSup = true; // above the baseline
            }
            if (g.baselineY() > 0) {
                sawSub = true; // below the baseline
            }
        }
        assertTrue(sawSup, "superscript raised above the baseline");
        assertTrue(sawSub, "subscript dropped below the baseline");
    }

    @Test
    void subscriptOnlyRenders() {
        Layout l = layout("a_i");
        assertEquals(2, l.glyphs().size());
        // The subscript is the one below the baseline.
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() > 0), "subscript below baseline");
    }

    // ---- Radical ---------------------------------------------------------

    @Test
    void sqrtHasBarAndSurdOverTheRadicand() {
        Layout l = layout("\\sqrt{x^2+1}");
        assertEquals(1, l.rules().size(), "one over-bar");
        Rule bar = l.rules().get(0);
        assertTrue(bar.y() < 0, "bar is above the baseline");

        int surdGid = FONT.glyphId(0x221A);
        assertTrue(l.glyphs().stream().anyMatch(g -> g.glyphId() == surdGid), "surd glyph present");
        // The bar must clear the tallest radicand ink (the '2' superscript sits
        // highest); i.e. bar top is above every radicand glyph baseline.
        for (PositionedGlyph g : l.glyphs()) {
            if (g.glyphId() == surdGid) {
                continue;
            }
            assertTrue(g.baselineY() > bar.y(), "radicand glyph sits below the bar top");
        }
        assertAlphabet(LatteX.render("\\sqrt{x^2+1}"));
    }

    @Test
    void sqrtWithIndexRenders() {
        Layout l = layout("\\sqrt[n]{x}");
        assertEquals(1, l.rules().size());
        // radicand x, surd, and the degree n → at least 3 glyphs.
        assertTrue(l.glyphs().size() >= 3, "surd + radicand + degree");
        assertAlphabet(LatteX.render("\\sqrt[n]{x}"));
    }

    // ---- MathList spacing ------------------------------------------------

    private static Layout row(MathNode.Atom... atoms) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(new MathNode.MathList(List.of(atoms)), ctx);
    }

    @Test
    void binaryOperatorAddsSpaceBeyondGlyphAdvances() {
        // Same glyphs, differing only in the middle atom's class: Bin gets medium
        // space on both sides, Ord gets none.
        Layout spaced = row(
            MathNode.Atom.ord('a'),
            new MathNode.Atom('+', MathNode.MathClass.BIN),
            MathNode.Atom.ord('b'));
        Layout tight = row(
            MathNode.Atom.ord('a'),
            new MathNode.Atom('+', MathNode.MathClass.ORD),
            MathNode.Atom.ord('b'));
        assertTrue(spaced.width() > tight.width() + 1.0,
            "binary-operator spacing widens the row vs. the Ord-only row");
    }

    @Test
    void leadingBinaryOperatorReclassifiesToOrd() {
        // A leading Bin has no left operand → TeX demotes it to Ord, so it gets
        // no surrounding space: identical to an explicitly-Ord leading atom.
        Layout demoted = row(
            new MathNode.Atom('-', MathNode.MathClass.BIN),
            MathNode.Atom.ord('x'));
        Layout ord = row(
            new MathNode.Atom('-', MathNode.MathClass.ORD),
            MathNode.Atom.ord('x'));
        assertEquals(ord.width(), demoted.width(), 1e-9,
            "leading Bin is demoted to Ord (no leading space)");
        // But a genuine binary '-' (with a left operand) is wider.
        Layout binary = row(
            MathNode.Atom.ord('z'),
            new MathNode.Atom('-', MathNode.MathClass.BIN),
            MathNode.Atom.ord('x'));
        Layout binaryOrd = row(
            MathNode.Atom.ord('z'),
            new MathNode.Atom('-', MathNode.MathClass.ORD),
            MathNode.Atom.ord('x'));
        assertTrue(binary.width() > binaryOrd.width() + 1.0, "genuine binary '-' keeps its space");
    }

    @Test
    void explicitThinSpaceWidensRow() {
        Layout with = layout("a\\,b");
        Layout without = layout("ab");
        assertTrue(with.width() > without.width(), "\\, adds width");
    }

    // ---- alphabet guard --------------------------------------------------

    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final Set<String> ALLOWED_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label",
        "transform", "fill", "d", "stroke", "stroke-width", "x", "y");
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "use", "defs", "symbol", "text", "foreignObject", "script", "style",
        "animate", "image", "a");

    private static void assertAlphabet(String svg) {
        for (String forbidden : FORBIDDEN_ELEMENTS) {
            assertFalse(svg.contains("<" + forbidden), "must not emit <" + forbidden + ">");
        }
        assertFalse(svg.contains("href"), "no href");
        assertFalse(svg.contains("data:"), "no data: URI");
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(svg);
        while (tag.find()) {
            String element = tag.group(1);
            assertTrue(ALLOWED_ELEMENTS.contains(element), "element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=").matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(ALLOWED_ATTRS.contains(attr.group(1)),
                    "attribute out of alphabet on <" + element + ">: " + attr.group(1));
            }
        }
    }
}
