package com.lattex.font;

import java.util.List;

/**
 * A parsed glyph outline: its contours plus the glyph bounding box, all in
 * font design units (y-up). An empty outline (no contours) denotes a glyph
 * with no visible ink, e.g. the space glyph.
 */
public record GlyphOutline(List<Contour> contours, int xMin, int yMin, int xMax, int yMax) {
    public GlyphOutline {
        contours = List.copyOf(contours);
    }

    public boolean isEmpty() {
        return contours.isEmpty();
    }
}
