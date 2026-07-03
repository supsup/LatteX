package com.lattex.font;

import java.util.List;

/**
 * A single OpenType {@code MATH} {@code MathKern} table: the sub/superscript
 * kerning staircase along one corner of a glyph.
 *
 * <p>From the public OpenType MATH spec: {@code correctionHeights} holds
 * {@code heightCount} strictly increasing heights (design units, measured from
 * the baseline), and {@code kernValues} holds {@code heightCount + 1} kern
 * amounts — one per height band. The correction applied at a given attachment
 * height is the kern of the band that height falls into. See
 * {@link #kernAtHeight(int)}.
 */
public record MathKern(List<Integer> correctionHeights, List<Integer> kernValues) {

    public MathKern {
        correctionHeights = List.copyOf(correctionHeights);
        kernValues = List.copyOf(kernValues);
    }

    /**
     * The kern value (design units) for a script attached at {@code height}.
     *
     * <p>Per spec: find the first correction height strictly greater than
     * {@code height} and return the kern of that band; if {@code height} is at
     * or above every correction height, return the final (top) kern value.
     */
    public int kernAtHeight(int height) {
        for (int i = 0; i < correctionHeights.size(); i++) {
            if (height < correctionHeights.get(i)) {
                return kernValues.get(i);
            }
        }
        return kernValues.get(kernValues.size() - 1);
    }
}
