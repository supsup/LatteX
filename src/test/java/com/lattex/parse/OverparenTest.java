package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import org.junit.jupiter.api.Test;

/// {@code \overparen{…}} — a stretchy over-parenthesis accent (⏜, U+23DC) sized to
/// the base, riding the wide-accent machinery (same path as {@code \widehat}).
class OverparenTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static Layout layout(String latex) {
        return LayoutEngine.layout(MathParser.parse(latex), new LayoutContext(FONT, FONT.mathConstants(), 40.0));
    }

    @Test
    void overparenParsesToAWideAccent() {
        MathNode.Accent a = assertInstanceOf(MathNode.Accent.class, MathParser.parse("\\overparen{AB}"),
            "\\overparen parses to an Accent");
        assertTrue(a.stretchy(), "it is a wide/stretchy accent");
        assertTrue(!a.under(), "the parenthesis sits over the base");
    }

    @Test
    void theAccentStretchesToAWiderBase() {
        // A wider base makes the whole box wider (the accent tracks the base width) —
        // guards against a fixed-size-accent mutant.
        double narrow = layout("\\overparen{a}").width();
        double wide = layout("\\overparen{a+b+c+d}").width();
        assertTrue(wide > narrow + 40.0, "the accent grows with the base: " + wide + " vs " + narrow);
    }

    @Test
    void rendersInAlphabet() {
        String svg = LatteX.render("\\overparen{x+y}");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(svg.contains("<path"), "the parenthesis glyph draws paths");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"), "in-alphabet only");
    }
}
