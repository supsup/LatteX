package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 720cd87e, item 2: amsmath typesets a {@code \tag} label as TEXT ({@code \tag}
 * reaches {@code \maketag@@@}, an {@code \hbox} in {@code \normalfont}), so a plain label
 * is upright roman, a hyphen is a hyphen, and {@code \(..\)} / {@code $..$} re-enter
 * math. LatteX read the label as math: {@code \tag{a}} was italic, {@code \tag{C-pair}}
 * set a binary minus, {@code \tag{$*$}} drew the dollar signs, and {@code \tag{\(*\)}}
 * was refused.
 */
class TagLabelTextTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double EPS = 1e-9;

    private static Layout layout(String latex) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    private static List<Integer> glyphIds(String latex) {
        return layout(latex).glyphs().stream().map(PositionedGlyph::glyphId).toList();
    }

    @Test
    void aPlainLabelIsUprightText() {
        List<Integer> ids = glyphIds("1\\tag{a}");
        assertTrue(ids.contains(FONT.glyphId('a')), "the label's a is the upright text a");
        assertFalse(ids.contains(FONT.glyphId(0x1D44E)), "and not the math-italic a");
        assertEquals(layout("1\\tag{\\text{a}}").width(), layout("1\\tag{a}").width(), EPS);
    }

    @Test
    void aHyphenatedLabelKeepsItsHyphenAndNoBinaryGlue() {
        List<Integer> ids = glyphIds("1\\tag{C-pair}");
        assertTrue(ids.contains(FONT.glyphId('-')), "a text hyphen");
        assertFalse(ids.contains(FONT.glyphId(0x2212)), "not a math minus");
        assertEquals(layout("1\\tag{\\text{C-pair}}").width(), layout("1\\tag{C-pair}").width(), EPS);
    }

    @Test
    void mathReEntersInsideTheLabel() {
        Layout paren = assertDoesNotThrow(() -> layout("1\\tag{\\(*\\)}"));
        Layout dollar = layout("1\\tag{$*$}");
        assertEquals(dollar.width(), paren.width(), EPS, "\\(..\\) and $..$ are the same toggle");
        assertFalse(glyphIds("1\\tag{$*$}").contains(FONT.glyphId('$')),
            "the $ delimiters are mode switches, never drawn");
        assertFalse(glyphIds("1\\tag{$\\dagger$}").contains(FONT.glyphId('$')));
        assertTrue(glyphIds("1\\tag{$\\dagger$}").contains(FONT.glyphId(0x2020)));
        // Math inside the label is math: an italic F with its subscript.
        assertTrue(glyphIds("1\\tag{\\(F_n\\)}").contains(FONT.glyphId(0x1D439)));
    }

    @Test
    void theShowcaseFormulaRenders() {
        String src = "R_c=\\{r\\in\\mathcal L_c:r^2=-2,\\ (r,h)=0,\\ (r,\\mathcal F_c)=0\\}."
            + " \\tag{\\(*\\)}";
        assertDoesNotThrow(() -> LatteX.render(src));
        assertTrue(layout(src).width() > layout(src.replace(" \\tag{\\(*\\)}", "")).width());
    }

    @Test
    void rowTagsAreTextToo() {
        List<Integer> ids = glyphIds("\\begin{align}1&=1\\tag{a}\\end{align}");
        assertTrue(ids.contains(FONT.glyphId('a')));
        assertFalse(ids.contains(FONT.glyphId(0x1D44E)));
    }

    @Test
    void labelsTheTextReaderCannotTakeStillRender() {
        // amsmath expands \ref in the text label; LatteX's text mode does not run commands,
        // so these fall back to the math reading they had before rather than start failing.
        assertDoesNotThrow(() -> LatteX.render("x\\tag{\\ref{ord:support}.1}"));
        assertDoesNotThrow(() -> LatteX.render("x\\tag{\\ast}"));
        assertDoesNotThrow(() -> LatteX.render("x\\tag1"));
        assertEquals(layout("x\\tag{1}").width(), layout("x\\tag1").width(), EPS);
    }
}
