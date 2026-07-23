package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Plan cfd12523 — the ONE shared output-boundary legality policy, tested directly.
 * Every LatteX output boundary (SVG text, MathML, aria/a11y, data-*) routes text
 * through {@link OutputLegality#sanitize} before format-specific escaping. Control
 * chars are written as {@code \\u} escapes so the source stays clean text.
 */
class OutputLegalityTest {

    @Test
    void cleanTextIsReturnedUntouchedAndIdentity() {
        String clean = "x + y = z";
        // No allocation on the fast path: the same instance is returned.
        assertSame(clean, OutputLegality.sanitize(clean));
    }

    @Test
    void c0ControlsAreStrippedExceptLegalWhitespace() {
        assertEquals("xy", OutputLegality.sanitize("x\u0000y"), "NUL stripped");
        assertEquals("ab", OutputLegality.sanitize("a\u0007b"), "BEL stripped");
        assertEquals("ab", OutputLegality.sanitize("a\u001fb"), "unit separator stripped");
        // Legal XML 1.0 whitespace is preserved.
        assertEquals("a\tb\nc\rd", OutputLegality.sanitize("a\tb\nc\rd"), "tab/newline/CR kept");
    }

    @Test
    void c1ControlsPassThrough() {
        // XML 1.0 permits C1 (0x80-0x9F) as characters; the SVG path never stripped
        // them, so the shared policy must not either (no new policy fork).
        assertEquals("a\u0085b", OutputLegality.sanitize("a\u0085b"), "C1 (NEL) preserved");
    }

    @Test
    void wellFormedSurrogatePairIsPreserved() {
        // U+1D538 MATHEMATICAL DOUBLE-STRUCK CAPITAL A, as a UTF-16 surrogate pair.
        String astral = "𝔸";
        String in = "pre" + astral + "post";
        assertEquals(in, OutputLegality.sanitize(in), "a well-formed astral pair round-trips");
    }

    @Test
    void unpairedSurrogatesFailLoud() {
        assertThrows(MathSyntaxException.class, () -> OutputLegality.sanitize("x\uD800y"),
            "lone high surrogate");
        assertThrows(MathSyntaxException.class, () -> OutputLegality.sanitize("x\uDC00y"),
            "lone low surrogate");
        assertThrows(MathSyntaxException.class, () -> OutputLegality.sanitize("\uD800"),
            "trailing lone high surrogate");
        assertThrows(MathSyntaxException.class, () -> OutputLegality.sanitize("\uDC00𝔸"),
            "leading lone low surrogate before a valid pair");
    }
}
