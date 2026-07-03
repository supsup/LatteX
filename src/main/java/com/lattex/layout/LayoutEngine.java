package com.lattex.layout;

import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.SupSub;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link MathNode} tree into positioned glyph geometry.
 *
 * <p>M0 handles exactly the two shapes the skeleton needs — a baseline
 * {@link Atom} and a {@link SupSub} superscript — via an exhaustive switch. The
 * superscript placement follows the OpenType MATH model: scale the script by
 * {@code scriptPercentScaleDown} and raise its baseline by
 * {@code superscriptShiftUp} (font units → user units at the base scale). The
 * script pen advances past the base advance plus the base's italic correction.
 */
public final class LayoutEngine {

    private LayoutEngine() {
    }

    /** Lays out a math tree at the origin; returns positioned glyphs + bbox. */
    public static Layout layout(MathNode root, LayoutContext ctx) {
        List<PositionedGlyph> glyphs = new ArrayList<>();
        Bounds bounds = new Bounds();
        place(root, ctx, 0.0, 0.0, ctx.baseScale(), glyphs, bounds);
        if (bounds.empty) {
            return new Layout(glyphs, 0, 0, 0, 0);
        }
        return new Layout(glyphs, bounds.minX, bounds.minY, bounds.maxX, bounds.maxY);
    }

    /**
     * Places {@code node} with pen origin {@code (originX, baselineY)} at
     * {@code scale}, appending glyphs and growing {@code bounds}. Returns the
     * horizontal advance consumed, in user units.
     */
    private static double place(MathNode node, LayoutContext ctx,
                                double originX, double baselineY, double scale,
                                List<PositionedGlyph> glyphs, Bounds bounds) {
        SfntFont font = ctx.font();
        return switch (node) {
            case Atom atom -> {
                int gid = font.glyphId(atom.codePoint());
                glyphs.add(new PositionedGlyph(gid, originX, baselineY, scale));
                accumulate(bounds, font.outline(gid), originX, baselineY, scale);
                yield font.advanceWidth(gid) * scale;
            }
            case SupSub sup -> {
                double baseAdvance = place(sup.base(), ctx, originX, baselineY, scale, glyphs, bounds);

                double italic = 0.0;
                if (sup.base() instanceof Atom baseAtom) {
                    italic = font.italicCorrection(font.glyphId(baseAtom.codePoint())) * ctx.baseScale();
                }

                double shiftUp = ctx.constants().superscriptShiftUp() * ctx.baseScale();
                double supOriginX = originX + baseAdvance + italic;
                double supBaselineY = baselineY - shiftUp; // up = smaller y in y-down space
                double supAdvance = place(sup.sup(), ctx, supOriginX, supBaselineY,
                    ctx.scriptScale(), glyphs, bounds);

                yield baseAdvance + italic + supAdvance;
            }
        };
    }

    private static void accumulate(Bounds b, GlyphOutline outline,
                                   double originX, double baselineY, double scale) {
        if (outline.isEmpty()) {
            return;
        }
        // Map the font-unit bbox corners into user space (note the y-flip:
        // larger font-y → smaller user-y).
        b.grow(originX + scale * outline.xMin(), baselineY - scale * outline.yMax());
        b.grow(originX + scale * outline.xMax(), baselineY - scale * outline.yMin());
    }

    /** Mutable running bounding box in user space. */
    private static final class Bounds {
        boolean empty = true;
        double minX, minY, maxX, maxY;

        void grow(double x, double y) {
            if (empty) {
                minX = maxX = x;
                minY = maxY = y;
                empty = false;
            } else {
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
    }
}
