package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parser support for amsmath's {@code subarray} environment — the single-column
 * limit stack that routes onto the SAME {@link MatrixKind#SUBSTACK} grid
 * {@code \substack} builds, plus the mandatory {@code {c}}/{@code {l}} column spec
 * {@code \substack} does not have.
 *
 * <p>LaTeXML expands {@code \substack} into
 * {@code \begin{subarray}{c}…\end{subarray}}, so harvested corpora carry this
 * shape directly (a stack of conditions under a {@code \sum}). Parse-only: it
 * reuses the existing Matrix machinery and an existing MatrixKind, so no layout
 * or SVG change is involved.
 */
class SubarrayParseTest {

    private static final String UNDER_SUM =
        "\\sum_{\\begin{subarray}{c} i \\ge 0 \\\\ j \\le n \\end{subarray}} a_{ij}";

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

    // ------------------------------------------------------------------
    // The shape: subarray IS \substack's grid.
    // ------------------------------------------------------------------

    @Test
    void subarrayUnderSumRenders() {
        String svg = assertDoesNotThrow(() -> LatteX.render(UNDER_SUM));
        assertTrue(svg.contains("<path"), "subarray under \\sum produced no glyphs");
    }

    @Test
    void subarrayIsASingleCentredSubstackColumn() {
        Matrix m = findMatrix(MathParser.parse(
            "\\begin{subarray}{c} i \\ge 0 \\\\ j \\le n \\end{subarray}"));
        assertNotNull(m, "subarray did not parse to a Matrix");
        assertEquals(MatrixKind.SUBSTACK, m.kind(),
            "subarray must reuse \\substack's grid kind");
        assertEquals(List.of(ColumnAlign.CENTER), m.columnAligns(),
            "subarray{c} is exactly one centred column");
        assertEquals(2, m.rows().size(), "two \\\\-separated rows");
        assertEquals(MathNode.Fenced.NULL_DELIMITER, m.leftDelim(), "subarray is undelimited");
        assertEquals(MathNode.Fenced.NULL_DELIMITER, m.rightDelim(), "subarray is undelimited");
    }

    @Test
    void subarrayLColSpecLeftAligns() {
        Matrix m = findMatrix(MathParser.parse(
            "\\begin{subarray}{l} k \\in S \\\\ k \\neq 0 \\end{subarray}"));
        assertNotNull(m, "subarray{l} did not parse to a Matrix");
        assertEquals(List.of(ColumnAlign.LEFT), m.columnAligns(),
            "subarray{l} is one LEFT-aligned column");
    }

    @Test
    void subarrayCentredMatchesSubstackTreeAndGlyphCount() {
        // The whole point of the routing: \substack{...} and \begin{subarray}{c}...
        // must produce the same node shape and the same rendered glyphs.
        String substack = "\\sum_{\\substack{i \\ge 0 \\\\ j \\le n}} a_{ij}";
        assertEquals(
            MathParserTest.pp(MathParser.parse(substack)),
            MathParserTest.pp(MathParser.parse(UNDER_SUM)),
            "subarray{c} must build the same tree as the equivalent \\substack");
        assertEquals(
            pathCount(LatteX.render(substack)),
            pathCount(LatteX.render(UNDER_SUM)),
            "subarray{c} must draw the same glyphs as the equivalent \\substack");
    }

    @Test
    void subarrayAcceptsCrAsARowSeparator() {
        Matrix m = findMatrix(MathParser.parse("\\begin{subarray}{c} a \\cr b \\end{subarray}"));
        assertNotNull(m, "subarray with \\cr did not parse to a Matrix");
        assertEquals(2, m.rows().size(), "\\cr separates rows exactly like \\\\");
    }

    @Test
    void singleRowSubarrayIsAOneRowStack() {
        Matrix m = findMatrix(MathParser.parse("\\begin{subarray}{c} n \\end{subarray}"));
        assertNotNull(m, "single-row subarray did not parse to a Matrix");
        assertEquals(1, m.rows().size());
        assertEquals(1, m.columnAligns().size());
    }

    // ------------------------------------------------------------------
    // Argument discipline: the colspec is mandatory and narrow.
    // ------------------------------------------------------------------

    @Test
    void subarrayWithoutAColSpecFailsLoud() {
        // The {c} is mandatory; without it the parser must reject cleanly rather than
        // silently serving the first body token as content.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray} a \\\\ b \\end{subarray}"));
        assertTrue(e.getMessage().contains("column spec"),
            "expected a column-spec complaint, got: " + e.getMessage());
    }

    @Test
    void subarrayRejectsColumnTypesItDoesNotSupport() {
        // amsmath's subarray takes only c and l — r and | belong to array's spec.
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray}{r} a \\end{subarray}"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray}{|c|} a \\end{subarray}"));
    }

    @Test
    void subarrayRejectsAMultiColumnSpec() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray}{cc} a \\end{subarray}"));
        assertTrue(e.getMessage().contains("single"),
            "expected a single-character complaint, got: " + e.getMessage());
    }

    @Test
    void subarrayRejectsAnAlignmentTabInTheBody() {
        // subarray declares exactly one column, so a '&' must fail loud rather than
        // silently widening the stack into a two-column grid.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray}{c} a & b \\end{subarray}"));
        assertTrue(e.getMessage().contains("subarray"),
            "the ragged-row message must name the environment, got: " + e.getMessage());
    }

    @Test
    void unterminatedSubarrayFailsLoud() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray}{c} a \\\\ b"));
    }

    @Test
    void mismatchedEndFailsLoud() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarray}{c} a \\end{matrix}"));
    }

    // ------------------------------------------------------------------
    // Negative control: adding subarray must not make array's own spec laxer,
    // and must not turn an unrelated unknown environment into a supported one.
    // ------------------------------------------------------------------

    @Test
    void arrayStillRejectsARowWiderThanItsColumnSpec() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}{c} a & b \\end{array}"));
        assertTrue(e.getMessage().contains("column spec declares only 1"),
            "array's ragged-row guard must survive the shared declared-spec path, got: "
                + e.getMessage());
    }

    @Test
    void arrayStillAcceptsItsFullColumnSpecVocabulary() {
        Matrix m = findMatrix(MathParser.parse("\\begin{array}{l|cr} a & b & c \\end{array}"));
        assertNotNull(m, "array did not parse to a Matrix");
        assertEquals(MatrixKind.ARRAY, m.kind());
        assertEquals(List.of(ColumnAlign.LEFT, ColumnAlign.CENTER, ColumnAlign.RIGHT),
            m.columnAligns());
        assertEquals(List.of(0, 1, 0, 0), m.columnRules(), "the | rule must still land");
    }

    @Test
    void aNeighbouringUnknownEnvironmentStillFailsLoud() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{subarrays}{c} a \\end{subarrays}"));
    }
}
