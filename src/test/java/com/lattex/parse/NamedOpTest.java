package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.SupSub;
import org.junit.jupiter.api.Test;

/**
 * Tier-1c named-operator tests: {@code \sin \cos \lim … \operatorname{…}} parse to
 * the upright-roman {@link OperatorName} node with the right limit behaviour, and
 * render inside the LatteX minimal SVG alphabet (glyphs as {@code <path>}s, never
 * {@code <text>}/{@code <use>}). Uses the canonical pretty-printer from
 * {@link MathParserTest#pp} so trees are compared structurally.
 */
class NamedOpTest {

    // ------------------------------------------------------------------
    // Parse trees.
    // ------------------------------------------------------------------

    @Test
    void nonLimitOperatorIsRomanOpWithBesideScripts() {
        // \sin x -> a named-op followed by an ordinary variable (a two-item row).
        assertEquals("L(Op(sin) A(x,ORD))", MathParserTest.pp(MathParser.parse("\\sin x")));
        // \cos^2\theta -> scripts attach to the operator as an ordinary SupSub.
        assertEquals("SS(Op(cos),^A(2,ORD))",
            MathParserTest.pp(MathParser.parse("\\cos^2")));
    }

    @Test
    void limitTakingOperatorMarksLimits() {
        assertEquals("SS(Op(lim,lim),_L(A(x,ORD) A(U+2192,REL) A(U+221E,ORD)))",
            MathParserTest.pp(MathParser.parse("\\lim_{x\\to\\infty}")));
        assertEquals("Op(max,lim)", MathParserTest.pp(MathParser.parse("\\max")));
    }

    @Test
    void compoundNamesRenderWithWordBreak() {
        OperatorName liminf =
            assertInstanceOf(OperatorName.class, MathParser.parse("\\liminf"));
        assertEquals("lim inf", liminf.name());
        assertTrue(liminf.takesLimits());
        OperatorName projlim =
            assertInstanceOf(OperatorName.class, MathParser.parse("\\projlim"));
        assertEquals("proj lim", projlim.name());
    }

    @Test
    void operatorNameFormsAreRomanUnits() {
        OperatorName lcm =
            assertInstanceOf(OperatorName.class, MathParser.parse("\\operatorname{lcm}"));
        assertEquals("lcm", lcm.name());
        assertFalse(lcm.takesLimits(), "\\operatorname (no star) sets scripts beside");

        // \operatorname*{argmax} takes limits.
        OperatorName argmax =
            assertInstanceOf(OperatorName.class, MathParser.parse("\\operatorname*{argmax}"));
        assertEquals("argmax", argmax.name());
        assertTrue(argmax.takesLimits(), "\\operatorname* stacks limits in display");
    }

    @Test
    void limitTakerScriptsBecomeSupSub() {
        // \operatorname*{argmax}_x -> a SupSub whose base is the limit-taking op.
        MathNode node = MathParser.parse("\\operatorname*{argmax}_x");
        SupSub ss = assertInstanceOf(SupSub.class, node);
        OperatorName base = assertInstanceOf(OperatorName.class, ss.base());
        assertEquals("argmax", base.name());
        assertTrue(base.takesLimits());
    }

    @Test
    void malformedOperatorNameFailsCleanly() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\operatorname"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\operatorname{}"));
        // A non-text argument (a command) is a clean, named refusal.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\operatorname{\\alpha}"));
    }

    // ------------------------------------------------------------------
    // Render — in-alphabet, non-empty, glyph paths present.
    // ------------------------------------------------------------------

    @Test
    void namedOperatorsRenderInAlphabet() {
        for (String latex : new String[] {
                "\\lim_{x\\to\\infty} f(x)",
                "\\cos^2\\theta",
                "\\operatorname{lcm}(a,b)",
                "\\sin x",
                "\\max_{1 \\leq i \\leq n} a_i"}) {
            String svg = LatteX.render(latex);
            assertTrue(svg.contains("<path"), "expected glyph paths for " + latex);
            assertFalse(svg.contains("<text"), "must not emit <text> for " + latex);
            assertFalse(svg.contains("<use"), "must not emit <use> for " + latex);
            assertFalse(svg.contains("href"), "must not emit href for " + latex);
        }
    }
}
