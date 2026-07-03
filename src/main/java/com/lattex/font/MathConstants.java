package com.lattex.font;

/**
 * The subset of the OpenType {@code MATH} table's {@code MathConstants} that
 * the walking-skeleton superscript layout needs.
 *
 * <p>Fields follow the OpenType MATH spec:
 * <ul>
 *   <li>{@code scriptPercentScaleDown} — percentage the font size is scaled
 *       down for the first script level (a plain {@code int16} percentage).</li>
 *   <li>{@code superscriptShiftUp} — the {@code value} of the
 *       {@code superscriptShiftUp} {@code MathValueRecord}, in font design
 *       units: the standard shift-up of a superscript baseline above the
 *       main baseline.</li>
 * </ul>
 *
 * <p>Only the two constants required by {@code x^2} are captured; the rest of
 * the table is deliberately left unparsed for M0.
 */
public record MathConstants(int scriptPercentScaleDown, int superscriptShiftUp) {
}
