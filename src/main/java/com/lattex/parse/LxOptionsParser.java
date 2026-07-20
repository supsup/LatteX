package com.lattex.parse;

import com.lattex.api.Color;
import com.lattex.api.RenderOptions;
import com.lattex.layout.MathStyle;
import com.lattex.parse.MathNode.StyledMath;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code \lx[options]{body}} author macro's options sub-language, split out
 * of {@link MathParser} verbatim. The options block has its own mini-syntax
 * (whitespace insignificant outside quotes; {@code #hex} and {@code "quoted"}
 * values), so it is parsed from the RAW string here, BEFORE the math lexer would
 * mangle it; the {@code {...}} body is then handed back to {@link
 * MathParser#parseMath} so a nested {@code \lx} inside the body still surfaces as
 * the ordinary "nested" error. Every option is validated and reduced to a typed
 * value (an L1 {@link RenderOptions} plus an {@link EffectSpec} / {@link
 * Semantics}) at parse time — never carried as a raw string. Purely a
 * string-to-typed reduction: it holds no token-cursor state.
 */
final class LxOptionsParser {

    private LxOptionsParser() {
    }

    /** Whether the trimmed input begins with {@code \lx} as a complete control word. */
    static boolean looksLikeTopLevelLx(String latex) {
        String s = latex.strip();
        if (!s.startsWith("\\lx")) {
            return false;
        }
        // "\lx" must be a whole control word — not the prefix of e.g. "\lxfoo".
        return s.length() == 3 || !isAsciiLetter(s.charAt(3));
    }

    /** Parses a whole-string {@code \lx[options]{body}} into a {@link StyledMath}. */
    static MathNode parseLx(String s) {
        return parseLx(s, java.util.Map.of());
    }

    /**
     * Macro-carrying form (L8): the caller's preset macros apply INSIDE the
     * {@code \lx} body (the {@code \lx} options sub-language itself takes no
     * macros — options are typed values, not LaTeX).
     */
    static MathNode parseLx(String s, java.util.Map<String, String> macros) {
        int n = s.length();
        int pos = skipWs(s, 3); // past "\lx"

        String optionsRaw = "";
        if (pos < n && s.charAt(pos) == '[') {
            int close = findClose(s, pos, '[', ']', "\\lx [ options ]", true);
            optionsRaw = s.substring(pos + 1, close);
            pos = skipWs(s, close + 1);
        }

        if (pos >= n || s.charAt(pos) != '{') {
            throw new MathSyntaxException(
                "\\lx requires a { body }: expected '{' after the \\lx options");
        }
        int bodyClose = findClose(s, pos, '{', '}', "\\lx { body }", false);
        String body = s.substring(pos + 1, bodyClose);
        pos = skipWs(s, bodyClose + 1);
        if (pos != n) {
            throw new MathSyntaxException(
                "\\lx must be the whole top-level expression; unexpected trailing content: \""
                    + s.substring(pos) + "\"");
        }

        LxOptions opts = parseLxOptions(optionsRaw, body);
        MathNode bodyNode = MathParser.parseMath(body, 0, macros);
        return new StyledMath(bodyNode, opts.style(), opts.fx(), opts.sem());
    }

    /**
     * Finds the closer matching the opener at {@code open}, scanning past
     * {@code "quoted"} spans and {@code \}-escaped chars (so a {@code ]} in a
     * quoted value or a {@code \}} in the LaTeX body does not close early).
     * Handles one level of {@code open}/{@code close} nesting for the body braces.
     */
    /**
     * Find the matching closer. {@code quoteAware} applies ONLY to the
     * {@code [options]} mini-language, where double quotes delimit values and
     * may enclose brackets. The math BODY must scan with it OFF: a quote there
     * is ordinary content (\text{"}, primes-adjacent typography), and treating
     * it as a delimiter made \lx{"} falsely "unterminated" (Lattice, lattex/42).
     */
    private static int findClose(String s, int open, char opener, char closer, String what,
                                 boolean quoteAware) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++; // skip the escaped char (e.g. \{ \} \" in the body)
                continue;
            }
            if (quoteAware && c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) {
                continue;
            }
            if (c == opener) {
                depth++;
            } else if (c == closer) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new MathSyntaxException("unterminated " + what + ": missing '" + closer + "'");
    }

    /** Skips ASCII whitespace in {@code s} starting at {@code i}. */
    private static int skipWs(String s, int i) {
        while (i < s.length() && isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The validated typed reduction of an {@code \lx} options block. */
    private record LxOptions(RenderOptions style, EffectSpec fx, Semantics sem) {
    }

    /**
     * Parses a comma-separated {@code key=value} options block into the typed
     * {@link LxOptions}. Whitespace is insignificant outside quoted values; values
     * are bare tokens or {@code "quoted strings"}. Every value is validated and
     * reduced to a typed field. Unknown top-level keys fail loud.
     *
     * <p>The {@code \lx} colour default is {@link Color#CURRENT} (not L1's
     * {@link RenderOptions#defaults()} black): the author macro is meant to be
     * embedded in prose, so unstyled math inherits the surrounding text colour
     * (dark-mode friendly).
     */
    private static LxOptions parseLxOptions(String raw, String body) {
        double scale = 1.0;
        Color color = Color.CURRENT;
        MathStyle mathStyle = MathStyle.DISPLAY;
        Map<Trigger, Effect> effects = new EnumMap<>(Trigger.class);
        String duration = null;
        Color glowColor = null;
        String intent = null;
        String concept = null;
        String a11yLabel = null;
        Map<String, String> data = new LinkedHashMap<>();
        String graphDomain = null;
        String graphOpen = null;
        boolean graphRequested = false;

        int i = 0;
        int n = raw.length();
        while (true) {
            i = skipWs(raw, i);
            if (i >= n) {
                break;
            }
            // key
            int ks = i;
            while (i < n && isKeyChar(raw.charAt(i))) {
                i++;
            }
            String key = raw.substring(ks, i);
            if (key.isEmpty()) {
                throw new MathSyntaxException(
                    "malformed \\lx option near: \"" + raw.substring(i) + "\"");
            }
            i = skipWs(raw, i);
            if (i >= n || raw.charAt(i) != '=') {
                throw new MathSyntaxException("\\lx option \"" + key + "\" must be key=value");
            }
            i = skipWs(raw, i + 1); // past '='
            // value: quoted or bare
            String value;
            if (i < n && raw.charAt(i) == '"') {
                int vs = ++i;
                while (i < n && raw.charAt(i) != '"') {
                    i++;
                }
                if (i >= n) {
                    throw new MathSyntaxException(
                        "unterminated quoted value for \\lx option \"" + key + "\"");
                }
                value = raw.substring(vs, i);
                i++; // past closing quote
            } else {
                int vs = i;
                while (i < n && raw.charAt(i) != ',' && !isWhitespace(raw.charAt(i))) {
                    i++;
                }
                value = raw.substring(vs, i);
            }
            i = skipWs(raw, i);
            if (i < n) {
                if (raw.charAt(i) == ',') {
                    i++;
                } else {
                    throw new MathSyntaxException(
                        "expected ',' between \\lx options near: \"" + raw.substring(i) + "\"");
                }
            }

            // dispatch on the top-level namespace (part before the first '.').
            int dot = key.indexOf('.');
            String ns = dot < 0 ? key : key.substring(0, dot);
            switch (ns) {
                case "style" -> {
                    // The Layer-1 validators throw a plain IllegalArgumentException;
                    // wrap it so an invalid \lx value fails loud naming the key.
                    try {
                        switch (key) {
                            case "style.scale" -> scale = RenderOptions.parseScale(value);
                            case "style.color" -> color = Color.parse(value);
                            case "style.mathstyle" -> mathStyle = RenderOptions.parseMathStyle(value);
                            default -> throw unknownKey(key);
                        }
                    } catch (MathSyntaxException e) {
                        throw e;
                    } catch (IllegalArgumentException e) {
                        throw new MathSyntaxException(
                            "invalid \\lx option \"" + key + "\": " + e.getMessage());
                    }
                }
                case "fx" -> {
                    switch (key) {
                        case "fx.enter" -> effects.put(Trigger.ENTER, Effect.parse(value));
                        case "fx.hover" -> effects.put(Trigger.HOVER, Effect.parse(value));
                        case "fx.click" -> effects.put(Trigger.CLICK, Effect.parse(value));
                        case "fx.duration" -> duration = value; // validated in EffectSpec
                        // The glow/lightning halo colour. Validated through the same
                        // Color boundary as style.color (accepts #rgb/#rrggbb/currentColor,
                        // fails loud otherwise); it becomes a data-lx-fx-glow-color VALUE on
                        // the container, never a new SVG element/attribute.
                        case "fx.glow-color" -> {
                            try {
                                glowColor = Color.parse(value);
                            } catch (IllegalArgumentException e) {
                                throw new MathSyntaxException(
                                    "invalid \\lx option \"" + key + "\": " + e.getMessage());
                            }
                        }
                        default -> throw unknownKey(key);
                    }
                }
                case "intent" -> {
                    if (!key.equals("intent")) {
                        throw unknownKey(key);
                    }
                    intent = value; // validated in Semantics
                }
                case "concept" -> {
                    if (!key.equals("concept")) {
                        throw unknownKey(key);
                    }
                    concept = value; // validated in Semantics
                }
                case "a11y" -> {
                    if (!key.equals("a11y.label")) {
                        throw unknownKey(key);
                    }
                    a11yLabel = htmlEscape(value);
                }
                case "data" -> {
                    if (dot < 0 || key.length() <= 5) {
                        throw unknownKey(key);
                    }
                    String dataKey = key.substring(5); // past "data."
                    if (!Semantics.IDENTIFIER.matcher(dataKey).matches()) {
                        throw new MathSyntaxException(
                            "invalid data.* key: \"" + key + "\" (expected an identifier suffix)");
                    }
                    if (!Semantics.IDENTIFIER.matcher(value).matches()) {
                        throw new MathSyntaxException(
                            "invalid data.* value for \"" + key + "\": \"" + value
                                + "\" (expected an identifier)");
                    }
                    data.put(dataKey, value);
                }
                case "graph" -> {
                    switch (key) {
                        case "graph.domain" -> {
                            graphDomain = value;
                            graphRequested = true;
                        }
                        case "graph.open" -> {
                            graphOpen = value;
                            graphRequested = true;
                        }
                        default -> throw unknownKey(key);
                    }
                }
                default -> throw unknownKey(key);
            }
        }

        // graph.* marks a plottable expression. The annotations ride the trusted
        // container as data-lx-graph-* (via the Semantics data map) for the page-side
        // plotting runtime; they are NEVER emitted into the <svg> (render() drops all
        // StyledMath annotations), so the emitter alphabet is unchanged. The body's raw
        // LaTeX is carried as data-lx-graph-expr (HTML-escaped) so the runtime knows
        // what to plot without re-reading the SVG.
        if (graphRequested) {
            if (graphDomain == null) {
                graphDomain = "-10..10";
            } else if (!graphDomain.matches("-?\\d+(\\.\\d+)?\\.\\.-?\\d+(\\.\\d+)?")) {
                throw new MathSyntaxException(
                    "invalid graph.domain: \"" + graphDomain + "\" (expected a..b like -3..3)");
            }
            if (graphOpen == null) {
                graphOpen = "single";
            } else if (!graphOpen.equals("single") && !graphOpen.equals("multi")) {
                throw new MathSyntaxException(
                    "invalid graph.open: \"" + graphOpen + "\" (expected single or multi)");
            }
            data.put("graph-expr", htmlEscape(body.strip()));
            data.put("graph-domain", graphDomain);
            data.put("graph-open", graphOpen);
        }

        return new LxOptions(
            new RenderOptions(scale, color, mathStyle),
            new EffectSpec(effects, duration, glowColor),
            new Semantics(intent, concept, a11yLabel, data));
    }

    private static boolean isKeyChar(char c) {
        // '-' lets hyphenated option keys like fx.glow-color tokenize as one key;
        // every value is still validated by its own boundary, so this only widens
        // the KEY grammar (an unknown hyphenated key still fails loud).
        return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    }

    private static MathSyntaxException unknownKey(String key) {
        return new MathSyntaxException(
            "unknown \\lx option key: \"" + key
                + "\" (known top-level keys: style, fx, intent, concept, a11y, data, graph)");
    }

    /** HTML-escapes an accessibility label so it is safe to stamp on the container. */
    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
