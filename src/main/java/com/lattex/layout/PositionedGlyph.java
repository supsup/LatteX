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
 * @param unmappedCodePoint the author code point the font had NO glyph for, or
 *                  {@link #MAPPED} when this glyph resolved normally. Set only where a
 *                  layout site asked the font for an AUTHOR-supplied character and got
 *                  glyph id 0 ({@code .notdef}) back — see the note below on why this is
 *                  a component of its own rather than a reuse of {@code sourceCodePoint}.
 *
 * <p><strong>Why {@code unmappedCodePoint} is separate from {@code sourceCodePoint}.</strong>
 * They look like the same datum and are not. {@code sourceCodePoint} is <em>threading
 * identity</em>: {@code SvgEmitter.glyphmap} keys the {@code data-lx-glyphmap} sidecar off
 * it and documents text-mode/operator-name letters as {@link #NO_SOURCE} so they never
 * thread. Populating it at those sites to carry an unmapped character would therefore
 * change the sidecar on MAPPED input too — a visible output change, on the majority path,
 * to report a minority failure. {@code unmappedCodePoint} is <em>defect identity</em>: it
 * is {@link #MAPPED} on every glyph that resolved, so it cannot move any existing output.
 */
public record PositionedGlyph(int glyphId, double originX, double baselineY, double scale,
                              Color color, int sourceCodePoint, int fenceDepth,
                              int unmappedCodePoint) {

    /** A glyph with no source token — a construction glyph (not an author atom). */
    public static final int NO_SOURCE = -1;

    /** A glyph with no precedence depth — a construction glyph (not an author atom). */
    public static final int NO_RANK = -1;

    /** This glyph resolved to a real font glyph — nothing to report. */
    public static final int MAPPED = -1;

    /** A fully-specified source atom glyph that resolved normally. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale,
                           Color color, int sourceCodePoint, int fenceDepth) {
        this(glyphId, originX, baselineY, scale, color, sourceCodePoint, fenceDepth, MAPPED);
    }

    /** A source atom glyph carrying its fence depth — the precedence-sidecar path. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale,
                           Color color, int sourceCodePoint) {
        this(glyphId, originX, baselineY, scale, color, sourceCodePoint, NO_RANK);
    }

    /** A glyph with no color override and no source token. */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale) {
        this(glyphId, originX, baselineY, scale, null, NO_SOURCE, NO_RANK);
    }

    /**
     * A no-source glyph carrying a fence depth — a function-word or {@code \text} letter that
     * has no threadable source token (so {@link #NO_SOURCE}) but IS visually inside a fenced
     * group, so it must carry that group's depth to light with it in the precedence cascade
     * rather than dimming as unresolved (Fixpoint F2, lattex/176). Distinguished from the
     * {@code (…, Color)} 5-arg by the {@code int} depth.
     */
    public PositionedGlyph(int glyphId, double originX, double baselineY, double scale, int fenceDepth) {
        this(glyphId, originX, baselineY, scale, null, NO_SOURCE, fenceDepth);
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
        // Carries unmappedCodePoint through: a \color-wrapped stray character is still a stray
        // character, and this rebuild is one of only two places a glyph is copied (the other is
        // Box's flattening translate). Dropping it here would silence the diagnostic for exactly
        // the coloured subset — a defect that reads as "the guard works, except sometimes".
        return color == null
            ? new PositionedGlyph(glyphId, originX, baselineY, scale, c, sourceCodePoint,
                fenceDepth, unmappedCodePoint)
            : this;
    }
}
