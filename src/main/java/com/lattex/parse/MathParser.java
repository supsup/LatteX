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
import static com.lattex.parse.Symbols.ACCENTS;
import static com.lattex.parse.Symbols.BIG_OPERATORS;
import static com.lattex.parse.Symbols.ENVIRONMENTS;
import static com.lattex.parse.Symbols.FONT_VARIANTS;
import static com.lattex.parse.Symbols.NAMED_OPS;
import static com.lattex.parse.Symbols.NEGATION;
import static com.lattex.parse.Symbols.SPACES;
import static com.lattex.parse.Symbols.SYMBOLS;
import static com.lattex.parse.Symbols.TEXT_COMMANDS;
import com.lattex.parse.Symbols.AccentSpec;
import com.lattex.parse.Symbols.EnvSpec;
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

    // Lexer
    // ------------------------------------------------------------------
    private enum Kind { CHAR, COMMAND, TEXT, LBRACE, RBRACE, SUP, SUB, EOF }

    /**
     * A lexer token. For {@link Kind#TEXT} the {@code name} holds the text-family
     * command (e.g. {@code "textbf"}) and {@code text} the raw brace content with
     * spaces preserved (text mode is space-significant); for other kinds {@code
     * text} is {@code null}.
     */
    private record Token(Kind kind, int codePoint, String name, String text) {
        static Token special(Kind k) {
            return new Token(k, 0, null, null);
        }

        static Token ch(int cp) {
            return new Token(Kind.CHAR, cp, null, null);
        }

        static Token cmd(String name) {
            return new Token(Kind.COMMAND, 0, name, null);
        }

        static Token text(String command, String raw) {
            return new Token(Kind.TEXT, 0, command, raw);
        }
    }

    private final List<Token> tokens;
    private int p;

    private MathParser(String src) {
        this.tokens = lex(src);
    }

    private static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++; // whitespace is insignificant in math mode
                continue;
            }
            switch (c) {
                case '\\' -> {
                    i++;
                    if (i >= n) {
                        throw new MathSyntaxException(
                            "Dangling '\\' at end of input (expected a command name)");
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
                            i = lexTextArgument(s, name, i, out);
                        } else {
                            out.add(Token.cmd(name));
                        }
                    } else {
                        // Single-character control sequence: \, \{ \} \| \! etc.
                        out.add(Token.cmd(String.valueOf(d)));
                        i++;
                    }
                }
                case '{' -> {
                    out.add(Token.special(Kind.LBRACE));
                    i++;
                }
                case '}' -> {
                    out.add(Token.special(Kind.RBRACE));
                    i++;
                }
                case '^' -> {
                    out.add(Token.special(Kind.SUP));
                    i++;
                }
                case '_' -> {
                    out.add(Token.special(Kind.SUB));
                    i++;
                }
                default -> {
                    int cp = s.codePointAt(i);
                    out.add(Token.ch(cp));
                    i += Character.charCount(cp);
                }
            }
        }
        out.add(Token.special(Kind.EOF));
        return out;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Reads the {@code {…}} argument of a text-family command starting at index
     * {@code i} (just past the command name), emitting a single {@link Kind#TEXT}
     * token, and returns the index just past the closing brace. Spaces are kept
     * verbatim (text mode is space-significant); grouping braces are stripped
     * (invisible, as in LaTeX). Nested-math ({@code $…$}) inside the argument is a
     * documented follow-up — the content is taken literally here.
     */
    private static int lexTextArgument(String s, String command, int i, List<Token> out) {
        int n = s.length();
        while (i < n && isWhitespace(s.charAt(i))) {
            i++; // skip space between the control word and its argument
        }
        if (i >= n || s.charAt(i) != '{') {
            throw new MathSyntaxException(
                "\\" + command + " expects a '{...}' text argument");
        }
        i++; // consume '{'
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                i++;
                if (depth == 0) {
                    out.add(Token.text(command, sb.toString()));
                    return i;
                }
            } else {
                int cp = s.codePointAt(i);
                sb.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        throw new MathSyntaxException("Unbalanced brace in \\" + command + " argument");
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
        if (latex == null) {
            throw new MathSyntaxException("input must not be null");
        }
        if (LxOptionsParser.looksLikeTopLevelLx(latex)) {
            return LxOptionsParser.parseLx(latex.strip());
        }
        return parseMath(latex);
    }

    /** Parses ordinary LaTeX math (no top-level {@code \lx}). */
    static MathNode parseMath(String latex) {
        MathParser parser = new MathParser(latex);
        MathNode node = parser.parseTopLevel();
        if (parser.peek().kind() != Kind.EOF) {
            throw new MathSyntaxException(
                "Unexpected trailing input near token " + parser.p + " in: " + latex);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Token cursor helpers
    // ------------------------------------------------------------------
    private Token peek() {
        return tokens.get(p);
    }

    private Token next() {
        return tokens.get(p++);
    }

    private boolean isCommand(Token t, String name) {
        return t.kind() == Kind.COMMAND && t.name().equals(name);
    }

    // ------------------------------------------------------------------
    // Grammar
    // ------------------------------------------------------------------

    private MathNode parseTopLevel() {
        List<MathNode> items = new ArrayList<>();
        while (peek().kind() != Kind.EOF) {
            items.add(parseComponent());
        }
        return wrap(items);
    }

    /** A brace group {@code '{' list '}'}. Assumes the current token is LBRACE. */
    private MathNode parseGroup() {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "Expected '{' but found " + describe(peek()));
        }
        next(); // consume '{'
        List<MathNode> items = new ArrayList<>();
        while (peek().kind() != Kind.RBRACE) {
            if (peek().kind() == Kind.EOF) {
                throw new MathSyntaxException("Unbalanced brace: missing '}'");
            }
            items.add(parseComponent());
        }
        next(); // consume '}'
        return wrap(items);
    }

    /** One component: a nucleus, plus any scripts (or big-operator limits). */
    private MathNode parseComponent() {
        MathNode nucleus = parseNucleus();
        // A large operator carries its scripts as limits, not as a SupSub.
        if (nucleus instanceof Atom op && op.mathClass() == MathClass.OP) {
            return parseBigOperator(op);
        }
        return parseScripts(nucleus);
    }

    /** Attaches an optional {@code ^}/{@code _} pair to an ordinary nucleus. */
    private MathNode parseScripts(MathNode base) {
        MathNode sup = null;
        MathNode sub = null;
        while (true) {
            Kind k = peek().kind();
            if (k == Kind.SUP) {
                if (sup != null) {
                    throw new MathSyntaxException("Double superscript ('^' after '^')");
                }
                next();
                sup = parseScriptArg("superscript");
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
                yield new TextRun(t.text(), TEXT_COMMANDS.get(t.name()));
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
        String name = next().name(); // consume the command

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
                return parseEnvironment();
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
                throw new MathSyntaxException("Unknown command: \\" + name);
            }
        }
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
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException("Unbalanced \\left: missing \\right");
            }
            items.add(parseComponent());
        }
        return wrap(items);
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
    private String readOperatorNameArg() {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\operatorname needs a '{name}' argument but found " + describe(peek()));
        }
        next(); // consume '{'
        StringBuilder sb = new StringBuilder();
        while (peek().kind() != Kind.RBRACE) {
            Token t = peek();
            if (t.kind() != Kind.CHAR) {
                throw new MathSyntaxException(
                    "\\operatorname argument must be plain text, but found " + describe(t));
            }
            sb.appendCodePoint(t.codePoint());
            next();
        }
        next(); // consume '}'
        if (sb.length() == 0) {
            throw new MathSyntaxException("\\operatorname argument must be non-empty");
        }
        return sb.toString();
    }

    /** Reads a delimiter code point after {@code \left}/{@code \right}. */
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
                case "|" -> '|';
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
    // Environments — \begin{env}…\end{env} grids (matrix family, array, cases).
    // '&' separates columns, '\\' separates rows (TeXbook \halign). The grid is
    // parsed here into a Matrix node; layout (S4) turns it into 2-D geometry.
    // ------------------------------------------------------------------

    /**
     * Parses a {@code \begin{env}…\end{env}} grid into a {@link Matrix}. The
     * current token is just past {@code \begin}. Fails loud on an unknown
     * environment, a mismatched {@code \end}, a ragged {@code array} row (more cells
     * than the column spec), or an unbalanced environment.
     */
    private MathNode parseEnvironment() {
        String env = readBraceName("\\begin");
        EnvSpec spec = ENVIRONMENTS.get(env);
        if (spec == null) {
            throw new MathSyntaxException("Unknown environment: \\begin{" + env + "}");
        }

        // array carries a user column spec {ccc|c}; other envs are uniform.
        List<ColumnAlign> specAligns = null;
        List<Integer> specVlines = null;
        if (spec.kind() == MatrixKind.ARRAY) {
            ColumnSpec cs = readColumnSpec();
            specAligns = cs.aligns();
            specVlines = cs.vlines();
        }

        // Read the body: cells (& separated) into rows (\\ separated), tracking
        // \hline/\hdashline rules per inter-row gap (gap index = rows completed).
        List<List<MathNode>> rawRows = new ArrayList<>();
        Map<Integer, RowRule> hlines = new java.util.HashMap<>();
        List<MathNode> row = new ArrayList<>();
        List<MathNode> cell = new ArrayList<>();

        while (true) {
            Token t = peek();
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException(
                    "Unterminated \\begin{" + env + "}: missing \\end{" + env + "}");
            }
            if (isCommand(t, "end")) {
                next();
                String endEnv = readBraceName("\\end");
                if (!endEnv.equals(env)) {
                    throw new MathSyntaxException(
                        "\\begin{" + env + "} closed by \\end{" + endEnv + "}");
                }
                break;
            }
            if (isCommand(t, "hline") || isCommand(t, "hdashline")) {
                RowRule rule = t.name().equals("hline") ? RowRule.SOLID : RowRule.DASHED;
                next();
                hlines.merge(rawRows.size(), rule,
                    (a, b) -> a == RowRule.SOLID ? a : b); // a solid line wins
                continue;
            }
            if (isCommand(t, "\\") || isCommand(t, "cr")) {
                next();
                skipRowBreakOptions(); // an optional \\[len] / \\* is accepted and ignored
                row.add(wrap(cell));
                cell = new ArrayList<>();
                rawRows.add(row);
                row = new ArrayList<>();
                continue;
            }
            if (t.kind() == Kind.CHAR && t.codePoint() == '&') {
                next();
                row.add(wrap(cell));
                cell = new ArrayList<>();
                continue;
            }
            cell.add(parseComponent());
        }
        // Finalize a trailing row (content with no closing \\). A bare trailing \\
        // (row + cell both empty) adds no phantom row, matching LaTeX.
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(wrap(cell));
            rawRows.add(row);
        }
        if (rawRows.isEmpty()) {
            throw new MathSyntaxException("empty \\begin{" + env + "} environment");
        }

        return buildMatrix(env, spec, specAligns, specVlines, rawRows, hlines);
    }

    /**
     * Assembles the parsed rows into a rectangular {@link Matrix}: determines the
     * column count, pads short rows with empty cells, builds the per-column
     * alignment + vertical-rule lists, and materialises the inter-row rule list.
     */
    private static MathNode buildMatrix(String env, EnvSpec spec, List<ColumnAlign> specAligns,
                                        List<Integer> specVlines, List<List<MathNode>> rawRows,
                                        Map<Integer, RowRule> hlines) {
        int cols;
        List<ColumnAlign> aligns;
        List<Integer> vlines;
        if (spec.kind() == MatrixKind.ARRAY) {
            cols = specAligns.size();
            for (List<MathNode> r : rawRows) {
                if (r.size() > cols) {
                    throw new MathSyntaxException("array row has " + r.size()
                        + " cells but the column spec declares only " + cols);
                }
            }
            aligns = specAligns;
            vlines = specVlines;
        } else {
            cols = 0;
            for (List<MathNode> r : rawRows) {
                cols = Math.max(cols, r.size());
            }
            List<ColumnAlign> a = new ArrayList<>(cols);
            for (int i = 0; i < cols; i++) {
                a.add(spec.uniform());
            }
            aligns = a;
            List<Integer> v = new ArrayList<>(cols + 1);
            for (int i = 0; i <= cols; i++) {
                v.add(0);
            }
            vlines = v;
        }

        // Pad short rows to the column count with empty cells (TeX pads with nulls).
        MathNode empty = new MathList(List.of());
        List<List<MathNode>> grid = new ArrayList<>(rawRows.size());
        for (List<MathNode> r : rawRows) {
            List<MathNode> padded = new ArrayList<>(r);
            while (padded.size() < cols) {
                padded.add(empty);
            }
            grid.add(padded);
        }

        List<RowRule> rowRules = new ArrayList<>(grid.size() + 1);
        for (int g = 0; g <= grid.size(); g++) {
            rowRules.add(hlines.getOrDefault(g, RowRule.NONE));
        }

        return new Matrix(grid, aligns, vlines, rowRules,
            spec.leftDelim(), spec.rightDelim(), spec.kind());
    }

    /** Silently consumes an optional {@code \\*} and/or {@code \\[len]} row-break modifier. */
    private void skipRowBreakOptions() {
        if (peek().kind() == Kind.CHAR && peek().codePoint() == '*') {
            next();
        }
        if (peek().kind() == Kind.CHAR && peek().codePoint() == '[') {
            next(); // consume '['
            while (peek().kind() != Kind.EOF
                    && !(peek().kind() == Kind.CHAR && peek().codePoint() == ']')) {
                next();
            }
            if (peek().kind() == Kind.EOF) {
                throw new MathSyntaxException("unterminated \\\\[...] row-break length");
            }
            next(); // consume ']'
        }
    }

    /**
     * Reads a {@code {name}} argument of plain ASCII-letter characters (the
     * environment name after {@code \begin}/{@code \end}). Rejects anything that is
     * not a run of letters, so a malformed {@code \begin{...}} fails cleanly.
     */
    private String readBraceName(String context) {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                context + " expects a '{name}' but found " + describe(peek()));
        }
        next(); // consume '{'
        StringBuilder sb = new StringBuilder();
        while (peek().kind() == Kind.CHAR) {
            sb.appendCodePoint(peek().codePoint());
            next();
        }
        if (peek().kind() != Kind.RBRACE) {
            throw new MathSyntaxException(
                context + " environment name must be plain letters, but found " + describe(peek()));
        }
        next(); // consume '}'
        if (sb.length() == 0) {
            throw new MathSyntaxException(context + " environment name must be non-empty");
        }
        return sb.toString();
    }

    /** The parsed {@code array} column spec: per-column alignment + boundary rules. */
    private record ColumnSpec(List<ColumnAlign> aligns, List<Integer> vlines) {
    }

    /**
     * Reads an {@code array} column spec {@code {lcr|}}: {@code l}/{@code c}/{@code r}
     * declare left/centre/right columns and {@code |} adds a vertical rule at the
     * current boundary. {@code vlines} has one count per {@code columns+1} boundary.
     * Unsupported column types ({@code p{}}, {@code @{}}, {@code *}, …) fail loud.
     */
    private ColumnSpec readColumnSpec() {
        if (peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\begin{array} requires a {column spec} but found " + describe(peek()));
        }
        next(); // consume '{'
        List<ColumnAlign> aligns = new ArrayList<>();
        List<Integer> vlines = new ArrayList<>();
        vlines.add(0); // boundary before the first column
        while (peek().kind() != Kind.RBRACE) {
            Token t = peek();
            if (t.kind() != Kind.CHAR) {
                throw new MathSyntaxException(
                    "array column spec must be l/c/r and '|', but found " + describe(t));
            }
            int cp = t.codePoint();
            switch (cp) {
                case 'l' -> { aligns.add(ColumnAlign.LEFT); vlines.add(0); }
                case 'c' -> { aligns.add(ColumnAlign.CENTER); vlines.add(0); }
                case 'r' -> { aligns.add(ColumnAlign.RIGHT); vlines.add(0); }
                case '|' -> vlines.set(vlines.size() - 1, vlines.get(vlines.size() - 1) + 1);
                default -> throw new MathSyntaxException(
                    "unsupported array column type '" + new String(Character.toChars(cp))
                        + "' (only l, c, r and | are supported)");
            }
            next();
        }
        next(); // consume '}'
        if (aligns.isEmpty()) {
            throw new MathSyntaxException("array column spec must declare at least one column");
        }
        return new ColumnSpec(aligns, vlines);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Wraps a component list: unwrap a singleton, else a {@link MathList}. */
    private static MathNode wrap(List<MathNode> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return new MathList(items);
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
            default -> MathClass.ORD;
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

    private static String describe(Token t) {
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
