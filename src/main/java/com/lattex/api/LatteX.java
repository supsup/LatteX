package com.lattex.api;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathParser;
import com.lattex.svg.SvgEmitter;

/**
 * LatteX — render LaTeX math to SVG.
 *
 * <p>Renders math to self-contained SVG using a bundled OFL math font
 * (STIX Two Math), emitting glyphs as inline filled {@code <path>}s in a
 * minimal, sanitizer-safe element subset. Zero runtime dependencies.
 *
 * <p><strong>Status:</strong> M0 walking skeleton — the full font &rarr; parse
 * &rarr; layout &rarr; SVG pipeline is wired end-to-end, but only for a single
 * expression class ({@code x^2}: an atom with a superscript). Breadth lands
 * across the later milestones.
 */
public final class LatteX {

    /** Default display font size, in user units (px at 1:1). */
    public static final double DISPLAY_FONT_SIZE = 40.0;

    // The bundled font is immutable and ~1.5 MB; parse it once, lazily.
    private static final class FontHolder {
        static final SfntFont FONT = SfntFont.loadBundled();
    }

    private LatteX() {
    }

    /**
     * Render a LaTeX math expression to a self-contained SVG document.
     *
     * @param latex the LaTeX math source (without surrounding {@code $} delimiters)
     * @return the SVG document
     */
    public static String render(String latex) {
        MathNode node = MathParser.parse(latex);
        SfntFont font = FontHolder.FONT;
        LayoutContext ctx = new LayoutContext(font, font.mathConstants(), DISPLAY_FONT_SIZE);
        Layout layout = LayoutEngine.layout(node, ctx);
        return SvgEmitter.emit(layout, font, describe(node));
    }

    /** A plain-language accessibility label for a math tree (M0 subset). */
    static String describe(MathNode node) {
        return switch (node) {
            case Atom atom -> Character.toString(atom.codePoint());
            case SupSub sup -> {
                String base = describe(sup.base());
                String exp = describe(sup.sup());
                yield exp.equals("2")
                    ? base + " squared"
                    : base + " to the power of " + exp;
            }
            // Exhaustive over the sealed MathNode: these kinds parse today but are
            // not laid out until S4, so render() never reaches describe() for them
            // yet. No-default cases force a real aria-label when each becomes
            // renderable.
            case MathList list -> throw describeTodo(list);
            case Fraction frac -> throw describeTodo(frac);
            case Radical rad -> throw describeTodo(rad);
            case BigOperator bigOp -> throw describeTodo(bigOp);
            case Fenced fenced -> throw describeTodo(fenced);
            case Spacing spacing -> throw describeTodo(spacing);
        };
    }

    private static UnsupportedOperationException describeTodo(MathNode node) {
        return new UnsupportedOperationException(
            "aria-label not yet implemented for " + node.getClass().getSimpleName());
    }
}
