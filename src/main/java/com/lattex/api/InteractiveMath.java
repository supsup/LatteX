package com.lattex.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trusted host API for an honest two-state equation transition.
 *
 * <p>Both endpoints are rendered independently by LatteX's existing static
 * renderer. This class adds only an inert outer HTML component and separately
 * bundled trusted runtime assets. It does not alter the static SVG emitter,
 * author FX grammar, or sanitizer-safe output contract. The first release uses
 * whole-expression FLIP/crossfade; it makes no per-glyph morph claim.
 */
public final class InteractiveMath {
    /** Combined UTF-16 source budget applied before either endpoint is rendered. */
    public static final int MAX_TOTAL_SOURCE_CHARS = 100_000;
    /** Defensive per-endpoint ceiling, matching the static emitter's current output cap. */
    public static final int MAX_ENDPOINT_CHARS = 2_000_000;
    /** Independent cap for the final wrapper plus its two endpoint SVGs. */
    public static final int MAX_COMPONENT_CHARS = 3_000_000;

    private static final String RUNTIME_RESOURCE =
        "/com/lattex/interactive/lattex-interactive.js";
    private static final String STYLES_RESOURCE =
        "/com/lattex/interactive/lattex-interactive.css";

    private static final Pattern TAG = Pattern.compile(
        "<(/?)([a-zA-Z][a-zA-Z0-9]*)([^<>]*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "([a-zA-Z][a-zA-Z-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern EVENT_HANDLER = Pattern.compile(
        "\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final String SVG_NUMBER = "-?[0-9]+(?:\\.[0-9]+)?";
    private static final Pattern NUMBER = Pattern.compile(SVG_NUMBER);
    private static final Pattern VIEW_BOX = Pattern.compile(
        SVG_NUMBER + "(?: " + SVG_NUMBER + "){3}");
    private static final Pattern PAINT = Pattern.compile(
        "#[0-9a-fA-F]{3,8}|none|currentColor");
    private static final Pattern PATH_DATA = Pattern.compile(
        "[MLHVCSQTAZmlhvcsqtaz0-9 ,.+\\-]+");
    private static final Pattern TRANSFORM = Pattern.compile(
        "(translate|scale|matrix|rotate|skewX|skewY|[\\s(),.+\\-0-9])+");
    private static final Set<String> ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final Set<String> SVG_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label");
    private static final Set<String> G_ATTRS = Set.of("transform", "fill");
    private static final Set<String> PATH_ATTRS = Set.of(
        "d", "fill", "stroke", "stroke-width", "transform");
    private static final Set<String> RECT_ATTRS = Set.of(
        "x", "y", "width", "height", "fill");
    private static final Set<String> REQUIRED_SVG_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label");
    private static final Set<String> REQUIRED_RECT_ATTRS = Set.of(
        "x", "y", "width", "height");

    private static final String[] FORBIDDEN_SVG_TOKENS = {
        "href", "xlink:", "data:", "javascript:", "url(", "<!--", "<![cdata[",
        "<!doctype", "<?", "&#"
    };
    private static final String[] ALLOWED_ENTITIES = {
        "&amp;", "&lt;", "&gt;", "&quot;", "&apos;"
    };

    private InteractiveMath() {}

    /** Render with {@link InteractiveOptions#defaults()}. */
    public static InteractiveResult render(String fromLatex, String toLatex) {
        return render(fromLatex, toLatex, InteractiveOptions.defaults());
    }

    /**
     * Render two independent static endpoints and assemble the trusted component.
     * Invalid or over-budget optional decoration fails to one valid static endpoint
     * when possible; it never returns a partially assembled wrapper.
     */
    public static InteractiveResult render(
            String fromLatex, String toLatex, InteractiveOptions options) {
        if (options == null) {
            Diagnostics missing = diagnostic(Outcome.PARSE_ERROR, "interactive-options",
                "Interactive options are required.");
            return failed(missing, missing, "Interactive options were invalid.");
        }

        Diagnostics fromInputFailure = inputFailure(fromLatex, "initial");
        Diagnostics toInputFailure = inputFailure(toLatex, "alternate");
        long sourceChars = (fromLatex == null ? 0L : fromLatex.length())
            + (toLatex == null ? 0L : toLatex.length());
        if (sourceChars > MAX_TOTAL_SOURCE_CHARS) {
            Diagnostics cap = diagnostic(Outcome.OUTPUT_CAP_EXCEEDED, "interactive-input",
                "Combined equation source exceeds the interactive source budget.");
            return failed(cap, cap, "Combined equation source exceeded the cap.");
        }

        Endpoint from = fromInputFailure == null
            ? renderEndpoint(fromLatex, options.renderOptions())
            : Endpoint.invalid(fromInputFailure);
        Endpoint to = toInputFailure == null
            ? renderEndpoint(toLatex, options.renderOptions())
            : Endpoint.invalid(toInputFailure);

        if (!from.usable() || !to.usable()) {
            return fallbackOrFailure(from, to,
                "One endpoint could not join the interactive component.");
        }

        try {
            String html = assemble(from.svg(), to.svg(), options.durationMillis());
            return new InteractiveResult(html, InteractiveResult.Status.INTERACTIVE,
                from.diagnostics(), to.diagnostics(),
                "Interactive transition assembled from two exact static endpoints.");
        } catch (RuntimeException assemblyFailure) {
            return fallbackOrFailure(from, to,
                "Interactive assembly failed closed to a static endpoint.");
        }
    }

    /** Return the separately bundled trusted runtime source. */
    public static String runtimeJs() {
        return bundledResource(RUNTIME_RESOURCE);
    }

    /** Return the separately bundled trusted component styles. */
    public static String stylesCss() {
        return bundledResource(STYLES_RESOURCE);
    }

    private static Diagnostics inputFailure(String source, String label) {
        if (source == null) {
            return diagnostic(Outcome.PARSE_ERROR, "interactive-input",
                "The " + label + " equation source is required.");
        }
        return null;
    }

    private static Endpoint renderEndpoint(String source, RenderOptions options) {
        RenderResult rendered;
        try {
            rendered = LatteX.renderWithDiagnostics(source, options);
        } catch (StackOverflowError | RuntimeException failure) {
            return Endpoint.invalid(diagnostic(Outcome.RENDER_BUG, "interactive-render",
                "The static endpoint renderer failed unexpectedly."));
        }
        if (rendered.diagnostics().outcome() != Outcome.OK) {
            return Endpoint.invalid(rendered.diagnostics());
        }
        if (rendered.svg().isEmpty()) {
            return Endpoint.invalid(diagnostic(Outcome.RENDER_BUG,
                "interactive-render", "The static endpoint renderer returned no SVG."));
        }
        if (rendered.svg().length() > MAX_ENDPOINT_CHARS) {
            return Endpoint.invalid(diagnostic(Outcome.OUTPUT_CAP_EXCEEDED,
                "interactive-output", "A static endpoint exceeded the interactive output cap."));
        }
        if (!staticSvgSafe(rendered.svg())) {
            return Endpoint.invalid(diagnostic(Outcome.RENDER_BUG,
                "interactive-validate", "A static endpoint escaped the minimal SVG contract."));
        }
        return new Endpoint(rendered.svg(), rendered.diagnostics(), true);
    }

    private static InteractiveResult fallbackOrFailure(
            Endpoint from, Endpoint to, String message) {
        if (from.usable()) {
            return new InteractiveResult(from.svg(), InteractiveResult.Status.STATIC_FALLBACK,
                from.diagnostics(), to.diagnostics(), message);
        }
        if (to.usable()) {
            return new InteractiveResult(to.svg(), InteractiveResult.Status.STATIC_FALLBACK,
                from.diagnostics(), to.diagnostics(), message);
        }
        return failed(from.diagnostics(), to.diagnostics(), message);
    }

    private static InteractiveResult failed(
            Diagnostics from, Diagnostics to, String message) {
        return new InteractiveResult("", InteractiveResult.Status.FAILED,
            from, to, message);
    }

    private static Diagnostics diagnostic(Outcome outcome, String stage, String message) {
        return new Diagnostics(outcome, stage, message, -1, "", -1, "");
    }

    /** Package-visible load-bearing seam for exact cap and serializer tests. */
    static String assemble(String fromSvg, String toSvg, int durationMillis) {
        if (durationMillis < InteractiveOptions.MIN_DURATION_MILLIS
                || durationMillis > InteractiveOptions.MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("duration outside the trusted range");
        }
        if (fromSvg == null || toSvg == null
                || fromSvg.length() > MAX_ENDPOINT_CHARS
                || toSvg.length() > MAX_ENDPOINT_CHARS
                || !staticSvgSafe(fromSvg) || !staticSvgSafe(toSvg)) {
            throw new IllegalArgumentException("endpoint escaped the trusted static contract");
        }

        String prefix = "<figure class=\"lx-transition\" data-lx-transition=\"true\""
            + " data-lx-duration=\"" + durationMillis + "\">\n"
            + "  <figcaption class=\"lx-transition__caption\">Equation transition</figcaption>\n"
            + "  <div class=\"lx-transition__stage\">\n"
            + "    <div class=\"lx-transition__state lx-transition__state--from\""
            + " data-lx-state=\"from\">\n"
            + "      <span class=\"lx-transition__label\">Initial equation</span>\n";
        String middle = "\n    </div>\n"
            + "    <div class=\"lx-transition__state lx-transition__state--to\""
            + " data-lx-state=\"to\">\n"
            + "      <span class=\"lx-transition__label\">Alternate equation</span>\n";
        String suffix = "\n    </div>\n"
            + "  </div>\n"
            + "  <button class=\"lx-transition__control\" type=\"button\""
            + " aria-expanded=\"false\">Show alternate equation</button>\n"
            + "</figure>";

        long expected = (long) prefix.length() + fromSvg.length() + middle.length()
            + toSvg.length() + suffix.length();
        if (expected > MAX_COMPONENT_CHARS) {
            throw new IllegalArgumentException("interactive component exceeds output cap");
        }
        StringBuilder html = new StringBuilder((int) expected);
        html.append(prefix).append(fromSvg).append(middle).append(toSvg).append(suffix);
        if (html.length() != expected) {
            throw new IllegalStateException("interactive component length drift");
        }
        return html.toString();
    }

    /**
     * Runtime backstop for the exact static SVG subset. The existing broad S8
     * tests remain the primary contract; this gate prevents a future emitter
     * drift from being blindly embedded in the trusted wrapper.
     */
    static boolean staticSvgSafe(String svg) {
        if (svg == null || svg.isBlank() || svg.length() > MAX_ENDPOINT_CHARS
                || !svg.startsWith("<svg ") || !svg.endsWith("</svg>")) {
            return false;
        }
        String lower = svg.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_SVG_TOKENS) {
            if (lower.contains(forbidden)) {
                return false;
            }
        }
        if (EVENT_HANDLER.matcher(svg).find()) {
            return false;
        }

        Matcher tags = TAG.matcher(svg);
        Deque<String> stack = new ArrayDeque<>();
        int cursor = 0;
        int rootCount = 0;
        int tagCount = 0;
        while (tags.find()) {
            if (!svg.substring(cursor, tags.start()).isBlank()) {
                return false;
            }
            boolean closing = !tags.group(1).isEmpty();
            String element = tags.group(2);
            String rawTail = tags.group(3);
            if (!ELEMENTS.contains(element)) {
                return false;
            }
            tagCount++;
            if (closing) {
                if (!rawTail.isBlank() || stack.isEmpty()
                        || !stack.removeLast().equals(element)) {
                    return false;
                }
            } else {
                String trimmedTail = rawTail.trim();
                boolean selfClosing = trimmedTail.endsWith("/");
                if ((element.equals("path") || element.equals("rect")) && !selfClosing) {
                    return false;
                }
                if (element.equals("svg") && !stack.isEmpty()) {
                    return false;
                }
                String attributes = selfClosing
                    ? trimmedTail.substring(0, trimmedTail.length() - 1)
                    : rawTail;
                Set<String> names = parseAttributes(element, attributes);
                if (names == null || !requiredAttributesPresent(element, names)) {
                    return false;
                }
                if (stack.isEmpty()) {
                    rootCount++;
                    if (rootCount != 1 || !element.equals("svg")) {
                        return false;
                    }
                }
                if (selfClosing) {
                    if (element.equals("svg")) {
                        return false;
                    }
                } else {
                    stack.addLast(element);
                }
            }
            cursor = tags.end();
        }
        return rootCount == 1 && tagCount >= 2 && stack.isEmpty()
            && svg.substring(cursor).isBlank();
    }

    private static Set<String> parseAttributes(String element, String attributes) {
        Set<String> allowed = switch (element) {
            case "svg" -> SVG_ATTRS;
            case "g" -> G_ATTRS;
            case "path" -> PATH_ATTRS;
            case "rect" -> RECT_ATTRS;
            default -> Set.of();
        };
        Set<String> names = new HashSet<>();
        Matcher matcher = ATTRIBUTE.matcher(attributes);
        int cursor = 0;
        while (cursor < attributes.length()) {
            while (cursor < attributes.length()
                    && Character.isWhitespace(attributes.charAt(cursor))) {
                cursor++;
            }
            if (cursor == attributes.length()) {
                break;
            }
            matcher.region(cursor, attributes.length());
            if (!matcher.lookingAt()) {
                return null;
            }
            String name = matcher.group(1);
            String value = matcher.group(2);
            if (!allowed.contains(name) || !names.add(name) || !valueSafe(name, value)) {
                return null;
            }
            cursor = matcher.end();
            if (cursor < attributes.length()
                    && !Character.isWhitespace(attributes.charAt(cursor))) {
                return null;
            }
        }
        return names;
    }

    private static boolean requiredAttributesPresent(String element, Set<String> names) {
        return switch (element) {
            case "svg" -> names.containsAll(REQUIRED_SVG_ATTRS);
            case "path" -> names.contains("d");
            case "rect" -> names.containsAll(REQUIRED_RECT_ATTRS);
            case "g" -> true;
            default -> false;
        };
    }

    private static boolean valueSafe(String name, String value) {
        return switch (name) {
            case "xmlns" -> value.equals("http://www.w3.org/2000/svg");
            case "role" -> value.equals("img");
            case "viewBox" -> VIEW_BOX.matcher(value).matches();
            case "width", "height", "x", "y", "stroke-width" ->
                NUMBER.matcher(value).matches();
            case "fill", "stroke" -> PAINT.matcher(value).matches();
            case "d" -> PATH_DATA.matcher(value).matches();
            case "transform" -> TRANSFORM.matcher(value).matches();
            case "aria-label" -> labelSafe(value);
            default -> false;
        };
    }

    /** Linear rather than regex-recursive so a legal near-cap label cannot overflow the stack. */
    private static boolean labelSafe(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("javascript:") || lower.contains("href")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r')
                    || ch == '<' || ch == '>') {
                return false;
            }
            if (ch != '&') {
                continue;
            }
            String entity = null;
            for (String allowed : ALLOWED_ENTITIES) {
                if (value.startsWith(allowed, i)) {
                    entity = allowed;
                    break;
                }
            }
            if (entity == null) {
                return false;
            }
            i += entity.length() - 1;
        }
        return true;
    }

    private static String bundledResource(String path) {
        try (InputStream input = InteractiveMath.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("bundled LatteX interactive resource missing: "
                    + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read bundled interactive resource "
                + path, e);
        }
    }

    private record Endpoint(String svg, Diagnostics diagnostics, boolean usable) {
        private static Endpoint invalid(Diagnostics diagnostics) {
            return new Endpoint("", diagnostics, false);
        }
    }
}
