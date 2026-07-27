package com.lattex.api;

/**
 * Result of a diagnostic render (L6.2): the SVG when the render succeeded, plus a
 * {@link Diagnostics} classifying the outcome. NEVER throws — the diagnostic twin of
 * {@link LatteX#render(String)} for pipelines that must degrade per-formula rather
 * than per-page (the Sirentide {@code renderWithDiagnostics} consumer contract).
 *
 * @param svg         the rendered SVG document on success; on failure, either
 *                    {@code ""} (the historical/default contract) or the bounded
 *                    inert card requested with {@link RenderOptions#renderErrors()}
 * @param diagnostics the outcome classification (never null)
 */
public record RenderResult(String svg, Diagnostics diagnostics) {
    /**
     * Enforces the javadoc's promised invariants (plan cfd12523): {@code svg} and
     * {@code diagnostics} are both non-null. A non-OK result may carry the opt-in
     * rendered error card, so the outcome — not SVG emptiness — is authoritative.
     */
    public RenderResult {
        java.util.Objects.requireNonNull(svg, "svg");
        java.util.Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
