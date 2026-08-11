package com.lattex.parse;

import com.lattex.parse.MathNode.Atom;
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

/**
 * The tree transform shared by the <strong>numeric-substitution family</strong>: replace
 * every {@link Atom} whose code point is a given variable with the decimal digits of an
 * integer, deep-copying the surrounding structure. {@link SumExpansion} uses it once per
 * term of an unfolded {@code \sum}; {@link SubstituteExpansion} uses it once, for the whole
 * body.
 *
 * <p><strong>Why this is ONE class and not two copies.</strong> The safety property lives
 * in the {@code default -> throw} arm below: a node kind this traversal does not know is
 * refused, so the caller degrades INERT rather than silently mis-rendering it. That
 * property is only as good as its coverage, and coverage that exists twice drifts — which
 * is precisely how {@code tryRenderMath} came to gate the glyphmap on {@code THREAD} while
 * {@code renderStyledHtml} gated it on {@code THREAD || CANCEL} (plan a01996f4). One
 * producer, so a new {@link MathNode} kind is handled for both consumers or neither.
 *
 * <p><strong>Why a TREE transform, not string substitution.</strong> Matching is by atom
 * code point on the parsed tree, so the {@code i} inside {@code \sin}/{@code \lim} can
 * never be touched — those are {@link MathNode.OperatorName} nodes whose letters are not
 * {@link Atom}s. A naive regex over the raw LaTeX would corrupt {@code \sin}; this cannot.
 */
final class AtomSubstitution {

    /** {@code \cdot} (U+22C5), the explicit product inserted at a juxtaposition boundary. */
    private static final int CDOT_CODEPOINT = 0x22C5;

    private AtomSubstitution() { }

    /** Signals that a subtree holds a node kind this pass does not traverse. */
    static final class UnsupportedNode extends RuntimeException {
        UnsupportedNode() {
            super(null, null, false, false);
        }
    }

    /**
     * Deep-copy a sequence of sibling nodes into {@code out}, substituting the variable.
     * A matched atom SPLICES (so {@code 10} contributes two sibling atoms rather than a
     * nested list).
     *
     * <p><strong>The juxtaposition guard.</strong> Implicit multiplication is written by
     * ADJACENCY in maths — {@code 2x} is a product — but adjacency between two DIGITS is
     * positional notation, so a naive splice turns {@code 2x} with {@code x=3} into
     * {@code 23}: not a rendering glitch but a different, false number. Whenever a
     * substituted digit run would land against a digit on either side, an explicit
     * {@code \cdot} is inserted so the product stays a product.
     *
     * <p>The guard fires ONLY at a substitution boundary, never between two digits that
     * were already adjacent in the source — {@code 12} must stay twelve, not become
     * {@code 1 \cdot 2}. That is why the splice flag is tracked here, on the sibling walk,
     * rather than inferred afterwards from the shape of the result.
     *
     * @throws UnsupportedNode on any node kind not traversed (caller degrades inert)
     */
    static void replaceList(List<MathNode> out, List<MathNode> items, int varCodePoint,
                            int value) {
        boolean previousWasSubstituted = false;
        for (MathNode item : items) {
            if (item instanceof Atom a && a.codePoint() == varCodePoint) {
                if (endsWithDigit(out)) {
                    out.add(new Atom(CDOT_CODEPOINT, MathClass.BIN));
                }
                out.addAll(digitAtoms(value));      // splice: 10 -> [Atom(1), Atom(0)]
                previousWasSubstituted = true;
                continue;
            }
            if (previousWasSubstituted && item instanceof Atom a && isDigit(a)) {
                out.add(new Atom(CDOT_CODEPOINT, MathClass.BIN));
            }
            if (item instanceof Atom a) {
                out.add(a);                         // records are immutable - share
            } else {
                out.add(replace(item, varCodePoint, value));
            }
            previousWasSubstituted = false;
        }
    }

    /**
     * Deep-copy ONE node into {@code out}, substituting the variable. Prefer
     * {@link #replaceList} when the node has siblings — a lone call cannot see its
     * neighbours, so it cannot apply the juxtaposition guard.
     *
     * @throws UnsupportedNode on any node kind not traversed (caller degrades inert)
     */
    static void replaceInto(List<MathNode> out, MathNode node, int varCodePoint, int value) {
        replaceList(out, List.of(node), varCodePoint, value);
    }

    private static boolean endsWithDigit(List<MathNode> out) {
        return !out.isEmpty()
            && out.get(out.size() - 1) instanceof Atom last
            && isDigit(last);
    }

    private static boolean isDigit(Atom a) {
        return a.codePoint() >= '0' && a.codePoint() <= '9';
    }

    /**
     * Deep-copy a single (non-spliced) node with the variable substituted.
     *
     * @throws UnsupportedNode on any node kind not traversed (caller degrades inert)
     */
    static MathNode replace(MathNode node, int varCodePoint, int value) {
        return switch (node) {
            case Atom a -> a.codePoint() == varCodePoint ? asNode(digitAtoms(value)) : a;
            case MathList ml -> {
                List<MathNode> items = new ArrayList<>();
                replaceList(items, ml.items(), varCodePoint, value);
                yield new MathList(items);
            }
            case SupSub s -> new SupSub(
                replace(s.base(), varCodePoint, value),
                s.sup() == null ? null : replace(s.sup(), varCodePoint, value),
                s.sub() == null ? null : replace(s.sub(), varCodePoint, value));
            case Fraction f -> new Fraction(
                replace(f.numerator(), varCodePoint, value),
                replace(f.denominator(), varCodePoint, value),
                f.hasRule(), f.fractionStyle());
            case Radical r -> new Radical(
                replace(r.radicand(), varCodePoint, value),
                r.index() == null ? null : replace(r.index(), varCodePoint, value));
            case Fenced fe -> new Fenced(
                fe.leftDelim(), replace(fe.body(), varCodePoint, value), fe.rightDelim());
            case Colored c -> new Colored(replace(c.body(), varCodePoint, value), c.color());
            case Boxed b -> new Boxed(replace(b.body(), varCodePoint, value));
            case Phantom p -> new Phantom(
                replace(p.content(), varCodePoint, value), p.keepWidth(), p.keepVertical());
            // Leaves that structurally cannot hold a variable atom - pass through as-is.
            case MathNode.Spacing sp -> sp;
            case MathNode.MiddleDelim md -> md;
            case MathNode.SizedDelim sd -> sd;
            case MathNode.OperatorName on -> on;
            case MathNode.TextRun tr -> tr;
            // Any other kind: degrade INERT rather than risk a silent mis-render.
            default -> throw new UnsupportedNode();
        };
    }

    /** A digit run as a single node: one Atom, or a MathList for multi-digit. */
    static MathNode asNode(List<MathNode> digits) {
        return digits.size() == 1 ? digits.get(0) : new MathList(digits);
    }

    /**
     * The decimal digits of an int as atoms, with the SAME {@link MathClass} the parser
     * gives the hand-written literal: {@link MathClass#ORD} digits, and a leading
     * {@code -} as {@link MathClass#BIN}. Matching the parser is not cosmetic — the
     * structural-identity oracle in the tests asserts that a substituted tree equals the
     * parse of the hand-written form, and math class is part of that equality (it drives
     * the spacing the layout engine applies).
     */
    static List<MathNode> digitAtoms(int value) {
        List<MathNode> digits = new ArrayList<>();
        String text = Integer.toString(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            digits.add(new Atom(c, c == '-' ? MathClass.BIN : MathClass.ORD));
        }
        return digits;
    }
}
