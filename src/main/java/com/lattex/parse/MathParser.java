package com.lattex.parse;

import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.SupSub;

/**
 * A deliberately tiny recursive-descent parser for the LatteX walking skeleton.
 *
 * <p>M0 grammar (enough for {@code x^2}):
 * <pre>
 *   expr    := atom ('^' atom)?
 *   atom    := singleChar | '{' singleChar '}'
 * </pre>
 * The token/lexer split, brace groups, and multi-atom runs are stubbed to the
 * minimum the skeleton needs; this seeds — but is not — the real S3 parser.
 */
public final class MathParser {

    private final String src;
    private int pos;

    private MathParser(String src) {
        this.src = src;
    }

    /** Parses a LaTeX math source string into a {@link MathNode}. */
    public static MathNode parse(String latex) {
        MathParser parser = new MathParser(latex);
        MathNode node = parser.expr();
        parser.skipSpaces();
        if (parser.pos != parser.src.length()) {
            throw new IllegalArgumentException(
                "Unexpected trailing input at index " + parser.pos + ": " + latex);
        }
        return node;
    }

    private MathNode expr() {
        MathNode base = atom();
        skipSpaces();
        if (peek() == '^') {
            pos++; // consume '^'
            MathNode sup = atom();
            return SupSub.superscript(base, sup);
        }
        return base;
    }

    private MathNode atom() {
        skipSpaces();
        char c = peek();
        if (c == '{') {
            pos++;
            MathNode inner = atom();
            skipSpaces();
            expect('}');
            return inner;
        }
        if (c == '\0' || c == '^' || c == '}') {
            throw new IllegalArgumentException("Expected an atom at index " + pos + ": " + src);
        }
        pos++;
        return new Atom(c);
    }

    private void skipSpaces() {
        while (pos < src.length() && src.charAt(pos) == ' ') {
            pos++;
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at index " + pos + ": " + src);
        }
        pos++;
    }
}
