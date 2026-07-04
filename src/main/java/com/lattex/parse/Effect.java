package com.lattex.parse;

import java.util.Locale;

/**
 * The small, closed vocabulary of {@code \lx} animation effects — the value half
 * of an {@code fx.*} option. Effects are parsed and validated at parse time but
 * are <em>not</em> emitted into the {@code <svg>}; they ride the trusted wrapping
 * container (see {@link com.lattex.api.LatteX#renderStyledHtml(String)}) as
 * {@code data-lx-fx-*} attributes. This enum simply pins the accepted names.
 */
public enum Effect {
    /** A brief scale-up "pop". */
    BOOM,
    /** A rhythmic scale pulse. */
    PULSE,
    /** A fade in/out. */
    FADE,
    /** A soft glow. */
    GLOW,
    /**
     * A confluence of lightning: jagged bolts arc in from the left and right
     * viewport edges and converge on the element. Unlike the others this is NOT a
     * CSS keyframe on the element — the page-side runtime special-cases it and
     * draws a full-viewport body overlay (see the fx runtime); the containment
     * contract is unchanged (nothing reaches the inner {@code <svg>}).
     */
    LIGHTNING,
    /** Explicitly no effect. */
    NONE;

    /**
     * Parse an effect name ({@code boom|pulse|fade|glow|lightning|none},
     * case-insensitive).
     *
     * @param raw the effect name (surrounding whitespace is trimmed)
     * @return the effect
     * @throws MathSyntaxException if {@code raw} is null or not a known effect
     */
    public static Effect parse(String raw) {
        if (raw == null) {
            throw new MathSyntaxException("fx effect must not be null");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "boom" -> BOOM;
            case "pulse" -> PULSE;
            case "fade" -> FADE;
            case "glow" -> GLOW;
            case "lightning" -> LIGHTNING;
            case "none" -> NONE;
            default -> throw new MathSyntaxException(
                "invalid fx effect: \"" + raw
                    + "\" (expected boom|pulse|fade|glow|lightning|none)");
        };
    }

    /** The lowercase token form of this effect (the value stamped on the container). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
