package com.lattex.parse;

import com.lattex.api.Color;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The validated animation options from an {@code \lx} macro: a per-{@link Trigger}
 * {@link Effect} map, an optional duration, and an optional glow colour. Parsed and
 * validated at parse time but <strong>never emitted into the {@code <svg>}</strong>
 * — they ride the trusted wrapping container as {@code data-lx-fx-*} attributes (see
 * {@link com.lattex.api.LatteX#renderStyledHtml(String)}), so the emitter's SVG
 * alphabet is unchanged.
 *
 * @param effects the trigger → effect map (defensively copied; may be empty)
 * @param duration the effect duration matching {@code ^\d{1,5}ms$}, or {@code null}
 * @param glowColor the validated {@code fx.glow-color} for the glow/lightning halo,
 *     or {@code null} to inherit {@code currentColor} (today's default behaviour)
 */
public record EffectSpec(Map<Trigger, Effect> effects, String duration, Color glowColor) {

    /** {@code fx.duration} grammar: 1–5 digits followed by the {@code ms} unit. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d{1,5}ms$");

    public EffectSpec {
        effects = Map.copyOf(effects);
        if (duration != null && !DURATION_PATTERN.matcher(duration).matches()) {
            throw new MathSyntaxException(
                "invalid fx.duration: \"" + duration + "\" (expected like 250ms)");
        }
    }

    /** The empty spec: no effects, no duration, no glow colour. */
    public static EffectSpec none() {
        return new EffectSpec(Map.of(), null, null);
    }

    /** The effect bound to a trigger, if any. */
    public Optional<Effect> effect(Trigger trigger) {
        return Optional.ofNullable(effects.get(trigger));
    }

    /** The duration string, if set. */
    public Optional<String> durationValue() {
        return Optional.ofNullable(duration);
    }

    /** The glow colour, if set (else the halo falls back to {@code currentColor}). */
    public Optional<Color> glowColorValue() {
        return Optional.ofNullable(glowColor);
    }

    /** Whether any effect, duration, or glow colour was specified. */
    public boolean isEmpty() {
        return effects.isEmpty() && duration == null && glowColor == null;
    }
}
