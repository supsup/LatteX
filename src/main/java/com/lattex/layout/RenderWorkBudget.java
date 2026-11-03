package com.lattex.layout;

import com.lattex.parse.MathNode;
import com.lattex.parse.MathSyntaxException;
import java.util.function.Supplier;

/**
 * Internal render preflight shared by SVG layout and MathML serialization. Public only
 * so the API implementation can call into this unexported package; no mutable state
 * survives a call. Layout additionally binds a cumulative allocation allowance for
 * every primitive producer and copy, including geometry without text payload.
 *
 * <p>Tokens and boxes do not measure text: one TextRun token can request thousands of
 * glyphs, and a shared array separator can be stamped on every row. Charge UTF-16
 * units PER OCCURRENCE, including spaces and supplementary-character surrogate pairs
 * (deliberately conservative), without expanding text or allocating output. The
 * 100k text allowance is independent of the unchanged parser/token/box limits.
 */
public final class RenderWorkBudget {
    private static final long MAX_TEXT_UNITS = 100_000;
    private static final long MAX_VISITS = 100_000;
    private static final int MAX_DEPTH = 512;
    private static final long MAX_LAYOUT_COPY_UNITS = 2_000_000;
    private static final long MAX_MATHML_UNITS = 2_000_000;
    private static final long MAX_MATHML_COPY_UNITS = 16_000_000;

    // Cumulative construction events, not the final retained primitive count. A flat
    // 96k-atom layout creates 288k glyph placements (producer, row, final flattening);
    // even one color-copy layer fits under 400k. Rules have their own 100k allowance
    // because a tiny AST can generate or repeatedly stamp very large rule lists.
    // These bound work and object counts, not bytes or a promise about arbitrary heaps.
    static final long MAX_GLYPH_ALLOCATIONS = 400_000;
    static final long MAX_RULE_ALLOCATIONS = 100_000;
    private static final ScopedValue<Allocations> ALLOCATIONS = ScopedValue.newInstance();

    private static final class Allocations {
        long glyphs;
        long rules;
    }

    /** Synchronous render-local binding; nested calls and failures restore the caller. */
    static <T> T withLayoutBudget(Supplier<T> action) {
        return ScopedValue.where(ALLOCATIONS, new Allocations()).call(action::get);
    }

    static void chargeGlyph() {
        if (ALLOCATIONS.isBound()) {
            Allocations current = ALLOCATIONS.get();
            current.glyphs = charge(current.glyphs, 1, MAX_GLYPH_ALLOCATIONS,
                "glyph allocations");
        }
    }

    static void chargeRule() {
        if (ALLOCATIONS.isBound()) {
            Allocations current = ALLOCATIONS.get();
            current.rules = charge(current.rules, 1, MAX_RULE_ALLOCATIONS,
                "rule allocations");
        }
    }

    // Capacity prechecks reserve nothing: canonical constructors charge each event
    // exactly once. Used before producer loops and polygon clone/translation work.
    static void requireGlyphs(long count) {
        if (ALLOCATIONS.isBound()) {
            charge(ALLOCATIONS.get().glyphs, count, MAX_GLYPH_ALLOCATIONS,
                "glyph allocations");
        }
    }

    static void requireRules(long count) {
        if (ALLOCATIONS.isBound()) {
            charge(ALLOCATIONS.get().rules, count, MAX_RULE_ALLOCATIONS,
                "rule allocations");
        }
    }

    private final boolean mathml;
    private long textUnits;
    private long visits;
    private long outputUnits;
    private long copyUnits;

    private RenderWorkBudget(boolean mathml) {
        this.mathml = mathml;
    }

    /** Refuses excessive materialized text/ancestor copying before any layout boxes. */
    public static void checkLayout(MathNode root) {
        new RenderWorkBudget(false).visit(root, 1);
    }

    /** Refuses excessive materialized markup/ancestor copying before serialization. */
    public static void checkMathML(MathNode root) {
        RenderWorkBudget budget = new RenderWorkBudget(true);
        budget.units(0, 0, 64); // document <math xmlns=...> wrapper
        budget.visit(root, 1);
    }

    private static long charge(long used, long amount, long limit, String kind) {
        // Subtraction avoids overflow even for a future caller with a much larger bound.
        if (amount < 0 || amount > limit - used) {
            throw MathSyntaxException.capExceeded("output work budget exceeded: " + kind);
        }
        return used + amount;
    }

    private void units(long payload, int depth, long markup) {
        textUnits = charge(textUnits, payload, MAX_TEXT_UNITS, "materialized text");
        if (mathml) {
            outputUnits = charge(outputUnits, markup, MAX_MATHML_UNITS, "MathML markup");
            // One logical descendant copy per ancestor, plus the document wrapper.
            // This is a work policy, not an allocation-byte prediction. Inflating
            // every ordinary atom to 256 chars and four copies refused a flat 5208
            // atoms despite its ~52k actual output. Use the leaf's actual markup and
            // separately account table wrappers instead of hiding them in each leaf.
            copyUnits = charge(copyUnits, markup * (depth + 1),
                MAX_MATHML_COPY_UNITS, "MathML copying");
        } else {
            // Box composition copies glyph placements through ancestors. Include one
            // unit for each non-text node and four placements/copies per level; text
            // counts even in phantoms, whose boxes are allocated before ink is hidden.
            copyUnits = charge(copyUnits, (payload + 1) * 4 * (depth + 1),
                MAX_LAYOUT_COPY_UNITS, "layout copying");
        }
    }

