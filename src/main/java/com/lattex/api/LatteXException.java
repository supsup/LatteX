package com.lattex.api;

/**
 * The exported supertype for every exception LatteX's public render methods throw
 * (plan 0c4f6015, Marlow audit LTX-08).
 *
 * <p><strong>Why this type exists.</strong> The module exports only {@code com.lattex.api}.
 * The concrete exception the render methods throw — {@link com.lattex.parse.MathSyntaxException}
 * — lives in the non-exported {@code com.lattex.parse} package, so a modular consumer (one with
 * its own {@code module-info} that {@code requires com.lattex}) could not name it to catch it:
 * {@code package com.lattex.parse is not visible}. This class is the visible, exported handle a
 * modular consumer catches instead; {@code MathSyntaxException} extends it, so
 * {@code catch (LatteXException)} catches every exception the public render methods can throw
 * (a genuine parse error, a contained internal render failure, a resource-cap trip) using only
 * exported packages.
 *
 * <p><strong>A supertype, not a move — for back-compat.</strong> The concrete
 * {@code com.lattex.parse.MathSyntaxException} type is UNCHANGED and still thrown, so existing
 * non-modular callers that catch it by its fully-qualified name keep compiling and running
 * byte-for-byte as before. And because this class extends {@link IllegalArgumentException} (as
 * {@code MathSyntaxException} always has), callers that catch the broader
 * {@code IllegalArgumentException} are also unaffected. The fix is purely additive at the top of
 * the hierarchy: {@code IllegalArgumentException → LatteXException → MathSyntaxException}.
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
