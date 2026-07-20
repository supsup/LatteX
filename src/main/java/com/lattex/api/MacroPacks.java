package com.lattex.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Bundled preset macro packs (L8 follow-on, plan 68f2eb85 scope 4) — curated
 * {@link RenderOptions#withMacros} maps that dogfood the user-macro engine.
 * Every body uses only built-in commands, every name obeys the additive-only
 * rule, and both facts are pinned by tests (the pack self-census), so a pack
 * can never break an input that parsed without it.
 *
 * <p>Usage: {@code LatteX.render(src, RenderOptions.defaults().withMacros(MacroPacks.PHYSICS))},
 * or merge with your own via {@link #plus(Map, Map)}.
 */
public final class MacroPacks {

    private MacroPacks() {
    }

    /**
     * Physics notation conveniences. Dirac brackets ({@code \bra \ket \braket})
     * are deliberately ABSENT: LatteX implements them natively, and the
     * additive-only rule refuses a pack that shadows a built-in — the pack
     * self-census test enforces exactly that, and refused the first draft of
     * this very map.
     *
     * <ul>
     *   <li>{@code \abs{x}} — |x|</li>
     *   <li>{@code \norm{v}} — ‖v‖</li>
     *   <li>{@code \ev{A}} — ⟨A⟩ (expectation)</li>
     *   <li>{@code \dd} — roman differential d</li>
     *   <li>{@code \dv{f}{x}} — df/dx (roman d, fraction form)</li>
     *   <li>{@code \pdv{f}{x}} — ∂f/∂x (fraction form)</li>
     *   <li>{@code \grad} — ∇</li>
     * </ul>
     */
    public static final Map<String, String> PHYSICS = Map.of(
        "abs", "| #1 |",
        "norm", "\\Vert #1 \\Vert",
        "ev", "\\langle #1 \\rangle",
        "dd", "\\mathrm{d}",
        "dv", "\\frac{\\mathrm{d} #1}{\\mathrm{d} #2}",
        "pdv", "\\frac{\\partial #1}{\\partial #2}",
        "grad", "\\nabla");

    /**
     * A new map holding {@code base} plus {@code extra} ({@code extra} wins on a
     * duplicate name) — the merge helper for composing a pack with caller macros,
     * since {@link RenderOptions#macros()} is a single immutable map.
     */
    public static Map<String, String> plus(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> merged = new HashMap<>(base);
        merged.putAll(extra);
        return Map.copyOf(merged);
    }
}
