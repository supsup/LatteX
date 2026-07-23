package com.lattex.parse;

import java.util.Locale;

/**
 * The ONE code-point legality policy for every LatteX output boundary (plan
 * cfd12523 — Marlow audit LTX-06/LTX-15). Before this, three surfaces disagreed:
 * the SVG aria path stripped C0 controls, the MathML serializer escaped only the
 * four XML metachars (so a NUL leaked into non-well-formed XML), and the styled-HTML
 * a11y/data path pre-escaped HTML metachars but let control chars through. This
 * collapses them to a single policy applied at each boundary BEFORE the
 * format-specific escaping (which then happens exactly once):
 *
 * <ul>
 *   <li><strong>C0 controls</strong> other than the legal XML 1.0 whitespace
 *       ({@code \t}, {@code \n}, {@code \r}) are STRIPPED — matching the SVG path's
 *       pre-existing behavior so no policy fork remains. XML 1.0 forbids them
 *       entirely, so a NUL/BEL/US in author input can never reach the output.</li>
 *   <li><strong>Unpaired UTF-16 surrogates</strong> fail LOUD via
 *       {@link MathSyntaxException} — a lone surrogate is malformed input (it cannot
 *       be a legal XML character nor round-trip through UTF-8), not renderable
 *       content, so it is rejected through the same typed channel as a syntax error
 *       rather than silently dropped.</li>
 *   <li><strong>Legal whitespace and all other characters</strong> (including
 *       well-formed astral pairs) are preserved verbatim.</li>
 * </ul>
 *
 * <p>The result is the legality-clean but otherwise RAW text: callers apply their
 * own format-specific escaping (XML text/attr, HTML attr) exactly once, after this.
 * Semantic text is therefore stored raw internally and escaped only at the boundary.
 */
public final class OutputLegality {

    private OutputLegality() {
    }

    /**
     * Applies the shared legality policy to {@code s}: strips C0 controls other than
     * legal XML whitespace, rejects an unpaired surrogate loud, preserves everything
     * else. Returns the legality-clean raw text (NOT format-escaped).
     *
     * @param s the raw text destined for an output boundary (never {@code null})
     * @return the same text with illegal C0 controls removed
     * @throws MathSyntaxException if {@code s} contains an unpaired UTF-16 surrogate
     */
    public static String sanitize(String s) {
        // Fast path: scan once; only allocate a builder if something must change.
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (needsWork(c)) {
                return rewrite(s, i);
            }
        }
        return s;
    }

    /** Whether {@code c} forces the slow path: an illegal C0 control, or any surrogate. */
    private static boolean needsWork(char c) {
        if (Character.isSurrogate(c)) {
            return true; // must validate pairing (throw on a lone one)
        }
        return c < 0x20 && c != '\t' && c != '\n' && c != '\r';
    }

    /** Slow path from the first index that needs work: copy prefix, then filter/validate. */
    private static String rewrite(String s, int from) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        out.append(s, 0, from);
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    out.append(c).append(s.charAt(i + 1)); // legal astral pair — keep both
                    i += 2;
                    continue;
                }
                throw unpaired("high", c);
            }
            if (Character.isLowSurrogate(c)) {
                throw unpaired("low", c);
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                i++; // illegal C0 control — strip
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static MathSyntaxException unpaired(String which, char c) {
        return new MathSyntaxException("illegal unpaired " + which + " surrogate (U+"
            + String.format(Locale.ROOT, "%04X", (int) c) + ") in output text");
    }
}
