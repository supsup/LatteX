package com.lattex.parse;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The validated animation options from an {@code \lx} macro: a per-{@link Trigger}
 * {@link Effect} map plus an optional duration. Parsed and validated at parse time
 * but <strong>never emitted into the {@code <svg>}</strong> — they ride the trusted
 * wrapping container as {@code data-lx-fx-*} attributes (see
 * {@link com.lattex.api.LatteX#renderStyledHtml(String)}), so the emitter's SVG
 * alphabet is unchanged.
 *
 * @param effects the trigger → effect map (defensively copied; may be empty)
 * @param duration the effect duration matching {@code ^\d{1,5}ms$}, or {@code null}
 */
public record EffectSpec(Map<Trigger, Effect> effects, String duration) {

    /** {@code fx.duration} grammar: 1–5 digits followed by the {@code ms} unit. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d{1,5}ms$");

    public EffectSpec {
        effects = Map.copyOf(effects);
        if (duration != null && !DURATION_PATTERN.matcher(duration).matches()) {
            throw new MathSyntaxException(
                "invalid fx.duration: \"" + duration + "\" (expected like 250ms)");
        }
    }

    /** The empty spec: no effects, no duration. */
    public static EffectSpec none() {
        return new EffectSpec(Map.of(), null);
    }

    /** The effect bound to a trigger, if any. */
    public Optional<Effect> effect(Trigger trigger) {
        return Optional.ofNullable(effects.get(trigger));
    }

    /** The duration string, if set. */
    public Optional<String> durationValue() {
        return Optional.ofNullable(duration);
    }

    /** Whether any effect or duration was specified. */
    public boolean isEmpty() {
        return effects.isEmpty() && duration == null;
    }
}
