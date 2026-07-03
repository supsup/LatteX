package com.lattex.layout;

import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.SupSub;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link MathNode} tree into positioned glyph geometry using the TeX box
 * model (TeXbook Appendix G). Each node is laid out into a {@link Box} — a
 * width/height/depth triple with glyphs and rules in the box's own coordinates —
 * and boxes compose vertically (scripts, fractions, radicals) and horizontally
 * (math lists with inter-atom spacing).
 *
 * <p>The geometry is derived clean-room from Knuth's TeXbook Appendix G (the
 * exact script/fraction/radical/spacing rules), TeX: The Program, and the public
 * OpenType MATH specification (which names the constants consumed here:
 * {@code superscriptShiftUp}, {@code subscriptShiftDown}, {@code axisHeight},
 * {@code fraction*}, {@code radical*}, …). No GPL/LGPL renderer source was
 * consulted.
 *
 * <p><strong>Rendered:</strong> {@link Atom}, {@link MathList} (with Appendix-G
 * inter-atom spacing), {@link SupSub} (full sub+sup), {@link Fraction},
 * {@link Radical} (with optional degree), {@link Spacing}. <strong>Still
 * stubbed</strong> (next S4 increment): {@link BigOperator} limit-stacking and
 * {@link Fenced} scaled delimiters — kept as throwing cases so the sealed
 * {@code MathNode} switch stays exhaustive without a {@code default}.
 */
public final class LayoutEngine {

    /** The surd (radical sign) starting glyph, U+221A. */
    private static final int SURD_CODEPOINT = 0x221A;

    private LayoutEngine() {
    }

    /** Lays out a math tree at the origin; returns positioned glyphs/rules + bbox. */
    public static Layout layout(MathNode root, LayoutContext ctx) {
        Box box = layoutBox(root, ctx);
        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        box.drawInto(glyphs, rules, 0.0, 0.0);

        Bounds bounds = new Bounds();
        SfntFont font = ctx.font();
        for (PositionedGlyph g : glyphs) {
            accumulate(bounds, font.outline(g.glyphId()), g.originX(), g.baselineY(), g.scale());
        }
        for (Rule r : rules) {
            bounds.grow(r.x(), r.y());
            bounds.grow(r.x() + r.width(), r.y() + r.height());
        }
        if (bounds.empty) {
            return new Layout(glyphs, rules, 0, 0, 0, 0);
        }
        return new Layout(glyphs, rules, bounds.minX, bounds.minY, bounds.maxX, bounds.maxY);
    }

    // ------------------------------------------------------------------
    // Box construction — one arm per renderable node kind.
    // ------------------------------------------------------------------

    private static Box layoutBox(MathNode node, LayoutContext ctx) {
        return switch (node) {
            case Atom atom -> atomBox(atom, ctx);
            case MathList(var items) -> rowBox(items, ctx);
            case SupSub(var base, var sup, var sub) -> scriptsBox(base, sup, sub, ctx);
            case Fraction(var num, var den) -> fractionBox(num, den, ctx);
            case Radical(var radicand, var index) -> radicalBox(radicand, index, ctx);
            case Spacing(var muWidth) -> Box.glue(muWidth * ctx.mu());
            // Next S4 increment — kept as throwing, no-default cases so the sealed
            // switch stays exhaustive and the compiler flags them the moment they
            // become renderable.
            case BigOperator _ -> throw notYetLaidOut(node);
            case Fenced _ -> throw notYetLaidOut(node);
        };
    }

    /** A single glyph on the baseline; height/depth are its ink extents. */
    private static Box atomBox(Atom atom, LayoutContext ctx) {
        SfntFont font = ctx.font();
        double scale = ctx.scale();
        int gid = font.glyphId(atom.codePoint());
        GlyphOutline o = font.outline(gid);
        PositionedGlyph glyph = new PositionedGlyph(gid, 0.0, 0.0, scale);
        double width = font.advanceWidth(gid) * scale;
        double height = o.isEmpty() ? 0.0 : Math.max(0.0, o.yMax() * scale);
        double depth = o.isEmpty() ? 0.0 : Math.max(0.0, -o.yMin() * scale);
        return new Box(List.of(glyph), List.of(), width, height, depth);
    }

    // ------------------------------------------------------------------
    // MathList — a horizontal row with Appendix-G inter-atom spacing.
    // ------------------------------------------------------------------

