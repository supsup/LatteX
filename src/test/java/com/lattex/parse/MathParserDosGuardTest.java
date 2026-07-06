package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Parse-time DoS guards: the recursive-descent {@link MathParser} must never let
 * an adversary-supplied string (author LaTeX arrives via {@code \lx}) exhaust the
 * JVM stack. Before these guards, {@code "{".repeat(200000)} threw a raw
 * {@link StackOverflowError} — an {@link Error}, not a {@link RuntimeException},
 * so it escaped the caller's {@code catch (RuntimeException)} handlers and killed
 * the render thread. Both guards now surface as {@link MathSyntaxException}
 * (a caught {@link RuntimeException}).
 */
class MathParserDosGuardTest {

    /** Deep brace nesting hits the depth cap as a MathSyntaxException, not a StackOverflowError. */
    @Test
    void deeplyNestedBracesThrowSyntaxExceptionNotStackOverflow() {
        String pathological = "{".repeat(5000);

        // The parse must NOT escape as an Error. assertThrows on MathSyntaxException
        // both asserts the caught type and lets a StackOverflowError propagate as a
        // test failure rather than being swallowed.
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(pathological));
        assertTrue(ex.getMessage().contains("nesting too deep"),
            "expected a nesting-depth message, got: " + ex.getMessage());
    }

    /** A legal deeply-nested expression (10 nested \frac) still parses cleanly. */
    @Test
    void legalDeepExpressionStillParses() {
        // Build \frac{1}{\frac{1}{ ... }} nested 10 deep — well under MAX_DEPTH.
        StringBuilder sb = new StringBuilder();
        int levels = 10;
        for (int i = 0; i < levels; i++) {
            sb.append("\\frac{1}{");
        }
        sb.append("x");
        sb.append("}".repeat(levels));
        String legal = sb.toString();

        assertDoesNotThrow(() -> MathParser.parse(legal));
    }

    /** An over-length input is rejected at the entry as a MathSyntaxException. */
    @Test
    void overLengthInputThrowsSyntaxException() {
        String tooLong = "a".repeat(MathParser.MAX_SOURCE_LENGTH + 1);

        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(tooLong));
        assertTrue(ex.getMessage().contains("input too long"),
            "expected an over-length message, got: " + ex.getMessage());
    }
}
