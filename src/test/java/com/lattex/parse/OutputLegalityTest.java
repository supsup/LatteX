package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- plan e5324119: the full XML 1.0 Char production, not C0 only ----

    @Test
    void isXmlCharIsTheCharProductionAtEveryBoundary() {
        // Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
        // Expectations come from the XML 1.0 spec text, not from the implementation.
        int[] legal = {0x9, 0xA, 0xD, 0x20, 0x7E, 0xD7FF, 0xE000, 0xFFFD, 0x10000, 0x1D538,
            0x10FFFF};
        int[] illegal = {0x0, 0x8, 0xB, 0xC, 0x1F, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xFFFE,
            0xFFFF, 0x110000, -1};
        for (int cp : legal) {
            assertTrue(OutputLegality.isXmlChar(cp), "legal: 0x" + Integer.toHexString(cp));
        }
        for (int cp : illegal) {
            assertFalse(OutputLegality.isXmlChar(cp), "illegal: 0x" + Integer.toHexString(cp));
        }
    }

    @Test
    void bmpNoncharactersAreStrippedLikeC0() {
        // Fast-path detection: a string whose ONLY defect is U+FFFE / U+FFFF.
        assertEquals("ab", OutputLegality.sanitize("a\uFFFEb"), "U+FFFE stripped");
        assertEquals("ab", OutputLegality.sanitize("a\uFFFFb"), "U+FFFF stripped");
        // Slow-path filtering: the noncharacter sits AFTER a surrogate pair already forced
        // the rewrite, so this pins the strip inside rewrite, not only the fast-path flag.
        assertEquals("\uD835\uDD38z", OutputLegality.sanitize("\uD835\uDD38\uFFFFz"),
            "U+FFFF stripped on the slow path, the pair kept");
    }

    @Test
    void legalNeighboursOfTheExcludedRangesArePreserved() {
        // POSITIVE: the last/first legal code point on each side of every excluded range.
        String bmp = "\u0020\uD7FF\uE000\uFFFD";
        assertSame(bmp, OutputLegality.sanitize(bmp), "U+0020/D7FF/E000/FFFD untouched");
        String astral = "\uD800\uDC00\uDBFF\uDFFF"; // U+10000 and U+10FFFF as pairs
        assertEquals(astral, OutputLegality.sanitize(astral), "U+10000 and U+10FFFF kept");
        assertEquals("a\tb", OutputLegality.sanitize("a\u0008\tb"),
            "U+0008 stripped, U+0009 kept");
    }

    @Test
    void isXmlLegalRefusesWhatSanitizeWouldRepair() {
        assertTrue(OutputLegality.isXmlLegal("x \uD7FF\uE000\uFFFD\uD835\uDD38"),
            "legal BMP edges plus a valid pair");
        assertTrue(OutputLegality.isXmlLegal(""), "empty is legal");
        assertFalse(OutputLegality.isXmlLegal("a\uFFFE"), "U+FFFE");
        assertFalse(OutputLegality.isXmlLegal("a\uFFFF"), "U+FFFF");
        assertFalse(OutputLegality.isXmlLegal("a\u001Fb"), "U+001F");
        assertFalse(OutputLegality.isXmlLegal("a\uD800b"), "lone high surrogate");
        assertFalse(OutputLegality.isXmlLegal("a\uDC00b"), "lone low surrogate");
        assertFalse(OutputLegality.isXmlLegal("\uD800"), "trailing lone high surrogate");
    }
}
