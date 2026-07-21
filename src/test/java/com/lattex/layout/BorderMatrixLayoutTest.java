package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S4 geometry oracle for {@code \bordermatrix} (Knuth's bordered matrix): the body
 * grid is wrapped in a paren fence, the column labels sit ABOVE the body columns and
 * the row labels sit to the LEFT of the fence, both OUTSIDE it. Distinct labels
 * ({@code P Q} above, {@code R S} at left, body {@code a b c d}) let every glyph be
 * identified by its source code point; the two paren glyphs are the only construction
 * glyphs ({@link PositionedGlyph#NO_SOURCE}), so they are found without guessing a gid.
 */
class BorderMatrixLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final Set<String> ALLOWED = Set.of("svg", "g", "path", "rect");

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    // Per-glyph ink extents in user space (see PositionedGlyph's userX/userY mapping).
    private static double inkLeft(PositionedGlyph g) {
        return g.originX() + g.scale() * FONT.outline(g.glyphId()).xMin();
    }

    private static double inkRight(PositionedGlyph g) {
        return g.originX() + g.scale() * FONT.outline(g.glyphId()).xMax();
    }

    private static double inkTop(PositionedGlyph g) { // smaller y is higher (y grows down)
        return g.baselineY() - g.scale() * FONT.outline(g.glyphId()).yMax();
    }

    private static double inkBottom(PositionedGlyph g) {
        return g.baselineY() - g.scale() * FONT.outline(g.glyphId()).yMin();
    }

    private static List<PositionedGlyph> withCp(Layout l, int... cps) {
        return l.glyphs().stream()
            .filter(g -> {
                for (int cp : cps) {
                    if (g.sourceCodePoint() == cp) {
                        return true;
                    }
                }
                return false;
            })
            .toList();
    }

    @Test
    void bordermatrixLabelsSitOutsideTheParenFencedBody() {
        // Distinct labels so each glyph is uniquely identifiable by code point.
        Layout l = layout("\\bordermatrix{&P&Q\\\\R&a&b\\\\S&c&d}");

        // The parens are the only NO_SOURCE (construction) glyphs; cluster by x-origin
        // into the left paren (min originX) and the right paren (max originX).
        List<PositionedGlyph> fence = l.glyphs().stream()
            .filter(g -> g.sourceCodePoint() == PositionedGlyph.NO_SOURCE)
            .toList();
        assertFalse(fence.isEmpty(), "the body is paren-fenced");
        double leftParenX = fence.stream().mapToDouble(PositionedGlyph::originX).min().orElseThrow();
        double rightParenX = fence.stream().mapToDouble(PositionedGlyph::originX).max().orElseThrow();
        assertTrue(rightParenX > leftParenX + 1.0, "two distinct paren columns");
        List<PositionedGlyph> leftParen = fence.stream()
            .filter(g -> Math.abs(g.originX() - leftParenX) < 0.5).toList();
        List<PositionedGlyph> rightParen = fence.stream()
            .filter(g -> Math.abs(g.originX() - rightParenX) < 0.5).toList();

        List<PositionedGlyph> rowLabels = withCp(l, 'R', 'S');
        List<PositionedGlyph> colLabels = withCp(l, 'P', 'Q');
        List<PositionedGlyph> body = withCp(l, 'a', 'b', 'c', 'd');
        assertEquals(2, rowLabels.size(), "two row labels R, S");
        assertEquals(2, colLabels.size(), "two column labels P, Q");
        assertEquals(4, body.size(), "four body cells a, b, c, d");

        // (1) Row labels' ink is LEFT of the left paren's ink.
        double rowLabelInkRight = rowLabels.stream().mapToDouble(BorderMatrixLayoutTest::inkRight)
            .max().orElseThrow();
        double leftParenInkLeft = leftParen.stream().mapToDouble(BorderMatrixLayoutTest::inkLeft)
            .min().orElseThrow();
        assertTrue(rowLabelInkRight < leftParenInkLeft,
            "row labels' ink (right edge " + rowLabelInkRight + ") is left of the left paren ink ("
                + leftParenInkLeft + ")");

        // (2) Column labels' ink is ABOVE the body's top ink.
        double colLabelInkBottom = colLabels.stream().mapToDouble(BorderMatrixLayoutTest::inkBottom)
            .max().orElseThrow();
        double bodyInkTop = body.stream().mapToDouble(BorderMatrixLayoutTest::inkTop)
            .min().orElseThrow();
        assertTrue(colLabelInkBottom < bodyInkTop,
            "column labels' ink (bottom " + colLabelInkBottom + ") is above the body top ink ("
                + bodyInkTop + ")");

        // (3) Every body cell's ink sits INSIDE the fence (between the two parens' ink).
        double leftParenInkRight = leftParen.stream().mapToDouble(BorderMatrixLayoutTest::inkRight)
            .max().orElseThrow();
        double rightParenInkLeft = rightParen.stream().mapToDouble(BorderMatrixLayoutTest::inkLeft)
            .min().orElseThrow();
        for (PositionedGlyph g : body) {
            assertTrue(inkLeft(g) > leftParenInkRight,
                (char) g.sourceCodePoint() + " is right of the left paren");
            assertTrue(inkRight(g) < rightParenInkLeft,
                (char) g.sourceCodePoint() + " is left of the right paren");
        }
    }

    @Test
    void bordermatrixRendersTheCorpusFixtureWithinTheAlphabet() {
        // Render-level regression on the exact corpus input.
        String svg = LatteX.render("\\bordermatrix{&1&2\\\\1&a&b\\\\2&c&d}");
        assertTrue(svg.contains("<path"), "renders real glyph paths");
        assertTrue(svg.contains("viewBox="), "has a viewBox");
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)").matcher(svg);
        while (tag.find()) {
            assertTrue(ALLOWED.contains(tag.group(1)), "element out of alphabet: <" + tag.group(1) + ">");
        }
    }

    @Test
    void bordermatrixMathMlAndAriaLabelAreStructured() {
        String src = "\\bordermatrix{&P&Q\\\\R&a&b\\\\S&c&d}";
        String mathml = LatteX.toMathML(src);
        assertTrue(mathml.contains("<mtable>"), "bordermatrix serializes to an mtable");
        assertTrue(mathml.contains("fence=\"true\""), "the body block carries paren fences");
        // describe() surfaces publicly as the SVG aria-label.
        String svg = LatteX.render(src);
        assertTrue(svg.contains("bordered matrix of 2 rows and 2 columns"), "a11y label names the grid");
        assertTrue(svg.contains("column labels: P, Q"), "a11y label lists the column labels");
    }
}
