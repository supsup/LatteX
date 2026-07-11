package com.lattex.api;

/**
 * A structured, author-facing report for one diagnostic render (L6.2, plan
 * lattex-containment-diagnostics). Core fields {@code outcome}/{@code stage}/
 * {@code message}/{@code line}/{@code detail} mirror {@code com.sirentide.api.Diagnostics}
 * field-for-field (the lattex/126 parity contract) so a consumer handles both with one
 * code path; {@code offset}/{@code caretString} are LatteX-only progressive enhancements
 * the shared path may ignore.
 *
 * @param outcome     the {@link Outcome} classification ({@code OK} on success)
 * @param stage       where the pipeline was: {@code "parse"}, {@code "layout"}, {@code "emit"}
 * @param message     a human-readable, author-directed sentence (safe for UI/log)
 * @param line        1-based source line of the problem, or {@code -1} when unknown
 * @param detail      lower-level crumb (throwable type+message); {@code ""} when none
 * @param offset      LatteX-only: source character offset, or {@code -1} when unknown
 * @param caretString LatteX-only: multi-line caret rendering pointing at the problem,
 *                    or {@code ""} when not positional
 */
public record Diagnostics(Outcome outcome, String stage, String message, int line,
                          String detail, int offset, String caretString) {}
