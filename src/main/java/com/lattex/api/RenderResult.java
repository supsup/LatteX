package com.lattex.api;

/**
 * Result of a diagnostic render (L6.2): the SVG when the render succeeded, plus a
 * {@link Diagnostics} classifying the outcome. NEVER throws — the diagnostic twin of
 * {@link LatteX#render(String)} for pipelines that must degrade per-formula rather
 * than per-page (the Sirentide {@code renderWithDiagnostics} consumer contract).
 *
 * @param svg         the SVG document when {@code diagnostics.outcome() == OK};
 *                    {@code ""} otherwise (LatteX has no inert-shell fallback — the
 *                    consumer renders its own fallback, e.g. the verbatim source)
 * @param diagnostics the outcome classification (never null)
 */
public record RenderResult(String svg, Diagnostics diagnostics) {}
