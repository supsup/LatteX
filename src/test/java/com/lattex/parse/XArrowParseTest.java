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
}
