package com.lattex.parse;

import java.util.List;
import java.util.Optional;

/**
 * The math tree ADT — a {@code sealed interface} closed over its node kinds so
 * layout traversals can pattern-match exhaustively (the compiler proves every
 * case is handled, no {@code default} branch needed).
 *
 * <p>The node set models the MVP LaTeX-math grammar. TeX's <em>atom classes</em>
 * (Ord, Op, Bin, Rel, Open, Close, Punct, Inner — see {@link MathClass}) are
 * carried explicitly on every {@link Atom}, because inter-atom spacing (the S4
 * layout concern, TeXbook Appendix G) is a function of the classes of adjacent
 * atoms. Composite nodes have an <em>implied</em> class that S4 derives from
 * their kind rather than storing here:
 * <ul>
 *   <li>{@link Fraction}, {@link Radical} &rarr; Ord</li>
 *   <li>{@link Fenced} &rarr; Inner</li>
 *   <li>{@link BigOperator} &rarr; Op (it wraps an Op {@link Atom})</li>
 *   <li>{@link MathList} &rarr; the class of its content in context</li>
 *   <li>{@link Spacing} &rarr; classless (pure glue)</li>
 * </ul>
 *
 * <p><strong>Optional child slots — nullable components, {@code Optional}
 * accessors.</strong> Several nodes have optional children (a {@link SupSub}
 * may carry only a superscript; a {@link Radical} may have no index). Following
 * <em>Effective Java</em> Item 55 ({@code Optional} is a return type, not a
 * field type), the optional slots are stored as <em>nullable record
 * components</em> and each exposes an {@code Optional}-returning convenience
 * accessor (e.g. {@link SupSub#superscript()}). The raw record accessors
 * ({@code sup()}, {@code sub()}, {@code index()}, …) return the possibly-null
 * component directly; the {@code Optional} accessors are the recommended read
 * path for consumers.
 */
public sealed interface MathNode {

    /**
     * The eight TeX math atom classes (TeXbook Ch.18 / Appendix G). Inter-atom
     * spacing in S4 layout is looked up on the ordered pair of adjacent classes.
     */
    enum MathClass {
        /** Ordinary: variables, digits, most symbols. */
        ORD,
        /** Large operator: {@code \sum \int \prod}. */
        OP,
        /** Binary operator: {@code + - \times \cdot \pm}. */
        BIN,
        /** Relation: {@code = < > \leq \geq \to \in}. */
        REL,
        /** Opening delimiter: {@code (}, {@code [}, or a left brace. */
        OPEN,
        /** Closing delimiter: {@code )}, {@code ]}, or a right brace. */
        CLOSE,
        /** Punctuation: {@code , ;}. */
        PUNCT,
        /** Inner: {@code \left..\right} sub-formulas, {@code \cdots}. */
        INNER
    }

    /**
     * Limit-placement mode for a {@link BigOperator}, set by {@code \limits} /
     * {@code \nolimits}. {@link #DEFAULT} defers to the style rule (limits go
     * above/below in display style, beside in text style).
     */
    enum LimitsMode {
        /** Style-dependent default placement. */
        DEFAULT,
        /** Force limits above/below the operator ({@code \limits}). */
        LIMITS,
        /** Force limits beside the operator as scripts ({@code \nolimits}). */
        NOLIMITS
    }

    /**
     * A single character on the baseline (a variable, digit, or symbol),
     * tagged with its {@link MathClass}.
     *
     * @param codePoint the Unicode code point rendered
     * @param mathClass the atom's spacing class
     */
    record Atom(int codePoint, MathClass mathClass) implements MathNode {
        public Atom {
            if (mathClass == null) {
                throw new IllegalArgumentException("Atom mathClass must not be null");
            }
        }

        /** The code point as a {@code char} (valid for BMP atoms). */
        public char asChar() {
            return (char) codePoint;
        }

        /** Convenience factory for an ordinary (Ord) atom. */
        public static Atom ord(int codePoint) {
            return new Atom(codePoint, MathClass.ORD);
        }
    }

    /**
     * A horizontal list (row) of nodes — the "mlist" of TeX. An empty list
     * models an empty group {@code {}}.
     *
     * @param items the row contents, in reading order (defensively copied)
     */
    record MathList(List<MathNode> items) implements MathNode {
        public MathList {
            items = List.copyOf(items);
        }
    }

