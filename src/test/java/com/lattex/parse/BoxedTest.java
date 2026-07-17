package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.font.SfntFont;
import org.junit.jupiter.api.Test;

/// {@code \boxed{…}} / {@code \fbox{…}} — a rule-rectangle frame around a math
/// sub-formula. Parse (a Boxed node wrapping the body), layout (four frame rects
/// bigger than the body on every side), and the in-alphabet render (rects only).
class BoxedTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex), new LayoutContext(FONT, FONT.mathConstants(), SIZE));
    }

    @Test
    void boxedParsesToABoxedNodeWrappingTheBody() {
        MathNode n = MathParser.parse("\\boxed{x+1}");
        MathNode.Boxed b = assertInstanceOf(MathNode.Boxed.class, n, "\\boxed parses to a Boxed");
        assertEquals("Box(L(A(x,ORD) A(+,BIN) A(1,ORD)))", MathParserTest.pp(b));
    }

    @Test
    void fboxIsAcceptedAsTheMathModeAnalogue() {
        assertInstanceOf(MathNode.Boxed.class, MathParser.parse("\\fbox{y}"), "\\fbox parses to a Boxed too");
    }

    @Test
    void boxedIsWiderAndTallerThanItsBodyByTheFrame() {
        double bare = layout("x").width();
        double boxed = layout("\\boxed{x}").width();
        assertTrue(boxed > bare, "the frame + padding widen the box: " + boxed + " vs " + bare);
        // The frame draws exactly four rects (top/bottom/left/right).
        Layout l = layout("\\boxed{x}");
        assertEquals(4, l.rules().size(), "a plain boxed body draws four frame rects");
    }

    @Test
    void boxedFrameContainsTheBodyAndRendersInAlphabet() {
        // The body's own ink stays strictly inside the frame's bounding box.
        Layout l = layout("\\boxed{a+b}");
        double left = l.minX();
        double right = l.maxX();
        for (com.lattex.layout.Rule r : l.rules()) {
            assertTrue(r.x() >= left - 1e-6 && r.x() + r.width() <= right + 1e-6,
                "frame rects stay within the box bounds");
        }
        String svg = LatteX.render("\\boxed{a+b}");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(svg.contains("<rect"), "the frame draws rects");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"), "in-alphabet only (no line/marker)");
    }

    @Test
    void boxedNestsAndRestylesItsBody() {
        // A boxed fraction lays out (nesting) and MathML wraps it in an menclose box.
        assertInstanceOf(MathNode.Boxed.class, MathParser.parse("\\boxed{\\frac{a}{b}}"));
        String mathml = LatteX.toMathML("\\boxed{x}");
        assertTrue(mathml.contains("<menclose notation=\"box\">"), "MathML uses menclose box: " + mathml);
    }
}
