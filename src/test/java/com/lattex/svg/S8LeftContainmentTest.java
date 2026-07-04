package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lattex.api.LatteX;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S8 <strong>LEFT-containment</strong> test: emitter output ⊆ the LatteX minimal
 * alphabet (emitter-output-contract v1). This is the LatteX repo's half of the
 * two-way containment guard — it asserts LatteX's SELF-imposed minimalism, which
 * is <em>stronger</em> than the downstream Stafficy sanitizer (that general docs
 * sanitizer legitimately allows {@code <text>}/{@code <defs>} for hand-authored
 * doc SVG; LatteX must never emit them). The right-containment test (contract ⊆
 * sanitizer) lives in the Stafficy repo.
 *
 * <p>The check runs over a BROAD battery covering every construct and every
 * symbol the parser currently accepts, so any future feature that reaches for a
 * denied element/attribute fails the build here rather than shipping an
 * HTML-embeddable SVG with fresh attack surface.
 *
 * <p>The contract (the entire alphabet):
 * <pre>
 *   svg  : viewBox width height xmlns role aria-label
 *   g    : transform fill
 *   path : d fill stroke stroke-width transform
 *   rect : x y width height fill
 * </pre>
 * Everything else — {@code text use defs symbol marker image foreignObject
 * script style animate}, any {@code href}/{@code xlink:}, {@code data:} URIs,
 * {@code javascript:}, {@code on*=} event handlers — is denied.
 */
class S8LeftContainmentTest {

    // ------------------------------------------------------------------
    // The contract alphabet (emitter-output-contract v1).
    // ------------------------------------------------------------------
    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");

