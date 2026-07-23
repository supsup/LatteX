package com.lattex.parse;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The validated semantic/accessibility annotations from an {@code \lx} macro.
 * Parsed and validated at parse time but <strong>never emitted into the
 * {@code <svg>}</strong> — they ride the trusted wrapping container as
 * {@code data-lx-*} / {@code aria-label} attributes (see
 * {@link com.lattex.api.LatteX#renderStyledHtml(String)}), so the emitter's SVG
 * alphabet is unchanged.
 *
 * <ul>
 *   <li>{@link #intent()} / {@link #concept()} — lowercase snake-case identifiers
 *       ({@code ^[a-z][a-z0-9_]*$}), or {@code null} if unset.</li>
 *   <li>{@link #a11yLabel()} — a free-text accessibility label stored RAW
 *       (unescaped); the shared output-boundary legality policy + HTML-attr
 *       escaping are applied where it is stamped onto the container (plan
 *       cfd12523). {@code null} if unset.</li>
 *   <li>{@link #data()} — {@code data.*} attributes (identifier keys; values are
 *       identifiers except the free-text {@code graph-expr}, stored RAW and
 *       escaped at the container boundary), defensively copied; may be empty.</li>
 * </ul>
 *
 * @param intent    the intent identifier, or {@code null}
 * @param concept   the concept identifier, or {@code null}
 * @param a11yLabel the RAW (unescaped) accessibility label, or {@code null}
 * @param data      the {@code data.*} attributes (defensively copied)
 */
public record Semantics(String intent, String concept, String a11yLabel, Map<String, String> data) {

    /** Identifier grammar shared by {@code intent}, {@code concept}, and {@code data.*}. */
    static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    public Semantics {
        if (intent != null && !IDENTIFIER.matcher(intent).matches()) {
            throw new MathSyntaxException(
                "invalid intent: \"" + intent + "\" (expected an identifier like normalized_score)");
        }
        if (concept != null && !IDENTIFIER.matcher(concept).matches()) {
            throw new MathSyntaxException(
                "invalid concept: \"" + concept + "\" (expected an identifier like normalized_score)");
        }
        data = Map.copyOf(data);
    }

    /** The empty semantics: nothing annotated. */
    public static Semantics none() {
        return new Semantics(null, null, null, Map.of());
    }

    /** The intent identifier, if set. */
    public Optional<String> intentValue() {
        return Optional.ofNullable(intent);
    }

    /** The concept identifier, if set. */
    public Optional<String> conceptValue() {
        return Optional.ofNullable(concept);
    }

    /** The accessibility label (RAW/unescaped; escaped at the container boundary), if set. */
    public Optional<String> a11yLabelValue() {
        return Optional.ofNullable(a11yLabel);
    }

    /** Whether any annotation was specified. */
    public boolean isEmpty() {
        return intent == null && concept == null && a11yLabel == null && data.isEmpty();
    }
}
