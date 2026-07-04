package com.lattex.api;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathParser;
import com.lattex.svg.SvgEmitter;

/**
 * LatteX — render LaTeX math to SVG.
 *
 * <p>Renders math to self-contained SVG using a bundled OFL math font
 * (STIX Two Math), emitting glyphs as inline filled {@code <path>}s in a
 * minimal, sanitizer-safe element subset. Zero runtime dependencies.
 *
 * <p><strong>Status:</strong> the full font &rarr; parse &rarr; layout &rarr; SVG
 * pipeline renders fractions, roots, sub/superscripts, big-operator limits,
 * scaled {@code \left..\right} delimiters, accents, named operators,
 * {@code \text} mode, font-variant alphabets, and 250+ symbols/relations/arrows
 * (KaTeX-gap Tier-1 &amp; Tier-2 complete). Output stays within the minimal,
 * sanitizer-safe SVG alphabet by construction.
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

    /** A plain-language accessibility label for a math tree. */
    static String describe(MathNode node) {
        return switch (node) {
            case Atom atom -> Character.toString(atom.codePoint());
            case MathList(var items) -> {
                StringBuilder sb = new StringBuilder();
                for (MathNode item : items) {
                    String part = describe(item);
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(part);
                }
                yield sb.toString();
            }
            case SupSub(var base, var sup, var sub) -> {
                String b = describe(base);
                if (sub == null) {
                    String exp = describe(sup);
                    yield exp.equals("2") ? b + " squared" : b + " to the power of " + exp;
                }
                if (sup == null) {
                    yield b + " sub " + describe(sub);
                }
                yield b + " sub " + describe(sub) + " to the power of " + describe(sup);
            }
            case Fraction(var num, var den) ->
                "the fraction " + describe(num) + " over " + describe(den);
            case Radical(var radicand, var index) -> index == null
                ? "the square root of " + describe(radicand)
                : "the " + describe(index) + "th root of " + describe(radicand);
            case Spacing _ -> "";
            case Phantom _ -> ""; // invisible: reserves space, reads as nothing
            case BigOperator(var op, var lower, var upper, _) -> {
                StringBuilder sb = new StringBuilder(operatorName(op.codePoint()));
                if (lower != null) {
                    sb.append(" from ").append(describe(lower));
                }
                if (upper != null) {
                    sb.append(" to ").append(describe(upper));
                }
                sb.append(" of");
                yield sb.toString();
            }
            case Fenced(var leftDelim, var body, var rightDelim) -> {
                String inner = describe(body);
                String open = leftDelim == Fenced.NULL_DELIMITER ? "" : delimiterName(leftDelim) + " ";
                String close = rightDelim == Fenced.NULL_DELIMITER ? "" : " " + delimiterName(rightDelim);
                yield (open + inner + close).strip();
            }
            case Accent(var command, var base, _, _, _) ->
                accentName(command) + " " + describe(base);
            case OperatorName(var name, _) -> name;
            case TextRun(var text, _) -> text;
        };
    }

    /** A spoken name for an accent/decoration command. */
    private static String accentName(String command) {
        return switch (command) {
            case "hat", "widehat" -> "hat over";
            case "bar" -> "bar over";
            case "vec", "overrightarrow" -> "vector";
            case "overleftarrow" -> "left arrow over";
            case "overleftrightarrow" -> "left-right arrow over";
            case "dot" -> "dot over";
            case "ddot" -> "double dot over";
            case "tilde", "widetilde" -> "tilde over";
            case "check" -> "check over";
            case "breve" -> "breve over";
            case "acute" -> "acute over";
            case "grave" -> "grave over";
            case "mathring" -> "ring over";
            case "overline" -> "line over";
            case "underline" -> "line under";
            default -> command + " over";
        };
    }

    /** A spoken name for a large-operator code point. */
    private static String operatorName(int codePoint) {
        return switch (codePoint) {
            case 0x2211 -> "the sum";
            case 0x222B -> "the integral";
            case 0x220F -> "the product";
            default -> Character.toString(codePoint);
        };
    }

    /** A spoken name for a delimiter code point. */
    private static String delimiterName(int codePoint) {
        return switch (codePoint) {
            case '(' -> "open paren";
            case ')' -> "close paren";
            case '[' -> "open bracket";
            case ']' -> "close bracket";
            case '{' -> "open brace";
            case '}' -> "close brace";
            case '|' -> "vertical bar";
            default -> Character.toString(codePoint);
        };
    }
}
