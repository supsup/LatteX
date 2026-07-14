package com.lattex.layout;

import com.lattex.api.Color;

/**
 * A glyph placed in user space. The glyph's outline is expressed in font design
 * units (y-up); this record carries the affine placement that maps it into the
 * final SVG canvas (y-down):
 *
 * <pre>  userX = originX + scale * fontX
 *  userY = baselineY - scale * fontY   (the y-axis flip)</pre>
 *
 * @param glyphId   the font glyph id whose outline to draw
 * @param originX   pen x-origin in user units
 * @param baselineY the glyph baseline's y in user units
 * @param scale     user units per font design unit for this glyph
 * @param color     a per-glyph {@code fill} override (from {@code \color}/{@code
 *                  \textcolor}), or {@code null} to inherit the surrounding group fill
 * @param sourceCodePoint the Unicode code point of the source token this glyph came
 *                  from (an atom), or {@link #NO_SOURCE} for a construction glyph
 *                  (delimiter piece, radical surd, big-op) with no author token — used
 *                  to build the {@code data-lx-glyphmap} token-identity sidecar
 * @param fenceDepth the {@code \left..\right} / delimiter nesting depth this glyph was
 *                  laid out at (0 = outermost), or {@link #NO_RANK} for a construction
 *                  glyph — the raw depth the {@code data-lx-groupmap} precedence sidecar
 *                  inverts into an evaluation rank (deepest fenced group = rank 0). Only
 *                  FENCE nesting deepens it; scripts/fractions/radicals do not.
 */
public record PositionedGlyph(int glyphId, double originX, double baselineY, double scale,
                              Color color, int sourceCodePoint, int fenceDepth) {

    /** A glyph with no source token — a construction glyph (not an author atom). */
    public static final int NO_SOURCE = -1;

    /** A glyph with no precedence depth — a construction glyph (not an author atom). */
    public static final int NO_RANK = -1;

    /** A source atom glyph carrying its fence depth — the precedence-sidecar path. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale,
                           Color color, int sourceCodePoint) {
        this(glyphId, originX, baselineY, scale, color, sourceCodePoint, NO_RANK);
    }

    /** A glyph with no color override and no source token. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale) {
        this(glyphId, originX, baselineY, scale, null, NO_SOURCE, NO_RANK);
    }

    /** A glyph with a color override but no source token. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale, Color color) {
        this(glyphId, originX, baselineY, scale, color, NO_SOURCE, NO_RANK);
    }

    /**
     * This glyph painted {@code c} — but only if it has no color yet, so an inner
     * {@code \color} already set on the glyph wins over an outer one wrapping it.
     */
    public PositionedGlyph paintedWith(Color c) {
        return color == null
            ? new PositionedGlyph(glyphId, originX, baselineY, scale, c, sourceCodePoint, fenceDepth)
            : this;
    }
}
