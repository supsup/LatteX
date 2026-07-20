package com.lattex.parse;

import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.LimitsMode;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import com.lattex.parse.MathNode.RowRule;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.api.Color;
import com.lattex.api.RenderOptions;
import com.lattex.layout.MathStyle;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.StyledMath;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.lattex.parse.Symbols.ACCENTS;
import static com.lattex.parse.Symbols.BIG_OPERATORS;
import static com.lattex.parse.Symbols.FONT_VARIANTS;
import static com.lattex.parse.Symbols.NAMED_OPS;
import static com.lattex.parse.Symbols.NEGATION;
import static com.lattex.parse.Symbols.SPACES;
import static com.lattex.parse.Symbols.SYMBOLS;
import static com.lattex.parse.Symbols.TEXT_COMMANDS;
import com.lattex.parse.Symbols.AccentSpec;
import com.lattex.parse.Symbols.OpSpec;
import com.lattex.parse.Symbols.Sym;

/**
 * A hand-written recursive-descent parser for the MVP LaTeX-math grammar,
 * producing the {@link MathNode} ADT. Written clean-room from the LaTeX/TeX
 * grammar (TeXbook Ch.7 &amp; 16-18) — no GPL renderer source consulted.
 *
 * <p>Grammar (informal EBNF; whitespace is insignificant in math mode):
 * <pre>
 *   input     := list EOF
 *   list      := component*
 *   component := nucleus scripts?          // ordinary
 *              | bigOp limitsMode? scripts? // \sum \int \prod
 *   scripts   := ('^' arg | '_' arg){0,2}  // at most one of each
 *   nucleus   := char
 *              | '{' list '}'
 *              | '\frac' group group
 *              | '\sqrt' ('[' list ']')? group
 *              | '\left' delim list '\right' delim
 *              | '\'command                // symbol / greek / spacing
 *   arg       := nucleus                   // single unit; '{' opens a group
 * </pre>
 *
 * <p>The lexer treats {@code { } ^ _ \\} specially and everything else as an
 * ordinary character (or the start of a control sequence). Malformed input
 * throws {@link MathSyntaxException} naming the problem.
 */
public final class MathParser {

    // ------------------------------------------------------------------
    // Parse-time DoS guards
    // ------------------------------------------------------------------
    // The parser is unbounded recursive descent, and its input is
    // adversary-reachable (an author's LaTeX flows in via the '\lx' macro).
    // Without these caps, a pathological input such as "{".repeat(200000)
    // overflows the JVM stack and throws a raw StackOverflowError — an Error,
    // NOT a RuntimeException, so it escapes the caller's `catch (RuntimeException)`
    // guards and crashes the render thread. Both caps instead throw
    // MathSyntaxException (a caught RuntimeException) with a clear message.

    /** Maximum accepted source length, in {@code char}s. */
    static final int MAX_SOURCE_LENGTH = 100_000;

    /** Maximum recursive nesting depth (groups, arguments, scripts, fences). */
    static final int MAX_DEPTH = 512;

    /** Current recursion depth; guarded against {@link #MAX_DEPTH} in {@link #parseNucleus}. */
    private int depth;

    // Lexer
    // ------------------------------------------------------------------
    enum Kind { CHAR, COMMAND, TEXT, LBRACE, RBRACE, SUP, SUB, EOF }

    /**
     * A lexer token. For {@link Kind#TEXT} the {@code name} holds the text-family
     * command (e.g. {@code "textbf"}) and {@code text} the raw brace content with
     * spaces preserved (text mode is space-significant); for other kinds {@code
     * text} is {@code null}.
     */
    record Token(Kind kind, int codePoint, String name, String text, int offset) {
        static Token special(Kind k, int offset) {
            return new Token(k, 0, null, null, offset);
        }

        static Token ch(int cp, int offset) {
            return new Token(Kind.CHAR, cp, null, null, offset);
        }

        static Token cmd(String name, int offset) {
            return new Token(Kind.COMMAND, 0, name, null, offset);
        }

        static Token text(String command, String raw, int offset) {
            return new Token(Kind.TEXT, 0, command, raw, offset);
        }
    }

    private final List<Token> tokens;
    private int p;

    private MathParser(String src) {
        this(src, Map.of());
    }

    /**
     * Lexes and then macro-expands {@code src} (L8): user macros — inline
     * {@code \newcommand}/{@code \renewcommand}/{@code \def} definitions plus
     * caller-supplied presets — are spliced into the token stream before any
     * parsing, so the parser only ever sees tokens it already understands. With
     * no presets and no inline definitions the expansion returns the lexed list
     * untouched — byte-identical to the pre-macro pipeline.
     */
    private MathParser(String src, Map<String, String> macros) {
        this.tokens = MacroExpander.expand(lex(src), macros);
    }

