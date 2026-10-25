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
 *   <li><strong>Illegal characters</strong> are STRIPPED. "Illegal" means exactly
 *       the complement of the XML 1.0 {@code Char} production (section 2.2,
 *       expressed once in {@link #isXmlChar(int)}): C0 controls other than
 *       {@code \t}, {@code \n}, {@code \r}, and the BMP noncharacters U+FFFE and
 *       U+FFFF. Stripping matches the SVG path's pre-existing C0 behavior so no
 *       policy fork remains, and an XML 1.0 parser rejects every one of them, so
 *       a NUL/BEL/US/U+FFFE in author input can never reach the output (plan
 *       e5324119 extended the C0-only check to the full production).</li>
 *   <li><strong>Unpaired UTF-16 surrogates</strong> fail LOUD via
 *       {@link MathSyntaxException}: a lone surrogate is malformed input (it cannot
 *       be a legal XML character nor round-trip through UTF-8), not renderable
 *       content, so it is rejected through the same typed channel as a syntax error
 *       rather than silently dropped.</li>
 *   <li><strong>Legal whitespace and all other characters</strong> (including
 *       well-formed astral pairs up to U+10FFFF) are preserved verbatim.</li>
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
     * The XML 1.0 {@code Char} production (XML 1.0 Fifth Edition, section 2.2),
     * over Unicode code points:
     * {@code #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]}.
     * This is the ONE legality predicate for every LatteX XML output boundary; a
     * surrogate code point (U+D800-U+DFFF) is outside it, so a lone surrogate is
     * illegal while the supplementary code point a valid pair encodes is legal.
     *
     * @param cp a Unicode code point
     * @return whether {@code cp} may appear in an XML 1.0 document
     */
    public static boolean isXmlChar(int cp) {
        if (cp < 0x20) {
            return cp == 0x9 || cp == 0xA || cp == 0xD;
        }
        return cp <= 0xD7FF
            || (cp >= 0xE000 && cp <= 0xFFFD)
            || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    /**
     * Whether every code point of {@code s} satisfies {@link #isXmlChar(int)}. A lone
     * surrogate is a code point outside the production, so it makes this false; a
     * well-formed pair contributes its supplementary code point, which is legal. For
     * validators that must refuse rather than repair (the repairing form is
     * {@link #sanitize(String)}).
     *
     * @param s the text to check (never {@code null})
     * @return whether {@code s} is entirely legal XML 1.0 character data
     */
    public static boolean isXmlLegal(String s) {
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            if (!isXmlChar(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * Applies the shared legality policy to {@code s}: strips every non-surrogate
     * character outside {@link #isXmlChar(int)}, rejects an unpaired surrogate loud,
     * preserves everything else. Returns the legality-clean raw text (NOT
     * format-escaped).
     *
     * @param s the raw text destined for an output boundary (never {@code null})
     * @return the same text with illegal characters removed
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

    /**
     * Whether {@code c} forces the slow path: any surrogate (its pairing must be
     * validated), or a BMP character outside the Char production.
     */
    private static boolean needsWork(char c) {
        return Character.isSurrogate(c) || !isXmlChar(c);
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
                    // A valid pair encodes U+10000-U+10FFFF, all inside the production.
                    out.append(c).append(s.charAt(i + 1));
                    i += 2;
                    continue;
                }
                throw unpaired("high", c);
            }
            if (Character.isLowSurrogate(c)) {
                throw unpaired("low", c);
            }
            if (!isXmlChar(c)) {
                i++; // illegal C0 control or U+FFFE/U+FFFF: strip
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
