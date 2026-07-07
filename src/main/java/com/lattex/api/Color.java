package com.lattex.api;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated fill color for rendered math — the single typed boundary at which
 * an untrusted string becomes a known-good SVG {@code fill} value. Two shapes:
 *
 * <ul>
 *   <li>{@link CurrentColor} — the {@code currentColor} keyword, so the math
 *       inherits the surrounding text color (the dark-mode-friendly option).</li>
 *   <li>{@link Hex} — a canonical {@code #rrggbb} literal (lowercase, 6 digits).</li>
 * </ul>
 *
 * <p>Both {@link #svgValue()} results ({@code currentColor} or a {@code #rrggbb}
 * literal) are already inside the emitter's allow-listed {@code fill} alphabet
 * (see {@code S8LeftContainmentTest}), so no new sanitizer surface is introduced —
 * a {@code color} option sets the fill VALUE, never a new element/attribute. An
 * unvalidated string is NEVER stored or emitted: construction of a {@link Hex}
 * enforces the canonical form, and {@link #parse(String)} is the only way in from
 * a raw string.
 */
public sealed interface Color permits Color.CurrentColor, Color.Hex {

    /** Anchored: exactly {@code #} followed by 6 or 3 hex digits, nothing else. */
    Pattern HEX_PATTERN = Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$");

    /**
     * Named colors accepted by {@code \color}/{@code \textcolor} — the xcolor base
     * set plus common CSS names, each resolved to a canonical {@link Hex}. Matched
     * case-insensitively. A name is just a shorthand for a known-good hex, so it
     * introduces no new fill value beyond what {@code #rrggbb} already allows.
     */
    Map<String, Hex> NAMED = Map.ofEntries(
        Map.entry("red", new Hex("#ff0000")), Map.entry("green", new Hex("#00ff00")),
        Map.entry("blue", new Hex("#0000ff")), Map.entry("cyan", new Hex("#00ffff")),
        Map.entry("magenta", new Hex("#ff00ff")), Map.entry("yellow", new Hex("#ffff00")),
        Map.entry("black", new Hex("#000000")), Map.entry("white", new Hex("#ffffff")),
        Map.entry("gray", new Hex("#808080")), Map.entry("grey", new Hex("#808080")),
        Map.entry("darkgray", new Hex("#a9a9a9")), Map.entry("lightgray", new Hex("#d3d3d3")),
        Map.entry("orange", new Hex("#ffa500")), Map.entry("purple", new Hex("#800080")),
        Map.entry("brown", new Hex("#a52a2a")), Map.entry("pink", new Hex("#ffc0cb")),
        Map.entry("olive", new Hex("#808000")), Map.entry("teal", new Hex("#008080")),
        Map.entry("violet", new Hex("#ee82ee")), Map.entry("lime", new Hex("#00ff00")),
        Map.entry("navy", new Hex("#000080")), Map.entry("maroon", new Hex("#800000")));

    /** The shared {@code currentColor} sentinel. */
    CurrentColor CURRENT = new CurrentColor();

    /**
     * The default fill: opaque black ({@code #000000}). Chosen so the no-options
     * {@code render(latex)} path stays byte-identical to the legacy hardcoded fill;
     * callers wanting dark-mode inheritance opt into {@link #CURRENT}.
     */
    Hex BLACK = new Hex("#000000");

    /** The literal to place in an SVG {@code fill} attribute. */
    String svgValue();

    /**
     * The {@code currentColor} keyword: math inherits the surrounding text color.
     * A component-less record so all instances compare equal; prefer {@link #CURRENT}.
     */
    record CurrentColor() implements Color {
        @Override
        public String svgValue() {
            return "currentColor";
        }
    }

    /**
     * A canonical {@code #rrggbb} color. The stored value is always exactly seven
     * characters: {@code '#'} plus six lowercase hex digits — enforced here, so a
     * {@code Hex} can never hold anything else.
     *
     * @param canonical the canonical {@code #rrggbb} literal
     */
    record Hex(String canonical) implements Color {
        /** Canonical form only: {@code #} + exactly 6 lowercase hex digits. */
        private static final Pattern CANONICAL = Pattern.compile("^#[0-9a-f]{6}$");

        public Hex {
            Objects.requireNonNull(canonical, "canonical");
            if (!CANONICAL.matcher(canonical).matches()) {
                throw new IllegalArgumentException(
                    "Hex color must be canonical #rrggbb (lowercase); got: " + canonical);
            }
        }

        @Override
        public String svgValue() {
            return canonical;
        }
    }

    /**
     * Parse a color string into a validated {@link Color}.
     *
     * <p>{@code "currentColor"} (case-insensitive) yields {@link #CURRENT};
     * otherwise the input must match {@code ^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$},
     * which is canonicalized to lowercase and, if a 3-digit shorthand, expanded to
     * 6 digits ({@code #abc → #aabbcc}). Anything else throws.
     *
     * @param raw the color string (surrounding whitespace is trimmed)
     * @return the validated color
     * @throws IllegalArgumentException if {@code raw} is null or not a recognized color
     */
    static Color parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("color must not be null");
        }
        String s = raw.trim();
        if (s.equalsIgnoreCase("currentColor")) {
            return CURRENT;
        }
        Hex named = NAMED.get(s.toLowerCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        if (!HEX_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                "invalid color: \"" + raw + "\" (expected a name like \"red\", \"currentColor\", or #rgb / #rrggbb)");
        }
        String digits = s.substring(1).toLowerCase(Locale.ROOT);
        if (digits.length() == 3) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                sb.append(digits.charAt(i)).append(digits.charAt(i));
            }
            digits = sb.toString();
        }
        return new Hex("#" + digits);
    }
}
