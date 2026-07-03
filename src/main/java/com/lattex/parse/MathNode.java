package com.lattex.parse;

/**
 * The math tree ADT — a {@code sealed interface} closed over its node kinds so
 * layout traversals can pattern-match exhaustively (the compiler proves every
 * case is handled).
 *
 * <p>For the M0 walking skeleton only two shapes exist ({@link Atom} and
 * {@link SupSub}); the ADT is intentionally kept minimal but extensible, to
 * seed the real S3 parser.
 */
public sealed interface MathNode {

    /** A single character run on the baseline (a variable or a digit). */
    record Atom(int codePoint) implements MathNode {
        public char asChar() {
            return (char) codePoint;
        }
    }

    /**
     * A base with an optional superscript and/or subscript. For M0 only the
     * superscript is populated; {@code sub} is carried for extensibility and
     * may be {@code null}.
     */
    record SupSub(MathNode base, MathNode sup, MathNode sub) implements MathNode {
        public static SupSub superscript(MathNode base, MathNode sup) {
            return new SupSub(base, sup, null);
        }
    }
}