    /**
     * A base (nucleus) with an optional superscript and/or subscript. At least
     * one of {@code sup}/{@code sub} is non-null (a scriptless nucleus is not
     * wrapped in a {@code SupSub}). Both may be {@code null} individually.
     *
     * @param base the nucleus
     * @param sup  the superscript, or {@code null}
     * @param sub  the subscript, or {@code null}
     */
    record SupSub(MathNode base, MathNode sup, MathNode sub) implements MathNode {
        public SupSub {
            if (base == null) {
                throw new IllegalArgumentException("SupSub base must not be null");
            }
            if (sup == null && sub == null) {
                throw new IllegalArgumentException("SupSub must carry a sup and/or a sub");
            }
        }

        /** The superscript, if present. */
        public Optional<MathNode> superscript() {
            return Optional.ofNullable(sup);
        }

        /** The subscript, if present. */
        public Optional<MathNode> subscript() {
            return Optional.ofNullable(sub);
        }

        /** Convenience factory for a superscript-only node. */
        public static SupSub superscript(MathNode base, MathNode sup) {
            return new SupSub(base, sup, null);
        }

        /** Convenience factory for a subscript-only node. */
        public static SupSub subscript(MathNode base, MathNode sub) {
            return new SupSub(base, null, sub);
        }
    }

    /**
     * A fraction {@code \frac{num}{den}}.
     *
     * @param numerator   the numerator sub-formula
     * @param denominator the denominator sub-formula
     */
    record Fraction(MathNode numerator, MathNode denominator) implements MathNode {
        public Fraction {
            if (numerator == null || denominator == null) {
                throw new IllegalArgumentException("Fraction parts must not be null");
            }
        }
    }

    /**
     * A radical {@code \sqrt{radicand}} or {@code \sqrt[index]{radicand}}.
     *
     * @param radicand the expression under the root
     * @param index    the root index (e.g. the 3 in a cube root), or {@code null}
     */
    record Radical(MathNode radicand, MathNode index) implements MathNode {
        public Radical {
            if (radicand == null) {
                throw new IllegalArgumentException("Radical radicand must not be null");
            }
        }

        /** The root index, if present. */
        public Optional<MathNode> indexNode() {
            return Optional.ofNullable(index);
        }

        /** Convenience factory for a square root (no index). */
        public static Radical sqrt(MathNode radicand) {
            return new Radical(radicand, null);
        }
    }

    /**
     * A large operator ({@code \sum \int \prod}) with optional lower/upper
     * limits and a {@link LimitsMode}.
     *
     * @param op         the operator atom (class {@link MathClass#OP})
     * @param lower      the lower limit (the {@code _} script), or {@code null}
     * @param upper      the upper limit (the {@code ^} script), or {@code null}
     * @param limitsMode limit placement, from {@code \limits}/{@code \nolimits}
     */
    record BigOperator(Atom op, MathNode lower, MathNode upper, LimitsMode limitsMode)
            implements MathNode {
        public BigOperator {
            if (op == null) {
                throw new IllegalArgumentException("BigOperator op must not be null");
            }
            if (limitsMode == null) {
                throw new IllegalArgumentException("BigOperator limitsMode must not be null");
            }
        }

        /** The lower limit, if present. */
        public Optional<MathNode> lowerLimit() {
            return Optional.ofNullable(lower);
        }

        /** The upper limit, if present. */
        public Optional<MathNode> upperLimit() {
            return Optional.ofNullable(upper);
        }
    }

    /**
     * A delimited sub-formula {@code \left<d> body \right<d>}. The delimiters
     * are code points; {@link #NULL_DELIMITER} models a null delimiter
     * ({@code \left.} / {@code \right.}), which renders nothing but still
     * balances the pair.
     *
     * @param leftDelim  the opening delimiter code point, or {@link #NULL_DELIMITER}
     * @param body       the enclosed sub-formula
     * @param rightDelim the closing delimiter code point, or {@link #NULL_DELIMITER}
     */
    record Fenced(int leftDelim, MathNode body, int rightDelim) implements MathNode {
        /** Sentinel for a null delimiter ({@code \left.} / {@code \right.}). */
        public static final int NULL_DELIMITER = -1;

        public Fenced {
            if (body == null) {
                throw new IllegalArgumentException("Fenced body must not be null");
            }
        }

        /** Whether the opening delimiter is present (not {@code \left.}). */
        public boolean hasLeft() {
            return leftDelim != NULL_DELIMITER;
        }

        /** Whether the closing delimiter is present (not {@code \right.}). */
        public boolean hasRight() {
            return rightDelim != NULL_DELIMITER;
        }
    }

    /**
     * Explicit horizontal spacing (glue), e.g. the thin space {@code \,}. The
     * width is in math units (mu); 18mu = 1em.
     *
     * @param muWidth the space width in math units (may be negative, e.g. {@code \!})
     */
    record Spacing(double muWidth) implements MathNode {
    }

