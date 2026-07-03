package com.lattex.layout;

import java.util.List;

/**
 * The laid-out result: positioned glyphs and rules (fraction/radical bars) plus
 * the tight bounding box that encloses their ink, all in user space (y-down).
 * The bounding box seeds the SVG {@code viewBox} and the page's width/height.
 */
public record Layout(List<PositionedGlyph> glyphs, List<Rule> rules,
                     double minX, double minY, double maxX, double maxY) {
    public Layout {
        glyphs = List.copyOf(glyphs);
        rules = List.copyOf(rules);
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }
}
