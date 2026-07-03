package com.lattex.font;

import java.util.List;

/**
 * An OpenType {@code MATH} {@code GlyphAssembly}: the recipe for building an
 * arbitrarily large stretchy glyph out of {@link GlyphPart}s when no single
 * pre-drawn variant is big enough.
 *
 * <p>{@code italicsCorrection} is the assembled glyph's italics correction
 * (design units). {@code parts} are the segments in stretch-axis order; parts
 * flagged {@link GlyphPart#isExtender()} may be repeated to reach the target
 * size, overlapping neighbours by up to their connector lengths (the actual
 * overlap math is a layout-side concern; this is the parsed recipe).
 */
public record GlyphAssembly(int italicsCorrection, List<GlyphPart> parts) {

    public GlyphAssembly {
        parts = List.copyOf(parts);
    }
}
