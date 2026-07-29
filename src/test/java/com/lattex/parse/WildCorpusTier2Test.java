package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.CommandRegistry.Descriptor;
import com.lattex.parse.CommandRegistry.GrammarKind;
import com.lattex.parse.CommandRegistry.OutputKind;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression contracts for the long-tail gaps found by the 2026-07-23 wild survey. */
class WildCorpusTier2Test {

    @Test
    void doubleBracketCommandsAreTypedOpeningAndClosingAtoms() {
        assertEquals(
            new MathList(List.of(
                new Atom(0x27E6, MathClass.OPEN),
                new Atom('x', MathClass.ORD),
                new Atom(0x27E7, MathClass.CLOSE))),
            MathParser.parse("\\llbracket x \\rrbracket"));

        assertEquals("SYMBOL", CommandRegistry.get("llbracket").handler().name());
        assertEquals("SYMBOL", CommandRegistry.get("rrbracket").handler().name());
    }

    @Test
    void arrayStandardPositionsAreConsumedBeforeTheColumnSpec() {
        String plain = "\\begin{array}{cc}a&b\\\\c&d\\end{array}";
        MathNode expected = MathParser.parse(plain);

        for (String position : List.of("t", "b", "c")) {
            String positioned = "\\begin{array}[" + position + "]{cc}a&b\\\\c&d\\end{array}";
            assertEquals(expected, MathParser.parse(positioned), positioned);
            assertEquals(LatteX.render(plain), LatteX.render(positioned), positioned);
            assertFalse(ariaOf(LatteX.render(positioned)).contains("[ " + position + " ]"),
                "the position argument must not leak into accessible content");
        }
    }

    @Test
    void arrayRejectsUnknownOrMalformedPositions() {
        for (String position : List.of("", "x", "tb")) {
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("\\begin{array}[" + position
                    + "]{c}x\\end{array}"), position);
            assertTrue(failure.getMessage().contains("position"), failure.getMessage());
        }

        MathSyntaxException unterminated = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{array}[t{c}x\\end{array}"));
        assertTrue(unterminated.getMessage().contains("position"), unterminated.getMessage());
    }

    @Test
    void unresolvedReferencesRenderVisibleKeyFreePlaceholders() {
        String refSvg = LatteX.render("\\ref{eq:private-key}");
        assertEquals(LatteX.render("??"), refSvg);
        assertEquals("? ?", ariaOf(refSvg));
        assertFalse(refSvg.contains("private-key"));

        String eqrefSvg = LatteX.render("\\eqref{eq:private-key}");
        assertEquals("open paren ? ? close paren", ariaOf(eqrefSvg));
        assertFalse(eqrefSvg.contains("private-key"));

        String eqrefMathml = LatteX.toMathML("\\eqref{eq:private-key}");
        assertTrue(eqrefMathml.contains("?"), eqrefMathml);
        assertFalse(eqrefMathml.contains("private-key"), eqrefMathml);

        for (String name : List.of("ref", "eqref")) {
            Descriptor descriptor = CommandRegistry.get(name);
            assertNotNull(descriptor, name);
            assertEquals("REFERENCE", descriptor.handler().name(), name);
            assertEquals(GrammarKind.ONE_ARGUMENT, descriptor.grammarKind(), name);
            assertEquals(OutputKind.RENDERING, descriptor.outputKind(), name);
        }
    }

    @Test
    void referencesRequireABracedKeyAndTextModeStillFailsLoud() {
        for (String source : List.of("\\ref", "\\ref x", "\\eqref", "\\eqref x")) {
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(source), source);
            assertTrue(failure.getMessage().contains("expects a {key} group"),
                failure.getMessage());
        }

        MathSyntaxException nested = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{see \\eqref{eq:x}}"));
        assertTrue(nested.getMessage().contains("Unknown command in \\text: \\eqref"),
            nested.getMessage());
    }

    private static String ariaOf(String svg) {
        int start = svg.indexOf("aria-label=\"");
        int valueStart = start + "aria-label=\"".length();
        int end = svg.indexOf('"', valueStart);
        return svg.substring(valueStart, end);
    }
}
