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
        // render() re-parses and applies a top-level \lx style to the inner SVG.
        String svg = render(latex);
        return openTag(fx, sem) + svg + "</span>";
    }

    /**
     * Builds the opening {@code <span class="lx-math" data-lx-…>} tag. Every stamped
     * value was validated + reduced at parse time (intent/concept/data.* are
     * {@code [a-z][a-z0-9_]*} identifiers, effects are a closed enum vocabulary,
     * duration matches {@code \d{1,5}ms}, the a11y label is HTML-escaped), so no raw
     * author string reaches the attribute unescaped.
     */
    private static String openTag(EffectSpec fx, Semantics sem) {
        StringBuilder sb = new StringBuilder("<span class=\"lx-math\"");
        sem.intentValue().ifPresent(v -> sb.append(" data-lx-intent=\"").append(v).append('"'));
        sem.conceptValue().ifPresent(v -> sb.append(" data-lx-concept=\"").append(v).append('"'));
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
            case Accent(var command, var base, _, _, _) ->
                accentName(command) + " " + describe(base);
            case OperatorName(var name, _) -> name;
            case TextRun(var text, _) -> text;
            case Matrix m -> describeMatrix(m);
            case StyledMath sm -> describe(sm.body()); // the wrapper is transparent to a11y
        };
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
}
