package com.lattex.parse;

import com.lattex.api.LatteXException;

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
 * <p>Extends the exported {@link LatteXException}, which itself extends
 * {@link IllegalArgumentException} — so existing callers that catch either of those
 * broader types keep working, and a MODULAR consumer (which cannot name this
 * non-exported {@code com.lattex.parse} type at all) can still catch every render
 * failure by naming {@code com.lattex.api.LatteXException}. The hierarchy is purely
 * additive at the top: {@code IllegalArgumentException → LatteXException →
 * MathSyntaxException}; this class is unmoved and unchanged in identity.
 */
public final class MathSyntaxException extends LatteXException {

    /**
     * Bumped 2 → 3 because inserting {@link LatteXException} into the superclass chain is,
     * per the Java Object Serialization Specification, an INCOMPATIBLE change: a class's
     * position in the hierarchy is part of its serialized form. Nothing in LatteX serializes
     * exceptions — they are thrown across an in-process API — so this is a theoretical
     * concern, but leaving the UID at 2 would let a stream written by 0.11.0 fail against a
     * hierarchy mismatch rather than against the version check. Bumping it makes any such
     * attempt fail as a named {@code InvalidClassException} that says exactly what changed.
     */
    private static final long serialVersionUID = 3L;

    /** Sentinel offset meaning "no known position." */
    public static final int NO_OFFSET = -1;

    /**
     * Internal typed unsupported-construct reason; never inferred from exception
     * text and deliberately not added to LatteX's public API.
     */
    enum UnsupportedKind {
        NONE,
        UNKNOWN_COMMAND,
        UNKNOWN_ENVIRONMENT
    }

    private final int offset;
    private String source; // the full input, attached by MathParser.parse; null if standalone
    // An unknown command / environment (a construct LatteX does not support), as opposed
    // to malformed syntax. Typed so registry/macro callers never parse message text.
    private UnsupportedKind unsupportedKind = UnsupportedKind.NONE;

    // L10 (plan lattex-hostile-input-hardening): marks a RESOURCE-CAP trip (output
    // bytes / layout boxes) so renderWithDiagnostics classifies OUTPUT_CAP_EXCEEDED
    // instead of RENDER_BUG. A typed flag, not a message-prefix — the class is final,
    // so the marker lives here rather than in a subtype.
    private boolean capExceeded;

    public MathSyntaxException(String message) {
        this(message, NO_OFFSET);
    }

    public MathSyntaxException(String message, int offset) {
        super(message);
        this.offset = offset;
    }

    /**
     * Builds an exception typed as an unknown command while preserving the established
     * public message/offset channel.
     */
    static MathSyntaxException unknownCommand(String message, int offset) {
        return withUnsupportedKind(message, offset, UnsupportedKind.UNKNOWN_COMMAND);
    }

    /** Builds an exception typed as an unknown environment. */
    static MathSyntaxException unknownEnvironment(String message, int offset) {
        return withUnsupportedKind(message, offset, UnsupportedKind.UNKNOWN_ENVIRONMENT);
    }

    /**
     * Rebuilds a contextual error without dropping its typed unsupported reason.
     * {@link UnsupportedKind#NONE} deliberately returns an ordinary syntax error.
     */
    static MathSyntaxException withUnsupportedKind(
            String message, int offset, UnsupportedKind unsupportedKind) {
        MathSyntaxException e = new MathSyntaxException(message, offset);
        e.unsupportedKind = unsupportedKind == null ? UnsupportedKind.NONE : unsupportedKind;
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

    /** L10 factory: a resource-cap trip (classified {@code OUTPUT_CAP_EXCEEDED}). */
    public static MathSyntaxException capExceeded(String message) {
        MathSyntaxException e = new MathSyntaxException(message);
        e.capExceeded = true;
        return e;
    }

    /** Whether this failure is a resource-cap trip (output bytes / layout boxes). */
    public boolean isCapExceeded() {
        return capExceeded;
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
        return unsupportedKind() != UnsupportedKind.NONE;
    }

    /** Internal typed unsupported reason, or {@link UnsupportedKind#NONE}. */
    UnsupportedKind unsupportedKind() {
        // Null is possible only when an exception serialized before this typed
        // field existed is deserialized with the retained serialVersionUID.
        return unsupportedKind == null ? UnsupportedKind.NONE : unsupportedKind;
    }

    /** Internal discriminator for an unknown command. */
    boolean isUnknownCommand() {
        return unsupportedKind() == UnsupportedKind.UNKNOWN_COMMAND;
    }

    /** Internal discriminator for an unknown environment. */
    boolean isUnknownEnvironment() {
        return unsupportedKind() == UnsupportedKind.UNKNOWN_ENVIRONMENT;
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
