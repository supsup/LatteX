package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A {@code \multicolumn} (plan 636d214f) meeting {@code @{...}}/{@code !{...}} column-spec
 * material (plan fc988bc4). In LaTeX's array the material after a column belongs to that
 * column's template (the leading material to the first column's), so a span replaces it
 * exactly as it replaces the rules there: on the span's row the material inside the span,
 * at its right edge, and at the grid's left edge when the span starts in column one is not
 * drawn; every other row, and the boundary to the span's left, keep it. The classic case is
 * {@code r@{.}l} decimal alignment under a {@code \multicolumn{2}{c}{Header}}.
 */
class MulticolumnSeparatorTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    private static List<PositionedGlyph> atoms(Layout l, int cp) {
        return l.glyphs().stream().filter(g -> g.sourceCodePoint() == cp).toList();
    }

    private static double baselineOf(Layout l, int cp) {
        List<PositionedGlyph> found = atoms(l, cp);
        assertEquals(1, found.size(), "exactly one '" + Character.toString(cp) + "' atom");
        return found.get(0).baselineY();
    }

    @Test
    void aSpanAcrossAtMaterialReplacesItOnItsRowOnly() {
        // r@{:}l with a header spanning both columns: the ':' is inside the span on row 1.
        Layout l = layout("\\begin{array}{r@{:}l}\\multicolumn{2}{c}{h}\\\\3&14\\end{array}");
        List<PositionedGlyph> colons = atoms(l, ':');
        assertEquals(1, colons.size(), "the material is drawn on the unspanned row only");
        assertEquals(baselineOf(l, '3'), colons.get(0).baselineY(), 0.01, "on row 2");
        // Control: without the span the material sits on both rows.
        Layout plain = layout("\\begin{array}{r@{:}l}h&k\\\\3&14\\end{array}");
        assertEquals(2, atoms(plain, ':').size(), "control: one ':' per row");
    }

    @Test
    void aSpanReplacesTheMaterialAtItsRightEdgeAndTheGridsLeftEdge() {
        // A one-column span in column 1 replaces the leading @{*} (column 1's template)
        // and the @{:} after it; the !{;} after column 2 is outside the span and stays.
        Layout l = layout("\\begin{array}{@{*}c@{:}c!{;}c}\\multicolumn{1}{c}{u}&v&w"
            + "\\\\x&a&b\\end{array}");
        double row1 = baselineOf(l, 'u');
        double row2 = baselineOf(l, 'x');
        for (int cp : new int[] {'*', ':'}) {
            List<PositionedGlyph> drawn = atoms(l, cp);
            assertEquals(1, drawn.size(), Character.toString(cp) + " drawn once");
            assertEquals(row2, drawn.get(0).baselineY(), 0.01, Character.toString(cp) + " on row 2");
        }
        List<PositionedGlyph> semis = atoms(l, ';');
        assertEquals(2, semis.size(), "material outside the span stays on both rows");
        assertTrue(semis.stream().anyMatch(g -> Math.abs(g.baselineY() - row1) < 0.01));
    }

    @Test
    void theMaterialLeftOfASpanBelongsToThePreviousColumnAndStays() {
        Layout l = layout("\\begin{array}{c@{:}cc}u&\\multicolumn{2}{c}{m}\\\\x&a&b\\end{array}");
        assertEquals(2, atoms(l, ':').size(), "the boundary before the span keeps its material");
    }

    @Test
    void theBoundaryKeepsItsWidthSoColumnsStayAligned() {
        // The replaced material's width still separates the columns on every row (as a
        // replaced rule's gap does), so a span narrower than its columns leaves the
        // unspanned row laid out exactly as it is alone.
        Layout spanned = layout("\\begin{array}{r@{:}l}\\multicolumn{2}{c}{h}\\\\3&14\\end{array}");
        Layout alone = layout("\\begin{array}{r@{:}l}3&14\\end{array}");
        assertEquals(atoms(alone, '3').get(0).originX(), atoms(spanned, '3').get(0).originX(), 0.01);
        assertEquals(atoms(alone, ':').get(0).originX(), atoms(spanned, ':').get(0).originX(), 0.01);
        assertEquals(atoms(alone, '4').get(0).originX(), atoms(spanned, '4').get(0).originX(), 0.01);
    }

    @Test
    void mathmlDropsTheReplacedMaterialToo() {
        String mml = LatteX.toMathML("\\begin{array}{r@{:}l}\\multicolumn{2}{c}{h}\\\\3&14\\end{array}");
        assertTrue(mml.contains("columnspan=\"2\""), mml);
        int first = mml.indexOf("<mo>:</mo>");
        assertTrue(first >= 0, "row 2 still carries the material: " + mml);
        assertEquals(-1, mml.indexOf("<mo>:</mo>", first + 1), "and only row 2: " + mml);
        String spanRow = mml.substring(mml.indexOf("<mtable>"), mml.indexOf("</mtr>"));
        assertFalse(spanRow.contains(":"), "not on the span row: " + spanRow);
    }
}
