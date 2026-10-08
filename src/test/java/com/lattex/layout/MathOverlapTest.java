package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 720cd87e, item 3: mathtools' {@code \mathllap}, {@code \mathrlap} and
 * {@code \mathclap} — a zero-WIDTH box whose math content overhangs to the left, to the
 * right, or both ways equally from the point where it sits (mathtools: {@code \llap},
 * {@code \rlap}, {@code \clap} with the content set in math at the current style). The
 * box keeps its content's height and depth, and spaces as an Ord atom.
 */
class MathOverlapTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double EPS = 1e-6;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    private static List<PositionedGlyph> atoms(Layout l, int cp) {
        return l.glyphs().stream().filter(g -> g.sourceCodePoint() == cp)
            .sorted(Comparator.comparingDouble(PositionedGlyph::originX)).toList();
    }

    private static double advance(PositionedGlyph g) {
        return FONT.advanceWidth(g.glyphId()) * g.scale();
    }

    /** Where the overlap sits: just past the 'a' that precedes it. */
    private static double pointAfterA(Layout l) {
        PositionedGlyph a = atoms(l, 'a').get(0);
        return a.originX() + advance(a);
    }

    @Test
    void eachOverlapHasZeroWidth() {
        // Layout.width() is the INK extent, which an overhang legitimately grows; the
        // advance is read off where the next atom lands: b sits exactly where it sits in "ab".
        double bInAb = atoms(layout("ab"), 'b').get(0).originX();
        for (String cmd : List.of("mathllap", "mathrlap", "mathclap")) {
            assertEquals(bInAb, atoms(layout("a\\" + cmd + "{xyz}b"), 'b').get(0).originX(), EPS,
                "\\" + cmd + "{xyz} must advance by zero");
        }
    }

    @Test
    void llapInksToTheLeftOfThePoint() {
        Layout l = layout("a\\mathllap{xy}b");
        double point = pointAfterA(l);
        PositionedGlyph y = atoms(l, 'y').get(0);
        assertEquals(point, y.originX() + advance(y), EPS, "content's right edge at the point");
        assertTrue(atoms(l, 'x').get(0).originX() < point);
    }

    @Test
    void rlapInksToTheRightOfThePoint() {
        Layout l = layout("a\\mathrlap{xy}b");
        double point = pointAfterA(l);
        assertEquals(point, atoms(l, 'x').get(0).originX(), EPS, "content's left edge at the point");
    }

    @Test
    void clapCentresOnThePoint() {
        Layout l = layout("a\\mathclap{xy}b");
        double point = pointAfterA(l);
        PositionedGlyph x = atoms(l, 'x').get(0);
        PositionedGlyph y = atoms(l, 'y').get(0);
        double left = x.originX();
        double right = y.originX() + advance(y);
        assertEquals(point, (left + right) / 2.0, EPS, "content centred on the point");
        assertTrue(left < point && right > point);
    }

    @Test
    void theContentIsMathAndKeepsItsVerticalExtent() {
        // math italic, not text: the x is U+1D465
        Layout l = layout("a\\mathclap{x}b");
        assertEquals(FONT.glyphId(0x1D465), atoms(l, 'x').get(0).glyphId());
        // height/depth survive: an overlapped fraction is as tall as the fraction
        Layout frac = layout("\\frac{a}{b}");
        Layout lapped = layout("\\mathclap{\\frac{a}{b}}");
        assertEquals(frac.width(), lapped.width(), EPS, "same ink, just moved");
        assertEquals(frac.height(), lapped.height(), EPS);
        assertEquals(atoms(layout("xy"), 'y').get(0).originX(),
            atoms(layout("x\\mathclap{\\frac{a}{b}}y"), 'y').get(0).originX(), EPS);
    }

    @Test
    void theOverlapSpacesAsAnOrdAtom() {
        // x, lap(Ord), +(Bin), z  ==  x + z  (Ord|Bin and Bin|Ord medium glue either way)
        assertEquals(atoms(layout("x+z"), 'z').get(0).originX(),
            atoms(layout("x\\mathrlap{y}+z"), 'z').get(0).originX(), EPS);
        // a lapped relation is not a relation outside: no thick glue
        assertEquals(atoms(layout("xz"), 'z').get(0).originX(),
            atoms(layout("x\\mathrlap{=}z"), 'z').get(0).originX(), EPS);
    }

    @Test
    void theOptionalStyleArgumentSetsTheContentStyle() {
        Layout big = layout("\\mathclap{x}");
        Layout small = layout("\\mathclap[\\scriptstyle]{x}");
        assertTrue(atoms(small, 'x').get(0).scale() < atoms(big, 'x').get(0).scale());
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\mathclap[x]{y}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\mathclap"));
    }
}
