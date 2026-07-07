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
    /** The default glyph fill — opaque black. */
    private static final String GLYPH_FILL = "#000000";

    private SvgEmitter() {
    }

    /** Emits the SVG document for a laid-out formula using the default black fill. */
    public static String emit(Layout layout, SfntFont font, String ariaLabel) {
        return emit(layout, font, ariaLabel, GLYPH_FILL);
    }

    /**
     * Emits the SVG document for a laid-out formula with an explicit fill value.
     *
     * @param fill the SVG {@code fill} value — MUST already be an allow-listed
     *             literal ({@code currentColor} or a {@code #rrggbb} hex), i.e. a
     *             {@link com.lattex.api.Color#svgValue()}. This is a fill VALUE
     *             only; it never introduces a new element or attribute, so the
     *             minimal-alphabet contract is preserved.
     */
    public static String emit(Layout layout, SfntFont font, String ariaLabel, String fill) {
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

        emitInner(svg, layout, font, fill, 0.0, 0.0);
        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * Emits the INNER markup for a laid-out formula as an embeddable fragment —
     * the {@code <g>/<path>/<rect>} elements WITHOUT the surrounding {@code <svg>}
     * wrapper, viewBox, or aria — using the default black fill.
     *
     * @see #emitFragment(Layout, SfntFont, String)
     */
    public static String emitFragment(Layout layout, SfntFont font) {
        return emitFragment(layout, font, GLYPH_FILL);
    }

    /**
     * Emits the INNER markup for a laid-out formula as an embeddable fragment: the
     * same positioned {@code <g>/<path>/<rect>} elements {@link #emit} produces, but
     * WITHOUT the {@code <svg>} wrapper / viewBox / aria, and re-based so the
     * fragment's local origin {@code (0,0)} is the LEFT END OF THE BASELINE — x=0 at
     * the left ink edge ({@code minX}), y=0 at the baseline (already the layout
     * origin). Coordinates are shifted by {@code -minX} in x (none in y); the glyph
     * {@code <path d>} data is unchanged, so the fragment paints glyphs and rules
     * IDENTICALLY to {@link #emit} (no forked emit path). Stays within the same
     * {@code g/path/rect} alphabet.
     *
     * @param fill the default group {@code fill} value (an allow-listed literal); a
     *             per-glyph {@code \color}/{@code \textcolor} override still wins.
     */
    public static String emitFragment(Layout layout, SfntFont font, String fill) {
        StringBuilder out = new StringBuilder();
        // Re-base x so the left ink edge lands at x=0; the baseline is already y=0.
        emitInner(out, layout, font, fill, -layout.minX(), 0.0);
        return out.toString();
    }

    /**
     * The shared inner-element emission consumed by BOTH {@link #emit} (wrapped in
     * {@code <svg>}) and {@link #emitFragment} (bare) — one producer, so the full
     * document and the embeddable fragment cannot diverge in how they paint glyphs
     * and rules. Each glyph/rule is translated by {@code (dx, dy)} on top of its
     * laid-out position; the per-glyph outline {@code d} and every {@code fill}
     * (default group fill + {@code \color}/{@code \textcolor} overrides) are emitted
     * unchanged.
     */
    static void emitInner(StringBuilder out, Layout layout, SfntFont font, String fill,
                          double dx, double dy) {
        out.append("  <g fill=\"").append(fill).append("\">\n");
        for (PositionedGlyph g : layout.glyphs()) {
            String d = GlyphPath.toPathData(font.outline(g.glyphId()));
            if (d.isEmpty()) {
                continue; // whitespace / inkless glyph
            }
            out.append("    <path d=\"").append(d).append("\"");
            if (g.color() != null) {
                // \color/\textcolor override; svgValue() is an allow-listed fill literal.
                out.append(" fill=\"").append(g.color().svgValue()).append("\"");
            }
            out.append(" transform=\"translate(")
                .append(num(g.originX() + dx)).append(' ').append(num(g.baselineY() + dy))
                .append(") scale(").append(num(g.scale())).append(' ').append(num(-g.scale()))
                .append(")\"/>\n");
        }
        for (Rule r : layout.rules()) {
            out.append("    <rect x=\"").append(num(r.x() + dx)).append("\"")
                .append(" y=\"").append(num(r.y() + dy)).append("\"")
                .append(" width=\"").append(num(r.width())).append("\"")
                .append(" height=\"").append(num(r.height())).append("\"");
            if (r.color() != null) {
                out.append(" fill=\"").append(r.color().svgValue()).append("\"");
            }
            out.append("/>\n");
        }
        out.append("  </g>\n");
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
