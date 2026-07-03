package com.lattex.svg;

import com.lattex.font.Contour;
import com.lattex.font.GlyphOutline;
import com.lattex.font.GlyphPoint;
import java.util.List;

/**
 * Converts a TrueType {@link GlyphOutline} into an SVG path {@code d} string,
 * in the glyph's own font design units (y-up). The caller (the emitter) applies
 * the affine {@code transform} that scales and flips the path into user space.
 *
 * <p>TrueType outlines are quadratic: an off-curve point is a single Bézier
 * control point, and two consecutive off-curve points imply an on-curve point
 * at their midpoint. This builder reconstructs those implied points and emits
 * {@code M}/{@code L}/{@code Q}/{@code Z} commands. (Reference: the OpenType
 * {@code glyf} table specification.)
 */
final class GlyphPath {

    private GlyphPath() {
    }

    /** Builds the {@code d} attribute (font units) for a glyph outline. */
    static String toPathData(GlyphOutline outline) {
        StringBuilder d = new StringBuilder();
        for (Contour contour : outline.contours()) {
            appendContour(d, contour.points());
        }
        return d.toString().trim();
    }

    private static void appendContour(StringBuilder d, List<GlyphPoint> pts) {
        int n = pts.size();
        if (n == 0) {
            return;
        }

        int startIdx = firstOnCurve(pts);
        if (startIdx < 0) {
            // No on-curve points at all: every on-curve point is an implied
            // midpoint between consecutive off-curve control points.
            appendAllOffCurve(d, pts);
            return;
        }

        GlyphPoint start = pts.get(startIdx);
        moveTo(d, start);

        GlyphPoint pending = null; // a held off-curve control point
        for (int i = 1; i <= n; i++) {
            GlyphPoint cur = pts.get((startIdx + i) % n);
            if (cur.onCurve()) {
                if (pending == null) {
                    lineTo(d, cur);
                } else {
                    quadTo(d, pending, cur);
                    pending = null;
                }
            } else {
                if (pending == null) {
                    pending = cur;
                } else {
                    GlyphPoint mid = midpoint(pending, cur);
                    quadTo(d, pending, mid);
                    pending = cur;
                }
            }
        }
        // Loop ends on the start point (an on-curve point), so `pending` is
        // cleared; guard just in case a degenerate contour leaves one held.
        if (pending != null) {
            quadTo(d, pending, start);
        }
        d.append("Z ");
    }

    private static void appendAllOffCurve(StringBuilder d, List<GlyphPoint> pts) {
        int n = pts.size();
        GlyphPoint start = midpoint(pts.get(0), pts.get(n - 1));
        moveTo(d, start);
        for (int i = 0; i < n; i++) {
            GlyphPoint control = pts.get(i);
            GlyphPoint nextMid = midpoint(control, pts.get((i + 1) % n));
            quadTo(d, control, nextMid);
        }
        d.append("Z ");
    }

    private static GlyphPoint midpoint(GlyphPoint a, GlyphPoint b) {
        // Integer midpoint (font units); halves are visually negligible and
        // keep the path data compact and exact-enough for M0.
        return new GlyphPoint((a.x() + b.x()) / 2, (a.y() + b.y()) / 2, true);
    }

    private static void moveTo(StringBuilder d, GlyphPoint p) {
        d.append('M').append(p.x()).append(' ').append(p.y()).append(' ');
    }

    private static void lineTo(StringBuilder d, GlyphPoint p) {
        d.append('L').append(p.x()).append(' ').append(p.y()).append(' ');
    }

    private static void quadTo(StringBuilder d, GlyphPoint control, GlyphPoint end) {
        d.append('Q').append(control.x()).append(' ').append(control.y()).append(' ')
            .append(end.x()).append(' ').append(end.y()).append(' ');
    }

    private static int firstOnCurve(List<GlyphPoint> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i).onCurve()) {
                return i;
            }
        }
        return -1;
    }
}
