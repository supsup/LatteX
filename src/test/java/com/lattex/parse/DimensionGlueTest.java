package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Spacing;
import org.junit.jupiter.api.Test;

/// Dimensioned horizontal glue: {@code \hspace{2em}} / {@code \mkern18mu} / {@code \kern3pt}
/// parse to a {@link Spacing} node whose width is the dimension in math units (18mu = 1em).
class DimensionGlueTest {

    /// Extract the single Spacing muWidth from a `<glue>` expression wrapped in a MathList.
    private static double spacingMu(String latex) {
        MathNode n = MathParser.parse(latex);
        MathList list = assertInstanceOf(MathList.class, n, "wraps to a list");
        for (MathNode item : list.items()) {
            if (item instanceof Spacing s) {
                return s.muWidth();
            }
        }
        throw new AssertionError("no Spacing in " + latex);
    }

    @Test
    void emIsEighteenMuPerUnit() {
        assertEquals(18.0, spacingMu("a\\hspace{1em}b"), 1e-9, "1em = 18mu");
        assertEquals(36.0, spacingMu("a\\hspace{2em}b"), 1e-9, "2em = 36mu");
    }

    @Test
    void bareMkernAndUnits() {
        assertEquals(18.0, spacingMu("x\\mkern18mu y"), 1e-9, "bare \\mkern18mu = 18mu");
        assertEquals(1.8 * 20, spacingMu("p\\kern20pt q"), 1e-9, "bare \\kern20pt (pt≈1.8mu)");
    }

    @Test
    void hspaceStarIsAccepted() {
        assertEquals(18.0, spacingMu("a\\hspace*{1em}b"), 1e-9, "the star is a no-op");
    }

    @Test
    void negativeAndFractionalDimensions() {
        assertEquals(-9.0, spacingMu("a\\hspace{-0.5em}b"), 1e-9, "negative fractional em");
    }

    @Test
    void malformedDimensionThrows() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("a\\hspace{2xy}b"), "unknown unit");
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("a\\hspace{em}b"), "no number");
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("a\\hspace b"), "no brace, no bare dim");
    }

    @Test
    void aGapWidensTheRender() {
        double narrow = width(LatteX.render("a\\hspace{1em}b"));
        double wide = width(LatteX.render("a\\hspace{4em}b"));
        assertTrue(wide > narrow, "a bigger dimension makes a wider box");
    }

    private static double width(String svg) {
        int i = svg.indexOf("width=\"");
        int j = svg.indexOf('"', i + 7);
        return Double.parseDouble(svg.substring(i + 7, j));
    }
}
