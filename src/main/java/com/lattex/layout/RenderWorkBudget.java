package com.lattex.layout;

import com.lattex.parse.MathNode;
import com.lattex.parse.MathSyntaxException;

/**
 * Internal render preflight shared by SVG layout and MathML serialization. Public only
 * so the API implementation can call into this unexported package; no mutable state
 * survives a call.
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
        new RenderWorkBudget(true).visit(root, 1);
    }

    private static long charge(long used, long amount, long limit, String kind) {
        // Subtraction avoids overflow even for a future caller with a much larger bound.
        if (amount < 0 || amount > limit - used) {
            throw MathSyntaxException.capExceeded("output work budget exceeded: " + kind);
        }
        return used + amount;
    }

    private void units(long payload, int depth) {
        textUnits = charge(textUnits, payload, MAX_TEXT_UNITS, "materialized text");
        if (mathml) {
            // xmlEscape expands a UTF-16 unit by at most 6 (&quot;). 256 units per
            // node/cell/row cover current fixed tags, attributes, numeric widths and
            // fences, plus the document root. Table columnalign is charged separately.
            long markup = 256 + 6 * payload; // payload <= String.length(): no overflow
            outputUnits = charge(outputUnits, markup, MAX_MATHML_UNITS, "MathML markup");
            // Each ancestor copies its descendants into a concatenation or builder.
            // Four copies per level also allow builder growth/toString and temporary
            // helper wrappers (stack braces, negations, table separators). This is a
            // conservative work policy, not an exact byte-size prediction.
            copyUnits = charge(copyUnits, markup * 4 * (depth + 1),
                MAX_MATHML_COPY_UNITS, "MathML copying");
        } else {
            // Box composition copies glyph placements through ancestors. Include one
            // unit for each non-text node and four placements/copies per level; text
            // counts even in phantoms, whose boxes are allocated before ink is hidden.
            copyUnits = charge(copyUnits, (payload + 1) * 4 * (depth + 1),
                MAX_LAYOUT_COPY_UNITS, "layout copying");
        }
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
        units(payload, depth);
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
                visit(n.corner(), child);
                n.columnLabels().forEach(label -> visit(label, child));
                for (int r = 0; r < n.rowCount(); r++) {
                    units(0, child); // row markup
                    visit(n.rowLabels().get(r), child);
                    for (MathNode cell : n.body().get(r)) {
                        units(0, child); // cell markup/fences
                        visit(cell, child);
                    }
                }
            }
        }
    }

    private void matrix(MathNode.Matrix matrix, int depth) {
        for (int c = 0; c < matrix.columnCount(); c++) {
            units(0, depth); // columnalign attribute, including empty tables
        }
        for (int r = 0; r < matrix.rows().size(); r++) {
            units(0, depth); // row and optional label-cell markup
            visit(matrix.rowTags().get(r), depth);
            for (MathNode cell : matrix.rows().get(r)) {
                units(0, depth); // cell/span/alignment attributes
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
