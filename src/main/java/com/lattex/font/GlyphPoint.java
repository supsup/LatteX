package com.lattex.font;

/**
 * A single control point of a TrueType glyph contour, in font design units
 * (y-up), tagged with whether it lies on the curve.
 *
 * <p>Off-curve points are quadratic Bézier control points; consecutive
 * off-curve points imply an on-curve point at their midpoint (the TrueType
 * "implied on-curve point" convention). See the OpenType {@code glyf} spec.
 */
public record GlyphPoint(int x, int y, boolean onCurve) {
}
