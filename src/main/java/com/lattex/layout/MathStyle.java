package com.lattex.layout;

/**
 * The four TeX math styles (TeXbook Appendix G): display, text, script, and
 * script-script. Each has a <em>cramped</em> variant (carried separately on
 * {@link LayoutContext}, not encoded here) in which superscripts are set lower.
 * The style selects both the glyph scale (script styles shrink via the MATH
 * {@code *PercentScaleDown} constants) and which display-vs-text MATH constant
 * a construct reads (fractions, radicals, limits).
 *
 * <p>The style-transition functions below are Appendix G's {@code C↑}/{@code C↓}
 * and the fraction numerator/denominator style step:
 * <ul>
 *   <li>{@link #scriptStyle()} — one step smaller, floor at script-script
 *       ({@code D,T → S}; {@code S,SS → SS}). Used for both scripts; the
 *       subscript additionally cramps (see {@link LayoutContext#subscript()}).</li>
 *   <li>{@link #fractionChildStyle()} — one full step down
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
    SCRIPT_SCRIPT;

    /** The style a superscript/subscript is set in — one step smaller. */
    MathStyle scriptStyle() {
        return switch (this) {
            case DISPLAY, TEXT -> SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> SCRIPT_SCRIPT;
        };
    }

    /** The style a fraction numerator/denominator is set in — one full step down. */
    MathStyle fractionChildStyle() {
        return switch (this) {
            case DISPLAY -> TEXT;
            case TEXT -> SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> SCRIPT_SCRIPT;
        };
    }

    /** Whether this is display style (selects the {@code *DisplayStyle*} MATH constants). */
    boolean isDisplay() {
        return this == DISPLAY;
    }
}
