package com.lattex.api;

/**
 * An embeddable, laid-out math fragment: the INNER SVG markup plus the box
 * metrics a consumer needs to place the math inline on a shared baseline.
 *
 * <p>Unlike {@link LatteX#render(String)} — which returns a full, self-contained
 * {@code <svg>} document — a fragment is the bare positioned markup ({@code
 * <g>/<path>/<rect>}, no {@code <svg>} wrapper, no viewBox, no aria) re-based so
 * its local origin {@code (0,0)} is the LEFT END OF THE BASELINE: x=0 at the left
 * ink edge, y=0 on the baseline. A consumer (e.g. a diagram renderer drawing
 * math-in-labels) drops {@code innerSvg} inside its own {@code <g transform>} at
 * the target baseline point and advances the pen by {@link #widthPx()}.
 *
 * <p>All measurements are in the same user units (px at 1:1) as the {@code
 * fontSizePx} passed to {@link LatteX#renderFragment(String, double)}.
 *
 * @param innerSvg  the positioned {@code <g>/<path>/<rect>} markup (no {@code
 *                  <svg>} wrapper, no aria), each element carrying its own {@code
 *                  transform}/{@code fill} exactly as {@link LatteX#render} emits it
 * @param widthPx   the fragment's tight INK width (maxX-minX) — NOT an advance with
 *                  side-bearing, so a consumer composing MULTIPLE fragments inline
 *                  gets ink-touching (add its own spacing); for a single embedded
 *                  fragment (math-in-labels) this is exactly the box width to reserve
 * @param heightPx  the ink extent ABOVE the baseline (a non-negative magnitude)
 * @param depthPx   the ink extent BELOW the baseline (a non-negative magnitude)
 * @param glyphmap  the token-identity sidecar for the fragment's inner {@code <path>}s
 *                  — the {@code <hexcp>:<idx>,<idx>;<hexcp>:...} grammar (charset
 *                  {@code [0-9a-f:,;]}) produced by the renderer, letting a diagram
 *                  consumer ADDRESS the embedded math (hover a variable &rarr; thread its
 *                  occurrences). Each index addresses an emitted {@code <path>} of
 *                  {@link #innerSvg()} in EMIT ORDER, and a run groups the paths that
 *                  share a source code point (only two-or-more occurrences form a run).
 *                  The map is math-atom-scoped: only author {@code Atom}s carry a source
 *                  code point, so construction glyphs (delimiters, radical surds,
 *                  big-operator symbols) and {@code \text}/{@code \sin} letters have no
 *                  source — they CONSUME an emit index but are never keyed. Empty string
 *                  when nothing is threadable.
 *
 *                  <p><strong>Re-base invariance (the load-bearing contract).</strong>
 *                  The indices are EMIT-ORDER-based, so they are RE-BASE-INVARIANT.
 *                  {@link LatteX#renderFragment(String, double)} re-bases {@code innerSvg}
 *                  x by {@code -minX} (shifting every element's {@code transform}), but an
 *                  index still addresses the Nth {@code <path>} of {@code innerSvg}
 *                  regardless of that translate — index N = the Nth path, position-
 *                  independent. No index adjustment is applied or needed.
 * @param mathml    a Presentation-MathML {@code <math>} serialization of the SAME parsed
 *                  body that produced {@code innerSvg} (one parse, two serializations —
 *                  no re-parse, so the two can never drift), for consumers that want an
 *                  assistive-tech surface next to the visual paths (a diagram label's
 *                  aria payload). PER-FRAGMENT FAIL-SOFT: empty string when MathML
 *                  serialization fails or the fragment was constructed via the
 *                  pre-mathml arity — never null, and a MathML failure never affects
 *                  {@code innerSvg} (a label that renders visually keeps rendering).
 */
public record MathFragment(String innerSvg, double widthPx, double heightPx, double depthPx,
                           String glyphmap, String mathml) {

    /**
     * Enforces exactly the invariants this record's javadoc already promises (plan
     * cfd12523): {@code innerSvg}, {@code glyphmap} (empty when nothing is threadable —
     * never null), and {@code mathml} (never null — empty on fail-soft) are non-null;
     * every metric is a finite, non-negative magnitude (widthPx is the tight ink width,
     * heightPx/depthPx the above/below-baseline extents — none can legitimately be
     * negative or non-finite).
     */
    public MathFragment {
        java.util.Objects.requireNonNull(innerSvg, "innerSvg");
        java.util.Objects.requireNonNull(glyphmap, "glyphmap");
        java.util.Objects.requireNonNull(mathml, "mathml");
        requireFiniteNonnegative(widthPx, "widthPx");
        requireFiniteNonnegative(heightPx, "heightPx");
        requireFiniteNonnegative(depthPx, "depthPx");
    }

    private static void requireFiniteNonnegative(double v, String name) {
        if (!Double.isFinite(v) || v < 0.0) {
            throw new IllegalArgumentException(
                name + " must be a finite non-negative magnitude; got: " + v);
        }
    }

    /**
     * Pre-mathml arity (plan lattex-mathfragment-mathml): delegates with an empty
     * {@code mathml} so every existing call-site — including consumers that construct
     * fragments field-by-field (the Sirentide math-bridge pattern) — compiles and
     * behaves exactly as before the component existed. Prefer the canonical
     * constructor when the MathML is available.
     */
    public MathFragment(String innerSvg, double widthPx, double heightPx, double depthPx,
                        String glyphmap) {
        this(innerSvg, widthPx, heightPx, depthPx, glyphmap, "");
    }
}
