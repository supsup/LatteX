package com.lattex.svg;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.PositionedGlyph;
import com.lattex.layout.Rule;
import java.util.Locale;

/**
 * Emits a self-contained SVG document from a {@link Layout}, using ONLY the
 * named minimal element/attribute subset (CONTRIBUTING §5): {@code svg},
 * {@code g}, {@code path}, {@code rect}. Glyphs are inline filled {@code <path>}
 * — never {@code <text>}, {@code <use>}, or {@code <defs>}. This alphabet is
 * already inside the downstream sanitizer allow-list, so LatteX SVG passes
 * unchanged.
 *
 * <p>Each glyph's path data is in font design units; a per-path
 * {@code transform="translate(originX,baselineY) scale(s,-s)"} scales it to the
 * display size and flips the y-axis (font is y-up, SVG is y-down). Fraction and
 * radical bars are emitted as filled {@code <rect>}s (already in user space).
 */
public final class SvgEmitter {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    /** Small margin (user units) so glyph ink is not flush against the edge. */
    private static final double MARGIN = 2.0;
    private static final String GLYPH_FILL = "#000000";

    private SvgEmitter() {
    }

    /** Emits the SVG document for a laid-out formula. */
    public static String emit(Layout layout, SfntFont font, String ariaLabel) {
        double vbX = layout.minX() - MARGIN;
        double vbY = layout.minY() - MARGIN;
        double vbW = layout.width() + 2 * MARGIN;
        double vbH = layout.height() + 2 * MARGIN;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"").append(SVG_NS).append("\"")
            .append(" viewBox=\"")
            .append(num(vbX)).append(' ').append(num(vbY)).append(' ')
            .append(num(vbW)).append(' ').append(num(vbH)).append("\"")
            .append(" width=\"").append(num(vbW)).append("\"")
            .append(" height=\"").append(num(vbH)).append("\"")
            .append(" role=\"img\"")
            .append(" aria-label=\"").append(escape(ariaLabel)).append("\">\n");

        svg.append("  <g fill=\"").append(GLYPH_FILL).append("\">\n");
        for (PositionedGlyph g : layout.glyphs()) {
            String d = GlyphPath.toPathData(font.outline(g.glyphId()));
            if (d.isEmpty()) {
                continue; // whitespace / inkless glyph
            }
            svg.append("    <path d=\"").append(d).append("\"")
                .append(" transform=\"translate(")
                .append(num(g.originX())).append(' ').append(num(g.baselineY()))
                .append(") scale(").append(num(g.scale())).append(' ').append(num(-g.scale()))
                .append(")\"/>\n");
        }
        for (Rule r : layout.rules()) {
            svg.append("    <rect x=\"").append(num(r.x())).append("\"")
                .append(" y=\"").append(num(r.y())).append("\"")
                .append(" width=\"").append(num(r.width())).append("\"")
                .append(" height=\"").append(num(r.height())).append("\"/>\n");
        }
        svg.append("  </g>\n");
        svg.append("</svg>");
        return svg.toString();
    }

    /** Locale-independent compact number: integers plain, else up to 4 dp. */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.4f", v);
        // Trim trailing zeros (and a dangling dot).
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
