package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/// The {@code cancel} strike family — {@code \cancel} (up {@code /}), {@code \bcancel}
/// (down {@code \}), {@code \xcancel} (both, an {@code X}), and {@code \cancelto{value}{body}}
/// (up strike + arrowhead + a target value). Parse-shape and malformed-input coverage;
/// geometry lives in {@code CancelLayoutTest}.
class CancelParseTest {

    @Test
    void cancelParsesToAnUpDiagonalCancelNode() {
        MathNode n = MathParser.parse("\\cancel{x}");
        MathNode.Cancel c = assertInstanceOf(MathNode.Cancel.class, n, "\\cancel parses to a Cancel");
        assertEquals(MathNode.CancelKind.CANCEL, c.kind());
        assertNull(c.to(), "a plain \\cancel carries no target value");
        assertEquals("Cancel[CANCEL](A(x,ORD))", MathParserTest.pp(c));
    }

    @Test
    void bcancelIsTheDownDiagonal() {
        MathNode.Cancel c = assertInstanceOf(MathNode.Cancel.class, MathParser.parse("\\bcancel{ab}"));
        assertEquals(MathNode.CancelKind.BCANCEL, c.kind());
        assertEquals("Cancel[BCANCEL](L(A(a,ORD) A(b,ORD)))", MathParserTest.pp(c));
    }

    @Test
    void xcancelIsBothDiagonals() {
        MathNode.Cancel c = assertInstanceOf(MathNode.Cancel.class, MathParser.parse("\\xcancel{y}"));
        assertEquals(MathNode.CancelKind.XCANCEL, c.kind());
        assertNull(c.to());
        assertEquals("Cancel[XCANCEL](A(y,ORD))", MathParserTest.pp(c));
    }

    @Test
    void canceltoTakesValueThenBodyInThatOrder() {
        // \cancelto{value}{body}: the FIRST brace group is the target value, the SECOND
        // the struck body (cancel-package argument order).
        MathNode.Cancel c = assertInstanceOf(MathNode.Cancel.class, MathParser.parse("\\cancelto{0}{x+1}"));
        assertEquals(MathNode.CancelKind.CANCELTO, c.kind());
        assertEquals("Cancel[CANCELTO](L(A(x,ORD) A(+,BIN) A(1,ORD))->A(0,ORD))", MathParserTest.pp(c));
    }

    @Test
    void cancelNestsAndRestylesItsBody() {
        // A cancelled fraction lays out (nesting through the normal group parse).
        MathNode.Cancel c = assertInstanceOf(MathNode.Cancel.class, MathParser.parse("\\cancel{\\frac{a}{b}}"));
        assertEquals("Cancel[CANCEL](Frac(A(a,ORD),A(b,ORD)))", MathParserTest.pp(c));
    }

    @Test
    void cancelWithoutAnArgumentIsAnError() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\cancel"),
            "\\cancel needs a body argument");
    }

    @Test
    void cancelWithAMissingBraceIsAnError() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\cancel{x"),
            "an unterminated \\cancel body is a syntax error");
    }

    @Test
    void canceltoWithoutABodyIsAnError() {
        // \cancelto{0} supplies the value but no struck body — the second argument is missing.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\cancelto{0}"),
            "\\cancelto needs both a value and a body");
    }

    @Test
    void canceltoWithNoArgumentsIsAnError() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\cancelto"),
            "\\cancelto needs a value and a body");
    }
}
