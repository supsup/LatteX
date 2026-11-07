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
 * @param substituteTo the {@code fx.substitute-to} target: a LITERAL integer matching
 *     {@code ^-?\d{1,6}$}, or {@code null}. Slice 1 is integers only — the target is
 *     PRE-RENDERED by LatteX, so it must be something the parser can build a tree from
 *     without evaluating anything symbolic.
 * @param substituteVar the {@code fx.substitute-var} variable to replace: exactly one
 *     letter, or {@code null} to auto-detect the single distinct letter in the body
 * @param bolts explicit {@code fx.bolts} in 1..8, or {@code null} for runtime default 2
 * @param intensity explicit {@code fx.intensity} in 0..300 percent, or {@code null}
 *     for runtime default 100; zero disables lightning/storm
 */
public record EffectSpec(Map<Trigger, Effect> effects, String duration, Color glowColor,
                         String substituteTo, String substituteVar, Integer bolts, Integer intensity) {

    /** Preserves the original public constructor and its omitted-knob state. */
    public EffectSpec(Map<Trigger, Effect> effects, String duration, Color glowColor,
                      String substituteTo, String substituteVar) {
        this(effects, duration, glowColor, substituteTo, substituteVar, null, null);
    }

    /** {@code fx.duration} grammar: 1–5 digits followed by the {@code ms} unit. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d{1,5}ms$");

    /**
     * {@code fx.substitute-to} grammar: an optionally-negative literal integer of at most
     * six digits. Bounded on purpose — the target is spliced into the tree as digit atoms
     * and laid out, so its magnitude is a layout-cost input, the same reasoning that bounds
     * {@link SumExpansion#MAX_TERMS}.
     */
    private static final Pattern SUBSTITUTE_TO_PATTERN = Pattern.compile("^-?\\d{1,6}$");

    public EffectSpec {
        effects = Map.copyOf(effects);
        validateIntegerOption("fx.bolts", bolts, 1, 8);
        validateIntegerOption("fx.intensity", intensity, 0, 300);
        if (duration != null && !DURATION_PATTERN.matcher(duration).matches()) {
            throw new MathSyntaxException(
                "invalid fx.duration: \"" + duration + "\" (expected like 250ms)");
        }
        if (substituteTo != null && !SUBSTITUTE_TO_PATTERN.matcher(substituteTo).matches()) {
            throw new MathSyntaxException(
                "invalid fx.substitute-to: \"" + substituteTo
                    + "\" (expected a literal integer of at most 6 digits, like 3 or -12)");
        }
        if (substituteVar != null
                && (substituteVar.codePointCount(0, substituteVar.length()) != 1
                    || !Character.isLetter(substituteVar.codePointAt(0)))) {
            throw new MathSyntaxException(
                "invalid fx.substitute-var: \"" + substituteVar
                    + "\" (expected exactly one letter, like x)");
        }
    }

    /** Strict ASCII decimal, bounded during accumulation so overflow never escapes. */
    static int parseIntegerOption(String key, String value, int min, int max) {
        if (value.isEmpty()) {
            throw invalidIntegerOption(key, value, min, max);
        }
        int parsed = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw invalidIntegerOption(key, value, min, max);
            }
            int digit = c - '0';
            if (parsed > max / 10 || (parsed == max / 10 && digit > max % 10)) {
                throw invalidIntegerOption(key, value, min, max);
            }
            parsed = parsed * 10 + digit;
        }
        if (parsed < min) {
            throw invalidIntegerOption(key, value, min, max);
        }
        return parsed;
    }

    private static void validateIntegerOption(String key, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw invalidIntegerOption(key, value.toString(), min, max);
        }
    }

    private static MathSyntaxException invalidIntegerOption(String key, String value, int min, int max) {
        return new MathSyntaxException("invalid " + key + ": \"" + value
            + "\" (expected integral ASCII decimal " + min + ".." + max + ")");
    }

    /** The empty spec: all effect options, including numeric knobs, omitted. */
    public static EffectSpec none() {
        return new EffectSpec(Map.of(), null, null, null, null);
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

    /** The {@code fx.substitute-to} target, if set. */
    public Optional<String> substituteToValue() {
        return Optional.ofNullable(substituteTo);
    }

    /**
     * The {@code fx.substitute-var} variable's code point, if the author named one
     * explicitly (else the variable is auto-detected from the body).
     */
    public Optional<Integer> substituteVarCodePoint() {
        return Optional.ofNullable(substituteVar).map(v -> v.codePointAt(0));
    }

    /** Whether any effect option, including an explicit numeric knob, was specified. */
    public boolean isEmpty() {
        return effects.isEmpty() && duration == null && glowColor == null
            && substituteTo == null && substituteVar == null && bolts == null && intensity == null;
    }
}
