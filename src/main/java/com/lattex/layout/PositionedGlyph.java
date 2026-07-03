package com.lattex.layout;

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
 */
public record PositionedGlyph(int glyphId, double originX, double baselineY, double scale) {
}
