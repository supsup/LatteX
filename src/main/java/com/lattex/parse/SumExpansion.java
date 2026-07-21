package com.lattex.parse;

import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Boxed;
import com.lattex.parse.MathNode.Colored;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.SupSub;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The render-time <strong>numeric-expansion</strong> pass — LatteX's first (and
 * deliberately tiny, bounded) semantic evaluation of the source, powering the
 * {@code fx.*=unfold} effect. It takes a parsed {@code \sum} with LITERAL-INTEGER
 * bounds and a single index variable, and rewrites it into its explicit terms as a
 * new {@link MathNode} tree — {@code \sum_{i=1}^{4} f(i)} becomes the tree for
 * {@code f(1)+f(2)+f(3)+f(4)}.
 *
 * <p><strong>This pass is opt-in and flag-gated.</strong> It NEVER runs on the
 * default render path — {@code renderStyledHtml} only calls it when the host has
 * enabled {@link com.lattex.api.RenderOptions#interactiveExpansion()} AND the
 * equation carries an {@code fx.*=unfold} directive. LatteX stays a pure typesetter
 * by default; this is the sole place it computes anything from the math.
 *
 * <p><strong>Why a TREE transform, not string substitution.</strong> Substitution
 * matches the index by atom code point on the parsed tree, so the {@code i} inside
 * {@code \sin}/{@code \lim} can never be touched — those are {@link MathNode.OperatorName}
 * nodes whose letters are not {@link Atom}s. A naive regex over the raw LaTeX would
 * corrupt {@code \sin}; this cannot.
 *
 * <p><strong>Fail-INERT everywhere unsupported</strong> ({@link Optional#empty()}):
 * a non-{@code \sum} operator; non-literal / symbolic / {@code \infty} bounds; a
 * multi-atom or zero-atom index; {@code end < start}; a term count over
 * {@link #MAX_TERMS}; an empty summand; a top-level trailing binary/relation operator
 * in the summand (so {@code \sum f(i) + C} degrades inert rather than folding
 * {@code +C} into every term); or a summand containing a node kind this pass does not
 * traverse (rather than silently mis-rendering it). Inert means the {@code \sum}
 * typesets exactly as today and the unfold interaction simply never arms.
 */
public final class SumExpansion {

    /** The {@code \sum} operator code point (U+2211, {@code ∑}). */
    private static final int SUM_CODEPOINT = 0x2211;

    /**
     * The maximum number of terms an expansion may produce. A resource guard (the
     * {@code lattex-security-hardening-resource-guard} ethos): a hostile
     * {@code \sum_{i=1}^{99999}} can never blow up layout — it degrades inert.
     */
    public static final int MAX_TERMS = 12;

    private SumExpansion() { }

    /**
     * A successful expansion: the rewritten {@link MathNode} (ready to lay out and
     * emit through the existing pipeline) plus the number of terms it holds (the
     * value stamped as the {@code data-lx-fx-expand} marker).
     */
    public record Result(MathNode expanded, int termCount) { }

    /** Signals that a summand subtree holds a node kind the pass does not traverse. */
    private static final class UnsupportedSummand extends RuntimeException {
        UnsupportedSummand() {
            super(null, null, false, false);
        }
    }

    /**
     * Expand a parsed expression into its explicit terms, or {@link Optional#empty()}
     * if any guard rail rejects it (see the class doc for the full inert list).
     *
     * @param node the parsed body (typically a {@link MathList} led by a {@code \sum})
     * @return the expansion, or empty (INERT) when unsupported
     */
    public static Optional<Result> expand(MathNode node) {
        if (!(node instanceof MathList ml)) {
            return Optional.empty();
        }
        List<MathNode> items = ml.items();
        if (items.isEmpty() || !(items.get(0) instanceof BigOperator op)) {
            return Optional.empty();
        }
        if (op.op().codePoint() != SUM_CODEPOINT) {
            return Optional.empty();   // \sum only in slice 1
        }

        // lower must be exactly [Atom(v), Atom('='), <integer>]; upper an integer.
        Optional<IndexBound> lower = parseLower(op.lower());
        Optional<Integer> upper = parseInteger(op.upper());
        if (lower.isEmpty() || upper.isEmpty()) {
            return Optional.empty();
        }
        int indexVar = lower.get().indexVar();
        int start = lower.get().start();
        int end = upper.get();
        if (end < start) {
            return Optional.empty();
        }
        int termCount = end - start + 1;
        if (termCount > MAX_TERMS) {
            return Optional.empty();
        }

        // The summand is everything after the operator in the enclosing list.
        List<MathNode> summand = items.subList(1, items.size());
        if (summand.isEmpty()) {
            return Optional.empty();
        }
        // Q5: reject a top-level trailing binary/relation operator (+ - =) so
        // `\sum f(i) + C` degrades inert instead of folding +C into every term.
        for (MathNode s : summand) {
            if (s instanceof Atom a
                    && (a.mathClass() == MathClass.BIN || a.mathClass() == MathClass.REL)) {
                return Optional.empty();
            }
        }

        List<MathNode> out = new ArrayList<>();
        try {
            for (int k = start; k <= end; k++) {
                if (k > start) {
                    out.add(new Atom('+', MathClass.BIN));
                }
                for (MathNode item : summand) {
                    substituteInto(out, item, indexVar, k);
                }
            }
        } catch (UnsupportedSummand unsupported) {
            return Optional.empty();
        }
        return Optional.of(new Result(new MathList(out), termCount));
    }

    /** The parsed lower bound: the index variable code point and the start integer. */
    private record IndexBound(int indexVar, int start) { }

    /** Parse {@code {i=1}} → (index code point, start). Empty on any other shape. */
    private static Optional<IndexBound> parseLower(MathNode lower) {
        if (!(lower instanceof MathList ml)) {
            return Optional.empty();
        }
        List<MathNode> items = ml.items();
        // [Atom(v), Atom('='), <one-or-more digit atoms>]
        if (items.size() < 3
                || !(items.get(0) instanceof Atom var)
                || !isSingleLetter(var)
                || !(items.get(1) instanceof Atom eq) || eq.codePoint() != '=') {
            return Optional.empty();
        }
        Optional<Integer> start = digitsToInt(items.subList(2, items.size()));
        return start.map(s -> new IndexBound(var.codePoint(), s));
    }

    /** Parse an upper bound that is a single digit Atom or a MathList of digit atoms. */
    private static Optional<Integer> parseInteger(MathNode upper) {
        if (upper instanceof Atom a) {
            return digitsToInt(List.of(a));
        }
        if (upper instanceof MathList ml) {
            return digitsToInt(ml.items());
        }
        return Optional.empty();
    }

    /** Every node must be a decimal digit Atom; returns the parsed non-negative int. */
    private static Optional<Integer> digitsToInt(List<MathNode> nodes) {
        if (nodes.isEmpty()) {
            return Optional.empty();
        }
        long value = 0;
        for (MathNode n : nodes) {
            if (!(n instanceof Atom a) || a.codePoint() < '0' || a.codePoint() > '9') {
                return Optional.empty();
            }
            value = value * 10 + (a.codePoint() - '0');
            if (value > 100000) {           // far past MAX_TERMS; stop before overflow
                return Optional.empty();
            }
        }
        return Optional.of((int) value);
    }

    private static boolean isSingleLetter(Atom a) {
        return Character.isLetter(a.codePoint());
    }

    /**
     * Deep-copy {@code node} into {@code out}, substituting every {@link Atom} whose
     * code point equals the index with the digit atom(s) of {@code k}. Throws
     * {@link UnsupportedSummand} on any node kind not traversed (→ inert expansion).
     */
    private static void substituteInto(List<MathNode> out, MathNode node, int indexVar, int k) {
        if (node instanceof Atom a) {
            if (a.codePoint() == indexVar) {
                out.addAll(digitAtoms(k));      // splice: 10 → [Atom(1), Atom(0)]
            } else {
                out.add(a);                     // records are immutable — share
            }
            return;
        }
        out.add(substitute(node, indexVar, k));
    }

    /** Deep-copy a single (non-spliced) node with the index substituted. */
    private static MathNode substitute(MathNode node, int indexVar, int k) {
        return switch (node) {
            case Atom a -> a.codePoint() == indexVar ? asNode(digitAtoms(k)) : a;
            case MathList ml -> {
                List<MathNode> items = new ArrayList<>();
                for (MathNode item : ml.items()) {
                    substituteInto(items, item, indexVar, k);
                }
                yield new MathList(items);
            }
            case SupSub s -> new SupSub(
                substitute(s.base(), indexVar, k),
                s.sup() == null ? null : substitute(s.sup(), indexVar, k),
                s.sub() == null ? null : substitute(s.sub(), indexVar, k));
            case Fraction f -> new Fraction(
                substitute(f.numerator(), indexVar, k),
                substitute(f.denominator(), indexVar, k),
                f.hasRule(), f.fractionStyle());
            case Radical r -> new Radical(
                substitute(r.radicand(), indexVar, k),
                r.index() == null ? null : substitute(r.index(), indexVar, k));
            case Fenced fe -> new Fenced(
                fe.leftDelim(), substitute(fe.body(), indexVar, k), fe.rightDelim());
            case Colored c -> new Colored(substitute(c.body(), indexVar, k), c.color());
            case Boxed b -> new Boxed(substitute(b.body(), indexVar, k));
            case Phantom p -> new Phantom(
                substitute(p.content(), indexVar, k), p.keepWidth(), p.keepVertical());
            // Leaves that structurally cannot hold an index atom — pass through as-is.
            case MathNode.Spacing sp -> sp;
            case MathNode.MiddleDelim md -> md;
            case MathNode.SizedDelim sd -> sd;
            case MathNode.OperatorName on -> on;
            case MathNode.TextRun tr -> tr;
            // Any other kind: degrade INERT rather than risk a silent mis-render.
            default -> throw new UnsupportedSummand();
        };
    }

    /** A digit run as a single node: one Atom, or a MathList for multi-digit. */
    private static MathNode asNode(List<MathNode> digits) {
        return digits.size() == 1 ? digits.get(0) : new MathList(digits);
    }

    /** The decimal digits of a non-negative int as ORD atoms (parser-identical class). */
    private static List<MathNode> digitAtoms(int k) {
        List<MathNode> digits = new ArrayList<>();
        for (char c : Integer.toString(k).toCharArray()) {
            digits.add(new Atom(c, MathClass.ORD));
        }
        return digits;
    }
}
