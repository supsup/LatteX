package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parser support for the {@code eqnarray} and {@code alignat} LaTeX environments
 * (plus the inert {@code \nonumber}/{@code \notag} suppressors), closing two
 * wild-corpus GAP rows. Parse-only: reuses the existing Matrix machinery, so no
 * layout/SVG changes are involved.
 */
class EqnarrayAlignatParseTest {

    private static final String EQNARRAY =
        "\\begin{eqnarray} y &=& (x+1)^2 \\nonumber \\\\ &=& x^2 + 2x + 1 \\end{eqnarray}";
    private static final String ALIGNAT =
        "\\begin{alignat}{2} 2x + 3y &= 7, &\\qquad x - y &= 1 \\\\ "
            + "4x - y &= 3, &\\qquad 2x + y &= 5 \\end{alignat}";

    /** Recursively finds the first {@link Matrix} in a parsed tree (grids may be wrapped). */
    private static Matrix findMatrix(MathNode node) {
        if (node instanceof Matrix m) {
            return m;
        }
        if (node instanceof MathList list) {
            for (MathNode child : list.items()) {
                Matrix m = findMatrix(child);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    private static int pathCount(String svg) {
        int n = 0;
        for (int i = svg.indexOf("<path"); i >= 0; i = svg.indexOf("<path", i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void bothGapFormulasRenderWithoutThrowing() {
        String eqn = assertDoesNotThrow(() -> LatteX.render(EQNARRAY));
        String ali = assertDoesNotThrow(() -> LatteX.render(ALIGNAT));
        assertTrue(eqn.contains("<path"), "eqnarray produced no glyphs");
        assertTrue(ali.contains("<path"), "alignat produced no glyphs");
    }

    @Test
    void eqnarrayIsThreeColumnRightCenterLeftGrid() {
        Matrix m = findMatrix(MathParser.parse(EQNARRAY));
        assertNotNull(m, "eqnarray did not parse to a Matrix");
        assertEquals(MatrixKind.ARRAY, m.kind(), "eqnarray should reuse the ARRAY kind");
        assertEquals(
            List.of(ColumnAlign.RIGHT, ColumnAlign.CENTER, ColumnAlign.LEFT),
            m.columnAligns(),
            "eqnarray columns must be right/center/left");
        assertEquals(3, m.columnAligns().size(), "eqnarray is a fixed 3-column grid");
        assertEquals(2, m.rows().size(), "eqnarray has two rows");
    }

    @Test
    void nonumberProducesNoGlyph() {
        // The same grid with and without \nonumber must render identically — \nonumber
        // (and \notag) are inert, contributing zero glyphs.
        String with = "\\begin{eqnarray} x &=& y \\nonumber \\\\ &=& z \\notag \\end{eqnarray}";
        String without = "\\begin{eqnarray} x &=& y \\\\ &=& z \\end{eqnarray}";
        assertEquals(
            pathCount(LatteX.render(without)),
            pathCount(LatteX.render(with)),
            "\\nonumber/\\notag must add no glyphs");
    }

    @Test
    void alignatConsumesMandatoryArgumentAndUsesAlignKind() {
        Matrix m = findMatrix(MathParser.parse(ALIGNAT));
        assertNotNull(m, "alignat did not parse to a Matrix");
        assertEquals(MatrixKind.ALIGN, m.kind(), "alignat should route through the ALIGN kind");
        // With {2} discarded, the body's four &-separated columns give alternating R/L pairs.
        assertEquals(4, m.columnAligns().size(), "alignat body has four columns");
        assertEquals(ColumnAlign.RIGHT, m.columnAligns().get(0));
        assertEquals(ColumnAlign.LEFT, m.columnAligns().get(1));
        assertEquals(ColumnAlign.RIGHT, m.columnAligns().get(2));
        assertEquals(ColumnAlign.LEFT, m.columnAligns().get(3));
    }

    @Test
    void alignatMissingMandatoryArgumentFailsLoud() {
        // The {n} is mandatory; without it the parser must reject cleanly, not silently
        // swallow the first body token.
        assertTrue(
            assertThrowsSyntax(() -> LatteX.render(
                "\\begin{alignat} a &= b \\end{alignat}")),
            "alignat without its {n} argument should fail loud");
    }

    private static boolean assertThrowsSyntax(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