    private static final Set<String> ALLOWED_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label", // svg
        "transform", "fill",                                          // g / path
        "d", "stroke", "stroke-width",                               // path
        "x", "y");                                                    // rect

    // Elements that must NEVER appear (as opening tags) anywhere in the output.
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "text", "tspan", "use", "defs", "symbol", "marker", "image",
        "foreignObject", "script", "style", "animate", "animateTransform",
        "set", "a", "switch", "pattern", "clipPath", "mask", "filter");

    // Substrings that must NEVER appear anywhere in the output (case-insensitive
    // where a spec keyword, exact where a scheme). These are the injection
    // vectors the contract forbids outright.
    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
        "href", "xlink:", "data:", "javascript:", "url(", "<!--", "<![CDATA[",
        "<!doctype", "<?xml-stylesheet", "&#");

    // ------------------------------------------------------------------
    // The battery: every construct + every symbol the parser accepts.
    // Adding a symbol to MathParser without adding it here is fine — but any
    // symbol added here that reaches a denied element fails the build.
    // ------------------------------------------------------------------
    private static List<String> battery() {
        List<String> b = new ArrayList<>();

        // Ordinary chars, digits, punctuation, ASCII operators/relations.
        b.add("a b c x y z");
        b.add("0 1 2 3 4 5 6 7 8 9");
        b.add("a + b - c * d = e < f > g");
        b.add("f(x) = [y] , g ; h");

        // Fractions (incl. nested).
        b.add("\\frac{a+b}{c}");
        b.add("\\frac{\\frac{1}{x}+\\frac{1}{y}}{y-z}");

        // Roots (square + indexed, nested).
        b.add("\\sqrt{x^2+1}");
        b.add("\\sqrt[n]{x+y}");
        b.add("\\sqrt[3]{\\frac{a}{b}}");

        // Scripts: sup, sub, both, stacked.
        b.add("x^2");
        b.add("x_i");
        b.add("x_i^2");
        b.add("k_n^2 + 1");

        // Big operators with limits / nolimits / scripts.
        b.add("\\sum_{i=1}^{10} t_i");
        b.add("\\int_0^\\infty e^{-x}\\,dx");
        b.add("\\prod_{k=1}^{n} k");
        b.add("\\sum\\limits_{i}^{n} a_i");
        b.add("\\int\\nolimits_a^b f");

        // Delimiters: every \left..\right form the parser reads.
        b.add("\\left(\\frac{x^2}{y^3}\\right)");
        b.add("\\left[ a + b \\right]");
        b.add("\\left| x \\right|");
        b.add("\\left\\{ x \\right\\}");
        b.add("\\left\\langle x \\right\\rangle");
        b.add("\\left\\lfloor x \\right\\rfloor");
        b.add("\\left\\lceil x \\right\\rceil");
        b.add("\\left\\Vert x \\right\\Vert");
        b.add("\\left. x \\right/");

        // Lowercase Greek.
        for (String g : List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta",
                "eta", "theta", "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron",
                "pi", "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega")) {
            b.add("\\" + g);
        }
        // Uppercase Greek.
        for (String g : List.of("Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi",
                "Sigma", "Phi", "Psi", "Omega")) {
            b.add("\\" + g);
        }

        // Binary operators.
        b.add("a \\times b");
        b.add("a \\cdot b");
        b.add("a \\pm b");

        // Relations (incl. aliases).
        for (String r : List.of("leq", "le", "geq", "ge", "neq", "ne", "approx",
                "equiv", "to", "rightarrow", "in", "subset")) {
            b.add("a \\" + r + " b");
        }

        // Ordinary symbols + inner dots.
        b.add("\\infty");
        b.add("\\partial x");
        b.add("\\nabla f");
        b.add("a \\cdots b");
        b.add("a \\ldots b");
        b.add("\\{ x \\}");
        b.add("| x |");

        // Accents: narrow glyph accents, wide/stretchy accents, and rules.
        for (String acc : List.of("hat", "bar", "vec", "dot", "ddot", "tilde",
                "check", "breve", "acute", "grave", "mathring")) {
            b.add("\\" + acc + "{a}");
        }
        b.add("\\widehat{AAA}");
        b.add("\\widetilde{AAA}");
        b.add("\\overrightarrow{AB}");
        b.add("\\overleftarrow{AB}");
        b.add("\\overleftrightarrow{AB}");
        b.add("\\overline{a+b}");
        b.add("\\underline{x}");
        b.add("\\hat{a}^2 + \\vec{v}");

        // Named operators: roman trig/log family, limit-takers (over/under limits
        // in display), compound names, and \operatorname / \operatorname*.
        for (String op : List.of("sin", "cos", "tan", "cot", "sec", "csc",
                "sinh", "cosh", "tanh", "coth", "arcsin", "arccos", "arctan",
                "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf",
                "det", "gcd", "deg", "dim", "ker", "hom", "arg", "Pr",
                "liminf", "limsup", "injlim", "projlim")) {
            b.add("\\" + op + " x");
        }
        b.add("\\lim_{x\\to\\infty} f(x)");
        b.add("\\cos^2\\theta + \\sin^2\\theta = 1");
        b.add("\\max_{1 \\leq i \\leq n} a_i");
        b.add("\\operatorname{lcm}(a,b)");
        b.add("\\operatorname*{argmax}_{x} f(x)");

        // Text mode: upright words with real spaces, shape variants, \mathrm, and
        // a text run carrying a superscript — every glyph must stay a <path>, never
        // an SVG <text> element, and the inter-word spaces must not leak markup.
        b.add("\\text{if } n \\text{ is even}");
        b.add("\\textrm{Roman text 123}");
        b.add("\\textbf{Bold text 456}");
        b.add("\\textit{Italic text}");
        b.add("\\texttt{Mono text 789}");
        b.add("\\mathrm{d}x");
        b.add("\\text{area} = \\pi r^2");
        b.add("\\text{count}^2 + \\textbf{k}_i");

        // Spacing commands (produce inkless spacing, never elements/attrs).
        b.add("a \\, b \\: c \\; d \\! e \\quad f \\qquad g");

        // Environments: matrix family (every delimiter), smallmatrix, cases, and an
        // array with a vertical rule + \hline / \hdashline. Every grid glyph is a
        // <path>, every rule (fraction-style bars, column/row rules, and the dashes
        // of \hdashline) a <rect> — the whole grid must stay ⊆ the minimal alphabet.
        b.add("\\begin{matrix}a&b\\\\c&d\\end{matrix}");
        b.add("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}");
        b.add("\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}");
        b.add("\\begin{Bmatrix}a&b\\\\c&d\\end{Bmatrix}");
        b.add("\\begin{vmatrix}a&b\\\\c&d\\end{vmatrix}");
        b.add("\\begin{Vmatrix}a&b\\\\c&d\\end{Vmatrix}");
        b.add("\\begin{smallmatrix}a&b\\\\c&d\\end{smallmatrix}");
        b.add("\\begin{cases}n/2&\\text{if }n\\text{ even}\\\\-(n+1)/2&\\text{if odd}\\end{cases}");
        b.add("\\begin{array}{c|c}1&2\\\\\\hline 3&4\\end{array}");
        b.add("\\begin{array}{lcr}\\hline a&bb&ccc\\\\\\hdashline 1&2&3\\\\\\hline\\end{array}");
        b.add("A_{m,n}=\\begin{pmatrix}a_{1,1}&\\cdots&a_{1,n}\\\\"
            + "\\vdots&\\ddots&\\vdots\\\\a_{m,1}&\\cdots&a_{m,n}\\end{pmatrix}");
        b.add("\\left(\\begin{matrix}1&0\\\\0&1\\end{matrix}\\right)^2");

        // A dense everything-together stress expression.
        b.add("\\sum_{i=1}^{n} \\frac{\\sqrt{\\alpha_i^2 + \\beta_i^2}}{\\gamma}"
            + " \\leq \\left( \\int_0^\\infty e^{-x}\\,dx \\right)");

        // \lx styled-math macro: style.* affects only the SVG fill VALUE / font size
        // / layout style (no new element/attr), and fx.*/semantics are NEVER emitted
        // into the SVG (they ride the trusted container, not LatteX.render's output).
        // Every rendered SVG here must stay ⊆ the minimal alphabet.
        b.add("\\lx{ x^2 }");
        b.add("\\lx[style.color=#c0392b, style.scale=lg]{ \\frac{a+b}{c} }");
        b.add("\\lx[style.color=currentColor, style.mathstyle=text]{ \\sum_{i=1}^{n} i }");
        b.add("\\lx[intent=ratio, concept=normalized_score, fx.enter=boom, fx.hover=pulse, "
            + "fx.click=glow, fx.duration=250ms, a11y.label=\"the ratio a plus b over c\", "
            + "data.role=header, data.group=scores]{ \\frac{a+b}{c} }");
        // graph.* plotting annotations ride the trusted container (data-lx-graph-*),
        // NEVER the SVG — render() drops them, so the emitter alphabet is unchanged.
        b.add("\\lx[graph.domain=-3..3, graph.open=multi]{ x^2 - 3 }");
        b.add("\\lx[graph.domain=-10..10, graph.open=single]{ y = \\frac{1}{x} }");

        return b;
    }

    // ------------------------------------------------------------------
    // The test.
    // ------------------------------------------------------------------

    @Test
    void emitterNeverEscapesTheMinimalAlphabet() {
        List<String> failures = new ArrayList<>();
        int scannedTags = 0;

        for (String latex : battery()) {
            String svg;
            try {
                svg = LatteX.render(latex);
            } catch (RuntimeException e) {
                failures.add("render threw for [" + latex + "]: " + e);
                continue;
            }
            scannedTags += auditOne(latex, svg, failures);
        }

        if (!failures.isEmpty()) {
            fail("LEFT-containment violated (" + failures.size() + "):\n  "
                + String.join("\n  ", failures));
        }
        // Sanity: we really did scan a broad, tag-rich body (svg+g+many paths+rects
        // across dozens of expressions), not an empty string that passes vacuously.
        assertTrue(scannedTags > 200,
            "expected a large tag corpus from the battery, scanned only " + scannedTags);
    }

    /** Audits one rendered SVG; appends any violations. Returns tags scanned. */
    private static int auditOne(String latex, String svg, List<String> failures) {
        String lower = svg.toLowerCase(java.util.Locale.ROOT);

        // 1. No forbidden opening tags.
        for (String el : FORBIDDEN_ELEMENTS) {
            if (lower.contains("<" + el.toLowerCase(java.util.Locale.ROOT))) {
                failures.add("forbidden element <" + el + "> in [" + latex + "]");
            }
        }
        // 2. No forbidden substrings (schemes / indirection / injection vectors).
        for (String bad : FORBIDDEN_SUBSTRINGS) {
            if (lower.contains(bad.toLowerCase(java.util.Locale.ROOT))) {
                failures.add("forbidden substring \"" + bad + "\" in [" + latex + "]");
            }
        }
        // 3. No on*= event-handler attributes.
        if (Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(svg).find()) {
            failures.add("event-handler (on*=) attribute in [" + latex + "]");
        }

        // 4. Per-tag: element name in alphabet; attribute names in alphabet;
        //    attribute VALUES are safe (numeric/hex/known-constant, never a URI
        //    or markup breakout).
        int tags = 0;
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^>]*)?)/?>").matcher(svg);
        while (tag.find()) {
            tags++;
            String element = tag.group(1);
            if (!ALLOWED_ELEMENTS.contains(element)) {
                failures.add("element out of alphabet <" + element + "> in [" + latex + "]");
                continue;
            }
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=\\s*\"([^\"]*)\"")
                .matcher(tag.group(2));
            while (attr.find()) {
                String name = attr.group(1);
                String value = attr.group(2);
                if (!ALLOWED_ATTRS.contains(name)) {
                    failures.add("attribute out of alphabet " + name + " on <" + element
                        + "> in [" + latex + "]");
                    continue;
                }
                if (!valueSafe(name, value)) {
                    failures.add("unsafe value for " + name + "=\"" + value + "\" on <"
                        + element + "> in [" + latex + "]");
                }
            }
        }
        return tags;
    }

    /**
     * A value is safe when it matches the narrow shape the contract permits for
     * that attribute — numeric geometry, a hex fill, path-command + number data,
     * an affine {@code transform}, the fixed SVG namespace, {@code role="img"},
     * or a plain-text {@code aria-label} with no markup/scheme breakout.
     */
    private static boolean valueSafe(String name, String value) {
        return switch (name) {
            case "xmlns" -> value.equals("http://www.w3.org/2000/svg");
            case "role" -> value.equals("img");
            case "viewBox" -> value.matches("-?[0-9.]+( -?[0-9.]+){3}");
            case "width", "height", "x", "y", "stroke-width" -> value.matches("-?[0-9.]+");
            case "fill", "stroke" -> value.matches("#[0-9a-fA-F]{3,8}")
                || value.equals("none") || value.equals("currentColor");
            // Path data: only path-command letters, digits, sign, dot, space, comma.
            case "d" -> value.matches("[MLHVCSQTAZmlhvcsqtaz0-9 ,.+\\-]+");
            // Affine transform: only translate/scale/matrix/rotate keywords + numbers.
            case "transform" -> value.matches("(translate|scale|matrix|rotate|skewX|skewY"
                + "|[\\s(),.+\\-0-9])+");
            // Free text (the LaTeX-derived a11y label). No markup or scheme breakout;
            // '<'/'&' are entity-escaped by the emitter so a raw '<' would be a bug.
            case "aria-label" -> !value.contains("<") && !value.contains(">")
                && !value.toLowerCase(java.util.Locale.ROOT).contains("javascript:")
                && !value.toLowerCase(java.util.Locale.ROOT).contains("href");
            default -> false; // unknown attr name shouldn't reach here (allowlist gate)
        };
    }
}
