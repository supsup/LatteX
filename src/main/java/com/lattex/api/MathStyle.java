package com.lattex.api;

/**
 * The four TeX math styles (TeXbook Appendix G): display, text, script, and
 * script-script. Each has a <em>cramped</em> variant (carried separately on
 * {@code LayoutContext}, not encoded here) in which superscripts are set lower.
 * The style selects both the glyph scale (script styles shrink via the MATH
 * {@code *PercentScaleDown} constants) and which display-vs-text MATH constant
 * a construct reads (fractions, radicals, limits).
 *
 * <p>This enum is FOUR CONSTANTS AND NOTHING ELSE, and that is a contract rather
 * than an accident: it is on the exported {@code com.lattex.api} surface, where
 * every declared method would become a permanent public commitment. The Appendix G
 * style-transition rules therefore live on {@code LayoutContext} in the
 * non-exported {@code com.lattex.layout} package, NOT here. Pinned by
 * {@code MathStyleIsConstantsOnlyTest}.
 *
 * <p>For reference, those transitions — implemented on {@code LayoutContext}, not
 * below — are Appendix G's {@code C↑}/{@code C↓} and the fraction
 * numerator/denominator step:
 * <ul>
 *   <li>{@code LayoutContext} script-style step — one step smaller, floor at script-script
 *       ({@code D,T → S}; {@code S,SS → SS}). Used for both scripts; the
 *       subscript additionally cramps (see {@code LayoutContext#subscript()}).</li>
 *   <li>{@code LayoutContext} fraction-child step — one full step down
 *       ({@code D → T → S → SS}, floor at {@code SS}). Numerator and denominator
 *       are set in this style (the denominator additionally cramps).</li>
 * </ul>
 */
public enum MathStyle {
    /** Displayed equations ({@code \[..\]}); the top-level default. */
    DISPLAY,
    /** In-line math ({@code $..$}). */
    TEXT,
    /** First-level scripts, numerators/denominators of text-style fractions. */
    SCRIPT,
    /** Second-level scripts and deeper. */
    SCRIPT_SCRIPT
}
