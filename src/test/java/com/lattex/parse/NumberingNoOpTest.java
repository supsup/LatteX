package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/**
 * {@code \nonumber}, {@code \notag}, and {@code \label} are equation-numbering
 * control commands that LatteX does not implement (it does no automatic
 * numbering or cross-referencing). They must be INERT no-ops, not "Unknown
 * command" parse errors — otherwise they break otherwise-valid
 * align/gather/multline blocks that legitimately carry them.
 */
class NumberingNoOpTest {

    private static int pathCount(String svg) {
        int n = 0;
        for (int i = svg.indexOf("<path"); i >= 0; i = svg.indexOf("<path", i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void alignBlockWithNotagRenders() {
        // The motivating regression: an already-supported align block carrying
        // \notag threw "Unknown command" before this fix.
        assertDoesNotThrow(() -> LatteX.render(
            "\\begin{align} a &= b \\notag \\\\ c &= d \\end{align}"));
    }

    @Test
    void nonumberIsInert() {
        assertEquals(
            pathCount(LatteX.render("x = y")),
            pathCount(LatteX.render("x = y \\nonumber")),
            "\\nonumber must contribute no glyphs");
    }

    @Test
    void notagIsInert() {
        assertEquals(
            pathCount(LatteX.render("x = y")),
            pathCount(LatteX.render("x = y \\notag")),
            "\\notag must contribute no glyphs");
    }

    @Test
    void labelIsConsumedAndDropped() {
        String labelled = LatteX.render("y = 1 \\label{eq:foo}");
        assertEquals(
            pathCount(LatteX.render("y = 1")),
            pathCount(labelled),
            "\\label must contribute no glyphs");
        assertFalse(labelled.contains("eq:foo"),
            "the \\label key must not leak into the rendered output");
    }

    @Test
    void labelWithoutGroupFailsLoud() {
        // \label keeps the strictness of the \tag reader: a missing group errors.
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("z \\label"));
    }
}