    /**
     * A base decorated with an accent above (or a line below), covering the two
     * families of TeX accent commands:
     *
     * <ul>
     *   <li><strong>Glyph accents</strong> — an accent glyph centred over the
     *       base. <em>Narrow</em> ({@code \hat \bar \vec \dot \ddot \tilde \check
     *       \breve \acute \grave \mathring}) draw the accent at its natural size;
     *       <em>stretchy</em> ({@code \widehat \widetilde \overrightarrow
     *       \overleftarrow \overleftrightarrow}) size the accent to the base width
     *       via the OpenType MATH horizontal glyph construction. In both cases
     *       {@link #accentCodePoint} is the accent glyph's code point.</li>
     *   <li><strong>Line decorations</strong> — {@code \overline} and
     *       {@code \\underline}, drawn as a rule (not a glyph). These carry
     *       {@link #accentCodePoint} == {@link #RULE}; {@link #under} selects an
     *       under-line ({@code true}) versus an over-line ({@code false}).</li>
     * </ul>
     *
     * <p>The accented result has an implied Ord class (like the nucleus it
     * decorates). Positioning is clean-room from Knuth's TeXbook (Appendix G, the
     * accent rule) and the public OpenType MATH spec
     * ({@code MathTopAccentAttachment}, {@code accentBaseHeight}, the over/underbar
     * constants, and {@code MathVariants} for stretchy sizing).
     *
     * @param command         the LaTeX command name (without backslash), for a11y
     * @param base            the decorated nucleus
     * @param accentCodePoint the accent glyph's code point, or {@link #RULE} for
     *                        {@code \overline}/{@code \\underline}
     * @param stretchy        whether the accent glyph is sized to the base width
     * @param under           whether the decoration sits below the base (only the
     *                        {@code \\underline} rule); {@code false} for every
     *                        over-accent and the over-line
     */
    record Accent(String command, MathNode base, int accentCodePoint,
                  boolean stretchy, boolean under) implements MathNode {

        /** Sentinel {@link #accentCodePoint} for a rule decoration (over/underline). */
        public static final int RULE = -1;

        public Accent {
            if (command == null) {
                throw new IllegalArgumentException("Accent command must not be null");
            }
            if (base == null) {
                throw new IllegalArgumentException("Accent base must not be null");
            }
            if (under && accentCodePoint != RULE) {
                throw new IllegalArgumentException("only a rule decoration may be under the base");
            }
            if (stretchy && accentCodePoint == RULE) {
                throw new IllegalArgumentException("a rule decoration is not stretchy");
            }
        }

        /** Whether this decoration is a rule ({@code \overline}/{@code \\underline}). */
        public boolean isRule() {
            return accentCodePoint == RULE;
        }
    }

    /**
     * A named mathematical operator set in upright roman type — the trig/log
     * family ({@code \sin \cos \log \ln \exp}, …), the limit-taking operators
     * ({@code \lim \max \min \sup \inf \det \gcd \dim \ker \arg \Pr}, …), and the
     * user form {@code \operatorname{name}} / {@code \operatorname*{name}}.
     *
     * <p>Clean-room from Knuth's TeXbook: a named operator is a {@code \mathop}
     * whose nucleus is roman text, so it has math class {@link MathClass#OP}
     * (its surrounding spacing is the Op-class spacing, exactly like a large
     * operator). Unlike a single-glyph {@link BigOperator}, its nucleus is a
     * <em>multi-letter</em> roman word, so it is a distinct node kind rather than
     * an {@link Atom}.
     *
     * <p>{@link #takesLimits} distinguishes the two TeX families: a limit-taking
     * operator ({@code \lim}, {@code \operatorname*{…}}) sets its {@code ^}/{@code
     * _} scripts as limits stacked <em>above/below</em> in display style (beside,
     * as ordinary scripts, in text style — TeXbook Ch.18); a non-limit operator
     * ({@code \sin}, {@code \operatorname{…}}) always sets scripts beside.
     *
     * <p>The {@link #name} is the roman text to render, and may contain a single
     * ASCII space for the compound operators ({@code lim inf}, {@code lim sup},
     * {@code inj lim}, {@code proj lim}); a space renders as a thin (3mu) gap.
     *
     * @param name        the roman operator text (letters, optionally with a
     *                    single-space word break), e.g. {@code "sin"}, {@code "lim"}
     * @param takesLimits whether {@code ^}/{@code _} scripts become over/under
     *                    limits in display style
     */
    record OperatorName(String name, boolean takesLimits) implements MathNode {
        public OperatorName {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("OperatorName name must be non-empty");
            }
        }
    }
}
