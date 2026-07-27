package com.lattex.api;

import java.util.Objects;

/**
 * Host-controlled options for a trusted two-state equation transition.
 *
 * <p>This is deliberately separate from {@link RenderOptions}: transition
 * behavior is not part of the author-controlled {@code \\lx} language and does
 * not widen LatteX's static SVG contract.
 *
 * @param renderOptions fixed-size options applied independently to both static endpoint renders;
 *                      host-fluid output is refused because its inline style is outside the
 *                      trusted transition's current-S8 endpoint contract
 * @param durationMillis whole-expression transition duration, from 0 through 2000 ms
 */
public record InteractiveOptions(RenderOptions renderOptions, int durationMillis) {
    public static final int MIN_DURATION_MILLIS = 0;
    public static final int MAX_DURATION_MILLIS = 2_000;
    public static final int DEFAULT_DURATION_MILLIS = 240;

    public InteractiveOptions {
        Objects.requireNonNull(renderOptions, "renderOptions");
        if (renderOptions.fluid()) {
            throw new IllegalArgumentException(
                "interactive transitions require fixed-size S8 endpoint SVGs;"
                    + " fluid adds inline style");
        }
        if (durationMillis < MIN_DURATION_MILLIS
                || durationMillis > MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("durationMillis must be between "
                + MIN_DURATION_MILLIS + " and " + MAX_DURATION_MILLIS
                + " inclusive, got: " + durationMillis);
        }
    }

    /** The conservative host defaults: ordinary static rendering and a 240 ms transition. */
    public static InteractiveOptions defaults() {
        return new InteractiveOptions(RenderOptions.defaults(), DEFAULT_DURATION_MILLIS);
    }

    public InteractiveOptions withDurationMillis(int value) {
        return new InteractiveOptions(renderOptions, value);
    }

    public InteractiveOptions withRenderOptions(RenderOptions value) {
        return new InteractiveOptions(value, durationMillis);
    }
}
