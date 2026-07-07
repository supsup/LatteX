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

    @Test
    void binomUsesWiderStackGapThanFraction() {
        // \binom (rule-less stack) must use the OpenType stack* spacing, whose
        // gap is ~3x the fraction gap — so its numerator/denominator are pushed
        // farther apart than a same-content \frac. \frac geometry is unchanged
        // (guarded by fractionRuleSitsBetweenNumeratorAndDenominator above).
        int nGid = FONT.glyphId('n');
        int kGid = FONT.glyphId('k');

        double fracSep = numDenSeparation(layout("\\frac{n}{k}"), nGid, kGid);
        double binomSep = numDenSeparation(layout("\\binom{n}{k}"), nGid, kGid);

        assertTrue(binomSep > fracSep,
            "binom stack gap (" + binomSep + ") wider than fraction gap (" + fracSep + ")");
    }

    /** Vertical distance between the numerator ('n') and denominator ('k') baselines. */
    private static double numDenSeparation(Layout l, int numGid, int denGid) {
        Double num = null;
        Double den = null;
        for (PositionedGlyph g : l.glyphs()) {
            if (g.glyphId() == numGid) {
                num = g.baselineY();
            } else if (g.glyphId() == denGid) {
                den = g.baselineY();
            }
        }
        assertTrue(num != null && den != null, "found numerator and denominator glyphs");
        return den - num;
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

    @Test
    void surdUsesProportionalVariantNotUniformStretch() {
        // The surd must be a pre-designed MATH vertical variant drawn at the normal
        // glyph scale — NOT the base U+221A uniformly scaled up (which thickens the
        // strokes and, for short radicands, dangles an oversized tail). For a
        // content this size STIX's base radical variant already fits, so the surd
        // is drawn at exactly the base scale.
        Layout l = layout("\\sqrt{x^2+1}");
        double baseScale = 40.0 / FONT.unitsPerEm();
        int surdGid = FONT.glyphId(0x221A);
        PositionedGlyph surd = l.glyphs().stream()
            .filter(g -> g.glyphId() == surdGid)
            .findFirst().orElseThrow(() -> new AssertionError("surd glyph present"));
        assertEquals(baseScale, surd.scale(), 1e-9,
            "surd is a proportional variant at the base scale, not a stretched glyph");
    }

    @Test
    void surdExcessIsSplitNotAllDangledBelow() {
        // Appendix-G Rule 11: when the smallest adequate surd is taller than the
        // content needs, the surplus is split — half raises the bar, half extends
        // the tail — so the surd does not dangle its whole surplus below the
        // radicand. A lone 'x' has no depth, yet the surd descends below the
        // baseline by only about half its total surplus, not the whole of it.
        Layout l = layout("\\sqrt{x}");
        double baseScale = 40.0 / FONT.unitsPerEm();
        int surdGid = FONT.glyphId(0x221A);
        PositionedGlyph surd = l.glyphs().stream()
            .filter(g -> g.glyphId() == surdGid)
            .findFirst().orElseThrow(() -> new AssertionError("surd glyph present"));
        var o = FONT.outline(surdGid);
        double surdSpan = (o.yMax() - o.yMin()) * baseScale;
        double surdTop = surd.baselineY() - baseScale * o.yMax();     // ink top (bar level)
        double surdBottom = surd.baselineY() - baseScale * o.yMin();  // ink bottom (below baseline)
        // Bar sits above the baseline; tail below it. With the excess split, the
        // tail descends less than the full surd span (it would equal the span were
        // the surd top-aligned with everything dangling below).
        assertTrue(surdTop < 0, "bar/surd top is above the baseline");
        assertTrue(surdBottom > 0, "surd tail descends below the baseline");
        assertTrue(surdBottom < surdSpan,
            "the surd surplus is split, not all dangled below the radicand");
    }

    @Test
    void radicalDegreeSitsInTheUpperLeftCrook() {
        Layout l = layout("\\sqrt[n]{x+y}");
        double baseScale = 40.0 / FONT.unitsPerEm();
        int surdGid = FONT.glyphId(0x221A);
        PositionedGlyph surd = l.glyphs().stream()
            .filter(g -> g.glyphId() == surdGid)
            .findFirst().orElseThrow(() -> new AssertionError("surd glyph present"));
        // The degree is the shrunk (script-script) glyph.
        PositionedGlyph deg = l.glyphs().stream()
            .filter(g -> g.scale() < baseScale)
            .findFirst().orElseThrow(() -> new AssertionError("degree glyph present"));
        var o = FONT.outline(surdGid);
        double surdTop = surd.baselineY() - baseScale * o.yMax();
        double surdBottom = surd.baselineY() - baseScale * o.yMin();
        double surdMid = (surdTop + surdBottom) / 2.0;
        // Upper: the degree baseline is above the surd's vertical midpoint.
        assertTrue(deg.baselineY() < surdMid,
            "degree nestles in the UPPER part of the surd, not low");
        // Left: the degree sits over the surd, left of the radicand body.
        double surdWidth = FONT.advanceWidth(surdGid) * baseScale;
        assertTrue(deg.originX() < surd.originX() + surdWidth,
            "degree sits to the left of the radicand, over the surd");
    }

    // ---- BigOperator limits ----------------------------------------------

    @Test
    void sumStacksLimitsAboveAndBelowInDisplayStyle() {
        Layout l = layout("\\sum_{i=1}^{10} t_i");
        double baseScale = 40.0 / FONT.unitsPerEm();
        // The operator is drawn at full (display) scale; limits are shrunk.
        PositionedGlyph op = l.glyphs().stream()
            .filter(g -> g.scale() == baseScale && g.originX() < 5.0)
            .findFirst().orElseThrow(() -> new AssertionError("operator glyph present"));
        // The display sigma is a larger MATH vertical variant, not the base glyph.
        assertTrue(op.glyphId() != FONT.glyphId(0x2211), "display style enlarges the operator");
        // A shrunk limit sits above the baseline (upper) and another below (lower),
        // both roughly centred over the operator's horizontal span.
        boolean above = l.glyphs().stream().anyMatch(g -> g.scale() < baseScale && g.baselineY() < -20.0);
        boolean below = l.glyphs().stream().anyMatch(g -> g.scale() < baseScale && g.baselineY() > 20.0);
        assertTrue(above, "upper limit stacked above the operator");
        assertTrue(below, "lower limit stacked below the operator");
        // Alphabet guard on the operator SVG path. Uses a '='-free variant because
        // the assertAlphabet heuristic scans attribute *values* too, and an
        // aria-label like "...i = 1..." would false-match '=' as an attribute.
        assertAlphabet(LatteX.render("\\sum_{i}^{n} k"));
    }

    @Test
    void integralKeepsSideLimitsInDisplayStyle() {
        // An integral conventionally takes SIDE limits even in display style: the
        // upper limit sits up-and-to-the-right, the lower down-and-to-the-right —
        // i.e. both scripts are to the right of the integral sign's left edge, not
        // centred over it (which is how \sum stacks).
        Layout l = layout("\\int_0^\\infty");
        double baseScale = 40.0 / FONT.unitsPerEm();
        PositionedGlyph intSign = l.glyphs().stream()
            .filter(g -> g.scale() == baseScale)
            .min((a, b) -> Double.compare(a.originX(), b.originX()))
            .orElseThrow();
        assertTrue(intSign.glyphId() != FONT.glyphId(0x222B), "display style enlarges the integral");
        // The shrunk limits are offset to the right of the sign's left edge.
        List<PositionedGlyph> limits = l.glyphs().stream()
            .filter(g -> g.scale() < baseScale).toList();
        assertEquals(2, limits.size(), "an upper and a lower limit");
        for (PositionedGlyph lim : limits) {
            assertTrue(lim.originX() > intSign.originX(),
                "integral limit is set beside (right of) the sign, not stacked over it");
        }
    }

    @Test
    void nolimitsForcesSideScriptsOnSum() {
        // \nolimits overrides the display default: the sum's limits go beside.
        Layout stacked = layout("\\sum_{i=1}^{10} t_i");
        Layout beside = layout("\\sum\\nolimits_{i=1}^{10} t_i");
        // Side scripts are narrower vertically (nothing stacked above/below) so the
        // overall bbox is shorter than the stacked form.
        assertTrue((beside.maxY() - beside.minY()) < (stacked.maxY() - stacked.minY()),
            "\\nolimits sum is vertically shorter than the stacked form");
    }

    // ---- Fenced delimiters -----------------------------------------------

    @Test
    void delimitersStretchToSpanTheBody() {
        Layout l = layout("\\left(\\frac{x^2}{y^3}\\right)");
        double baseScale = 40.0 / FONT.unitsPerEm();
        // The two delimiters are the leftmost and rightmost glyphs.
        PositionedGlyph left = l.glyphs().stream()
            .min((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        PositionedGlyph right = l.glyphs().stream()
            .max((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        // A stretched delimiter is a taller MATH vertical variant, not the base
        // '(' / ')' glyph drawn at text size.
        assertTrue(left.glyphId() != FONT.glyphId('('), "left paren is a stretched variant");
        assertTrue(right.glyphId() != FONT.glyphId(')'), "right paren is a stretched variant");
        // Delimiters are centred on the math axis: their ink straddles the baseline
        // both above (negative y) and below (positive y), spanning the tall fraction.
        assertTrue(-l.minY() > 30.0, "delimiter ink rises above the baseline");
        assertTrue(l.maxY() > 25.0, "delimiter ink descends below the baseline");
        assertAlphabet(LatteX.render("\\left(\\frac{x^2}{y^3}\\right)"));
    }

    @Test
    void nullDelimiterRendersNothingButBalances() {
        // \left. renders no glyph; \right| renders a bar. Body 'x' plus one bar.
        Layout l = layout("\\left. x \\right|");
        assertEquals(2, l.glyphs().size(), "body glyph + one delimiter (null left renders nothing)");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.glyphId() == FONT.glyphId('|')),
            "the right bar is present");
        assertAlphabet(LatteX.render("\\left. x \\right|"));
    }

    @Test
    void braceDelimitersRender() {
        // A fraction body stretches the braces to a taller MATH vertical variant,
        // so the delimiter glyph ids are brace *variants*, not the base '{' / '}'.
        String latex = "\\left\\{ \\frac{a}{b} \\right\\}";
        Layout l = layout(latex);
        PositionedGlyph left = l.glyphs().stream()
            .min((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        PositionedGlyph right = l.glyphs().stream()
            .max((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        assertTrue(variantGids('{').contains(left.glyphId()), "left delimiter is a brace");
        assertTrue(variantGids('}').contains(right.glyphId()), "right delimiter is a brace");
        assertAlphabet(LatteX.render(latex));
    }

    /** The glyph ids for a stretchy delimiter: its base glyph plus every vertical variant. */
    private static Set<Integer> variantGids(int codePoint) {
        Set<Integer> gids = new java.util.HashSet<>();
        gids.add(FONT.glyphId(codePoint));
        var construction = FONT.verticalVariants(FONT.glyphId(codePoint));
        if (construction != null) {
            construction.variants().forEach(v -> gids.add(v.glyphId()));
        }
        return gids;
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

    @Test
    void spacingCommandAdvancesMatchMuWidths() {
        // The gap between the two glyphs is exactly its mu-width. Since 18mu = 1em
        // and mu = fontSize/18 (unitsPerEm cancels), the row-width difference
        // between two spaces equals (Δmu)·(fontSize/18) user units.
        double mu = 40.0 / 18.0;
        double thin = layout("a\\,b").width();
        double thick = layout("a\\;b").width();
        double quad = layout("a\\quad b").width();
        double qquad = layout("a\\qquad b").width();

        // \; (5mu) is 2mu wider than \, (3mu).
        assertEquals(2.0 * mu, thick - thin, 1e-6, "\\; is 2mu wider than \\,");
        // \quad (18mu) is 15mu wider than \, (3mu).
        assertEquals(15.0 * mu, quad - thin, 1e-6, "\\quad is 15mu wider than \\,");
        // \qquad (36mu) is exactly 18mu wider than \quad (18mu).
        assertEquals(18.0 * mu, qquad - quad, 1e-6, "\\qquad is 18mu wider than \\quad");
    }

    @Test
    void negativeThinSpaceNarrowsRow() {
        // \! is -3mu: it pulls the glyphs closer than no space at all.
        Layout negative = layout("a\\!b");
        Layout plain = layout("ab");
        assertTrue(negative.width() < plain.width(), "\\! narrows the row");
        assertEquals(-3.0 * (40.0 / 18.0), negative.width() - plain.width(), 1e-6,
            "\\! removes 3mu of width");
    }

    @Test
    void phantomOccupiesSpaceButEmitsNoInk() {
        // A bare phantom draws nothing at all.
        Layout ph = layout("\\phantom{M}");
        assertTrue(ph.glyphs().isEmpty(), "phantom emits no glyphs");
        assertTrue(ph.rules().isEmpty(), "phantom emits no rules");

        // ...but it reserves the full width of its content.
        Layout withPhantom = layout("a\\phantom{M}b");
        Layout tight = layout("ab");
        Layout real = layout("aMb");
        assertTrue(withPhantom.width() > tight.width(), "\\phantom reserves width");
        assertEquals(real.width(), withPhantom.width(), 0.5,
            "\\phantom{M} reserves the same advance as a real M");
        // The phantom row draws only 'a' and 'b' — the M leaves no ink.
        assertEquals(2, withPhantom.glyphs().size(), "only 'a' and 'b' are inked");
    }

    @Test
    void hphantomKeepsWidthVphantomKeepsNone() {
        Layout tight = layout("ab");
        // \hphantom keeps width -> wider than "ab".
        assertTrue(layout("a\\hphantom{M}b").width() > tight.width(),
            "\\hphantom reserves width");
        // \vphantom keeps zero width -> same advance as "ab".
        assertEquals(tight.width(), layout("a\\vphantom{M}b").width(), 1e-6,
            "\\vphantom reserves no width");
    }

    @Test
    void spacingAndPhantomsStayInAlphabet() {
        assertAlphabet(LatteX.render("a\\quad b\\;c\\,d\\!e"));
        assertAlphabet(LatteX.render("\\phantom{M}x\\hphantom{y}\\vphantom{Z}\\mathstrut"));
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
