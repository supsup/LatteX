package com.lattex.api;

/**
 * The exported supertype for every exception LatteX's public render methods throw.
 *
 * <p><strong>Why this type exists.</strong> The {@code com.lattex} module exports only
 * {@code com.lattex.api}. The concrete exception the render methods throw —
 * {@link com.lattex.parse.MathSyntaxException} — lives in the non-exported
 * {@code com.lattex.parse} package, so a modular consumer (one with its own
 * {@code module-info} that {@code requires com.lattex}) could not name it to catch it:
 * {@code package com.lattex.parse is not visible}. Its only fallback was
 * {@link IllegalArgumentException}, which the render methods ALSO throw for a bad
 * parameter (a non-finite {@code fontSizePx}), so a modular consumer could not tell
 * "your LaTeX is malformed" from "you passed a bad argument". This class is the
 * visible, exported handle a modular consumer catches instead:
 * {@code catch (LatteXException)} catches every render failure that comes from the
 * LaTeX itself (a genuine parse error, a contained internal render failure, a
 * resource-cap trip) using only exported packages — and does NOT catch the plain
 * bad-parameter {@code IllegalArgumentException}.
 *
 * <p><strong>A supertype, not a move — for back-compat.</strong> The concrete
 * {@code com.lattex.parse.MathSyntaxException} type is unchanged in identity: same
 * name, same package, still {@code public final}, still thrown from exactly where it
 * was thrown before. Existing non-modular callers that catch it by its fully-qualified
 * name keep compiling and behaving identically. And because this class extends
 * {@link IllegalArgumentException} (as {@code MathSyntaxException} always has),
 * callers that catch the broader {@code IllegalArgumentException} are also unaffected.
 * The fix is purely additive at the TOP of the hierarchy:
 * {@code IllegalArgumentException → LatteXException → MathSyntaxException}.
 */
public class LatteXException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Constructs an exception with the given detail message. */
    public LatteXException(String message) {
        super(message);
    }

    /** Constructs an exception with the given detail message and underlying cause. */
    public LatteXException(String message, Throwable cause) {
        super(message, cause);
    }
}
