package com.lattex.font;

import java.util.List;
import java.util.OptionalInt;

/**
 * An OpenType {@code MATH} {@code MathGlyphConstruction}: how one base glyph
 * stretches along an axis. It offers an ordered list of pre-drawn
 * {@link MathGlyphVariant}s (increasing size) and, optionally, a
 * {@link GlyphAssembly} for sizes beyond the largest variant.
 */
public record MathGlyphConstruction(List<MathGlyphVariant> variants, GlyphAssembly assembly) {

    public MathGlyphConstruction {
        variants = List.copyOf(variants);
    }

    /** Whether the font provides a part-assembly recipe for unbounded stretch. */
    public boolean hasAssembly() {
        return assembly != null;
    }

    /**
     * The glyph id of the smallest pre-drawn variant whose extent along the
     * stretch axis is at least {@code minSize} (design units). Empty when no
     * variant reaches {@code minSize} — the caller should then fall back to the
     * {@link #assembly()} (if any) or the largest available variant.
     */
    public OptionalInt variantAtLeast(int minSize) {
        for (MathGlyphVariant v : variants) {
            if (v.advanceMeasurement() >= minSize) {
                return OptionalInt.of(v.glyphId());
            }
        }
        return OptionalInt.empty();
    }
}
