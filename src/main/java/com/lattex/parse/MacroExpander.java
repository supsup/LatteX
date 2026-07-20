package com.lattex.parse;

import com.lattex.parse.MathParser.Kind;
import com.lattex.parse.MathParser.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Token-level user-macro expansion (L8, plan 68f2eb85): {@code \newcommand} /
 * {@code \renewcommand} / {@code \def} subset with {@code #1..#9} argument
 * splicing, plus caller-supplied preset macros ({@code RenderOptions.macros} —
 * server-side per-tenant notation packs).
 *
 * <p>Runs between {@link MathParser#lex} and parsing: definitions are consumed
 * from the token stream, invocations are replaced by their substituted bodies,
 * and the parser then sees only tokens it already understands. The pass is
 * <em>additive-only by construction</em>: a macro may not use the name of any
 * built-in command — decided by asking the PARSER itself (the probe in
 * {@code denyBuiltin}, one authority, no hand list to drift) — so expansion can never
 * change the meaning of input that parsed before macros existed — the no-macro
 * path returns the token list untouched, byte-identical. {@code \renewcommand}
 * redefines <em>user</em> macros only. This is deliberately narrower than TeX
 * (which allows shadowing anything); documented as the L8 subset.
 *
 * <p>Scoping subset: definitions are global to the input (no brace scoping) and
 * may appear anywhere at the top level of the stream, but not inside another
 * macro's body. Macros do not reach nested {@code $…$} spans inside
 * {@code \text{…}} (those re-parse from the raw text token) — documented limit.
 *
 * <p>DoS posture mirrors the existing guard family's depth-vs-fanout split:
 * runaway <em>recursion</em> ({@code \newcommand{\a}{\a\a}}) trips
 * {@link #MAX_MACRO_DEPTH} as a positioned author-facing error (the
 * {@link MathParser#MAX_DEPTH} shape), while an expansion <em>bomb</em> that
 * stays shallow but multiplies trips {@link #MAX_MACRO_EXPANSIONS} as
 * {@link MathSyntaxException#capExceeded} (the {@code MAX_LAYOUT_BOXES} shape),
 * so diagnostics classify it as a resource cap. Both fail closed.
 */
final class MacroExpander {

    /** Maximum macro-invocation nesting depth — runaway recursion fails closed. */
    static final int MAX_MACRO_DEPTH = 64;

    /** Maximum total macro invocations per input — expansion bombs fail closed. */
    static final int MAX_MACRO_EXPANSIONS = 10_000;

    /**
     * Maximum total MATERIALIZED replacement tokens per input (review lattex 253
     * F2): invocation count alone cannot see splice VOLUME — a legal ~4KB source
     * (1,000-token body invoked 1,000 times) materialized 1,000,001 tokens with
     * every earlier cap green. The third resource axis beside depth and count;
     * trips as {@link MathSyntaxException#capExceeded} before large lists build.
     */
    static final int MAX_MACRO_OUTPUT_TOKENS = 100_000;

    /**
     * The definition keywords themselves — consumed by this expander, unknown to
     * the parser, so the parser-authority probe below cannot vouch for them.
     * The ONLY hand-maintained deny entries; everything else asks the parser.
     */
    private static final Set<String> DEFINITION_KEYWORDS = Set.of("newcommand", "renewcommand", "def");

    private record MacroDef(String name, int numArgs, List<Token> body, boolean inlineSource) {}

    private final Map<String, MacroDef> defs = new HashMap<>();
    private int expansions;
    private int outputTokens;
    private int presetChars;

    private MacroExpander() {
    }

    /**
     * Expands {@code tokens} against the caller-supplied preset macros plus any
     * inline definitions found in the stream. Returns the input list unchanged
     * (same instance — provably byte-identical downstream) when there are no
     * presets and no definition commands in the stream.
     */
    static List<Token> expand(List<Token> tokens, Map<String, String> presets) {
        if (presets.isEmpty() && !containsDefinition(tokens)) {
            return tokens;
        }
        MacroExpander ex = new MacroExpander();
        for (Map.Entry<String, String> e : presets.entrySet()) {
            ex.registerPreset(e.getKey(), e.getValue());
        }
        return ex.expandRun(tokens, true, 0);
    }

    private static boolean containsDefinition(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.kind() == Kind.COMMAND
                    && ("newcommand".equals(t.name()) || "renewcommand".equals(t.name())
                        || "def".equals(t.name()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    /** A preset macro from {@code RenderOptions.macros}: body lexed once, arity inferred from max #k. */
    private void registerPreset(String name, String body) {
        if (name == null || name.isEmpty() || !name.chars().allMatch(MacroExpander::isAsciiLetter)) {
            throw new MathSyntaxException(
                "preset macro name must be one or more ASCII letters, got: " + name);
        }
        denyBuiltin(name, 0);
        if (body == null) {
            throw new MathSyntaxException("preset macro \\" + name + " has a null body");
        }
        // The normal source ceiling applies to preset bodies too (review lattex 253
        // F2): they lex through the same lexer as source, so an unused 100k+ body
        // was a resource bypass. CUMULATIVE across the map — the map is one input.
        presetChars += body.length();
        if (presetChars > MathParser.MAX_SOURCE_LENGTH) {
            throw new MathSyntaxException(
                "preset macro bodies too large: cumulative " + presetChars
                    + " chars exceeds the " + MathParser.MAX_SOURCE_LENGTH + "-char source ceiling");
        }
        List<Token> bodyTokens;
        try {
            bodyTokens = stripEof(MathParser.lex(body));
        } catch (MathSyntaxException e) {
            throw new MathSyntaxException(
                "preset macro \\" + name + " body does not lex: " + e.getMessage());
        }
        int arity = inferArity(bodyTokens, name);
        defs.put(name, new MacroDef(name, arity, bodyTokens, false));
    }

    /**
     * Consumes one inline definition starting at {@code run.get(i)} (the
     * {@code \newcommand}/{@code \renewcommand}/{@code \def} token itself) and
     * registers it. Returns the index just past the definition.
     */
    private int consumeDefinition(List<Token> run, int i) {
        Token kw = run.get(i);
        boolean isDef = "def".equals(kw.name());
        boolean isRenew = "renewcommand".equals(kw.name());
        int j = i + 1;

        String name;
        if (isDef) {
            // \def\name{body}
            j = expect(run, j, Kind.COMMAND, "\\def expects a \\name", kw.offset());
            name = run.get(j - 1).name();
        } else {
            // \newcommand{\name}[n]{body} — the braces around \name are required in this subset.
            j = expect(run, j, Kind.LBRACE, "\\" + kw.name() + " expects {\\name}", kw.offset());
            j = expect(run, j, Kind.COMMAND, "\\" + kw.name() + " expects {\\name}", kw.offset());
            name = run.get(j - 1).name();
            j = expect(run, j, Kind.RBRACE, "\\" + kw.name() + " expects {\\name}", kw.offset());
        }
        if (!name.chars().allMatch(MacroExpander::isAsciiLetter)) {
            throw new MathSyntaxException(
                "macro name \\" + name + " must be ASCII letters only", kw.offset());
        }
        denyBuiltin(name, kw.offset());
        if (isRenew && !defs.containsKey(name)) {
            throw new MathSyntaxException(
                "\\renewcommand: \\" + name + " is not a defined macro (use \\newcommand)",
                kw.offset());
        }
        if (!isRenew && !isDef && defs.containsKey(name)) {
            throw new MathSyntaxException(
                "\\newcommand: \\" + name + " is already defined (use \\renewcommand)",
                kw.offset());
        }

        int declaredArgs = -1; // \newcommand's optional [n]
        if (!isDef && j < run.size() && isChar(run.get(j), '[')) {
            Token digit = j + 1 < run.size() ? run.get(j + 1) : null;
            if (digit == null || digit.kind() != Kind.CHAR
                    || digit.codePoint() < '0' || digit.codePoint() > '9'
                    || j + 2 >= run.size() || !isChar(run.get(j + 2), ']')) {
                throw new MathSyntaxException(
                    "\\" + kw.name() + ": malformed [n] argument count", kw.offset());
            }
            declaredArgs = digit.codePoint() - '0';
            j += 3;
        }

        j = expect(run, j, Kind.LBRACE, "\\" + kw.name() + " expects a {body}", kw.offset());
        int bodyStart = j;
        int bodyEnd = matchingRbrace(run, bodyStart - 1, kw.offset());
        List<Token> body = new ArrayList<>(run.subList(bodyStart, bodyEnd));
        int inferred = inferArity(body, name);
        int arity = declaredArgs >= 0 ? declaredArgs : inferred;
        if (inferred > arity) {
            throw new MathSyntaxException(
                "macro \\" + name + " body references #" + inferred + " but declares only ["
                    + arity + "] argument(s)", kw.offset());
        }
        for (Token t : body) {
            if (t.kind() == Kind.COMMAND
                    && ("newcommand".equals(t.name()) || "renewcommand".equals(t.name())
                        || "def".equals(t.name()))) {
                throw new MathSyntaxException(
                    "macro \\" + name + " body may not contain definitions", kw.offset());
            }
        }
        defs.put(name, new MacroDef(name, arity, body, true));
        return bodyEnd + 1; // past the closing brace
    }

    /** Max {@code #k} referenced in a body; also rejects a stray {@code #}. */
    private static int inferArity(List<Token> body, String name) {
        int max = 0;
        for (int i = 0; i < body.size(); i++) {
            if (isChar(body.get(i), '#')) {
                Token nxt = i + 1 < body.size() ? body.get(i + 1) : null;
                if (nxt == null || nxt.kind() != Kind.CHAR
                        || nxt.codePoint() < '1' || nxt.codePoint() > '9') {
                    throw new MathSyntaxException(
                        "macro \\" + name + " body has a stray # (expected #1..#9)",
                        body.get(i).offset());
                }
                max = Math.max(max, nxt.codePoint() - '1' + 1);
                i++;
            }
        }
        return max;
    }

    /**
     * The additive-only deny check, derived from ONE parser authority (review
     * lattex 253 F1 — the hand-maintained structural list omitted live commands;
     * fbox/mkern/kern/mskip/hdashline/nolimits were all shadowable). A name is
     * built-in iff the PARSER ITSELF does not reject it as an unknown command:
     * the probe parses {@code \name} with no macros and inspects the failure.
     * Success, or ANY failure other than "Unknown command: \name" (missing
     * argument, wrong context, …), means the parser knows the name — deny.
     * Only a verbatim unknown-command rejection proves the name is free. The
     * three definition keywords are the sole hand-maintained entries (this
     * expander consumes them; the parser cannot vouch either way).
     */
    private void denyBuiltin(String name, int offset) {
        boolean builtin;
        if (DEFINITION_KEYWORDS.contains(name)) {
            builtin = true;
        } else {
            try {
                MathParser.parseMath("\\" + name, 0, Map.of());
                builtin = true; // parses clean -> the parser owns this name
            } catch (MathSyntaxException e) {
                String msg = String.valueOf(e.getMessage());
                builtin = !msg.startsWith("Unknown command: \\" + name);
            } catch (RuntimeException anythingElse) {
                builtin = true; // fail closed: an odd probe failure never frees a name
            }
        }
        if (builtin) {
            throw new MathSyntaxException(
                "cannot define \\" + name + ": it is a built-in command (user macros are "
                    + "additive-only in this subset)", offset);
        }
    }

    // ------------------------------------------------------------------
    // Expansion
    // ------------------------------------------------------------------

    /**
     * Expands one token run. {@code allowDefinitions} is true only for the
     * top-level stream; substituted macro bodies may not define. {@code depth}
     * counts invocation nesting for the recursion cap.
     */
    private List<Token> expandRun(List<Token> run, boolean allowDefinitions, int depth) {
        List<Token> out = new ArrayList<>(run.size());
        int i = 0;
        while (i < run.size()) {
            Token t = run.get(i);
            if (t.kind() == Kind.COMMAND) {
                String name = t.name();
                if ("newcommand".equals(name) || "renewcommand".equals(name) || "def".equals(name)) {
                    if (!allowDefinitions) {
                        throw new MathSyntaxException(
                            "macro bodies may not contain definitions", t.offset());
                    }
                    i = consumeDefinition(run, i);
                    continue;
                }
                MacroDef def = defs.get(name);
                if (def != null) {
                    i = expandInvocation(run, i, def, depth, out);
                    continue;
                }
            }
            out.add(t);
            i++;
        }
        return out;
    }

    /**
     * Expands one invocation at {@code run.get(i)}: collects {@code numArgs}
     * arguments (a brace group, or a single token — the LaTeX undelimited-arg
     * rule), substitutes them into the body, recursively expands the result,
     * and appends to {@code out}. Returns the index just past the arguments.
     */
    private int expandInvocation(List<Token> run, int i, MacroDef def, int depth, List<Token> out) {
        Token call = run.get(i);
        if (depth >= MAX_MACRO_DEPTH) {
            // Runaway recursion: an author-facing positioned error, the MAX_DEPTH shape.
            throw new MathSyntaxException(
                "macro recursion too deep: \\" + def.name() + " exceeds " + MAX_MACRO_DEPTH
                    + " nested expansions (is it defined in terms of itself?)", call.offset());
        }
        if (++expansions > MAX_MACRO_EXPANSIONS) {
            // Expansion bomb: a resource cap, the MAX_LAYOUT_BOXES shape.
            throw MathSyntaxException.capExceeded(
                "macro expansion budget exceeded: more than " + MAX_MACRO_EXPANSIONS
                    + " invocations in one input");
        }
        int j = i + 1;
        List<List<Token>> args = new ArrayList<>(def.numArgs());
        for (int a = 0; a < def.numArgs(); a++) {
            if (j >= run.size() || run.get(j).kind() == Kind.EOF) {
                throw new MathSyntaxException(
                    "macro \\" + def.name() + " expects " + def.numArgs()
                        + " argument(s), got " + a, call.offset());
            }
            Token first = run.get(j);
            if (first.kind() == Kind.LBRACE) {
                int end = matchingRbrace(run, j, call.offset());
                args.add(new ArrayList<>(run.subList(j + 1, end)));
                j = end + 1;
            } else if (first.kind() == Kind.RBRACE) {
                throw new MathSyntaxException(
                    "macro \\" + def.name() + " expects " + def.numArgs()
                        + " argument(s), got " + a, call.offset());
            } else {
                args.add(List.of(first));
                j++;
            }
        }

        // The output-token budget (lattex 253 F2): count what THIS invocation will
        // materialize (body + spliced argument runs) before building it, so the
        // 1,000x1,000 amplification trips at the budget, not at the allocator.
        int willMaterialize = def.body().size();
        for (List<Token> arg : args) {
            willMaterialize += arg.size();
        }
        outputTokens += willMaterialize;
        if (outputTokens > MAX_MACRO_OUTPUT_TOKENS) {
            throw MathSyntaxException.capExceeded(
                "macro expansion output budget exceeded: more than " + MAX_MACRO_OUTPUT_TOKENS
                    + " materialized replacement tokens in one input");
        }
        List<Token> substituted = new ArrayList<>(def.body().size());
        for (int b = 0; b < def.body().size(); b++) {
            Token bt = def.body().get(b);
            if (isChar(bt, '#')) {
                int k = def.body().get(b + 1).codePoint() - '1'; // shape validated at definition
                for (Token at : args.get(k)) {
                    substituted.add(at); // argument tokens keep their true source offsets
                }
                b++;
            } else {
                // Inline-def body tokens carry valid offsets in THIS source (they point at the
                // definition — a sensible caret); preset bodies come from a different string, so
                // their offsets are re-stamped to the invocation site.
                substituted.add(def.inlineSource() ? bt : restamp(bt, call.offset()));
            }
        }
        out.addAll(expandRun(substituted, false, depth + 1));
        return j;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static Token restamp(Token t, int offset) {
        return new Token(t.kind(), t.codePoint(), t.name(), t.text(), offset);
    }

    private static List<Token> stripEof(List<Token> tokens) {
        List<Token> out = new ArrayList<>(tokens);
        if (!out.isEmpty() && out.get(out.size() - 1).kind() == Kind.EOF) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** The index of the RBRACE matching the LBRACE at {@code lbrace} (same run, balanced). */
    private static int matchingRbrace(List<Token> run, int lbrace, int errOffset) {
        int depth = 0;
        for (int i = lbrace; i < run.size(); i++) {
            Kind k = run.get(i).kind();
            if (k == Kind.LBRACE) {
                depth++;
            } else if (k == Kind.RBRACE) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            } else if (k == Kind.EOF) {
                break;
            }
        }
        throw new MathSyntaxException("unbalanced braces in macro definition or argument", errOffset);
    }

    /** Advances past one expected token kind or throws a positioned error. */
    private static int expect(List<Token> run, int i, Kind kind, String what, int errOffset) {
        if (i >= run.size() || run.get(i).kind() != kind) {
            throw new MathSyntaxException(what, errOffset);
        }
        return i + 1;
    }

    private static boolean isChar(Token t, char c) {
        return t.kind() == Kind.CHAR && t.codePoint() == c;
    }

    private static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
