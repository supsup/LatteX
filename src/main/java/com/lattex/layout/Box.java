package com.lattex.layout;

import java.util.List;

/**
 * A laid-out sub-formula in the TeX box model (TeXbook Appendix G), expressed in
 * its own local coordinates: the pen origin is {@code x = 0} and the baseline is
 * {@code y = 0} (user space, y-down). Its glyphs and rules are positioned
 * relative to that origin; the three metrics describe its extent:
 *
 * <ul>
 *   <li>{@link #width} — horizontal advance (pen movement), user units.</li>
 *   <li>{@link #height} — ink/metric extent <em>above</em> the baseline
 *       (a non-negative distance; larger means taller).</li>
 *   <li>{@link #depth} — extent <em>below</em> the baseline (non-negative).</li>
 * </ul>
 *
 * <p>Boxes compose by {@linkplain #drawInto stamping} a child's glyphs/rules into
 * a parent accumulator at a baseline offset {@code (dx, dy)}; the parent then
 * derives its own metrics from the placement arithmetic (the child's own
 * metrics are relative to <em>its</em> baseline, so a vertical shift changes how
 * they contribute — the caller does that math explicitly).
 *
 * @param glyphs positioned glyphs, in this box's local coordinates
 * @param rules  positioned rules, in this box's local coordinates
 * @param width  horizontal advance, user units
 * @param height extent above the baseline, user units
 * @param depth  extent below the baseline, user units
 */
record Box(List<PositionedGlyph> glyphs, List<Rule> rules,
           double width, double height, double depth) {

    Box {
        glyphs = List.copyOf(glyphs);
        rules = List.copyOf(rules);
    }

    /** An empty box of the given width (explicit glue), with no ink. */
    static Box glue(double width) {
        return new Box(List.of(), List.of(), width, 0.0, 0.0);
    }

    /**
     * Stamps this box's glyphs and rules into {@code outGlyphs}/{@code outRules},
     * translated so this box's origin lands at {@code (dx, dy)} in the target
     * coordinate space (dy positive = further down).
     */
    void drawInto(List<PositionedGlyph> outGlyphs, List<Rule> outRules, double dx, double dy) {
        for (PositionedGlyph g : glyphs) {
            outGlyphs.add(new PositionedGlyph(g.glyphId(), g.originX() + dx, g.baselineY() + dy,
                g.scale(), g.color(), g.sourceCodePoint(), g.fenceDepth()));
        }
        for (Rule r : rules) {
            if (r.isPolygon()) {
                // A filled polygon (cancel strike/arrowhead): translate every vertex,
                // then rebuild so its rect bbox metrics stay consistent with the points.
                double[] p = r.polygon();
                double[] q = new double[p.length];
                for (int i = 0; i < p.length; i += 2) {
                    q[i] = p[i] + dx;
                    q[i + 1] = p[i + 1] + dy;
                }
                outRules.add(Rule.polygon(q, r.color()));
            } else {
                outRules.add(new Rule(r.x() + dx, r.y() + dy, r.width(), r.height(), r.color()));
            }
        }
    }
}
