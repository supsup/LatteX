package com.lattex.api;

import java.util.Objects;

/**
 * Typed result of assembling a trusted equation transition.
 *
 * <p>{@link Status#STATIC_FALLBACK} is an intentional success shape: one exact
 * static endpoint is returned when the optional interactive decoration cannot
 * be produced. A caller never receives a half-assembled trusted component.
 *
 * @param html component markup, one static endpoint SVG, or the empty string on total failure
 * @param status whether the result is interactive, a static fallback, or failed
 * @param fromDiagnostics diagnostics from the independently rendered initial endpoint
 * @param toDiagnostics diagnostics from the independently rendered alternate endpoint
 * @param message fixed host-facing result explanation; never contains raw input source
 */
public record InteractiveResult(
        String html,
        Status status,
        Diagnostics fromDiagnostics,
        Diagnostics toDiagnostics,
        String message) {

    public enum Status {
        INTERACTIVE,
        STATIC_FALLBACK,
        FAILED
    }

    public InteractiveResult {
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(fromDiagnostics, "fromDiagnostics");
        Objects.requireNonNull(toDiagnostics, "toDiagnostics");
        Objects.requireNonNull(message, "message");
        if (status == Status.INTERACTIVE && html.isBlank()) {
            throw new IllegalArgumentException("interactive result requires component markup");
        }
        if (status == Status.STATIC_FALLBACK && html.isBlank()) {
            throw new IllegalArgumentException("static fallback requires one endpoint SVG");
        }
        if (status == Status.FAILED && !html.isEmpty()) {
            throw new IllegalArgumentException("failed result must not carry partial markup");
        }
        if (status == Status.INTERACTIVE
                && (fromDiagnostics.outcome() != Outcome.OK
                    || toDiagnostics.outcome() != Outcome.OK)) {
            throw new IllegalArgumentException(
                "interactive result requires two successful endpoint diagnostics");
        }
        if (status == Status.STATIC_FALLBACK
                && fromDiagnostics.outcome() != Outcome.OK
                && toDiagnostics.outcome() != Outcome.OK) {
            throw new IllegalArgumentException(
                "static fallback requires at least one successful endpoint diagnostic");
        }
    }

    public boolean interactive() {
        return status == Status.INTERACTIVE;
    }
}
