package com.lattex.parse;

import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.LimitsMode;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.SupSub;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    // Symbol table: LaTeX command -> code point + atom class.
    // Clean-room: code points from the Unicode standard; classes from the
    // TeXbook's atom-class assignments (Ch.18 / Appendix G tables).
    // ------------------------------------------------------------------
    private record Sym(int codePoint, MathClass mathClass) {
    }

    private static final Map<String, Sym> SYMBOLS = buildSymbols();

    // Large operators, handled specially (they take limits).
    private static final Map<String, Sym> BIG_OPERATORS = Map.of(
        "sum", new Sym(0x2211, MathClass.OP),   // ∑
        "int", new Sym(0x222B, MathClass.OP),   // ∫
        "prod", new Sym(0x220F, MathClass.OP)); // ∏

    // Explicit spacing commands -> width in math units (18mu = 1em).
    private static final Map<String, Double> SPACES = Map.of(
        ",", 3.0,    // thin space  \,
        ":", 4.0,    // medium space \:
        ";", 5.0,    // thick space  \;
        "!", -3.0,   // negative thin \!
        " ", 6.0,    // control space \(space)
        "quad", 18.0,
        "qquad", 36.0);

    private static Map<String, Sym> buildSymbols() {
        Map<String, Sym> m = new java.util.HashMap<>();

        // Lowercase Greek.
        m.put("alpha", new Sym(0x03B1, MathClass.ORD));
        m.put("beta", new Sym(0x03B2, MathClass.ORD));
        m.put("gamma", new Sym(0x03B3, MathClass.ORD));
        m.put("delta", new Sym(0x03B4, MathClass.ORD));
        m.put("epsilon", new Sym(0x03B5, MathClass.ORD));
        m.put("zeta", new Sym(0x03B6, MathClass.ORD));
        m.put("eta", new Sym(0x03B7, MathClass.ORD));
        m.put("theta", new Sym(0x03B8, MathClass.ORD));
        m.put("iota", new Sym(0x03B9, MathClass.ORD));
        m.put("kappa", new Sym(0x03BA, MathClass.ORD));
        m.put("lambda", new Sym(0x03BB, MathClass.ORD));
        m.put("mu", new Sym(0x03BC, MathClass.ORD));
        m.put("nu", new Sym(0x03BD, MathClass.ORD));
        m.put("xi", new Sym(0x03BE, MathClass.ORD));
        m.put("omicron", new Sym(0x03BF, MathClass.ORD));
        m.put("pi", new Sym(0x03C0, MathClass.ORD));
        m.put("rho", new Sym(0x03C1, MathClass.ORD));
        m.put("sigma", new Sym(0x03C3, MathClass.ORD));
        m.put("tau", new Sym(0x03C4, MathClass.ORD));
        m.put("upsilon", new Sym(0x03C5, MathClass.ORD));
        m.put("phi", new Sym(0x03C6, MathClass.ORD));
        m.put("chi", new Sym(0x03C7, MathClass.ORD));
        m.put("psi", new Sym(0x03C8, MathClass.ORD));
        m.put("omega", new Sym(0x03C9, MathClass.ORD));

        // Uppercase Greek (the LaTeX-provided set).
        m.put("Gamma", new Sym(0x0393, MathClass.ORD));
        m.put("Delta", new Sym(0x0394, MathClass.ORD));
        m.put("Theta", new Sym(0x0398, MathClass.ORD));
        m.put("Lambda", new Sym(0x039B, MathClass.ORD));
        m.put("Xi", new Sym(0x039E, MathClass.ORD));
        m.put("Pi", new Sym(0x03A0, MathClass.ORD));
        m.put("Sigma", new Sym(0x03A3, MathClass.ORD));
        m.put("Phi", new Sym(0x03A6, MathClass.ORD));
        m.put("Psi", new Sym(0x03A8, MathClass.ORD));
        m.put("Omega", new Sym(0x03A9, MathClass.ORD));

        // Binary operators.
        m.put("times", new Sym(0x00D7, MathClass.BIN)); // ×
        m.put("cdot", new Sym(0x22C5, MathClass.BIN));  // ⋅
        m.put("pm", new Sym(0x00B1, MathClass.BIN));    // ±

        // Relations.
        m.put("leq", new Sym(0x2264, MathClass.REL));    // ≤
        m.put("le", new Sym(0x2264, MathClass.REL));     // alias
        m.put("geq", new Sym(0x2265, MathClass.REL));    // ≥
        m.put("ge", new Sym(0x2265, MathClass.REL));     // alias
        m.put("neq", new Sym(0x2260, MathClass.REL));    // ≠
        m.put("ne", new Sym(0x2260, MathClass.REL));     // alias
        m.put("approx", new Sym(0x2248, MathClass.REL)); // ≈
        m.put("equiv", new Sym(0x2261, MathClass.REL));  // ≡
        m.put("to", new Sym(0x2192, MathClass.REL));     // →
        m.put("rightarrow", new Sym(0x2192, MathClass.REL)); // →
        m.put("in", new Sym(0x2208, MathClass.REL));     // ∈
        m.put("subset", new Sym(0x2282, MathClass.REL)); // ⊂

        // Ordinary symbols.
        m.put("infty", new Sym(0x221E, MathClass.ORD));   // ∞
        m.put("partial", new Sym(0x2202, MathClass.ORD)); // ∂
        m.put("nabla", new Sym(0x2207, MathClass.ORD));   // ∇

        // Inner (dots).
        m.put("cdots", new Sym(0x22EF, MathClass.INNER)); // ⋯
        m.put("ldots", new Sym(0x2026, MathClass.INNER)); // …

        // Brace symbols usable outside \left..\right.
        m.put("{", new Sym('{', MathClass.OPEN));
        m.put("}", new Sym('}', MathClass.CLOSE));
        m.put("|", new Sym('|', MathClass.ORD));

        return Map.copyOf(m);
    }

    // ------------------------------------------------------------------
    // Lexer
    // ------------------------------------------------------------------
    private enum Kind { CHAR, COMMAND, LBRACE, RBRACE, SUP, SUB, EOF }

    private record Token(Kind kind, int codePoint, String name) {
        static Token special(Kind k) {
            return new Token(k, 0, null);
        }

        static Token ch(int cp) {
            return new Token(Kind.CHAR, cp, null);
        }

        static Token cmd(String name) {
            return new Token(Kind.COMMAND, 0, name);
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
                        out.add(Token.cmd(s.substring(i, j)));
                        i = j;
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

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /** Parses a LaTeX math source string into a {@link MathNode}. */
    public static MathNode parse(String latex) {
        if (latex == null) {
            throw new MathSyntaxException("input must not be null");
        }
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

    /** A single nucleus (no trailing scripts). */
    private MathNode parseNucleus() {
        Token t = peek();
        return switch (t.kind()) {
            case LBRACE -> parseGroup();
            case CHAR -> {
                next();
                yield charAtom(t.codePoint());
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
                MathNode num = parseGroup();
                MathNode den = parseGroup();
                return new Fraction(num, den);
            }
            case "sqrt" -> {
                MathNode index = null;
                if (peek().kind() == Kind.CHAR && peek().codePoint() == '[') {
                    next(); // consume '['
                    index = parseUntilRBracket();
                }
                MathNode radicand = parseGroup();
                return new Radical(radicand, index);
            }
            case "left" -> {
                int leftDelim = readDelimiter("\\left");
                MathNode body = parseFencedBody();
                int rightDelim = readDelimiter("\\right");
                return new Fenced(leftDelim, body, rightDelim);
            }
            case "right" -> throw new MathSyntaxException("\\right without matching \\left");
            case "limits", "nolimits" ->
                throw new MathSyntaxException("\\" + name + " must directly follow a large operator");
            default -> {
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

    private static String describe(Token t) {
        return switch (t.kind()) {
            case CHAR -> "'" + new String(Character.toChars(t.codePoint())) + "'";
            case COMMAND -> "\\" + t.name();
            case LBRACE -> "'{'";
            case RBRACE -> "'}'";
            case SUP -> "'^'";
            case SUB -> "'_'";
            case EOF -> "end of input";
        };
    }
}
