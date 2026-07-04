package com.lattex.layout;

import com.lattex.font.MathConstants;
import com.lattex.font.SfntFont;

/**
 * Immutable style/font context threaded through layout — a plain {@code record}
 * passed by argument, deliberately <em>not</em> a {@code ThreadLocal} or
 * {@code ScopedValue}.
 *
 * <p>Beyond the font/metrics it carries the current TeX math {@link MathStyle}
 * and a <em>cramped</em> flag (Appendix G). Layout descends into sub-formulas by
 * deriving a child context with the appropriate style transition — scripts
 * ({@link #superscript()}/{@link #subscript()}), fraction parts
 * ({@link #numerator()}/{@link #denominator()}), and radicands ({@link #cramp()}).
 * The style also sets the glyph {@link #scale()} (script styles shrink via the
 * MATH {@code *PercentScaleDown} constants).
 *
 * @param font      the glyph/metrics source
 * @param constants the MATH constants driving script/fraction/radical placement
 * @param fontSize  the base display font size, in user units (px at 1:1)
 * @param style     the current math style
 * @param cramped   whether the current style is cramped
 */
public record LayoutContext(SfntFont font, MathConstants constants, double fontSize,
                            MathStyle style, boolean cramped) {

    /**
     * Entry-point context: display style, un-cramped, at the given size.
     */
    public LayoutContext(SfntFont font, MathConstants constants, double fontSize) {
        this(font, constants, fontSize, MathStyle.DISPLAY, false);
    }

    /** User units per font design unit at display/text size (the un-shrunk base). */
    public double baseScale() {
        return fontSize / font.unitsPerEm();
    }

    /** User units per font design unit at the current style (script styles shrink). */
    public double scale() {
        return baseScale() * switch (style) {
            case DISPLAY, TEXT -> 1.0;
            case SCRIPT -> constants.scriptPercentScaleDown() / 100.0;
            case SCRIPT_SCRIPT -> constants.scriptScriptPercentScaleDown() / 100.0;
        };
    }

    /** One math unit (1/18 em) in user units at the current style. */
    public double mu() {
        // 18mu = 1em; an em at the current style is scale()·unitsPerEm user units.
        return scale() * font.unitsPerEm() / 18.0;
    }

    private LayoutContext with(MathStyle newStyle, boolean newCramped) {
        return new LayoutContext(font, constants, fontSize, newStyle, newCramped);
    }

    /** Context for a superscript: one style smaller, cramping inherited. */
    public LayoutContext superscript() {
        return with(style.scriptStyle(), cramped);
    }

    /** Context for a subscript: one style smaller, always cramped. */
    public LayoutContext subscript() {
        return with(style.scriptStyle(), true);
    }

    /** Context for a fraction numerator: one style down, cramping inherited. */
    public LayoutContext numerator() {
        return with(style.fractionChildStyle(), cramped);
    }

    /** Context for a fraction denominator: one style down, always cramped. */
    public LayoutContext denominator() {
        return with(style.fractionChildStyle(), true);
    }

    /** Context forcing display style ({@code \cfrac}, {@code \dbinom}), cramping inherited. */
    public LayoutContext displayStyle() {
        return with(MathStyle.DISPLAY, cramped);
    }

    /** Context forcing text style ({@code \tbinom}), cramping inherited. */
    public LayoutContext textStyle() {
        return with(MathStyle.TEXT, cramped);
    }

    /** Context for a radicand: same style, cramped. */
    public LayoutContext cramp() {
        return with(style, true);
    }

    /** Context for a radical degree/index: script-script style, cramped. */
    public LayoutContext radicalDegree() {
        return with(MathStyle.SCRIPT_SCRIPT, true);
    }
}
