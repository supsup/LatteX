package com.lattex.api;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopySourcePayloadTest {
    @Test
    void canonicalGoldensKeepCrLfAndBomDistinct() {
        assertEquals("", CopySourcePayload.encode("").orElseThrow());
        assertEquals("QQ1C", CopySourcePayload.encode("A\rB").orElseThrow());
        assertEquals("QQpC", CopySourcePayload.encode("A\nB").orElseThrow());
        assertEquals("QQ0KQg", CopySourcePayload.encode("A\r\nB").orElseThrow());
        assertEquals("77u_", CopySourcePayload.encode("\uFEFF").orElseThrow());
        assertEquals("8J-YgA", CopySourcePayload.encode("\uD83D\uDE00").orElseThrow());
    }

    @Test
    void authoredEntitiesAndHostileMarkupRemainPlainOriginalText() {
        for (String source : new String[] {
                "&#13;", "&quot;", "\" onclick=\"alert(1)", "\"><script>x</script>",
                "'&<>\t\r\n", "\uFEFFx", "x\uFEFFy"}) {
            String carrier = CopySourcePayload.encode(source).orElseThrow();
            assertTrue(carrier.matches("[A-Za-z0-9_-]*"));
            assertEquals(source, new String(Base64.getUrlDecoder().decode(carrier), StandardCharsets.UTF_8));
        }
    }

    @Test
    void unitCapAppliesToOriginalUtf16WithoutTruncation() {
        assertTrue(CopySourcePayload.encode("x".repeat(4095)).isPresent());
        assertTrue(CopySourcePayload.encode("x".repeat(4096)).isPresent());
        assertFalse(CopySourcePayload.encode("x".repeat(4097)).isPresent());
        assertTrue(CopySourcePayload.encode("x".repeat(4094) + "\uD83D\uDE00").isPresent());
        assertFalse(CopySourcePayload.encode("x".repeat(4095) + "\uD83D\uDE00").isPresent());
        assertTrue(CopySourcePayload.encode("\uD83D\uDE00".repeat(2048)).isPresent());
        assertEquals(16384, CopySourcePayload.encode("\u0800".repeat(4096)).orElseThrow().length());
        assertFalse(CopySourcePayload.encode("x".repeat(4096) + "\u0000").isPresent());
    }

    @Test
    void forbiddenC0AndUnpairedSurrogatesOmitTheCarrier() {
        for (char unit = 0; unit < 0x20; unit++) {
            if (unit != '\t' && unit != '\n' && unit != '\r') {
                assertFalse(CopySourcePayload.encode("a" + unit + "b").isPresent());
            }
        }
        assertFalse(CopySourcePayload.encode("\uD800").isPresent());
        assertFalse(CopySourcePayload.encode("\uDC00").isPresent());
        assertFalse(CopySourcePayload.encode("\uD800x").isPresent());
        assertFalse(CopySourcePayload.encode("\uDC00\uD800").isPresent());
        assertFalse(CopySourcePayload.encode(null).isPresent());
    }

    @Test
    void utf8EncoderItselfRefusesReplacementEvenWithoutTheDomainGuard() {
        assertThrows(CharacterCodingException.class, () -> CopySourcePayload.strictUtf8("\uD800"));
        assertThrows(CharacterCodingException.class, () -> CopySourcePayload.strictUtf8("\uDC00"));
    }
}
