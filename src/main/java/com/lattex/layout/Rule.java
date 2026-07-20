package com.lattex.layout;

import com.lattex.api.Color;

/**
 * A solid fill primitive in user space (y-down). In its ordinary form it is an
 * axis-aligned rectangle — the layout primitive behind fraction bars, radical
 * over-bars, and {@code \boxed} frames — emitted as a single {@code <rect x y
 * width height fill>}.
 *
 * <p>When {@link #polygon} is non-null the rule is instead a filled convex
 * <em>polygon</em> — a diagonal cancel strike ({@code \cancel} family) or an
 * arrowhead ({@code \cancelto}) — emitted as a filled {@code <path d="M…L…Z">}.
 * A polygon carries the SAME fill conventions as an axis-aligned rule (it inherits
 * the group fill, or a per-rule {@code \color} override), so it rides the exact
 * rules channel a {@code \boxed} frame does; only the emitted element differs
 * ({@code <path>} vs {@code <rect>}), and both are inside the allow-listed SVG
 * alphabet. Its {@code x}/{@code y}/{@code width}/{@code height} are set to the
 * polygon's own bounding box, so every existing consumer that measures a rule by
 * its rect metrics (the layout bounding-box accumulation) measures a polygon
 * correctly without a special case.
 *
 * @param x       left edge (rect) or polygon-bbox left, user units
 * @param y       top edge (rect) or polygon-bbox top, user units (y-down)
 * @param width   rule width (rect) or polygon-bbox width, user units
 * @param height  rule thickness (rect) or polygon-bbox height, user units
 * @param color   a per-rule {@code fill} override (from {@code \color}), or {@code
 *                null} to inherit the surrounding group fill
 * @param polygon flat {@code [x0,y0,x1,y1,…]} vertex coordinates of a filled convex
 *                polygon in user space, or {@code null} for an ordinary rectangle
 */
public record Rule(double x, double y, double width, double height, Color color,
                   double[] polygon) {

    public Rule {
        // Defensive copy so a caller's array can't mutate the rule out from under us.
        polygon = polygon == null ? null : polygon.clone();
    }

    /** A rectangular rule with an explicit color and no polygon. */
    public Rule(double x, double y, double width, double height, Color color) {
        this(x, y, width, height, color, null);
    }

    /** A rectangular rule with no color override — inherits the surrounding group fill. */
    public Rule(double x, double y, double width, double height) {
        this(x, y, width, height, null, null);
    }

    /**
     * A filled convex polygon (a cancel strike or arrowhead) with the given flat
     * {@code [x0,y0,x1,y1,…]} vertices, painted like a rule. The rect metrics are set
     * to the polygon's bounding box so the layout bbox accumulation measures it with
     * no special case.
     *
     * <p>This factory is the SHARED fail-closed gate for every polygon rule (the layout
     * engine builds strikes/arrowheads through it, and {@link Box}'s translate rebuilds
     * through it too). It rejects any non-finite vertex coordinate up front — a {@code
     * NaN}/{@code Infinity} from a degenerate geometry (e.g. a zero-length diagonal's
     * undefined unit vector) would otherwise silently poison the box metrics and the
     * emitted {@code viewBox}. We fail closed (throw), never clamp: a caller that produced
     * a non-finite vertex has a bug to surface, not a value to guess.
     *
     * @throws IllegalArgumentException if {@code pts} is null, odd-length, has fewer than
     *                                  three vertices (six coordinates), or carries any
     *                                  non-finite ({@code NaN}/{@code Infinity}) coordinate
     */
    public static Rule polygon(double[] pts, Color color) {
        if (pts == null || pts.length < 6 || pts.length % 2 != 0) {
            throw new IllegalArgumentException(
                "a polygon rule needs >=3 (x,y) vertices, got "
                + (pts == null ? "null" : pts.length + " coordinates"));
        }
        for (int i = 0; i < pts.length; i++) {
            if (!Double.isFinite(pts[i])) {
                throw new IllegalArgumentException(
                    "a polygon rule needs finite vertex coordinates, got " + pts[i]
                    + " at index " + i);
            }
        }
        double minX = pts[0];
        double minY = pts[1];
        double maxX = pts[0];
        double maxY = pts[1];
        for (int i = 0; i < pts.length; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        return new Rule(minX, minY, maxX - minX, maxY - minY, color, pts);
    }

    /** This rule painted {@code c}, but only if it has no color yet (inner wins). */
    public Rule paintedWith(Color c) {
        return color == null ? new Rule(x, y, width, height, c, polygon) : this;
    }

    /** True when this rule is a filled polygon (a cancel strike/arrowhead), not a rect. */
    public boolean isPolygon() {
        return polygon != null;
    }
}
