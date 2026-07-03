package com.lattex.layout;

/**
 * A solid rectangular rule in user space (y-down), the layout primitive behind
 * fraction bars and radical over-bars. The emitter renders it as a single
 * {@code <rect x y width height fill>} — inside the allow-listed SVG alphabet.
 *
 * @param x      left edge, user units
 * @param y      top edge, user units (y-down: smaller y is higher)
 * @param width  rule width, user units
 * @param height rule thickness, user units
 */
public record Rule(double x, double y, double width, double height) {
}
