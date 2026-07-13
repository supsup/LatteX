package com.lattex.parse;

/**
 * Thrown when {@link MathParser} cannot parse its input: an unbalanced brace, a
 * dangling script, an unknown command, a bad delimiter, and so on. The message
 * names the specific problem so malformed input fails loudly rather than
 * producing a silently wrong tree.
 *
 * <p>When the parser knows WHERE the problem is, the exception also carries the
 * source character {@link #offset()} and (once {@link MathParser#parse} attaches it)
 * the full {@link #source()} input, so {@link #caretString()} can point a {@code ^}
 * at the offending column — an author-facing error instead of a positionless message.
 *
 * <p>Extends {@link IllegalArgumentException} so existing callers that catch the
 * broader type keep working.
 */
public final class MathSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 2L;

    /** Sentinel offset meaning "no known position." */
    public static final int NO_OFFSET = -1;

    private final int offset;
    private String source; // the full input, attached by MathParser.parse; null if standalone
    // An unknown command / environment (a construct LatteX does not support), as opposed
    // to malformed syntax. Lets the Outcome mapping classify a typo as UNSUPPORTED_CONSTRUCT
    // instead of PARSE_ERROR so consumers can tell "you meant \frac" from "your braces are
    // unbalanced." Defaults false; set only via the unsupported(...) factory.
    private boolean unsupportedConstruct = false;

    public MathSyntaxException(String message) {
        this(message, NO_OFFSET);
    }

    public MathSyntaxException(String message, int offset) {
        super(message);
        this.offset = offset;
    }

    /**
     * Builds an exception flagged as an UNSUPPORTED construct (an unknown command or
     * environment name) rather than malformed syntax. Same message/offset channel as the
     * plain constructor; only {@link #isUnsupportedConstruct()} differs, which the
     * diagnostics layer reads to classify the {@link com.lattex.api.Outcome}.
     */
    static MathSyntaxException unsupported(String message, int offset) {
        MathSyntaxException e = new MathSyntaxException(message, offset);
        e.unsupportedConstruct = true;
        return e;
    }

    /**
     * Internal-failure containment form (L6.1, plan lattex-containment-diagnostics):
     * used by the render boundary to surface a layout/emit-stage failure through the
     * SAME typed channel consumers already catch, with the original failure preserved
     * as the cause. No source offset — the problem is not positional.
     */
    public MathSyntaxException(String message, Throwable cause) {
        super(message, cause);
        this.offset = NO_OFFSET;
    }

    /** The source character offset of the problem, or {@link #NO_OFFSET} if unknown. */
    public int offset() {
        return offset;
    }

    /** The full source input, or {@code null} if it was not attached. */
    public String source() {
        return source;
    }

    /**
     * {@code true} when this reports an unsupported construct (an unknown command or
     * environment), as opposed to malformed syntax. Read by the diagnostics layer to
     * classify the {@link com.lattex.api.Outcome} as {@code UNSUPPORTED_CONSTRUCT}.
     */
    public boolean isUnsupportedConstruct() {
        return unsupportedConstruct;
    }

    /**
     * Attaches the source input (idempotent — the first attachment wins, so an inner
     * frame's source is not overwritten by an outer re-throw). Package-private:
     * {@link MathParser#parse} calls it as the exception propagates out.
     */
    void attachSource(String src) {
        if (this.source == null) {
            this.source = src;
        }
    }

    /**
     * A multi-line rendering that points a caret at the problem:
     * <pre>
     *   \frac{a}{  x^} + 1
     *              ^
     *   Dangling '^' (expected a base and an exponent)
     * </pre>
     * Falls back to just {@link #getMessage()} when there is no attached source or the
     * offset is unknown/out of range (so it is always safe to display).
     */
    public String caretString() {
        if (source == null || offset < 0 || offset > source.length()) {
            return getMessage();
        }
        String oneLine = source.replace('\n', ' '); // keep columns aligned for the caret
        return oneLine + "\n" + " ".repeat(offset) + "^\n" + getMessage();
    }
}
