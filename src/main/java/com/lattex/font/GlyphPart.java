package com.lattex.font;

/**
 * One part of an OpenType {@code MATH} {@code GlyphAssembly} — a piece used to
 * build a stretched glyph (a tall brace, a wide arrow, a big radical) from
 * repeatable and fixed segments.
 *
 * <p>From the public OpenType MATH spec ({@code GlyphPartRecord}):
 * {@code startConnectorLength} / {@code endConnectorLength} are the maximum
 * lengths (design units) available to overlap with the neighbouring parts,
 * {@code fullAdvance} is the part's extent along the stretch axis, and
 * {@code partFlags} carries {@link #EXTENDER_FLAG} for parts that may repeat.
 */
public record GlyphPart(int glyphId, int startConnectorLength, int endConnectorLength,
                        int fullAdvance, int partFlags) {

    /** {@code partFlags} bit 0x0001: this part can be repeated to fill length. */
    public static final int EXTENDER_FLAG = 0x0001;

    /** Whether this part is a repeatable extender (vs. a fixed end/middle piece). */
    public boolean isExtender() {
        return (partFlags & EXTENDER_FLAG) != 0;
    }
}
