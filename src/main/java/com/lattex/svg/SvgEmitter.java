package com.lattex.svg;

import com.lattex.api.Color;
import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.PositionedGlyph;
import com.lattex.layout.Rule;
import java.util.Locale;

/**
 * Emits a self-contained SVG document from a {@link Layout}, using ONLY the
 * named minimal element/attribute subset (CONTRIBUTING §5): {@code svg},
 * {@code g}, {@code path}, {@code rect}. Glyphs are inline filled {@code <path>}
 * — never {@code <text>}, {@code <use>}, or {@code <defs>}. This alphabet is
 * already inside the downstream sanitizer allow-list, so LatteX SVG passes
 * unchanged.
 *
 * <p>Each glyph's path data is in font design units; a per-path
 * {@code transform="translate(originX,baselineY) scale(s,-s)"} scales it to the
 * display size and flips the y-axis (font is y-up, SVG is y-down). Fraction and
 * radical bars are emitted as filled {@code <rect>}s (already in user space).
 */
public final class SvgEmitter {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    /** Small margin (user units) so glyph ink is not flush against the edge. */
    private static final double MARGIN = 2.0;
    /** The default glyph fill — opaque black. */
    private static final Color GLYPH_FILL = Color.BLACK;

    private SvgEmitter() {
    }

    /** Emits the SVG document for a laid-out formula using the default black fill. */
    /// L10: hard output budget. 2M chars (~2 MB) is ~50x the largest wild-corpus
    /// formula's SVG — a ceiling for runaway amplification, not a working limit.
    static final int MAX_OUTPUT_CHARS = 2_000_000;

    public static String emit(Layout layout, SfntFont font, String ariaLabel) {
        return emit(layout, font, ariaLabel, GLYPH_FILL);
    }

    /**
     * Emits the SVG document for a laid-out formula with an explicit fill.
     *
     * @param fill the glyph fill — a typed {@link Color}, so the "must be an
     *             allow-listed {@code currentColor}/{@code #rrggbb} literal" contract
     *             is now COMPILER-enforced, not Javadoc-only: only a validated
     *             {@link Color#svgValue()} can reach the {@code fill} attribute, so no
     *             raw author string can. It is a fill VALUE only — never a new element
     *             or attribute — so the minimal-alphabet contract is preserved.
     */
    public static String emit(Layout layout, SfntFont font, String ariaLabel, Color fill) {
        return emit(layout, font, ariaLabel, fill, false);
    }

    /**
     * Emits the SVG document for a laid-out formula, optionally with <em>fluid</em>
     * (scale-to-fit) sizing on the outer {@code <svg>}.
     *
     * <p><strong>Fluid sizing is presentation-only.</strong> With {@code fluid=false}
     * this is byte-for-byte the historical output. With {@code fluid=true} the ONE
     * change is a single sizing {@code style} rule on the outer {@code <svg>} —
     * {@code width:100%;max-width:<natural>px;height:auto} — so the equation
     * downscales in a container narrower than its natural width and never upscales
     * past it (the {@code max-width} is the natural width; {@code height:auto} keeps
     * the aspect ratio via the UNCHANGED viewBox). The viewBox, the {@code width}/
     * {@code height} attributes (the fixed-size fallback), and every inner element
     * are identical to the fixed-size render — no layout or geometry is touched. The
     * value is renderer-derived ({@code [0-9.:%;a-z-]} from a fixed template + a
     * numeric width) — no author string can reach it.
     */
    public static String emit(Layout layout, SfntFont font, String ariaLabel, Color fill,
                              boolean fluid) {
        double vbX = layout.minX() - MARGIN;
        double vbY = layout.minY() - MARGIN;
        double vbW = layout.width() + 2 * MARGIN;
        double vbH = layout.height() + 2 * MARGIN;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"").append(SVG_NS).append("\"")
            .append(" viewBox=\"")
            .append(num(vbX)).append(' ').append(num(vbY)).append(' ')
            .append(num(vbW)).append(' ').append(num(vbH)).append("\"")
            .append(" width=\"").append(num(vbW)).append("\"")
            .append(" height=\"").append(num(vbH)).append("\"");
        if (fluid) {
            // The one fluid addition: a single sizing rule. Shrink-to-fit, capped at
            // the natural width, aspect ratio preserved by the (unchanged) viewBox.
            svg.append(" style=\"width:100%;max-width:").append(num(vbW))
                .append("px;height:auto\"");
        }
        svg.append(" role=\"img\"")
            .append(" aria-label=\"").append(escape(ariaLabel)).append("\">\n");

