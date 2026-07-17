package com.lattex.layout;

import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.api.Color;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathSyntaxException;
import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Colored;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathNode.LimitsMode;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.RowRule;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.StyledMath;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
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

    /**
     * {@code align} inter-column gaps, in em. Within an equation's right/left pair
     * the columns meet at the relation with a {@code \thickmathspace} (5mu) gap,
     * mirroring the space a relation carries on its far side inside the RHS cell;
     * between successive equations on one line a {@code \qquad} (2em) separates them.
     */
    private static final double ALIGN_RELATION_GAP = 5.0 / 18.0;   // \thickmathspace
    private static final double ALIGN_INTER_EQ_GAP = 2.0;          // \qquad

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

    /**
     * Independent layout-recursion depth bound. Layout recurses as deep as the node
     * tree ({@link #layoutBox} calls itself for every child), and a {@code
     * StackOverflowError} here is an {@code Error} — it escapes the caller's {@code
     * catch (RuntimeException)} guards and kills the render thread. The parser's {@code
     * MAX_DEPTH} bounds the tree today, but that is a DIFFERENT module for a DIFFERENT
     * reason (parse-stack safety); layout frames are heavier (Box allocations), so a
     * future {@code MAX_DEPTH} bump — or a programmatic {@link MathNode} tree built
     * without the parser — would silently re-open thread death. This is layout's own
     * guarantee, surfaced as a caught {@link MathSyntaxException}.
     */
    static final int MAX_LAYOUT_DEPTH = 512;

    /** Per-thread layout recursion depth (the engine is static/stateless). */
    private static final ThreadLocal<int[]> LAYOUT_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private static Box layoutBox(MathNode node, LayoutContext ctx) {
        int[] depth = LAYOUT_DEPTH.get();
        if (++depth[0] > MAX_LAYOUT_DEPTH) {
            depth[0]--; // balance the counter before unwinding
            throw new MathSyntaxException(
                "layout nesting too deep: exceeds the " + MAX_LAYOUT_DEPTH + "-level limit");
        }
        try {
            return layoutBoxDispatch(node, ctx);
        } finally {
            depth[0]--;
        }
    }

    private static Box layoutBoxDispatch(MathNode node, LayoutContext ctx) {
        return switch (node) {
            case Atom atom -> atomBox(atom, ctx);
            case MathList(var items) -> rowBox(items, ctx);
            case SupSub(var base, var sup, var sub) -> scriptsBox(base, sup, sub, ctx);
            case Fraction frac -> fractionBox(frac, ctx);
            case Radical(var radicand, var index) -> radicalBox(radicand, index, ctx);
            case Spacing(var muWidth) -> Box.glue(muWidth * ctx.mu());
            case Colored(var body, var color) -> coloredBox(body, color, ctx);
            case MathNode.Boxed(var body) -> boxedBox(body, ctx);
            case Phantom(var content, var keepW, var keepV) ->
                phantomBox(content, keepW, keepV, ctx);
            case BigOperator(var op, var lower, var upper, var limitsMode) ->
                bigOperatorBox(op, lower, upper, limitsMode, ctx);
            case Fenced(var leftDelim, var body, var rightDelim) ->
                fencedBox(leftDelim, body, rightDelim, ctx);
            // Standalone fallback only — the parser emits MiddleDelim solely inside a
            // Fenced body, where fencedBox consumes it in place (segment boundary).
            case MathNode.MiddleDelim(var delimCp) ->
                atomBox(new Atom(delimCp, MathClass.REL), ctx);
            case MathNode.SizedDelim sd -> sizedDelimBox(sd, ctx);
            case Accent accent -> accentBox(accent, ctx);
            case OperatorName opName -> operatorNameBox(opName, ctx);
            case TextRun textRun -> textRunBox(textRun, ctx);
            case Matrix matrix -> matrixBox(matrix, ctx);
            case MathNode.Stack stack -> stackBox(stack, ctx);
            case MathNode.XArrow xArrow -> xArrowBox(xArrow, ctx);
            // A CdArrow only ever appears as a CD-grid cell (laid out by cdBox with its
            // column/row context); this arm is the exhaustiveness fallback for a bare arrow,
            // rendered at natural size.
            case MathNode.CdArrow cdArrow -> cdArrowStandaloneBox(cdArrow, ctx);
            case MathNode.Tagged(var body, var label) -> taggedBox(body, label, ctx);
            // A top-level \lx wrapper: its RenderOptions (scale / mathStyle / color)
            // are applied at the render entry by seeding the LayoutContext + the
            // emitter fill, so here we simply lay out the wrapped body. (\lx is
            // top-level-only for the MVP, so this arm is effectively the render-entry
            // unwrap; it also keeps the sealed switch exhaustive without a default.)
            case StyledMath sm -> layoutBox(sm.body(), ctx);
            case MathNode.StyleSwitch(var level, var body) ->
                layoutBox(body, ctx.atStyle(switch (level) {
                    case DISPLAY -> MathStyle.DISPLAY;
                    case TEXT -> MathStyle.TEXT;
                    case SCRIPT -> MathStyle.SCRIPT;
                    case SCRIPT_SCRIPT -> MathStyle.SCRIPT_SCRIPT;
                }));
        };
    }

    /** A single glyph on the baseline; height/depth are its ink extents. */
    private static Box atomBox(Atom atom, LayoutContext ctx) {
        SfntFont font = ctx.font();
        double scale = ctx.scale();
        int gid = font.glyphId(atom.codePoint());
        GlyphOutline o = font.outline(gid);
        // Carry the source code point so the glyphmap can key token identity to this
        // glyph's emitted <path> (the data-lx-glyphmap sidecar the `thread` effect reads),
        // and the current fence depth so the precedence-cascade groupmap can key this
        // glyph to its paren-nesting level (the data-lx-groupmap sidecar).
        PositionedGlyph glyph = new PositionedGlyph(gid, 0.0, 0.0, scale, null,
            atom.codePoint(), ctx.fenceDepth());
        double width = font.advanceWidth(gid) * scale;
        double height = o.isEmpty() ? 0.0 : Math.max(0.0, o.yMax() * scale);
        double depth = o.isEmpty() ? 0.0 : Math.max(0.0, -o.yMin() * scale);
        return new Box(List.of(glyph), List.of(), width, height, depth);
    }

    /**
     * A phantom box: lay out the content to borrow its metrics, then discard all
     * ink (glyphs and rules), keeping only the selected dimensions. {@code \phantom}
     * keeps width and vertical extent; {@code \hphantom} keeps width; {@code
     * \vphantom} / {@code \mathstrut} keep height and depth. Emitting no ink, it is
     * trivially within the minimal SVG alphabet.
     */
    private static Box phantomBox(MathNode content, boolean keepWidth,
                                  boolean keepVertical, LayoutContext ctx) {
        Box inner = layoutBox(content, ctx);
        return new Box(List.of(), List.of(),
            keepWidth ? inner.width() : 0.0,
            keepVertical ? inner.height() : 0.0,
            keepVertical ? inner.depth() : 0.0);
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
            glyphs.add(new PositionedGlyph(gid, penX, 0.0, scale, ctx.fenceDepth())); // carry fence depth so function/text words light with their group (F2)
            penX += font.advanceWidth(gid) * scale;
            if (!o.isEmpty()) {
                height = Math.max(height, o.yMax() * scale);
                depth = Math.max(depth, -o.yMin() * scale);
            }
        }
        return new Box(glyphs, List.of(), penX, Math.max(0.0, height), Math.max(0.0, depth));
    }

    // ------------------------------------------------------------------
    // TextRun — a text-mode word (\text, \textbf, \textit, \texttt, \mathrm) set
    // upright (or in the requested shape) at the surrounding font size. Unlike a
    // math row, letters carry NO inter-atom spacing (a word is one unit) and the
    // literal inter-word spaces render as real gaps (the font's space advance) —
    // math mode ignores spaces, text mode keeps them (TeXbook Ch.18). Every
    // character, spaces included, is a filled glyph <path>; the space glyph is
    // inkless, so it advances the pen without emitting ink.
    // ------------------------------------------------------------------

    private static Box textRunBox(TextRun textRun, LayoutContext ctx) {
        SfntFont font = ctx.font();
        double scale = ctx.scale();
        String text = textRun.text();
        TextStyle style = textRun.style();
        double spaceAdvance = font.advanceWidth(font.glyphId(' ')) * scale;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        double penX = 0.0;
        double height = 0.0;
        double depth = 0.0;

        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                penX += spaceAdvance; // a real inter-word gap
                continue;
            }
            int styledCp = styledCodePoint(cp, style);
            int gid = font.glyphId(styledCp);
            if (gid == 0 && styledCp != cp) {
                gid = font.glyphId(cp); // font lacks the shaped variant: plain glyph
            }
            GlyphOutline o = font.outline(gid);
            glyphs.add(new PositionedGlyph(gid, penX, 0.0, scale, ctx.fenceDepth())); // carry fence depth so function/text words light with their group (F2)
            penX += font.advanceWidth(gid) * scale;
            if (!o.isEmpty()) {
                height = Math.max(height, o.yMax() * scale);
                depth = Math.max(depth, -o.yMin() * scale);
            }
        }
        return new Box(glyphs, List.of(), penX, Math.max(0.0, height), Math.max(0.0, depth));
    }

    /**
     * Maps an ASCII letter/digit to its {@link TextStyle}-shaped code point in the
     * Unicode Mathematical Alphanumeric Symbols block (the bundled math font's
     * bold/italic/monospace families). {@link TextStyle#ROMAN} keeps the plain
     * upright glyph; non-letter/non-digit characters (punctuation, symbols) are
     * left unchanged in every style (there is no shaped variant, and text
     * punctuation is upright regardless). Clean-room from the Unicode code charts.
     */
    private static int styledCodePoint(int cp, TextStyle style) {
        return switch (style) {
            case ROMAN -> cp;
            // Upright bold: A→U+1D400, a→U+1D41A, 0→U+1D7CE (contiguous, no holes).
            case BOLD -> mapAlphaNumeric(cp, 0x1D400, 0x1D41A, 0x1D7CE);
            case ITALIC -> italicCodePoint(cp);
            // Monospace: A→U+1D670, a→U+1D68A, 0→U+1D7F6 (contiguous, no holes).
            case MONO -> mapAlphaNumeric(cp, 0x1D670, 0x1D68A, 0x1D7F6);
        };
    }

    /** ASCII letter/digit → a contiguous Mathematical Alphanumeric range; else unchanged. */
    private static int mapAlphaNumeric(int cp, int upperBase, int lowerBase, int digitBase) {
        if (cp >= 'A' && cp <= 'Z') {
            return upperBase + (cp - 'A');
        }
        if (cp >= 'a' && cp <= 'z') {
            return lowerBase + (cp - 'a');
        }
        if (cp >= '0' && cp <= '9') {
            return digitBase + (cp - '0');
        }
        return cp;
    }

    /**
     * ASCII letter → Mathematical Italic. The italic small "h" (U+1D455) is a
     * reserved hole in the block — Unicode places it at U+210E (PLANCK CONSTANT).
     * Digits have no italic form, so they stay upright ASCII (as in LaTeX text).
     */
    private static int italicCodePoint(int cp) {
        if (cp == 'h') {
            return 0x210E; // reserved-hole substitute
        }
        if (cp >= 'A' && cp <= 'Z') {
            return 0x1D434 + (cp - 'A');
        }
        if (cp >= 'a' && cp <= 'z') {
            return 0x1D44E + (cp - 'a');
        }
        return cp;
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
    /**
     * Lays out {@code body} exactly as usual, then stamps {@code color} onto every
     * glyph and rule it produced — so the color is a pure presentation overlay with
     * no effect on geometry. {@code paintedWith} only sets the color where none is
     * present, so an inner {@code \color} wrapped by an outer one keeps the inner.
     */
    private static Box coloredBox(MathNode body, Color color, LayoutContext ctx) {
        Box b = layoutBox(body, ctx);
        return new Box(
            b.glyphs().stream().map(g -> g.paintedWith(color)).toList(),
            b.rules().stream().map(r -> r.paintedWith(color)).toList(),
            b.width(), b.height(), b.depth());
    }

    /**
     * {@code \boxed{body}}: the body inside a rule-rectangle frame at a fixed
     * padding ({@code \fboxsep}) and thickness ({@code \fboxrule}). The frame is
     * four {@code <rect>}s (in-alphabet); the box is one atom wider/taller than the
     * body by the padding + frame on every side, centred on the body's baseline.
     */
    private static Box boxedBox(MathNode body, LayoutContext ctx) {
        Box b = layoutBox(body, ctx);
        double em = 18.0 * ctx.mu();
        double t = Math.max(ctx.constants().fractionRuleThickness() * ctx.scale(), 0.6 * ctx.mu());
        double pad = 0.25 * em;                       // ~\fboxsep
        double w = b.width() + 2 * (pad + t);
        double innerTop = -b.height() - pad;          // padding above the body ink
        double innerBottom = b.depth() + pad;         // padding below
        double outerTop = innerTop - t;
        double outerBottom = innerBottom + t;
        double frameH = outerBottom - outerTop;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        b.drawInto(glyphs, rules, t + pad, 0.0);       // body sits inside the frame
        rules.add(new Rule(0, outerTop, w, t));        // top
        rules.add(new Rule(0, innerBottom, w, t));     // bottom
        rules.add(new Rule(0, outerTop, t, frameH));   // left
        rules.add(new Rule(w - t, outerTop, t, frameH)); // right
        return new Box(glyphs, rules, w, -outerTop, outerBottom);
    }

    /**
     * A {@code \tag'd} equation: the body, then the label auto-wrapped in ordinary
     * parentheses ({@code \tag{1}} renders as {@code (1)}) and placed flush-right
     * after a {@code \qquad}-sized gap. {@code \tag} is equation-global, so this only
     * appears at the top level.
     */
    private static Box taggedBox(MathNode body, MathNode label, LayoutContext ctx) {
        Box bodyBox = layoutBox(body, ctx);
        Box labelBox = layoutBox(new MathList(List.of(
            new Atom('(', MathClass.OPEN), label, new Atom(')', MathClass.CLOSE))), ctx);
        double gap = 2.0 * ctx.fontSize(); // ~\qquad between the equation and its tag
        double labelX = bodyBox.width() + gap;
        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        bodyBox.drawInto(glyphs, rules, 0.0, 0.0);
        labelBox.drawInto(glyphs, rules, labelX, 0.0);
        return new Box(glyphs, rules, labelX + labelBox.width(),
            Math.max(bodyBox.height(), labelBox.height()),
            Math.max(bodyBox.depth(), labelBox.depth()));
    }

    private static MathClass classOf(MathNode node) {
        return switch (node) {
            case Atom atom -> atom.mathClass();
            case SupSub(var base, _, _) -> classOf(base);
            // Standalone spacing class only — inside a Fenced body the delimiter is
            // consumed positionally by fencedWithMiddles, not spaced as an atom.
            case MathNode.MiddleDelim _ -> MathClass.REL;
            case Fraction _ -> MathClass.ORD;
            case Radical _ -> MathClass.ORD;
            case Fenced _ -> MathClass.INNER;
            case MathNode.SizedDelim sd -> sd.mathClass(); // the declared \bigl/\bigr/\bigm class
            case BigOperator _ -> MathClass.OP;
            case MathList _ -> MathClass.ORD; // a {group} behaves as an Ord atom
            case Accent _ -> MathClass.ORD;   // an accented nucleus is Ord
            case Colored c -> classOf(c.body()); // color is transparent: take the body's class
            case MathNode.Boxed _ -> MathClass.ORD; // a framed box behaves as an Ord atom
            case MathNode.Tagged t -> classOf(t.body()); // the tag rides outside; class = body's
            case Phantom _ -> MathClass.ORD;  // a phantom box behaves as an Ord atom
            case OperatorName _ -> MathClass.OP; // a named operator is class Op
            case TextRun _ -> MathClass.ORD;   // a text run behaves as an Ord atom
            // A delimited grid behaves as an Inner sub-formula (like \left..\right);
            // a bare grid (matrix/array with no fence) behaves as an Ord atom.
            case Matrix m -> m.hasDelimiters() ? MathClass.INNER : MathClass.ORD;
            // A brace stack behaves as an Ord atom in a row (so a following binary op
            // keeps its binary spacing); \stackrel is Rel (TeXbook); \overset/\\underset
            // keep the base's own class.
            case MathNode.Stack st -> switch (st.kind()) {
                case UNDERBRACE, OVERBRACE -> MathClass.ORD;
                case STACKREL -> MathClass.REL;
                case OVERSET, UNDERSET -> classOf(st.base());
            };
            // An extensible labelled arrow spaces exactly like the plain arrow
            // relation it stretches (amsmath: xrightarrow is a mathrel).
            case MathNode.XArrow _ -> MathClass.REL;
            case MathNode.CdArrow _ -> MathClass.REL;  // a connector behaves as a relation
            case Spacing _ -> null;           // classless glue (handled by caller)
            case StyledMath sm -> classOf(sm.body()); // wrapper is transparent to spacing
            case MathNode.StyleSwitch sw -> classOf(sw.body()); // ditto — style is invisible to spacing
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

        // The per-glyph kern staircases only exist for a single-glyph nucleus.
        com.lattex.font.MathKernInfo baseKern = base instanceof Atom baseAtom
            ? font.mathKernInfo(font.glyphId(baseAtom.codePoint())) : null;
        Box supBox = sup == null ? null : layoutBox(sup, ctx.superscript());
        Box subBox = sub == null ? null : layoutBox(sub, ctx.subscript());
        return attachScripts(baseBox, simpleChar, italic, supBox, subBox, baseKern, ctx);
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
                                     Box supBox, Box subBox,
                                     com.lattex.font.MathKernInfo baseKern, LayoutContext ctx) {
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

        // OpenType math-kern cut-ins (L9, plan lattex-mathkern-scripts): the MATH
        // table's per-corner staircases say how far a script may tuck INTO (negative)
        // or must clear (positive) the base glyph at the script's landing height —
        // superscripts read the base's TopRight corner at the height of the script's
        // BOTTOM ink edge, subscripts the BottomRight corner at their TOP ink edge
        // (heights in font design units, so divide the pixel height by the scale the
        // metrics were multiplied by). Base-glyph side only: the script side of the
        // spec pairing needs a single-glyph script, which a composed script box
        // rarely is — a documented follow-on, not an accident.
        double supKern = 0.0;
        double subKern = 0.0;
        if (baseKern != null) {
            if (supBox != null && baseKern.topRight() != null) {
                int h = (int) Math.round((supShift - supBox.depth()) / baseScale);
                supKern = baseKern.topRight().kernAtHeight(h) * baseScale;
            }
            if (subBox != null && baseKern.bottomRight() != null) {
                int h = (int) Math.round((subBox.height() - subShift) / baseScale);
                subKern = baseKern.bottomRight().kernAtHeight(h) * baseScale;
            }
        }

        if (supBox != null) {
            supBox.drawInto(glyphs, rules, baseBox.width() + italic + supKern, -supShift);
            height = Math.max(height, supShift + supBox.height());
            depth = Math.max(depth, supBox.depth() - supShift); // usually negative → ignored
            scriptsRight = Math.max(scriptsRight, baseBox.width() + italic + supKern + supBox.width());
        }
        if (subBox != null) {
            subBox.drawInto(glyphs, rules, baseBox.width() + subKern, subShift);
            depth = Math.max(depth, subShift + subBox.depth());
            height = Math.max(height, subBox.height() - subShift); // usually negative → ignored
            scriptsRight = Math.max(scriptsRight, baseBox.width() + subKern + subBox.width());
        }

        double width = scriptsRight + c.spaceAfterScript() * baseScale;
        return new Box(glyphs, rules, width, Math.max(0.0, height), Math.max(0.0, depth));
    }

    // ------------------------------------------------------------------
    // Fraction — numerator/denominator stacked on the math axis.
    // ------------------------------------------------------------------

    private static Box fractionBox(Fraction frac, LayoutContext ctx0) {
        // \cfrac / \dbinom force display style, \tbinom forces text; \frac / \binom
        // inherit the surrounding style (TeXbook \genfrac style argument).
        LayoutContext ctx = switch (frac.fractionStyle()) {
            case INHERIT -> ctx0;
            case DISPLAY -> ctx0.displayStyle();
            case TEXT -> ctx0.textStyle();
        };
        var c = ctx.constants();
        double scale = ctx.scale();
        boolean display = ctx.style().isDisplay();

        Box numBox = layoutBox(frac.numerator(), ctx.numerator());
        Box denBox = layoutBox(frac.denominator(), ctx.denominator());

        double axis = c.axisHeight() * scale;
        // A rule-less fraction (\binom / \atop) has zero bar thickness.
        double thickness = frac.hasRule() ? c.fractionRuleThickness() * scale : 0.0;
        double ruleHalf = thickness / 2.0;

        double numShiftUp;
        double denShiftDown;
        if (frac.hasRule()) {
            numShiftUp = (display
                ? c.fractionNumeratorDisplayStyleShiftUp() : c.fractionNumeratorShiftUp()) * scale;
            denShiftDown = (display
                ? c.fractionDenominatorDisplayStyleShiftDown() : c.fractionDenominatorShiftDown()) * scale;
            double numGapMin = (display
                ? c.fractionNumDisplayStyleGapMin() : c.fractionNumeratorGapMin()) * scale;
            double denGapMin = (display
                ? c.fractionDenomDisplayStyleGapMin() : c.fractionDenominatorGapMin()) * scale;

            // Keep the numerator's bottom clear of the rule's top by at least numGapMin;
            // symmetrically for the denominator below the rule.
            numShiftUp = Math.max(numShiftUp, axis + ruleHalf + numGapMin + numBox.depth());
            denShiftDown = Math.max(denShiftDown, denBox.height() - axis + ruleHalf + denGapMin);
        } else {
            // A rule-less stack (\binom / \atop) uses the OpenType stack* family,
            // not the tighter fraction* spacing — stackGapMin is ~3× the fraction
            // gap, matching TeX's \atop geometry (TeXbook Appendix-G Rule 15).
            numShiftUp = (display
                ? c.stackTopDisplayStyleShiftUp() : c.stackTopShiftUp()) * scale;
            denShiftDown = (display
                ? c.stackBottomDisplayStyleShiftDown() : c.stackBottomShiftDown()) * scale;
            double gapMin = (display
                ? c.stackDisplayStyleGapMin() : c.stackGapMin()) * scale;

            // Enforce the minimum gap between the numerator's bottom and the
            // denominator's top, splitting any shortfall evenly around the axis.
            double gap = (numShiftUp - numBox.depth()) - (denBox.height() - denShiftDown);
            if (gap < gapMin) {
                double need = (gapMin - gap) / 2.0;
                numShiftUp += need;
                denShiftDown += need;
            }
        }

        double width = Math.max(numBox.width(), denBox.width());
        double numX = (width - numBox.width()) / 2.0;
        double denX = (width - denBox.width()) / 2.0;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        numBox.drawInto(glyphs, rules, numX, -numShiftUp);
        denBox.drawInto(glyphs, rules, denX, denShiftDown);
        if (frac.hasRule()) {
            rules.add(new Rule(0.0, -axis - ruleHalf, width, thickness));
        }

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
            // Carry the enclosing fence depth: a \sum/\int/\prod inside a fence is an
            // author atom and must light with its group, not dim as unresolved forever
            // (the lattex/180-filmed residual of the F2 class; follow-up taken at 182).
            List.of(new PositionedGlyph(opGid, 0.0, opBaseline, scale, ctx.fenceDepth())),
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
            return attachScripts(opBox, false, italic, upperBox, lowerBox,
                font.mathKernInfo(opGid), ctx);
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
    // Stack — a base with material set above and/or below it: the shared mechanism
    // behind \\underbrace/\overbrace (a horizontally-stretched brace plus a
    // script-size label), \stackrel/\overset (a script-size mark over the base) and
    // \\underset (under it). Clean-room from Knuth's TeXbook: \stackrel is a \mathop
    // carrying a limit script; \\underbrace is a \mathop whose brace decorates the
    // base with the label attached as a limit. Every layer is centred over the base
    // and separated by the OpenType MATH stackGapMin (stackDisplayStyleGapMin in
    // display), the same stack* family the rule-less fraction (\atop/\binom) uses.
    // ------------------------------------------------------------------

    private static Box stackBox(MathNode.Stack st, LayoutContext ctx) {
        var c = ctx.constants();
        double scale = ctx.scale();
        boolean display = ctx.style().isDisplay();
        double gap = (display ? c.stackDisplayStyleGapMin() : c.stackGapMin()) * scale;

        Box baseBox = layoutBox(st.base(), ctx);
        // The above/below marks (and the brace labels) are set at script size.
        Box aboveBox = st.above() == null ? null : layoutBox(st.above(), ctx.superscript());
        Box belowBox = st.below() == null ? null : layoutBox(st.below(), ctx.subscript());

        // Layers stacked outward from the base, nearest-first. The brace kinds insert
        // a stretched curly bracket (U+23DE over / U+23DF under) between base and label.
        List<Box> up = new ArrayList<>();
        List<Box> down = new ArrayList<>();
        switch (st.kind()) {
            case OVERSET, STACKREL -> {
                if (aboveBox != null) {
                    up.add(aboveBox);
                }
            }
            case UNDERSET -> {
                if (belowBox != null) {
                    down.add(belowBox);
                }
            }
            case OVERBRACE -> {
                up.add(horizontalStretchBox(ctx, 0x23DE, baseBox.width()));
                if (aboveBox != null) {
                    up.add(aboveBox);
                }
            }
            case UNDERBRACE -> {
                down.add(horizontalStretchBox(ctx, 0x23DF, baseBox.width()));
                if (belowBox != null) {
                    down.add(belowBox);
                }
            }
        }

        double width = baseBox.width();
        for (Box b : up) {
            width = Math.max(width, b.width());
        }
        for (Box b : down) {
            width = Math.max(width, b.width());
        }

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        baseBox.drawInto(glyphs, rules, (width - baseBox.width()) / 2.0, 0.0);

        // Going up: each layer's ink bottom sits `gap` above the current top boundary.
        double runUp = -baseBox.height();
        for (Box layer : up) {
            double layerBaseline = runUp - gap - layer.depth(); // stack-gap application (above)
            layer.drawInto(glyphs, rules, (width - layer.width()) / 2.0, layerBaseline);
            runUp = layerBaseline - layer.height();
        }
        // Going down: each layer's ink top sits `gap` below the current bottom boundary.
        double runDown = baseBox.depth();
        for (Box layer : down) {
            double layerBaseline = runDown + gap + layer.height(); // stack-gap application (below)
            layer.drawInto(glyphs, rules, (width - layer.width()) / 2.0, layerBaseline);
            runDown = layerBaseline + layer.depth();
        }

        return new Box(glyphs, rules, width, Math.max(0.0, -runUp), Math.max(0.0, runDown));
    }

    /**
     * Builds a horizontally-stretched glyph — a brace ({@code U+23DE}/{@code U+23DF})
     * or an extensible arrow ({@code U+2192}/{@code U+2190}) — sized to at least
     * {@code targetWidth} (user units) via the OpenType MATH horizontal glyph
     * construction — the same {@link #stretchHorizontal} machinery a wide accent
     * ({@code \widehat}) uses — returned as a {@link Box} whose glyphs sit on the
     * baseline (ink from {@code yMin}..{@code yMax}). No drawn geometry: the result
     * is an honest font glyph (single variant or assembled parts), and it is never
     * smaller than the glyph's natural size (the smallest adequate variant IS the
     * base glyph when the target is narrower than it).
     */
    private static Box horizontalStretchBox(LayoutContext ctx, int glyphCp, double targetWidth) {
        SfntFont font = ctx.font();
        double scale = ctx.scale();
        List<AccentPiece> pieces = new ArrayList<>();
        stretchHorizontal(font, font.glyphId(glyphCp), targetWidth / scale, pieces);

        List<PositionedGlyph> glyphs = new ArrayList<>();
        double inkYMax = Double.NEGATIVE_INFINITY;
        double inkYMin = Double.POSITIVE_INFINITY;
        double maxRight = 0.0;
        for (AccentPiece p : pieces) {
            GlyphOutline o = font.outline(p.gid());
            glyphs.add(new PositionedGlyph(p.gid(), p.dx() * scale, 0.0, scale));
            maxRight = Math.max(maxRight, (p.dx() + font.advanceWidth(p.gid())) * scale);
            if (!o.isEmpty()) {
                inkYMax = Math.max(inkYMax, o.yMax() * scale);
                inkYMin = Math.min(inkYMin, o.yMin() * scale);
            }
        }
        if (inkYMax < inkYMin) { // defensive: stretched glyph had no ink
            inkYMax = 0.0;
            inkYMin = 0.0;
        }
        return new Box(glyphs, List.of(), maxRight,
            Math.max(0.0, inkYMax), Math.max(0.0, -inkYMin));
    }

    // ------------------------------------------------------------------
    // XArrow — amsmath's extensible labelled arrow (xrightarrow / xleftarrow,
    // written without the backslash). The INVERSE of the brace stacks above:
    // a brace stretches the decoration to the base width, while here the base
    // (the arrow, U+2192 / U+2190 via the same OpenType MATH horizontal
    // construction) stretches until the script-size labels fit over/under it
    // with side padding — never shorter than the arrow's natural length. The
    // arrow's glyphs sit on the baseline exactly as the plain arrow atom does,
    // so the shaft lies at its designed axis height; labels are centred with
    // the same stackGapMin clearance the stack mechanism uses.
    // ------------------------------------------------------------------

    /** Side padding, in mu, between an arrow label's edge and the arrow's tips. */
    private static final double XARROW_SIDE_PAD_MU = 5.0;

    private static Box xArrowBox(MathNode.XArrow xa, LayoutContext ctx) {
        SfntFont font = ctx.font();
        var c = ctx.constants();
        double scale = ctx.scale();
        boolean display = ctx.style().isDisplay();
        double gap = (display ? c.stackDisplayStyleGapMin() : c.stackGapMin()) * scale;

        // Labels at script size, like a stack's above/below marks.
        Box aboveBox = layoutBox(xa.above(), ctx.superscript());
        Box belowBox = xa.below() == null ? null : layoutBox(xa.below(), ctx.subscript());

        double labelWidth = aboveBox.width();
        if (belowBox != null) {
            labelWidth = Math.max(labelWidth, belowBox.width());
        }

        // Stretch target: the widest label plus side padding, but at least the
        // arrow's natural advance (the label-width term is what makes the arrow
        // grow under a long label — dropping it collapses the stretch).
        int arrowCp = xa.kind().codePoint();
        double natural = font.advanceWidth(font.glyphId(arrowCp)) * scale;
        double pad = XARROW_SIDE_PAD_MU * ctx.mu();
        double target = Math.max(natural, labelWidth) + 2.0 * pad;
        Box arrowBox = horizontalStretchBox(ctx, arrowCp, target);

        double width = Math.max(arrowBox.width(), labelWidth);

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        arrowBox.drawInto(glyphs, rules, (width - arrowBox.width()) / 2.0, 0.0);

        // Above label: its ink bottom clears the arrow's ink top by the stack gap.
        double top = -arrowBox.height();
        double aboveBaseline = top - gap - aboveBox.depth();
        aboveBox.drawInto(glyphs, rules, (width - aboveBox.width()) / 2.0, aboveBaseline);
        top = aboveBaseline - aboveBox.height();

        // Below label: its ink top clears the arrow's ink bottom by the stack gap.
        double bottom = arrowBox.depth();
        if (belowBox != null) {
            double belowBaseline = bottom + gap + belowBox.height();
            belowBox.drawInto(glyphs, rules, (width - belowBox.width()) / 2.0, belowBaseline);
            bottom = belowBaseline + belowBox.depth();
        }

        return new Box(glyphs, rules, width, Math.max(0.0, -top), Math.max(0.0, bottom));
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

        // \middle support (L2, plan lattex-middle-evalbar): a body carrying
        // MiddleDelim children is laid out as segments with each middle delimiter
        // stretched to the SAME symmetric span as the outer pair. Bodies without
        // \middle take the pre-existing path below UNCHANGED (golden-stable).
        List<MathNode> flatBody = body instanceof MathNode.MathList(var items)
            ? items : List.of(body);
        // The body's atoms are one paren-level deeper (precedence-cascade fence depth);
        // the fence delimiters CARRY that same body depth so the pair lights exactly when
        // the group it closes completes (Charles's cascade design, 2026-07-14 — brackets
        // bolding read as "this value is finished"). insideFence preserves style so
        // scale/axis are identical — the deepening carries no metric change.
        LayoutContext bodyCtx = ctx.insideFence();
        if (flatBody.stream().anyMatch(n -> n instanceof MathNode.MiddleDelim)) {
            return fencedWithMiddles(leftDelim, flatBody, rightDelim, bodyCtx, font, axis, scale);
        }

        Box bodyBox = layoutBox(body, bodyCtx);

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
            double[] hd = placeDelimiter(font, leftDelim, requiredSpan, axis, scale, penX, glyphs,
                bodyCtx.fenceDepth());
            penX = hd[0];
            height = Math.max(height, hd[1]);
            depth = Math.max(depth, hd[2]);
        }
        bodyBox.drawInto(glyphs, rules, penX, 0.0);
        penX += bodyBox.width();
        if (rightDelim != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, rightDelim, requiredSpan, axis, scale, penX, glyphs,
                bodyCtx.fenceDepth());
            penX = hd[0];
            height = Math.max(height, hd[1]);
            depth = Math.max(depth, hd[2]);
        }
        return new Box(glyphs, rules, penX, height, depth);
    }

    /**
     * Segmented {@code \left..\middle..\right} layout (L2): every segment between
     * delimiters is laid out independently; the symmetric span requirement is taken
     * over ALL segments so the outer pair and every {@code \middle} delimiter get
     * the SAME size (TeX's rule — a \middle is sized exactly like \left/\right).
     */
    private static Box fencedWithMiddles(int leftDelim, List<MathNode> flatBody, int rightDelim,
                                         LayoutContext ctx, SfntFont font, double axis, double scale) {
        List<List<MathNode>> segments = new ArrayList<>();
        List<Integer> middles = new ArrayList<>();
        List<MathNode> current = new ArrayList<>();
        for (MathNode n : flatBody) {
            if (n instanceof MathNode.MiddleDelim(var delimCp)) {
                segments.add(current);
                middles.add(delimCp);
                current = new ArrayList<>();
            } else {
                current.add(n);
            }
        }
        segments.add(current);

        List<Box> segmentBoxes = new ArrayList<>();
        double requiredSpan = 0.0;
        double height = 0.0;
        double depth = 0.0;
        for (List<MathNode> segment : segments) {
            Box b = layoutBox(new MathNode.MathList(List.copyOf(segment)), ctx);
            segmentBoxes.add(b);
            double aboveAxis = b.height() - axis;
            double belowAxis = b.depth() + axis;
            requiredSpan = Math.max(requiredSpan, 2.0 * Math.max(aboveAxis, belowAxis) * DELIMITER_FACTOR);
            height = Math.max(height, b.height());
            depth = Math.max(depth, b.depth());
        }

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double penX = 0.0;
        if (leftDelim != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, leftDelim, requiredSpan, axis, scale, penX, glyphs,
                ctx.fenceDepth());
            penX = hd[0];
            height = Math.max(height, hd[1]);
            depth = Math.max(depth, hd[2]);
        }
        for (int i = 0; i < segmentBoxes.size(); i++) {
            Box seg = segmentBoxes.get(i);
            seg.drawInto(glyphs, rules, penX, 0.0);
            penX += seg.width();
            if (i < middles.size()) {
                double[] hd = placeDelimiter(font, middles.get(i), requiredSpan, axis, scale, penX,
                    glyphs, ctx.fenceDepth());
                penX = hd[0];
                height = Math.max(height, hd[1]);
                depth = Math.max(depth, hd[2]);
            }
        }
        if (rightDelim != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, rightDelim, requiredSpan, axis, scale, penX, glyphs,
                ctx.fenceDepth());
            penX = hd[0];
            height = Math.max(height, hd[1]);
            depth = Math.max(depth, hd[2]);
        }
        return new Box(glyphs, rules, penX, height, depth);
    }

    /** Fixed spans (as multiples of the em) for {@code \big}…{@code \Bigg}, indexed 1..4. */
    private static final double[] BIG_DELIM_SPAN = {0.0, 1.2, 1.8, 2.4, 3.0};

    /**
     * A manually-sized delimiter ({@code \big}/{@code \Big}/{@code \bigg}/{@code
     * \Bigg}): the same stretch machinery as {@code \left..\right}, but the target span
     * is a FIXED multiple of the em rather than the content height, centred on the math
     * axis. The declared class (OPEN/CLOSE/REL/ORD) rides on the node for spacing.
     */
    private static Box sizedDelimBox(MathNode.SizedDelim sd, LayoutContext ctx) {
        if (sd.delimCp() == MathNode.Fenced.NULL_DELIMITER) {
            return Box.glue(0.0); // \bigl. — an invisible (null) delimiter
        }
        SfntFont font = ctx.font();
        double scale = ctx.scale();
        double axis = ctx.constants().axisHeight() * scale;
        double span = BIG_DELIM_SPAN[sd.sizeLevel()] * ctx.fontSize();
        List<PositionedGlyph> glyphs = new ArrayList<>();
        double[] hd = placeDelimiter(font, sd.delimCp(), span, axis, scale, 0.0, glyphs);
        return new Box(glyphs, List.of(), hd[0], Math.max(0.0, hd[1]), Math.max(0.0, hd[2]));
    }

    /**
     * Sizes a single delimiter to {@code requiredSpan}, centres its ink on the
     * axis, stamps it at {@code x}, and returns {@code {newPenX, height, depth}}
     * (the delimiter's contribution to the enclosing box's extents).
     */
    private static double[] placeDelimiter(SfntFont font, int delimCp, double requiredSpan,
                                           double axis, double scale, double x,
                                           List<PositionedGlyph> glyphs) {
        return placeDelimiter(font, delimCp, requiredSpan, axis, scale, x, glyphs,
            PositionedGlyph.NO_RANK);
    }

    /// Depth-carrying overload — see StretchyGlyph.placeInto: only \left..\right pairs
    /// (and their \middle segments) pass a real depth; every other caller stays NO_RANK.
    private static double[] placeDelimiter(SfntFont font, int delimCp, double requiredSpan,
                                           double axis, double scale, double x,
                                           List<PositionedGlyph> glyphs, int fenceDepth) {
        StretchyGlyph d = stretchVertical(font, font.glyphId(delimCp), requiredSpan, scale);
        double inkTop = -axis - d.span() / 2.0; // centre the ink on the axis
        d.placeInto(glyphs, x, inkTop, fenceDepth);
        return new double[] {x + d.width(), axis + d.span() / 2.0, d.span() / 2.0 - axis};
    }

    // ------------------------------------------------------------------
    // Matrix — a 2-D grid (matrix family / array / cases). Column widths are the
    // per-column max cell advance; each row has its own height/depth; rows stack on
    // a baseline pitch (depth + gap + next height); the whole grid is centred on
    // the math axis and any enclosing delimiters stretch to the grid height (the
    // same S4 delimiter machinery as \left..\right). \hline/vertical rules and the
    // \hdashline dashes are all in-alphabet <rect>s.
    //
    // Spacing is clean-room from Knuth's TeXbook \halign template: \arraycolsep
    // (5pt ≈ 0.5em, so a 2·arraycolsep=1em inter-column gap and a 0.5em edge gap in
    // an array), the \quad (1em) inter-column gap of cases, tighter matrix gaps,
    // and a jot-sized inter-row gap. Cells are set one style down (text style;
    // smallmatrix in script style), like a fraction's numerator, never in display.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // CD — amscd commutative diagram. A grid whose object cells are ordinary
    // math and whose connector cells are CdArrows: horizontal arrows stretch to
    // the COLUMN width, vertical arrows span a fixed ROW pitch, each carrying
    // script-size side labels. Two passes: (1) lay out objects + arrow labels to
    // get column widths and row extents WITHOUT any stretch feedback (the shaft
    // never feeds its own column width); (2) build stretched connector boxes and
    // place every cell centred in its column on its row baseline.
    // ------------------------------------------------------------------

    private static final double CD_MIN_H_ARROW_EM = 2.2;  // min horizontal arrow/label span
    private static final double CD_V_ARROW_SPAN_EM = 1.5;  // vertical arrow span (row connector)
    private static final double CD_LABEL_PAD_EM = 0.25;    // gap between a vertical arrow and its side label
    private static final double CD_ROW_GAP_EM = 0.35;      // extra breathing room between rows
    private static final double CD_COL_GAP_EM = 0.4;       // extra breathing room between columns

    private static boolean isCdArrow(MathNode n) {
        return n instanceof MathNode.CdArrow;
    }

    private static Box cdBox(Matrix mx, LayoutContext ctx) {
        var c = ctx.constants();
        double scale = ctx.scale();
        double axis = c.axisHeight() * scale;
        double em = 18.0 * ctx.mu();
        double ruleThick = c.fractionRuleThickness() * scale;
        SfntFont font = ctx.font();

        int rows = mx.rows().size();
        int cols = mx.columnCount();
        LayoutContext cellCtx =
            new LayoutContext(font, c, ctx.fontSize(), MathStyle.TEXT, false, ctx.fenceDepth());
        LayoutContext labelCtx = cellCtx.superscript();  // script-size side/over labels

        double minHArrow = CD_MIN_H_ARROW_EM * em;
        double vSpan = CD_V_ARROW_SPAN_EM * em;
        double labelPad = CD_LABEL_PAD_EM * em;

        // --- Pass 1: object boxes + connector label boxes; column widths + row extents. ---
        Box[][] objectBox = new Box[rows][cols];        // non-arrow cells
        Box[][] labelA = new Box[rows][cols];
        Box[][] labelB = new Box[rows][cols];
        double[] colWidth = new double[cols];
        double[] rowHeight = new double[rows];
        double[] rowDepth = new double[rows];

        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                MathNode cellNode = mx.rows().get(r).get(col);
                if (cellNode instanceof MathNode.CdArrow arrow) {
                    Box la = arrow.labelA() == null ? null : layoutBox(arrow.labelA(), labelCtx);
                    Box lb = arrow.labelB() == null ? null : layoutBox(arrow.labelB(), labelCtx);
                    labelA[r][col] = la;
                    labelB[r][col] = lb;
                    double lblW = Math.max(la == null ? 0 : la.width(), lb == null ? 0 : lb.width());
                    if (arrow.kind().horizontal()) {
                        double w = arrow.kind() == MathNode.CdArrowKind.EMPTY ? 0 : Math.max(lblW, minHArrow);
                        colWidth[col] = Math.max(colWidth[col], w);
                        // Above/below labels add height/depth to this (object) row.
                        rowHeight[r] = Math.max(rowHeight[r], la == null ? 0 : la.height() + la.depth() + axis);
                        rowDepth[r] = Math.max(rowDepth[r], lb == null ? 0 : lb.height() + lb.depth());
                    } else {
                        double shaftW = arrow.kind().codePoint() == 0
                            ? 2 * ruleThick + labelPad
                            : font.advanceWidth(font.glyphId(arrow.kind().codePoint())) * scale;
                        double sideW = (la == null ? 0 : la.width()) + (lb == null ? 0 : lb.width());
                        colWidth[col] = Math.max(colWidth[col], shaftW + sideW + 2 * labelPad);
                        rowHeight[r] = Math.max(rowHeight[r], vSpan / 2 + axis);
                        rowDepth[r] = Math.max(rowDepth[r], vSpan / 2);
                    }
                } else {
                    Box b = layoutBox(cellNode, cellCtx);
                    objectBox[r][col] = b;
                    colWidth[col] = Math.max(colWidth[col], b.width());
                    rowHeight[r] = Math.max(rowHeight[r], b.height());
                    rowDepth[r] = Math.max(rowDepth[r], b.depth());
                }
            }
        }

        // --- Pass 2: build final cell boxes (connectors now stretch to col/row). ---
        Box[][] cell = new Box[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                MathNode cellNode = mx.rows().get(r).get(col);
                if (cellNode instanceof MathNode.CdArrow arrow) {
                    cell[r][col] = cdConnectorBox(arrow, colWidth[col], vSpan, labelA[r][col],
                        labelB[r][col], ctx, axis, ruleThick, labelPad);
                } else {
                    cell[r][col] = objectBox[r][col];
                }
            }
        }

        // --- Placement: rows stacked by pitch, cells centred in their column. ---
        double rowGap = CD_ROW_GAP_EM * em;
        double colGap = CD_COL_GAP_EM * em;
        double[] baseline = new double[rows];
        for (int r = 1; r < rows; r++) {
            baseline[r] = baseline[r - 1] + rowDepth[r - 1] + rowGap + rowHeight[r];
        }
        double gridTop = -rowHeight[0];
        double gridBottom = baseline[rows - 1] + rowDepth[rows - 1];
        double shift = -axis - (gridTop + gridBottom) / 2.0;
        for (int r = 0; r < rows; r++) {
            baseline[r] += shift;
        }
        double[] colX = new double[cols];
        double x = 0;
        for (int col = 0; col < cols; col++) {
            colX[col] = x;
            x += colWidth[col] + (col < cols - 1 ? colGap : 0);
        }
        double totalWidth = x;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                Box b = cell[r][col];
                if (b == null) {
                    continue;
                }
                double cx = colX[col] + (colWidth[col] - b.width()) / 2.0;
                b.drawInto(glyphs, rules, cx, baseline[r]);
            }
        }
        double boxHeight = -(gridTop + shift);
        double boxDepth = gridBottom + shift;
        return new Box(glyphs, rules, totalWidth, Math.max(0, boxHeight), Math.max(0, boxDepth));
    }

    /**
     * Builds one CD connector cell centred on the baseline: a horizontal arrow
     * stretched to {@code colWidth} with above/below labels, a vertical arrow
     * spanning {@code vSpan} with left/right labels, the {@code =}/{@code |} double
     * rules, or an empty spacer. The returned box is centred on the math axis.
     */
    private static Box cdConnectorBox(MathNode.CdArrow arrow, double colWidth, double vSpan,
                                      Box la, Box lb, LayoutContext ctx, double axis,
                                      double ruleThick, double labelPad) {
        var c = ctx.constants();
        double scale = ctx.scale();
        double gap = c.stackGapMin() * scale;
        SfntFont font = ctx.font();
        MathNode.CdArrowKind kind = arrow.kind();
        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();

        if (kind == MathNode.CdArrowKind.EMPTY) {
            return Box.glue(colWidth);
        }
        if (kind.horizontal()) {
            double width = colWidth;
            if (kind == MathNode.CdArrowKind.EQUAL) {
                // Two horizontal rules on the axis (a double line), U+003D has no
                // horizontal MATH construction so it is drawn in-alphabet as rects.
                double sep = 1.5 * ruleThick;
                rules.add(new Rule(0, -axis - sep / 2 - ruleThick, width, ruleThick));
                rules.add(new Rule(0, -axis + sep / 2, width, ruleThick));
                return new Box(glyphs, rules, width, axis + sep / 2 + ruleThick, 0);
            }
            Box shaft = horizontalStretchBox(ctx, kind.codePoint(), Math.max(width * 0.9, width - 2 * labelPad));
            double h = shaft.height();
            double d = shaft.depth();
            shaft.drawInto(glyphs, rules, (width - shaft.width()) / 2.0, 0.0);
            double top = -h;
            if (la != null) {
                double aBase = top - gap - la.depth();
                la.drawInto(glyphs, rules, (width - la.width()) / 2.0, aBase);
                top = aBase - la.height();
            }
            double bottom = d;
            if (lb != null) {
                double bBase = d + gap + lb.height();
                lb.drawInto(glyphs, rules, (width - lb.width()) / 2.0, bBase);
                bottom = bBase + lb.depth();
            }
            return new Box(glyphs, rules, width, Math.max(0, -top), Math.max(0, bottom));
        }
        // Vertical: shaft spanning vSpan, centred on the axis, labels to the sides.
        double laW = la == null ? 0 : la.width();
        double lbW = lb == null ? 0 : lb.width();
        double shaftW;
        double inkTopY = -axis - vSpan / 2.0;
        if (kind == MathNode.CdArrowKind.VEQUAL) {
            // Two vertical rules (a double bar) spanning vSpan, in-alphabet rects.
            double sep = 1.5 * ruleThick;
            double xL = laW + labelPad;
            rules.add(new Rule(xL, inkTopY, ruleThick, vSpan));
            rules.add(new Rule(xL + sep + ruleThick, inkTopY, ruleThick, vSpan));
            shaftW = sep + 2 * ruleThick;
        } else {
            StretchyGlyph sg = stretchVertical(font, font.glyphId(kind.codePoint()), vSpan, scale);
            sg.placeInto(glyphs, laW + labelPad, inkTopY);
            shaftW = sg.width();
        }
        double width = laW + lbW + shaftW + 2 * labelPad;
        double midY = -axis;  // vertical centre for the side labels
        if (la != null) {
            la.drawInto(glyphs, rules, 0, midY + (la.height() - la.depth()) / 2.0);
        }
        if (lb != null) {
            lb.drawInto(glyphs, rules, laW + labelPad + shaftW + labelPad,
                midY + (lb.height() - lb.depth()) / 2.0);
        }
        double halfSpan = vSpan / 2.0;
        return new Box(glyphs, rules, width, Math.max(0, axis + halfSpan), Math.max(0, halfSpan - axis));
    }

    /** Natural-size fallback for a bare {@link MathNode.CdArrow} outside a CD grid. */
    private static Box cdArrowStandaloneBox(MathNode.CdArrow arrow, LayoutContext ctx) {
        double em = 18.0 * ctx.mu();
        double axis = ctx.constants().axisHeight() * ctx.scale();
        double ruleThick = ctx.constants().fractionRuleThickness() * ctx.scale();
        Box la = arrow.labelA() == null ? null : layoutBox(arrow.labelA(), ctx.superscript());
        Box lb = arrow.labelB() == null ? null : layoutBox(arrow.labelB(), ctx.superscript());
        return cdConnectorBox(arrow, CD_MIN_H_ARROW_EM * em, CD_V_ARROW_SPAN_EM * em,
            la, lb, ctx, axis, ruleThick, CD_LABEL_PAD_EM * em);
    }

    private static Box matrixBox(Matrix mx, LayoutContext ctx) {
        if (mx.kind() == MatrixKind.CD) {
            return cdBox(mx, ctx);
        }
        var c = ctx.constants();
        double scale = ctx.scale();
        double axis = c.axisHeight() * scale;
        double em = 18.0 * ctx.mu();                 // one em at the current (outer) style
        double ruleThick = c.fractionRuleThickness() * scale;
        SfntFont font = ctx.font();

        // Cell math style: matrices/array/cases sit one style down (text),
        // smallmatrix two down (script); aligned-equation environments keep each
        // equation in display style (full-size fractions, big-op limits), un-cramped.
        MathStyle cellStyle = switch (mx.kind()) {
            case SMALL, SUBSTACK -> MathStyle.SCRIPT;
            case ALIGN, GATHER, MULTLINE -> MathStyle.DISPLAY;
            default -> MathStyle.TEXT;
        };
        // Preserve the ENCLOSING fence depth into the cell (Fixpoint F1, lattex/176): a matrix
        // is not a precedence boundary, so a cell atom inside a \left..\right fence must keep
        // that depth — the 5-arg form would reset it to 0 and emit a confidently-wrong groupmap
        // (the matrix-cell atom ranked WITH atoms outside the fence). A fence INSIDE a cell then
        // correctly deepens from the cell's inherited level.
        LayoutContext cellCtx =
            new LayoutContext(font, c, ctx.fontSize(), cellStyle, false, ctx.fenceDepth());

        int rows = mx.rows().size();
        int cols = mx.columnCount();

        // 1. Lay out every cell; derive per-column widths and per-row height/depth.
        Box[][] cell = new Box[rows][cols];
        double[] colWidth = new double[cols];
        double[] rowHeight = new double[rows];
        double[] rowDepth = new double[rows];
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                Box b = layoutBox(mx.rows().get(r).get(col), cellCtx);
                cell[r][col] = b;
                colWidth[col] = Math.max(colWidth[col], b.width());
                rowHeight[r] = Math.max(rowHeight[r], b.height());
                rowDepth[r] = Math.max(rowDepth[r], b.depth());
            }
        }

        // 2. Vertical stacking: row 0's baseline at local y=0, each subsequent row a
        // pitch of (prev depth + inter-row gap + this height) below.
        double interRowGap = switch (mx.kind()) {
            case SMALL, SUBSTACK -> 0.15 * em;
            case ALIGN, GATHER, MULTLINE -> 0.5 * em;   // matrix gap + \jot breathing room
            default -> 0.3 * em;
        };
        double[] baseline = new double[rows];
        for (int r = 1; r < rows; r++) {
            baseline[r] = baseline[r - 1] + rowDepth[r - 1] + interRowGap + rowHeight[r];
        }
        double gridTop = -rowHeight[0];
        double gridBottom = baseline[rows - 1] + rowDepth[rows - 1];
        // Centre the grid on the math axis (axis is at y = -axis, above the baseline).
        double shift = -axis - (gridTop + gridBottom) / 2.0;
        double boxHeight = -(gridTop + shift);
        double boxDepth = gridBottom + shift;
        double fullSpan = boxHeight + boxDepth;      // grid vertical extent
        double gridTopY = gridTop + shift;           // = -boxHeight
        for (int r = 0; r < rows; r++) {
            baseline[r] += shift;
        }

        // 3. Horizontal spacing template (clean-room TeXbook \halign — see above).
        double edgeGap;
        double colGap;
        switch (mx.kind()) {
            case ARRAY -> { edgeGap = 0.5 * em; colGap = 1.0 * em; }     // \arraycolsep = 5pt
            case CASES -> { edgeGap = 0.18 * em; colGap = 1.0 * em; }    // \quad between columns
            case SMALL -> { edgeGap = 0.1 * em; colGap = 0.3 * em; }
            // A substack is a bare single column used as a limit; no edge padding.
            case SUBSTACK -> { edgeGap = 0.0; colGap = 0.0; }
            case MATRIX -> { edgeGap = 0.18 * em; colGap = 0.5 * em; }
            // Aligned-equation environments flush to the outer margin (no edge gap);
            // ALIGN's inter-column gaps are set per-boundary below, GATHER has a
            // single centred column so its (unused) colGap stays 0.
            case ALIGN, GATHER, MULTLINE -> { edgeGap = 0.0; colGap = 0.0; }
            default -> { edgeGap = 0.18 * em; colGap = 0.5 * em; }
        }
        double[] boundaryGap = new double[cols + 1];
        boundaryGap[0] = edgeGap;
        for (int i = 1; i < cols; i++) {
            // align alternates gaps: within an r/l equation pair (odd boundary) a
            // thin relation space; between successive equations (even boundary) a
            // wide \qquad. Every other environment uses a uniform inter-column gap.
            boundaryGap[i] = mx.kind() == MatrixKind.ALIGN
                ? (i % 2 == 1 ? ALIGN_RELATION_GAP : ALIGN_INTER_EQ_GAP) * em
                : colGap;
        }
        boundaryGap[cols] = edgeGap;

        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double penX = 0.0;

        // 4. Left delimiter (stretched to the grid height, centred on the axis).
        double leftH = 0.0;
        double leftD = 0.0;
        if (mx.leftDelim() != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, mx.leftDelim(), fullSpan, axis, scale, penX, glyphs);
            penX = hd[0];
            leftH = hd[1];
            leftD = hd[2];
        }

        // 5. Walk the columns, placing per-column x and drawing vertical rules in the
        // boundary gaps. Grid content spans [contentStartX, contentEndX].
        double contentStartX = penX;
        double[] colX = new double[cols];
        for (int col = 0; col < cols; col++) {
            addVerticalRules(rules, penX, boundaryGap[col], mx.columnRules().get(col),
                ruleThick, gridTopY, fullSpan);
            penX += boundaryGap[col];
            colX[col] = penX;
            penX += colWidth[col];
        }
        addVerticalRules(rules, penX, boundaryGap[cols], mx.columnRules().get(cols),
            ruleThick, gridTopY, fullSpan);
        penX += boundaryGap[cols];
        double contentEndX = penX;

        // 6. Right delimiter.
        double rightH = 0.0;
        double rightD = 0.0;
        if (mx.rightDelim() != MathNode.Fenced.NULL_DELIMITER) {
            double[] hd = placeDelimiter(font, mx.rightDelim(), fullSpan, axis, scale, penX, glyphs);
            penX = hd[0];
            rightH = hd[1];
            rightD = hd[2];
        }
        double totalWidth = penX;

        // 7. Stamp each cell at its column x and row baseline. Alignment is per-COLUMN for
        // every environment EXCEPT multline, which is per-ROW in its single column: the first
        // line flush-left, the last flush-right, the middle centred (a single-row multline is
        // treated as the first line ⇒ left).
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                Box b = cell[r][col];
                ColumnAlign align = mx.kind() == MatrixKind.MULTLINE
                    ? (r == 0 ? ColumnAlign.LEFT
                       : r == rows - 1 ? ColumnAlign.RIGHT
                       : ColumnAlign.CENTER)
                    : mx.columnAligns().get(col);
                double dx = colX[col] + alignOffset(align, colWidth[col], b.width());
                b.drawInto(glyphs, rules, dx, baseline[r]);
            }
        }

        // 8. Horizontal rules (\hline / \hdashline) at the inter-row gaps.
        double hlineW = contentEndX - contentStartX;
        for (int g = 0; g <= rows; g++) {
            RowRule rule = mx.rowRules().get(g);
            if (rule == RowRule.NONE) {
                continue;
            }
            double yCenter;
            if (g == 0) {
                yCenter = gridTopY;
            } else if (g == rows) {
                yCenter = gridTopY + fullSpan;
            } else {
                double above = baseline[g - 1] + rowDepth[g - 1];
                double below = baseline[g] - rowHeight[g];
                yCenter = (above + below) / 2.0;
            }
            addHorizontalRule(rules, contentStartX, yCenter - ruleThick / 2.0, hlineW,
                ruleThick, rule == RowRule.DASHED, em);
        }

        double height = Math.max(boxHeight, Math.max(leftH, rightH));
        double depth = Math.max(boxDepth, Math.max(leftD, rightD));
        return new Box(glyphs, rules, totalWidth, height, depth);
    }

    /** The x-offset of a cell of {@code cellWidth} within a column of {@code colWidth}. */
    private static double alignOffset(ColumnAlign align, double colWidth, double cellWidth) {
        return switch (align) {
            case LEFT -> 0.0;
            case CENTER -> (colWidth - cellWidth) / 2.0;
            case RIGHT -> colWidth - cellWidth;
        };
    }

    /**
     * Draws {@code count} vertical rules (an array {@code |} / {@code ||}) centred in
     * the boundary gap {@code [regionStart, regionStart+gap]}, each a full-grid-height
     * {@code <rect>}; a double rule separates the two bars by one rule thickness.
     */
    private static void addVerticalRules(List<Rule> rules, double regionStart, double gap,
                                         int count, double thick, double topY, double spanH) {
        if (count <= 0) {
            return;
        }
        double block = count * thick + (count - 1) * thick; // bars + inter-bar gaps
        double x0 = regionStart + (gap - block) / 2.0;
        for (int k = 0; k < count; k++) {
            rules.add(new Rule(x0 + k * 2.0 * thick, topY, thick, spanH));
        }
    }

    /**
     * Draws a horizontal rule spanning the grid width: a single {@code <rect>} for
     * {@code \hline}, or a run of short {@code <rect>} dashes for {@code \hdashline}
     * (both strictly in-alphabet).
     */
    private static void addHorizontalRule(List<Rule> rules, double x, double y, double width,
                                          double thick, boolean dashed, double em) {
        if (!dashed) {
            rules.add(new Rule(x, y, width, thick));
            return;
        }
        double dash = 0.25 * em;
        double gap = 0.18 * em;
        double cursor = x;
        double end = x + width;
        while (cursor < end) {
            double w = Math.min(dash, end - cursor);
            rules.add(new Rule(cursor, y, w, thick));
            cursor += dash + gap;
        }
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
            placeInto(out, x, inkTopY, PositionedGlyph.NO_RANK);
        }

        /// Depth-carrying overload: \left..\right fence delimiters pass their BODY's
        /// fence depth so the pair lights with the group it closes in the precedence
        /// cascade (Charles, 2026-07-14: the brackets bolding marks "this value is now
        /// complete"). Construction uses (radical surds, matrix brackets, \big sizers)
        /// keep NO_RANK via the 3-arg form — they delimit typography, not precedence.
        void placeInto(List<PositionedGlyph> out, double x, double inkTopY, int fenceDepth) {
            for (StretchyPiece p : pieces) {
                // inkTop = baselineY - scale*yMax  ⇒  baselineY = inkTop + scale*yMax.
                double baselineY = inkTopY + p.topOffset() + p.scale() * p.yMax();
                out.add(new PositionedGlyph(p.gid(), x, baselineY, p.scale(), fenceDepth));
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
