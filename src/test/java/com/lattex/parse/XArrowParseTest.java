package com.lattex.parse;

import static com.lattex.parse.MathParserTest.pp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Parse shape of the extensible labelled arrows — amsmath's
 * {@code xrightarrow[below]{above}} and {@code xleftarrow} (written here without
 * the backslash). The optional {@code [...]} argument comes BEFORE the required
 * {@code {...}} argument, and a {@code '['} is only taken as the optional label
 * when a matching {@code ']'} closes it.
 */
class XArrowParseTest {

    @Test
    void xrightarrowWithOnlyTheRequiredLabel() {
        assertEquals("XArr[R](^A(f,ORD))", pp(MathParser.parse("\\xrightarrow{f}")));
    }

    @Test
    void xrightarrowWithOptionalBelowLabel() {
        assertEquals("XArr[R](^A(f,ORD),_A(g,ORD))",
            pp(MathParser.parse("\\xrightarrow[g]{f}")));
    }

    @Test
    void xleftarrowMirrors() {
        assertEquals("XArr[L](^A(f,ORD),_A(g,ORD))",
            pp(MathParser.parse("\\xleftarrow[g]{f}")));
    }

    @Test
    void optionalLabelMayBeAMultiTokenList() {
        MathNode node = MathParser.parse("\\xrightarrow[T \\to \\infty]{n \\to \\infty}");
        MathNode.XArrow xa = (MathNode.XArrow) node;
        assertTrue(xa.above() instanceof MathNode.MathList, "above is a list");
        assertTrue(xa.below() instanceof MathNode.MathList, "below is a list");
    }

    @Test
    void emptyOptionalBracketReadsAsNoBelowLabel() {
        MathNode.XArrow xa = (MathNode.XArrow) MathParser.parse("\\xrightarrow[]{f}");
        assertNull(xa.below(), "an empty [] carries no below label");
    }

    @Test
    void unclosedBracketIsNotConsumedAsTheOptionalLabel() {
        // The '[' has no matching ']' at brace depth 0 (the ']' is inside a group),
        // so it is NOT the optional argument: it becomes the required label itself
        // and the rest parses as ordinary trailing content.
        MathNode node = MathParser.parse("\\xrightarrow[a{b]}");
        MathNode.MathList row = (MathNode.MathList) node;
        MathNode.XArrow xa = (MathNode.XArrow) row.items().get(0);
        assertNull(xa.below(), "no optional label was consumed");
        assertEquals("A([,OPEN)", pp(xa.above()), "the bare '[' became the required label");
    }

    @Test
    void bracketAfterTheRequiredLabelIsOrdinaryContent() {
        // Once the required {above} is read, a following [...] is plain math.
        assertEquals("L(XArr[R](^A(f,ORD)) A([,OPEN) A(0,ORD) A(,,PUNCT) A(1,ORD) A(],CLOSE))",
            pp(MathParser.parse("\\xrightarrow{f}[0,1]")));
    }

    @Test
    void missingRequiredLabelFailsCleanly() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\xrightarrow"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\xrightarrow[g]"));
    }

    @Test
    void controlSpacePaddedCorpusShapeParses() {
        // The wild-corpus shape "\\xrightarrow{\\ f\\ }" (control-space padding).
        MathNode.XArrow xa = (MathNode.XArrow) MathParser.parse("\\xrightarrow{\\ f\\ }");
        assertTrue(xa.above() instanceof MathNode.MathList, "spaces + letter");
    }

    @Test
    void theExtendedArrowFamilyEachMapsToItsOwnKind() {
        // Plan lattex-xarrow-family (L4): every \\x... command routes to the right shaft
        // kind — the one source of truth the parser, layout, and MathML all read.
        record Case(String cmd, MathNode.XArrowKind kind) {}
        for (Case c : new Case[] {
                new Case("xrightarrow", MathNode.XArrowKind.RIGHT),
                new Case("xleftarrow", MathNode.XArrowKind.LEFT),
                new Case("xleftrightarrow", MathNode.XArrowKind.LEFTRIGHT),
                new Case("xRightarrow", MathNode.XArrowKind.RIGHT_DBL),
                new Case("xLeftarrow", MathNode.XArrowKind.LEFT_DBL),
                new Case("xLeftrightarrow", MathNode.XArrowKind.LEFTRIGHT_DBL),
                new Case("xmapsto", MathNode.XArrowKind.MAPSTO),
                new Case("xhookrightarrow", MathNode.XArrowKind.HOOK_RIGHT),
                new Case("xhookleftarrow", MathNode.XArrowKind.HOOK_LEFT),
                new Case("xrightleftharpoons", MathNode.XArrowKind.RIGHTLEFTHARPOONS)}) {
            MathNode.XArrow xa = (MathNode.XArrow) MathParser.parse("\\" + c.cmd() + "{f}");
            assertEquals(c.kind(), xa.kind(), c.cmd());
            // The new arrows share the exact optional-[below]-then-{above} grammar.
            MathNode.XArrow withBelow =
                (MathNode.XArrow) MathParser.parse("\\" + c.cmd() + "[g]{f}");
            assertEquals(c.kind(), withBelow.kind());
            assertTrue(withBelow.belowLabel().isPresent(), c.cmd() + " below label");
        }
    }

    @Test
    void distinctKindsHaveDistinctShaftGlyphsAndMathml() {
        // No two kinds collide on the shaft codepoint or the MathML entity — a copy-paste
        // slip in the enum table would be caught here.
        java.util.Set<Integer> cps = new java.util.HashSet<>();
        java.util.Set<String> mml = new java.util.HashSet<>();
        for (MathNode.XArrowKind k : MathNode.XArrowKind.values()) {
            assertTrue(cps.add(k.codePoint()), "duplicate shaft codepoint: " + k);
            assertTrue(mml.add(k.mathmlEntity()), "duplicate mathml entity: " + k);
            assertTrue(k.a11yName() != null && !k.a11yName().isBlank(), "a11y name: " + k);
        }
    }
}