        emitInner(svg, layout, font, fill, 0.0, 0.0);
        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * Emits the INNER markup for a laid-out formula as an embeddable fragment —
     * the {@code <g>/<path>/<rect>} elements WITHOUT the surrounding {@code <svg>}
     * wrapper, viewBox, or aria — using the default black fill.
     *
     * @see #emitFragment(Layout, SfntFont, com.lattex.api.Color)
     */
    public static String emitFragment(Layout layout, SfntFont font) {
        return emitFragment(layout, font, GLYPH_FILL);
    }

    /**
     * Emits the INNER markup for a laid-out formula as an embeddable fragment: the
     * same positioned {@code <g>/<path>/<rect>} elements {@link #emit} produces, but
     * WITHOUT the {@code <svg>} wrapper / viewBox / aria, and re-based so the
     * fragment's local origin {@code (0,0)} is the LEFT END OF THE BASELINE — x=0 at
     * the left ink edge ({@code minX}), y=0 at the baseline (already the layout
     * origin). Coordinates are shifted by {@code -minX} in x (none in y); the glyph
     * {@code <path d>} data is unchanged, so the fragment paints glyphs and rules
     * IDENTICALLY to {@link #emit} (no forked emit path). Stays within the same
     * {@code g/path/rect} alphabet.
     *
     * @param fill the default group {@code fill} value (an allow-listed literal); a
     *             per-glyph {@code \color}/{@code \textcolor} override still wins.
     */
    public static String emitFragment(Layout layout, SfntFont font, Color fill) {
        StringBuilder out = new StringBuilder();
        // Re-base x so the left ink edge lands at x=0; the baseline is already y=0.
        emitInner(out, layout, font, fill, -layout.minX(), 0.0);
        return out.toString();
    }

    /**
     * The shared inner-element emission consumed by BOTH {@link #emit} (wrapped in
     * {@code <svg>}) and {@link #emitFragment} (bare) — one producer, so the full
     * document and the embeddable fragment cannot diverge in how they paint glyphs
     * and rules. Each glyph/rule is translated by {@code (dx, dy)} on top of its
     * laid-out position; the per-glyph outline {@code d} and every {@code fill}
     * (default group fill + {@code \color}/{@code \textcolor} overrides) are emitted
     * unchanged.
     */
    static void emitInner(StringBuilder out, Layout layout, SfntFont font, Color fill,
                          double dx, double dy) {
        out.append("  <g fill=\"").append(fill.svgValue()).append("\">\n");
        // ONE producer decides which glyphs emit a <path> and in what order
        // (emittedGlyphs); glyphmap() keys off the SAME sequence, so a path index in
        // the SVG and a path index in the data-lx-glyphmap sidecar can never diverge.
        for (EmittedGlyph eg : emittedGlyphs(layout, font)) {
            // L10 (plan lattex-hostile-input-hardening): incremental output cap —
            // fail DURING buffer growth so the runaway SVG is never built in full
            // (Sirentide's incremental-cap pattern). Checked per element, not
            // post-emit; the trip is typed (OUTPUT_CAP_EXCEEDED via diagnostics).
            if (out.length() > MAX_OUTPUT_CHARS) {
                throw com.lattex.parse.MathSyntaxException.capExceeded(
                    "SVG output exceeds the " + MAX_OUTPUT_CHARS
                    + "-character cap; reduce the formula size");
            }
            PositionedGlyph g = eg.glyph();
            out.append("    <path d=\"").append(eg.pathData()).append("\"");
            if (g.color() != null) {
                // \color/\textcolor override; svgValue() is an allow-listed fill literal.
                out.append(" fill=\"").append(g.color().svgValue()).append("\"");
            }
            out.append(" transform=\"translate(")
                .append(num(g.originX() + dx)).append(' ').append(num(g.baselineY() + dy))
                .append(") scale(").append(num(g.scale())).append(' ').append(num(-g.scale()))
                .append(")\"/>\n");
        }
        // Rules emit AFTER all glyph <path>s, so a polygon rule's <path> lands past the
        // glyph paths the data-lx-glyphmap indexes — the glyph path indices are unchanged
        // and a strike/arrowhead is never a thread target.
        for (Rule r : layout.rules()) {
            if (r.isPolygon()) {
                // A filled convex polygon (cancel strike / arrowhead) — same fill
                // conventions as a rect rule, emitted as a filled <path> (in-alphabet).
                double[] p = r.polygon();
                out.append("    <path d=\"");
                for (int i = 0; i < p.length; i += 2) {
                    out.append(i == 0 ? "M " : " L ")
                        .append(num(p[i] + dx)).append(' ').append(num(p[i + 1] + dy));
                }
                out.append(" Z\"");
                if (r.color() != null) {
                    out.append(" fill=\"").append(r.color().svgValue()).append("\"");
                }
                out.append("/>\n");
                continue;
            }
            out.append("    <rect x=\"").append(num(r.x() + dx)).append("\"")
                .append(" y=\"").append(num(r.y() + dy)).append("\"")
                .append(" width=\"").append(num(r.width())).append("\"")
                .append(" height=\"").append(num(r.height())).append("\"");
            if (r.color() != null) {
                out.append(" fill=\"").append(r.color().svgValue()).append("\"");
            }
            out.append("/>\n");
        }
        out.append("  </g>\n");
    }

