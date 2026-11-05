package com.lattex.api;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Exact original-input transport; independent of rendering legality and transformations. */
final class CopySourcePayload {
    static final int MAX_SOURCE_UNITS = 4096;

    private CopySourcePayload() {
    }

    static Optional<String> encode(String source) {
        if (!representable(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(strictUtf8(source)));
        } catch (CharacterCodingException malformed) {
            // Defensive even if a later refactor moves the original-input domain check.
            return Optional.empty();
        }
    }

    static byte[] strictUtf8(String source) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(source));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static boolean representable(String source) {
        if (source == null || source.length() > MAX_SOURCE_UNITS) {
            return false;
        }
        for (int index = 0; index < source.length(); index++) {
            char unit = source.charAt(index);
            if (unit < 0x20 && unit != '\t' && unit != '\n' && unit != '\r') {
                return false;
            }
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }
}
