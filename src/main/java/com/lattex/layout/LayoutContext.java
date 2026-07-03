package com.lattex.layout;

import com.lattex.font.MathConstants;
import com.lattex.font.SfntFont;

/**
 * Immutable style/font context threaded through layout — a plain {@code record}
 * passed by argument, deliberately <em>not</em> a {@code ThreadLocal} or
 * {@code ScopedValue}.
 *
 * @param font       the glyph/metrics source
 * @param constants  the MATH constants driving script placement
 * @param fontSize   the base display font size, in user units (px at 1:1)
 */
public record LayoutContext(SfntFont font, MathConstants constants, double fontSize) {

    /** User units per font design unit at the base size. */
    public double baseScale() {
        return fontSize / font.unitsPerEm();
    }

    /** The scale factor for a first-level script, per {@code scriptPercentScaleDown}. */
    public double scriptScale() {
        return baseScale() * constants.scriptPercentScaleDown() / 100.0;
    }
}
