package com.lattex.api;

/**
 * LatteX — render LaTeX math to SVG.
 *
 * <p>Renders inline and display math to self-contained SVG using a bundled
 * OFL math font, with baseline metrics for inline placement. Zero runtime
 * dependencies.
 *
 * <p><strong>Status:</strong> scaffold (S1). The parse &rarr; layout &rarr; SVG
 * pipeline lands across S2&ndash;S6 &mdash; see the {@code lattex-mvp} plan.
 */
public final class LatteX {

    private LatteX() {
    }

    /**
     * Render a LaTeX math expression to a self-contained SVG document.
     *
     * @param latex the LaTeX math source (without surrounding {@code $} delimiters)
     * @return the SVG document
     * @throws UnsupportedOperationException not yet implemented (S1 scaffold)
     */
    public static String render(String latex) {
        throw new UnsupportedOperationException(
            "LatteX.render lands in S2-S6; see the lattex-mvp plan.");
    }
}
