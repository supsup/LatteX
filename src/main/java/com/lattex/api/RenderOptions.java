package com.lattex.api;

import com.lattex.layout.MathStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Typed, validated styling options for {@link LatteX#render(String, RenderOptions)}.
 * Three orthogonal knobs, each with a sane default and a string parser used by the
 * future markdown/CLI layer (Layer-2):
 *
 * <ul>
 *   <li>{@link #scale()} — output size multiplier (default {@code 1.0}). Folded
 *       into the effective font size, so geometry scales proportionally (crisp
 *       vector output, not a CSS zoom).</li>
 *   <li>{@link #color()} — a validated {@link Color} (default {@link Color#CURRENT}
 *       so rendered math inherits the surrounding text color and adapts to dark
 *       mode natively; pass a {@link Color.Hex} for a fixed fill).</li>
 *   <li>{@link #mathStyle()} — the top-level TeX {@link MathStyle} (default
 *       {@link MathStyle#DISPLAY}).</li>
 * </ul>
 *
 * <p>All string validation lives here (and in {@link Color}) — the typed boundary.
 * Downstream code consumes the typed fields only and never re-parses a raw string.
 * The record is immutable; derive variants with the {@code with*} methods.
 *
 * @param scale     output size multiplier, in {@code [MIN_SCALE, MAX_SCALE]}
 * @param color     the validated fill color
 * @param mathStyle the top-level math style
 */
public record RenderOptions(double scale, Color color, MathStyle mathStyle) {

    /** Lower clamp for {@link #scale()} — below this glyphs collapse to nothing. */
    public static final double MIN_SCALE = 0.1;
    /** Upper clamp for {@link #scale()} — a generous ceiling for a single formula. */
    public static final double MAX_SCALE = 20.0;

    // Bounded decimal: 1–2 integer digits, optional 1–2 fractional digits.
    private static final Pattern SCALE_PATTERN = Pattern.compile("^\\d{1,2}(\\.\\d{1,2})?$");

    public RenderOptions {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(mathStyle, "mathStyle");
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException(
                "scale must be a finite value in [" + MIN_SCALE + ", " + MAX_SCALE + "]; got: " + scale);
        }
    }

    /** The default options: {@code scale=1.0}, {@link Color#CURRENT}, display style. */
    public static RenderOptions defaults() {
        return new RenderOptions(1.0, Color.CURRENT, MathStyle.DISPLAY);
    }

    /** A copy with a different scale (must be in {@code [MIN_SCALE, MAX_SCALE]}). */
    public RenderOptions withScale(double newScale) {
        return new RenderOptions(newScale, color, mathStyle);
    }

    /** A copy with a different color. */
    public RenderOptions withColor(Color newColor) {
        return new RenderOptions(scale, newColor, mathStyle);
    }

    /** A copy with a different math style. */
    public RenderOptions withMathStyle(MathStyle newStyle) {
        return new RenderOptions(scale, color, newStyle);
    }

    // ---- string parsers (the typed boundary for the markdown/CLI layer) ----

    /**
     * Parse a scale string into a bounded multiplier. Accepts a size bucket
     * ({@code sm}=0.8, {@code md}=1.0, {@code lg}=1.4, case-insensitive) or a
     * bounded decimal ({@code ^\d{1,2}(\.\d{1,2})?$}, clamped to
     * {@code [MIN_SCALE, MAX_SCALE]}). Anything else throws.
     *
     * @param raw the scale string (surrounding whitespace is trimmed)
     * @return the multiplier
     * @throws IllegalArgumentException if {@code raw} is null or unrecognized
     */
    public static double parseScale(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("scale must not be null");
        }
        String s = raw.trim();
        switch (s.toLowerCase(Locale.ROOT)) {
            case "sm":
                return 0.8;
            case "md":
                return 1.0;
            case "lg":
                return 1.4;
            default:
                if (!SCALE_PATTERN.matcher(s).matches()) {
                    throw new IllegalArgumentException(
                        "invalid scale: \"" + raw + "\" (expected sm|md|lg or a number like 1.4)");
                }
                double v = Double.parseDouble(s);
                return Math.min(MAX_SCALE, Math.max(MIN_SCALE, v));
        }
    }

    /**
     * Parse a color string into a validated {@link Color}. Delegates to
     * {@link Color#parse(String)}.
     *
     * @param raw the color string
     * @return the validated color
     * @throws IllegalArgumentException if unrecognized
     */
    public static Color parseColor(String raw) {
        return Color.parse(raw);
    }

    /**
     * Parse a math-style name ({@code display|text|script|scriptscript},
     * case-insensitive) into a {@link MathStyle}. Anything else throws.
     *
     * @param raw the style name (surrounding whitespace is trimmed)
     * @return the math style
     * @throws IllegalArgumentException if {@code raw} is null or unrecognized
     */
    public static MathStyle parseMathStyle(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("mathStyle must not be null");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "display" -> MathStyle.DISPLAY;
            case "text" -> MathStyle.TEXT;
            case "script" -> MathStyle.SCRIPT;
            case "scriptscript" -> MathStyle.SCRIPT_SCRIPT;
            default -> throw new IllegalArgumentException(
                "invalid mathStyle: \"" + raw + "\" (expected display|text|script|scriptscript)");
        };
    }
}
