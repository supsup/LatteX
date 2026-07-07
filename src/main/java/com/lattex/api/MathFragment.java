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
 * @param widthPx   the horizontal advance — the fragment's ink width (maxX-minX)
 * @param heightPx  the ink extent ABOVE the baseline (a non-negative magnitude)
 * @param depthPx   the ink extent BELOW the baseline (a non-negative magnitude)
 */
public record MathFragment(String innerSvg, double widthPx, double heightPx, double depthPx) {
}
