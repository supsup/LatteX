package com.lattex.font;

/**
 * The four per-glyph {@code MathKern} corners from the OpenType {@code MATH}
 * {@code MathKernInfoRecord}. Any corner may be {@code null} when the glyph has
 * no kern staircase for that corner.
 *
 * <p>Corners are named by attachment position: {@code topRight} kerns a
 * superscript following the glyph, {@code bottomRight} a subscript following it,
 * and the {@code *Left} corners the analogous scripts preceding the glyph
 * (used with right-to-left runs / preceding-script layouts).
 */
public record MathKernInfo(MathKern topRight, MathKern topLeft,
                           MathKern bottomRight, MathKern bottomLeft) {
}