    private static long mathmlMarkup(MathNode node, long payload) {
        return switch (node) {
            case MathNode.Atom atom -> {
                int cp = atom.codePoint() == '-' ? 0x2212 : atom.codePoint();
                int escaped = switch (cp) {
                    case '&' -> 5;
                    case '<', '>' -> 4;
                    case '"' -> 6;
                    default -> Character.charCount(cp);
                };
                int tags = switch (atom.mathClass()) {
                    case ORD -> Character.isLetter(cp)
                        && ((cp >= 0x0391 && cp <= 0x03A9) || (atom.upright() && cp < 0x80))
                        ? 30 : 9;
                    case OPEN, CLOSE -> 22;
                    default -> 9;
                };
                yield tags + escaped;
            }
            case MathNode.MathList _ -> 13; // <mrow></mrow>
            case MathNode.TextRun _ -> 15 + 6 * payload; // escaped <mtext>
            case MathNode.OperatorName _ -> 9 + 6 * payload; // escaped <mo>
            case MathNode.Spacing _, MathNode.MiddleDelim _, MathNode.SizedDelim _ -> 96;
            // Fixed tags/attributes/helper wrappers for compound nodes; descendants
            // contribute separately. Text-bearing leaves never take this allowance.
            default -> 256;
        };
    }

    private void visit(MathNode node, int depth) {
        if (node == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw MathSyntaxException.capExceeded("output work budget exceeded: nesting too deep");
        }
        if (++visits > MAX_VISITS) {
            // Retain the existing direct-layout box-cap diagnostic for wide trees.
            throw MathSyntaxException.capExceeded("layout box budget exceeded during output preflight");
        }
        long payload = switch (node) {
            case MathNode.TextRun t -> t.text().length();
            case MathNode.OperatorName o -> o.name().length();
            default -> 0;
        };
        units(payload, depth, mathml ? mathmlMarkup(node, payload) : 0);
        int child = depth + 1;
        // Exhaustiveness is intentional: a new text-bearing node must choose its
        // materialized traversal here, rather than silently bypassing the budget.
        switch (node) {
            case MathNode.Atom _, MathNode.Spacing _, MathNode.MiddleDelim _,
                 MathNode.SizedDelim _, MathNode.TextRun _, MathNode.OperatorName _ -> { }
            case MathNode.MathList n -> n.items().forEach(item -> visit(item, child));
            case MathNode.SupSub n -> {
                visit(n.base(), child); visit(n.sup(), child); visit(n.sub(), child);
            }
            case MathNode.Fraction n -> {
                visit(n.numerator(), child); visit(n.denominator(), child);
            }
            case MathNode.Radical n -> {
                visit(n.radicand(), child); visit(n.index(), child);
            }
            case MathNode.BigOperator n -> {
                visit(n.op(), child); visit(n.lower(), child); visit(n.upper(), child);
            }
            case MathNode.Fenced n -> visit(n.body(), child);
            case MathNode.Colored n -> visit(n.body(), child);
            case MathNode.ClassOverride n -> visit(n.body(), child);
            case MathNode.Boxed n -> visit(n.body(), child);
            case MathNode.Negated n -> visit(n.body(), child);
            case MathNode.Cancel n -> { visit(n.body(), child); visit(n.to(), child); }
            case MathNode.Tagged n -> { visit(n.body(), child); visit(n.label(), child); }
            case MathNode.Accent n -> visit(n.base(), child);
            case MathNode.Phantom n -> visit(n.content(), child);
            case MathNode.Lap n -> visit(n.body(), child);
            case MathNode.StyledMath n -> visit(n.body(), child);
            case MathNode.StyleSwitch n -> visit(n.body(), child);
            case MathNode.Stack n -> {
                visit(n.base(), child); visit(n.above(), child); visit(n.below(), child);
            }
            case MathNode.XArrow n -> { visit(n.above(), child); visit(n.below(), child); }
            case MathNode.CdArrow n -> { visit(n.labelA(), child); visit(n.labelB(), child); }
            case MathNode.Matrix n -> matrix(n, child);
            case MathNode.BorderMatrix n -> {
                if (mathml) {
                    units(0, child, 96); // header row/corner cell
                }
                visit(n.corner(), child);
                for (MathNode label : n.columnLabels()) {
                    if (mathml) {
                        units(0, child, 16); // header <mtd>
                    }
                    visit(label, child);
                }
                for (int r = 0; r < n.rowCount(); r++) {
                    units(0, child, 96); // row and row-label cell
                    visit(n.rowLabels().get(r), child);
                    for (MathNode cell : n.body().get(r)) {
                        units(0, child, 128); // cell and up to two stretchy fences
                        visit(cell, child);
                    }
                }
            }
        }
    }

    private void matrix(MathNode.Matrix matrix, int depth) {
        for (int c = 0; c < matrix.columnCount(); c++) {
            units(0, depth, 16); // columnalign attribute, including empty tables
        }
        for (int r = 0; r < matrix.rows().size(); r++) {
            units(0, depth, 96); // row and optional label-cell markup
            visit(matrix.rowTags().get(r), depth);
            for (MathNode cell : matrix.rows().get(r)) {
                units(0, depth, 96); // cell/span/alignment/separator wrapper
                visit(cell, depth);
            }
            // Do not deduplicate shared separator ASTs. Layout creates the boundary
            // box even when a span hides it; MathML emits visible material per row.
            // Counting every boundary per row safely covers both, conservatively
            // charging hidden material too, without invoking spanAt's linear scan.
            for (MathNode.ColumnSeparator separator : matrix.columnSeparators()) {
                if (separator != null) {
                    visit(separator.material(), depth);
                }
            }
        }
    }
}
