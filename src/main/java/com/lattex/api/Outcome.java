package com.lattex.api;

/**
 * Diagnostic outcome classification (L6.2, plan lattex-containment-diagnostics).
 *
 * <p><strong>PARITY CONTRACT (lattex/126):</strong> the constant NAMES and their order
 * mirror {@code com.sirentide.api.Outcome} EXACTLY, so a consumer that renders both
 * Sirentide diagrams and LatteX math switches over ONE shared vocabulary. Do not add,
 * rename, or reorder without the sibling change in Sirentide — the drift-guard test
 * pins this.
 *
 * <p>LatteX v1 emits {@code OK}, {@code PARSE_ERROR}, and {@code RENDER_BUG};
 * {@code OUTPUT_CAP_EXCEEDED} is reserved for the L10 output caps and
 * {@code UNSUPPORTED_CONSTRUCT} for future classification — present now so the
 * shared vocabulary never shape-shifts underneath a consumer.
 */
public enum Outcome {
    OK,
    PARSE_ERROR,
    OUTPUT_CAP_EXCEEDED,
    UNSUPPORTED_CONSTRUCT,
    RENDER_BUG
}
