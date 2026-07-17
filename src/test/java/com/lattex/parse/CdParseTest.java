package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathNode.CdArrow;
import com.lattex.parse.MathNode.CdArrowKind;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import org.junit.jupiter.api.Test;

/// {@code \begin{CD}} amscd commutative-diagram parse tests: the connector grammar and the
/// object/connector COLUMN MAPPING (objects at even columns, horizontal connectors at odd
/// columns; a connector row's vertical connectors land at the even columns). Structural
/// {@code pp} assertions (see {@link MathParserTest#pp}); malformed connectors fail loud.
class CdParseTest {

    private static Matrix parseCd(String dsl) {
        MathNode n = MathParser.parse(dsl);
        Matrix m = assertInstanceOf(Matrix.class, n, "CD parses to a Matrix");
        assertEquals(MatrixKind.CD, m.kind(), "kind is CD");
        return m;
    }

    private static CdArrow arrowAt(Matrix m, int r, int c) {
        return assertInstanceOf(CdArrow.class, m.rows().get(r).get(c),
            "cell (" + r + "," + c + ") is a connector");
    }

    @Test
    void singleRowObjectsAndHorizontalArrowsAlternateColumns() {
        // A @>f>> B @>>g> C  →  obj, →, obj, →, obj at columns 0..4.
        Matrix m = parseCd("\\begin{CD} A @>f>> B @>>g> C \\end{CD}");
        assertEquals(1, m.rows().size());
        assertEquals(5, m.columnCount(), "obj/conn/obj/conn/obj");
        assertEquals("A(A,ORD)", MathParserTest.pp(m.rows().get(0).get(0)));
        assertEquals(CdArrowKind.RIGHT, arrowAt(m, 0, 1).kind());
        assertEquals("A(f,ORD)", MathParserTest.pp(arrowAt(m, 0, 1).labelA()), "@>f>> label above the arrow");
        assertTrue(arrowAt(m, 0, 1).labelB() == null, "no below label");
        assertEquals("A(B,ORD)", MathParserTest.pp(m.rows().get(0).get(2)));
        assertEquals(CdArrowKind.RIGHT, arrowAt(m, 0, 3).kind());
        assertEquals("A(g,ORD)", MathParserTest.pp(arrowAt(m, 0, 3).labelB()), "@>>g> label below the arrow");
        assertTrue(arrowAt(m, 0, 3).labelA() == null, "no above label");
        assertEquals("A(C,ORD)", MathParserTest.pp(m.rows().get(0).get(4)));
    }

    @Test
    void connectorRowPlacesVerticalArrowsAtEvenColumns() {
        // The classic square: objects, then a @VVV @VVV row, then objects.
        Matrix m = parseCd("\\begin{CD} A @>>> B \\\\ @VfVV @VVgV \\\\ C @>>> D \\end{CD}");
        assertEquals(3, m.rows().size());
        assertEquals(3, m.columnCount(), "obj/conn/obj");
        // Row 1 is the connector row: down arrows at cols 0 and 2, col 1 empty.
        assertEquals(CdArrowKind.DOWN, arrowAt(m, 1, 0).kind());
        assertEquals("A(f,ORD)", MathParserTest.pp(arrowAt(m, 1, 0).labelA()), "@VfVV left label");
        assertEquals("L()", MathParserTest.pp(m.rows().get(1).get(1)), "the odd column is empty");
        assertEquals(CdArrowKind.DOWN, arrowAt(m, 1, 2).kind());
        assertEquals("A(g,ORD)", MathParserTest.pp(arrowAt(m, 1, 2).labelB()), "@VVgV right label");
        // Object rows line up above/below.
        assertEquals("A(A,ORD)", MathParserTest.pp(m.rows().get(0).get(0)));
        assertEquals("A(D,ORD)", MathParserTest.pp(m.rows().get(2).get(2)));
    }

    @Test
    void allConnectorTypesParse() {
        Matrix m = parseCd("\\begin{CD} A @>>> B @<<< C @= D \\\\ @AAA @VVV @| @. \\end{CD}");
        assertEquals(CdArrowKind.RIGHT, arrowAt(m, 0, 1).kind());
        assertEquals(CdArrowKind.LEFT, arrowAt(m, 0, 3).kind());
        assertEquals(CdArrowKind.EQUAL, arrowAt(m, 0, 5).kind());
        assertEquals(CdArrowKind.UP, arrowAt(m, 1, 0).kind());
        assertEquals(CdArrowKind.DOWN, arrowAt(m, 1, 2).kind());
        assertEquals(CdArrowKind.VEQUAL, arrowAt(m, 1, 4).kind());
        assertEquals(CdArrowKind.EMPTY, arrowAt(m, 1, 6).kind());
    }

    @Test
    void bracedLabelWithDelimiterCharSurvives() {
        // A `>` inside a braced label is part of the label, not a connector delimiter.
        Matrix m = parseCd("\\begin{CD} A @>{x>y}>> B \\end{CD}");
        CdArrow a = arrowAt(m, 0, 1);
        assertEquals(CdArrowKind.RIGHT, a.kind());
        // The braced group parses to a math list containing the inner '>' relation.
        assertTrue(a.labelA() != null, "the braced label is captured");
    }

    @Test
    void unterminatedConnectorThrowsPositioned() {
        // `@>f` never closes its delimiter run before \end.
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{CD} A @>f B \\end{CD}"));
    }

    @Test
    void unknownConnectorTypeThrows() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{CD} A @X B \\end{CD}"));
    }

    @Test
    void missingEndThrows() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{CD} A @>>> B"));
    }
}