    private static Box rowBox(List<MathNode> items, LayoutContext ctx) {
        int n = items.size();
        // Per-item spacing class (null for explicit-glue Spacing nodes), with
        // TeX's Bin→Ord reclassification applied.
        MathClass[] cls = new MathClass[n];
        for (int i = 0; i < n; i++) {
            cls[i] = items.get(i) instanceof Spacing ? null : classOf(items.get(i));
        }
        reclassifyBins(cls);

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double penX = 0.0;
        double height = 0.0;
        double depth = 0.0;
        MathClass prev = null; // previous atom's class (glue resets it to null)

        for (int i = 0; i < n; i++) {
            MathNode item = items.get(i);
            if (item instanceof Spacing(var muWidth)) {
                penX += muWidth * ctx.mu();
                prev = null;
                continue;
            }
            if (prev != null && cls[i] != null) {
                penX += interAtomSpace(prev, cls[i], ctx);
            }
            Box b = layoutBox(item, ctx);
            b.drawInto(glyphs, rules, penX, 0.0);
            height = Math.max(height, b.height());
            depth = Math.max(depth, b.depth());
            penX += b.width();
            prev = cls[i];
        }
        return new Box(glyphs, rules, penX, height, depth);
    }

    /**
     * TeX's Bin-atom reclassification (Appendix G / TeX82 §726): a Bin that has
     * no valid left operand becomes Ord, and a Bin left of a Rel/Close/Punct is
     * also demoted. Prevents e.g. a leading or post-relation {@code -} from
     * getting binary-operator spacing.
     */
    private static void reclassifyBins(MathClass[] cls) {
        MathClass prev = null;
        int prevIdx = -1;
        for (int i = 0; i < cls.length; i++) {
            MathClass c = cls[i];
            if (c == null) {
                continue; // glue: does not participate, and resets nothing here
            }
            if (c == MathClass.BIN
                    && (prev == null || prev == MathClass.BIN || prev == MathClass.OP
                        || prev == MathClass.REL || prev == MathClass.OPEN
                        || prev == MathClass.PUNCT)) {
                cls[i] = MathClass.ORD;
            } else if ((c == MathClass.REL || c == MathClass.CLOSE || c == MathClass.PUNCT)
                    && prev == MathClass.BIN) {
                cls[prevIdx] = MathClass.ORD;
            }
            prev = cls[i];
            prevIdx = i;
        }
    }

    // Inter-atom spacing table (TeXbook Ch.18, p.170 / Appendix G). Rows = left
    // atom class, cols = right, indexed by MathClass.ordinal()
    // (ORD OP BIN REL OPEN CLOSE PUNCT INNER). Codes:
    //   0 = none
    //   1 = thin  (3mu), applied in ALL styles
    //   2 = thin  (3mu), suppressed in script/script-script  [ (1) in the book ]
    //   3 = medium(4mu), suppressed in script/script-script  [ (2) ]
    //   4 = thick (5mu), suppressed in script/script-script  [ (3) ]
    // Impossible Bin-adjacencies (the book's '*') are 0; Bin reclassification
    // above ensures they do not arise.
    private static final int[][] SPACING = {
        /* ORD   */ {0, 1, 3, 4, 0, 0, 0, 2},
        /* OP    */ {1, 1, 0, 4, 0, 0, 0, 2},
        /* BIN   */ {3, 3, 0, 0, 3, 0, 0, 3},
        /* REL   */ {4, 4, 0, 0, 4, 0, 0, 4},
        /* OPEN  */ {0, 0, 0, 0, 0, 0, 0, 0},
        /* CLOSE */ {0, 1, 3, 4, 0, 0, 0, 2},
        /* PUNCT */ {2, 2, 0, 2, 2, 2, 2, 2},
        /* INNER */ {2, 1, 3, 4, 2, 0, 2, 2},
    };

    private static double interAtomSpace(MathClass left, MathClass right, LayoutContext ctx) {
        int code = SPACING[left.ordinal()][right.ordinal()];
        if (code == 0) {
            return 0.0;
        }
        boolean scriptStyle = ctx.style() == MathStyle.SCRIPT
            || ctx.style() == MathStyle.SCRIPT_SCRIPT;
        if (code != 1 && scriptStyle) {
            return 0.0; // conditional spaces vanish in script styles
        }
        double mu = switch (code) {
            case 1, 2 -> 3.0; // thin
            case 3 -> 4.0;    // medium
            case 4 -> 5.0;    // thick
            default -> 0.0;
        };
        return mu * ctx.mu();
    }

    /** The spacing class a node contributes in a row (composites have implied classes). */
    private static MathClass classOf(MathNode node) {
        return switch (node) {
            case Atom atom -> atom.mathClass();
            case SupSub(var base, _, _) -> classOf(base);
            case Fraction _ -> MathClass.ORD;
            case Radical _ -> MathClass.ORD;
            case Fenced _ -> MathClass.INNER;
            case BigOperator _ -> MathClass.OP;
            case MathList _ -> MathClass.ORD; // a {group} behaves as an Ord atom
            case Spacing _ -> null;           // classless glue (handled by caller)
        };
    }

    // ------------------------------------------------------------------
    // SupSub — full subscript + superscript (Appendix G rules 18a–18f).
    // ------------------------------------------------------------------