    /** Package-private for {@link MacroExpander} (preset bodies lex through the same lexer). */
    static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++; // whitespace is insignificant in math mode
                continue;
            }
            int start = i; // source offset of the token about to be lexed (for error carets)
            switch (c) {
                case '\\' -> {
                    i++;
                    if (i >= n) {
                        throw new MathSyntaxException(
                            "Dangling '\\' at end of input (expected a command name)", start);
                    }
                    char d = s.charAt(i);
                    if (isAsciiLetter(d)) {
                        int j = i;
                        while (j < n && isAsciiLetter(s.charAt(j))) {
                            j++;
                        }
                        String name = s.substring(i, j);
                        i = j;
                        if (TEXT_COMMANDS.containsKey(name)) {
                            // Capture the {…} argument raw so text-mode spaces
                            // survive (math mode strips whitespace); grouping
                            // braces are invisible, as in LaTeX.
                            i = lexTextArgument(s, name, i, out, start);
                        } else {
                            out.add(Token.cmd(name, start));
                        }
                    } else {
                        // Single-character control sequence: \, \{ \} \| \! etc.
                        out.add(Token.cmd(String.valueOf(d), start));
                        i++;
                    }
                }
                case '{' -> {
                    out.add(Token.special(Kind.LBRACE, start));
                    i++;
                }
                case '}' -> {
                    out.add(Token.special(Kind.RBRACE, start));
                    i++;
                }
                case '^' -> {
                    out.add(Token.special(Kind.SUP, start));
                    i++;
                }
                case '_' -> {
                    out.add(Token.special(Kind.SUB, start));
                    i++;
                }
                default -> {
                    int cp = s.codePointAt(i);
                    out.add(Token.ch(cp, start));
                    i += Character.charCount(cp);
                }
            }
        }
        out.add(Token.special(Kind.EOF, n));
        return out;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Reads the {@code {…}} argument of a text-family command starting at index
     * {@code i} (just past the command name), emitting a single {@link Kind#TEXT}
     * token, and returns the index just past the closing brace. Spaces are kept
     * verbatim (text mode is space-significant), and the content — INCLUDING its
     * interior grouping braces — is carried verbatim on the token: a nested
     * {@code $…$} span needs the brace structure for its re-parse (stripping here
     * silently corrupted {@code \text{$\frac{12}{34}$}} into {@code \frac1234} —
     * Conf review lattex/210 F1). Brace-invisibility for the LITERAL text runs is
     * applied later, in {@link #textWithNestedMath}, never to a math span.
     */
    private static int lexTextArgument(String s, String command, int i, List<Token> out, int start) {
        int n = s.length();
        while (i < n && isWhitespace(s.charAt(i))) {
            i++; // skip space between the control word and its argument
        }
        if (i >= n || s.charAt(i) != '{') {
            throw new MathSyntaxException(
                "\\" + command + " expects a '{...}' text argument", start);
        }
        i++; // consume '{'
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
                sb.append(c); // interior brace: KEEP (verbatim content)
                i++;
            } else if (c == '}') {
                depth--;
                i++;
                if (depth == 0) {
                    out.add(Token.text(command, sb.toString(), start));
                    return i;
                }
                sb.append(c); // interior brace: KEEP (verbatim content)
            } else {
                int cp = s.codePointAt(i);
                sb.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        throw new MathSyntaxException("Unbalanced brace in \\" + command + " argument", start);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Parses a LaTeX math source string into a {@link MathNode}.
     *
     * <p>If the whole (trimmed) input is the author's {@code \lx[options]{body}}
     * macro, it is parsed into a {@link StyledMath} wrapper — every option
     * validated and reduced to a typed value there (see {@link LxOptionsParser#parseLx}).
     * Otherwise the input is ordinary LaTeX math ({@link #parseMath}). A {@code \lx}
     * that is <em>not</em> the whole top-level expression surfaces through
     * {@link #parseCommand} as a clear "nested" error (top-level-only for the MVP).
     */
    public static MathNode parse(String latex) {
        return parse(latex, Map.of());
    }

    /**
     * Parses with caller-supplied preset macros (L8, {@code RenderOptions.macros}):
     * each entry is a user-macro name (ASCII letters, no backslash) mapped to its
     * replacement body; arity is inferred from the highest {@code #k} in the body.
     * Presets share one namespace with inline definitions and the same
     * additive-only rule (no built-in names). An empty map is the byte-identical
     * fast path.
     */
    public static MathNode parse(String latex, Map<String, String> macros) {
        if (latex == null) {
            throw new MathSyntaxException("input must not be null");
        }
        if (macros == null) {
            throw new MathSyntaxException("macros must not be null (use Map.of())");
        }
        if (latex.length() > MAX_SOURCE_LENGTH) {
            throw new MathSyntaxException(
                "input too long: " + latex.length() + " chars exceeds the "
                    + MAX_SOURCE_LENGTH + "-char limit");
        }
        if (LxOptionsParser.looksLikeTopLevelLx(latex)) {
            return LxOptionsParser.parseLx(latex.strip(), macros);
        }
        return parseMath(latex, 0, macros);
    }

    /** Parses ordinary LaTeX math (no top-level {@code \lx}). */
    static MathNode parseMath(String latex) {
        return parseMath(latex, 0);
    }

    /**
     * Parses ordinary LaTeX math seeded at {@code initialDepth}. Used by the
     * nested-math text split ({@code \text{a $x$ b}}): the inner span's parser
     * CONTINUES the outer parser's depth so the {@link #MAX_DEPTH} guard is global
     * across {@code $}-nesting — a fresh counter per level would let
     * {@code \text{$\text{$…}$}} recurse past the guard into a stack overflow.
     */
    static MathNode parseMath(String latex, int initialDepth) {
        return parseMath(latex, initialDepth, Map.of());
    }

    /**
     * Full form: depth-seeded (see above) and macro-carrying (L8). Nested
     * {@code $…$} spans inside {@code \text{…}} re-parse through the two-arg
     * overload, so user macros do NOT reach them — the documented L8 subset
     * limit (a nested span is opaque raw text at expansion time).
     */
    static MathNode parseMath(String latex, int initialDepth, Map<String, String> macros) {
        try {
            MathParser parser = new MathParser(latex, macros);
            parser.depth = initialDepth;
            MathNode node = parser.parseTopLevel();
            if (parser.peek().kind() != Kind.EOF) {
                throw new MathSyntaxException(
                    "Unexpected trailing input", parser.peek().offset());
            }
            return node;
        } catch (MathSyntaxException e) {
            // Attach the source as the error propagates out, so caretString() can point
            // at the column. Idempotent: an inner frame's source (if any) is kept.
            e.attachSource(latex);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Token cursor helpers
    // ------------------------------------------------------------------
    Token peek() {
        return tokens.get(p);
    }

    Token next() {
        return tokens.get(p++);
    }

    /** Source offset of the current token, for a positioned {@link MathSyntaxException}. */
    int currentOffset() {
        return peek().offset();
    }

    boolean isCommand(Token t, String name) {
        return t.kind() == Kind.COMMAND && t.name().equals(name);
    }

    // ------------------------------------------------------------------
    // Grammar
    // ------------------------------------------------------------------

    private MathNode parseTopLevel() {
        List<MathNode> items = new ArrayList<>();
        MathNode tag = null;
        while (peek().kind() != Kind.EOF) {
            // \tag{label} is equation-global: hoist it out of the component stream and
            // attach it to the whole equation, wherever it appears in the source.
            if (isCommand(peek(), "tag")) {
                next(); // consume \tag
                if (tag != null) {
                    throw new MathSyntaxException("Multiple \\tag on one equation");
                }
                if (peek().kind() != Kind.LBRACE) {
                    throw new MathSyntaxException("\\tag expects a {label} group");
                }
                tag = parseGroup();
                continue;
            }
            // A TeX INFIX fraction operator (\over/\atop/...) splits its enclosing
            // group: everything BEFORE it is the numerator, everything after (to the
            // group boundary — here EOF) is the denominator. It becomes the group's
            // sole result, so once seen we finish the fraction and stop accumulating.
            if (isInfixFraction(peek())) {
                MathNode frac = splitInfixDenominator(next().name(), items, true);
                items = new ArrayList<>();
                items.add(frac);
                break;
            }
            items.add(parseComponent());
        }
        MathNode body = wrap(items);
        return tag == null ? body : new MathNode.Tagged(body, tag);
    }

    /** A brace group {@code '{' list '}'}. Assumes the current token is LBRACE. */
    private MathNode parseGroup() {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "Expected '{' but found " + describe(peek()), currentOffset());
        }
        next(); // consume '{'
        List<MathNode> items = new ArrayList<>();
        while (peek().kind() != Kind.RBRACE) {
            if (peek().kind() == Kind.EOF) {
                throw new MathSyntaxException("Unbalanced brace: missing '}'", currentOffset());
            }
            // A TeX INFIX fraction operator (\over/\atop/...) splits THIS group into
            // numerator (items so far) over denominator (the rest, to the '}'), and
            // becomes the group's sole result. splitInfixDenominator stops at the
            // '}', which we consume here.
            if (isInfixFraction(peek())) {
                MathNode frac = splitInfixDenominator(next().name(), items, false);
                next(); // consume '}'
                return frac;
            }
            items.add(parseComponent());
        }
        next(); // consume '}'
        return wrap(items);
    }

    /** The five TeX INFIX fraction operators (command names, no backslash). */
    private static final Set<String> INFIX_FRACTIONS =
        Set.of("over", "atop", "choose", "brace", "brack");

    /** Whether {@code t} is one of the five INFIX fraction operators. */
    private boolean isInfixFraction(Token t) {
        return t.kind() == Kind.COMMAND && INFIX_FRACTIONS.contains(t.name());
    }

    /**
     * Completes a TeX INFIX fraction ({@code \over}, {@code \atop}, {@code \choose},
     * {@code \brace}, {@code \brack}). Unlike {@code \frac}/{@code \binom}, these sit
     * BETWEEN a numerator and a denominator and split their enclosing group: the
     * operator ({@code op}, a command name with no backslash) has just been consumed,
     * {@code numItems} are the components accumulated before it (the numerator), and
     * the denominator is the REST of this group parsed with the ordinary component
     * parser up to the group boundary — {@code '}'} for a {@code {...}} group,
     * {@code EOF} at top level. Because the denominator uses {@link #parseComponent},
     * scripts bind normally inside it ({@code a \over b^2} → denominator {@code b^2}).
     *
     * <p>TeX allows at most ONE infix operator per group; a second one here is
     * ambiguous and throws (never a silent mis-nest). The terminator ({@code '}'}/EOF)
     * is left in place for the caller to consume.
     */
    private MathNode splitInfixDenominator(String op, List<MathNode> numItems, boolean topLevel) {
        MathNode numerator = wrap(numItems);
        List<MathNode> denItems = new ArrayList<>();
        while (true) {
            Kind k = peek().kind();
            if (k == Kind.EOF) {
                if (topLevel) {
                    break;
                }
                throw new MathSyntaxException("Unbalanced brace: missing '}'", currentOffset());
            }
            if (!topLevel && k == Kind.RBRACE) {
                break;
            }
            if (isInfixFraction(peek())) {
                throw new MathSyntaxException(
                    "ambiguous: multiple infix fraction operators "
                    + "(\\over/\\atop/\\choose/\\brace/\\brack) in one group",
                    currentOffset());
            }
            denItems.add(parseComponent());
        }
        return makeInfixFraction(op, numerator, wrap(denItems));
    }

    /**
     * Maps an INFIX fraction operator to its node, reusing the {@link Fraction} /
     * {@link Fenced} construction of {@code \frac} and {@code \binom}:
     * {@code \over} is a ruled fraction; {@code \atop} a rule-less one; and
     * {@code \choose}/{@code \brace}/{@code \brack} are rule-less fractions fenced by
     * {@code ()}/{@code
     * {}}/{@code []} respectively (the {@code \binom} construction with a different
     * delimiter pair).
     */
    private static MathNode makeInfixFraction(String op, MathNode num, MathNode den) {
        return switch (op) {
            case "over"   -> new Fraction(num, den, true, MathNode.FractionStyle.INHERIT);
            case "atop"   -> new Fraction(num, den, false, MathNode.FractionStyle.INHERIT);
            case "choose" -> new Fenced('(',
                new Fraction(num, den, false, MathNode.FractionStyle.INHERIT), ')');
            case "brace"  -> new Fenced('{',
                new Fraction(num, den, false, MathNode.FractionStyle.INHERIT), '}');
            case "brack"  -> new Fenced('[',
                new Fraction(num, den, false, MathNode.FractionStyle.INHERIT), ']');
            default -> throw new IllegalStateException(
                "not an infix fraction operator: \\" + op);
        };
    }

    /** Maps an {@code \x...} extensible-arrow command name (no backslash) to its shaft kind. */
    private static MathNode.XArrowKind xArrowKind(String name) {
        return switch (name) {
            case "xrightarrow" -> MathNode.XArrowKind.RIGHT;
            case "xleftarrow" -> MathNode.XArrowKind.LEFT;
            case "xleftrightarrow" -> MathNode.XArrowKind.LEFTRIGHT;
            case "xRightarrow" -> MathNode.XArrowKind.RIGHT_DBL;
            case "xLeftarrow" -> MathNode.XArrowKind.LEFT_DBL;
            case "xLeftrightarrow" -> MathNode.XArrowKind.LEFTRIGHT_DBL;
            case "xmapsto" -> MathNode.XArrowKind.MAPSTO;
            case "xhookrightarrow" -> MathNode.XArrowKind.HOOK_RIGHT;
            case "xhookleftarrow" -> MathNode.XArrowKind.HOOK_LEFT;
            case "xrightleftharpoons" -> MathNode.XArrowKind.RIGHTLEFTHARPOONS;
            case "xlongequal" -> MathNode.XArrowKind.LONGEQUAL;
            default -> throw new IllegalStateException("not an extensible arrow: " + name);
        };
    }

    /**
     * A bare {@code \displaystyle}-family switch: it restyles everything after it to the
     * end of the enclosing group, so greedily consume components up to the group boundary
     * ({@code }}, EOF, or a matrix cell/row separator) and wrap them. Nesting handles
     * override — a later {@code \textstyle} in the same group parses as an inner switch.
     */
    private MathNode parseStyleSwitch(String name) {
        MathNode.StyleLevel level = switch (name) {
            case "displaystyle" -> MathNode.StyleLevel.DISPLAY;
            case "textstyle" -> MathNode.StyleLevel.TEXT;
            case "scriptstyle" -> MathNode.StyleLevel.SCRIPT;
            case "scriptscriptstyle" -> MathNode.StyleLevel.SCRIPT_SCRIPT;
            default -> throw new IllegalStateException("not a style switch: " + name);
        };
        List<MathNode> rest = new ArrayList<>();
        while (!isStyleSwitchBoundary(peek())) {
            rest.add(parseComponent());
        }
        return new MathNode.StyleSwitch(level, wrap(rest));
    }

    /**
     * A bare {@code \color{c}} SWITCH (the switch form of {@code \textcolor{c}{body}}): it
     * recolours everything after it to the end of the enclosing group, so — exactly like
     * {@link #parseStyleSwitch} — greedily consume components up to the group boundary
     * ({@code }}, EOF, or a matrix cell/row separator) and wrap them in a {@link
     * MathNode.Colored}. Nesting handles override: a later {@code \color} in the same group
     * parses as an inner {@code Colored} for what follows it, and inner wins at layout (same
     * fill precedence as nested {@code \textcolor}). The colour name/hex is validated through
     * {@link Color} — the single raw-string-to-fill boundary — so a bogus colour fails loud,
     * never an emitted raw string.
     */
    private MathNode parseColorSwitch() {
        Color color = parseColorArg("\\color");
        List<MathNode> rest = new ArrayList<>();
        while (!isStyleSwitchBoundary(peek())) {
            rest.add(parseComponent());
        }
        return new MathNode.Colored(wrap(rest), color);
    }

    /** Where a {@code \displaystyle}-family switch stops consuming: group end or a cell/row sep. */
    private boolean isStyleSwitchBoundary(Token t) {
        return switch (t.kind()) {
            case RBRACE, EOF -> true;
            case CHAR -> t.codePoint() == '&';                              // matrix column separator
            case COMMAND -> t.name().equals("\\") || t.name().equals("cr"); // matrix row separator
            default -> false;
        };
    }

    /** One component: a nucleus, plus any scripts (or big-operator limits). */
    MathNode parseComponent() {
        MathNode nucleus = parseNucleus();
        // A large operator carries its scripts as limits, not as a SupSub.
        if (nucleus instanceof Atom op && op.mathClass() == MathClass.OP) {
            return parseBigOperator(op);
        }
        // \\underbrace/\overbrace take their label as a following limit script
        // (^ above the brace, _ below it), exactly as a large operator takes limits.
        if (nucleus instanceof MathNode.Stack st && st.takesLimitLabel()) {
            return parseStackLabel(st);
        }
        return parseScripts(nucleus);
    }

    /**
     * Attaches a {@code \\underbrace}/{@code \overbrace} label from a following
     * {@code ^} (above the brace) and/or {@code _} (below it), mirroring
     * {@link #parseBigOperator}'s limit loop. The label sits at script size in
     * layout. A bare {@code \\underbrace{x}} with no script is left label-less.
     */
    private MathNode parseStackLabel(MathNode.Stack st) {
        MathNode.Stack out = st;
        while (true) {
            Kind k = peek().kind();
            if (k == Kind.SUP) {
                if (out.above() != null) {
                    throw new MathSyntaxException("Double superscript on \\" + braceCommand(st));
                }
                next();
                out = out.withAbove(parseScriptArg("brace label"));
            } else if (k == Kind.SUB) {
                if (out.below() != null) {
                    throw new MathSyntaxException("Double subscript on \\" + braceCommand(st));
                }
                next();
                out = out.withBelow(parseScriptArg("brace label"));
            } else {
                break;
            }
        }
        return out;
    }

    private static String braceCommand(MathNode.Stack st) {
        return st.kind() == MathNode.StackKind.OVERBRACE ? "overbrace" : "underbrace";
    }

    /**
     * Attaches an optional {@code ^}/{@code _} pair to an ordinary nucleus.
     *
     * <p>A math-mode {@code '} is TeX's {@code ^{\prime}}: it contributes a
     * prime glyph ({@code U+2032 ′}) to the superscript. Primes accumulate in
     * source order and merge with a single explicit {@code ^} into one
     * superscript, so {@code f'} → sup {@code ′}, {@code f''} → sup
     * {@code (′ ′)}, {@code f'^2} → sup {@code (′ 2)}, {@code f^{2}'} → sup
     * {@code (2 ′)}. Two explicit carets still error as a double superscript.
     */
    private MathNode parseScripts(MathNode base) {
        List<MathNode> supItems = new ArrayList<>();
        boolean explicitSup = false;
        MathNode sub = null;
        while (true) {
            Kind k = peek().kind();
            if (k == Kind.CHAR && peek().codePoint() == '\'') {
                next();
                supItems.add(new Atom(0x2032, MathClass.ORD));
            } else if (k == Kind.SUP) {
                if (explicitSup) {
                    throw new MathSyntaxException("Double superscript ('^' after '^')");
                }
                next();
                explicitSup = true;
                supItems.add(parseScriptArg("superscript"));
            } else if (k == Kind.SUB) {
                if (sub != null) {
                    throw new MathSyntaxException("Double subscript ('_' after '_')");
                }
                next();
                sub = parseScriptArg("subscript");
            } else {
                break;
            }
        }
        MathNode sup = supItems.isEmpty() ? null : wrap(supItems);
        if (sup == null && sub == null) {
            return base;
        }
        return new SupSub(base, sup, sub);
    }

    /** A large operator with optional {@code \limits}/{@code \nolimits} + limits. */
    private MathNode parseBigOperator(Atom op) {
        LimitsMode mode = LimitsMode.DEFAULT;
        while (peek().kind() == Kind.COMMAND) {
            String name = peek().name();
            if (name.equals("limits")) {
                mode = LimitsMode.LIMITS;
                next();
            } else if (name.equals("nolimits")) {
                mode = LimitsMode.NOLIMITS;
                next();
            } else {
                break;
            }
        }
        MathNode upper = null;
        MathNode lower = null;
        while (true) {
            Kind k = peek().kind();
            if (k == Kind.SUP) {
                if (upper != null) {
                    throw new MathSyntaxException("Double superscript on operator");
                }
                next();
                upper = parseScriptArg("upper limit");
            } else if (k == Kind.SUB) {
                if (lower != null) {
                    throw new MathSyntaxException("Double subscript on operator");
                }
                next();
                lower = parseScriptArg("lower limit");
            } else {
                break;
            }
        }
        return new BigOperator(op, lower, upper, mode);
    }

    /** The argument of a script: a single nucleus ({@code '{'} opens a group). */
    private MathNode parseScriptArg(String what) {
        Kind k = peek().kind();
        if (k == Kind.SUP || k == Kind.SUB || k == Kind.RBRACE || k == Kind.EOF
                || isCommand(peek(), "right")) {
            throw new MathSyntaxException(
                "Dangling " + what + ": nothing follows the script marker");
        }
        return parseNucleus();
    }

    /**
     * The argument of an accent command ({@code \hat{a}}, {@code \vec v}): a
     * single nucleus, so {@code '{'} opens a group and a bare token/command is
     * taken as-is (matching LaTeX's brace-optional accent argument).
     */
    private MathNode parseAccentArg(String command) {
        Kind k = peek().kind();
        if (k == Kind.SUP || k == Kind.SUB || k == Kind.RBRACE || k == Kind.EOF
                || isCommand(peek(), "right")) {
            throw new MathSyntaxException(
                "\\" + command + " needs a base to accent, but found " + describe(peek()));
        }
        return parseNucleus();
    }

    /**
     * The argument of a font-variant command ({@code \mathbb{ABC}},
     * {@code \mathbf x}): a single nucleus, so {@code '{'} opens a group and a bare
     * token/command is taken as-is (matching LaTeX's brace-optional single-token
     * argument, e.g. {@code \mathbb R}).
     */
    private MathNode parseFontArg(String command) {
        Kind k = peek().kind();
        if (k == Kind.SUP || k == Kind.SUB || k == Kind.RBRACE || k == Kind.EOF
                || isCommand(peek(), "right")) {
            throw new MathSyntaxException(
                "\\" + command + " needs an argument, but found " + describe(peek()));
        }
        return parseNucleus();
    }

    /**
     * A required brace-optional argument of a command that takes a math unit —
     * {@code \frac}, {@code \cfrac}, {@code \sqrt}, {@code \binom}. A {@code '{'}
     * opens a group; otherwise the single next token (a char or a {@code \command})
     * is consumed, so {@code \frac1x} ≡ {@code \frac{1}{x}}, {@code \sqrt2} ≡
     * {@code \sqrt{2}} and {@code \frac{x^3}3} all read — matching LaTeX's
     * single-token argument rule. Fails cleanly when nothing follows.
     */
    private MathNode parseArgument(String context) {
        Kind k = peek().kind();
        if (k == Kind.SUP || k == Kind.SUB || k == Kind.RBRACE || k == Kind.EOF
                || isCommand(peek(), "right")) {
            throw new MathSyntaxException(
                context + " expects an argument, but found " + describe(peek()));
        }
        return parseNucleus();
    }

    /**
     * Reads a braced dimension argument ({@code {2em}} / {@code {18mu}} / {@code {3pt}})
     * and returns its width in math units (18mu = 1em). em-relative units (em/ex/mu) are
     * exact against the current size; pt is approximated at a 10pt em (1pt = 1.8mu).
     * Throws a positioned {@link MathSyntaxException} on a missing brace, empty arg,
     * malformed number, or an unknown unit.
     */
    private double parseDimensionMu(String command, int offset) {
        StringBuilder sb = new StringBuilder();
        if (peek().kind() == Kind.LBRACE) {
            // Braced form: \hspace{2em}. Read every CHAR up to the closing brace.
            next(); // consume '{'
            while (peek().kind() != Kind.RBRACE) {
                Token t = peek();
                if (t.kind() == Kind.EOF) {
                    throw new MathSyntaxException("\\" + command + ": unterminated {dimension}", offset);
                }
                if (t.kind() != Kind.CHAR) {
                    throw new MathSyntaxException(
                        "\\" + command + ": a dimension is <number><unit>, not " + describe(t), offset);
                }
                sb.appendCodePoint(t.codePoint());
                next();
            }
            next(); // consume '}'
        } else {
            // Bare form: \mkern18mu / \kern3pt. Read the numeric run, then exactly the
            // 1-2 unit letters (TeX units are two letters); stop so following math is untouched.
            while (peek().kind() == Kind.CHAR && isDimensionNumberChar(peek().codePoint())) {
                sb.appendCodePoint(peek().codePoint());
                next();
            }
            for (int i = 0; i < 2 && peek().kind() == Kind.CHAR
                    && Character.isLetter(peek().codePoint()); i++) {
                sb.appendCodePoint(peek().codePoint());
                next();
            }
        }
        String dim = sb.toString().strip();
        int u = dim.length();
        while (u > 0 && (Character.isLetter(dim.charAt(u - 1)))) {
            u--;
        }
        String numberPart = dim.substring(0, u).strip();
        String unit = dim.substring(u).strip();
        double value;
        try {
            value = Double.parseDouble(numberPart);
        } catch (NumberFormatException bad) {
            throw new MathSyntaxException(
                "\\" + command + ": '" + dim + "' is not a <number><unit> dimension", offset);
        }
        double muPerUnit = switch (unit) {
            case "em" -> 18.0;      // 1em = 18mu
            case "mu" -> 1.0;
            case "ex" -> 8.0;       // ~0.43em
            case "pt" -> 1.8;       // approx at a 10pt em (absolute lengths are a follow-up)
            default -> throw new MathSyntaxException(
                "\\" + command + ": unknown unit '" + unit + "' (use em / ex / mu / pt)", offset);
        };
        return value * muPerUnit;
    }

    /** A char that can appear in the numeric prefix of a bare dimension ({@code -1.5em}). */
    private static boolean isDimensionNumberChar(int cp) {
        return (cp >= '0' && cp <= '9') || cp == '.' || cp == '-' || cp == '+';
    }

    /**
     * A binomial coefficient {@code \binom{n}{r}} (and {@code \dbinom} /
     * {@code \tbinom}): an upper-over-lower stack with <em>no</em> fraction rule,
     * wrapped in parentheses. Clean-room from the TeXbook — {@code \binom} is
     * {@code \genfrac(){0pt}{}}, i.e. a rule-less fraction fenced by {@code (} and
     * {@code )}. The parens are the ordinary {@link Fenced} pair, so they stretch
     * to the stack via the existing delimiter machinery; the {@code style} forces
     * the stack's math style ({@code \dbinom} display, {@code \tbinom} text,
     * {@code \binom} inherited).
     */
    private MathNode binom(MathNode.FractionStyle style) {
        MathNode upper = parseArgument("\\binom upper argument");
        MathNode lower = parseArgument("\\binom lower argument");
        Fraction stack = new Fraction(upper, lower, false, style);
        return new Fenced('(', stack, ')');
    }

    /** A single nucleus (no trailing scripts). */
    private MathNode parseNucleus() {
        // Depth guard: parseNucleus is the universal chokepoint of the recursive
        // descent — every nested group, argument, script and fence bottoms out
        // through here — so bounding it here bounds the whole recursion and turns
        // a stack-overflowing input into a caught MathSyntaxException.
        if (++depth > MAX_DEPTH) {
            depth--;
            throw new MathSyntaxException(
                "nesting too deep: exceeds the " + MAX_DEPTH + "-level limit", currentOffset());
        }
        try {
            return parseNucleusInner();
        } finally {
            depth--;
        }
    }

    private MathNode parseNucleusInner() {
        Token t = peek();
        return switch (t.kind()) {
            case LBRACE -> parseGroup();
            case CHAR -> {
                next();
                // A tie '~' is an interword (non-breaking) space, not an atom
                // (TeXbook Ch.7); approximate it as the control-space width.
                if (t.codePoint() == '~') {
                    yield new Spacing(6.0);
                }
                yield charAtom(t.codePoint());
            }
            case TEXT -> {
                next();
                yield textWithNestedMath(t);
            }
            case COMMAND -> parseCommand();
            case SUP -> throw new MathSyntaxException(
                "Unexpected '^': no nucleus to attach a superscript to");
            case SUB -> throw new MathSyntaxException(
                "Unexpected '_': no nucleus to attach a subscript to");
            case RBRACE -> throw new MathSyntaxException("Unexpected '}' (unbalanced brace)");
            case EOF -> throw new MathSyntaxException("Unexpected end of input (expected an atom)");
        };
    }

    /** Dispatches a control sequence. Assumes the current token is a COMMAND. */
    private MathNode parseCommand() {
        Token command = next(); // consume the command
        String name = command.name();
        int commandOffset = command.offset();

        switch (name) {
            case "frac" -> {
                MathNode num = parseArgument("\\frac numerator");
                MathNode den = parseArgument("\\frac denominator");
                return new Fraction(num, den);
            }
            case "cfrac" -> {
                // Continued fraction: an ordinary ruled fraction forced to display
                // style (TeXbook: \cfrac sets its parts in \displaystyle), so the
                // nested a_0+\cfrac{1}{a_1+…} form stays full-size.
                MathNode num = parseArgument("\\cfrac numerator");
                MathNode den = parseArgument("\\cfrac denominator");
                return new Fraction(num, den, true, MathNode.FractionStyle.DISPLAY);
            }
            case "dfrac" -> {
                // Display-style ruled fraction: \dfrac forces \displaystyle regardless
                // of context (a full-size fraction even inside inline math) — the
                // amsmath sibling of \tfrac. Same styling as \cfrac without the
                // continued-fraction framing.
                MathNode num = parseArgument("\\dfrac numerator");
                MathNode den = parseArgument("\\dfrac denominator");
                return new Fraction(num, den, true, MathNode.FractionStyle.DISPLAY);
            }
            case "tfrac" -> {
                // Text-style ruled fraction: \tfrac forces \textstyle (a small,
                // inline-sized fraction even in display math). The \frac whose style
                // is pinned to TEXT, mirroring \tbinom for the binom family.
                MathNode num = parseArgument("\\tfrac numerator");
                MathNode den = parseArgument("\\tfrac denominator");
                return new Fraction(num, den, true, MathNode.FractionStyle.TEXT);
            }
            case "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle" -> {
                return parseStyleSwitch(name);
            }
            case "textcolor" -> {
                // \textcolor{color}{body}: paint body a fixed color. The name/hex is
                // validated through Color (the only path from a raw string to an SVG
                // fill); an inner \textcolor wins over an outer one at layout time.
                Color color = parseColorArg("\\textcolor");
                MathNode body = parseArgument("\\textcolor body");
                return new MathNode.Colored(body, color);
            }
            case "color" -> {
                return parseColorSwitch();
            }
            case "boxed", "fbox" -> {
                // \boxed{body} (amsmath) frames a math sub-formula in a rule rectangle.
                // \fbox is text-mode in LaTeX; here it is accepted as the math-mode
                // analogue (a clean-room simplification — the frame is identical).
                MathNode body = parseArgument("\\" + name + " body");
                return new MathNode.Boxed(body);
            }
            case "cancel", "bcancel", "xcancel" -> {
                // \cancel{body} (up /), \bcancel (down \), \xcancel (both — an X): the
                // body is struck through by one or two diagonal rules. The argument is
                // parsed through the normal group parse (parseArgument -> parseNucleus),
                // so MAX_DEPTH bounds the recursion exactly as it does for \boxed.
                MathNode.CancelKind kind = switch (name) {
                    case "bcancel" -> MathNode.CancelKind.BCANCEL;
                    case "xcancel" -> MathNode.CancelKind.XCANCEL;
                    default -> MathNode.CancelKind.CANCEL;
                };
                MathNode body = parseArgument("\\" + name + " body");
                return new MathNode.Cancel(kind, body, null);
            }
            case "cancelto" -> {
                // \cancelto{value}{body}: an up-diagonal strike with an arrowhead at its
                // tip, the target value set script-size beyond the tip. The VALUE is the
                // first brace group, the struck body the second (cancel-package order).
                MathNode to = parseArgument("\\cancelto value");
                MathNode body = parseArgument("\\cancelto body");
                return new MathNode.Cancel(MathNode.CancelKind.CANCELTO, body, to);
            }
            case "bra" -> {
                // \bra{ψ} = ⟨ψ| — a fixed-size angle-bracket + vertical bar (physics braket).
                MathNode body = parseArgument("\\bra body");
                return new MathList(List.of(
                    new Atom(0x27E8, MathClass.OPEN), body, new Atom('|', MathClass.CLOSE)));
            }
            case "ket" -> {
                // \ket{ψ} = |ψ⟩ — a vertical bar + closing angle bracket.
                MathNode body = parseArgument("\\ket body");
                return new MathList(List.of(
                    new Atom('|', MathClass.OPEN), body, new Atom(0x27E9, MathClass.CLOSE)));
            }
            case "braket" -> {
                // \braket{a|b} = ⟨a|b⟩ — angle brackets around the body; an inner | (the
                // inner product's mid bar) renders as-is inside. Fixed-size v1 (the manual
                // \langle a|b\rangle already renders; stretchy braket is a follow-up).
                MathNode body = parseArgument("\\braket body");
                return new MathList(List.of(
                    new Atom(0x27E8, MathClass.OPEN), body, new Atom(0x27E9, MathClass.CLOSE)));
            }
            case "hspace", "mkern", "kern", "mskip" -> {
                // Dimensioned horizontal glue: \hspace{2em} / \mkern{18mu} / \kern{3pt} —
                // a braced <number><unit> whose width becomes a Spacing node in math units
                // (18mu = 1em). \hspace* is accepted (the star is a no-op here — we never
                // discard glue at a line break). em-relative units (em/ex/mu) are exact;
                // pt is approximated at a 10pt em (1pt ≈ 1.8mu) — absolute lengths are a
                // follow-up. A malformed dimension throws a positioned MathSyntaxException.
                if (peek().kind() == Kind.CHAR && peek().codePoint() == '*') {
                    next(); // \hspace* — consume the star
                }
                return new Spacing(parseDimensionMu(name, commandOffset));
            }
            case "prescript" -> {
                // \prescript{sup}{sub}{base} = {}^{sup}_{sub} base — left-attached scripts.
                // Pure sugar over an empty-base SupSub (the {}^3 idiom) followed by the base;
                // an empty {} slot renders no script on that side.
                MathNode preSup = parseArgument("\\prescript superscript");
                MathNode preSub = parseArgument("\\prescript subscript");
                MathNode base = parseArgument("\\prescript base");
                return new MathList(List.of(
                    new SupSub(new MathList(List.of()), preSup, preSub), base));
            }
            case "binom" -> {
                return binom(MathNode.FractionStyle.INHERIT);
            }
            case "dbinom" -> {
                return binom(MathNode.FractionStyle.DISPLAY);
            }
            case "tbinom" -> {
                return binom(MathNode.FractionStyle.TEXT);
            }
            case "sqrt" -> {
                MathNode index = null;
                if (peek().kind() == Kind.CHAR && peek().codePoint() == '[') {
                    next(); // consume '['
                    index = parseUntilRBracket();
                }
                MathNode radicand = parseArgument("\\sqrt argument");
                return new Radical(radicand, index);
            }
            case "overset" -> {
                // \overset{above}{base}: above at script size over the base, base
                // keeps its class (TeXbook \overset). Arguments read eagerly.
                MathNode above = parseArgument("\\overset annotation");
                MathNode base = parseArgument("\\overset base");
                return new MathNode.Stack(base, above, null, MathNode.StackKind.OVERSET);
            }
            case "underset" -> {
                MathNode below = parseArgument("\\underset annotation");
                MathNode base = parseArgument("\\underset base");
                return new MathNode.Stack(base, null, below, MathNode.StackKind.UNDERSET);
            }
            case "stackrel" -> {
                // \stackrel{above}{rel}: like \overset but the result is class Rel.
                MathNode above = parseArgument("\\stackrel annotation");
                MathNode base = parseArgument("\\stackrel base");
                return new MathNode.Stack(base, above, null, MathNode.StackKind.STACKREL);
            }
            case "underbrace" -> {
                // The braced expression; its _ label is attached later as a limit
                // (see parseComponent -> parseStackLabel), so the brace stack can be
                // written bare (\\underbrace{x}) or labelled (\\underbrace{x}_{n}).
                MathNode base = parseArgument("\\underbrace argument");
                return new MathNode.Stack(base, null, null, MathNode.StackKind.UNDERBRACE);
            }
            case "overbrace" -> {
                MathNode base = parseArgument("\\overbrace argument");
                return new MathNode.Stack(base, null, null, MathNode.StackKind.OVERBRACE);
            }
            case "xrightarrow", "xleftarrow", "xleftrightarrow",
                 "xRightarrow", "xLeftarrow", "xLeftrightarrow",
                 "xmapsto", "xhookrightarrow", "xhookleftarrow", "xrightleftharpoons",
                 "xlongequal" -> {
                // amsmath's extensible labelled arrows (the whole \x... family): an
                // optional [below] label FIRST, then the required {above} label. The '['
                // is only taken as the optional argument when a matching ']' closes it
                // (lookahead); an unclosed '[' belongs to the following content. The
                // command name picks the shaft glyph via XArrowKind (one source of truth).
                MathNode below = null;
                if (peek().kind() == Kind.CHAR && peek().codePoint() == '['
                        && hasMatchingRBracket()) {
                    next(); // consume '['
                    below = normalizeOptionalLabel(parseUntilRBracket());
                }
                MathNode above = parseArgument("\\" + name + " label");
                return new MathNode.XArrow(above, below, xArrowKind(name));
            }
            case "substack" -> {
                return parseSubstack();
            }
            case "phantom" -> {
                // Occupies the content's full box (width + height + depth), no ink.
                return new Phantom(parseGroup(), true, true);
            }
            case "hphantom" -> {
                // Occupies the content's width only (zero height/depth).
                return new Phantom(parseGroup(), true, false);
            }
            case "vphantom" -> {
                // Occupies the content's height/depth only (zero width).
                return new Phantom(parseGroup(), false, true);
            }
            case "mathstrut" -> {
                // A zero-width strut with the height/depth of '(' — i.e. \vphantom{(}.
                return new Phantom(new Atom('(', MathClass.OPEN), false, true);
            }
            case "left" -> {
                int leftDelim = readDelimiter("\\left");
                MathNode body = parseFencedBody();
                int rightDelim = readDelimiter("\\right");
                return new Fenced(leftDelim, body, rightDelim);
            }
            case "not" -> {
                // \not overlays a negation slash on the following relation. We
                // realise it with the precomposed negated code point when one
                // exists (STIX has the glyph); otherwise we fail loudly rather
                // than fake an overlay we cannot draw in the minimal SVG alphabet.
                MathNode target = parseNucleus();
                if (target instanceof Atom a) {
                    Integer negated = NEGATION.get(a.codePoint());
                    if (negated != null) {
                        return new Atom(negated, MathClass.REL);
                    }
                }
                throw new MathSyntaxException(
                    "\\not has no precomposed negation for the following symbol");
            }
            case "begin" -> {
                return EnvironmentParser.parseEnvironment(this);
            }
            case "end" -> throw new MathSyntaxException(
                "\\end without a matching \\begin");
            case "hline", "hdashline" -> throw new MathSyntaxException(
                "\\" + name + " is only valid inside an array/matrix environment");
            case "right" -> throw new MathSyntaxException("\\right without matching \\left");
            case "lx" -> throw new MathSyntaxException(
                "nested \\lx not supported: \\lx must be the whole top-level expression "
                    + "(a future refinement may allow nesting)");
            case "limits", "nolimits" ->
                throw new MathSyntaxException("\\" + name + " must directly follow a large operator");
            case "operatorname" -> {
                // \operatorname{name} (beside scripts) / \operatorname*{name} (limits).
                boolean withLimits = false;
                if (peek().kind() == Kind.CHAR && peek().codePoint() == '*') {
                    next(); // consume '*'
                    withLimits = true;
                }
                String opText = readOperatorNameArg();
                return new OperatorName(opText, withLimits);
            }
            default -> {
                // Font-variant alphabet (\mathbb \mathcal \mathbf \boldsymbol …)?
                // Rewrites the enclosed atoms to math-alphanumeric code points —
                // no new node kind (see MathVariant).
                MathVariant.Style variant = FONT_VARIANTS.get(name);
                if (variant != null) {
                    MathNode arg = parseFontArg(name);
                    return MathVariant.apply(variant, arg);
                }
                // Predefined named operator (\sin \cos \lim \max \operatorname*…)?
                OpSpec op = NAMED_OPS.get(name);
                if (op != null) {
                    return new OperatorName(op.display(), op.takesLimits());
                }
                // Accent (glyph accent, wide accent, or over/underline rule)?
                AccentSpec accent = ACCENTS.get(name);
                if (accent != null) {
                    MathNode base = parseAccentArg(name);
                    return new Accent(name, base, accent.codePoint(),
                        accent.stretchy(), accent.under());
                }
                // The mod family (wild-corpus GAP tier). \bmod is the binary
                // "mod" word (a mathbin in TeX; the upright operator word is the
                // dominant visual, so OperatorName's roman rendering fits);
                // \pmod{m} typesets "(mod m)" with a leading 18mu, per TeX.
                if (name.equals("bmod")) {
                    return new OperatorName("mod", false);
                }
                if (name.equals("pmod")) {
                    MathNode arg = parseGroup();
                    return new MathList(List.of(
                        new Spacing(18.0),
                        new Atom('(', MathClass.OPEN),
                        new OperatorName("mod", false),
                        new Spacing(6.0),
                        arg,
                        new Atom(')', MathClass.CLOSE)));
                }
                // Large operator?
                Sym big = BIG_OPERATORS.get(name);
                if (big != null) {
                    return new Atom(big.codePoint(), big.mathClass());
                }
                // Explicit spacing?
                Double mu = SPACES.get(name);
                if (mu != null) {
                    return new Spacing(mu);
                }
                // Symbol / greek?
                Sym sym = SYMBOLS.get(name);
                if (sym != null) {
                    return new Atom(sym.codePoint(), sym.mathClass());
                }
                // \big/\Big/\bigg/\Bigg (+ l/r/m class variants): fixed-size delimiters.
                // Runs AFTER BIG_OPERATORS/SYMBOLS, so \bigcup, \bigstar, … are gone.
                MathNode sized = tryParseSizedDelim(name);
                if (sized != null) {
                    return sized;
                }
                // Equation-numbering control commands are INERT in LatteX (no automatic
                // equation numbering / cross-referencing). Rather than throw "Unknown
                // command" — which today breaks otherwise-valid align/gather/multline
                // blocks that carry them — accept them as no-glyph no-ops. An empty
                // MathList contributes nothing to layout/SVG/MathML.
                if (name.equals("nonumber") || name.equals("notag")) {
                    return new MathList(List.of());
                }
                // \label{key}: consume and DISCARD its mandatory brace group (mirrors
                // the strictness of the \tag reader), then emit nothing.
                if (name.equals("label")) {
                    if (peek().kind() != Kind.LBRACE) {
                        throw new MathSyntaxException("\\label expects a {key} group");
                    }
                    parseGroup(); // read and discard the label
                    return new MathList(List.of());
                }
                throw MathSyntaxException.unsupported(
                    "Unknown command: \\" + name + commandSuggestion(name), commandOffset);
            }
        }
    }

    /**
     * Parses {@code \substack{row \\ row \\ ...}} into a single-column, centred,
     * script-size grid (a {@link Matrix} of {@link MatrixKind#SUBSTACK}). Clean-room
     * from the TeXbook: {@code \substack} is a one-column {@code \halign} set in
     * script style with a tightened baseline, used to stack several conditions under
     * a big-operator limit ({@code \sum_{\substack{i<j \\ i \ne k}}}). Rows are
     * {@code \\}-separated; each row is an ordinary math list.
     */
    private MathNode parseSubstack() {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\substack expects a '{...}' argument but found " + describe(peek()));
        }
        next(); // consume '{'
        List<List<MathNode>> rows = new ArrayList<>();
        List<MathNode> row = new ArrayList<>();
        while (true) {
            Token t = peek();
            if (t.kind() == Kind.RBRACE) {
                next(); // consume '}'
                break;
            }
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException("Unbalanced brace in \\substack argument");
            }
            if (isCommand(t, "\\") || isCommand(t, "cr")) {
                next();
                rows.add(List.of(wrap(row)));
                row = new ArrayList<>();
                continue;
            }
            row.add(parseComponent());
        }
        // A trailing row (content after the last \\, or the sole row) — a bare
        // trailing \\ adds no phantom row, matching LaTeX's \halign.
        if (!row.isEmpty() || rows.isEmpty()) {
            rows.add(List.of(wrap(row)));
        }
        List<ColumnAlign> aligns = List.of(ColumnAlign.CENTER);
        List<Integer> vlines = List.of(0, 0);
        List<RowRule> rowRules = new ArrayList<>(rows.size() + 1);
        for (int i = 0; i <= rows.size(); i++) {
            rowRules.add(RowRule.NONE);
        }
        return new Matrix(rows, aligns, vlines, rowRules,
            Fenced.NULL_DELIMITER, Fenced.NULL_DELIMITER, MatrixKind.SUBSTACK);
    }

    /** Parses the body of {@code \left..\right}, consuming the {@code \right}. */
    private MathNode parseFencedBody() {
        List<MathNode> items = new ArrayList<>();
        while (true) {
            Token t = peek();
            if (isCommand(t, "right")) {
                next(); // consume \right; the delimiter is read by the caller
                break;
            }
            if (isCommand(t, "middle")) {
                // \middle <delim> — a mid delimiter stretched like the enclosing
                // \left..\right pair (L2, plan lattex-middle-evalbar). Legal ONLY
                // here: outside a fenced body the command falls through to the
                // unknown-command failure, matching TeX's "extra \middle" error.
                next();
                items.add(new MathNode.MiddleDelim(readDelimiter("\\middle")));
                continue;
            }
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException("Unbalanced \\left: missing \\right");
            }
            items.add(parseComponent());
        }
        return wrap(items);
    }

    /**
     * Lookahead for an optional-bracket argument: with the cursor ON a {@code '['}
     * token, scans forward for a {@code ']'} at brace depth 0 (a {@code ']'} inside
     * a {@code {...}} group belongs to that group, exactly as
     * {@link #parseUntilRBracket} would consume it). Stops without a match at end
     * of input or when the enclosing group closes, so an unclosed {@code '['} is
     * left to be read as ordinary content.
     */
    private boolean hasMatchingRBracket() {
        int braceDepth = 0;
        for (int i = p + 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.kind()) {
                case LBRACE -> braceDepth++;
                case RBRACE -> {
                    if (--braceDepth < 0) {
                        return false; // the enclosing group closed first
                    }
                }
                case CHAR -> {
                    if (braceDepth == 0 && t.codePoint() == ']') {
                        return true;
                    }
                }
                case EOF -> {
                    return false;
                }
                default -> {
                    // other tokens neither open nor close the bracket scan
                }
            }
        }
        return false;
    }

    /** An empty optional label ({@code []}) reads as no label at all. */
    private static MathNode normalizeOptionalLabel(MathNode label) {
        if (label instanceof MathList(var items) && items.isEmpty()) {
            return null;
        }
        return label;
    }

    /** Parses a {@code \sqrt} optional index, consuming the closing {@code ']'}. */
    private MathNode parseUntilRBracket() {
        List<MathNode> items = new ArrayList<>();
        while (true) {
            Token t = peek();
            if (t.kind() == Kind.CHAR && t.codePoint() == ']') {
                next(); // consume ']'
                break;
            }
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException("Unterminated \\sqrt[...]: missing ']'");
            }
            items.add(parseComponent());
        }
        return wrap(items);
    }

    /**
     * Reads the {@code {name}} argument of {@code \operatorname}: a brace group of
     * plain characters (the roman letters to typeset). Rejects anything other than
     * literal characters — an {@code \operatorname} whose argument reaches for a
     * command/group/script fails cleanly rather than silently dropping it.
     */
    /**
     * Reads a {@code {color}} group as a validated {@link Color}. The group holds
     * a color name ({@code red}) or a hex literal ({@code #ff0000}); it is routed
     * through {@link Color#parse} — the single typed boundary — so an unrecognized
     * or malformed color fails as a clean parse error, never an emitted raw string.
     */
    private Color parseColorArg(String command) {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                command + " needs a '{color}' argument but found " + describe(peek()));
        }
        next(); // consume '{'
        StringBuilder sb = new StringBuilder();
        while (peek().kind() != Kind.RBRACE) {
            Token t = peek();
            if (t.kind() != Kind.CHAR) {
                throw new MathSyntaxException(
                    command + " color must be a name or hex literal, but found " + describe(t));
            }
            sb.appendCodePoint(t.codePoint());
            next();
        }
        next(); // consume '}'
        try {
            return Color.parse(sb.toString());
        } catch (IllegalArgumentException e) {
            throw new MathSyntaxException(e.getMessage());
        }
    }

    private String readOperatorNameArg() {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\operatorname needs a '{name}' argument but found " + describe(peek()));
        }
        next(); // consume '{'
        StringBuilder sb = new StringBuilder();
        while (peek().kind() != Kind.RBRACE) {
            Token t = peek();
            if (t.kind() == Kind.CHAR) {
                sb.appendCodePoint(t.codePoint());
                next();
            } else if (t.kind() == Kind.COMMAND && SPACES.containsKey(t.name())) {
                // Spacing commands are allowed inside an operator name (arg\,max,
                // lim\;sup): a positive space renders as one literal space; a negative
                // space (\!) collapses. The name stays plain text — no math nucleus.
                if (SPACES.get(t.name()) > 0) {
                    sb.append(' ');
                }
                next();
            } else {
                throw new MathSyntaxException(
                    "\\operatorname argument must be plain text, but found " + describe(t));
            }
        }
        next(); // consume '}'
        if (sb.length() == 0) {
            throw new MathSyntaxException("\\operatorname argument must be non-empty");
        }
        return sb.toString();
    }

    /** Reads a delimiter code point after {@code \left}/{@code \right}. */
    /**
     * If {@code name} is a {@code \big}-family delimiter command ({@code \big}/{@code
     * \Big}/{@code \bigg}/{@code \Bigg} with an optional {@code l}/{@code r}/{@code m}
     * class suffix), reads the following delimiter and returns the fixed-size node;
     * otherwise returns {@code null} so the caller reports an unknown command. Runs
     * after the symbol/big-operator lookups, so {@code \bigcup}/{@code \bigstar} never
     * reach here.
     */
    private MathNode tryParseSizedDelim(String name) {
        int level;
        String suffix;
        if (name.startsWith("bigg")) {
            level = 3;
            suffix = name.substring(4);
        } else if (name.startsWith("Bigg")) {
            level = 4;
            suffix = name.substring(4);
        } else if (name.startsWith("big")) {
            level = 1;
            suffix = name.substring(3);
        } else if (name.startsWith("Big")) {
            level = 2;
            suffix = name.substring(3);
        } else {
            return null;
        }
        MathClass mathClass = switch (suffix) {
            case "" -> MathClass.ORD;    // plain \big
            case "l" -> MathClass.OPEN;  // \bigl
            case "r" -> MathClass.CLOSE; // \bigr
            case "m" -> MathClass.REL;   // \bigm
            default -> null;             // an unknown suffix — not a \big command
        };
        if (mathClass == null) {
            return null;
        }
        int delim = readDelimiter("\\" + name);
        return new MathNode.SizedDelim(delim, level, mathClass);
    }

    private int readDelimiter(String context) {
        Token t = next();
        if (t.kind() == Kind.CHAR) {
            int cp = t.codePoint();
            return switch (cp) {
                case '.' -> Fenced.NULL_DELIMITER; // \left. / \right.
                case '(', ')', '[', ']', '|', '/' -> cp;
                default -> throw new MathSyntaxException(
                    context + ": '" + new String(Character.toChars(cp)) + "' is not a valid delimiter");
            };
        }
        if (t.kind() == Kind.COMMAND) {
            return switch (t.name()) {
                case "{" -> '{';
                case "}" -> '}';
                case "|" -> 0x2016;      // \| is the double bar ‖ (synonym of \Vert)
                case "Vert" -> 0x2016;   // ‖
                case "vert" -> '|';
                case "langle" -> 0x27E8; // ⟨
                case "rangle" -> 0x27E9; // ⟩
                case "lfloor" -> 0x230A; // ⌊
                case "rfloor" -> 0x230B; // ⌋
                case "lceil" -> 0x2308;  // ⌈
                case "rceil" -> 0x2309;  // ⌉
                default -> throw new MathSyntaxException(
                    context + ": \\" + t.name() + " is not a valid delimiter");
            };
        }
        throw new MathSyntaxException(context + ": expected a delimiter but found " + describe(t));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Wraps a component list: unwrap a singleton, else a {@link MathList}. */
    static MathNode wrap(List<MathNode> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return new MathList(items);
    }

    /**
     * Turns a {@link Kind#TEXT} token into its node, honoring nested inline math:
     * an unescaped {@code $…$} span inside a text-family argument re-enters math
     * mode (LaTeX's text-mode toggle), so {@code \text{if $x>0$ then}} splits into
     * literal {@link TextRun}s and recursively-parsed math segments. {@code \$}
     * stays a literal (verbatim, exactly the pre-split behavior — never a toggle).
     * A plain argument (no unescaped {@code $}) yields the byte-identical single
     * {@link TextRun} it always did. An unpaired {@code $} is a positioned error;
     * an empty {@code $$} span contributes nothing. The inner parse CONTINUES this
     * parser's depth (see {@link #parseMath(String, int)}) so {@code $}-nesting
     * cannot recurse past {@link #MAX_DEPTH}.
     */
    private MathNode textWithNestedMath(Token t) {
        String raw = t.text(); // verbatim, braces included (lexTextArgument keeps them)
        TextStyle style = TEXT_COMMANDS.get(t.name());
        if (indexOfUnescapedDollar(raw, 0) < 0) {
            return new TextRun(literalText(raw), style); // fast path: one literal run
        }
        List<MathNode> items = new ArrayList<>();
        int i = 0;
        int n = raw.length();
        while (i < n) {
            int open = indexOfUnescapedDollar(raw, i);
            if (open < 0) {
                items.add(new TextRun(literalText(raw.substring(i)), style));
                break;
            }
            if (open > i) {
                items.add(new TextRun(literalText(raw.substring(i, open)), style));
            }
            int close = indexOfUnescapedDollar(raw, open + 1);
            if (close < 0) {
                throw new MathSyntaxException(
                    "Unpaired '$' in \\" + t.name() + " argument", t.offset());
            }
            // The math span keeps its braces VERBATIM — the re-parse needs the group
            // structure (\frac{12}{34}, x^{10}); stripping here silently corrupted
            // multi-char braced arguments (Conf review lattex/210 F1).
            String span = raw.substring(open + 1, close);
            if (!span.isBlank()) {
                try {
                    items.add(parseMath(span, depth));
                } catch (MathSyntaxException e) {
                    throw new MathSyntaxException("in \\" + t.name() + " nested math '$"
                        + span + "$': " + e.getMessage(), t.offset());
                }
            }
            i = close + 1;
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return new MathList(List.copyOf(items));
    }

    /**
     * A LITERAL text segment: grouping braces become invisible (exactly the old
     * lexer-level stripping, relocated here so math spans keep theirs), and
     * {@code \$} → {@code $} — with {@code $} toggling math, the escape is the
     * only way to write a literal dollar in text (matches LaTeX).
     */
    private static String literalText(String s) {
        if (s.indexOf('{') < 0 && s.indexOf('}') < 0 && s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '$') {
                sb.append('$');
                i++;
            } else if (c != '{' && c != '}') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The index of the next math-toggling {@code $} at or after {@code from}, or
     * -1. Skips: {@code \$} (the literal-dollar escape); and the ENTIRE braced
     * argument of a nested text-family command ({@code \text{…}} inside this
     * argument) — those {@code $}s belong to the inner command's own re-parse,
     * not this level's pairing. Without that skip, {@code \text{$\text{$x$}$}}
     * mis-paired the outer span at the first inner {@code $} and the depth-guard
     * path was unreachable (Conf review lattex/210 F2). A plain {@code {…}} group
     * is NOT opaque: {@code $} inside it still toggles, as in LaTeX.
     */
    private static int indexOfUnescapedDollar(String s, int from) {
        int i = from;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                int j = i + 1;
                while (j < n && Character.isLetter(s.charAt(j))) {
                    j++;
                }
                if (j > i + 1 && TEXT_COMMANDS.containsKey(s.substring(i + 1, j))) {
                    // nested text-family command: skip its whole braced argument
                    while (j < n && isWhitespace(s.charAt(j))) {
                        j++;
                    }
                    if (j < n && s.charAt(j) == '{') {
                        int depth = 1;
                        j++;
                        while (j < n && depth > 0) {
                            char d = s.charAt(j);
                            if (d == '\\') {
                                j++; // escaped char inside the nested argument
                            } else if (d == '{') {
                                depth++;
                            } else if (d == '}') {
                                depth--;
                            }
                            j++;
                        }
                    }
                }
                i = Math.max(j, i + 2); // command word skipped, or \X escape
            } else if (c == '$') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** A single-character atom, classified per the TeXbook atom-class tables. */
    private static Atom charAtom(int cp) {
        return new Atom(cp, classify(cp));
    }

    private static MathClass classify(int cp) {
        if (Character.isLetterOrDigit(cp)) {
            return MathClass.ORD;
        }
        return switch (cp) {
            case '+', '-', '*' -> MathClass.BIN;
            case '=', '<', '>' -> MathClass.REL;
            case '(', '[' -> MathClass.OPEN;
            case ')', ']' -> MathClass.CLOSE;
            case ',', ';' -> MathClass.PUNCT;
            // A literal pasted Unicode operator (≤ → × ∈ …) classifies like its
            // \command form via the reverse symbol table, so it gets correct
            // relation/binary spacing instead of the old default ORD.
            default -> Symbols.classForCodePoint(cp);
        };
    }

    // ------------------------------------------------------------------
    // Read-only command enumeration (the drift-free "every supported
    // command" index). This exposes the internal command tables as a flat,
    // categorised list so a generator can render every command LatteX
    // accepts. It adds NO parsing/layout/emit behaviour — it only reads the
    // static tables built above, so the index can never drift from what the
    // parser actually supports: add a command to a table and it appears here.
    // ------------------------------------------------------------------

    /**
     * The command family a {@link SupportedCommand} belongs to. The
     * {@link #title()} is a human-readable group heading; enum order is the
     * natural display order for a categorised index.
     */
    public enum Category {
        GREEK("Greek letters"),
        RELATION("Relations"),
        BINARY_OPERATOR("Binary operators"),
        ARROW("Arrows"),
        ORDINARY("Ordinary symbols"),
        BIG_OPERATOR("Big operators"),
        NAMED_OPERATOR("Named operators"),
        ACCENT("Accents & decorations"),
        FONT_VARIANT("Font variants"),
        SPACING("Spacing");

        private final String title;

        Category(String title) {
            this.title = title;
        }

        /** A human-readable heading for this category. */
        public String title() {
            return title;
        }
    }

    /**
     * One parser-supported command paired with a LaTeX snippet that exercises
     * it sensibly for its category (e.g. a bare symbol {@code \leq}, an accent
     * over a base {@code \hat{x}}, spacing between visible marks {@code a\quad b}).
     *
     * @param command       the {@code \}-prefixed command name (e.g. {@code \leq})
     * @param category      the family it belongs to
     * @param renderTemplate a self-contained LaTeX math snippet exercising it
     */
    public record SupportedCommand(String command, Category category, String renderTemplate) {
    }

    /**
     * Enumerates every command in the parser's static command tables (symbols,
     * big operators, named operators, accents, font variants, spacing) into a
     * flat list, each paired with a category and a render template. READ-ONLY:
     * this reflects exactly what {@link #parse} accepts today and updates
     * automatically when a table gains an entry.
     */
    public static List<SupportedCommand> supportedCommands() {
        List<SupportedCommand> out = new ArrayList<>();
        SYMBOLS.forEach((name, sym) ->
            out.add(new SupportedCommand("\\" + name, categorize(sym), "\\" + name)));
        BIG_OPERATORS.forEach((name, sym) ->
            out.add(new SupportedCommand("\\" + name, Category.BIG_OPERATOR,
                "\\" + name + "_{i=1}^{n}")));
        NAMED_OPS.forEach((name, op) ->
            out.add(new SupportedCommand("\\" + name, Category.NAMED_OPERATOR,
                op.takesLimits() ? "\\" + name + "_{x\\to0}" : "\\" + name + " x")));
        ACCENTS.forEach((name, acc) ->
            out.add(new SupportedCommand("\\" + name, Category.ACCENT,
                "\\" + name + accentBase(acc))));
        FONT_VARIANTS.forEach((name, style) ->
            out.add(new SupportedCommand("\\" + name, Category.FONT_VARIANT,
                (name.equals("boldsymbol") || name.equals("bm"))
                    ? "\\" + name + "{\\alpha\\beta\\gamma}"
                    : "\\" + name + "{RQZ}")));
        SPACES.forEach((name, mu) ->
            out.add(new SupportedCommand("\\" + name, Category.SPACING,
                "a\\" + name + " b")));
        // The source tables are unordered maps; sort by (category, command) so the
        // enumeration — and the generated index page — is deterministic (drift-free).
        out.sort(java.util.Comparator
            .comparingInt((SupportedCommand c) -> c.category().ordinal())
            .thenComparing(SupportedCommand::command));
        return List.copyOf(out);
    }

    /**
     * The keyword-dispatched structural commands handled by the big {@code switch} in
     * {@link #parseCommandBody} (fractions, roots, binomials, phantoms, style/color, …).
     * Unlike the symbol/operator/accent tables, these are NOT reachable via
     * {@link #supportedCommands()}, so a "did you mean?" over commands alone would never
     * propose {@code \frac}. This hand-maintained set fills that gap; keep it in sync with
     * the switch when a structural command is added or renamed. (Purely a suggestion
     * source — it changes no parse behavior.)
     */
    private static final List<String> STRUCTURAL_COMMANDS = List.of(
        "frac", "cfrac", "dfrac", "tfrac", "binom", "dbinom", "tbinom", "sqrt",
        "overset", "underset", "stackrel", "underbrace", "overbrace", "substack",
        "phantom", "hphantom", "vphantom", "mathstrut", "operatorname",
        "textcolor", "color", "left", "right", "not", "bmod", "pmod",
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
        "limits", "nolimits", "begin", "end",
        "xrightarrow", "xleftarrow", "xleftrightarrow", "xRightarrow", "xLeftarrow",
        "xLeftrightarrow", "xmapsto", "xhookrightarrow", "xhookleftarrow",
        "xrightleftharpoons", "xlongequal");

    /**
     * A {@code " — did you mean \frac?"} suffix for an unknown command {@code name}
     * (given WITHOUT its leading backslash), or {@code ""} when no supported command is
     * within the {@link FuzzyMatch} threshold. The candidate set is every
     * {@link #supportedCommands()} name (backslash stripped — these track the tables
     * automatically) plus the {@link #STRUCTURAL_COMMANDS} the switch handles directly.
     */
    private static String commandSuggestion(String name) {
        List<String> names = new ArrayList<>(STRUCTURAL_COMMANDS);
        for (SupportedCommand c : supportedCommands()) {
            names.add(c.command().substring(1)); // drop the leading '\'
        }
        return FuzzyMatch.nearest(name, names)
            .map(hit -> " — did you mean \\" + hit + "?")
            .orElse("");
    }

    /** Categorises a symbol-table entry by its code-point range and math class. */
    private static Category categorize(Sym sym) {
        int cp = sym.codePoint();
        // Greek + Coptic block covers every Greek letter and \var* / digamma form.
        if (cp >= 0x0370 && cp <= 0x03FF) {
            return Category.GREEK;
        }
        // Arrows block + Supplemental Arrows-A (the long arrows \longleftarrow …).
        if ((cp >= 0x2190 && cp <= 0x21FF) || (cp >= 0x27F0 && cp <= 0x27FF)) {
            return Category.ARROW;
        }
        return switch (sym.mathClass()) {
            case REL -> Category.RELATION;
            case BIN -> Category.BINARY_OPERATOR;
            default -> Category.ORDINARY; // ORD, INNER, OPEN, CLOSE, PUNCT
        };
    }

    /** A base to place under an accent: wide accents & rules get a run, others a single letter. */
    private static String accentBase(AccentSpec acc) {
        if (acc.codePoint() == Accent.RULE) {
            return "{a+b}"; // overline / underline rule over a short expression
        }
        return acc.stretchy() ? "{abc}" : "{x}";
    }

    /**
     * Every {@code \command -> code point} this parser can emit as a glyph
     * (symbol table + large operators), keyed by the {@code \}-prefixed command.
     * Package-private, for the STIX-coverage test only.
     */
    static Map<String, Integer> allSymbolCodePointsForTest() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        SYMBOLS.forEach((k, v) -> m.put("\\" + k, v.codePoint()));
        BIG_OPERATORS.forEach((k, v) -> m.put("\\" + k, v.codePoint()));
        return m;
    }

    static String describe(Token t) {
        return switch (t.kind()) {
            case CHAR -> "'" + new String(Character.toChars(t.codePoint())) + "'";
            case COMMAND -> "\\" + t.name();
            case TEXT -> "\\" + t.name() + "{...}";
            case LBRACE -> "'{'";
            case RBRACE -> "'}'";
            case SUP -> "'^'";
            case SUB -> "'_'";
            case EOF -> "end of input";
        };
    }
}