    /** A glyph that produces a {@code <path>}, paired with its path data. */
    private record EmittedGlyph(PositionedGlyph glyph, String pathData) {}

    /**
     * THE single decision of which glyphs emit a {@code <path>} and in what order: it
     * applies the one inkless skip ({@code d.isEmpty()}) to {@link Layout#glyphs()} and
     * returns the survivors in emit order. Both {@link #emitInner} (which writes the
     * paths) and {@link #glyphmap} (which keys token identity to path index) consume
     * this, so their path indices are the SAME by construction — no mirrored predicate
     * to drift out of sync.
     */
    private static java.util.List<EmittedGlyph> emittedGlyphs(Layout layout, SfntFont font) {
        java.util.List<EmittedGlyph> out = new java.util.ArrayList<>();
        for (PositionedGlyph g : layout.glyphs()) {
            String d = GlyphPath.toPathData(font.outline(g.glyphId()));
            if (!d.isEmpty()) {
                out.add(new EmittedGlyph(g, d));
            }
        }
        return out;
    }

    /**
     * Serializes the token-identity {@code data-lx-glyphmap} sidecar for a laid-out
     * formula: {@code <hexcp>:<idx>,<idx>;<hexcp>:...}, where each index addresses an
     * emitted {@code <path>} in EMIT ORDER and the run groups the paths that share a
     * source code point. The {@code thread} fx effect reads it to light up every
     * occurrence of a hovered token.
     *
     * <p>Keys off the SAME {@link #emittedGlyphs} sequence that {@link #emitInner}
     * writes, so an index here is a {@code <path>}'s position by construction — not a
     * mirrored skip predicate that could drift. Only code points with two or more
     * occurrences form a run — a unique glyph has nothing to thread and stays inert.
     * Note the map is math-atom-scoped: only author {@code Atom}s carry a source code
     * point, so construction glyphs (delimiters, radical surds, big-op symbols) and
     * text-mode / operator-name letters ({@code \text}, {@code \sin}) are
     * {@link PositionedGlyph#NO_SOURCE} and never thread. Returns the empty string when
     * nothing is threadable (the runtime treats that as no map).
     */
    public static String glyphmap(Layout layout, SfntFont font) {
        java.util.Map<Integer, java.util.List<Integer>> byCodePoint = new java.util.LinkedHashMap<>();
        // Key off the SAME emitted-glyph sequence emitInner writes — the index here is
        // the <path>'s position in the SVG, by construction, not by a mirrored predicate.
        java.util.List<EmittedGlyph> emitted = emittedGlyphs(layout, font);
        for (int idx = 0; idx < emitted.size(); idx++) {
            int cp = emitted.get(idx).glyph().sourceCodePoint();
            if (cp != PositionedGlyph.NO_SOURCE) {
                byCodePoint.computeIfAbsent(cp, k -> new java.util.ArrayList<>()).add(idx);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, java.util.List<Integer>> e : byCodePoint.entrySet()) {
            if (e.getValue().size() < 2) {
                continue; // a single occurrence isn't a thread group
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(Integer.toHexString(e.getKey())).append(':');
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(e.getValue().get(i));
            }
        }
        return sb.toString();
    }

    /**
     * Serializes the precedence-group {@code data-lx-groupmap} sidecar for a laid-out
     * formula: {@code <rank>:<idx>,<idx>;<rank>:...}, where each index addresses an
     * emitted {@code <path>} in EMIT ORDER (the SAME {@link #emittedGlyphs} sequence
     * {@link #glyphmap} and {@link #emitInner} key off) and {@code rank} is the
     * evaluation order — {@code 0} = evaluated first. The {@code precedence} fx effect
     * reads it to light sub-expressions in binding order (deepest parens inward).
     *
     * <p>FENCED-ONLY v1 (seam sign-off lattex/169): rank is derived purely from
     * {@code \left..\right} nesting — a source atom's {@code fenceDepth} inverted so the
     * DEEPEST fence is rank 0 ({@code rank = maxDepth − fenceDepth}). Construction glyphs
     * ({@link PositionedGlyph#NO_RANK}) carry no rank. This handles the unambiguous case
     * (paren grouping) without operator-precedence reconstruction, so it never guesses.
     *
     * <p>WHOLE-EXPRESSION FAIL-HONEST: emitted ONLY when there is genuine nesting
     * variation (≥2 distinct ranks). A paren-free expression — or one where every atom
     * sits at the same depth — has no cascade to show and returns the empty string (the
     * runtime treats that as no map, degrading to a single static highlight). A partial
     * or single-group cascade is never emitted.
     */
    public static String groupmap(Layout layout, SfntFont font) {
        java.util.List<EmittedGlyph> emitted = emittedGlyphs(layout, font);
        int maxDepth = -1;
        for (EmittedGlyph eg : emitted) {
            int d = eg.glyph().fenceDepth();
            if (d != PositionedGlyph.NO_RANK) {
                maxDepth = Math.max(maxDepth, d);
            }
        }
        if (maxDepth < 1) {
            return ""; // no fence nesting variation → nothing to cascade → static degrade
        }
        // rank = maxDepth − depth, so the deepest fenced group is rank 0 (evaluated first).
        // TreeMap keeps ranks ascending in the serialized runs.
        java.util.Map<Integer, java.util.List<Integer>> byRank = new java.util.TreeMap<>();
        for (int idx = 0; idx < emitted.size(); idx++) {
            int d = emitted.get(idx).glyph().fenceDepth();
            if (d != PositionedGlyph.NO_RANK) {
                byRank.computeIfAbsent(maxDepth - d, k -> new java.util.ArrayList<>()).add(idx);
            }
        }
        if (byRank.size() < 2) {
            return ""; // only one rank present → no ordering to animate
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, java.util.List<Integer>> e : byRank.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':');
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(e.getValue().get(i));
            }
        }
        return sb.toString();
    }

    /** Locale-independent compact number: integers plain, else up to 4 dp. */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.4f", v);
        // Trim trailing zeros (and a dangling dot).
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * XML-escapes the aria-label text. The shared
     * {@link com.lattex.parse.OutputLegality} policy runs FIRST (plan cfd12523),
     * so this boundary shares the SINGLE code-point legality policy with the MathML
     * and styled-HTML surfaces: illegal C0 controls (e.g. a NUL — the class
     * Sirentide's fuzzer caught) are stripped and an unpaired surrogate fails loud;
     * legal whitespace (tab/newline) is preserved. The four-metachar escaping then
     * happens exactly once, here.
     */
    private static String escape(String s) {
        String clean = com.lattex.parse.OutputLegality.sanitize(s);
        StringBuilder out = new StringBuilder(clean.length());
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
