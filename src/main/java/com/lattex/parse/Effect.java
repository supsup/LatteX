package com.lattex.parse;

import java.util.Locale;

/**
 * The small, closed vocabulary of {@code \lx} animation effects — the value half
 * of an {@code fx.*} option. Effects are parsed and validated at parse time but
 * are <em>not</em> emitted into the {@code <svg>}; they ride the trusted wrapping
 * container (see {@link com.lattex.api.LatteX#renderStyledHtml(String)}) as
 * {@code data-lx-fx-*} attributes. This enum simply pins the accepted names.
 */
public enum Effect {
    /** A brief scale-up "pop". */
    BOOM,
    /** A rhythmic scale pulse. */
    PULSE,
    /** A fade in/out. */
    FADE,
    /** A soft glow. */
    GLOW,
    /**
     * A confluence of lightning: jagged bolts arc in from the left and right
     * viewport edges and converge on the element. Unlike the others this is NOT a
     * CSS keyframe on the element — the page-side runtime special-cases it and
     * draws a full-viewport body overlay (see the fx runtime); the containment
     * contract is unchanged (nothing reaches the inner {@code <svg>}).
     */
    LIGHTNING,
    /**
     * Night lightning. Like {@link #LIGHTNING} the page-side runtime special-cases
     * it (never a keyframe on the element), but first it drops the whole scene to
     * near-black behind a spotlit target: a full-viewport body overlay darkens the
     * page while the target stays lit, converging bolts strike the centre, and each
     * strike briefly FLASHES the dark backdrop bright (like lightning at night)
     * before the page restores. The containment contract is unchanged — the darken
     * backdrop, bolt canvas, and flash are separate body elements; nothing reaches
     * the inner {@code <svg>}.
     */
    STORM,
    /**
     * The equation writes itself: each glyph is drawn stroke-by-stroke, staggered
     * left-to-right, as if by an invisible pen. Like {@link #LIGHTNING}/{@link #STORM}
     * the page-side runtime special-cases it (NOT a CSS keyframe on the element): it
     * reads the container's inner {@code <svg>} {@code <path>} glyphs and animates their
     * {@code stroke-dashoffset} to "ink" them in. The containment contract is unchanged —
     * the runtime only toggles presentation attributes on the existing paths; it adds no
     * new element to the inner {@code <svg>}.
     */
    HANDSCRIBE,
    /**
     * A cyan wireframe HOLOGRAM: rolling scanlines, an RGB-split jitter, a slow parallax
     * tilt and occasional flicker dropouts, then a subtle idle loop. Page-side JS routine
     * (NOT a CSS keyframe): it tints/filters/transforms the container and drives a
     * pointer-events-none scanline body overlay; it adds no element to the inner {@code <svg>}.
     */
    HOLOGRAM,
    /**
     * A NEON SIGN buzzing to life: a couple of failed, stuttering ignitions, then a steady
     * glowing hum — with one unlucky glyph left flickering forever. Page-side JS routine: it
     * drives a layered drop-shadow bloom + opacity on the container and toggles opacity on one
     * existing inner-{@code <svg>} {@code <path>}; it adds no element to the {@code <svg>}.
     */
    NEONSIGN,
    /**
     * The equation CRYSTALLIZEs: a frost front creeps across it, an icy blue-white tint fits
     * over the glyphs, and a few sparkles pop at the end. Page-side JS routine: it animates a
     * filter/clip-path on the container and builds separate body overlays (a frost pane +
     * sparkle motes); it adds no element to the inner {@code <svg>}.
     */
    CRYSTALLIZE,
    /**
     * The equation drafts itself as an engineer's BLUEPRINT: white construction linework
     * (stroke-draw) on a deep-cyan drafting field, with transient guide marks that fade as the
     * glyphs resolve. Page-side JS routine: it flips a container class, stroke-draws the
     * existing paths in white, and fades a body-level guide overlay; it adds no element to the
     * inner {@code <svg>}.
     */
    BLUEPRINT,
    /**
     * WOBBLE: on its trigger the glyphs jiggle like jelly — each does an autonomous damped
     * wobble (a springy rotate + bob decaying over ~1.5s), staggered left-to-right so it
     * ripples across the equation. Page-side JS routine: it toggles {@code style.transform}
     * on the existing inner-{@code <svg>} paths while animating, cleared at rest; it adds no
     * element to the inner {@code <svg>}.
     */
    WOBBLE,
    /**
     * A GRAVWELL: click a glyph and it becomes a gravity well — neighbouring glyphs SPIRAL in
     * toward it (radius collapsing as the angle sweeps) and SHRINK as they fall, with a
     * {@code 1/r²} reach; once they've fallen in, the source glyph collapses into an
     * eclipse-like orb (a dark disc with a glowing corona), then everything unwinds back out.
     * Page-side JS routine: it wires click handlers onto the existing paths, rAF-drives their
     * {@code transform}, and parks a body-level orb overlay; it adds no element to the inner
     * {@code <svg>}.
     */
    GRAVWELL,
    /**
     * MATRIXRAIN: green "digital rain" cascades down over the equation, then decrypts into it —
     * katakana/digit streams fall in columns over the element's box, then thin as the real math
     * resolves back in. Page-side JS routine (NOT a keyframe): it dims the container and draws on
     * a pointer-events-none body {@code <canvas>}; it adds no element to the inner {@code <svg>}.
     */
    MATRIXRAIN,
    /**
     * SUPERNOVA: click and the equation collapses to a brilliant point, detonates (flash +
     * shockwave ring + stardust), then re-condenses from the dust. Page-side JS routine: it
     * toggles {@code transform}/{@code filter} on the container and draws a body {@code <canvas>}
     * overlay; it adds no element to the inner {@code <svg>}.
     */
    SUPERNOVA,
    /**
     * INKDROP: the equation grows out of a falling ink splat — a drop falls, bursts into an
     * irregular blot flinging spatter, and the glyphs bloom out of the ink. Page-side JS routine:
     * it hides the container, drives body-level drop/splat/spatter overlays, and reveals the
     * container from the blot; it adds no element to the inner {@code <svg>}.
     */
    INKDROP,
    /**
     * DIFFUSION: on hover the equation dissolves into ink-in-water (a turbulent spreading blur)
     * and reassembles on leave. Page-side JS routine: it injects a runtime body-level hidden
     * {@code <svg>} holding an {@code feTurbulence}→{@code feDisplacementMap} filter, points
     * {@code el.style.filter} at it, and drives the map's scale + opacity; it adds no element to
     * the inner math {@code <svg>} and removes its filter {@code <svg>} once idle.
     */
    DIFFUSION,
    /**
     * REFRACTION: on hover a small glassy lens follows the pointer across the equation, bending +
     * brightening the glyphs beneath it like light through water. Page-side JS routine: it tracks
     * {@code pointermove} and parks a body-level {@code backdrop-filter} lens overlay, removed on
     * {@code pointerleave}; it touches nothing in the inner {@code <svg>}.
     */
    REFRACTION,
    /**
     * TELEPORT: a transporter beam. On click the equation dematerializes into a rising shimmer of
     * particles that scatter, then re-coalesces. Page-side JS routine: it toggles {@code opacity}/
     * {@code filter} on the container and drives a pointer-events-none body {@code <canvas>}
     * particle field; it adds no element to the inner {@code <svg>}.
     */
    TELEPORT,
    /**
     * SHATTER: the equation cracks like glass — a bright crack-web flashes, the pane
     * breaks into triangular shards that scatter and hang in zero-g, and the next
     * click magnetically reassembles them (a toggle). Page-side JS routine: it replays
     * the existing inner-{@code <svg>} ink onto a body-level {@code <canvas>} overlay
     * and animates shards of it while the container only fades under the pane; it adds
     * no element to the inner {@code <svg>}.
     */
    SHATTER,
    /**
     * GLITCH: datamosh — the red/cyan channels rip apart, slice bands drop out and
     * shear sideways with static jitter, then everything snaps back pixel-perfect.
     * Unlike the JS routines this is a pure CSS keyframe on the container
     * ({@code filter}/{@code transform}/{@code clip-path}); nothing reaches the inner
     * {@code <svg>}.
     */
    GLITCH,
    /**
     * SPARKLER: a white-hot spark travels along every glyph stroke, writing the
     * equation in fire — embers spray off the moving tip, drift, and die out as the
     * letters cool into place. Page-side JS routine: handscribe's dashoffset draw-on
     * (presentation attributes on the existing {@code <path>}s only) plus a
     * pointer-events-none body {@code <canvas>} for the tip glow and embers; it adds
     * no element to the inner {@code <svg>}.
     */
    SPARKLER,
    /**
     * QUANTUM: the equation sits in superposition — every glyph jitters fuzzily between
     * ghost positions under a soft blur until you OBSERVE it (hover), when the
     * wavefunction collapses crisp with a snap-flash (idle-collapses on its own after a
     * while). Page-side JS routine: inline {@code transform}/{@code filter} on the
     * existing {@code <path>}s plus a container flash; it adds no element to the inner
     * {@code <svg>}.
     */
    QUANTUM,
    /**
     * TYPESET: letterpress — the glyphs stamp onto the page one by one in reading order,
     * each pressed in with a satisfying squash. Page-side JS routine: staggered inline
     * {@code opacity}/{@code transform} on the existing {@code <path>}s; it adds no
     * element to the inner {@code <svg>}.
     */
    TYPESET,
    /**
     * CONSTELLATION: the equation first appears as a night-sky star map — points
     * ignite along the glyph outlines, faint lines join near neighbours, the map
     * twinkles, then the stars fuse into the crisp equation. Page-side JS routine:
     * star positions are sampled read-only from the existing {@code <path>}s, the
     * stars/lines live on a pointer-events-none body {@code <canvas>}, and only
     * {@code opacity} is toggled on the paths; it adds no element to the inner
     * {@code <svg>}.
     */
    CONSTELLATION,
    /**
     * THREAD: the first <em>semantic</em> effect — hover any glyph and every other
     * occurrence of the same source token lights up while the rest of the equation
     * recedes ({@code x} threads with {@code x²}'s x, across the whole expression).
     * Page-side JS routine reading the {@code data-lx-glyphmap} container sidecar
     * (RFC lattex/34→35): identity rides the wrapper as a validated attribute,
     * paths are addressed by emit order, and only {@code opacity}/{@code transform}
     * presentation attributes on the existing {@code <path>}s change; it adds no
     * element to the inner {@code <svg>}. Without a glyphmap it is inert.
     */
    THREAD,
    /**
     * PRECEDENCE: the order-of-operations cascade — hover and the equation EVALUATES in
     * binding order, sub-expressions lighting inward (the deepest {@code \left..\right}
     * group first, then the next level out). The pedagogical sequel to {@code thread}:
     * thread answers "same token," precedence answers "what happens first." Page-side JS
     * routine reading the {@code data-lx-groupmap} container sidecar (seam lattex/168→172):
     * paths are addressed by emit order and grouped by evaluation rank, and only
     * {@code opacity}/{@code stroke} on the existing {@code <path>}s change (never
     * {@code style.transform}, which would clobber each glyph's placement transform
     * attribute); it adds no element to the inner {@code <svg>}. FENCED-ONLY v1: rank derives
     * purely from {@code \left..\right} nesting, and the sidecar is whole-expression
     * fail-honest — absent (⇒ inert, a single static highlight) whenever precedence is
     * ambiguous or there is no nesting to cascade. NOTE: only stretchy {@code \left..\right}
     * fences rank; LITERAL paren atoms ({@code (b+c)} written as plain characters) do not
     * deepen the cascade in v1, so a group containing them lights as one step (true but
     * under-refined) — operator-precedence ranking within a level is a later cut.
     */
    PRECEDENCE,
    /**
     * CANCEL: the third <em>semantic</em> effect — matching factors strike out and puff
     * away to a grayed ghost. The pedagogical sequel to {@code thread}/{@code precedence}:
     * thread answers "same token," precedence answers "what happens first," cancel answers
     * "these annihilate." Page-side JS routine reusing the {@code data-lx-glyphmap}
     * container sidecar (the SAME sidecar {@code thread} reads — zero new attribute): a
     * source code point appearing <strong>exactly twice</strong> is a cancelling pair; the
     * routine draws a diagonal strike across both on a {@code position:fixed},
     * {@code pointer-events:none} body overlay (echoing the author {@code \cancel}
     * filled-polygon look) and puffs the glyphs — an opacity fade + a placement-composed
     * scale bump (never {@code style.transform}, which would clobber each glyph's placement
     * transform attribute) — settling to a faint grayed ghost ({@code opacity ≈ 0.18}), so
     * "cancelled" stays legible instead of leaving a broken-looking bare bar; it adds no
     * element to the inner {@code <svg>}. EXACTLY-TWICE v1: the glyphmap does not encode
     * numerator-vs-denominator position, so this fires on any code point occurring twice
     * (e.g. {@code x + x} as well as {@code \frac{x}{x}}) — a strong proxy for cancellation,
     * documented honestly. A code point occurring 3+ times, or with unequal multiplicity
     * ({@code x^2/x}), is INERT (the whole-expression fail-honest posture {@code precedence}
     * takes); without a glyphmap it is inert. Restores on replay so {@code LatteXFx.play}
     * and re-triggers are idempotent. Slice 1 is {@code fx.enter} only.
     */
    CANCEL,
    /**
     * A bounded {@code \sum} blooms open into its explicit terms:
     * {@code \sum_{i=1}^{4} f(i)} swaps to {@code f(1)+f(2)+f(3)+f(4)} on click,
     * and collapses back on the next click. This is the ONE effect that needs LatteX to
     * COMPUTE new material — the page-side runtime cannot lay out LaTeX, so the expanded
     * form is PRE-RENDERED by LatteX into a hidden sibling {@code <svg>} inside the
     * {@code .lx-math} span. On click the runtime hides the outgoing expression
     * immediately, takes the new layout width, then fades the incoming one in (a swap
     * with a fade-in, not a simultaneous cross-fade). The payload is element-anchored so
     * it rides scroll for free (NO fixed overlay, so — unlike {@code cancel} — no
     * scrollKillable teardown).
     *
     * <p><strong>Double gated, opt-out by default.</strong> The expansion pass
     * ({@link SumExpansion}) runs ONLY when the host enabled
     * {@link com.lattex.api.RenderOptions#interactiveExpansion()} (default OFF) AND the
     * equation carries this directive. With the flag off, {@code unfold} degrades INERT:
     * the sum typesets normally and the interaction simply never arms. LatteX stays a pure
     * typesetter by default.
     *
     * <p><strong>Slice-1 scope</strong> (fail-INERT everywhere else): {@code \sum} only,
     * LITERAL-INTEGER bounds, a single letter index, a bare trailing summand with no
     * top-level {@code +}/{@code -}/{@code =}, at most {@link SumExpansion#MAX_TERMS}
     * terms. Substitution is by atom code point on the parsed tree (so the {@code i} in
     * {@code \sin} is never touched). Reduced-motion snaps instantly. Staggered
     * term-by-term sprout, {@code \prod}/{@code \int}, and symbolic bounds are deferred.
     */
    UNFOLD,
    /** Explicitly no effect. */
    NONE;

