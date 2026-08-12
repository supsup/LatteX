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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The render-time <strong>variable-substitution</strong> pass, powering the
 * {@code fx.*=substitute} effect: take a parsed body and a literal integer target, and
 * rewrite every occurrence of ONE variable into that integer as a new {@link MathNode}
 * tree — {@code x^2 + 2x + 1} with target {@code 3} becomes the tree for
 * {@code 3^2 + 2 \cdot 3 + 1}.
 *
 * <p>The second member of the numeric-substitution family, after {@link SumExpansion}.
 * Both share {@link AtomSubstitution} for the transform itself; what is specific here is
 * deciding WHICH variable, and refusing to guess when the answer is not unique.
 *
 * <p><strong>This pass is opt-in and flag-gated.</strong> It NEVER runs on the default
 * render path — {@code renderStyledHtml} calls it only when the host has enabled
 * {@link com.lattex.api.RenderOptions#interactiveExpansion()} AND the equation carries an
 * {@code fx.*=substitute} directive. LatteX stays a pure typesetter by default.
 *
 * <p><strong>Fail-INERT everywhere unsupported</strong> ({@link Optional#empty()}): a body
 * with no letter variable at all; a body with more than one distinct letter and no
 * explicit {@code fx.substitute-var} naming which to take; an explicit variable that does
 * not occur in the body (an author typo must not silently render an unchanged payload that
 * looks like a working effect); or a body holding a node kind {@link AtomSubstitution}
 * does not traverse. Inert means the expression typesets exactly as today and the
 * interaction simply never arms.
 *
 * <p><strong>Why "distinct letter", not "any atom".</strong> Digits are {@link Atom}s too,
 * and so are operators; only letters can be variables. Letters inside
 * {@link MathNode.OperatorName} nodes ({@code \sin}, {@code \lim}) are not {@link Atom}s at
 * all, so they are structurally immune to both the detection and the replacement.
 */
public final class SubstituteExpansion {

    private SubstituteExpansion() { }

    /**
     * A successful substitution: the rewritten {@link MathNode} (ready to lay out and emit
     * through the existing pipeline), the variable's code point (the value the
     * {@code data-lx-var} sidecar is keyed on), and the number of occurrences replaced.
     */
    public record Result(MathNode substituted, int variableCodePoint, int occurrences) { }

    /**
     * Substitute a literal integer for one variable, or {@link Optional#empty()} if any
     * guard rail rejects it (see the class doc for the full inert list).
     *
     * @param node the parsed body
     * @param target the literal integer to substitute in
     * @param explicitVariable the author's {@code fx.substitute-var} code point, or
     *     {@code null} to auto-detect the body's single distinct letter
     * @return the substitution, or empty (INERT) when unsupported
     */
    public static Optional<Result> expand(MathNode node, int target, Integer explicitVariable) {
        if (node == null) {
            return Optional.empty();
        }
        Set<Integer> letters = new LinkedHashSet<>();
        try {
            collectLetterAtoms(node, letters);
        } catch (AtomSubstitution.UnsupportedNode unsupported) {
            return Optional.empty();
        }

        final int variable;
        if (explicitVariable != null) {
            // An explicit variable that is not there is an author error, and rendering a
            // payload identical to the source would present a broken directive as a
            // working effect. Refuse instead.
            if (!letters.contains(explicitVariable)) {
                return Optional.empty();
            }
            variable = explicitVariable;
        } else {
            if (letters.size() != 1) {
                return Optional.empty();   // zero or ambiguous: the author must name one
            }
            variable = letters.iterator().next();
        }

        int occurrences = countOccurrences(node, variable);
        if (occurrences == 0) {
            return Optional.empty();
        }

        final MathNode substituted;
        try {
            substituted = AtomSubstitution.replace(node, variable, target);
        } catch (AtomSubstitution.UnsupportedNode unsupported) {
            return Optional.empty();
        }
        return Optional.of(new Result(substituted, variable, occurrences));
    }

    /**
     * Collect the distinct code points of every ORD letter {@link Atom} in the tree.
     *
     * <p>The traversal deliberately mirrors {@link AtomSubstitution}'s arm-for-arm, and
     * refuses the SAME node kinds: a body this cannot fully inspect must be inert, because
     * a variable hiding in an untraversed subtree would be left un-substituted and the
     * payload would differ from the source in a way the author never asked for.
     *
     * @throws AtomSubstitution.UnsupportedNode on any node kind not traversed
     */
    private static void collectLetterAtoms(MathNode node, Set<Integer> out) {
        switch (node) {
            case Atom a -> {
                if (a.mathClass() == MathClass.ORD && Character.isLetter(a.codePoint())) {
                    out.add(a.codePoint());
                }
            }
            case MathList ml -> {
                for (MathNode item : ml.items()) {
                    collectLetterAtoms(item, out);
                }
            }
            case SupSub s -> {
                collectLetterAtoms(s.base(), out);
                if (s.sup() != null) {
                    collectLetterAtoms(s.sup(), out);
                }
                if (s.sub() != null) {
                    collectLetterAtoms(s.sub(), out);
                }
            }
            case Fraction f -> {
                collectLetterAtoms(f.numerator(), out);
                collectLetterAtoms(f.denominator(), out);
            }
            case Radical r -> {
                collectLetterAtoms(r.radicand(), out);
                if (r.index() != null) {
                    collectLetterAtoms(r.index(), out);
                }
            }
            case Fenced fe -> collectLetterAtoms(fe.body(), out);
            case Colored c -> collectLetterAtoms(c.body(), out);
            case Boxed b -> collectLetterAtoms(b.body(), out);
            case Phantom p -> collectLetterAtoms(p.content(), out);
            // The same pass-through leaves AtomSubstitution treats as variable-free.
            case MathNode.Spacing sp -> { }
            case MathNode.MiddleDelim md -> { }
            case MathNode.SizedDelim sd -> { }
            case MathNode.OperatorName on -> { }
            case MathNode.TextRun tr -> { }
            default -> throw new AtomSubstitution.UnsupportedNode();
        }
    }

    /**
     * Count occurrences of the variable. This is the {@code data-lx-var} sidecar's
     * expected run length, so the runtime can tell "no occurrences addressed" (inert) from
     * "addressed them all".
     */
    private static int countOccurrences(MathNode node, int variable) {
        return switch (node) {
            case Atom a -> a.codePoint() == variable ? 1 : 0;
            case MathList ml -> {
                int total = 0;
                for (MathNode item : ml.items()) {
                    total += countOccurrences(item, variable);
                }
                yield total;
            }
            case SupSub s -> countOccurrences(s.base(), variable)
                + (s.sup() == null ? 0 : countOccurrences(s.sup(), variable))
                + (s.sub() == null ? 0 : countOccurrences(s.sub(), variable));
            case Fraction f -> countOccurrences(f.numerator(), variable)
                + countOccurrences(f.denominator(), variable);
            case Radical r -> countOccurrences(r.radicand(), variable)
                + (r.index() == null ? 0 : countOccurrences(r.index(), variable));
            case Fenced fe -> countOccurrences(fe.body(), variable);
            case Colored c -> countOccurrences(c.body(), variable);
            case Boxed b -> countOccurrences(b.body(), variable);
            case Phantom p -> countOccurrences(p.content(), variable);
            // Unreachable for a body collectLetterAtoms already accepted; zero is the
            // safe answer either way (an empty run reads as "nothing addressed" -> inert).
            default -> 0;
        };
    }
}
