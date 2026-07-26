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
 * @param macros    preset user macros (L8): name (ASCII letters, no backslash) to
 *                  replacement body; arity inferred from the highest {@code #k} in
 *                  the body. Applied before parsing on every render — the
 *                  server-side per-tenant notation-pack seam. Never null; empty
 *                  map = the byte-identical no-macro fast path.
 * @param interactiveExpansion the HOST gate for the opt-in numeric-expansion pass
 *                  (fx.*=unfold). Default {@code false}: LatteX is a pure typesetter
 *                  and {@link com.lattex.parse.SumExpansion} never runs — even an
 *                  {@code fx.*=unfold} directive degrades INERT (the sum typesets
 *                  normally; no payload is stamped). {@code true} arms the pass, which
 *                  still fires ONLY on an equation that opted in via the directive.
 * @param fluid     the HOST opt-in for scale-to-fit display math. Default {@code false}:
 *                  output is byte-identical to before the flag existed. {@code true}
 *                  adds ONE sizing {@code style} rule to the outer display {@code <svg>}
 *                  ({@code width:100%;max-width:<natural>px;height:auto}) so the
 *                  equation downscales in a narrow container but never upscales past
 *                  its natural width (the preserved viewBox keeps the aspect ratio).
 *                  Geometry/layout are untouched; inline results
 *                  ({@link LatteX#renderInlineResult}) and fragments
 *                  ({@link LatteX#renderFragment}) stay fixed-size regardless.
 */
public record RenderOptions(double scale, Color color, MathStyle mathStyle,
                            java.util.Map<String, String> macros,
                            boolean interactiveExpansion, boolean fluid) {

    /** Lower clamp for {@link #scale()} — below this glyphs collapse to nothing. */
    public static final double MIN_SCALE = 0.1;
    /** Upper clamp for {@link #scale()} — a generous ceiling for a single formula. */
    public static final double MAX_SCALE = 20.0;

    // Bounded decimal: 1–2 integer digits, optional 1–2 fractional digits.
    private static final Pattern SCALE_PATTERN = Pattern.compile("^\\d{1,2}(\\.\\d{1,2})?$");

    public RenderOptions {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(mathStyle, "mathStyle");
        Objects.requireNonNull(macros, "macros");
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException(
                "scale must be a finite value in [" + MIN_SCALE + ", " + MAX_SCALE + "]; got: " + scale);
        }
        macros = java.util.Map.copyOf(macros); // defensive + null-key/value refusing
    }

    /** Three-arg compatibility constructor: no preset macros, expansion off, fixed-size. */
    public RenderOptions(double scale, Color color, MathStyle mathStyle) {
        this(scale, color, mathStyle, java.util.Map.of(), false, false);
    }

    /** Four-arg compatibility constructor: preset macros, interactive expansion off, fixed-size. */
    public RenderOptions(double scale, Color color, MathStyle mathStyle,
                         java.util.Map<String, String> macros) {
        this(scale, color, mathStyle, macros, false, false);
    }

    /** Five-arg compatibility constructor: pre-{@code fluid} shape, fixed-size sizing. */
    public RenderOptions(double scale, Color color, MathStyle mathStyle,
                         java.util.Map<String, String> macros, boolean interactiveExpansion) {
        this(scale, color, mathStyle, macros, interactiveExpansion, false);
    }

    /** The default options: {@code scale=1.0}, {@link Color#CURRENT}, display style, expansion off, fixed-size. */
    public static RenderOptions defaults() {
        return new RenderOptions(1.0, Color.CURRENT, MathStyle.DISPLAY, java.util.Map.of(), false, false);
    }

    /** A copy with a different scale (must be in {@code [MIN_SCALE, MAX_SCALE]}). */
    public RenderOptions withScale(double newScale) {
        return new RenderOptions(newScale, color, mathStyle, macros, interactiveExpansion, fluid);
    }

    /** A copy with a different color. */
    public RenderOptions withColor(Color newColor) {
        return new RenderOptions(scale, newColor, mathStyle, macros, interactiveExpansion, fluid);
    }

    /** A copy with a different math style. */
    public RenderOptions withMathStyle(MathStyle newStyle) {
        return new RenderOptions(scale, color, newStyle, macros, interactiveExpansion, fluid);
    }

    /**
     * A copy with preset user macros (L8). The map is defensively copied; parse
     * rejects built-in names and non-letter names at render time (the same
     * additive-only rule as inline definitions).
     */
    public RenderOptions withMacros(java.util.Map<String, String> newMacros) {
        return new RenderOptions(scale, color, mathStyle, newMacros, interactiveExpansion, fluid);
    }

    /**
     * A copy with the host's opt-in numeric-expansion pass enabled or disabled
     * (default disabled). The HOST gate for {@code fx.*=unfold}: with it {@code false}
     * the {@link com.lattex.parse.SumExpansion} pass never runs and an
     * {@code fx.*=unfold} directive degrades inert; with it {@code true} the pass fires
     * ONLY on an equation that authored the directive. A plain page pays zero cost.
     */
    public RenderOptions withInteractiveExpansion(boolean enabled) {
        return new RenderOptions(scale, color, mathStyle, macros, enabled, fluid);
    }

    /**
     * A copy with scale-to-fit display math enabled or disabled (default disabled).
     * The HOST opt-in for responsive sizing: with it {@code true},
     * {@link LatteX#render(String, RenderOptions)} and
     * {@link LatteX#renderStyledHtml(String, RenderOptions)} stamp ONE sizing
     * {@code style} rule ({@code width:100%;max-width:<natural>px;height:auto}) on the
     * outer display {@code <svg>} so the equation shrinks to fit a narrow container and
     * never upscales past its natural width. The viewBox — and every glyph inside — is
     * byte-identical to the fixed-size render; this is presentation-only sizing, not a
     * re-layout. Inline math ({@link LatteX#renderInlineResult}) and embeddable
     * fragments ({@link LatteX#renderFragment}) never go fluid — their baselines stay
     * fixed-size regardless of this flag.
     */
    public RenderOptions withFluid(boolean enabled) {
        return new RenderOptions(scale, color, mathStyle, macros, interactiveExpansion, enabled);
    }

    /**
     * A copy in <em>inline</em> (text) math style — smaller fractions and scripts, big-operator
     * limits set to the side rather than stacked. This is the right style for math embedded in a
     * line of running prose ({@code $…$}). An api-only selector so a caller need not name the
     * (non-exported) style type.
     */
    public RenderOptions inline() {
        return withMathStyle(MathStyle.TEXT);
    }

    /**
     * A copy in <em>display</em> math style (the default) — full-size fractions and stacked
     * big-operator limits, for a standalone displayed equation ({@code $$…$$} / {@code \[…\]}).
     */
    public RenderOptions display() {
        return withMathStyle(MathStyle.DISPLAY);
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