    private static Box scriptsBox(MathNode base, MathNode sup, MathNode sub, LayoutContext ctx) {
        SfntFont font = ctx.font();
        double baseScale = ctx.scale();
        Box baseBox = layoutBox(base, ctx);

        // Italic correction shifts a superscript right off a slanted nucleus; a
        // subscript is not shifted. Only a single-character nucleus carries one.
        double italic = 0.0;
        boolean simpleChar = base instanceof Atom;
        if (base instanceof Atom baseAtom) {
            italic = font.italicCorrection(font.glyphId(baseAtom.codePoint())) * baseScale;
        }

        var c = ctx.constants();
        // Base-derived tentative shifts (rule 18a): zero for a bare character,
        // else driven off the nucleus box height/depth minus the drop constants.
        double u = simpleChar ? 0.0 : baseBox.height() - c.superscriptBaselineDropMax() * baseScale;
        double v = simpleChar ? 0.0 : baseBox.depth() + c.subscriptBaselineDropMin() * baseScale;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        baseBox.drawInto(glyphs, rules, 0.0, 0.0);
        double height = baseBox.height();
        double depth = baseBox.depth();
        double scriptsRight = baseBox.width(); // rightmost extent of the script cluster

        Box supBox = sup == null ? null : layoutBox(sup, ctx.superscript());
        Box subBox = sub == null ? null : layoutBox(sub, ctx.subscript());

        double supShift = 0.0;
        double subShift = 0.0;

        if (supBox != null) {
            double shiftUpConst = (ctx.cramped()
                ? c.superscriptShiftUpCramped() : c.superscriptShiftUp()) * baseScale;
            supShift = Math.max(u, shiftUpConst);
            // The superscript's bottom must clear the baseline by superscriptBottomMin.
            supShift = Math.max(supShift, supBox.depth() + c.superscriptBottomMin() * baseScale);
        }
        if (subBox != null) {
            double shiftDownConst = c.subscriptShiftDown() * baseScale;
            subShift = Math.max(v, shiftDownConst);
            // The subscript's top must not rise above subscriptTopMax over the baseline.
            subShift = Math.max(subShift, subBox.height() - c.subscriptTopMax() * baseScale);
        }

        if (supBox != null && subBox != null) {
            // Rule 18f: open the sub/superscript gap. Raise the superscript first,
            // but not past superscriptBottomMaxWithSubscript; push the subscript
            // down for whatever clearance remains.
            double gapMin = c.subSuperscriptGapMin() * baseScale;
            double gap = (supShift - supBox.depth()) - (subBox.height() - subShift);
            if (gap < gapMin) {
                double need = gapMin - gap;
                supShift += need;
                double supBottom = supShift - supBox.depth();
                double cap = c.superscriptBottomMaxWithSubscript() * baseScale;
                if (supBottom > cap) {
                    double excess = supBottom - cap;
                    supShift -= excess;
                    subShift += excess;
                }
            }
        }

        if (supBox != null) {
            supBox.drawInto(glyphs, rules, baseBox.width() + italic, -supShift);
            height = Math.max(height, supShift + supBox.height());
            depth = Math.max(depth, supBox.depth() - supShift); // usually negative → ignored
            scriptsRight = Math.max(scriptsRight, baseBox.width() + italic + supBox.width());
        }
        if (subBox != null) {
            subBox.drawInto(glyphs, rules, baseBox.width(), subShift);
            depth = Math.max(depth, subShift + subBox.depth());
            height = Math.max(height, subBox.height() - subShift); // usually negative → ignored
            scriptsRight = Math.max(scriptsRight, baseBox.width() + subBox.width());
        }

        double width = scriptsRight + c.spaceAfterScript() * baseScale;
        return new Box(glyphs, rules, width, Math.max(0.0, height), Math.max(0.0, depth));
    }

    // ------------------------------------------------------------------
    // Fraction — numerator/denominator stacked on the math axis.
    // ------------------------------------------------------------------

