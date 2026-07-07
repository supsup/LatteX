package com.lattex.parse;

import com.lattex.api.RenderOptions;
import java.util.List;
import java.util.Objects;
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
     * The math-style a {@link Fraction} is set in, from the TeXbook's
     * {@code \genfrac} style argument. {@link #INHERIT} sets the fraction in the
     * surrounding context's style (the ordinary {@code \frac}, {@code \binom});
     * {@link #DISPLAY} forces display style (the larger {@code \cfrac} /
     * {@code \dbinom} form); {@link #TEXT} forces text style ({@code \tbinom}).
     */
    enum FractionStyle {
        /** Set in the surrounding style ({@code \frac}, {@code \binom}). */
        INHERIT,
        /** Force display style ({@code \cfrac}, {@code \dbinom}). */
        DISPLAY,
        /** Force text style ({@code \tbinom}). */
        TEXT
    }

    /**
     * A generalized fraction — the numerator/denominator stack shared by
     * {@code \frac}, {@code \cfrac} (display style) and {@code \binom} (no rule,
     * wrapped by a paren {@link Fenced} at parse time). Clean-room from Knuth's
     * TeXbook {@code \genfrac}: the fraction bar is optional ({@link #hasRule}
     * false for {@code \binom}) and the whole stack may force a math style
     * ({@link #fractionStyle}).
     *
     * @param numerator     the numerator sub-formula
     * @param denominator   the denominator sub-formula
     * @param hasRule       whether the fraction bar (rule) is drawn — {@code true}
     *                      for {@code \frac}/{@code \cfrac}, {@code false} for the
     *                      {@code \binom} family
     * @param fractionStyle the math-style override for the stack
     */
    record Fraction(MathNode numerator, MathNode denominator, boolean hasRule,
                    FractionStyle fractionStyle) implements MathNode {
        public Fraction {
            if (numerator == null || denominator == null) {
                throw new IllegalArgumentException("Fraction parts must not be null");
            }
            if (fractionStyle == null) {
                throw new IllegalArgumentException("Fraction fractionStyle must not be null");
            }
        }

        /** An ordinary ruled fraction ({@code \frac}) set in the inherited style. */
        public Fraction(MathNode numerator, MathNode denominator) {
            this(numerator, denominator, true, FractionStyle.INHERIT);
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
     * A phantom box — its {@code content} is laid out to obtain metrics but emits
     * <em>no ink</em> (no glyphs, no rules). It reserves space so surrounding
     * material aligns as if the content were present. Covers the TeX/LaTeX family:
     *
     * <ul>
     *   <li>{@code \phantom{x}} — keep width <em>and</em> vertical extent
     *       ({@code keepWidth} &amp; {@code keepVertical} both true).</li>
     *   <li>{@code \hphantom{x}} — keep width only (zero height/depth).</li>
     *   <li>{@code \vphantom{x}} — keep vertical extent only (zero width); also how
     *       {@code \mathstrut} is realised, as {@code \vphantom{(}}.</li>
     * </ul>
     *
     * <p>The phantom has an implied Ord class (it behaves like the box it mimics).
     * Being ink-free it stays trivially within the minimal SVG alphabet.
     *
     * @param content      the sub-formula whose metrics are borrowed
     * @param keepWidth    whether the phantom advances by the content's width
     * @param keepVertical whether the phantom keeps the content's height and depth
     */
    record Phantom(MathNode content, boolean keepWidth, boolean keepVertical)
            implements MathNode {
        public Phantom {
            if (content == null) {
                throw new IllegalArgumentException("Phantom content must not be null");
            }
            if (!keepWidth && !keepVertical) {
                throw new IllegalArgumentException(
                    "Phantom must preserve width and/or vertical extent");
            }
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

    /**
     * The typeface shape of a {@link TextRun}, selecting which glyph family the
     * run's ASCII letters/digits are drawn from. Clean-room from Knuth's TeXbook
     * (text mode is upright roman; {@code \textbf}/{@code \textit}/{@code \texttt}
     * switch the family). {@link #ROMAN} uses the font's plain (upright) glyphs;
     * the others remap ASCII letters/digits to the corresponding Unicode
     * Mathematical Alphanumeric Symbols, which the bundled math font provides.
     */
    enum TextStyle {
        /** Upright roman (the font's plain glyphs) — {@code \text}, {@code \textrm}, {@code \mathrm}. */
        ROMAN,
        /** Upright bold — {@code \textbf}. */
        BOLD,
        /** Slanted italic — {@code \textit}. */
        ITALIC,
        /** Monospace (typewriter) — {@code \texttt}. */
        MONO
    }

    /**
     * A run of <em>text-mode</em> characters set as an upright (or {@link
     * TextStyle}-shaped) word — the {@code \text}/{@code \textrm}/{@code \textbf}/
     * {@code \textit}/{@code \texttt} family and the math-mode {@code \mathrm}.
     *
     * <p>Text mode differs from math mode in two ways (TeXbook Ch.18): letters are
     * <strong>not</strong> italicised (roman by default, versus math's slanted
     * variables) and inter-word <strong>spaces are significant</strong> (in math
     * mode they are ignored). The {@link #text} therefore carries its spaces
     * literally; layout renders each as a real inter-word gap (the font's space
     * advance), and every character — letters, digits, punctuation, and the spaces
     * — is drawn as a filled glyph {@code <path>} (never an SVG {@code <text>}
     * element), so the run stays within the minimal SVG alphabet.
     *
     * <p>The run has an implied Ord class (a word behaves as an ordinary atom in a
     * row). {@link #text} may be empty (an empty {@code \text{}} renders nothing).
     *
     * @param text  the literal text to typeset, spaces preserved
     * @param style the typeface shape
     */
    record TextRun(String text, TextStyle style) implements MathNode {
        public TextRun {
            if (text == null) {
                throw new IllegalArgumentException("TextRun text must not be null");
            }
            if (style == null) {
                throw new IllegalArgumentException("TextRun style must not be null");
            }
        }
    }

    /**
     * The author's {@code \lx[options]{body}} styled-math wrapper — a body
     * sub-tree annotated with a validated visual {@link #style()}, an
     * {@link #fx() effect spec}, and {@link #sem() semantics}. Produced only by the
     * {@code \lx} macro (see {@link MathParser}); every option is validated and
     * reduced to a typed value at parse time.
     *
     * <p><strong>Where each field goes.</strong>
     * <ul>
     *   <li>{@link #style()} — an L1 {@link RenderOptions} (scale / color /
     *       mathStyle). Applied to the rendered {@code <svg>} through L1's existing
     *       threading: scale folds into the font size, mathStyle seeds the layout
     *       context, color becomes the emitter's {@code fill} <em>value</em>. It
     *       introduces no new SVG element/attribute.</li>
     *   <li>{@link #fx()} — an {@link EffectSpec} (enter/hover/click effect +
     *       duration). Validated and stored, but <strong>NEVER emitted into the
     *       {@code <svg>}</strong>; it rides the trusted wrapping container
     *       ({@code <span class="lx-math" data-lx-fx-*>}) instead.</li>
     *   <li>{@link #sem()} — {@link Semantics} (intent / concept / a11y label /
     *       data-*). Likewise validated, stored, and <strong>NEVER emitted into the
     *       {@code <svg>}</strong>; it rides the container as {@code data-lx-*} /
     *       {@code aria-label}. Keeping fx and semantics off the render path is why
     *       the emitter's SVG alphabet is unchanged.</li>
     * </ul>
     *
     * <p>{@code \lx} is top-level-only for the MVP (it styles the whole
     * expression); a nested {@code \lx} is rejected by the parser with a clear
     * message rather than silently misrendered.
     *
     * @param body  the wrapped math tree (the {@code {…}} LaTeX body)
     * @param style the validated visual style (applied on render)
     * @param fx    the validated effect spec (stored, rides the container)
     * @param sem   the validated semantics (stored, rides the container)
     */
    record StyledMath(MathNode body, RenderOptions style, EffectSpec fx, Semantics sem)
            implements MathNode {
        public StyledMath {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(fx, "fx");
            Objects.requireNonNull(sem, "sem");
        }
    }

    /**
     * Per-column horizontal alignment inside a {@link Matrix} grid cell (the
     * {@code l}/{@code c}/{@code r} of an {@code array} column spec; matrices are
     * uniformly {@link #CENTER}, {@code cases} uniformly {@link #LEFT}).
     */
    enum ColumnAlign {
        /** Left-aligned column ({@code l}). */
        LEFT,
        /** Centred column ({@code c}) — the matrix default. */
        CENTER,
        /** Right-aligned column ({@code r}). */
        RIGHT
    }

    /**
     * A horizontal rule between grid rows: {@code \hline} (a solid rule) or
     * {@code \hdashline} (a dashed rule, drawn as a run of short {@code <rect>}s).
     * Both stay within the minimal SVG alphabet ({@code <rect>} only).
     */
    enum RowRule {
        /** No rule at this inter-row gap. */
        NONE,
        /** A solid rule ({@code \hline}). */
        SOLID,
        /** A dashed rule ({@code \hdashline}). */
        DASHED
    }

    /**
     * The kind of grid environment, which selects the clean-room TeX spacing
     * template (inter-column / inter-row separation and cell math style).
     */
    enum MatrixKind {
        /** {@code matrix}/{@code pmatrix}/… — cells in text style, centred. */
        MATRIX,
        /** {@code smallmatrix} — cells in script style, tight spacing. */
        SMALL,
        /**
         * {@code \substack{a\\b}} — a single centred column set in script style with a
         * tightened baseline and no edge padding, used to stack conditions under a
         * big-operator limit.
         */
        SUBSTACK,
        /** {@code array} — user column spec (l/c/r + {@code |} rules), {@code \hline}s. */
        ARRAY,
        /** {@code cases} — a left brace over two left-aligned columns, wide gap. */
        CASES,
        /**
         * {@code align}/{@code aligned} — alternating right/left column pairs, each
         * pair meeting at the relation alignment point (the {@code &}); cells in
         * display style.
         */
        ALIGN,
        /** {@code gather} — a single centred column, one equation per row, display style. */
        GATHER,
        /**
         * {@code multline} — one long equation across lines with NO alignment column:
         * the first line is flush-left, the last line flush-right, middle lines centred.
         * A single-column grid whose alignment is applied per ROW in layout. Display style.
         */
        MULTLINE
    }

    /**
     * A 2-D grid of cells — the {@code matrix}/{@code pmatrix}/{@code bmatrix}/
     * {@code Bmatrix}/{@code vmatrix}/{@code Vmatrix}/{@code smallmatrix},
     * {@code array}, and {@code cases} LaTeX environments (TeXbook {@code \halign}).
     * {@code &} separates columns and {@code \\} separates rows; the parser pads
     * short rows so {@link #rows} is rectangular with exactly
     * {@link #columnAligns}{@code .size()} columns.
     *
     * <p>Optional enclosing delimiters ({@link #leftDelim}/{@link #rightDelim}, code
     * points or {@link Fenced#NULL_DELIMITER}) stretch to the grid height exactly as
     * a {@code \left..\right} pair does. Vertical rules from an {@code array} column
     * spec are carried in {@link #columnRules} (a count at each of the
     * {@code columns+1} boundaries); horizontal {@code \hline}/{@code \hdashline}
     * rules are carried in {@link #rowRules} (one entry per {@code rows+1} inter-row
     * gap). Both rule families render as {@code <rect>}s (in-alphabet).
     *
     * <p>A grid with any delimiter has an implied Inner class (like a
     * {@code \left..\right} sub-formula); a bare grid is Ord.
     *
     * @param rows         the grid cells, {@code rows[r][c]}, rectangular
     * @param columnAligns per-column alignment (length = column count)
     * @param columnRules  vertical-rule counts at each boundary (length = columns+1)
     * @param rowRules     the rule at each inter-row gap (length = rows+1)
     * @param leftDelim    the opening delimiter code point, or {@link Fenced#NULL_DELIMITER}
     * @param rightDelim   the closing delimiter code point, or {@link Fenced#NULL_DELIMITER}
     * @param kind         the environment kind (selects the spacing template)
     */
    record Matrix(List<List<MathNode>> rows, List<ColumnAlign> columnAligns,
                  List<Integer> columnRules, List<RowRule> rowRules,
                  int leftDelim, int rightDelim, MatrixKind kind) implements MathNode {
        public Matrix {
            if (rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException("Matrix must have at least one row");
            }
            // Deep-copy to immutable, and validate rectangularity against the aligns.
            List<List<MathNode>> copied = new java.util.ArrayList<>(rows.size());
            int cols = columnAligns.size();
            for (List<MathNode> row : rows) {
                if (row.size() != cols) {
                    throw new IllegalArgumentException(
                        "Matrix row has " + row.size() + " cells, expected " + cols);
                }
                copied.add(List.copyOf(row));
            }
            rows = List.copyOf(copied);
            columnAligns = List.copyOf(columnAligns);
            columnRules = List.copyOf(columnRules);
            rowRules = List.copyOf(rowRules);
            if (columnRules.size() != cols + 1) {
                throw new IllegalArgumentException("columnRules length must be columns+1");
            }
            if (rowRules.size() != rows.size() + 1) {
                throw new IllegalArgumentException("rowRules length must be rows+1");
            }
            if (kind == null) {
                throw new IllegalArgumentException("Matrix kind must not be null");
            }
        }

        /** Whether the grid carries an enclosing delimiter (⇒ Inner class). */
        public boolean hasDelimiters() {
            return leftDelim != Fenced.NULL_DELIMITER || rightDelim != Fenced.NULL_DELIMITER;
        }

        /** The number of columns in the (rectangular) grid. */
        public int columnCount() {
            return columnAligns.size();
        }
    }

    /**
     * The kind of a {@link Stack} — a base with material set above and/or below it,
     * the shared clean-room mechanism behind five LaTeX commands. Selects both the
     * implied math class of the result and whether a horizontally-stretched brace
     * is drawn between the base and its label.
     */
    enum StackKind {
        /**
         * {@code \\underbrace{base}_{label}} — a curly brace (U+23DF, stretched to the
         * base width via the OpenType MATH horizontal construction) under the base,
         * with the {@code _} label at script size below the brace. Implied class Ord.
         */
        UNDERBRACE,
        /**
         * {@code \overbrace{base}^{label}} — a curly brace (U+23DE, horizontally
         * stretched) over the base, with the {@code ^} label at script size above the
         * brace. Implied class Ord.
         */
        OVERBRACE,
        /**
         * {@code \stackrel{above}{base}} — {@code above} at script size over a
         * relation base (TeXbook: {@code \mathrel{\mathop{base}\limits^{above}}}). The
         * result has implied class Rel, regardless of the base's own class.
         */
        STACKREL,
        /**
         * {@code \overset{above}{base}} — like {@link #STACKREL} but the result keeps
         * the base's own math class.
         */
        OVERSET,
        /**
         * {@code \\underset{below}{base}} — {@code below} at script size under the base;
         * the result keeps the base's own math class.
         */
        UNDERSET
    }

    /**
     * A base with material stacked <em>above</em> and/or <em>below</em> it — the
     * shared layout mechanism behind {@code \\underbrace}/{@code \overbrace} (a
     * stretched brace plus a script-size label), {@code \stackrel}/{@code \overset}
     * (a script-size mark over the base), and {@code \\underset} (a mark under it).
     * Clean-room from Knuth's TeXbook: {@code \stackrel} and friends are a
     * {@code \mathop} carrying limit-style scripts, and {@code \\underbrace} is a
     * {@code \mathop} whose brace is an over/under-line-like decoration with the
     * label attached as a limit.
     *
     * <p>{@link #above} and {@link #below} are optional (nullable) — a bare
     * {@code \\underbrace{x}} carries neither until its {@code _} label is attached
     * during parsing (the brace kinds take their label as a limit script, exactly as
     * a {@link BigOperator} takes its limits). {@link #kind} selects the implied math
     * class (see {@link #impliedClass()}) and whether a brace is drawn.
     *
     * @param base  the nucleus the material stacks around (set at the current style)
     * @param above the mark stacked above, at script size, or {@code null}
     * @param below the mark stacked below, at script size, or {@code null}
     * @param kind  the stack flavour
     */
    record Stack(MathNode base, MathNode above, MathNode below, StackKind kind)
            implements MathNode {
        public Stack {
            if (base == null) {
                throw new IllegalArgumentException("Stack base must not be null");
            }
            if (kind == null) {
                throw new IllegalArgumentException("Stack kind must not be null");
            }
        }

        /** The mark stacked above the base, if present. */
        public Optional<MathNode> aboveMark() {
            return Optional.ofNullable(above);
        }

        /** The mark stacked below the base, if present. */
        public Optional<MathNode> belowMark() {
            return Optional.ofNullable(below);
        }

        /** Whether a horizontally-stretched brace is drawn (the brace kinds). */
        public boolean hasBrace() {
            return kind == StackKind.UNDERBRACE || kind == StackKind.OVERBRACE;
        }

        /**
         * Whether this stack takes its label as a following {@code ^}/{@code _} limit
         * script (the brace kinds), the way a large operator takes its limits. The
         * {@code \stackrel}/{@code \overset}/{@code \\underset} kinds instead read both
         * arguments eagerly at parse time, so they never take a trailing script.
         */
        public boolean takesLimitLabel() {
            return hasBrace();
        }

        /** With {@code above} replaced (used to attach a brace kind's {@code ^} label). */
        public Stack withAbove(MathNode newAbove) {
            return new Stack(base, newAbove, below, kind);
        }

        /** With {@code below} replaced (used to attach a brace kind's {@code _} label). */
        public Stack withBelow(MathNode newBelow) {
            return new Stack(base, above, newBelow, kind);
        }
    }
}
