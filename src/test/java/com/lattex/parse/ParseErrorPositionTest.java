package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Positioned parse errors: {@link MathSyntaxException} carries the source offset and,
 * once {@link MathParser#parse} attaches the input, renders a caret at the column.
 */
class ParseErrorPositionTest {

    @Test
    void unknownCommandCarriesTheCommandOffset() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("a + \\unknwn"));
        // 'a'=0 ' '=1 '+'=2 ' '=3 '\'=4 — the caret sits on the backslash.
        assertEquals(4, e.offset(), "offset should point at the backslash of \\unknwn");
        assertEquals("a + \\unknwn", e.source());
        String[] lines = e.caretString().split("\n");
        assertEquals("a + \\unknwn", lines[0], "first line echoes the source");
        assertEquals(4, lines[1].indexOf('^'), "the caret is under column 4");
        assertTrue(lines[2].contains("Unknown command"), "then the message");
    }

    @Test
    void caretFallsBackToMessageWithoutPosition() {
        MathSyntaxException e = new MathSyntaxException("boom");
        assertEquals(MathSyntaxException.NO_OFFSET, e.offset());
        assertEquals("boom", e.caretString(), "no source/offset -> just the message, still safe to show");
    }

    @Test
    void danglingBackslashAndUnbalancedBraceArePositioned() {
        MathSyntaxException dangle = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x + \\"));
        assertEquals(4, dangle.offset(), "the trailing backslash is at column 4");
        assertTrue(dangle.caretString().contains("^"));

        MathSyntaxException brace = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\frac{a}{b"));
        assertTrue(brace.offset() >= 0, "the missing-brace error should be positioned");
        assertTrue(brace.source() != null && brace.caretString().contains("^"));
    }

    @Test
    void validInputStillParsesUnaffected() {
        // The offset plumbing must not perturb the happy path.
        assertTrue(MathParser.parse("\\frac{a}{b} + x^2") instanceof MathNode);
    }
}
