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

    private AtomSubstitution() { }

    /** Signals that a subtree holds a node kind this pass does not traverse. */
    static final class UnsupportedNode extends RuntimeException {
        UnsupportedNode() {
            super(null, null, false, false);
        }
    }

    /**
     * Deep-copy {@code node} into {@code out}, substituting every {@link Atom} whose code
     * point equals {@code varCodePoint} with the digit atom(s) of {@code value}. A matched
     * atom at the top level SPLICES (so {@code 10} contributes two sibling atoms rather
     * than a nested list).
     *
     * @throws UnsupportedNode on any node kind not traversed (caller degrades inert)
     */
    static void replaceInto(List<MathNode> out, MathNode node, int varCodePoint, int value) {
        if (node instanceof Atom a) {
            if (a.codePoint() == varCodePoint) {
                out.addAll(digitAtoms(value));      // splice: 10 -> [Atom(1), Atom(0)]
            } else {
                out.add(a);                         // records are immutable - share
            }
            return;
        }
        out.add(replace(node, varCodePoint, value));
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
                for (MathNode item : ml.items()) {
                    replaceInto(items, item, varCodePoint, value);
                }
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
     * The decimal digits of an int as ORD atoms (parser-identical class). A negative value
     * leads with a {@code -} carrying {@link MathClass#ORD} rather than {@code BIN}: it is
     * the sign of a literal, not a binary minus between two operands, and the distinction
     * is visible in the spacing the layout engine applies.
     */
    static List<MathNode> digitAtoms(int value) {
        List<MathNode> digits = new ArrayList<>();
        for (char c : Integer.toString(value).toCharArray()) {
            digits.add(new Atom(c, MathClass.ORD));
        }
        return digits;
    }
}
