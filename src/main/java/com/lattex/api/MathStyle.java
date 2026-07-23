package com.lattex.api;

/**
 * The exported selector for the top-level TeX math style (plan 0c4f6015, Marlow audit LTX-08).
 *
 * <p><strong>Why this type exists.</strong> {@link RenderOptions} is public, exported API, but its
 * math-style knob was typed as the internal {@code com.lattex.layout.MathStyle} — a type in a
 * package the module does not export. That leaked a non-exported type across the public boundary
 * (six {@code -Xlint:exports} warnings: the record component + accessor, the canonical and three
 * compatibility constructors, {@code withMathStyle}, and {@code parseMathStyle}). This enum is the
 * exported mirror {@code RenderOptions} now names instead, so a modular consumer can select a style
 * — or read {@code mathStyle()} — using only exported packages. It maps 1:1 to the internal
 * layout enum via the package-private {@link #toLayout()} (not part of the exported surface), so
 * the TeX layout algorithm's style-transition helpers stay internal to {@code com.lattex.layout}.
 *
 * <p>The four TeX math styles are TeXbook Appendix G's display, text, script, and script-script.
 * Most callers never name this type at all: {@link RenderOptions#display()},
 * {@link RenderOptions#inline()}, {@link RenderOptions#script()}, and
 * {@link RenderOptions#scriptScript()} are convenience selectors for each.
 */
public enum MathStyle {

    /** Displayed equations ({@code \[..\]} / {@code $$..$$}); the top-level default. */
    DISPLAY,
    /** In-line (text) math ({@code $..$}) — smaller fractions/scripts, limits set beside. */
    TEXT,
    /** First-level scripts, numerators/denominators of text-style fractions. */
    SCRIPT,
    /** Second-level scripts and deeper. */
    SCRIPT_SCRIPT;

    /**
     * Maps this exported selector to the internal {@code com.lattex.layout.MathStyle} the layout
     * engine consumes. Package-private on purpose: it names a non-exported type and so must never
     * appear in the exported API surface (that would re-open the LTX-08 leak). Only
     * {@code com.lattex.api} internals (e.g. {@link LatteX}) call it, as they feed layout.
     */
    com.lattex.layout.MathStyle toLayout() {
        return switch (this) {
            case DISPLAY -> com.lattex.layout.MathStyle.DISPLAY;
            case TEXT -> com.lattex.layout.MathStyle.TEXT;
            case SCRIPT -> com.lattex.layout.MathStyle.SCRIPT;
            case SCRIPT_SCRIPT -> com.lattex.layout.MathStyle.SCRIPT_SCRIPT;
        };
    }
}
