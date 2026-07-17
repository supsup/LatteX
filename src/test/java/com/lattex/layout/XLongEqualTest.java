package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import org.junit.jupiter.api.Test;

/// {@code \xlongequal} — an extensible labelled DOUBLE-equals. Unlike the glyph-shaft
/// {@code \x...} arrows, U+003D has no horizontal MATH construction, so the shaft is
/// drawn as two in-alphabet rules that stretch to the label width.
class XLongEqualTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = 40.0;

    private static Layout layout(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex), new LayoutContext(FONT, FONT.mathConstants(), SIZE));
    }

    @Test
    void parsesToAnXArrowWithLongequalKind() {
        MathNode.XArrow xa = assertInstanceOf(MathNode.XArrow.class,
            MathParser.parse("\\xlongequal{d}"), "\\xlongequal parses to an XArrow");
        assertEquals(MathNode.XArrowKind.LONGEQUAL, xa.kind());
    }

    @Test
    void shaftIsTwoRulesThatStretchToTheLabel() {
        // Exactly two rules form the double line (no glyph shaft), and a longer
        // label stretches them — guards against a fixed-length or single-rule mutant.
        Layout shortL = layout("\\xlongequal{x}");
        long rulesShort = shortL.rules().stream().filter(r -> r.width() > r.height() * 4).count();
        assertEquals(2, rulesShort, "the double line is two horizontal rules");
        double narrow = shortL.width();
        double wide = layout("\\xlongequal{xxxxxxxxxxxx}").width();
        assertTrue(wide > narrow + SIZE, "a long label stretches the double line: " + wide + " vs " + narrow);
    }

    @Test
    void aboveAndBelowLabelsBothRender() {
        // \xlongequal[below]{above}: both labels present increases the drawn glyph paths.
        String one = LatteX.render("\\xlongequal{a}");
        String two = LatteX.render("\\xlongequal[b]{a}");
        int paths1 = one.split("<path", -1).length;
        int paths2 = two.split("<path", -1).length;
        assertTrue(paths2 > paths1, "the below label adds glyph paths");
    }

    @Test
    void rendersInAlphabet() {
        String svg = LatteX.render("A \\xlongequal{\\text{def}} B");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(svg.contains("<rect"), "the double line draws rects");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"), "in-alphabet only");
    }
}
