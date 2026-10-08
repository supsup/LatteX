package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lattex.api.LatteX;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Table-driven MathML coverage for EVERY accent in {@link Symbols#ACCENTS}, not just
 * {@code \hat}.
 *
 * <p>Filed from plan 8f26b646 (NEEDS-FIX PROJECT/lattex 950, mutation testing at
 * 85751bfd): {@code MathMLTest#accentsEmitTheRightCharacter} asserts exactly one
 * non-rule accent's emitted character (\hat, plus the two rule decorations). A
 * mutation that hard-wires every OTHER non-rule accent to a caret stayed GREEN across
 * the whole 997-test suite -- {@code \tilde{a}} could silently render with a
 * circumflex, and nothing here would have caught it.
 *
 * <p>This test's {@code expected} table is a small, HAND-TRANSCRIBED oracle,
 * independent of {@link Symbols#ACCENTS}'s own {@code codePoint}/{@code under}
 * fields (an oracle read from the same table the emitter reads would agree with a
 * broken emitter as readily as a correct one — see the "equality decays to
 * agreement" failure mode). The closing assertion cross-checks this table's key set
 * against {@code Symbols.ACCENTS} itself, so a newly added accent with no MathML
 * expectation here fails loudly and names the missing command, instead of silently
 * not being covered.
 */
class AccentMathMLCoverageTest {

    private static final String MATH_OPEN =
        "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">";
    private static final String MATH_CLOSE = "</math>";

    // Mirrors LatteX's own private RULE_OVER_CODE_POINT / RULE_UNDER_CODE_POINT: the two
    // rule decorations (\overline / \\underline) carry the Accent.RULE sentinel in
    // AccentSpec, not a real glyph code point, so their MathML character is named here
    // by hand instead of read off the table.
    private static final int RULE_OVER_CODE_POINT = 0x203E;  // OVERLINE
    private static final int RULE_UNDER_CODE_POINT = 0x005F; // LOW LINE

    /** One accent's expected MathML {@code <mo>} content and over/under placement. */
    private record Expected(int codePoint, boolean under) {
    }

    @Test
    void everyAccentEmitsItsOwnMathMLCharacterOverOrUnderTheBase() {
        Map<String, Expected> expected = Map.ofEntries(
            // Narrow accents (natural size).
            Map.entry("hat", new Expected(0x0302, false)),
            Map.entry("bar", new Expected(0x0304, false)),
            Map.entry("vec", new Expected(0x20D7, false)),
            Map.entry("dot", new Expected(0x0307, false)),
            Map.entry("ddot", new Expected(0x0308, false)),
            Map.entry("tilde", new Expected(0x0303, false)),
            Map.entry("check", new Expected(0x030C, false)),
            Map.entry("breve", new Expected(0x0306, false)),
            Map.entry("acute", new Expected(0x0301, false)),
            Map.entry("grave", new Expected(0x0300, false)),
            Map.entry("mathring", new Expected(0x030A, false)),
            // Wide / stretchy accents (sized to the base width).
            Map.entry("widehat", new Expected(0x0302, false)),
            Map.entry("widetilde", new Expected(0x0303, false)),
            Map.entry("overrightarrow", new Expected(0x20D7, false)),
            Map.entry("overleftarrow", new Expected(0x20D6, false)),
            Map.entry("overleftrightarrow", new Expected(0x20E1, false)),
            // The arrows below (plan b3f198f2): COMBINING LEFT / RIGHT ARROW BELOW and
            // COMBINING LEFT RIGHT ARROW BELOW, under the base.
            Map.entry("underleftarrow", new Expected(0x20EE, true)),
            Map.entry("underrightarrow", new Expected(0x20EF, true)),
            Map.entry("underleftrightarrow", new Expected(0x034D, true)),
            // Stretchy over/under-parenthesis accents.
            Map.entry("overparen", new Expected(0x23DC, false)),
            Map.entry("underparen", new Expected(0x23DD, true)),
            // Line decorations (drawn as a rule, not a glyph).
            Map.entry("overline", new Expected(RULE_OVER_CODE_POINT, false)),
            Map.entry("underline", new Expected(RULE_UNDER_CODE_POINT, true)));

        expected.forEach((name, want) -> {
            String mo = "<mo>" + Character.toString(want.codePoint()) + "</mo>";
            String wantXml = want.under()
                ? MATH_OPEN + "<munder accentunder=\"true\"><mi>a</mi>" + mo + "</munder>"
                    + MATH_CLOSE
                : MATH_OPEN + "<mover accent=\"true\"><mi>a</mi>" + mo + "</mover>" + MATH_CLOSE;
            assertEquals(wantXml, LatteX.toMathML("\\" + name + "{a}"),
                "\\" + name + " must emit U+" + Integer.toHexString(want.codePoint()).toUpperCase()
                    + " as its own MathML <mo>, not a shared or hardcoded character");
        });

        // Tripwire: a newly added accent with no expectation above must fail LOUDLY and
        // name itself, rather than silently going unchecked by this test.
        assertEquals(expected.keySet(), Symbols.ACCENTS.keySet(),
            "this table and Symbols.ACCENTS must not drift apart -- a newly added accent "
                + "needs its own expected MathML code point here");
    }
}
