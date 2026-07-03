package com.lattex.font;

/**
 * One pre-drawn size variant of a stretchy glyph, from an OpenType {@code MATH}
 * {@code MathGlyphVariantRecord}.
 *
 * <p>{@code advanceMeasurement} is the variant's extent along the stretch axis
 * (height for vertical constructions, width for horizontal), in design units —
 * the value used to pick the smallest variant that is "at least this big".
 */
public record MathGlyphVariant(int glyphId, int advanceMeasurement) {
}
