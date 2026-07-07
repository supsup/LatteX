package com.lattex.layout;

import com.lattex.api.Color;

/**
 * A glyph placed in user space. The glyph's outline is expressed in font design
 * units (y-up); this record carries the affine placement that maps it into the
 * final SVG canvas (y-down):
 *
 * <pre>  userX = originX + scale * fontX
 *  userY = baselineY - scale * fontY   (the y-axis flip)</pre>
 *
 * @param glyphId   the font glyph id whose outline to draw
 * @param originX   pen x-origin in user units
 * @param baselineY the glyph baseline's y in user units
 * @param scale     user units per font design unit for this glyph
 * @param color     a per-glyph {@code fill} override (from {@code \color}/{@code
 *                  \textcolor}), or {@code null} to inherit the surrounding group fill
 */
public record PositionedGlyph(int glyphId, double originX, double baselineY, double scale, Color color) {

    /** A glyph with no color override — inherits the surrounding group fill. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale) {
        this(glyphId, originX, baselineY, scale, null);
    }

    /**
     * This glyph painted {@code c} — but only if it has no color yet, so an inner
     * {@code \color} already set on the glyph wins over an outer one wrapping it.
     */
    public PositionedGlyph paintedWith(Color c) {
        return color == null ? new PositionedGlyph(glyphId, originX, baselineY, scale, c) : this;
    }
}
