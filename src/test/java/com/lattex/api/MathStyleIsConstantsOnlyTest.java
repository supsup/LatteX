package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Discriminator for the {@link MathStyle} exported-surface contract.
 *
 * <p>MathStyle sits in the EXPORTED api package, so every method it declares is a
 * permanent public contract that consumers may call and that we may not remove.
 * It is therefore required to be <em>four constants and nothing else</em>; the
 * TeXbook style-transition rules live on {@code LayoutContext} in the layout
 * package, off the exported surface.
 *
 * <p>This test exists because that invariant ALREADY WENT FALSE ONCE WITHOUT
 * ANYONE NOTICING. A review ruling asserted MathStyle was a pure enum with no
 * methods; it actually carried three package-private TeXbook helpers, and moving
 * the enum into the exported package would have forced all three public —
 * publishing internal layout rules on the permanent API surface, invisibly, with
 * {@code module-info} byte-unchanged. Nothing in the build could have caught it.
 *
 * <p>A prose ruling cannot hold an invariant across time. This can.
 */
class MathStyleIsConstantsOnlyTest {

    /**
     * {@code values()} and {@code valueOf(String)} are implicitly declared on every
     * enum by the compiler. They are unavoidable and are not what this test guards.
     */
    private static final List<String> COMPILER_DECLARED = List.of("values", "valueOf");

    @Test
    @DisplayName("MathStyle declares no methods of its own — it is four constants")
    void mathStyleDeclaresNoMethodsOfItsOwn() {
        List<String> authored = Arrays.stream(MathStyle.class.getDeclaredMethods())
            .filter(m -> !m.isSynthetic())
            .map(Method::getName)
            .filter(n -> !COMPILER_DECLARED.contains(n))
            .sorted()
            .toList();

        assertTrue(
            authored.isEmpty(),
            () -> """
                MathStyle is on the EXPORTED api surface, where every declared method \
                becomes a permanent public contract. Found authored method(s): %s.

                If you need a style-transition rule, put it on LayoutContext (layout \
                package, private or package-private) as scriptStyle/fractionChildStyle \
                already are — NOT here. See tools/exported-api/fixtures/corrected for \
                the worked shape."""
                .formatted(authored));
    }

    @Test
    @DisplayName("MathStyle still has exactly the four TeX styles, in TeXbook order")
    void mathStyleHasExactlyFourConstantsInOrder() {
        // Guards the other direction: a test that only asserts "no methods" would
        // still pass if someone deleted a constant, which would be a far worse
        // exported-surface break than adding a method.
        assertArrayEquals(
            new MathStyle[] {
                MathStyle.DISPLAY, MathStyle.TEXT, MathStyle.SCRIPT, MathStyle.SCRIPT_SCRIPT
            },
            MathStyle.values(),
            "the four TeX math styles must remain, in TeXbook order");
    }
}
