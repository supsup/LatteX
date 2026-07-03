package com.lattex.parse;

/**
 * Thrown when {@link MathParser} cannot parse its input: an unbalanced brace, a
 * dangling script, an unknown command, a bad delimiter, and so on. The message
 * names the specific problem so malformed input fails loudly rather than
 * producing a silently wrong tree.
 *
 * <p>Extends {@link IllegalArgumentException} so existing callers that catch the
 * broader type keep working.
 */
public final class MathSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public MathSyntaxException(String message) {
        super(message);
    }
}
