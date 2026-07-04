package com.lattex.layout;

import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.LimitsMode;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.OperatorName;
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
 * {@link Radical} (with optional degree), {@link BigOperator} (display-size
 * operator glyph with limits stacked above/below or set beside as scripts),
 * {@link Fenced} (delimiters stretched to span the body and centred on the
 * axis), and {@link Spacing}. The sealed {@code MathNode} switch is exhaustive
 * with no {@code default}, so a new node kind is a compile error until handled.
 */
public final class LayoutEngine {

    /** The surd (radical sign) starting glyph, U+221A. */
    private static final int SURD_CODEPOINT = 0x221A;

    /** U+222B INTEGRAL — conventionally takes side limits even in display style. */
    private static final int INTEGRAL_CODEPOINT = 0x222B;

    /**
     * Fraction of the axis-symmetric body span a {@code \left..\right} delimiter
     * must reach (TeXbook Appendix-G / plain TeX {@code \delimiterfactor}=901).
     * A delimiter need only cover ~90% of the content, so the smallest adequate
     * pre-drawn variant hugs the body instead of overshooting to the next size.
     */
    private static final double DELIMITER_FACTOR = 0.901;

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
            case BigOperator(var op, var lower, var upper, var limitsMode) ->
                bigOperatorBox(op, lower, upper, limitsMode, ctx);
            case Fenced(var leftDelim, var body, var rightDelim) ->
                fencedBox(leftDelim, body, rightDelim, ctx);
            case Accent accent -> accentBox(accent, ctx);
            case OperatorName opName -> operatorNameBox(opName, ctx);
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
    // OperatorName — a named operator (\sin, \lim, \operatorname{lcm}) set as an
    // upright roman word. Its letters are the font's plain (roman) glyphs laid
    // adjacent at their advance widths — no inter-letter math spacing (a name is
    // one unit); a single space in the display text renders as a thin (3mu) gap,
    // matching TeX's thin space in the compound forms (lim inf, inj lim, …). The
    // resulting box carries math class Op for its surrounding spacing (set in the
    // enclosing row via classOf), like any large operator.
    // ------------------------------------------------------------------

