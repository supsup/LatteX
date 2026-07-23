package com.lattex.api;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathNode;
import com.lattex.parse.SumExpansion;
import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.StyledMath;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.Effect;
import com.lattex.parse.EffectSpec;
import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import com.lattex.parse.Semantics;
import com.lattex.parse.Trigger;
import com.lattex.svg.SvgEmitter;
import java.util.Locale;
import java.util.Map;

/**
 * LatteX — render LaTeX math to SVG.
 *
 * <p>Renders math to self-contained SVG using a bundled OFL math font
 * (STIX Two Math), emitting glyphs as inline filled {@code <path>}s in a
 * minimal, sanitizer-safe element subset. Zero runtime dependencies.
 *
 * <p><strong>A pure typesetter by default.</strong> LatteX lays out the math it is
 * given and computes nothing from it. The one exception is opt-in and doubly gated:
 * an {@code fx.*=unfold} effect can pre-render a bounded {@code \sum} into its explicit
 * terms ({@link com.lattex.parse.SumExpansion}), but ONLY when the host enables
 * {@link RenderOptions#interactiveExpansion()} (default {@code false}) AND the equation
 * opted in with the directive. With the flag off — the default — that pass never runs
 * and the directive degrades inert; a plain render is byte-identical to today.
 *
 * <p><strong>Status:</strong> the full font &rarr; parse &rarr; layout &rarr; SVG
 * pipeline renders fractions, roots, sub/superscripts, big-operator limits,
 * scaled {@code \left..\right} delimiters, accents, named operators,
 * {@code \text} mode, font-variant alphabets, and 250+ symbols/relations/arrows
 * (KaTeX-gap Tier-1 &amp; Tier-2 complete). Output stays within the minimal,
 * sanitizer-safe SVG alphabet by construction.
 */
public final class LatteX {

    /** Default display font size, in user units (px at 1:1). */
    public static final double DISPLAY_FONT_SIZE = 40.0;

    /**
     * Upper bound for {@link #renderFragment(String, double)}'s {@code fontSizePx}
     * (plan cfd12523). Derived from {@link RenderOptions#MAX_SCALE} applied to
     * {@link #DISPLAY_FONT_SIZE}, so a raw fragment size and a scaled render agree on
     * the largest single formula: {@code 20 × 40 = 800} user units.
     */
    public static final double MAX_FRAGMENT_FONT_SIZE = RenderOptions.MAX_SCALE * DISPLAY_FONT_SIZE;

    // The bundled font is immutable and ~1.5 MB; parse it once, lazily.
    private static final class FontHolder {
        static final SfntFont FONT = SfntFont.loadBundled();
    }

    private LatteX() {
    }

    /**
     * Render a LaTeX math expression to a self-contained SVG document.
     *
     * @param latex the LaTeX math source (without surrounding {@code $} delimiters)
     * @return the SVG document
     */

    /**
     * L6.1 — the never-throw floor (plan lattex-containment-diagnostics): every public
     * render entry runs its post-parse pipeline (layout + emit) inside this boundary.
     * Contract: {@link MathSyntaxException} (including the layout depth guard's) passes
     * through UNTOUCHED — the typed channel consumers already catch; any OTHER
     * {@code RuntimeException} or {@code StackOverflowError} is contained into a
     * {@code MathSyntaxException} carrying the original failure as its cause, so an
     * {@code Error} can never escape onto a live page. {@code OutOfMemoryError} is
     * deliberately NOT caught (catching it lies about JVM state).
     */
    static <T> T containRender(java.util.function.Supplier<T> stage) {
        try {
            return stage.get();
        } catch (MathSyntaxException e) {
            throw e;
        } catch (StackOverflowError | RuntimeException e) {
            throw new MathSyntaxException("internal render failure ("
                + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", e);
        }
    }

    /**
     * Diagnostic render (L6.2, plan lattex-containment-diagnostics): the same SVG
     * {@link #render(String)} produces, plus a structured {@link Diagnostics} — and it
     * NEVER throws. A syntax error returns {@code PARSE_ERROR} with the offset/caret;
     * an internal layout/emit failure returns {@code RENDER_BUG} with the throwable
     * crumb in {@code detail}. The Sirentide-parity twin (lattex/126): one consumer
     * code path handles a failed diagram and a failed formula identically.
     */
    public static RenderResult renderWithDiagnostics(String latex) {
        String stage = "parse";
        try {
            MathNode node = MathParser.parse(latex);
            RenderOptions style = node instanceof StyledMath sm ? sm.style() : RenderOptions.defaults();
            MathNode body = node instanceof StyledMath sm ? sm.body() : node;
            stage = "layout";
            SfntFont font = FontHolder.FONT;
            LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
                DISPLAY_FONT_SIZE * style.scale(), style.mathStyle(), false);
            Layout layout = LayoutEngine.layout(body, ctx);
            stage = "emit";
            String svg = SvgEmitter.emit(layout, font, describe(body), style.color());
            return new RenderResult(svg, new Diagnostics(Outcome.OK, "emit",
                "Rendered successfully.", -1, "", -1, ""));
        } catch (MathSyntaxException e) {
            // Typed channel: a parse-stage throw is an author-facing syntax error with
            // position; a later-stage typed throw (depth guard, contained internal
            // failure) is classified RENDER_BUG — the pipeline, not the author. A parse-stage
            // throw flagged as an unknown command/environment is UNSUPPORTED_CONSTRUCT, so a
            // typo ("did you mean \frac?") is distinguishable from malformed syntax. A resource-
            // cap trip (output bytes / layout boxes) wins over both — it is OUTPUT_CAP_EXCEEDED
            // regardless of stage, so a hostile blow-up reads as a cap, not a bug or a typo.
            Outcome outcome;
            if (e.isCapExceeded()) {
                outcome = Outcome.OUTPUT_CAP_EXCEEDED;
            } else if ("parse".equals(stage)) {
                outcome = e.isUnsupportedConstruct() ? Outcome.UNSUPPORTED_CONSTRUCT : Outcome.PARSE_ERROR;
            } else {
                outcome = Outcome.RENDER_BUG;
            }
            String caret;
            try { caret = e.caretString(); } catch (RuntimeException ignored) { caret = ""; }
            return new RenderResult("", new Diagnostics(outcome, stage,
                e.getMessage() == null ? "render failed" : e.getMessage(),
                lineOf(latex, e.offset()), causeCrumb(e), e.offset(),
                caret == null ? "" : caret));
        } catch (StackOverflowError | RuntimeException e) {
            // Belt-and-suspenders: parse-stage non-typed failures (the fd6bd568 lane)
            // and anything the L6.1 boundary did not see. Never escapes.
            return new RenderResult("", new Diagnostics(Outcome.RENDER_BUG, stage,
                "internal render failure", -1,
                e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                -1, ""));
        }
    }

