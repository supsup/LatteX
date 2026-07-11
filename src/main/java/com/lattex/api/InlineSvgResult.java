package com.lattex.api;

/**
 * Inline-math render result carrying the baseline metrics as DATA (L7, plan
 * lattex-inline-baseline; API shape decided with the Stafficy consumer at
 * lattex/126: no baked {@code style} attribute on the SVG — the embedding layer
 * owns the CSS policy and applies {@code vertical-align:-depthEm em} on the
 * wrapper element it controls, which also keeps the served SVG style-attr-free
 * for the sanitizer and composes with fx enter-transforms).
 *
 * <p>Both metrics are in {@code em} of the formula's own font size, measured from
 * the baseline: {@code depthEm} is ink below (the amount the SVG must be lowered
 * to sit ON the text baseline), {@code heightEm} ink above. Never negative.
 *
 * @param svg      the self-contained inline-styled SVG document — byte-identical
 *                 to {@link LatteX#renderInline(String)} for the same input
 * @param depthEm  ink depth below the baseline, in em (&ge; 0)
 * @param heightEm ink height above the baseline, in em (&ge; 0)
 */
public record InlineSvgResult(String svg, double depthEm, double heightEm) {}
