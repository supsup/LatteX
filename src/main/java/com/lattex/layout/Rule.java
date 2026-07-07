package com.lattex.layout;

import com.lattex.api.Color;

/**
 * A solid rectangular rule in user space (y-down), the layout primitive behind
 * fraction bars and radical over-bars. The emitter renders it as a single
 * {@code <rect x y width height fill>} — inside the allow-listed SVG alphabet.
 *
 * @param x      left edge, user units
 * @param y      top edge, user units (y-down: smaller y is higher)
 * @param width  rule width, user units
 * @param height rule thickness, user units
 * @param color  a per-rule {@code fill} override (from {@code \color}), or {@code
 *               null} to inherit the surrounding group fill
 */
public record Rule(double x, double y, double width, double height, Color color) {

    /** A rule with no color override — inherits the surrounding group fill. */
    public Rule(double x, double y, double width, double height) {
        this(x, y, width, height, null);
    }

    /** This rule painted {@code c}, but only if it has no color yet (inner wins). */
    public Rule paintedWith(Color c) {
        return color == null ? new Rule(x, y, width, height, c) : this;
    }
}