    /**
     * Parse an effect name ({@code boom|pulse|fade|glow|lightning|storm|none},
     * case-insensitive).
     *
     * @param raw the effect name (surrounding whitespace is trimmed)
     * @return the effect
     * @throws MathSyntaxException if {@code raw} is null or not a known effect
     */
    public static Effect parse(String raw) {
        if (raw == null) {
            throw new MathSyntaxException("fx effect must not be null");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "boom" -> BOOM;
            case "pulse" -> PULSE;
            case "fade" -> FADE;
            case "glow" -> GLOW;
            case "lightning" -> LIGHTNING;
            case "storm" -> STORM;
            case "handscribe" -> HANDSCRIBE;
            case "hologram" -> HOLOGRAM;
            case "neonsign" -> NEONSIGN;
            case "crystallize" -> CRYSTALLIZE;
            case "blueprint" -> BLUEPRINT;
            case "wobble" -> WOBBLE;
            case "gravwell" -> GRAVWELL;
            case "matrixrain" -> MATRIXRAIN;
            case "supernova" -> SUPERNOVA;
            case "inkdrop" -> INKDROP;
            case "diffusion" -> DIFFUSION;
            case "refraction" -> REFRACTION;
            case "teleport" -> TELEPORT;
            case "shatter" -> SHATTER;
            case "glitch" -> GLITCH;
            case "sparkler" -> SPARKLER;
            case "quantum" -> QUANTUM;
            case "typeset" -> TYPESET;
            case "constellation" -> CONSTELLATION;
            case "thread" -> THREAD;
            case "precedence" -> PRECEDENCE;
            case "cancel" -> CANCEL;
            case "unfold" -> UNFOLD;
            case "none" -> NONE;
            default -> throw new MathSyntaxException(
                "invalid fx effect: \"" + raw
                    + "\" (expected boom|pulse|fade|glow|lightning|storm|handscribe"
                    + "|hologram|neonsign|crystallize|blueprint|wobble|gravwell"
                    + "|matrixrain|supernova|inkdrop|diffusion|refraction|teleport"
                    + "|shatter|glitch|sparkler|quantum|typeset|constellation|thread"
                    + "|precedence|cancel|unfold|none)");
        };
    }

    /** The lowercase token form of this effect (the value stamped on the container). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