    private static Box fractionBox(MathNode num, MathNode den, LayoutContext ctx) {
        var c = ctx.constants();
        double scale = ctx.scale();
        boolean display = ctx.style().isDisplay();

        Box numBox = layoutBox(num, ctx.numerator());
        Box denBox = layoutBox(den, ctx.denominator());

        double axis = c.axisHeight() * scale;
        double thickness = c.fractionRuleThickness() * scale;
        double numShiftUp = (display
            ? c.fractionNumeratorDisplayStyleShiftUp() : c.fractionNumeratorShiftUp()) * scale;
        double denShiftDown = (display
            ? c.fractionDenominatorDisplayStyleShiftDown() : c.fractionDenominatorShiftDown()) * scale;
        double numGapMin = (display
            ? c.fractionNumDisplayStyleGapMin() : c.fractionNumeratorGapMin()) * scale;
        double denGapMin = (display
            ? c.fractionDenomDisplayStyleGapMin() : c.fractionDenominatorGapMin()) * scale;

        // Keep the numerator's bottom clear of the rule's top by at least numGapMin;
        // symmetrically for the denominator below the rule.
        double ruleHalf = thickness / 2.0;
        numShiftUp = Math.max(numShiftUp, axis + ruleHalf + numGapMin + numBox.depth());
        denShiftDown = Math.max(denShiftDown, denBox.height() - axis + ruleHalf + denGapMin);

        double width = Math.max(numBox.width(), denBox.width());
        double numX = (width - numBox.width()) / 2.0;
        double denX = (width - denBox.width()) / 2.0;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        numBox.drawInto(glyphs, rules, numX, -numShiftUp);
        denBox.drawInto(glyphs, rules, denX, denShiftDown);
        rules.add(new Rule(0.0, -axis - ruleHalf, width, thickness));

        double height = numShiftUp + numBox.height();
        double depth = denShiftDown + denBox.depth();
        return new Box(glyphs, rules, width, height, depth);
    }

    // ------------------------------------------------------------------
    // Radical — surd glyph + over-bar over the radicand, optional degree.
    // ------------------------------------------------------------------

    private static Box radicalBox(MathNode radicand, MathNode index, LayoutContext ctx) {
        SfntFont font = ctx.font();
        var c = ctx.constants();
        double scale = ctx.scale();
        boolean display = ctx.style().isDisplay();

        Box radBox = layoutBox(radicand, ctx.cramp());
        double gap = (display ? c.radicalDisplayStyleVerticalGap() : c.radicalVerticalGap()) * scale;
        double ruleThick = c.radicalRuleThickness() * scale;
        double extraAscender = c.radicalExtraAscender() * scale;

        double hr = radBox.height();
        double dr = radBox.depth();
        // The over-bar's top sits this far above the baseline; its bottom clears
        // the radicand top by `gap`.
        double barTopAbove = hr + gap + ruleThick;

        // Surd glyph. True stretch-to-content via MATH glyph variants/assembly is
        // a follow-up; here we scale the single U+221A glyph uniformly so it spans
        // the radicand from the bar down past its depth (documented approximation).
        int surdGid = font.glyphId(SURD_CODEPOINT);
        GlyphOutline surd = font.outline(surdGid);
        double surdSpanFontUnits = Math.max(1.0, surd.yMax() - surd.yMin());
        double requiredSpan = barTopAbove + dr;
        double surdScale = Math.max(scale, requiredSpan / surdSpanFontUnits);
        double surdWidth = font.advanceWidth(surdGid) * surdScale;
        // Baseline placed so the glyph top aligns with the bar top.
        double barTopY = -barTopAbove;
        double surdBaselineY = barTopY + surdScale * surd.yMax();
        double surdBottomY = surdBaselineY - surdScale * surd.yMin(); // yMin < 0 → below baseline

        // Optional degree/index, raised to the upper-left of the surd.
        double leftOffset = 0.0;
        Box degBox = null;
        double degBaselineY = 0.0;
        if (index != null) {
            degBox = layoutBox(index, ctx.radicalDegree());
            double kernBefore = c.radicalKernBeforeDegree() * scale;
            double kernAfter = c.radicalKernAfterDegree() * scale;
            leftOffset = kernBefore + degBox.width() + kernAfter;
            double surdTotalHeight = surdBottomY - barTopY; // full surd extent
            double pct = c.radicalDegreeBottomRaisePercent() / 100.0;
            double degBottomY = surdBottomY - pct * surdTotalHeight;
            degBaselineY = degBottomY - degBox.depth();
        }

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double surdX = leftOffset;
        double radX = leftOffset + surdWidth;

        glyphs.add(new PositionedGlyph(surdGid, surdX, surdBaselineY, surdScale));
        radBox.drawInto(glyphs, rules, radX, 0.0);
        rules.add(new Rule(radX, barTopY, radBox.width(), ruleThick));
        if (degBox != null) {
            double kernBefore = c.radicalKernBeforeDegree() * scale;
            degBox.drawInto(glyphs, rules, kernBefore, degBaselineY);
        }

        double width = leftOffset + surdWidth + radBox.width();
        double height = barTopAbove + extraAscender;
        if (degBox != null) {
            height = Math.max(height, -(degBaselineY - degBox.height()));
        }
        double depth = Math.max(dr, surdBottomY);
        return new Box(glyphs, rules, width, height, depth);
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private static UnsupportedOperationException notYetLaidOut(MathNode node) {
        return new UnsupportedOperationException(
            "S4 layout not yet implemented for " + node.getClass().getSimpleName()
                + " (next increment: BigOperator limits, Fenced delimiters)");
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
