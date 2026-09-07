package com.lattex.api;

import java.util.List;

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
                          String detail, int offset, String caretString,
                          List<String> caveats) {

    /// ADDITIVE, and the additivity is the whole point [ruling PROJECT/stafficy 25843, lattex/868].
    ///
    /// {@code caveats} is a SECOND honesty channel, deliberately NOT {@code detail}. LatteX documents
    /// empty-{@code detail} as the cleanliness discriminator, so emitting a dropped-the-number note
    /// through {@code detail} would silently change the meaning of a documented convention for every
    /// consumer already gating on it - a cross-cutting guard change wearing the clothes of a small
    /// feature. Consumers gating on {@code detail} keep their exact meaning; consumers who want the
    /// caveats opt in by reading this field.
    ///
    /// Sits with {@code offset}/{@code caretString} as a LatteX-only progressive enhancement under
    /// the lattex/126 parity contract: {@code message}/{@code line}/{@code detail} still mirror
    /// {@code com.sirentide.api.Diagnostics} field-for-field, and a shared consumer that ignores this
    /// field is still correct.
    public Diagnostics {
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    /// The pre-caveats shape, retained so the construction sites that predate this field do not move.
    /// An omitted {@code caveats} is an EMPTY list, never null - the same empty-on-clean discipline
    /// {@code detail} already follows.
    public Diagnostics(Outcome outcome, String stage, String message, int line,
                       String detail, int offset, String caretString) {
        this(outcome, stage, message, line, detail, offset, caretString, List.of());
    }
}
