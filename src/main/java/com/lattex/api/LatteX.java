package com.lattex.api;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathNode;
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
        MathNode node = MathParser.parse(latex);

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

        SfntFont font = FontHolder.FONT;
        LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
            DISPLAY_FONT_SIZE * style.scale(), style.mathStyle(), false);
        Layout layout = LayoutEngine.layout(body, ctx);
        return SvgEmitter.emit(layout, font, describe(body), style.color().svgValue());
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
        return render(latex, RenderOptions.defaults().inline());
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
     * @param fontSizePx the base font size in user units (px at 1:1)
     * @return the laid-out {@link MathFragment}
     * @throws com.lattex.parse.MathSyntaxException if {@code latex} does not parse —
     *         same error behavior as {@link #render(String)}; the consumer catches it
     */
    public static MathFragment renderFragment(String latex, double fontSizePx) {
        MathNode node = MathParser.parse(latex);
        // A top-level \lx wrapper is transparent here: render the body; the wrapper's
        // container metadata rides the consumer's own wrapper, not the fragment.
        MathNode body = node instanceof StyledMath sm ? sm.body() : node;

        SfntFont font = FontHolder.FONT;
        LayoutContext ctx = new LayoutContext(font, font.mathConstants(), fontSizePx);
        Layout layout = LayoutEngine.layout(body, ctx);

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
        return new MathFragment(inner, width, height, depth, glyphmap);
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
        MathNode node = MathParser.parse(latex);
        EffectSpec fx = node instanceof StyledMath sm ? sm.fx() : EffectSpec.none();
        Semantics sem = node instanceof StyledMath sm ? sm.sem() : Semantics.none();

        // Lay out once, and reuse the layout for BOTH the SVG and the glyphmap — the
        // sidecar's path indices must line up with this exact emission.
        RenderOptions style = node instanceof StyledMath sm ? sm.style() : RenderOptions.defaults();
        MathNode body = node instanceof StyledMath sm ? sm.body() : node;
        SfntFont font = FontHolder.FONT;
        LayoutContext ctx = new LayoutContext(font, font.mathConstants(),
            DISPLAY_FONT_SIZE * style.scale(), style.mathStyle(), false);
        Layout layout = LayoutEngine.layout(body, ctx);
        String svg = SvgEmitter.emit(layout, font, describe(body), style.color().svgValue());

        // The `thread` effect reads data-lx-glyphmap to light up every occurrence of a
        // hovered token. Stamp the sidecar only when a thread effect is present — it is
        // inert (and wasted bytes) otherwise. Value is [0-9a-f:,;] only, so container-safe.
        String glyphmap = fx.effects().containsValue(Effect.THREAD)
            ? SvgEmitter.glyphmap(layout, font) : "";
        return openTag(fx, sem, glyphmap) + svg + "</span>";
    }

    /**
     * Builds the opening {@code <span class="lx-math" data-lx-…>} tag. Every stamped
     * value was validated + reduced at parse time (intent/concept/data.* are
     * {@code [a-z][a-z0-9_]*} identifiers, effects are a closed enum vocabulary,
     * duration matches {@code \d{1,5}ms}, the a11y label is HTML-escaped), so no raw
     * author string reaches the attribute unescaped.
     */
    private static String openTag(EffectSpec fx, Semantics sem, String glyphmap) {
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
        // data.* attributes (keys already identifier-validated) — iterated in sorted
        // key order so the generated HTML is deterministic regardless of the source
        // map's iteration order (a HashMap/Map.of view is randomized per JVM run).
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(sem.data()).entrySet()) {
            sb.append(" data-lx-").append(e.getKey()).append("=\"").append(e.getValue()).append('"');
        }
        // a11y label (already HTML-escaped by the parser).
        sem.a11yLabelValue().ifPresent(v -> sb.append(" aria-label=\"").append(v).append('"'));
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
    public static String fxContainerAttrs(String latex) {
        MathNode node = MathParser.parse(latex);
        EffectSpec fx = node instanceof StyledMath sm ? sm.fx() : EffectSpec.none();
        return fxAttrs(fx);
    }

    /**
     * The shared fx-attribute stamping source consumed by BOTH {@link #openTag} and
     * {@link #fxContainerAttrs} — one producer, so the wrapper form and the seam form
     * cannot drift. Emits only the container contract's five {@code data-lx-fx-*}
     * attributes, each value validated at parse time.
     */
    private static String fxAttrs(EffectSpec fx) {
        StringBuilder sb = new StringBuilder();
        // fx triggers, in a stable enter/hover/click order.
        for (Trigger t : Trigger.values()) {
            fx.effect(t).ifPresent(e ->
                sb.append(" data-lx-fx-").append(t.name().toLowerCase(Locale.ROOT))
                    .append("=\"").append(e.token()).append('"'));
        }
        fx.durationValue().ifPresent(v ->
            sb.append(" data-lx-fx-duration=\"").append(v).append('"'));
        // glow colour: a validated Color, so svgValue() is currentColor or a canonical
        // #rrggbb literal — a safe attribute VALUE (never a new element/attribute), and
        // it rides the container exactly like the other fx.* metadata.
        fx.glowColorValue().ifPresent(c ->
            sb.append(" data-lx-fx-glow-color=\"").append(c.svgValue()).append('"'));
        return sb.toString();
    }

    /** A plain-language accessibility label for a math tree. */
    static String describe(MathNode node) {
        return switch (node) {
            case Atom atom -> Character.toString(atom.codePoint());
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
                String dir = xa.left() ? "left" : "right";
                yield xa.below() == null
                    ? dir + " arrow labelled " + describe(xa.above())
                    : dir + " arrow labelled " + describe(xa.above())
                        + " over " + describe(xa.below());
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
            case MathNode.Stack st -> stackMathML(st);
            case MathNode.XArrow xa -> {
                String arrow = xa.left() ? "<mo>&#8592;</mo>" : "<mo>&#8594;</mo>";
                yield xa.below() == null
                    ? "<mover>" + arrow + toMathML(xa.above()) + "</mover>"
                    : "<munderover>" + arrow + toMathML(xa.below()) + toMathML(xa.above()) + "</munderover>";
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

    /** XML-escapes text content / attribute values for MathML output. */
    private static String xmlEscape(String s) {
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
            default -> "matrix";
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
        return bundledResource("/com/lattex/fx/lattex-fx.js");
    }

    /**
     * The OPTIONAL styles for the fx layer — the effect {@code @keyframes} + a couple of effect
     * classes. Pair with {@link #fxRuntimeJs()}. Optional: the math needs neither.
     *
     * @return the {@code lattex-fx.css} source
     */
    public static String fxStylesCss() {
        return bundledResource("/com/lattex/fx/lattex-fx.css");
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
