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
 * S4 grid-layout geometry tests for the Tier-3 environments (matrix family,
 * {@code array}, {@code cases}): column alignment, the grid centred on the math
 * axis, delimiters stretched to the grid height, and {@code \hline}/vertical
 * rules emitted as in-alphabet {@code <rect>}s.
 */
class MatrixLayoutTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        MathNode node = MathParser.parse(latex);
        return LayoutEngine.layout(node, ctx);
    }

    // ---- Grid geometry ---------------------------------------------------

    @Test
    void plainMatrixLaysCellsOnAGrid() {
        Layout l = layout("\\begin{matrix}a&b\\\\c&d\\end{matrix}");
        // Four cell glyphs (a, b, c, d), no delimiters, no rules.
        assertEquals(4, l.glyphs().size(), "a b c d");
        assertTrue(l.rules().isEmpty(), "a plain matrix has no rules");

        // Two columns: with the row content symmetric, exactly two glyphs sit left of
        // the horizontal mid and two to the right; and two rows straddle the axis.
        double minX = l.glyphs().stream().mapToDouble(PositionedGlyph::originX).min().orElseThrow();
        double maxX = l.glyphs().stream().mapToDouble(PositionedGlyph::originX).max().orElseThrow();
        double midX = (minX + maxX) / 2.0;
        long leftColumn = l.glyphs().stream().filter(g -> g.originX() < midX).count();
        assertEquals(2, leftColumn, "two cells in the left column");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() < -1.0), "a top row above the axis");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() > 1.0), "a bottom row below the axis");
    }

    @Test
    void gridIsCentredOnTheMathAxis() {
        // A pmatrix's delimiters are placed with their ink centred on the math axis
        // (the grid, in turn, is centred there). So the left paren's ink vertical
        // midpoint must sit at y = -axisHeight (above the baseline).
        Layout l = layout("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}");
        double baseScale = 40.0 / FONT.unitsPerEm();
        double axis = FONT.mathConstants().axisHeight() * baseScale;
        PositionedGlyph left = l.glyphs().stream()
            .min((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        var o = FONT.outline(left.glyphId());
        double inkTop = left.baselineY() - left.scale() * o.yMax();
        double inkBottom = left.baselineY() - left.scale() * o.yMin();
        double inkCentre = (inkTop + inkBottom) / 2.0;
        assertTrue(Math.abs(inkCentre + axis) < 3.0,
            "delimiter ink is centred on the math axis (grid centred there)");
        // The grid genuinely straddles the baseline.
        assertTrue(-l.minY() > 5.0 && l.maxY() > 5.0, "grid straddles the baseline");
    }

    @Test
    void columnAlignmentRightVsLeft() {
        // Column 0 holds a wide 'W' (row 0) over a narrow 'i' (row 1). Right-aligning
        // that column pushes the narrow cell's right edge to match the wide one, so
        // the narrow glyph starts further right than it would left-aligned.
        double leftX = firstColumnNarrowCellX("\\begin{array}{l}W\\\\i\\end{array}");
        double rightX = firstColumnNarrowCellX("\\begin{array}{r}W\\\\i\\end{array}");
        assertTrue(rightX > leftX + 1.0,
            "right-aligned narrow cell starts further right than left-aligned");
    }

    /** The x-origin of the (narrow, second-row) cell glyph in a one-column array. */
    private static double firstColumnNarrowCellX(String latex) {
        Layout l = layout(latex);
        // The narrow cell 'i' is the shorter glyph; pick the rightmost-starting glyph
        // that sits below the baseline (row 1).
        return l.glyphs().stream()
            .filter(g -> g.baselineY() > 0.5)
            .mapToDouble(PositionedGlyph::originX)
            .max().orElseThrow();
    }

    // ---- Delimiter stretch ----------------------------------------------

    @Test
    void pmatrixStretchesParensToGridHeight() {
        Layout l = layout("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}");
        // Leftmost and rightmost glyphs are the enclosing parens, and they are
        // stretched MATH variants — not the base '(' / ')' at text size.
        PositionedGlyph left = l.glyphs().stream()
            .min((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        PositionedGlyph right = l.glyphs().stream()
            .max((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        assertTrue(variantGids('(').contains(left.glyphId()), "left delimiter is a paren variant");
        assertTrue(variantGids(')').contains(right.glyphId()), "right delimiter is a paren variant");
        assertTrue(left.glyphId() != FONT.glyphId('(') || right.glyphId() != FONT.glyphId(')'),
            "at least one paren is a stretched (non-base) variant over a 2-row grid");
        // The delimiter ink straddles the baseline (spans the whole grid height).
        assertTrue(-l.minY() > 15.0 && l.maxY() > 10.0, "delimiters span the grid vertically");
    }

    @Test
    void bracketAndBraceMatricesRender() {
        assertAlphabet(LatteX.render("\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}"));
        assertAlphabet(LatteX.render("\\begin{Bmatrix}a&b\\\\c&d\\end{Bmatrix}"));
        assertAlphabet(LatteX.render("\\begin{vmatrix}a&b\\\\c&d\\end{vmatrix}"));
    }

    /** A stretchy delimiter's glyph ids: its base glyph plus every vertical variant. */
    private static Set<Integer> variantGids(int codePoint) {
        Set<Integer> gids = new java.util.HashSet<>();
        gids.add(FONT.glyphId(codePoint));
        var construction = FONT.verticalVariants(FONT.glyphId(codePoint));
        if (construction != null) {
            construction.variants().forEach(v -> gids.add(v.glyphId()));
        }
        return gids;
    }

    // ---- cases -----------------------------------------------------------

    @Test
    void casesHasALeftBraceAndTwoLeftColumns() {
        Layout l = layout("\\begin{cases}a&x\\\\b&y\\end{cases}");
        // A left brace (open-brace variant) at the far left, no right delimiter.
        PositionedGlyph left = l.glyphs().stream()
            .min((a, b) -> Double.compare(a.originX(), b.originX())).orElseThrow();
        assertTrue(variantGids('{').contains(left.glyphId()), "cases opens with a brace");
        // Two rows straddle the axis.
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() < -1.0), "top row");
        assertTrue(l.glyphs().stream().anyMatch(g -> g.baselineY() > 1.0), "bottom row");
        assertAlphabet(LatteX.render("\\begin{cases}a&x\\\\b&y\\end{cases}"));
    }

    // ---- array rules -----------------------------------------------------

    @Test
    void arrayHlineAndVruleAreInAlphabetRects() {
        Layout l = layout("\\begin{array}{c|c}1&2\\\\\\hline 3&4\\end{array}");
        assertEquals(4, l.glyphs().size(), "1 2 3 4");
        // One vertical rule (the '|' between the two columns) + one horizontal rule
        // (the \hline between the two rows) = two rects.
        assertEquals(2, l.rules().size(), "one vertical + one horizontal rule");

        Rule vertical = l.rules().stream()
            .max((a, b) -> Double.compare(a.height(), b.height())).orElseThrow();
        Rule horizontal = l.rules().stream()
            .max((a, b) -> Double.compare(a.width(), b.width())).orElseThrow();
        // The vertical rule is tall and thin; the horizontal rule is wide and thin.
        assertTrue(vertical.height() > vertical.width(), "vertical rule is taller than wide");
        assertTrue(horizontal.width() > horizontal.height(), "horizontal rule is wider than tall");
        // The \hline sits between the two rows (near the axis, roughly mid-grid).
        double axis = FONT.mathConstants().axisHeight() * (40.0 / FONT.unitsPerEm());
        assertTrue(Math.abs(horizontal.y() + axis) < 20.0, "\\hline lies between the rows");
        assertAlphabet(LatteX.render("\\begin{array}{c|c}1&2\\\\\\hline 3&4\\end{array}"));
    }

    @Test
    void bigMatrixWithDotsRenders() {
        String latex = "A_{m,n}=\\begin{pmatrix}a_{1,1}&\\cdots&a_{1,n}\\\\"
            + "\\vdots&\\ddots&\\vdots\\\\a_{m,1}&\\cdots&a_{m,n}\\end{pmatrix}";
        Layout l = layout(latex);
        assertTrue(l.width() > 0 && l.height() > 0, "non-degenerate 3x3 matrix");
        // Three columns worth of x-clusters inside the parens.
        long columns = l.glyphs().stream()
            .map(g -> Math.round(g.originX() / 12.0)).distinct().count();
        assertTrue(columns >= 3, "at least three columns of content");
        assertAlphabet(LatteX.render(latex));
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
            // Match attributes as name="value": consuming the whole quoted value keeps
            // an '=' inside a free-text aria-label from false-matching as an attribute.
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=\\s*\"[^\"]*\"")
                .matcher(tag.group(2));
            while (attr.find()) {
                assertTrue(ALLOWED_ATTRS.contains(attr.group(1)),
                    "attribute out of alphabet on <" + element + ">: " + attr.group(1));
            }
        }
    }
}