    private static Box operatorNameBox(OperatorName opName, LayoutContext ctx) {
        SfntFont font = ctx.font();
        double scale = ctx.scale();
        String text = opName.name();

        List<PositionedGlyph> glyphs = new ArrayList<>();
        double penX = 0.0;
        double height = 0.0;
        double depth = 0.0;
        double thinSpace = 3.0 * ctx.mu(); // TeX thin space between compound words

        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                penX += thinSpace;
                continue;
            }
            int gid = font.glyphId(cp);
            GlyphOutline o = font.outline(gid);
            glyphs.add(new PositionedGlyph(gid, penX, 0.0, scale));
            penX += font.advanceWidth(gid) * scale;
            if (!o.isEmpty()) {
                height = Math.max(height, o.yMax() * scale);
                depth = Math.max(depth, -o.yMin() * scale);
            }
        }
        return new Box(glyphs, List.of(), penX, Math.max(0.0, height), Math.max(0.0, depth));
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
            case Accent _ -> MathClass.ORD;   // an accented nucleus is Ord
            case OperatorName _ -> MathClass.OP; // a named operator is class Op
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

        // A limit-taking named operator (\lim, \max, \operatorname*{…}) stacks its
        // scripts as limits above/below in display style (TeXbook: \lim takes
        // limits), and sets them beside as ordinary scripts in text style. This
        // reuses the BigOperator limit-stacking path — sup is the upper limit,
        // sub the lower — exactly as a large operator does.
        if (base instanceof OperatorName on && on.takesLimits() && ctx.style().isDisplay()) {
            Box upperBox = sup == null ? null : layoutBox(sup, ctx.superscript());
            Box lowerBox = sub == null ? null : layoutBox(sub, ctx.subscript());
            return stackLimits(baseBox, upperBox, lowerBox, ctx);
        }

        // Italic correction shifts a superscript right off a slanted nucleus; a
        // subscript is not shifted. Only a single-character nucleus carries one.
        double italic = 0.0;
        boolean simpleChar = base instanceof Atom;
        if (base instanceof Atom baseAtom) {
            italic = font.italicCorrection(font.glyphId(baseAtom.codePoint())) * baseScale;
        }

        Box supBox = sup == null ? null : layoutBox(sup, ctx.superscript());
        Box subBox = sub == null ? null : layoutBox(sub, ctx.subscript());
        return attachScripts(baseBox, simpleChar, italic, supBox, subBox, ctx);
    }

    /**
     * Attaches a superscript and/or subscript beside an already-laid-out nucleus
     * (Appendix G rules 18a–18f). Factored out of {@link #scriptsBox} so a
     * {@link BigOperator} with side limits ({@code \nolimits}, or an integral in
     * display style) can reuse the exact sub/sup placement over its enlarged
     * operator box. {@code simpleChar} selects the rule-18a shortcut (zero
     * base-derived shift for a bare character); {@code italic} is the nucleus's
     * italic correction (already scaled), added to the superscript's x-offset.
     */
    private static Box attachScripts(Box baseBox, boolean simpleChar, double italic,
                                     Box supBox, Box subBox, LayoutContext ctx) {
        var c = ctx.constants();
        double baseScale = ctx.scale();
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

        // Vertical extent the surd must span, from the over-bar's top down to the
        // radicand's bottom (TeXbook Appendix-G Rule 11: height+depth+gap+rule).
        double requiredSpan = hr + gap + ruleThick + dr;

        // Pick a *pre-designed* surd sized to the content via the OpenType MATH
        // MathVariants table — NOT a uniformly stretched base glyph (which would
        // thicken the strokes and, for short radicands, dangle a long tail). See
        // {@link #stretchVertical}. The chosen glyph is generally a little taller
        // than {@code requiredSpan}; that excess is split evenly below (Rule 11).
        int surdGid = font.glyphId(SURD_CODEPOINT);
        StretchyGlyph surd = stretchVertical(font, surdGid, requiredSpan, scale);
        double surdWidth = surd.width();

        // Appendix-G Rule 11 excess split: half the surplus surd height raises the
        // over-bar (a larger effective gap), half extends the surd tail below the
        // radicand — so a slightly-too-tall glyph never dangles its whole surplus
        // beneath the content (the old top-aligned look that read as oversized).
        double delta = surd.span() - requiredSpan;
        double effGap = delta > 0.0 ? gap + delta / 2.0 : gap;

        double barTopAbove = hr + effGap + ruleThick;
        double barTopY = -barTopAbove;
        double surdBottomY = barTopY + surd.span(); // ink bottom of the surd (below baseline)

        // Optional degree/index, nestled into the surd's upper-left kink and raised
        // radicalDegreeBottomRaisePercent of the surd's height up from its bottom.
        double leftOffset = 0.0;
        Box degBox = null;
        double degBaselineY = 0.0;
        double kernBefore = c.radicalKernBeforeDegree() * scale;
        if (index != null) {
            degBox = layoutBox(index, ctx.radicalDegree());
            double kernAfter = c.radicalKernAfterDegree() * scale;
            leftOffset = Math.max(0.0, kernBefore + degBox.width() + kernAfter);
            double pct = c.radicalDegreeBottomRaisePercent() / 100.0;
            double degBottomY = surdBottomY - pct * surd.span();
            degBaselineY = degBottomY - degBox.depth();
        }

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double surdX = leftOffset;
        double radX = leftOffset + surdWidth;

        // Materialise the chosen surd (single variant, or stacked assembly parts)
        // with its ink top aligned to the bar top.
        surd.placeInto(glyphs, surdX, barTopY);
        radBox.drawInto(glyphs, rules, radX, 0.0);
        rules.add(new Rule(radX, barTopY, radBox.width(), ruleThick));
        if (degBox != null) {
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
    // BigOperator — a large operator (\sum \int \prod) with limits stacked
    // above/below (display) or set beside as scripts (text, \nolimits, integrals).
    // ------------------------------------------------------------------

    private static Box bigOperatorBox(Atom op, MathNode lower, MathNode upper,
                                      LimitsMode mode, LayoutContext ctx) {
        SfntFont font = ctx.font();
        var c = ctx.constants();
        double scale = ctx.scale();
        boolean display = ctx.style().isDisplay();

        // Display style uses a larger operator glyph (an OpenType MATH vertical
        // variant at least displayOperatorMinHeight tall, else the largest one).
        int opGid = font.glyphId(op.codePoint());
        if (display) {
            opGid = displayOperatorGlyph(font, opGid, c.displayOperatorMinHeight());
        }

        // The operator box, centred vertically on the math axis (TeXbook: a large
        // operator is set so its centre lies on the axis, like a fraction bar).
        GlyphOutline o = font.outline(opGid);
        double axis = c.axisHeight() * scale;
        double opWidth = font.advanceWidth(opGid) * scale;
        double halfInk = (o.yMax() - o.yMin()) / 2.0 * scale;
        double opBaseline = (o.yMax() + o.yMin()) / 2.0 * scale - axis; // shift centre onto axis
        Box opBox = new Box(
            List.of(new PositionedGlyph(opGid, 0.0, opBaseline, scale)),
            List.of(), opWidth, halfInk + axis, halfInk - axis);

        // Limit placement: above/below in display style (except integrals, which
        // conventionally keep side limits), beside as scripts otherwise.
        boolean sideLimits = switch (mode) {
            case LIMITS -> false;
            case NOLIMITS -> true;
            case DEFAULT -> !display || op.codePoint() == INTEGRAL_CODEPOINT;
        };
        Box upperBox = upper == null ? null : layoutBox(upper, ctx.superscript());
        Box lowerBox = lower == null ? null : layoutBox(lower, ctx.subscript());

        if (sideLimits) {
            double italic = font.italicCorrection(opGid) * scale;
            return attachScripts(opBox, false, italic, upperBox, lowerBox, ctx);
        }
        return stackLimits(opBox, upperBox, lowerBox, ctx);
    }

    /**
     * Stacks the upper limit above and lower limit below the operator, each
     * horizontally centred on it, using the OpenType MATH
     * {@code upperLimit*}/{@code lowerLimit*} constants (TeXbook Appendix-G limit
     * rules). Limits are already laid out in the shrunk script style.
     */
    private static Box stackLimits(Box opBox, Box upperBox, Box lowerBox, LayoutContext ctx) {
        var c = ctx.constants();
        double scale = ctx.scale();

        double width = opBox.width();
        if (upperBox != null) {
            width = Math.max(width, upperBox.width());
        }
        if (lowerBox != null) {
            width = Math.max(width, lowerBox.width());
        }

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double opX = (width - opBox.width()) / 2.0;
        opBox.drawInto(glyphs, rules, opX, 0.0);
        double height = opBox.height();
        double depth = opBox.depth();

        if (upperBox != null) {
            // Baseline of the upper limit above the operator's ink top: clear the
            // gap (limit depth + gapMin) but at least the baseline-rise minimum.
            double rise = opBox.height() + Math.max(
                c.upperLimitGapMin() * scale + upperBox.depth(),
                c.upperLimitBaselineRiseMin() * scale);
            upperBox.drawInto(glyphs, rules, (width - upperBox.width()) / 2.0, -rise);
            height = Math.max(height, rise + upperBox.height());
        }
        if (lowerBox != null) {
            double drop = opBox.depth() + Math.max(
                c.lowerLimitGapMin() * scale + lowerBox.height(),
                c.lowerLimitBaselineDropMin() * scale);
            lowerBox.drawInto(glyphs, rules, (width - lowerBox.width()) / 2.0, drop);
            depth = Math.max(depth, drop + lowerBox.depth());
        }
        return new Box(glyphs, rules, width, height, depth);
    }

    /** The display-size glyph for an operator: smallest vertical variant at least
     *  {@code minHeight} design units, else the largest variant, else the base. */
    private static int displayOperatorGlyph(SfntFont font, int baseGid, int minHeight) {
        var construction = font.verticalVariants(baseGid);
        if (construction == null) {
            return baseGid;
        }
        var variant = construction.variantAtLeast(minHeight);
        if (variant.isPresent()) {
            return variant.getAsInt();
        }
        var variants = construction.variants();
        return variants.isEmpty() ? baseGid : variants.get(variants.size() - 1).glyphId();
    }

    // ------------------------------------------------------------------
    // Fenced — a \left..\right sub-formula whose delimiters are stretched to
    // span the body and centred on the math axis (reuses the stretchy-glyph
    // machinery below, exactly as the radical surd does).
    // ------------------------------------------------------------------

    private static Box fencedBox(int leftDelim, MathNode body, int rightDelim, LayoutContext ctx) {
        SfntFont font = ctx.font();
        var c = ctx.constants();
        double scale = ctx.scale();
        double axis = c.axisHeight() * scale;

        Box bodyBox = layoutBox(body, ctx);

        // Symmetric target about the axis: cover the taller of (body above axis)
        // and (body below axis) on both sides, so the pair is balanced on the axis.
        double aboveAxis = bodyBox.height() - axis;
        double belowAxis = bodyBox.depth() + axis;
        double requiredSpan = 2.0 * Math.max(aboveAxis, belowAxis) * DELIMITER_FACTOR;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double penX = 0.0;
        double height = bodyBox.height();
        double depth = bodyBox.depth();

        if (leftDelim != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, leftDelim, requiredSpan, axis, scale, penX, glyphs);
            penX = hd[0];
            height = Math.max(height, hd[1]);
            depth = Math.max(depth, hd[2]);
        }
        bodyBox.drawInto(glyphs, rules, penX, 0.0);
        penX += bodyBox.width();
        if (rightDelim != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, rightDelim, requiredSpan, axis, scale, penX, glyphs);
            penX = hd[0];
            height = Math.max(height, hd[1]);
            depth = Math.max(depth, hd[2]);
        }
        return new Box(glyphs, rules, penX, height, depth);
    }

    /**
     * Sizes a single delimiter to {@code requiredSpan}, centres its ink on the
     * axis, stamps it at {@code x}, and returns {@code {newPenX, height, depth}}
     * (the delimiter's contribution to the enclosing box's extents).
     */
    private static double[] placeDelimiter(SfntFont font, int delimCp, double requiredSpan,
                                           double axis, double scale, double x,
                                           List<PositionedGlyph> glyphs) {
        StretchyGlyph d = stretchVertical(font, font.glyphId(delimCp), requiredSpan, scale);
        double inkTop = -axis - d.span() / 2.0; // centre the ink on the axis
        d.placeInto(glyphs, x, inkTop);
        return new double[] {x + d.width(), axis + d.span() / 2.0, d.span() / 2.0 - axis};
    }

    // ------------------------------------------------------------------
    // Accent — an accent glyph centred over the base (narrow or stretched to the
    // base width), or a rule drawn over/under it (\overline / \\underline).
    // Clean-room from the TeXbook accent rule (Appendix G) + the OpenType MATH
    // constants (MathTopAccentAttachment, accentBaseHeight, over/underbar*).
    // ------------------------------------------------------------------

    /** One drawable piece of an accent: a glyph and its x-offset (design units). */
    private record AccentPiece(int gid, int dx) {
    }

    private static Box accentBox(Accent accent, LayoutContext ctx) {
        // The nucleus is set cramped (TeX): its own superscripts sit lower, less
        // likely to collide with the accent above.
        Box baseBox = layoutBox(accent.base(), ctx.cramp());
        return accent.isRule()
            ? overUnderLineBox(accent, baseBox, ctx)
            : glyphAccentBox(accent, baseBox, ctx);
    }

    /**
     * Positions an accent glyph over the base. The accent's ink is centred over
     * the base's top-accent attachment point (MATH {@code MathTopAccentAttachment},
     * falling back to the base centre) and raised so its bottom clears the base:
     * TeXbook's rule lifts the accent by {@code max(0, baseHeight −
     * accentBaseHeight)}, so a tall base pushes the accent up while a short one
     * lets it hug — the combining accent glyph's intrinsic ink height supplies the
     * clearance. A stretchy accent is first sized to the base width.
     */
    private static Box glyphAccentBox(Accent accent, Box baseBox, LayoutContext ctx) {
        SfntFont font = ctx.font();
        var c = ctx.constants();
        double scale = ctx.scale();

        // Base attachment x (where the accent centres over the base).
        double baseAccentX;
        if (accent.base() instanceof Atom a) {
            int taa = font.topAccentAttachment(font.glyphId(a.codePoint()));
            baseAccentX = taa != 0 ? taa * scale : baseBox.width() / 2.0;
        } else {
            baseAccentX = baseBox.width() / 2.0;
        }

        // Choose the accent rendering: a single glyph (narrow), or — for a stretchy
        // accent — a wider MATH variant / assembled row sized to the base width.
        int accentGid = font.glyphId(accent.accentCodePoint());
        List<AccentPiece> pieces = new ArrayList<>();
        if (accent.stretchy()) {
            stretchHorizontal(font, accentGid, baseBox.width() / scale, pieces);
        } else {
            pieces.add(new AccentPiece(accentGid, 0));
        }

        // Ink bbox of the whole accent (design units, x-offsets applied).
        double inkMinX = Double.POSITIVE_INFINITY;
        double inkMaxX = Double.NEGATIVE_INFINITY;
        double inkYMax = Double.NEGATIVE_INFINITY;
        for (AccentPiece p : pieces) {
            GlyphOutline o = font.outline(p.gid());
            if (o.isEmpty()) {
                continue;
            }
            inkMinX = Math.min(inkMinX, p.dx() + o.xMin());
            inkMaxX = Math.max(inkMaxX, p.dx() + o.xMax());
            inkYMax = Math.max(inkYMax, o.yMax());
        }
        if (inkMinX > inkMaxX) { // accent glyph has no ink (defensive)
            return baseBox;
        }
        double inkCenterDesign = (inkMinX + inkMaxX) / 2.0;

        // Align the accent ink centre to the base attachment point; raise it clear.
        double accentOriginX = baseAccentX - scale * inkCenterDesign;
        double raise = Math.max(0.0, baseBox.height() - c.accentBaseHeight() * scale);
        double accentBaselineY = -raise;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        baseBox.drawInto(glyphs, rules, 0.0, 0.0);
        for (AccentPiece p : pieces) {
            glyphs.add(new PositionedGlyph(
                p.gid(), accentOriginX + scale * p.dx(), accentBaselineY, scale));
        }

        double height = Math.max(baseBox.height(), raise + scale * inkYMax);
        // Advance is the base width (an over-accent never widens the row); ink may
        // legitimately overhang, which the layout bbox accounts for.
        return new Box(glyphs, rules, baseBox.width(), height, baseBox.depth());
    }

    /**
     * Draws {@code \overline} / {@code \\underline} as a rule spanning the base
     * width, above or below the base with the MATH over/underbar gap, rule
     * thickness, and extra ascender/descender (all in-alphabet {@code <rect>}s).
     */
    private static Box overUnderLineBox(Accent accent, Box baseBox, LayoutContext ctx) {
        var c = ctx.constants();
        double scale = ctx.scale();
        double width = baseBox.width();

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        baseBox.drawInto(glyphs, rules, 0.0, 0.0);

        if (accent.under()) {
            double gap = c.underbarVerticalGap() * scale;
            double thickness = c.underbarRuleThickness() * scale;
            double extra = c.underbarExtraDescender() * scale;
            rules.add(new Rule(0.0, baseBox.depth() + gap, width, thickness));
            double depth = baseBox.depth() + gap + thickness + extra;
            return new Box(glyphs, rules, width, baseBox.height(), depth);
        }
        double gap = c.overbarVerticalGap() * scale;
        double thickness = c.overbarRuleThickness() * scale;
        double extra = c.overbarExtraAscender() * scale;
        double ruleTopY = -(baseBox.height() + gap + thickness);
        rules.add(new Rule(0.0, ruleTopY, width, thickness));
        double height = baseBox.height() + gap + thickness + extra;
        return new Box(glyphs, rules, width, height, baseBox.depth());
    }

    /**
     * Sizes a stretchy accent to (at least) {@code targetDesignWidth} using the
     * OpenType MATH horizontal glyph construction: the smallest pre-drawn variant
     * that is wide enough, else an assembled row of parts, else the widest variant,
     * else (documented last resort) the natural accent glyph. Fills {@code out}
     * with the pieces to draw, left→right, at x-offsets in design units.
     */
    private static void stretchHorizontal(SfntFont font, int baseGid,
                                          double targetDesignWidth, List<AccentPiece> out) {
        var construction = font.horizontalVariants(baseGid);
        int minSize = (int) Math.ceil(targetDesignWidth);
        if (construction != null) {
            var variant = construction.variantAtLeast(minSize);
            if (variant.isPresent()) {
                out.add(new AccentPiece(variant.getAsInt(), 0));
                return;
            }
            if (construction.hasAssembly()) {
                assembleHorizontal(font, construction.assembly(),
                    font.minConnectorOverlap(), minSize, out);
                return;
            }
            var variants = construction.variants();
            if (!variants.isEmpty()) {
                out.add(new AccentPiece(variants.get(variants.size() - 1).glyphId(), 0));
                return;
            }
        }
        // No horizontal construction: the natural accent glyph, unstretched.
        out.add(new AccentPiece(baseGid, 0));
    }

    /**
     * Assembles a wide accent from a {@link com.lattex.font.GlyphAssembly}: fixed
     * ends plus repeated extenders, adjacent pieces overlapping by
     * {@code minConnectorOverlap}. Repeats the extenders the fewest times needed to
     * reach {@code minSizeDesign}, then lays the parts out left→right at their
     * cumulative advances (design units). Mirrors {@link #assembledStack} on the
     * horizontal axis; a completeness fallback for bases wider than the largest
     * pre-drawn variant.
     */
    private static void assembleHorizontal(SfntFont font,
                                           com.lattex.font.GlyphAssembly assembly,
                                           int overlap, int minSizeDesign, List<AccentPiece> out) {
        var parts = assembly.parts();
        boolean hasExtender = parts.stream().anyMatch(com.lattex.font.GlyphPart::isExtender);
        int rep = 1;
        List<com.lattex.font.GlyphPart> stack = expandAssembly(parts, rep);
        while (hasExtender && assemblySpanDesign(stack, overlap) < minSizeDesign) {
            stack = expandAssembly(parts, ++rep);
        }
        int dx = 0;
        for (com.lattex.font.GlyphPart part : stack) {
            out.add(new AccentPiece(part.glyphId(), dx));
            dx += part.fullAdvance() - overlap;
        }
    }

    // ------------------------------------------------------------------
    // Stretchy vertical glyph construction — pick a pre-designed vertical variant
    // big enough for the content, else assemble one from parts, else (documented
    // last resort) uniformly scale the base glyph. Shared by the radical surd and
    // the \left..\right delimiters (a stretchy delimiter is the same problem as a
    // stretchy surd).
    // ------------------------------------------------------------------

    /**
     * One drawable piece of a stretchy glyph: a glyph at {@code scale}, whose ink
     * top lies {@code topOffset} user units below the glyph's overall ink top, and
     * whose outline reaches {@code yMax} at that top (design units).
     */
    private record StretchyPiece(int gid, double scale, double topOffset, double yMax) {
    }

    /**
     * A chosen stretchy rendering: the pieces to draw (top→bottom), the advance
     * {@code width} used to place adjacent content, and the total ink {@code span}
     * (all user units). Placed by aligning the assembly's ink top to a given y.
     */
    private record StretchyGlyph(List<StretchyPiece> pieces, double width, double span) {
        void placeInto(List<PositionedGlyph> out, double x, double inkTopY) {
            for (StretchyPiece p : pieces) {
                // inkTop = baselineY - scale*yMax  ⇒  baselineY = inkTop + scale*yMax.
                double baselineY = inkTopY + p.topOffset() + p.scale() * p.yMax();
                out.add(new PositionedGlyph(p.gid(), x, baselineY, p.scale()));
            }
        }
    }

    private static StretchyGlyph stretchVertical(SfntFont font, int baseGid,
                                                 double requiredSpan, double scale) {
        var construction = font.verticalVariants(baseGid);
        int minSizeDesign = (int) Math.ceil(requiredSpan / scale);

        if (construction != null) {
            var variant = construction.variantAtLeast(minSizeDesign);
            if (variant.isPresent()) {
                return singleVariant(font, variant.getAsInt(), scale);
            }
            if (construction.hasAssembly()) {
                return assembledStack(font, construction.assembly(),
                    font.minConnectorOverlap(), minSizeDesign, scale);
            }
            // Variants exist but none is tall enough and there is no assembly:
            // use the largest pre-drawn variant (best available, undistorted).
            var variants = construction.variants();
            if (!variants.isEmpty()) {
                return singleVariant(font, variants.get(variants.size() - 1).glyphId(), scale);
            }
        }

        // Last resort (documented): no MATH construction at all — uniformly scale
        // the base glyph to span the content. This thickens the strokes, so it is
        // only reached for fonts lacking a vertical construction for the glyph.
        GlyphOutline o = font.outline(baseGid);
        double baseSpan = Math.max(1.0, o.yMax() - o.yMin());
        double glyphScale = Math.max(scale, requiredSpan / baseSpan);
        return new StretchyGlyph(
            List.of(new StretchyPiece(baseGid, glyphScale, 0.0, o.yMax())),
            font.advanceWidth(baseGid) * glyphScale,
            (o.yMax() - o.yMin()) * glyphScale);
    }

    /** A stretchy glyph rendered from a single pre-drawn variant at normal scale. */
    private static StretchyGlyph singleVariant(SfntFont font, int gid, double scale) {
        GlyphOutline o = font.outline(gid);
        return new StretchyGlyph(
            List.of(new StretchyPiece(gid, scale, 0.0, o.yMax())),
            font.advanceWidth(gid) * scale,
            (o.yMax() - o.yMin()) * scale);
    }

    /**
     * Builds a stretchy glyph from a {@link com.lattex.font.GlyphAssembly}: fixed
     * end parts plus repeated extenders, adjacent pieces overlapping by
     * {@code minConnectorOverlap} (design units). Parts are stored bottom→top; we
     * repeat the extenders the fewest times needed to reach {@code minSizeDesign}
     * (minimum overlap ⇒ maximum height per repeat), then place the expanded stack
     * top→bottom with each piece's ink top aligned to its tile top. This path is a
     * completeness fallback for content taller than the largest pre-drawn variant;
     * for the surd the small residual overshoot is absorbed by the Rule-11 split.
     */
    private static StretchyGlyph assembledStack(SfntFont font,
                                           com.lattex.font.GlyphAssembly assembly,
                                           int overlap, int minSizeDesign, double scale) {
        var parts = assembly.parts();
        boolean hasExtender = parts.stream().anyMatch(com.lattex.font.GlyphPart::isExtender);

        // Fewest extender repetitions to reach the target (or 1 if no extenders).
        int rep = 1;
        List<com.lattex.font.GlyphPart> stack = expandAssembly(parts, rep);
        while (hasExtender && assemblySpanDesign(stack, overlap) < minSizeDesign) {
            stack = expandAssembly(parts, ++rep);
        }

        // Place bottom→top tiling into pieces ordered top→bottom.
        int spanDesign = assemblySpanDesign(stack, overlap);
        List<StretchyPiece> pieces = new ArrayList<>(stack.size());
        double maxWidth = 0.0;
        double topOffset = 0.0;
        for (int i = stack.size() - 1; i >= 0; i--) { // top part is last in bottom→top order
            com.lattex.font.GlyphPart part = stack.get(i);
            GlyphOutline o = font.outline(part.glyphId());
            pieces.add(new StretchyPiece(part.glyphId(), scale, topOffset, o.yMax()));
            maxWidth = Math.max(maxWidth, font.advanceWidth(part.glyphId()) * scale);
            topOffset += (part.fullAdvance() - overlap) * scale;
        }
        return new StretchyGlyph(pieces, maxWidth, spanDesign * scale);
    }

    /** The parts list with each extender repeated {@code rep} times (bottom→top). */
    private static List<com.lattex.font.GlyphPart> expandAssembly(
            List<com.lattex.font.GlyphPart> parts, int rep) {
        List<com.lattex.font.GlyphPart> out = new ArrayList<>();
        for (com.lattex.font.GlyphPart p : parts) {
            int times = p.isExtender() ? rep : 1;
            for (int i = 0; i < times; i++) {
                out.add(p);
            }
        }
        return out;
    }

    /** Total design-unit extent of a stacked assembly at the minimum overlap. */
    private static int assemblySpanDesign(List<com.lattex.font.GlyphPart> stack, int overlap) {
        int sum = 0;
        for (com.lattex.font.GlyphPart p : stack) {
            sum += p.fullAdvance();
        }
        return sum - overlap * Math.max(0, stack.size() - 1);
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

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
