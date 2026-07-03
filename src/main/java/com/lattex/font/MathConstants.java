package com.lattex.font;

/**
 * The full OpenType {@code MATH} table {@code MathConstants} sub-table.
 *
 * <p>Written from the public <em>OpenType MATH table</em> specification
 * ({@code MathConstants}). Each field is in font design units unless it is a
 * percentage ({@code *PercentScaleDown} / {@code radicalDegreeBottomRaisePercent},
 * a plain {@code int16} percentage) or a minimum-height threshold
 * ({@code delimitedSubFormulaMinHeight} / {@code displayOperatorMinHeight},
 * a {@code uint16} in design units).
 *
 * <p>The spec models most of these as {@code MathValueRecord} (an {@code int16}
 * value plus an optional device-table offset for hinting at specific ppem
 * sizes). LatteX renders to resolution-independent SVG, so only the {@code value}
 * is captured; the device table is intentionally dropped.
 *
 * <p>Fields appear here in the exact order they are laid out in the table, so
 * this record doubles as the wire-format map. See {@code SfntFont#readMathConstants}.
 */
public record MathConstants(
        int scriptPercentScaleDown,
        int scriptScriptPercentScaleDown,
        int delimitedSubFormulaMinHeight,
        int displayOperatorMinHeight,
        int mathLeading,
        int axisHeight,
        int accentBaseHeight,
        int flattenedAccentBaseHeight,
        int subscriptShiftDown,
        int subscriptTopMax,
        int subscriptBaselineDropMin,
        int superscriptShiftUp,
        int superscriptShiftUpCramped,
        int superscriptBottomMin,
        int superscriptBaselineDropMax,
        int subSuperscriptGapMin,
        int superscriptBottomMaxWithSubscript,
        int spaceAfterScript,
        int upperLimitGapMin,
        int upperLimitBaselineRiseMin,
        int lowerLimitGapMin,
        int lowerLimitBaselineDropMin,
        int stackTopShiftUp,
        int stackTopDisplayStyleShiftUp,
        int stackBottomShiftDown,
        int stackBottomDisplayStyleShiftDown,
        int stackGapMin,
        int stackDisplayStyleGapMin,
        int stretchStackTopShiftUp,
        int stretchStackBottomShiftDown,
        int stretchStackGapAboveMin,
        int stretchStackGapBelowMin,
        int fractionNumeratorShiftUp,
        int fractionNumeratorDisplayStyleShiftUp,
        int fractionDenominatorShiftDown,
        int fractionDenominatorDisplayStyleShiftDown,
        int fractionNumeratorGapMin,
        int fractionNumDisplayStyleGapMin,
        int fractionRuleThickness,
        int fractionDenominatorGapMin,
        int fractionDenomDisplayStyleGapMin,
        int skewedFractionHorizontalGap,
        int skewedFractionVerticalGap,
        int overbarVerticalGap,
        int overbarRuleThickness,
        int overbarExtraAscender,
        int underbarVerticalGap,
        int underbarRuleThickness,
        int underbarExtraDescender,
        int radicalVerticalGap,
        int radicalDisplayStyleVerticalGap,
        int radicalRuleThickness,
        int radicalExtraAscender,
        int radicalKernBeforeDegree,
        int radicalKernAfterDegree,
        int radicalDegreeBottomRaisePercent) {
}
