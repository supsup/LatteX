package com.lattex.parse;

import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.StyledMath;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathNode.TextRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The font-variant alphabets ({@code \mathbb \mathcal \mathfrak \mathbf \mathsf
 * \mathit \mathtt \mathscr \boldsymbol \bm}). Each maps a base ASCII letter/digit
 * (and, for the bold/italic styles, a Greek letter) to its counterpart in the
 * Unicode <em>Mathematical Alphanumeric Symbols</em> block (U+1D400–U+1D7FF),
 * plus the reserved-slot characters that live in the <em>Letterlike Symbols</em>
 * block (e.g. ℝ, ℒ, ℭ).
 *
 * <p><strong>Clean-room.</strong> The char&rarr;math-alphanumeric-code-point
 * mapping is defined <em>entirely</em> by the Unicode Standard — the contiguous
 * block layout (computed by offset) and the explicit Letterlike-Symbols
 * exception table for the reserved slots. Glyphs are supplied by bundled STIX
 * Two Math. Nothing here is derived from any renderer's source.
 *
 * <p><strong>Atom rewrite, no new node kind.</strong> {@code \mathX{content}}
 * does not introduce a MathNode kind: {@link #apply(Style, MathNode)} walks the
 * parsed sub-tree and rewrites each {@link Atom}'s code point to the variant
 * form, producing ordinary atoms the existing layout/emit already handle. Nodes
 * without a letter (operators, delimiters, spacing) pass through unchanged.
 *
 * <p><strong>Sensible fallback.</strong> A code point the style does not define
 * — a digit in a style with no math digits ({@code \mathcal5}), Greek in a style
 * with no math Greek ({@code \mathbb\alpha}), or any symbol — is returned
 * unchanged, so the ordinary glyph renders. This matches LaTeX (e.g.
 * {@code \mathcal{5}} is an ordinary 5) and guarantees we never reach for a glyph
 * that does not exist (which the coverage test also proves for the letters we
 * <em>do</em> map). No {@code <text>} fallback is ever produced — the output
 * stays within the minimal SVG alphabet by construction.
 */
public final class MathVariant {

    private MathVariant() {
    }

    /**
     * A math font-variant style. Each carries the start code points of its runs
     * in the Mathematical Alphanumeric Symbols block:
     *
     * <ul>
     *   <li>{@code letterStart} — the code point of variant {@code A}; variant
     *       {@code a} is {@code letterStart + 26}.</li>
     *   <li>{@code digitStart} — the code point of variant {@code 0}, or
     *       {@link #NONE} if the style has no math digits (they stay ASCII).</li>
     *   <li>{@code greekUpperStart} — the code point of the style's Greek run
     *       (variant {@code Α}), or {@link #NONE} if the style has no math Greek
     *       (Greek stays as its base code point).</li>
     * </ul>
     */
    public enum Style {
        // (-1 == NONE below; enum-constant initializers may not forward-reference
        // a static field, so the literal is used here.)
        /** {@code \mathbb} — double-struck (blackboard bold) Latin + double-struck digits. */
        BLACKBOARD(0x1D538, 0x1D7D8, -1),
        /** {@code \mathcal} / {@code \mathscr} — script (single STIX script alphabet). */
        SCRIPT(0x1D49C, -1, -1),
        /** {@code \mathfrak} — fraktur. */
        FRAKTUR(0x1D504, -1, -1),
        /** {@code \mathbf} — upright bold Latin + bold digits (Greek unchanged, per LaTeX). */
        BOLD(0x1D400, 0x1D7CE, -1),
        /** {@code \mathsf} — sans-serif Latin + sans digits. */
        SANS(0x1D5A0, 0x1D7E2, -1),
        /** {@code \mathit} — math italic Latin + italic Greek (no math digits). */
        ITALIC(0x1D434, -1, 0x1D6E2),
        /** {@code \mathtt} — monospace Latin + monospace digits. */
        MONO(0x1D670, 0x1D7F6, -1),
        /** {@code \boldsymbol} / {@code \bm} — bold Latin + bold digits + bold Greek. */
        BOLDSYMBOL(0x1D400, 0x1D7CE, 0x1D6A8);

        /** Sentinel: this style defines no run for that character family. */
        static final int NONE = -1;

        private final int letterStart;
        private final int digitStart;
        private final int greekUpperStart;

        Style(int letterStart, int digitStart, int greekUpperStart) {
            this.letterStart = letterStart;
            this.digitStart = digitStart;
            this.greekUpperStart = greekUpperStart;
        }
    }

    // ------------------------------------------------------------------
    // Letterlike-Symbols exceptions: reserved slots in the contiguous block that
    // Unicode places in the Letterlike Symbols block instead. Keyed by base char.
    // (Unicode Standard, "Mathematical Alphanumeric Symbols" block description.)
    // ------------------------------------------------------------------

    private static final Map<Integer, Integer> BB_EXCEPTIONS = Map.ofEntries(
        Map.entry((int) 'C', 0x2102), // ℂ
        Map.entry((int) 'H', 0x210D), // ℍ
        Map.entry((int) 'N', 0x2115), // ℕ
        Map.entry((int) 'P', 0x2119), // ℙ
        Map.entry((int) 'Q', 0x211A), // ℚ
        Map.entry((int) 'R', 0x211D), // ℝ
        Map.entry((int) 'Z', 0x2124)); // ℤ

    private static final Map<Integer, Integer> SCRIPT_EXCEPTIONS = Map.ofEntries(
        Map.entry((int) 'B', 0x212C), // ℬ
        Map.entry((int) 'E', 0x2130), // ℰ
        Map.entry((int) 'F', 0x2131), // ℱ
        Map.entry((int) 'H', 0x210B), // ℋ
        Map.entry((int) 'I', 0x2110), // ℐ
        Map.entry((int) 'L', 0x2112), // ℒ
        Map.entry((int) 'M', 0x2133), // ℳ
        Map.entry((int) 'R', 0x211B), // ℛ
        Map.entry((int) 'e', 0x212F), // ℯ
        Map.entry((int) 'g', 0x210A), // ℊ
        Map.entry((int) 'o', 0x2134)); // ℴ

    private static final Map<Integer, Integer> FRAKTUR_EXCEPTIONS = Map.ofEntries(
        Map.entry((int) 'C', 0x212D), // ℭ
        Map.entry((int) 'H', 0x210C), // ℌ
        Map.entry((int) 'I', 0x2111), // ℑ
        Map.entry((int) 'R', 0x211C), // ℜ
        Map.entry((int) 'Z', 0x2128)); // ℨ

    private static final Map<Integer, Integer> ITALIC_EXCEPTIONS = Map.ofEntries(
        Map.entry((int) 'h', 0x210E)); // ℎ (Planck constant — italic h reserved slot)

    private static final Map<Style, Map<Integer, Integer>> EXCEPTIONS = Map.of(
        Style.BLACKBOARD, BB_EXCEPTIONS,
        Style.SCRIPT, SCRIPT_EXCEPTIONS,
        Style.FRAKTUR, FRAKTUR_EXCEPTIONS,
        Style.ITALIC, ITALIC_EXCEPTIONS);

    // Greek "variant symbol" slots + nabla/partial, as offsets from a style's
    // greekUpperStart within the 58-glyph Greek run (Unicode block layout).
    private static final Map<Integer, Integer> GREEK_VARIANT_OFFSET = Map.ofEntries(
        Map.entry(0x2207, 25), // ∇ nabla
        Map.entry(0x2202, 51), // ∂ partial
        Map.entry(0x03F5, 52), // ϵ epsilon symbol (\epsilon)
        Map.entry(0x03D1, 53), // ϑ theta symbol   (\vartheta)
        Map.entry(0x03F0, 54), // ϰ kappa symbol   (\varkappa)
        Map.entry(0x03D5, 55), // ϕ phi symbol     (\phi)
        Map.entry(0x03F1, 56), // ϱ rho symbol     (\varrho)
        Map.entry(0x03D6, 57)); // ϖ pi symbol     (\varpi)

    // ------------------------------------------------------------------
    // The map: base code point -> variant code point (or the base, unchanged).
    // ------------------------------------------------------------------

    /**
     * Maps a single base code point to its {@code style} form, or returns it
     * unchanged if the style defines no variant for that character.
     */
    public static int map(Style style, int cp) {
        Map<Integer, Integer> ex = EXCEPTIONS.get(style);
        if (ex != null) {
            Integer r = ex.get(cp);
            if (r != null) {
                return r;
            }
        }
        if (cp >= 'A' && cp <= 'Z') {
            return style.letterStart + (cp - 'A');
        }
        if (cp >= 'a' && cp <= 'z') {
            return style.letterStart + 26 + (cp - 'a');
        }
        if (cp >= '0' && cp <= '9') {
            return style.digitStart == Style.NONE ? cp : style.digitStart + (cp - '0');
        }
        if (style.greekUpperStart != Style.NONE) {
            int g = mapGreek(style.greekUpperStart, cp);
            if (g != cp) {
                return g;
            }
        }
        return cp; // no variant form for this character — leave the base glyph
    }

    /** Maps a Greek code point within a style's 58-glyph Greek run, else returns cp. */
    private static int mapGreek(int greekUpperStart, int cp) {
        // Uppercase Α..Ω (0x391..0x3A9); 0x3A2 is the reserved final-sigma slot
        // (theta-symbol in the math run) and never appears as a source letter.
        if (cp >= 0x391 && cp <= 0x3A9 && cp != 0x3A2) {
            return greekUpperStart + (cp - 0x391);
        }
        // Lowercase α..ω (0x3B1..0x3C9), including ς — the lowercase run starts at
        // block index 26 (after the 25 uppercase slots + the nabla slot).
        if (cp >= 0x3B1 && cp <= 0x3C9) {
            return greekUpperStart + 26 + (cp - 0x3B1);
        }
        Integer off = GREEK_VARIANT_OFFSET.get(cp);
        if (off != null) {
            return greekUpperStart + off;
        }
        return cp;
    }

    // ------------------------------------------------------------------
    // Atom-rewrite: apply a style to a whole parsed sub-tree.
    // ------------------------------------------------------------------

    /**
     * Returns {@code node} with every {@link Atom}'s code point remapped to its
     * {@code style} form, recursing through composite nodes. No new node kind is
     * introduced — the result is the same ADT the layout/emit stages already
     * consume.
     */
    public static MathNode apply(Style style, MathNode node) {
        return switch (node) {
            case Atom a -> new Atom(map(style, a.codePoint()), a.mathClass());
            // A delimiter is never letter/digit-mapped by a style variant.
            case MathNode.MiddleDelim md -> md;
            case MathList list -> {
                List<MathNode> items = new ArrayList<>(list.items().size());
                for (MathNode child : list.items()) {
                    items.add(apply(style, child));
                }
                yield new MathList(items);
            }
            case SupSub s -> new SupSub(
                apply(style, s.base()),
                s.sup() == null ? null : apply(style, s.sup()),
                s.sub() == null ? null : apply(style, s.sub()));
            case Fraction f -> new Fraction(
                apply(style, f.numerator()), apply(style, f.denominator()),
                f.hasRule(), f.fractionStyle());
            case Radical r -> new Radical(
                apply(style, r.radicand()),
                r.index() == null ? null : apply(style, r.index()));
            case BigOperator b -> new BigOperator(
                (Atom) apply(style, b.op()),
                b.lower() == null ? null : apply(style, b.lower()),
                b.upper() == null ? null : apply(style, b.upper()),
                b.limitsMode());
            case Fenced f -> new Fenced(
                f.leftDelim(), apply(style, f.body()), f.rightDelim());
            case MathNode.SizedDelim sd -> sd; // a delimiter glyph is not font-variant styled
            case Accent a -> new Accent(
                a.command(), apply(style, a.base()),
                a.accentCodePoint(), a.stretchy(), a.under());
            case Phantom p -> new Phantom(
                apply(style, p.content()), p.keepWidth(), p.keepVertical());
            case MathNode.Colored c -> new MathNode.Colored(apply(style, c.body()), c.color());
            case MathNode.ClassOverride co ->
                new MathNode.ClassOverride(apply(style, co.body()), co.forcedClass());
            case MathNode.Boxed bx -> new MathNode.Boxed(apply(style, bx.body()));
            case MathNode.Cancel c -> new MathNode.Cancel(c.kind(), apply(style, c.body()),
                c.to() == null ? null : apply(style, c.to()));
            case MathNode.Tagged t ->
                new MathNode.Tagged(apply(style, t.body()), apply(style, t.label()));
            // Glue, roman operator words, and text-mode runs carry no math letter to restyle.
            case Spacing s -> s;
            case OperatorName o -> o;
            case TextRun t -> t;
            // A grid: restyle every cell, preserving the grid's shape/delimiters/rules.
            case MathNode.Matrix mx -> {
                List<List<MathNode>> restyled = new ArrayList<>(mx.rows().size());
                for (List<MathNode> gridRow : mx.rows()) {
                    List<MathNode> cells = new ArrayList<>(gridRow.size());
                    for (MathNode c : gridRow) {
                        cells.add(apply(style, c));
                    }
                    restyled.add(cells);
                }
                yield new MathNode.Matrix(restyled, mx.columnAligns(), mx.columnRules(),
                    mx.rowRules(), mx.leftDelim(), mx.rightDelim(), mx.kind());
            }
            // A bordered matrix: restyle the corner, the labels, and every body cell.
            case MathNode.BorderMatrix bm -> {
                List<List<MathNode>> body = new ArrayList<>(bm.body().size());
                for (List<MathNode> gridRow : bm.body()) {
                    List<MathNode> cells = new ArrayList<>(gridRow.size());
                    for (MathNode c : gridRow) {
                        cells.add(apply(style, c));
                    }
                    body.add(cells);
                }
                List<MathNode> colLabels = new ArrayList<>(bm.columnLabels().size());
                for (MathNode c : bm.columnLabels()) {
                    colLabels.add(apply(style, c));
                }
                List<MathNode> rowLabels = new ArrayList<>(bm.rowLabels().size());
                for (MathNode c : bm.rowLabels()) {
                    rowLabels.add(apply(style, c));
                }
                yield new MathNode.BorderMatrix(body, colLabels, rowLabels, apply(style, bm.corner()));
            }
            // A stack: restyle the base and any above/below marks, preserving the kind.
            case MathNode.Stack st -> new MathNode.Stack(
                apply(style, st.base()),
                st.above() == null ? null : apply(style, st.above()),
                st.below() == null ? null : apply(style, st.below()),
                st.kind());
            // An extensible arrow: restyle its labels (the arrow itself is a glyph
            // synthesized in layout, not a restylable atom).
            case MathNode.XArrow xa -> new MathNode.XArrow(
                apply(style, xa.above()),
                xa.below() == null ? null : apply(style, xa.below()),
                xa.kind());
            // A CD connector: restyle its side labels; the shaft is a synthesized glyph.
            case MathNode.CdArrow cd -> new MathNode.CdArrow(
                cd.kind(),
                cd.labelA() == null ? null : apply(style, cd.labelA()),
                cd.labelB() == null ? null : apply(style, cd.labelB()));
            // A \lx wrapper is top-level-only (nested \lx is rejected by the parser),
            // so this arm is unreachable in practice; restyle the body for totality.
            case StyledMath sm -> new StyledMath(
                apply(style, sm.body()), sm.style(), sm.fx(), sm.sem());
            case MathNode.StyleSwitch sw -> new MathNode.StyleSwitch(sw.level(), apply(style, sw.body()));
        };
    }
}
