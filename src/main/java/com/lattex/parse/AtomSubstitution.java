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
        boolean previousTrailsSubstitutedDigit = false;
        for (MathNode item : items) {
            if (item instanceof Atom a && a.codePoint() == varCodePoint) {
                // A digit neighbour would CONCATENATE; a negative value after any operand
                // would re-read as SUBTRACTION. The second condition is the wider one and was
                // missing: `ax` with x=-3 produced the parse of `a-3`.
                if (endsWithDigit(out) || (value < 0 && endsWithMultiplicand(out))) {
                    out.add(new Atom(CDOT_CODEPOINT, MathClass.BIN));
                }
                out.addAll(digitAtoms(value));      // splice: 10 -> [Atom(1), Atom(0)]
                previousTrailsSubstitutedDigit = true;
                continue;
            }
            if (item instanceof Atom a) {
                if (previousTrailsSubstitutedDigit && isDigit(a)) {
                    out.add(new Atom(CDOT_CODEPOINT, MathClass.BIN));
                }
                out.add(a);                         // records are immutable - share
                previousTrailsSubstitutedDigit = false;
                continue;
            }
            // A COMPOUND node. Its substitution can put a digit at its own leading or
            // trailing edge (`x^2` with x=3 leads with `3`), and that edge juxtaposes with
            // a sibling exactly as a bare atom would — which is the hole Lattice found at
            // lattex/851: `2x^2` produced a tree byte-identical to the parse of `23^2`.
            Replacement r = replaceNode(item, varCodePoint, value);
            if ((previousTrailsSubstitutedDigit && r.leadsWithDigit())
                    || (r.leadsWithSubstitutedDigit() && endsWithDigit(out))
                    || (r.leadsWithSubstitutedMinus() && endsWithMultiplicand(out))) {
                out.add(new Atom(CDOT_CODEPOINT, MathClass.BIN));
            }
            out.add(r.node());
            previousTrailsSubstitutedDigit = r.trailsWithSubstitutedDigit();
        }
    }

    /**
     * A transformed node plus the BOUNDARY FACTS a sibling needs to decide whether an
     * explicit product is required at the seam.
     *
     * <p>Carrying the facts out of the transform beats wrapping every nested node in
     * {@code \cdot} (Lattice's ruling on lattex/851): fences, fractions and radicals
     * already draw a visible grouping boundary, so a product there is unambiguous to a
     * reader and an inserted dot would be noise. What is NOT visibly grouped is a
     * {@link SupSub} base — {@code 3^2} sits on the baseline exactly where a digit would —
     * so that is the edge whose facts must travel.
     *
     * @param node the transformed node
     * @param leadsWithSubstitutedDigit its first rendered glyph is a digit THIS pass
     *     substituted (so a preceding digit would concatenate)
     * @param trailsWithSubstitutedDigit its last BASELINE glyph is a digit this pass
     *     substituted (so a following digit would concatenate)
     * @param leadsWithDigit its first rendered glyph is a digit from any source — used when
     *     the PRECEDING sibling was substituted, where the collision is symmetric
     * @param leadsWithSubstitutedMinus its first rendered glyph is a MINUS this pass
     *     substituted. Needed for a compound that draws no grouping and carries no script —
     *     a {@link Colored} wrapper — where neither the fence nor the digit seam applies but
     *     the subtraction re-reading still does
     */
    private record Replacement(MathNode node, boolean leadsWithSubstitutedDigit,
                               boolean trailsWithSubstitutedDigit, boolean leadsWithDigit,
                               boolean leadsWithSubstitutedMinus) { }

    /** Transform a compound node and report its substitution boundary facts. */
    private static Replacement replaceNode(MathNode node, int varCodePoint, int value) {
        MathNode replaced = replace(node, varCodePoint, value);
        boolean substituted = !replaced.equals(node);
        return switch (replaced) {
            // The base renders on the baseline, first. A sup/sub is RAISED/LOWERED, so it
            // cannot concatenate with a following baseline digit — only a bare base can.
            //
            // The fact must come from the BASE's own substitution, not from `substituted`
            // (Lattice, lattex/861 finding 1). `substituted` is true when ANY descendant
            // changed, so substituting only the EXPONENT of `23^x` marked the SOURCE digit 3
            // as a substituted boundary and split a literal twenty-three into `2 \cdot 3^2`.
            // A boundary invented where none exists changes meaning exactly as surely as a
            // boundary missed — the 851 defect and this one are the same error, mirrored.
            case SupSub s -> {
                boolean baseSubstituted = node instanceof SupSub original
                    && !s.base().equals(original.base());
                yield new Replacement(replaced,
                    baseSubstituted && leadsWithDigit(s.base()),
                    baseSubstituted && s.sup() == null && s.sub() == null
                        && trailsWithDigit(s.base()),
                    leadsWithDigit(s.base()),
                    baseSubstituted && leadsWithMinus(s.base()));
            }
            case MathList ml -> {
                MathNode first = ml.items().isEmpty() ? null : ml.items().get(0);
                MathNode last = ml.items().isEmpty() ? null : ml.items().get(ml.items().size() - 1);
                yield new Replacement(replaced,
                    substituted && first != null && leadsWithDigit(first),
                    substituted && last != null && trailsWithDigit(last),
                    first != null && leadsWithDigit(first),
                    substituted && first != null && leadsWithMinus(first));
            }
            // COLOUR IS PAINT, NOT GROUPING, so a Colored wrapper reports the facts of its
            // body rather than the silence of a boundary-drawing node (Lattice, lattex/883).
            // It sat in the default arm below and produced `\textcolor{red}{2}` immediately
            // followed by a substituted `3` — the digit string 23.
            case Colored c -> {
                boolean bodySubstituted = node instanceof Colored original
                    && !c.body().equals(original.body());
                yield new Replacement(replaced,
                    bodySubstituted && leadsWithDigit(c.body()),
                    bodySubstituted && trailsWithDigit(c.body()),
                    leadsWithDigit(c.body()),
                    bodySubstituted && leadsWithMinus(c.body()));
            }
            // Fenced/Fraction/Radical/Boxed/Phantom and the pass-through leaves all draw their
            // own visible boundary; a digit inside them cannot be misread as positional
            // notation with a sibling outside them, and a minus inside one is unambiguous.
            default -> new Replacement(replaced, false, false, false, false);
        };
    }

    // COLORED IS TRANSPARENT TO EVERY EDGE QUESTION BELOW (Lattice, lattex/883 finding 2).
    // It sat in the `default -> false` arm alongside Fenced/Fraction/Radical, and the grouping
    // those draw is exactly what it does NOT draw: `\textcolor{red}{2}` paints a 2 and puts it
    // on the baseline where any other 2 would be. Treating paint as grouping meant
    // `\textcolor{red}{2}x` with x=3 rendered the digit string `23`, and a coloured negative
    // base neither exported a seam nor earned its fence. Colour changes paint, not adjacency.

    /** Whether the node's first rendered glyph is a minus sign — the negative-value shape. */
    private static boolean leadsWithMinus(MathNode n) {
        return switch (n) {
            case Atom a -> a.codePoint() == '-';
            case SupSub s -> leadsWithMinus(s.base());
            case Colored c -> leadsWithMinus(c.body());
            case MathList ml -> !ml.items().isEmpty() && leadsWithMinus(ml.items().get(0));
            default -> false;
        };
    }

    private static boolean leadsWithDigit(MathNode n) {
        return switch (n) {
            case Atom a -> isDigit(a);
            case SupSub s -> leadsWithDigit(s.base());
            case Colored c -> leadsWithDigit(c.body());
            case MathList ml -> !ml.items().isEmpty() && leadsWithDigit(ml.items().get(0));
            default -> false;
        };
    }

    private static boolean trailsWithDigit(MathNode n) {
        return switch (n) {
            case Atom a -> isDigit(a);
            case SupSub s -> s.sup() == null && s.sub() == null && trailsWithDigit(s.base());
            case Colored c -> trailsWithDigit(c.body());
            case MathList ml -> !ml.items().isEmpty()
                && trailsWithDigit(ml.items().get(ml.items().size() - 1));
            default -> false;
        };
    }

    /**
     * Whether {@code out} ends with something a following factor would MULTIPLY — an operand
     * rather than an operator or an empty list.
     *
     * <p>Needed because {@link #endsWithDigit} is too narrow for a negative value (Lattice,
     * lattex/883 finding 1). A substituted {@code -3} after a digit was already guarded, but
     * after any other operand it was not: {@code ax} with {@code x=-3} produced atoms
     * structurally identical to the parse of {@code a-3}, a subtraction. The source adjacency
     * meant multiplication.
     *
     * <p>The operator exclusion is what keeps this from becoming its own defect: after a BIN or
     * REL the minus is a SIGN, not a seam, and {@code 1+x} must stay {@code 1+-3} rather than
     * gaining a nonsensical {@code 1+\cdot-3}.
     */
    private static boolean endsWithMultiplicand(List<MathNode> out) {
        if (out.isEmpty()) {
            return false;
        }
        return !(out.get(out.size() - 1) instanceof Atom a
            && (a.mathClass() == MathClass.BIN || a.mathClass() == MathClass.REL
                || a.mathClass() == MathClass.OPEN || a.mathClass() == MathClass.PUNCT
                || a.mathClass() == MathClass.OP));
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

    /**
     * Whether the last thing already emitted ends in a digit ON THE BASELINE.
     *
     * <p>Delegates to {@link #trailsWithDigit} rather than testing for a bare {@link Atom},
     * so a digit inside a transparent wrapper counts (Lattice, lattex/883). The bare-Atom
     * test made {@code \textcolor{red}{2}} invisible as a left neighbour, and
     * {@code \textcolor{red}{2}x} with {@code x=3} rendered the digit string {@code 23} —
     * the original 851 defect, reappearing behind a colour.
     */
    private static boolean endsWithDigit(List<MathNode> out) {
        return !out.isEmpty() && trailsWithDigit(out.get(out.size() - 1));
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
            case SupSub s -> {
                MathNode base = replace(s.base(), varCodePoint, value);
                MathNode sup = s.sup() == null ? null : replace(s.sup(), varCodePoint, value);
                MathNode sub = s.sub() == null ? null : replace(s.sub(), varCodePoint, value);
                // A NEGATIVE value substituted into an EXPONENT's base must be parenthesised
                // (Lattice, lattex/861 finding 2). `2x^2` with x=-3 produced `2-3^2`, which
                // is wrong twice over: it reads as a subtraction, and `-3^2` is -(3^2) under
                // ordinary precedence when the substitution means (-3)^2. Grouping repairs
                // both — the fence ends the subtraction reading AND binds the sign into the
                // base — which is why it is the remedy rather than only inserting a product.
                //
                // A SUBSCRIPT needs the same fence, for a different reason (Lattice,
                // lattex/863). An earlier version of this code scoped the remedy to `sup`
                // alone, on the strength of a comment claiming "a subscript carries no
                // precedence hazard". That claim is true and was the wrong thing to be true:
                // there is no PRECEDENCE hazard inside the node, and there are two other
                // hazards it says nothing about.
                //
                // `2x_2` with x=-3 produced `2-3_2`. First, the seam: the coefficient now
                // abuts a leading minus, so a product reads as a SUBTRACTION. Second, and
                // the reason a fence beats an inserted `\cdot` here — `-3_2` and `(-3)_2`
                // are not the same value. A subscript on a numeral commonly denotes a radix,
                // so `-3_2` binds the subscript to the 3 and negates the result, while the
                // substitution means the whole -3 carries the subscript. The two render
                // identically and differ in value, which is the worst pair of properties a
                // defect can have. The fence closes both readings at once.
                //
                // Still scoped to a sign THIS pass introduced: a minus already in the source
                // was already grouped or already meant what it says, and a remedy applied
                // wider than its hazard becomes the next silent edit.
                if ((sup != null || sub != null) && !base.equals(s.base())
                        && leadsWithMinus(base)) {
                    base = new Fenced('(', base, ')');
                }
                yield new SupSub(base, sup, sub);
            }
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
