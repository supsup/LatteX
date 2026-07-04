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
    /**
     * Night lightning. Like {@link #LIGHTNING} the page-side runtime special-cases
     * it (never a keyframe on the element), but first it drops the whole scene to
     * near-black behind a spotlit target: a full-viewport body overlay darkens the
     * page while the target stays lit, converging bolts strike the centre, and each
     * strike briefly FLASHES the dark backdrop bright (like lightning at night)
     * before the page restores. The containment contract is unchanged — the darken
     * backdrop, bolt canvas, and flash are separate body elements; nothing reaches
     * the inner {@code <svg>}.
     */
    STORM,
    /**
     * The equation writes itself: each glyph is drawn stroke-by-stroke, staggered
     * left-to-right, as if by an invisible pen. Like {@link #LIGHTNING}/{@link #STORM}
     * the page-side runtime special-cases it (NOT a CSS keyframe on the element): it
     * reads the container's inner {@code <svg>} {@code <path>} glyphs and animates their
     * {@code stroke-dashoffset} to "ink" them in. The containment contract is unchanged —
     * the runtime only toggles presentation attributes on the existing paths; it adds no
     * new element to the inner {@code <svg>}.
     */
    HANDSCRIBE,
    /** Explicitly no effect. */
    NONE;

    /**
     * Parse an effect name ({@code boom|pulse|fade|glow|lightning|storm|none},
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
            case "storm" -> STORM;
            case "handscribe" -> HANDSCRIBE;
            case "none" -> NONE;
            default -> throw new MathSyntaxException(
                "invalid fx effect: \"" + raw
                    + "\" (expected boom|pulse|fade|glow|lightning|storm|handscribe|none)");
        };
    }

    /** The lowercase token form of this effect (the value stamped on the container). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
