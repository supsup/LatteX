package com.lattex.font;

import java.util.List;

/**
 * A single closed contour of a glyph outline: an ordered ring of
 * {@link GlyphPoint}s in font design units.
 */
public record Contour(List<GlyphPoint> points) {
    public Contour {
        points = List.copyOf(points);
    }
}