    /** 1-based line of {@code offset} in {@code source}, or -1 when unknown. */
    private static int lineOf(String source, int offset) {
        if (source == null || offset < 0 || offset > source.length()) { return -1; }
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') { line++; }
        }
        return line;
    }

    /** The throwable-crumb for Diagnostics.detail: cause type+message, or "". */
    private static String causeCrumb(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null) { return ""; }
        return cause.getClass().getSimpleName()
            + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    public static String render(String latex) {
        return render(latex, RenderOptions.defaults());
    }

    /**
     * Render a LaTeX math expression to a self-contained SVG document, applying
     * the given styling options.
     *
     * <p>The three knobs thread through the pipeline without touching geometry:
     * <ul>
     *   <li>{@link RenderOptions#scale()} folds into the effective font size, so
     *       the whole formula scales proportionally as crisp vector output;</li>
     *   <li>{@link RenderOptions#mathStyle()} seeds the top-level layout style;</li>
     *   <li>{@link RenderOptions#color()} becomes the emitter's {@code fill}
     *       value (a value, never a new element/attribute).</li>
     * </ul>
     *
     * @param latex the LaTeX math source (without surrounding {@code $} delimiters)
     * @param opts  the styling options (never {@code null})
     * @return the SVG document
     */
    public static String render(String latex, RenderOptions opts) {
        java.util.Objects.requireNonNull(opts, "opts");
        // L8: preset macros expand before parsing. A top-level \lx wrapper below may
        // override the STYLING options, but macros are not part of the \lx
        // sub-language, so the caller's notation pack applies inside the body too.
        MathNode node = MathParser.parse(latex, opts.macros());

        // A top-level \lx wrapper carries its own validated RenderOptions in the
        // source; that style is what we apply (it overrides `opts`), and we render
        // the wrapped body under a context seeded from it.
        //
        // The wrapper's fx.* (effects) and semantics (intent / concept / a11y.label
        // / data.*) are validated and stored on the node but are DELIBERATELY NOT
        // emitted into the SVG here — they ride the trusted container built by
        // renderStyledHtml, which keeps the emitter's SVG alphabet unchanged.
        RenderOptions style = opts;
        MathNode body = node;
        if (node instanceof StyledMath sm) {
            style = sm.style();
            body = sm.body();
        }

        final RenderOptions fStyle = style;
        final MathNode fBody = body;
        return containRender(() -> {
            SfntFont font = FontHolder.FONT;
            LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
                DISPLAY_FONT_SIZE * fStyle.scale(), fStyle.mathStyle(), false);
            Layout layout = LayoutEngine.layout(fBody, ctx);
            // fluid is a HOST flag (like interactiveExpansion): it reads from the
            // caller's opts even when a \lx wrapper overrides the styling, and it is
            // NOT part of the \lx sub-language — an author can never turn it on.
            return SvgEmitter.emit(layout, font, describe(fBody), fStyle.color(), opts.fluid());
        });
    }

    /**
     * Render <em>inline</em> math — for a formula embedded in a line of running prose
     * ({@code $…$}). Same as {@link #render(String)} but in text (inline) math style:
     * smaller fractions and scripts, big-operator limits set beside rather than stacked,
     * so the math sits comfortably on a text line instead of pushing it open. Equivalent
     * to {@code render(latex, RenderOptions.defaults().inline())}. The SVG stays inside the
     * same minimal {@code svg/g/path/rect} alphabet.
     *
     * @param latex the LaTeX math source
     * @return a self-contained inline-styled SVG document
     */
    public static String renderInline(String latex) {
        return renderInlineResult(latex).svg();
    }

    /**
     * Inline render WITH baseline metrics (L7, plan lattex-inline-baseline): the
     * same SVG {@link #renderInline(String)} returns — one pipeline, byte-identical
     * by construction — plus the depth/height of the ink relative to the baseline,
     * in em of the formula's own font size. The embedding layer applies
     * {@code vertical-align: -depthEm em} on ITS wrapper so inline math sits ON the
     * prose baseline instead of floating above it (no style attribute is baked into
     * the SVG — sanitizer-safe, and composes with fx enter-transforms).
     */
    public static InlineSvgResult renderInlineResult(String latex) {
        RenderOptions opts = RenderOptions.defaults().inline();
        MathNode node = MathParser.parse(latex);
        RenderOptions style = node instanceof StyledMath sm ? sm.style() : opts;
        MathNode body = node instanceof StyledMath sm ? sm.body() : node;
        final RenderOptions fStyle = style;
        return containRender(() -> {
            SfntFont font = FontHolder.FONT;
            double fontSize = DISPLAY_FONT_SIZE * fStyle.scale();
            LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
                fontSize, fStyle.mathStyle(), false);
            Layout layout = LayoutEngine.layout(body, ctx);
            String svg = SvgEmitter.emit(layout, font, describe(body), fStyle.color());
            double depthEm = Math.max(0.0, layout.maxY()) / fontSize;
            double heightEm = Math.max(0.0, -layout.minY()) / fontSize;
            return new InlineSvgResult(svg, depthEm, heightEm);
        });
    }

    /**
     * Render a LaTeX math expression to an embeddable {@link MathFragment} — the
     * INNER markup plus box metrics, for a consumer that composes the math inline
     * on a shared baseline (a diagram renderer drawing math-in-labels).
     *
     * <p>Unlike {@link #render(String)} / {@link #renderInline(String)} — which
     * return a full self-contained {@code <svg>} document — this returns the bare
     * positioned {@code <g>/<path>/<rect>} markup (no {@code <svg>} wrapper, no
     * viewBox, no aria), re-based so the fragment's local origin {@code (0,0)} is
     * the LEFT END OF THE BASELINE: x=0 at the left ink edge, y=0 on the baseline
     * (in SVG y-down space, ink above the baseline has negative y). The consumer
     * drops {@link MathFragment#innerSvg()} inside its own {@code <g transform>} at
     * the target baseline point and advances by {@link MathFragment#widthPx()}.
     *
     * <p>The fragment paints glyphs and rules IDENTICALLY to {@link #render(String)}
     * — both go through the same {@code SvgEmitter} inner-emit path, so there is no
     * forked emit. The default fill is opaque black; a per-subterm {@code \color}/
     * {@code \textcolor} override is preserved on the {@code <path>}/{@code <rect>}.
     * The metrics are the tight ink box at {@code fontSizePx}:
     * {@link MathFragment#widthPx()} is {@code layout.width()},
     * {@link MathFragment#heightPx()} the above-baseline extent, and
     * {@link MathFragment#depthPx()} the below-baseline extent.
     *
     * <p>The fragment also carries the token-identity sidecar
     * {@link MathFragment#glyphmap()} — derived from the SAME layout + font as
     * {@code innerSvg} (one emit-order producer), so a glyphmap index addresses the
     * Nth {@code <path>} of {@code innerSvg}. The indices are emit-order-based and thus
     * RE-BASE-INVARIANT: the {@code -minX} x re-base shifts the paths' positions but not
     * their order, so no index adjustment is applied. See {@link MathFragment#glyphmap()}.
     *
     * @param latex      the LaTeX math source (without surrounding {@code $} delimiters)
     * @param fontSizePx the base font size in user units (px at 1:1); must be finite,
     *                   strictly positive, and at most {@link #MAX_FRAGMENT_FONT_SIZE}
     *                   (plan cfd12523). A non-finite (NaN/Infinity) or non-positive
     *                   size would carry through into the metrics and output as garbage,
     *                   so it is rejected loud.
     * @return the laid-out {@link MathFragment}
     * @throws IllegalArgumentException if {@code fontSizePx} is not a finite value in
     *         {@code (0, MAX_FRAGMENT_FONT_SIZE]}
     * @throws com.lattex.parse.MathSyntaxException if {@code latex} does not parse —
     *         same error behavior as {@link #render(String)}; the consumer catches it
     */
    public static MathFragment renderFragment(String latex, double fontSizePx) {
        if (!Double.isFinite(fontSizePx) || fontSizePx <= 0.0 || fontSizePx > MAX_FRAGMENT_FONT_SIZE) {
            throw new IllegalArgumentException(
                "fontSizePx must be a finite value in (0, " + MAX_FRAGMENT_FONT_SIZE
                    + "]; got: " + fontSizePx);
        }
        MathNode node = MathParser.parse(latex);
        // A top-level \lx wrapper is transparent here: render the body; the wrapper's
        // container metadata rides the consumer's own wrapper, not the fragment.
        MathNode body = node instanceof StyledMath sm ? sm.body() : node;

        final MathNode fBody = body;
        return containRender(() -> {
        SfntFont font = FontHolder.FONT;
        LayoutContext ctx = new LayoutContext(font, font.mathConstants(), fontSizePx);
        Layout layout = LayoutEngine.layout(fBody, ctx);

        String inner = SvgEmitter.emitFragment(layout, font);
        // Token-identity sidecar from the SAME layout + font that produced `inner`, so a
        // glyphmap index is the Nth <path> of `inner` by construction. emitFragment
        // re-bases x by -minX, but the indices are emit-ORDER-based (index N = Nth path),
        // so the re-base shifts positions, not order — no index adjustment needed.
        String glyphmap = SvgEmitter.glyphmap(layout, font);
        // The baseline is y=0 in layout space; above-baseline ink has negative y
        // (SVG y-down), below-baseline positive. So heightPx (above) is the magnitude
        // of minY and depthPx (below) is maxY — clamped so an all-below/all-above box
        // never yields a negative extent.
        double width = layout.width();
        double height = Math.max(0.0, -layout.minY());
        double depth = Math.max(0.0, layout.maxY());
        // Presentation-MathML of the SAME parsed body the SVG was laid out from — one
        // parse, two serializations, so the visual and assistive surfaces can never
        // drift (plan lattex-mathfragment-mathml; the Sirentide consumer contract).
        // Serializing `body` (post-\lx-unwrap) rather than re-parsing `latex` is
        // deliberate and load-bearing: a re-parse would re-include the wrapper AND
        // reopen the one-source-no-drift property.
        return new MathFragment(inner, width, height, depth, glyphmap, mathmlOrEmpty(fBody));
        });
    }

    /**
     * Per-fragment fail-soft MathML: serializes the already-parsed node, or returns
     * {@code ""} on ANY failure — a MathML problem must never blank or throw through a
     * label whose SVG rendered fine (the math-bridge's Optional.empty degrade contract,
     * consumer requirement #2 of plan lattex-mathfragment-mathml).
     */
    private static String mathmlOrEmpty(MathNode body) {
        try {
            return "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
                + toMathML(body) + "</math>";
        } catch (RuntimeException | StackOverflowError e) {
            return "";
        }
    }

    /**
     * Render an {@code \lx}-annotated formula to an HTML fragment: the styled inner
     * {@code <svg>} wrapped in a trusted {@code <span class="lx-math">} container
     * that carries the macro's effect and semantic annotations as {@code data-lx-*}
     * (and {@code aria-label}) attributes.
     *
     * <p><strong>Containment contract.</strong> All interactivity/semantics/effect
     * metadata rides the CONTAINER, never the {@code <svg>}. The inner SVG stays
     * within the minimal, affordance-free alphabet exactly as {@link #render(String)}
     * produces it — this method injects nothing into it. Only the visual
     * {@code style.*} knobs (scale/color/mathStyle) reach the SVG, through the same
     * L1 render path (fill value / font size / layout style); they add no new
     * element or attribute.
     *
     * <p>For a plain (non-{@code \lx}) expression the result is the SVG wrapped in a
     * bare {@code <span class="lx-math">} with no data attributes.
     *
     * @param latex the LaTeX math source (optionally the {@code \lx[…]{…}} macro)
     * @return an HTML fragment: {@code <span class="lx-math" …>…<svg>…</svg></span>}
     */
    public static String renderStyledHtml(String latex) {
        return renderStyledHtml(latex, RenderOptions.defaults());
    }

    /**
     * Render an {@code \lx}-annotated formula to an HTML fragment, honoring the HOST's
     * {@link RenderOptions#interactiveExpansion()} gate. Identical to
     * {@link #renderStyledHtml(String)} EXCEPT that when the host has enabled interactive
     * expansion AND the equation opted in with an {@code fx.*=unfold} directive, a bounded
     * {@code \sum} is pre-rendered into a hidden SIBLING {@code <svg>} payload inside the
     * same {@code <span>} — on click the runtime swaps the collapsed sum for the expanded
     * form (hiding the outgoing immediately, then fading the incoming one in; a swap with
     * a fade-in, not a simultaneous cross-fade) — and the container is stamped with the
     * {@code data-lx-fx-expand="<term-count>"} marker.
     *
     * <p><strong>Double gate (opt-out by default).</strong> The expansion pass runs iff
     * {@code opts.interactiveExpansion()} is {@code true} (host flag, default OFF) AND the
     * source carries an {@code fx.*=unfold} effect (author opt-in). With the flag off, an
     * {@code fx.*=unfold} equation degrades INERT — the sum typesets normally, no payload,
     * no marker, byte-identical to a page that never asked for unfold. With the flag on, a
     * plain equation with no unfold directive is byte-identical to today. The visual
     * styling ({@code style.*}) still comes from the equation's own {@code \lx} wrapper;
     * the flag is orthogonal.
     *
     * @param latex the LaTeX math source (optionally the {@code \lx[…]{…}} macro)
     * @param opts  the host render options carrying the {@link RenderOptions#interactiveExpansion()} gate
     * @return an HTML fragment: {@code <span class="lx-math" …>…<svg>…</svg>[payload]</span>}
     */
    public static String renderStyledHtml(String latex, RenderOptions opts) {
        java.util.Objects.requireNonNull(opts, "opts");
        // Honor the host options with the SAME semantics as render(latex, opts): preset
        // macros expand before parsing (RenderOptions' public contract), and when there
        // is no top-level \lx wrapper the host's scale/color/mathStyle apply. A source
        // \lx wrapper still overrides the STYLING (macros are not part of the \lx
        // sub-language, so the notation pack applies inside the body regardless).
        MathNode node = MathParser.parse(latex, opts.macros());
        EffectSpec fx = node instanceof StyledMath sm ? sm.fx() : EffectSpec.none();
        Semantics sem = node instanceof StyledMath sm ? sm.sem() : Semantics.none();

        // Lay out once, and reuse the layout for BOTH the SVG and the glyphmap — the
        // sidecar's path indices must line up with this exact emission.
        RenderOptions style = node instanceof StyledMath sm ? sm.style() : opts;
        MathNode body = node instanceof StyledMath sm ? sm.body() : node;
        return containRender(() -> {
            SfntFont font = FontHolder.FONT;
            LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
                DISPLAY_FONT_SIZE * style.scale(), style.mathStyle(), false);
            Layout layout = LayoutEngine.layout(body, ctx);
            // fluid rides the HOST opts (never the \lx wrapper) and lands on the <svg>
            // element only — the container's attribute surface is untouched.
            String svg = SvgEmitter.emit(layout, font, describe(body), style.color(), opts.fluid());

            // The `thread` and `cancel` effects both read data-lx-glyphmap (thread lights
            // every occurrence of a hovered token; cancel strikes+puffs a code point that
            // occurs exactly twice). Stamp the sidecar only when one of them is present — it
            // is inert (and wasted bytes) otherwise. Value is [0-9a-f:,;] only, container-safe.
            String glyphmap = usesGlyphmap(fx)
                ? SvgEmitter.glyphmap(layout, font) : "";
            String groupmap = precedenceGroupmap(fx, layout, font);

            // fx.*=unfold — the DOUBLE-GATED numeric-expansion pass. Runs only when the host
            // enabled the flag AND the author opted in; otherwise this whole block is skipped
            // and the sum typesets inert (no payload, no marker). See RenderOptions#interactiveExpansion.
            String payload = "";
            String expandMarker = "";
            if (opts.interactiveExpansion() && usesUnfold(fx)) {
                // FAIL-INERT BOUNDARY (review lattex-308 blocker 1): the unfold payload is
                // OPTIONAL decoration — arming it must never break an already-valid collapsed
                // formula. If the expanded form cannot be rendered for any bounded reason (e.g.
                // its SVG exceeds SvgEmitter's cap, which the collapsed sum does not hit), we
                // degrade INERT: keep the collapsed `svg` emitted above, and omit payload +
                // marker. Catch RuntimeException (covers MathSyntaxException = the cap throw)
                // but let JVM-fatal Errors (OOM, StackOverflow) propagate unswallowed.
                try {
                    java.util.Optional<SumExpansion.Result> expanded = SumExpansion.expand(body);
                    if (expanded.isPresent()) {
                        MathNode expandedBody = expanded.get().expanded();
                        LayoutContext ectx = new LayoutContext(font, font.mathConstants(),
                            DISPLAY_FONT_SIZE * style.scale(), style.mathStyle(), false);
                        Layout elayout = LayoutEngine.layout(expandedBody, ectx);
                        // The payload rides the SAME LayoutEngine.layout + SvgEmitter.emit path,
                        // so it is independently within the emitter's minimal SVG alphabet. It is
                        // hidden in a wrapper <span> (never an svg attribute S8 would reject), so
                        // the pre-rendered svg stays pristine.
                        // The expanded payload is display math in the same container:
                        // it follows the host's fluid flag so the click-swap keeps the
                        // same responsive sizing as the collapsed form.
                        String esvg = SvgEmitter.emit(elayout, font, describe(expandedBody),
                            style.color(), opts.fluid());
                        payload = "<span class=\"lx-fx-expanded\" hidden>" + esvg + "</span>";
                        expandMarker = Integer.toString(expanded.get().termCount());
                    }
                } catch (RuntimeException degradeInert) {
                    // Optional payload unrenderable → no payload, no marker; the collapsed
                    // formula still typesets. Decoration never breaks the math.
                    payload = "";
                    expandMarker = "";
                }
            }
            return openTag(fx, sem, glyphmap, groupmap, expandMarker) + svg + payload + "</span>";
        });
    }

    /**
     * Whether {@code fx} carries an {@code unfold} effect on any trigger — the per-equation
     * author opt-in half of the double gate for the numeric-expansion pass. The host-flag
     * half is {@link RenderOptions#interactiveExpansion()}.
     */
    private static boolean usesUnfold(EffectSpec fx) {
        return fx.effects().containsValue(Effect.UNFOLD);
    }

    /**
     * Whether {@code fx} needs the {@code data-lx-glyphmap} token-identity sidecar: the
     * {@code thread} effect (hover-to-thread) and the {@code cancel} effect (exactly-twice
     * strike-and-puff) both read it. One producer for the stamping gate so the two semantic
     * effects can never drift on whether the sidecar rides the container.
     */
    private static boolean usesGlyphmap(EffectSpec fx) {
        return fx.effects().containsValue(Effect.THREAD)
            || fx.effects().containsValue(Effect.CANCEL);
    }

    /**
     * The {@code data-lx-groupmap} precedence sidecar for {@code fx}, or {@code ""}. Stamped
     * only when a {@code precedence} effect is present; the emitter is itself
     * whole-expression fail-honest (empty unless there is genuine fence-nesting variation),
     * so an ambiguous/flat expression yields {@code ""} and the effect degrades to static.
     */
    private static String precedenceGroupmap(EffectSpec fx, Layout layout, SfntFont font) {
        return fx.effects().containsValue(Effect.PRECEDENCE)
            ? SvgEmitter.groupmap(layout, font) : "";
    }

    /**
     * Builds the opening {@code <span class="lx-math" data-lx-…>} tag. Every stamped
     * value was validated + reduced at parse time (intent/concept/data.* are
     * {@code [a-z][a-z0-9_]*} identifiers, effects are a closed enum vocabulary,
     * duration matches {@code \d{1,5}ms}); the free-text a11y label and the plottable
     * {@code data-lx-graph-expr} are stored RAW and made legal+safe HERE via
     * {@link #htmlAttrEscape} (the shared output-boundary legality policy + HTML-attr
     * escaping, applied exactly once), so no raw author string reaches the attribute.
     */
    private static String openTag(EffectSpec fx, Semantics sem, String glyphmap, String groupmap,
                                  String expandMarker) {
        StringBuilder sb = new StringBuilder("<span class=\"lx-math\"");
        sem.intentValue().ifPresent(v -> sb.append(" data-lx-intent=\"").append(v).append('"'));
        sem.conceptValue().ifPresent(v -> sb.append(" data-lx-concept=\"").append(v).append('"'));
        // The fx half rides the SAME stamping source as fxContainerAttrs — one
        // producer, so the two public shapes cannot drift.
        sb.append(fxAttrs(fx));
        // Token-identity sidecar for the `thread` effect (renderer-derived, [0-9a-f:,;]).
        if (!glyphmap.isEmpty()) {
            sb.append(" data-lx-glyphmap=\"").append(glyphmap).append('"');
        }
        // Precedence-group sidecar for the `precedence` effect (renderer-derived, [0-9:,;]).
        if (!groupmap.isEmpty()) {
            sb.append(" data-lx-groupmap=\"").append(groupmap).append('"');
        }
        // Unfold term-count marker (renderer-derived, [0-9]+) — stamped only when the
        // double-gated expansion pass produced a payload. Pairs the interaction name
        // (data-lx-fx-*=unfold) with the pre-rendered sibling svg; reserves the future
        // staggered-sprout addressing.
        if (!expandMarker.isEmpty()) {
            sb.append(" data-lx-fx-expand=\"").append(expandMarker).append('"');
        }
        // data.* attributes (keys already identifier-validated) — iterated in sorted
        // key order so the generated HTML is deterministic regardless of the source
        // map's iteration order (a HashMap/Map.of view is randomized per JVM run).
        // Values are stored RAW (plan cfd12523): the shared output-boundary legality
        // policy + HTML-attr escaping are applied HERE, exactly once. Identifier
        // values are unchanged by escaping; the free-text graph-expr is made legal+safe.
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(sem.data()).entrySet()) {
            sb.append(" data-lx-").append(e.getKey()).append("=\"")
              .append(htmlAttrEscape(e.getValue())).append('"');
        }
        // a11y label — stored RAW; legality policy + HTML-attr escaping applied here.
        sem.a11yLabelValue().ifPresent(v -> sb.append(" aria-label=\"")
            .append(htmlAttrEscape(v)).append('"'));
        return sb.append('>').toString();
    }

    /**
     * The author's validated {@code \lx[fx.*]} annotations for {@code latex} as a
     * container-attribute string — the <strong>fx-stamp seam</strong> for a consumer
     * that owns its <em>own</em> wrapper (the container contract's pinned Producer API;
     * Stafficy's {@code /docs} math seam is the reference consumer).
     *
     * <p>Returns {@code ""} when the source carries no top-level {@code \lx[fx.*]}
     * annotations, else a <em>leading-space-prefixed</em> run of only the five
     * {@code data-lx-fx-*} attributes (enter/hover/click/duration/glow-color, in that
     * order) — never {@code data-lx-intent}/{@code concept}/{@code data.*}/
     * {@code aria-*}, which ride {@link #renderStyledHtml}'s own container instead.
     * Every value is parse-time validated (effects are the closed {@code Effect}
     * vocabulary, duration matches {@code \d{1,5}ms}, glow-color is
     * {@code currentColor} or a canonical hex literal), so no raw author string
     * reaches an attribute.
     *
     * @param latex the LaTeX math source (optionally the {@code \lx[…]{…}} macro)
     * @return {@code ""} or a leading-space-prefixed {@code data-lx-fx-*} attribute run
     * @throws com.lattex.parse.MathSyntaxException if {@code latex} does not parse
     */
    /**
     * @deprecated Parse-only: it structurally cannot carry the layout-derived
     * {@code data-lx-glyphmap} sidecar, which is why the {@code thread} effect stayed inert on
     * any consumer using the split render+attrs seam. Use {@link #tryRenderMath(String)} — the
     * render-coupled record computes SVG and container attributes from the SAME parse+layout,
     * so glyphmap indices can never desync from the emitted paths. Kept one minor version
     * (seam sign-off, lattex room seq 165); removal after 0.7.0.
     */
    @Deprecated
    public static String fxContainerAttrs(String latex) {
        MathNode node = MathParser.parse(latex);
        EffectSpec fx = node instanceof StyledMath sm ? sm.fx() : EffectSpec.none();
        return fxAttrs(fx);
    }

    /**
     * The render-coupled seam record: the self-contained SVG document plus the container
     * attributes derived from the SAME parse+layout, as a key→value map. The consumer (the
     * Stafficy {@code /docs} markdown converter) stamps each entry onto ITS OWN wrapper
     * element; the key set is pinned by the container-output contract (the five
     * {@code data-lx-fx-*} plus {@code data-lx-glyphmap}), so nothing re-encodes and the
     * reflective bridge stays name-agnostic. Values are parse-validated (effects: closed
     * vocabulary; duration: {@code \d{1,5}ms}; glow: {@code currentColor|#rrggbb}; glyphmap:
     * {@code [0-9a-f:,;]} only).
     *
     * @param svg            the SVG document, byte-identical to {@link #render(String)}
     * @param containerAttrs immutable attribute map (empty for a plain, effect-free expression)
     */
    public record RenderedMath(String svg, Map<String, String> containerAttrs) {
        /**
         * Enforces the javadoc's promised invariants (plan cfd12523): {@code svg} is
         * non-null and {@code containerAttrs} is a defensively-copied immutable map, so a
         * caller cannot observe {@code null} or mutate the pair's attributes after the fact.
         */
        public RenderedMath {
            java.util.Objects.requireNonNull(svg, "svg");
            // Order-PRESERVING defensive copy: the historical stamp order (enter/hover/
            // click, duration, glow, glyphmap) is load-bearing — the deprecated string
            // seam is byte-identical to this map joined in iteration order. Map.copyOf
            // would be immutable but randomize iteration, breaking that equivalence, so
            // copy through a LinkedHashMap and wrap unmodifiable.
            containerAttrs = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(java.util.Objects.requireNonNull(containerAttrs, "containerAttrs")));
        }
    }

    /**
     * Render a LaTeX math expression AND its container attributes from one parse+layout —
     * the sanctioned producer path for a consumer that builds its own wrapper element
     * (seam sign-off: lattex room seq 163→165). The {@code data-lx-glyphmap} token-identity
     * sidecar (present only when a {@code thread} effect is authored) indexes the returned
     * SVG's {@code <path>}s in emit order; because both halves come from the same
     * {@link Layout}, the indices cannot desync — the drift a separate attrs call would risk.
     *
     * <p>Failure semantics mirror the consumer-side {@code tryRender}: ANY failure (parse
     * error, layout overflow, emit fault) yields {@link java.util.Optional#empty()} so the
     * caller falls back to the plain source text — fail-inert, never a half-rendered pair.
     *
     * @param latex the LaTeX math source (optionally the {@code \lx[…]{…}} macro)
     * @return the render-coupled pair, or empty on any failure
     */
    public static java.util.Optional<RenderedMath> tryRenderMath(String latex) {
        try {
            MathNode node = MathParser.parse(latex);
            EffectSpec fx = node instanceof StyledMath sm ? sm.fx() : EffectSpec.none();
            RenderOptions style = node instanceof StyledMath sm ? sm.style() : RenderOptions.defaults();
            MathNode body = node instanceof StyledMath sm ? sm.body() : node;
            SfntFont font = FontHolder.FONT;
            LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
                DISPLAY_FONT_SIZE * style.scale(), style.mathStyle(), false);
            Layout layout = LayoutEngine.layout(body, ctx);
            String svg = SvgEmitter.emit(layout, font, describe(body), style.color());
            String glyphmap = fx.effects().containsValue(Effect.THREAD)
                ? SvgEmitter.glyphmap(layout, font) : "";
            String groupmap = precedenceGroupmap(fx, layout, font);
            return java.util.Optional.of(new RenderedMath(svg, containerAttrMap(fx, glyphmap, groupmap)));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * THE single container-attribute producer: {@link #fxAttrs} (the string form
     * {@link #openTag} and the deprecated seam join) derives from this map, so the record
     * form and the string form cannot drift. Insertion order is the historical stamp order
     * (enter/hover/click, duration, glow, glyphmap) so the joined string stays byte-identical.
     */
    private static Map<String, String> containerAttrMap(EffectSpec fx, String glyphmap, String groupmap) {
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        for (Trigger t : Trigger.values()) {
            fx.effect(t).ifPresent(e ->
                attrs.put("data-lx-fx-" + t.name().toLowerCase(Locale.ROOT), e.token()));
        }
        fx.durationValue().ifPresent(v -> attrs.put("data-lx-fx-duration", v));
        fx.glowColorValue().ifPresent(c -> attrs.put("data-lx-fx-glow-color", c.svgValue()));
        if (glyphmap != null && !glyphmap.isEmpty()) {
            attrs.put("data-lx-glyphmap", glyphmap);
        }
        if (groupmap != null && !groupmap.isEmpty()) {
            attrs.put("data-lx-groupmap", groupmap);
        }
        return java.util.Collections.unmodifiableMap(attrs);
    }

    /**
     * The shared fx-attribute stamping source consumed by BOTH {@link #openTag} and
     * {@link #fxContainerAttrs} — one producer, so the wrapper form and the seam form
     * cannot drift. Emits only the container contract's five {@code data-lx-fx-*}
     * attributes, each value validated at parse time.
     */
    private static String fxAttrs(EffectSpec fx) {
        // Derived from containerAttrMap — the ONE producer (record seam + string form share
        // it, so they cannot drift). Values are parse-validated; the glow colour's svgValue()
        // is currentColor or a canonical #rrggbb literal — a safe attribute VALUE.
        // Empty sidecars: fxAttrs stamps only the fx-* / duration / glow attrs; openTag
        // stamps the glyphmap/groupmap sidecars separately (and the deprecated seam never
        // carried them).
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : containerAttrMap(fx, "", "").entrySet()) {
            sb.append(' ').append(e.getKey()).append("=\"").append(e.getValue()).append('"');
        }
        return sb.toString();
    }

    /** A plain-language accessibility label for a math tree. */
    static String describe(MathNode node) {
        return switch (node) {
            case Atom atom -> Character.toString(atom.codePoint());
            case MathNode.MiddleDelim(var delimCp) -> Character.toString(delimCp);
            case MathList(var items) -> {
                StringBuilder sb = new StringBuilder();
                for (MathNode item : items) {
                    String part = describe(item);
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(part);
                }
                yield sb.toString();
            }
            case SupSub(var base, var sup, var sub) -> {
                String b = describe(base);
                if (sub == null) {
                    String exp = describe(sup);
                    yield exp.equals("2") ? b + " squared" : b + " to the power of " + exp;
                }
                if (sup == null) {
                    yield b + " sub " + describe(sub);
                }
                yield b + " sub " + describe(sub) + " to the power of " + describe(sup);
            }
            case Fraction f -> f.hasRule()
                ? "the fraction " + describe(f.numerator()) + " over " + describe(f.denominator())
                : describe(f.numerator()) + " choose " + describe(f.denominator());
            case Radical(var radicand, var index) -> index == null
                ? "the square root of " + describe(radicand)
                : "the " + describe(index) + "th root of " + describe(radicand);
            case Spacing _ -> "";
            case MathNode.Colored c -> describe(c.body()); // color is presentation; speak the content
            case MathNode.Boxed bx -> "boxed " + describe(bx.body());
            case MathNode.Cancel c -> switch (c.kind()) {
                case CANCELTO -> describe(c.body()) + " cancels to " + describe(c.to());
                default -> "cancel " + describe(c.body());
            };
            case MathNode.Tagged t -> describe(t.body()) + ", tagged " + describe(t.label());
            case Phantom _ -> ""; // invisible: reserves space, reads as nothing
            case BigOperator(var op, var lower, var upper, _) -> {
                StringBuilder sb = new StringBuilder(operatorName(op.codePoint()));
                if (lower != null) {
                    sb.append(" from ").append(describe(lower));
                }
                if (upper != null) {
                    sb.append(" to ").append(describe(upper));
                }
                sb.append(" of");
                yield sb.toString();
            }
            case Fenced(var leftDelim, var body, var rightDelim) -> {
                String inner = describe(body);
                String open = leftDelim == Fenced.NULL_DELIMITER ? "" : delimiterName(leftDelim) + " ";
                String close = rightDelim == Fenced.NULL_DELIMITER ? "" : " " + delimiterName(rightDelim);
                yield (open + inner + close).strip();
            }
            case MathNode.SizedDelim sd ->
                sd.delimCp() == Fenced.NULL_DELIMITER ? "" : delimiterName(sd.delimCp());
            case Accent(var command, var base, _, _, _) ->
                accentName(command) + " " + describe(base);
            case OperatorName(var name, _) -> name;
            case TextRun(var text, _) -> text;
            case Matrix m -> describeMatrix(m);
            case MathNode.BorderMatrix bm -> describeBorderMatrix(bm);
            case MathNode.Stack st -> {
                String base = describe(st.base());
                yield switch (st.kind()) {
                    case UNDERBRACE -> st.below() == null ? base + " under a brace"
                        : base + " under a brace, labelled " + describe(st.below());
                    case OVERBRACE -> st.above() == null ? base + " under an overbrace"
                        : base + " with an overbrace labelled " + describe(st.above());
                    case STACKREL, OVERSET -> describe(st.above()) + " over " + base;
                    case UNDERSET -> base + " under " + describe(st.below());
                };
            }
            case MathNode.XArrow xa -> {
                String dir = xa.kind().a11yName();
                yield xa.below() == null
                    ? dir + " arrow labelled " + describe(xa.above())
                    : dir + " arrow labelled " + describe(xa.above())
                        + " over " + describe(xa.below());
            }
            case MathNode.CdArrow cd -> {
                StringBuilder sb = new StringBuilder(cd.kind().a11yName());
                if (cd.labelA() != null) {
                    sb.append(" labelled ").append(describe(cd.labelA()));
                }
                if (cd.labelB() != null) {
                    sb.append(cd.labelA() == null ? " labelled " : " and ").append(describe(cd.labelB()));
                }
                yield sb.toString();
            }
            case StyledMath sm -> describe(sm.body()); // the wrapper is transparent to a11y
            case MathNode.StyleSwitch sw -> describe(sw.body()); // style is invisible to a11y prose
        };
    }

    // ------------------------------------------------------------------
    // MathML — a Presentation-MathML serialization of the SAME parse tree that
    // describe() walks for the aria label and the renderer walks for SVG. Assistive
    // tech gets navigable structure (not a flat string), plus an interop surface —
    // still zero-dependency, pure Java, from one shared traversal.
    // ------------------------------------------------------------------

    /**
     * Renders {@code latex} to a self-contained Presentation-MathML {@code <math>}
     * element, built from the same parse tree as {@link #render(String)} so the
     * structure matches the SVG. The result is well-formed XML (text content escaped).
     *
     * @param latex a LaTeX math expression
     * @return {@code <math xmlns="http://www.w3.org/1998/Math/MathML">…</math>}
     * @throws com.lattex.parse.MathSyntaxException if {@code latex} does not parse
     */
    public static String toMathML(String latex) {
        return "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
            + toMathML(MathParser.parse(latex)) + "</math>";
    }

    /** Serializes one node to Presentation-MathML markup (no {@code <math>} wrapper). */
    private static String toMathML(MathNode node) {
        return switch (node) {
            case Atom atom -> atomMathML(atom);
            // \middle delimiter: a stretchy operator, exactly how MathML models it.
            case MathNode.MiddleDelim(var delimCp) ->
                "<mo stretchy=\"true\">" + xmlEscape(Character.toString(delimCp)) + "</mo>";
            case MathList(var items) -> {
                StringBuilder sb = new StringBuilder("<mrow>");
                for (MathNode item : items) {
                    sb.append(toMathML(item));
                }
                yield sb.append("</mrow>").toString();
            }
            case SupSub(var base, var sup, var sub) -> {
                String b = toMathML(base);
                if (sub != null && sup != null) {
                    yield "<msubsup>" + b + toMathML(sub) + toMathML(sup) + "</msubsup>";
                }
                if (sub != null) {
                    yield "<msub>" + b + toMathML(sub) + "</msub>";
                }
                yield "<msup>" + b + toMathML(sup) + "</msup>";
            }
            case Fraction f -> (f.hasRule()
                    ? "<mfrac>"
                    : "<mfrac linethickness=\"0\">")
                + toMathML(f.numerator()) + toMathML(f.denominator()) + "</mfrac>";
            case Radical(var radicand, var index) -> index == null
                ? "<msqrt>" + toMathML(radicand) + "</msqrt>"
                : "<mroot>" + toMathML(radicand) + toMathML(index) + "</mroot>";
            case Spacing sp -> "<mspace width=\"" + spaceEm(sp) + "em\"/>";
            case MathNode.Colored c ->
                "<mstyle mathcolor=\"" + xmlEscape(c.color().svgValue()) + "\">"
                    + toMathML(c.body()) + "</mstyle>";
            case MathNode.Boxed bx ->
                "<menclose notation=\"box\">" + toMathML(bx.body()) + "</menclose>";
            case MathNode.Cancel c -> switch (c.kind()) {
                case CANCEL -> "<menclose notation=\"updiagonalstrike\">"
                    + toMathML(c.body()) + "</menclose>";
                case BCANCEL -> "<menclose notation=\"downdiagonalstrike\">"
                    + toMathML(c.body()) + "</menclose>";
                case XCANCEL -> "<menclose notation=\"updiagonalstrike downdiagonalstrike\">"
                    + toMathML(c.body()) + "</menclose>";
                // \cancelto: MathML 4 names notation="northeastarrow" as the recommended
                // encoding for TeX \cancelto (https://www.w3.org/TR/mathml4/#presm_menclose).
                // The target value rides as a superscript on the arrowed body, so the
                // "cancels to <value>" semantics stay accessible to a MathML consumer.
                case CANCELTO -> "<msup><menclose notation=\"northeastarrow\">"
                    + toMathML(c.body()) + "</menclose>" + toMathML(c.to()) + "</msup>";
            };
            case MathNode.Tagged t ->
                "<mrow>" + toMathML(t.body()) + "<mspace width=\"1em\"/><mo>(</mo>"
                    + toMathML(t.label()) + "<mo>)</mo></mrow>";
            case Phantom p -> "<mphantom>" + toMathML(p.content()) + "</mphantom>";
            case BigOperator(var op, var lower, var upper, var limits) -> {
                String o = mo(op.codePoint());
                // \nolimits (e.g. an inline integral) sets scripts to the SIDE —
                // msub/msup/msubsup — matching the SVG; otherwise above/below.
                boolean beside = limits == MathNode.LimitsMode.NOLIMITS;
                if (lower != null && upper != null) {
                    yield (beside ? "<msubsup>" : "<munderover>") + o
                        + toMathML(lower) + toMathML(upper) + (beside ? "</msubsup>" : "</munderover>");
                }
                if (lower != null) {
                    yield (beside ? "<msub>" : "<munder>") + o + toMathML(lower)
                        + (beside ? "</msub>" : "</munder>");
                }
                if (upper != null) {
                    yield (beside ? "<msup>" : "<mover>") + o + toMathML(upper)
                        + (beside ? "</msup>" : "</mover>");
                }
                yield o;
            }
            case Fenced(var leftDelim, var body, var rightDelim) -> {
                StringBuilder sb = new StringBuilder("<mrow>");
                if (leftDelim != Fenced.NULL_DELIMITER) {
                    sb.append(fenceMo(leftDelim));
                }
                sb.append(toMathML(body));
                if (rightDelim != Fenced.NULL_DELIMITER) {
                    sb.append(fenceMo(rightDelim));
                }
                yield sb.append("</mrow>").toString();
            }
            case MathNode.SizedDelim sd -> sd.delimCp() == Fenced.NULL_DELIMITER
                ? "<mrow/>"
                : "<mo stretchy=\"false\">" + xmlEscape(Character.toString(sd.delimCp())) + "</mo>";
            case Accent a -> {
                String acc = mo(a.accentCodePoint());
                yield a.under()
                    ? "<munder accentunder=\"true\">" + toMathML(a.base()) + acc + "</munder>"
                    : "<mover accent=\"true\">" + toMathML(a.base()) + acc + "</mover>";
            }
            case OperatorName(var name, _) -> "<mo>" + xmlEscape(name) + "</mo>";
            case TextRun(var text, _) -> "<mtext>" + xmlEscape(text) + "</mtext>";
            case Matrix m -> matrixMathML(m);
            case MathNode.BorderMatrix bm -> borderMatrixMathML(bm);
            case MathNode.Stack st -> stackMathML(st);
            case MathNode.XArrow xa -> {
                String arrow = "<mo>" + xa.kind().mathmlEntity() + "</mo>";
                yield xa.below() == null
                    ? "<mover>" + arrow + toMathML(xa.above()) + "</mover>"
                    : "<munderover>" + arrow + toMathML(xa.below()) + toMathML(xa.above()) + "</munderover>";
            }
            case MathNode.CdArrow cd -> {
                if (cd.kind() == MathNode.CdArrowKind.EMPTY) {
                    yield "<mspace width=\"1em\"/>";
                }
                String op = "<mo>" + cd.kind().mathmlEntity() + "</mo>";
                if (cd.labelA() == null && cd.labelB() == null) {
                    yield op;
                }
                // labelA over/above, labelB under/below (mirrors the XArrow serialization).
                String over = cd.labelA() == null ? "<none/>" : toMathML(cd.labelA());
                String under = cd.labelB() == null ? "<none/>" : toMathML(cd.labelB());
                yield "<munderover>" + op + under + over + "</munderover>";
            }
            case StyledMath sm -> toMathML(sm.body()); // the \lx wrapper is transparent to MathML
            case MathNode.StyleSwitch sw -> toMathML(sw.body()); // \displaystyle etc.: presentation, not structure
        };
    }

    /** An {@code <mo>} for a code point (operator/relation/punctuation). */
    private static String mo(int codePoint) {
        return "<mo>" + xmlEscape(Character.toString(codePoint)) + "</mo>";
    }

    /** A stretchy fence {@code <mo>} for a delimiter code point. */
    private static String fenceMo(int codePoint) {
        return "<mo fence=\"true\" stretchy=\"true\">"
            + xmlEscape(Character.toString(codePoint)) + "</mo>";
    }

    /** An atom's element: {@code <mn>} for digits, {@code <mi>} for letters, else {@code <mo>}. */
    private static String atomMathML(Atom atom) {
        int cp = atom.codePoint();
        String ch = xmlEscape(Character.toString(cp));
        return switch (atom.mathClass()) {
            case ORD -> {
                if (cp >= '0' && cp <= '9') {
                    yield "<mn>" + ch + "</mn>";
                }
                yield Character.isLetter(cp) ? "<mi>" + ch + "</mi>" : "<mo>" + ch + "</mo>";
            }
            case OPEN, CLOSE -> "<mo fence=\"true\">" + ch + "</mo>";
            case OP, BIN, REL, PUNCT, INNER -> "<mo>" + ch + "</mo>";
        };
    }

    /**
     * MathML {@code <mtable>} for a grid; cells walk the same tree. When the matrix
     * carries enclosing delimiters ({@code pmatrix}/{@code bmatrix}/{@code vmatrix}/…),
     * the table is wrapped in stretchy {@code <mo>} fences so a screen reader hears the
     * brackets — otherwise every kind would sound like a bare table.
     */
    private static String matrixMathML(Matrix m) {
        int cols = m.columnCount();
        StringBuilder sb = new StringBuilder();
        boolean fenced = m.leftDelim() != Fenced.NULL_DELIMITER
            || m.rightDelim() != Fenced.NULL_DELIMITER;
        if (fenced) {
            sb.append("<mrow>");
            if (m.leftDelim() != Fenced.NULL_DELIMITER) {
                sb.append(fenceMo(m.leftDelim()));
            }
        }
        sb.append("<mtable>");
        for (var row : m.rows()) {
            sb.append("<mtr>");
            for (int c = 0; c < cols; c++) {
                sb.append("<mtd>")
                  .append(c < row.size() ? toMathML(row.get(c)) : "")
                  .append("</mtd>");
            }
            sb.append("</mtr>");
        }
        sb.append("</mtable>");
        if (fenced) {
            if (m.rightDelim() != Fenced.NULL_DELIMITER) {
                sb.append(fenceMo(m.rightDelim()));
            }
            sb.append("</mrow>");
        }
        return sb.toString();
    }

    /**
     * MathML {@code <mtable>} for a {@code \bordermatrix}: a full labelled table
     * whose FIRST row is (corner, column labels) and whose subsequent rows are
     * (row label, body cells), with the body block wrapped in stretchy paren fences.
     * MathML has no bordered-matrix primitive, so the border is expressed as the
     * outer label row/column of one table — the assistive-tech reading of the layout.
     */
    private static String borderMatrixMathML(MathNode.BorderMatrix bm) {
        int cols = bm.columnCount();
        StringBuilder sb = new StringBuilder("<mtable>");
        // Header row: corner then the column labels.
        sb.append("<mtr><mtd>").append(toMathML(bm.corner())).append("</mtd>");
        for (int c = 0; c < cols; c++) {
            sb.append("<mtd>").append(toMathML(bm.columnLabels().get(c))).append("</mtd>");
        }
        sb.append("</mtr>");
        // Body rows: the row label, then the paren-fenced body cells.
        for (int r = 0; r < bm.rowCount(); r++) {
            sb.append("<mtr><mtd>").append(toMathML(bm.rowLabels().get(r))).append("</mtd>");
            for (int c = 0; c < cols; c++) {
                sb.append("<mtd>");
                if (c == 0) {
                    sb.append(fenceMo('('));
                }
                sb.append(toMathML(bm.body().get(r).get(c)));
                if (c == cols - 1) {
                    sb.append(fenceMo(')'));
                }
                sb.append("</mtd>");
            }
            sb.append("</mtr>");
        }
        return sb.append("</mtable>").toString();
    }

    /** A plain-language label for a {@code \bordermatrix}: the labels, then the body. */
    private static String describeBorderMatrix(MathNode.BorderMatrix bm) {
        int rows = bm.rowCount();
        int cols = bm.columnCount();
        StringBuilder sb = new StringBuilder("bordered matrix of ")
            .append(rows).append(" rows and ").append(cols).append(" columns");
        StringBuilder colls = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            if (c > 0) {
                colls.append(", ");
            }
            colls.append(describe(bm.columnLabels().get(c)));
        }
        if (colls.length() > 0) {
            sb.append("; column labels: ").append(colls);
        }
        for (int r = 0; r < rows; r++) {
            sb.append("; row ").append(describe(bm.rowLabels().get(r))).append(": ");
            for (int c = 0; c < cols; c++) {
                if (c > 0) {
                    sb.append(", ");
                }
                sb.append(describe(bm.body().get(r).get(c)));
            }
        }
        return sb.toString();
    }

    /** MathML for a stacked annotation (under/over-brace, overset/underset). */
    private static String stackMathML(MathNode.Stack st) {
        String base = toMathML(st.base());
        return switch (st.kind()) {
            case UNDERBRACE -> "<munder>" + base
                + (st.below() == null ? "<mo>&#9183;</mo>" : toMathML(st.below())) + "</munder>";
            case OVERBRACE -> "<mover>" + base
                + (st.above() == null ? "<mo>&#9182;</mo>" : toMathML(st.above())) + "</mover>";
            case STACKREL, OVERSET -> "<mover>" + base + toMathML(st.above()) + "</mover>";
            case UNDERSET -> "<munder>" + base + toMathML(st.below()) + "</munder>";
        };
    }

    /** Explicit-spacing width in em (18mu = 1em). */
    private static double spaceEm(Spacing sp) {
        return Math.round(sp.muWidth() / 18.0 * 1000.0) / 1000.0;
    }

    /**
     * XML-escapes text content / attribute values for MathML output. The shared
     * {@link com.lattex.parse.OutputLegality} policy runs FIRST (plan cfd12523):
     * illegal C0 controls are stripped and an unpaired surrogate fails loud, so the
     * serialized MathML is legal XML 1.0 — a raw NUL can no longer leak (LTX-06).
     * The four-metachar escaping then happens exactly once, here.
     */
    private static String xmlEscape(String raw) {
        String s = com.lattex.parse.OutputLegality.sanitize(raw);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * HTML-attribute-escapes a container attribute value (aria-label / data-lx-*).
     * The shared {@link com.lattex.parse.OutputLegality} policy runs FIRST (plan
     * cfd12523): illegal C0 controls are stripped and an unpaired surrogate fails
     * loud, so a semantic value stored RAW is made legal+safe at this single
     * boundary. Escapes the same metachar set the parser used to pre-escape
     * ({@code & < > " '}), so clean values stamp byte-identically to before.
     */
    private static String htmlAttrEscape(String raw) {
        String s = com.lattex.parse.OutputLegality.sanitize(raw);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** A plain-language label for a grid: kind, size, then each row's cells. */
    private static String describeMatrix(Matrix m) {
        int rows = m.rows().size();
        int cols = m.columnCount();
        String kind = switch (m.kind()) {
            case CASES -> "cases";
            case ARRAY -> "array";
            case ALIGN -> "aligned equations";
            case GATHER -> "gathered equations";
            case MULTLINE -> "multi-line equation";
            // Explicit residual (drift-guard): a new MatrixKind must fail to compile here
            // rather than silently describe itself as a plain "matrix".
            case MATRIX, SMALL, SUBSTACK, CD -> "matrix";
        };
        StringBuilder sb = new StringBuilder(kind)
            .append(" of ").append(rows).append(" rows and ").append(cols).append(" columns");
        for (int r = 0; r < rows; r++) {
            sb.append("; row ").append(r + 1).append(": ");
            for (int col = 0; col < cols; col++) {
                if (col > 0) {
                    sb.append(", ");
                }
                sb.append(describe(m.rows().get(r).get(col)));
            }
        }
        return sb.toString();
    }

    /** A spoken name for an accent/decoration command. */
    private static String accentName(String command) {
        return switch (command) {
            case "hat", "widehat" -> "hat over";
            case "bar" -> "bar over";
            case "vec", "overrightarrow" -> "vector";
            case "overleftarrow" -> "left arrow over";
            case "overleftrightarrow" -> "left-right arrow over";
            case "dot" -> "dot over";
            case "ddot" -> "double dot over";
            case "tilde", "widetilde" -> "tilde over";
            case "check" -> "check over";
            case "breve" -> "breve over";
            case "acute" -> "acute over";
            case "grave" -> "grave over";
            case "mathring" -> "ring over";
            case "overline" -> "line over";
            case "underline" -> "line under";
            case "overparen" -> "parenthesis over";
            case "underparen" -> "parenthesis under";
            default -> command + " over";
        };
    }

    /** A spoken name for a large-operator code point. */
    private static String operatorName(int codePoint) {
        return switch (codePoint) {
            case 0x2211 -> "the sum";
            case 0x222B -> "the integral";
            case 0x220F -> "the product";
            default -> Character.toString(codePoint);
        };
    }

    /** A spoken name for a delimiter code point. */
    private static String delimiterName(int codePoint) {
        return switch (codePoint) {
            case '(' -> "open paren";
            case ')' -> "close paren";
            case '[' -> "open bracket";
            case ']' -> "close bracket";
            case '{' -> "open brace";
            case '}' -> "close brace";
            case '|' -> "vertical bar";
            default -> Character.toString(codePoint);
        };
    }

    /**
     * The OPTIONAL LatteX fx runtime — vanilla JS, zero dependencies. It reads {@code data-lx-fx-*}
     * attributes on {@code .lx-math} wrappers and animates the {@code \lx} effects. The math renders
     * from this jar WITHOUT it; include this script (with {@link #fxStylesCss()}) only if you want
     * the effects. Bundled as a jar resource so any consumer gets it from the jar it already depends
     * on — no separately-managed asset.
     *
     * @return the {@code lattex-fx.js} runtime source
     */
    public static String fxRuntimeJs() {
        return FxRuntimeHolder.JS;
    }

    /**
     * The fx runtime JS is an immutable bundled resource; read {@code readAllBytes} once,
     * lazily, mirroring {@link FontHolder} — not on every accessor call (plan 725c1488 /
     * LTX-03). The class-init lazy holder is thread-safe and the String is immutable, so
     * the returned value is byte-identical to the per-call read.
     */
    private static final class FxRuntimeHolder {
        static final String JS = bundledResource("/com/lattex/fx/lattex-fx.js");
    }

    /**
     * The OPTIONAL styles for the fx layer — the effect {@code @keyframes} + a couple of effect
     * classes. Pair with {@link #fxRuntimeJs()}. Optional: the math needs neither.
     *
     * @return the {@code lattex-fx.css} source
     */
    public static String fxStylesCss() {
        return FxStylesHolder.CSS;
    }

    /** The fx styles CSS — same immutable-resource lazy-holder pattern as {@link FxRuntimeHolder}. */
    private static final class FxStylesHolder {
        static final String CSS = bundledResource("/com/lattex/fx/lattex-fx.css");
    }

    private static String bundledResource(String path) {
        try (java.io.InputStream in = LatteX.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("bundled LatteX resource missing: " + path);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to read bundled resource " + path, e);
        }
    }
}
