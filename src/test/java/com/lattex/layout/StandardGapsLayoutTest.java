package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 636d214f, geometry half: the spanning and overlaid forms the plan adds are
 * asserted on their POSITIONS, not merely on rendering without a throw. A
 * {@code \multicolumn} that rendered every cell in column one, a {@code \genfrac}
 * whose brackets did not reach the stack, or a row {@code \tag} drawn on the wrong
 * row would all "render". Each test also carries the control that makes it
 * discriminate.
 */
class StandardGapsLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double EPS = 0.01;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    /** The glyphs drawn for author atoms with this source code point, left to right. */
    private static List<PositionedGlyph> atoms(Layout l, int cp) {
        return l.glyphs().stream().filter(g -> g.sourceCodePoint() == cp)
            .sorted(Comparator.comparingDouble(PositionedGlyph::originX)).toList();
    }

    private static PositionedGlyph atom(Layout l, int cp) {
        List<PositionedGlyph> found = atoms(l, cp);
        assertEquals(1, found.size(), "exactly one '" + Character.toString(cp) + "' atom");
        return found.get(0);
    }

    /**
     * The one glyph of a tag label's character. A label is TEXT (amsmath; plan 720cd87e),
     * and a text run's glyphs carry no source code point, so it is found by glyph.
     */
    private static PositionedGlyph label(Layout l, int cp) {
        List<PositionedGlyph> found = l.glyphs().stream()
            .filter(g -> g.sourceCodePoint() == PositionedGlyph.NO_SOURCE
                && g.glyphId() == FONT.glyphId(cp)).toList();
        assertEquals(1, found.size(), "exactly one label '" + Character.toString(cp) + "'");
        return found.get(0);
    }

    private static double advance(PositionedGlyph g) {
        return FONT.advanceWidth(g.glyphId()) * g.scale();
    }

    private static double centreX(PositionedGlyph g) {
        return g.originX() + advance(g) / 2.0;
    }

    private static double inkTop(PositionedGlyph g) {
        return g.baselineY() - g.scale() * FONT.outline(g.glyphId()).yMax();
    }

    private static double inkBottom(PositionedGlyph g) {
        return g.baselineY() - g.scale() * FONT.outline(g.glyphId()).yMin();
    }

    // ------------------------------------------------------------------
    // \multicolumn
    // ------------------------------------------------------------------

    @Test
    void aCentredSpanIsCentredOverTheColumnsItSpans() {
        // Two equal columns below; the span's one glyph sits on the midpoint of the
        // two cells it covers, not over the first column alone.
        Layout l = layout("\\begin{array}{ccc}u&\\multicolumn{2}{c}{m}\\\\x&a&a\\end{array}");
        List<PositionedGlyph> as = atoms(l, 'a');
        assertEquals(2, as.size());
        double spanMid = (centreX(as.get(0)) + centreX(as.get(1))) / 2.0;
        PositionedGlyph m = atom(l, 'm');
        assertEquals(spanMid, centreX(m), 0.05, "m centred over columns 2-3");
        // Control: the span is clearly NOT in column 2 alone.
        assertTrue(centreX(m) > centreX(as.get(0)) + 5.0, "m is right of column 2's centre");
    }

    @Test
    void spanAlignmentLeftAndRightFollowItsOwnSpec() {
        Layout left = layout("\\begin{array}{cc}\\multicolumn{2}{l}{m}\\\\aaaa&aaaa\\end{array}");
        Layout right = layout("\\begin{array}{cc}\\multicolumn{2}{r}{m}\\\\aaaa&aaaa\\end{array}");
        List<PositionedGlyph> la = atoms(left, 'a');
        List<PositionedGlyph> ra = atoms(right, 'a');
        assertEquals(la.get(0).originX(), atom(left, 'm').originX(), EPS,
            "an l span starts where column 1 starts");
        PositionedGlyph lastA = ra.get(ra.size() - 1);
        PositionedGlyph m = atom(right, 'm');
        assertEquals(lastA.originX() + advance(lastA), m.originX() + advance(m), EPS,
            "an r span ends where the last spanned column ends");
    }

    @Test
    void aWideSpanWidensTheLastSpannedColumnOnly() {
        // TeX puts a span's excess width into the LAST column of the span. Column 1's
        // cell does not move; column 2's centred cell moves right.
        String narrow = "\\begin{array}{cc}\\multicolumn{2}{c}{m}\\\\a&b\\end{array}";
        String wide = "\\begin{array}{cc}\\multicolumn{2}{c}{mmmmmmmmmmmm}\\\\a&b\\end{array}";
        Layout n = layout(narrow);
        Layout w = layout(wide);
        assertEquals(atom(n, 'a').originX(), atom(w, 'a').originX(), EPS, "column 1 unchanged");
        assertTrue(atom(w, 'b').originX() > atom(n, 'b').originX() + 20.0, "column 2 widened");
        // ... and the span's ink lies inside the grid it spans.
        List<PositionedGlyph> ms = atoms(w, 'm');
        assertTrue(ms.get(0).originX() >= atom(w, 'a').originX() - 1.0, "span starts in column 1");
    }

    /** Vertical rules (thin, taller than wide) whose x lies in [x0, x1]. */
    private static List<Rule> vRulesBetween(Layout l, double x0, double x1) {
        return l.rules().stream().filter(r -> r.height() > r.width() && r.x() > x0 && r.x() < x1)
            .toList();
    }

    private static boolean covers(Rule r, double y) {
        return r.y() <= y && r.y() + r.height() >= y;
    }

    @Test
    void ruleBetweenSpannedColumnsStopsAtTheSpanRow() {
        // {c|c|c}: the rule between columns 2 and 3 is INSIDE the span on row 1, so it
        // must not cross row 1; it is drawn on row 2. The rule between columns 1 and 2
        // is outside the span and crosses both rows.
        Layout l = layout("\\begin{array}{c|c|c}u&\\multicolumn{2}{c}{m}\\\\x&a&b\\end{array}");
        PositionedGlyph u = atom(l, 'u');
        PositionedGlyph x = atom(l, 'x');
        PositionedGlyph a = atom(l, 'a');
        PositionedGlyph b = atom(l, 'b');
        double row1 = u.baselineY();
        double row2 = x.baselineY();
        List<Rule> inner = vRulesBetween(l, a.originX() + advance(a), b.originX());
        assertFalse(inner.isEmpty(), "the rule between a and b is drawn");
        assertTrue(inner.stream().anyMatch(r -> covers(r, row2)), "it crosses row 2");
        assertFalse(inner.stream().anyMatch(r -> covers(r, row1)), "it does NOT cross the span row");
        List<Rule> outer = vRulesBetween(l, x.originX() + advance(x), a.originX());
        assertTrue(outer.stream().anyMatch(r -> covers(r, row1)) && outer.stream().anyMatch(r -> covers(r, row2)),
            "the rule left of the span crosses both rows");
        // Control: with no span, the same rule crosses row 1.
        Layout plain = layout("\\begin{array}{c|c|c}u&m&n\\\\x&a&b\\end{array}");
        PositionedGlyph pa = atom(plain, 'a');
        PositionedGlyph pb = atom(plain, 'b');
        assertTrue(vRulesBetween(plain, pa.originX() + advance(pa), pb.originX()).stream()
            .anyMatch(r -> covers(r, atom(plain, 'u').baselineY())), "control: unspanned rule crosses row 1");
    }

    @Test
    void aSpanDrawsItsOwnTrailingRule() {
        // {cc|c}: no rule after column 1 normally; \multicolumn{1}{c|} adds one on row 1 only.
        Layout l = layout("\\begin{array}{ccc}\\multicolumn{1}{c|}{u}&v&w\\\\x&a&b\\end{array}");
        PositionedGlyph u = atom(l, 'u');
        PositionedGlyph v = atom(l, 'v');
        List<Rule> between = vRulesBetween(l, u.originX() + advance(u), v.originX());
        assertEquals(1, between.size(), "one rule segment after the span");
        assertTrue(covers(between.get(0), u.baselineY()), "on the span's row");
        assertFalse(covers(between.get(0), atom(l, 'x').baselineY()), "not on the other row");
    }

    /** The distinct x positions of the vertical rules between x0 and x1 that cross y. */
    private static List<Double> ruleXsCrossing(Layout l, double x0, double x1, double y) {
        return vRulesBetween(l, x0, x1).stream().filter(r -> covers(r, y))
            .map(r -> Math.round(r.x() * 100.0) / 100.0).distinct().sorted().toList();
    }

    @Test
    void aMiddleSpanDrawsItsOwnLeadingRuleBesideTheSpecRule() {
        // Review lattex/1015 F3. {c|c} already rules boundary 1; \multicolumn{1}{|c}
        // replaces column 2's template with one that ALSO opens with a rule, so TeX
        // draws both: a doubled rule (which is why authors write {c|} there instead).
        Layout one = layout("\\begin{array}{c|c}a&\\multicolumn{1}{|c}{b}\\end{array}");
        PositionedGlyph a = atom(one, 'a');
        PositionedGlyph b = atom(one, 'b');
        List<Double> xs = ruleXsCrossing(one, a.originX() + advance(a), b.originX(), a.baselineY());
        assertEquals(2, xs.size(), "the spec rule AND the span's own leading rule: " + xs);
        // Two rows, the other row unspanned: the doubled rule is on the span's row only.
        Layout two = layout("\\begin{array}{c|c}a&\\multicolumn{1}{|c}{b}\\\\c&d\\end{array}");
        PositionedGlyph a2 = atom(two, 'a');
        PositionedGlyph b2 = atom(two, 'b');
        PositionedGlyph c2 = atom(two, 'c');
        double gap0 = Math.max(a2.originX() + advance(a2), c2.originX() + advance(c2));
        double gap1 = Math.min(b2.originX(), atom(two, 'd').originX());
        assertEquals(2, ruleXsCrossing(two, gap0, gap1, a2.baselineY()).size(), "span row: doubled");
        assertEquals(1, ruleXsCrossing(two, gap0, gap1, c2.baselineY()).size(), "other row: the spec's one");
        // Control: a span with no leading | leaves the spec's single rule.
        Layout plain = layout("\\begin{array}{c|c}a&\\multicolumn{1}{c}{b}\\end{array}");
        PositionedGlyph pa = atom(plain, 'a');
        assertEquals(1, ruleXsCrossing(plain, pa.originX() + advance(pa), atom(plain, 'b').originX(),
            pa.baselineY()).size(), "control: one rule");
    }

    @Test
    void anUnspannedGridIsByteIdenticalToBefore() {
        // The span machinery must not perturb a grid with no \multicolumn: the rules are
        // still one full-height rect per bar.
        Layout l = layout("\\begin{array}{c|c}a&b\\\\c&d\\end{array}");
        assertEquals(1, l.rules().size(), "one full-height vertical rule");
    }

    // ------------------------------------------------------------------
    // \genfrac
    // ------------------------------------------------------------------

    @Test
    void genfracBracketsEncloseTheStackAndZeroThicknessDrawsNoBar() {
        Layout l = layout("\\genfrac{[}{]}{0pt}{}{n}{t}");
        assertTrue(l.rules().isEmpty(), "0pt: no fraction bar");
        PositionedGlyph n = atom(l, 'n');
        PositionedGlyph t = atom(l, 't');
        PositionedGlyph left = l.glyphs().stream().min(Comparator.comparingDouble(PositionedGlyph::originX))
            .orElseThrow();
        PositionedGlyph right = l.glyphs().stream().max(Comparator.comparingDouble(PositionedGlyph::originX))
            .orElseThrow();
        assertNotEquals('n', left.sourceCodePoint());
        // TeX's delimiter rule (\delimiterfactor 901): the bracket covers at least 90.1%
        // of the stack, so it need not overhang every pixel of ink - but it must rise above
        // the numerator's baseline, sink below the denominator's, and cover ~90% of the ink.
        double stackInk = inkBottom(t) - inkTop(n);
        for (PositionedGlyph bracket : List.of(left, right)) {
            assertTrue(inkTop(bracket) < n.baselineY(), "bracket rises above the numerator's baseline");
            assertTrue(inkBottom(bracket) > t.baselineY(), "bracket sinks below the denominator's baseline");
            assertTrue(inkBottom(bracket) - inkTop(bracket) >= 0.9 * stackInk,
                "bracket spans ~the whole stack: " + (inkBottom(bracket) - inkTop(bracket)) + " vs " + stackInk);
        }
        // Control: the same stack with NO delimiters has its leftmost glyph in the stack.
        assertEquals('n', layout("\\genfrac{}{}{0pt}{}{n}{t}").glyphs().stream()
            .min(Comparator.comparingDouble(PositionedGlyph::originX)).orElseThrow().sourceCodePoint());
        assertTrue(left.originX() + advance(left) <= n.originX() + EPS, "left bracket left of the stack");
        assertTrue(right.originX() >= t.originX() + advance(t) - EPS, "right bracket right of the stack");
        assertTrue(n.baselineY() < t.baselineY(), "n stacked over t");
        // Control: an empty thickness draws the default bar.
        assertEquals(1, layout("\\genfrac{[}{]}{}{}{n}{t}").rules().size(), "default bar");
    }

    @Test
    void genfracSpellingsOfBinomRenderIdentically() {
        assertEquals(LatteX.render("\\binom{n}{k}"), LatteX.render("\\genfrac(){0pt}{}{n}{k}"));
        assertEquals(LatteX.render("\\dfrac{a}{b}"), LatteX.render("\\genfrac{}{}{}{0}{a}{b}"));
    }

    // ------------------------------------------------------------------
    // \fint and siblings: side limits like every integral
    // ------------------------------------------------------------------

    @Test
    void esintIntegralsKeepSideLimitsInDisplayStyle() {
        for (String op : List.of("fint", "sqint", "int")) {
            Layout l = layout("\\" + op + "_{B}");
            PositionedGlyph b = atom(l, 'B');
            PositionedGlyph opGlyph = l.glyphs().stream().filter(g -> g.sourceCodePoint() != 'B')
                .findFirst().orElseThrow();
            assertTrue(b.originX() > opGlyph.originX() + 0.5 * advance(opGlyph),
                "\\" + op + ": the limit sits BESIDE the operator");
        }
        // Control: \sum stacks its limit under the operator in display style.
        Layout sum = layout("\\sum_{B}");
        PositionedGlyph b = atom(sum, 'B');
        PositionedGlyph op = sum.glyphs().stream().filter(g -> g.sourceCodePoint() != 'B')
            .findFirst().orElseThrow();
        assertTrue(b.originX() < op.originX() + 0.5 * advance(op), "control: \\sum stacks");
    }

    @Test
    void fintDrawsTheIntegralAverageGlyph() {
        Layout l = layout("\\fint");
        assertEquals(1, l.glyphs().size());
        int gid = l.glyphs().get(0).glyphId();
        int base = FONT.glyphId(0x2A0F);
        assertTrue(base != 0, "STIX Two Math carries U+2A0F");
        // Display style may pick the larger variant of the same glyph; either is the
        // integral-average sign, never the plain integral.
        assertNotEquals(FONT.glyphId(0x222B), gid);
        boolean fromConstruction = gid == base || FONT.verticalVariants(base).variants().stream()
            .anyMatch(v -> v.glyphId() == gid);
        assertTrue(fromConstruction, "drawn from U+2A0F's own construction, gid " + gid);
    }

    // ------------------------------------------------------------------
    // Row tags in align / gather
    // ------------------------------------------------------------------

    @Test
    void eachRowTagSitsOnItsOwnRowRightOfTheGrid() {
        Layout l = layout("\\begin{align}a&=b\\tag{4}\\\\c&=dd\\tag{5}\\end{align}");
        PositionedGlyph four = label(l, '4');
        PositionedGlyph five = label(l, '5');
        assertEquals(atom(l, 'a').baselineY(), four.baselineY(), EPS, "(4) on row 1's baseline");
        assertEquals(atom(l, 'c').baselineY(), five.baselineY(), EPS, "(5) on row 2's baseline");
        assertNotEquals(four.baselineY(), five.baselineY());
        double gridRight = atoms(l, 'd').get(1).originX() + advance(atoms(l, 'd').get(1));
        assertTrue(four.originX() > gridRight && five.originX() > gridRight, "tags right of the grid");
        // The tags form a right-aligned column, as at a display's right margin.
        List<PositionedGlyph> closers = atoms(l, ')');
        assertEquals(2, closers.size());
        assertEquals(closers.get(0).originX(), closers.get(1).originX(), EPS);
    }

    @Test
    void anUntaggedRowCarriesNoTag() {
        Layout l = layout("\\begin{align}a&=b\\\\c&=d\\tag{5}\\end{align}");
        assertEquals(1, atoms(l, '(').size(), "one tag");
        assertEquals(atom(l, 'c').baselineY(), label(l, '5').baselineY(), EPS);
    }

    @Test
    void aTagOnTheEmptyRowAfterATrailingBreakSitsBelowThePreviousRow() {
        // Review lattex/1015 F1: "a\\ \tag{1}" numbers a real empty last row, so the tag
        // sits on that row's own baseline, below row 1, not beside it.
        for (String env : List.of("align", "align*", "gather", "gather*")) {
            String body = env.startsWith("align") ? "a&=b" : "a";
            Layout l = layout("\\begin{" + env + "}" + body + "\\\\\\tag{1}\\end{" + env + "}");
            PositionedGlyph a = atom(l, 'a');
            PositionedGlyph one = label(l, '1');
            assertTrue(one.baselineY() > a.baselineY() + 5.0, env + ": (1) is below row 1");
            // Control: the same tag ON row 1 sits on its baseline.
            Layout same = layout("\\begin{" + env + "}" + body + "\\tag{1}\\\\\\end{" + env + "}");
            assertEquals(atom(same, 'a').baselineY(), label(same, '1').baselineY(), EPS, env);
        }
        // The empty row is spaced like a row: it sits where a non-empty second row would.
        Layout tagged = layout("\\begin{gather}a\\\\\\tag{1}\\end{gather}");
        Layout filled = layout("\\begin{gather}a\\\\x\\tag{1}\\end{gather}");
        assertEquals(label(filled, '1').baselineY(), label(tagged, '1').baselineY(), 0.5,
            "the empty row's baseline is a normal row's");
    }
}
