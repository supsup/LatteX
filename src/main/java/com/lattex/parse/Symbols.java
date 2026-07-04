package com.lattex.parse;

import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MatrixKind;
import com.lattex.parse.MathNode.TextStyle;
import java.util.List;
import java.util.Map;

/**
 * The parser's static, read-only data tables: the LaTeX-command &rarr; code-point
 * symbol table, the big-operator / named-operator / accent / negation / spacing /
 * font-variant / text-command tables, and the supported grid environments. These
 * are pure data carriers moved out of {@link MathParser} verbatim (clean-room:
 * code points from the Unicode standard, atom classes and operator/limit
 * behaviour from Knuth's TeXbook). {@code MathParser} reads them via {@code
 * import static}; the command enumeration ({@link MathParser#supportedCommands()})
 * iterates them, so it can never drift from what the parser accepts.
 */
final class Symbols {

    private Symbols() {
    }

    // ------------------------------------------------------------------
    // Symbol table: LaTeX command -> code point + atom class.
    // Clean-room: code points from the Unicode standard; classes from the
    // TeXbook's atom-class assignments (Ch.18 / Appendix G tables).
    // ------------------------------------------------------------------
    record Sym(int codePoint, MathClass mathClass) {
    }

    static final Map<String, Sym> SYMBOLS = buildSymbols();

    // Large operators, handled specially (they take limits).
    static final Map<String, Sym> BIG_OPERATORS = Map.ofEntries(
        Map.entry("sum", new Sym(0x2211, MathClass.OP)),      // ∑
        Map.entry("int", new Sym(0x222B, MathClass.OP)),      // ∫
        Map.entry("prod", new Sym(0x220F, MathClass.OP)),     // ∏
        Map.entry("coprod", new Sym(0x2210, MathClass.OP)),   // ∐
        Map.entry("oint", new Sym(0x222E, MathClass.OP)),     // ∮
        Map.entry("bigcup", new Sym(0x22C3, MathClass.OP)),   // ⋃
        Map.entry("bigcap", new Sym(0x22C2, MathClass.OP)),   // ⋂
        Map.entry("bigsqcup", new Sym(0x2A06, MathClass.OP)), // ⨆
        Map.entry("bigvee", new Sym(0x22C1, MathClass.OP)),   // ⋁
        Map.entry("bigwedge", new Sym(0x22C0, MathClass.OP)), // ⋀
        Map.entry("bigodot", new Sym(0x2A00, MathClass.OP)),  // ⨀
        Map.entry("bigotimes", new Sym(0x2A02, MathClass.OP)), // ⨂
        Map.entry("bigoplus", new Sym(0x2A01, MathClass.OP)), // ⨁
        Map.entry("biguplus", new Sym(0x2A04, MathClass.OP))); // ⨄

    // Explicit spacing commands -> width in math units (18mu = 1em). Widths are
    // clean-room from Knuth's TeXbook (Ch.18 / Appendix G): \, = 3mu, \: = 4mu,
    // \; = 5mu, and the negatives mirror them; \quad = 18mu (1em), \qquad = 36mu;
    // \enspace = 0.5em = 9mu; the control space \(space) and ~ (tie) are an
    // interword space, approximated here as 6mu.
    static final Map<String, Double> SPACES = Map.ofEntries(
        Map.entry(",", 3.0),             // thin space   \,
        Map.entry("thinspace", 3.0),     // \thinspace == \,
        Map.entry(":", 4.0),             // medium space \:
        Map.entry(">", 4.0),             // \> == \:
        Map.entry("medspace", 4.0),      // \medspace == \:
        Map.entry(";", 5.0),             // thick space  \;
        Map.entry("thickspace", 5.0),    // \thickspace == \;
        Map.entry("!", -3.0),            // negative thin \!
        Map.entry("negthinspace", -3.0), // \negthinspace == \!
        Map.entry("negmedspace", -4.0),  // negative medium
        Map.entry("negthickspace", -5.0),// negative thick
        Map.entry(" ", 6.0),             // control space \(space)
        Map.entry("quad", 18.0),         // 1em
        Map.entry("qquad", 36.0),        // 2em
        Map.entry("enspace", 9.0));      // 0.5em

    /**
     * A named operator's roman rendering text plus whether it takes limits
     * (over/under in display style) rather than beside-set scripts.
     */
    record OpSpec(String display, boolean takesLimits) {
    }

    /**
     * The predefined named operators (TeXbook Ch.18 "log-like functions" +
     * the limit-taking operators). Rendered in upright roman; the {@code display}
     * text is the letters to typeset — a single space marks the word break in the
     * compound forms ({@code \liminf}, {@code \limsup}, {@code \injlim},
     * {@code \projlim}), which TeX sets with a thin space. Clean-room: the name
     * set and limit behaviour are from Knuth's TeXbook (operators are roman;
     * {@code \lim} and friends take limits), not from any renderer's source.
     */
    static final Map<String, OpSpec> NAMED_OPS = buildNamedOps();

    static Map<String, OpSpec> buildNamedOps() {
        Map<String, OpSpec> m = new java.util.HashMap<>();
        // Non-limit "log-like" functions: scripts always sit beside.
        for (String f : List.of("sin", "cos", "tan", "cot", "sec", "csc",
                "sinh", "cosh", "tanh", "coth", "arcsin", "arccos", "arctan",
                "log", "ln", "lg", "exp")) {
            m.put(f, new OpSpec(f, false));
        }
        // Limit-taking operators: scripts become over/under limits in display.
        for (String f : List.of("lim", "max", "min", "sup", "inf", "det", "gcd",
                "deg", "dim", "ker", "hom", "arg")) {
            m.put(f, new OpSpec(f, true));
        }
        m.put("Pr", new OpSpec("Pr", true));
        m.put("liminf", new OpSpec("lim inf", true));
        m.put("limsup", new OpSpec("lim sup", true));
        m.put("injlim", new OpSpec("inj lim", true));
        m.put("projlim", new OpSpec("proj lim", true));
        return Map.copyOf(m);
    }

    /**
     * Accent commands -> the accent glyph and how it is applied. Code points are
     * from the Unicode standard (combining diacritics / combining arrows above,
     * which STIX Two Math provides as standalone accent glyphs and, for the wide
     * forms, with OpenType MATH horizontal constructions). {@code \overline} /
     * {@code \\underline} map to {@link Accent#RULE} — they are drawn as a rule,
     * not a glyph.
     */
    record AccentSpec(int codePoint, boolean stretchy, boolean under) {
    }

    static final Map<String, AccentSpec> ACCENTS = Map.ofEntries(
        // Narrow accents (natural size).
        Map.entry("hat", new AccentSpec(0x0302, false, false)),      // ◌̂ circumflex
        Map.entry("bar", new AccentSpec(0x0304, false, false)),      // ◌̄ macron
        Map.entry("vec", new AccentSpec(0x20D7, false, false)),      // ◌⃗ right arrow above
        Map.entry("dot", new AccentSpec(0x0307, false, false)),      // ◌̇ dot above
        Map.entry("ddot", new AccentSpec(0x0308, false, false)),     // ◌̈ diaeresis
        Map.entry("tilde", new AccentSpec(0x0303, false, false)),    // ◌̃ tilde
        Map.entry("check", new AccentSpec(0x030C, false, false)),    // ◌̌ caron
        Map.entry("breve", new AccentSpec(0x0306, false, false)),    // ◌̆ breve
        Map.entry("acute", new AccentSpec(0x0301, false, false)),    // ◌́ acute
        Map.entry("grave", new AccentSpec(0x0300, false, false)),    // ◌̀ grave
        Map.entry("mathring", new AccentSpec(0x030A, false, false)), // ◌̊ ring above
        // Wide / stretchy accents (sized to the base width).
        Map.entry("widehat", new AccentSpec(0x0302, true, false)),
        Map.entry("widetilde", new AccentSpec(0x0303, true, false)),
        Map.entry("overrightarrow", new AccentSpec(0x20D7, true, false)),
        Map.entry("overleftarrow", new AccentSpec(0x20D6, true, false)),   // ◌⃖ left arrow above
        Map.entry("overleftrightarrow", new AccentSpec(0x20E1, true, false)), // ◌⃡
        // Line decorations (drawn as a rule, in-alphabet).
        Map.entry("overline", new AccentSpec(Accent.RULE, false, false)),
        Map.entry("underline", new AccentSpec(Accent.RULE, false, true)));

    // The \not prefix: base code point -> precomposed negated code point (Unicode).
    // We only negate relations that HAVE a single precomposed negation glyph in
    // Unicode (which STIX Two Math provides); anything else is reported loudly
    // rather than faked with an overlay we cannot draw in the minimal alphabet.
    static final Map<Integer, Integer> NEGATION = Map.ofEntries(
        Map.entry(0x003D, 0x2260), // =  -> ≠
        Map.entry(0x003C, 0x226E), // <  -> ≮
        Map.entry(0x003E, 0x226F), // >  -> ≯
        Map.entry(0x2264, 0x2270), // ≤  -> ≰
        Map.entry(0x2265, 0x2271), // ≥  -> ≱
        Map.entry(0x2208, 0x2209), // ∈  -> ∉
        Map.entry(0x220B, 0x220C), // ∋  -> ∌
        Map.entry(0x2261, 0x2262), // ≡  -> ≢
        Map.entry(0x223C, 0x2241), // ∼  -> ≁
        Map.entry(0x2245, 0x2247), // ≅  -> ≇
        Map.entry(0x2248, 0x2249), // ≈  -> ≉
        Map.entry(0x2282, 0x2284), // ⊂  -> ⊄
        Map.entry(0x2283, 0x2285), // ⊃  -> ⊅
        Map.entry(0x2286, 0x2288), // ⊆  -> ⊈
        Map.entry(0x2287, 0x2289), // ⊇  -> ⊉
        Map.entry(0x227A, 0x2280), // ≺  -> ⊀
        Map.entry(0x227B, 0x2281), // ≻  -> ⊁
        Map.entry(0x2223, 0x2224), // ∣  -> ∤
        Map.entry(0x2225, 0x2226), // ∥  -> ∦
        Map.entry(0x2192, 0x219B), // →  -> ↛
        Map.entry(0x2190, 0x219A), // ←  -> ↚
        Map.entry(0x2194, 0x21AE), // ↔  -> ↮
        Map.entry(0x21D2, 0x21CF), // ⇒  -> ⇏
        Map.entry(0x21D0, 0x21CD), // ⇐  -> ⇍
        Map.entry(0x21D4, 0x21CE)); // ⇔ -> ⇎

    /**
     * Font-variant alphabet commands -> the {@link MathVariant.Style} they apply.
     * {@code \mathX{content}} rewrites the enclosed atoms to the variant's
     * Mathematical-Alphanumeric-Symbols code points (see {@link MathVariant}); it
     * adds no MathNode kind. {@code \mathcal} and {@code \mathscr} share the single
     * bundled STIX script alphabet; {@code \boldsymbol}/{@code \bm} additionally
     * bold Greek (which {@code \mathbf} leaves upright, matching LaTeX).
     */
    static final Map<String, MathVariant.Style> FONT_VARIANTS = Map.ofEntries(
        Map.entry("mathbb", MathVariant.Style.BLACKBOARD),
        Map.entry("mathcal", MathVariant.Style.SCRIPT),
        Map.entry("mathscr", MathVariant.Style.SCRIPT),
        Map.entry("mathfrak", MathVariant.Style.FRAKTUR),
        Map.entry("mathbf", MathVariant.Style.BOLD),
        Map.entry("mathsf", MathVariant.Style.SANS),
        Map.entry("mathit", MathVariant.Style.ITALIC),
        Map.entry("mathtt", MathVariant.Style.MONO),
        Map.entry("boldsymbol", MathVariant.Style.BOLDSYMBOL),
        Map.entry("bm", MathVariant.Style.BOLDSYMBOL));

    static Map<String, Sym> buildSymbols() {
        Map<String, Sym> m = new java.util.HashMap<>();

        // Lowercase Greek.
        m.put("alpha", new Sym(0x03B1, MathClass.ORD));
        m.put("beta", new Sym(0x03B2, MathClass.ORD));
        m.put("gamma", new Sym(0x03B3, MathClass.ORD));
        m.put("delta", new Sym(0x03B4, MathClass.ORD));
        // \epsilon is the lunate form (U+03F5); \varepsilon is U+03B5 (added below).
        m.put("epsilon", new Sym(0x03F5, MathClass.ORD));
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
        // \phi is the closed form (U+03D5); \varphi is U+03C6 (added below).
        m.put("phi", new Sym(0x03D5, MathClass.ORD));
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
        m.put("Upsilon", new Sym(0x03A5, MathClass.ORD));
        m.put("Phi", new Sym(0x03A6, MathClass.ORD));
        m.put("Psi", new Sym(0x03A8, MathClass.ORD));
        m.put("Omega", new Sym(0x03A9, MathClass.ORD));

        // Greek variant letters (\var* forms) + digamma.
        m.put("varepsilon", new Sym(0x03B5, MathClass.ORD)); // ε
        m.put("vartheta", new Sym(0x03D1, MathClass.ORD));   // ϑ
        m.put("varpi", new Sym(0x03D6, MathClass.ORD));      // ϖ
        m.put("varrho", new Sym(0x03F1, MathClass.ORD));     // ϱ
        m.put("varsigma", new Sym(0x03C2, MathClass.ORD));   // ς
        m.put("varphi", new Sym(0x03C6, MathClass.ORD));     // φ
        m.put("varkappa", new Sym(0x03F0, MathClass.ORD));   // ϰ
        m.put("digamma", new Sym(0x03DD, MathClass.ORD));    // ϝ

        // -- Binary operators (MathClass.BIN) --------------------------------
        m.put("pm", new Sym(0x00B1, MathClass.BIN));       // ±
        m.put("mp", new Sym(0x2213, MathClass.BIN));       // ∓
        m.put("times", new Sym(0x00D7, MathClass.BIN));    // ×
        m.put("div", new Sym(0x00F7, MathClass.BIN));      // ÷
        m.put("cdot", new Sym(0x22C5, MathClass.BIN));     // ⋅
        m.put("ast", new Sym(0x2217, MathClass.BIN));      // ∗
        m.put("star", new Sym(0x22C6, MathClass.BIN));     // ⋆
        m.put("circ", new Sym(0x2218, MathClass.BIN));     // ∘
        m.put("bullet", new Sym(0x2219, MathClass.BIN));   // ∙
        m.put("cap", new Sym(0x2229, MathClass.BIN));      // ∩
        m.put("cup", new Sym(0x222A, MathClass.BIN));      // ∪
        m.put("Cap", new Sym(0x22D2, MathClass.BIN));      // ⋒
        m.put("Cup", new Sym(0x22D3, MathClass.BIN));      // ⋓
        m.put("sqcap", new Sym(0x2293, MathClass.BIN));    // ⊓
        m.put("sqcup", new Sym(0x2294, MathClass.BIN));    // ⊔
        m.put("uplus", new Sym(0x228E, MathClass.BIN));    // ⊎
        m.put("wedge", new Sym(0x2227, MathClass.BIN));    // ∧
        m.put("vee", new Sym(0x2228, MathClass.BIN));      // ∨
        m.put("land", new Sym(0x2227, MathClass.BIN));     // ∧ alias
        m.put("lor", new Sym(0x2228, MathClass.BIN));      // ∨ alias
        m.put("setminus", new Sym(0x2216, MathClass.BIN)); // ∖
        m.put("smallsetminus", new Sym(0x2216, MathClass.BIN)); // ∖
        m.put("wr", new Sym(0x2240, MathClass.BIN));       // ≀
        m.put("amalg", new Sym(0x2A3F, MathClass.BIN));    // ⨿
        m.put("oplus", new Sym(0x2295, MathClass.BIN));    // ⊕
        m.put("ominus", new Sym(0x2296, MathClass.BIN));   // ⊖
        m.put("otimes", new Sym(0x2297, MathClass.BIN));   // ⊗
        m.put("oslash", new Sym(0x2298, MathClass.BIN));   // ⊘
        m.put("odot", new Sym(0x2299, MathClass.BIN));     // ⊙
        m.put("circledcirc", new Sym(0x229A, MathClass.BIN));  // ⊚
        m.put("circledast", new Sym(0x229B, MathClass.BIN));   // ⊛
        m.put("circleddash", new Sym(0x229D, MathClass.BIN));  // ⊝
        m.put("boxplus", new Sym(0x229E, MathClass.BIN));  // ⊞
        m.put("boxminus", new Sym(0x229F, MathClass.BIN)); // ⊟
        m.put("boxtimes", new Sym(0x22A0, MathClass.BIN)); // ⊠
        m.put("boxdot", new Sym(0x22A1, MathClass.BIN));   // ⊡
        m.put("dagger", new Sym(0x2020, MathClass.BIN));   // †
        m.put("ddagger", new Sym(0x2021, MathClass.BIN));  // ‡
        m.put("diamond", new Sym(0x22C4, MathClass.BIN));  // ⋄
        m.put("dotplus", new Sym(0x2214, MathClass.BIN));  // ∔
        m.put("bigtriangleup", new Sym(0x25B3, MathClass.BIN));   // △
        m.put("bigtriangledown", new Sym(0x25BD, MathClass.BIN)); // ▽
        m.put("triangleleft", new Sym(0x25C3, MathClass.BIN));    // ◃
        m.put("triangleright", new Sym(0x25B9, MathClass.BIN));   // ▹
        m.put("barwedge", new Sym(0x22BC, MathClass.BIN));     // ⊼
        m.put("veebar", new Sym(0x22BB, MathClass.BIN));       // ⊻
        m.put("doublebarwedge", new Sym(0x2A5E, MathClass.BIN)); // ⩞
        m.put("curlywedge", new Sym(0x22CF, MathClass.BIN));   // ⋏
        m.put("curlyvee", new Sym(0x22CE, MathClass.BIN));     // ⋎
        m.put("intercal", new Sym(0x22BA, MathClass.BIN));     // ⊺
        m.put("divideontimes", new Sym(0x22C7, MathClass.BIN)); // ⋇
        m.put("ltimes", new Sym(0x22C9, MathClass.BIN));       // ⋉
        m.put("rtimes", new Sym(0x22CA, MathClass.BIN));       // ⋊
        m.put("leftthreetimes", new Sym(0x22CB, MathClass.BIN));  // ⋋
        m.put("rightthreetimes", new Sym(0x22CC, MathClass.BIN)); // ⋌

        // -- Relations (MathClass.REL) ---------------------------------------
        m.put("leq", new Sym(0x2264, MathClass.REL));    // ≤
        m.put("le", new Sym(0x2264, MathClass.REL));     // alias
        m.put("geq", new Sym(0x2265, MathClass.REL));    // ≥
        m.put("ge", new Sym(0x2265, MathClass.REL));     // alias
        m.put("neq", new Sym(0x2260, MathClass.REL));    // ≠
        m.put("ne", new Sym(0x2260, MathClass.REL));     // alias
        m.put("leqslant", new Sym(0x2A7D, MathClass.REL));  // ⩽
        m.put("geqslant", new Sym(0x2A7E, MathClass.REL));  // ⩾
        m.put("equiv", new Sym(0x2261, MathClass.REL));  // ≡
        m.put("sim", new Sym(0x223C, MathClass.REL));    // ∼
        m.put("simeq", new Sym(0x2243, MathClass.REL));  // ≃
        m.put("cong", new Sym(0x2245, MathClass.REL));   // ≅
        m.put("approx", new Sym(0x2248, MathClass.REL)); // ≈
        m.put("approxeq", new Sym(0x224A, MathClass.REL)); // ≊
        m.put("asymp", new Sym(0x224D, MathClass.REL));  // ≍
        m.put("propto", new Sym(0x221D, MathClass.REL)); // ∝
        m.put("doteq", new Sym(0x2250, MathClass.REL));  // ≐
        m.put("doteqdot", new Sym(0x2251, MathClass.REL)); // ≑
        m.put("fallingdotseq", new Sym(0x2252, MathClass.REL)); // ≒
        m.put("risingdotseq", new Sym(0x2253, MathClass.REL));  // ≓
        m.put("circeq", new Sym(0x2257, MathClass.REL)); // ≗
        m.put("triangleq", new Sym(0x225C, MathClass.REL)); // ≜
        m.put("bumpeq", new Sym(0x224F, MathClass.REL)); // ≏
        m.put("Bumpeq", new Sym(0x224E, MathClass.REL)); // ≎
        m.put("prec", new Sym(0x227A, MathClass.REL));   // ≺
        m.put("succ", new Sym(0x227B, MathClass.REL));   // ≻
        m.put("preceq", new Sym(0x2AAF, MathClass.REL)); // ⪯
        m.put("succeq", new Sym(0x2AB0, MathClass.REL)); // ⪰
        m.put("precsim", new Sym(0x227E, MathClass.REL)); // ≾
        m.put("succsim", new Sym(0x227F, MathClass.REL)); // ≿
        m.put("precapprox", new Sym(0x2AB7, MathClass.REL)); // ⪷
        m.put("succapprox", new Sym(0x2AB8, MathClass.REL)); // ⪸
        m.put("ll", new Sym(0x226A, MathClass.REL));     // ≪
        m.put("gg", new Sym(0x226B, MathClass.REL));     // ≫
        m.put("lll", new Sym(0x22D8, MathClass.REL));    // ⋘
        m.put("ggg", new Sym(0x22D9, MathClass.REL));    // ⋙
        m.put("lessgtr", new Sym(0x2276, MathClass.REL)); // ≶
        m.put("gtrless", new Sym(0x2277, MathClass.REL)); // ≷
        m.put("lesseqgtr", new Sym(0x22DA, MathClass.REL)); // ⋚
        m.put("gtreqless", new Sym(0x22DB, MathClass.REL)); // ⋛
        m.put("lesssim", new Sym(0x2272, MathClass.REL)); // ≲
        m.put("gtrsim", new Sym(0x2273, MathClass.REL));  // ≳
        m.put("lessapprox", new Sym(0x2A85, MathClass.REL)); // ⪅
        m.put("gtrapprox", new Sym(0x2A86, MathClass.REL)); // ⪆
        m.put("lessdot", new Sym(0x22D6, MathClass.REL)); // ⋖
        m.put("gtrdot", new Sym(0x22D7, MathClass.REL));  // ⋗
        m.put("subset", new Sym(0x2282, MathClass.REL)); // ⊂
        m.put("supset", new Sym(0x2283, MathClass.REL)); // ⊃
        m.put("subseteq", new Sym(0x2286, MathClass.REL)); // ⊆
        m.put("supseteq", new Sym(0x2287, MathClass.REL)); // ⊇
        m.put("subsetneq", new Sym(0x228A, MathClass.REL)); // ⊊
        m.put("supsetneq", new Sym(0x228B, MathClass.REL)); // ⊋
        m.put("sqsubset", new Sym(0x228F, MathClass.REL)); // ⊏
        m.put("sqsupset", new Sym(0x2290, MathClass.REL)); // ⊐
        m.put("sqsubseteq", new Sym(0x2291, MathClass.REL)); // ⊑
        m.put("sqsupseteq", new Sym(0x2292, MathClass.REL)); // ⊒
        m.put("in", new Sym(0x2208, MathClass.REL));     // ∈
        m.put("ni", new Sym(0x220B, MathClass.REL));     // ∋
        m.put("owns", new Sym(0x220B, MathClass.REL));   // ∋ alias
        m.put("notin", new Sym(0x2209, MathClass.REL));  // ∉
        m.put("vdash", new Sym(0x22A2, MathClass.REL));  // ⊢
        m.put("dashv", new Sym(0x22A3, MathClass.REL));  // ⊣
        m.put("models", new Sym(0x22A8, MathClass.REL)); // ⊨
        m.put("vDash", new Sym(0x22A8, MathClass.REL));  // ⊨
        m.put("Vdash", new Sym(0x22A9, MathClass.REL));  // ⊩
        m.put("Vvdash", new Sym(0x22AA, MathClass.REL)); // ⊪
        m.put("perp", new Sym(0x22A5, MathClass.REL));   // ⊥
        m.put("parallel", new Sym(0x2225, MathClass.REL)); // ∥
        m.put("mid", new Sym(0x2223, MathClass.REL));    // ∣
        m.put("smile", new Sym(0x2323, MathClass.REL));  // ⌣
        m.put("frown", new Sym(0x2322, MathClass.REL));  // ⌢
        m.put("bowtie", new Sym(0x22C8, MathClass.REL)); // ⋈
        m.put("Join", new Sym(0x22C8, MathClass.REL));   // ⋈ alias
        m.put("multimap", new Sym(0x22B8, MathClass.REL)); // ⊸
        m.put("pitchfork", new Sym(0x22D4, MathClass.REL)); // ⋔
        m.put("between", new Sym(0x226C, MathClass.REL)); // ≬
        m.put("therefore", new Sym(0x2234, MathClass.REL)); // ∴
        m.put("because", new Sym(0x2235, MathClass.REL));   // ∵
        m.put("vartriangleleft", new Sym(0x22B2, MathClass.REL));  // ⊲
        m.put("vartriangleright", new Sym(0x22B3, MathClass.REL)); // ⊳
        m.put("trianglelefteq", new Sym(0x22B4, MathClass.REL));   // ⊴
        m.put("trianglerighteq", new Sym(0x22B5, MathClass.REL));  // ⊵
        m.put("coloneqq", new Sym(0x2254, MathClass.REL)); // ≔

        // -- Negation relations (precomposed) --------------------------------
        m.put("nleq", new Sym(0x2270, MathClass.REL));   // ≰
        m.put("ngeq", new Sym(0x2271, MathClass.REL));   // ≱
        m.put("nless", new Sym(0x226E, MathClass.REL));  // ≮
        m.put("ngtr", new Sym(0x226F, MathClass.REL));   // ≯
        m.put("nsim", new Sym(0x2241, MathClass.REL));   // ≁
        m.put("ncong", new Sym(0x2247, MathClass.REL));  // ≇
        m.put("nmid", new Sym(0x2224, MathClass.REL));   // ∤
        m.put("nparallel", new Sym(0x2226, MathClass.REL)); // ∦
        m.put("nprec", new Sym(0x2280, MathClass.REL));  // ⊀
        m.put("nsucc", new Sym(0x2281, MathClass.REL));  // ⊁
        m.put("nsubseteq", new Sym(0x2288, MathClass.REL)); // ⊈
        m.put("nsupseteq", new Sym(0x2289, MathClass.REL)); // ⊉
        m.put("nvdash", new Sym(0x22AC, MathClass.REL)); // ⊬
        m.put("nvDash", new Sym(0x22AD, MathClass.REL)); // ⊭
        m.put("nVdash", new Sym(0x22AE, MathClass.REL)); // ⊮
        m.put("nVDash", new Sym(0x22AF, MathClass.REL)); // ⊯
        m.put("ntriangleleft", new Sym(0x22EA, MathClass.REL));  // ⋪
        m.put("ntriangleright", new Sym(0x22EB, MathClass.REL)); // ⋫
        m.put("ntrianglelefteq", new Sym(0x22EC, MathClass.REL)); // ⋬
        m.put("ntrianglerighteq", new Sym(0x22ED, MathClass.REL)); // ⋭

        // -- Arrows (MathClass.REL) ------------------------------------------
        m.put("leftarrow", new Sym(0x2190, MathClass.REL));  // ←
        m.put("gets", new Sym(0x2190, MathClass.REL));       // ← alias
        m.put("rightarrow", new Sym(0x2192, MathClass.REL)); // →
        m.put("to", new Sym(0x2192, MathClass.REL));         // → alias
        m.put("leftrightarrow", new Sym(0x2194, MathClass.REL)); // ↔
        m.put("Leftarrow", new Sym(0x21D0, MathClass.REL));  // ⇐
        m.put("Rightarrow", new Sym(0x21D2, MathClass.REL)); // ⇒
        m.put("Leftrightarrow", new Sym(0x21D4, MathClass.REL)); // ⇔
        m.put("uparrow", new Sym(0x2191, MathClass.REL));    // ↑
        m.put("downarrow", new Sym(0x2193, MathClass.REL));  // ↓
        m.put("updownarrow", new Sym(0x2195, MathClass.REL)); // ↕
        m.put("Uparrow", new Sym(0x21D1, MathClass.REL));    // ⇑
        m.put("Downarrow", new Sym(0x21D3, MathClass.REL));  // ⇓
        m.put("Updownarrow", new Sym(0x21D5, MathClass.REL)); // ⇕
        m.put("mapsto", new Sym(0x21A6, MathClass.REL));     // ↦
        m.put("longmapsto", new Sym(0x27FC, MathClass.REL)); // ⟼
        m.put("hookleftarrow", new Sym(0x21A9, MathClass.REL));  // ↩
        m.put("hookrightarrow", new Sym(0x21AA, MathClass.REL)); // ↪
        m.put("leftharpoonup", new Sym(0x21BC, MathClass.REL));  // ↼
        m.put("leftharpoondown", new Sym(0x21BD, MathClass.REL)); // ↽
        m.put("rightharpoonup", new Sym(0x21C0, MathClass.REL)); // ⇀
        m.put("rightharpoondown", new Sym(0x21C1, MathClass.REL)); // ⇁
        m.put("upharpoonleft", new Sym(0x21BF, MathClass.REL));  // ↿
        m.put("upharpoonright", new Sym(0x21BE, MathClass.REL)); // ↾
        m.put("downharpoonleft", new Sym(0x21C3, MathClass.REL)); // ⇃
        m.put("downharpoonright", new Sym(0x21C2, MathClass.REL)); // ⇂
        m.put("rightleftharpoons", new Sym(0x21CC, MathClass.REL)); // ⇌
        m.put("leftrightharpoons", new Sym(0x21CB, MathClass.REL)); // ⇋
        m.put("longleftarrow", new Sym(0x27F5, MathClass.REL));  // ⟵
        m.put("longrightarrow", new Sym(0x27F6, MathClass.REL)); // ⟶
        m.put("longleftrightarrow", new Sym(0x27F7, MathClass.REL)); // ⟷
        m.put("Longleftarrow", new Sym(0x27F8, MathClass.REL));  // ⟸
        m.put("Longrightarrow", new Sym(0x27F9, MathClass.REL)); // ⟹
        m.put("Longleftrightarrow", new Sym(0x27FA, MathClass.REL)); // ⟺
        m.put("nearrow", new Sym(0x2197, MathClass.REL));    // ↗
        m.put("searrow", new Sym(0x2198, MathClass.REL));    // ↘
        m.put("swarrow", new Sym(0x2199, MathClass.REL));    // ↙
        m.put("nwarrow", new Sym(0x2196, MathClass.REL));    // ↖
        m.put("leadsto", new Sym(0x21DD, MathClass.REL));    // ⇝
        m.put("rightsquigarrow", new Sym(0x21DD, MathClass.REL)); // ⇝
        m.put("leftrightsquigarrow", new Sym(0x21AD, MathClass.REL)); // ↭
        m.put("twoheadleftarrow", new Sym(0x219E, MathClass.REL));  // ↞
        m.put("twoheadrightarrow", new Sym(0x21A0, MathClass.REL)); // ↠
        m.put("leftarrowtail", new Sym(0x21A2, MathClass.REL));  // ↢
        m.put("rightarrowtail", new Sym(0x21A3, MathClass.REL)); // ↣
        m.put("looparrowleft", new Sym(0x21AB, MathClass.REL));  // ↫
        m.put("looparrowright", new Sym(0x21AC, MathClass.REL)); // ↬
        m.put("curvearrowleft", new Sym(0x21B6, MathClass.REL)); // ↶
        m.put("curvearrowright", new Sym(0x21B7, MathClass.REL)); // ↷
        m.put("circlearrowleft", new Sym(0x21BA, MathClass.REL));  // ↺
        m.put("circlearrowright", new Sym(0x21BB, MathClass.REL)); // ↻
        m.put("Lsh", new Sym(0x21B0, MathClass.REL));        // ↰
        m.put("Rsh", new Sym(0x21B1, MathClass.REL));        // ↱
        m.put("leftleftarrows", new Sym(0x21C7, MathClass.REL));   // ⇇
        m.put("rightrightarrows", new Sym(0x21C9, MathClass.REL)); // ⇉
        m.put("leftrightarrows", new Sym(0x21C6, MathClass.REL));  // ⇆
        m.put("rightleftarrows", new Sym(0x21C4, MathClass.REL));  // ⇄
        m.put("upuparrows", new Sym(0x21C8, MathClass.REL));       // ⇈
        m.put("downdownarrows", new Sym(0x21CA, MathClass.REL));   // ⇊
        m.put("Lleftarrow", new Sym(0x21DA, MathClass.REL)); // ⇚
        m.put("Rrightarrow", new Sym(0x21DB, MathClass.REL)); // ⇛
        // Negated arrows.
        m.put("nleftarrow", new Sym(0x219A, MathClass.REL));  // ↚
        m.put("nrightarrow", new Sym(0x219B, MathClass.REL)); // ↛
        m.put("nleftrightarrow", new Sym(0x21AE, MathClass.REL)); // ↮
        m.put("nLeftarrow", new Sym(0x21CD, MathClass.REL));  // ⇍
        m.put("nRightarrow", new Sym(0x21CF, MathClass.REL)); // ⇏
        m.put("nLeftrightarrow", new Sym(0x21CE, MathClass.REL)); // ⇎

        // -- Ordinary symbols (MathClass.ORD) --------------------------------
        m.put("infty", new Sym(0x221E, MathClass.ORD));   // ∞
        m.put("partial", new Sym(0x2202, MathClass.ORD)); // ∂
        m.put("nabla", new Sym(0x2207, MathClass.ORD));   // ∇
        m.put("hbar", new Sym(0x210F, MathClass.ORD));    // ℏ
        m.put("hslash", new Sym(0x210F, MathClass.ORD));  // ℏ
        m.put("ell", new Sym(0x2113, MathClass.ORD));     // ℓ
        m.put("Re", new Sym(0x211C, MathClass.ORD));      // ℜ
        m.put("Im", new Sym(0x2111, MathClass.ORD));      // ℑ
        m.put("wp", new Sym(0x2118, MathClass.ORD));      // ℘
        m.put("aleph", new Sym(0x2135, MathClass.ORD));   // ℵ
        m.put("beth", new Sym(0x2136, MathClass.ORD));    // ℶ
        m.put("gimel", new Sym(0x2137, MathClass.ORD));   // ℷ
        m.put("daleth", new Sym(0x2138, MathClass.ORD));  // ℸ
        m.put("forall", new Sym(0x2200, MathClass.ORD));  // ∀
        m.put("exists", new Sym(0x2203, MathClass.ORD));  // ∃
        m.put("nexists", new Sym(0x2204, MathClass.ORD)); // ∄
        m.put("emptyset", new Sym(0x2205, MathClass.ORD)); // ∅
        m.put("varnothing", new Sym(0x2205, MathClass.ORD)); // ∅
        m.put("complement", new Sym(0x2201, MathClass.ORD)); // ∁
        m.put("angle", new Sym(0x2220, MathClass.ORD));   // ∠
        m.put("measuredangle", new Sym(0x2221, MathClass.ORD)); // ∡
        m.put("sphericalangle", new Sym(0x2222, MathClass.ORD)); // ∢
        m.put("triangle", new Sym(0x25B3, MathClass.ORD)); // △
        m.put("triangledown", new Sym(0x25BD, MathClass.ORD)); // ▽
        m.put("square", new Sym(0x25A1, MathClass.ORD));  // □
        m.put("blacksquare", new Sym(0x25A0, MathClass.ORD)); // ■
        m.put("lozenge", new Sym(0x25CA, MathClass.ORD)); // ◊
        m.put("blacklozenge", new Sym(0x29EB, MathClass.ORD)); // ⧫
        m.put("bigstar", new Sym(0x2605, MathClass.ORD)); // ★
        m.put("diamondsuit", new Sym(0x2662, MathClass.ORD)); // ♢
        m.put("heartsuit", new Sym(0x2661, MathClass.ORD)); // ♡
        m.put("spadesuit", new Sym(0x2660, MathClass.ORD)); // ♠
        m.put("clubsuit", new Sym(0x2663, MathClass.ORD)); // ♣
        m.put("flat", new Sym(0x266D, MathClass.ORD));    // ♭
        m.put("natural", new Sym(0x266E, MathClass.ORD)); // ♮
        m.put("sharp", new Sym(0x266F, MathClass.ORD));   // ♯
        m.put("neg", new Sym(0x00AC, MathClass.ORD));     // ¬
        m.put("lnot", new Sym(0x00AC, MathClass.ORD));    // ¬
        m.put("top", new Sym(0x22A4, MathClass.ORD));     // ⊤
        m.put("bot", new Sym(0x22A5, MathClass.ORD));     // ⊥
        m.put("surd", new Sym(0x221A, MathClass.ORD));    // √
        m.put("backslash", new Sym(0x2216, MathClass.ORD)); // ∖
        m.put("prime", new Sym(0x2032, MathClass.ORD));   // ′
        m.put("mho", new Sym(0x2127, MathClass.ORD));     // ℧
        m.put("Finv", new Sym(0x2132, MathClass.ORD));    // Ⅎ
        m.put("Game", new Sym(0x2141, MathClass.ORD));    // ⅁
        m.put("eth", new Sym(0x00F0, MathClass.ORD));     // ð
        m.put("circledR", new Sym(0x00AE, MathClass.ORD)); // ®
        m.put("circledS", new Sym(0x24C8, MathClass.ORD)); // Ⓢ
        m.put("checkmark", new Sym(0x2713, MathClass.ORD)); // ✓
        m.put("maltese", new Sym(0x2720, MathClass.ORD)); // ✠
        m.put("imath", new Sym(0x0131, MathClass.ORD));   // ı
        m.put("jmath", new Sym(0x0237, MathClass.ORD));   // ȷ

        // -- Dots ------------------------------------------------------------
        m.put("cdots", new Sym(0x22EF, MathClass.INNER)); // ⋯
        m.put("ldots", new Sym(0x2026, MathClass.INNER)); // …
        m.put("dots", new Sym(0x2026, MathClass.INNER));  // … alias
        m.put("vdots", new Sym(0x22EE, MathClass.ORD));   // ⋮
        m.put("ddots", new Sym(0x22F1, MathClass.ORD));   // ⋱

        // -- Punctuation -----------------------------------------------------
        m.put("colon", new Sym(0x003A, MathClass.PUNCT)); // : (punctuation spacing)

        // -- Delimiters usable as ordinary symbols (outside \left..\right) ---
        m.put("{", new Sym('{', MathClass.OPEN));
        m.put("}", new Sym('}', MathClass.CLOSE));
        m.put("|", new Sym('|', MathClass.ORD));
        m.put("Vert", new Sym(0x2016, MathClass.ORD));    // ‖
        m.put("langle", new Sym(0x27E8, MathClass.OPEN)); // ⟨
        m.put("rangle", new Sym(0x27E9, MathClass.CLOSE)); // ⟩
        m.put("lfloor", new Sym(0x230A, MathClass.OPEN)); // ⌊
        m.put("rfloor", new Sym(0x230B, MathClass.CLOSE)); // ⌋
        m.put("lceil", new Sym(0x2308, MathClass.OPEN));  // ⌈
        m.put("rceil", new Sym(0x2309, MathClass.CLOSE)); // ⌉
        m.put("ulcorner", new Sym(0x231C, MathClass.OPEN)); // ⌜
        m.put("urcorner", new Sym(0x231D, MathClass.CLOSE)); // ⌝
        m.put("llcorner", new Sym(0x231E, MathClass.OPEN)); // ⌞
        m.put("lrcorner", new Sym(0x231F, MathClass.CLOSE)); // ⌟
        m.put("lbrace", new Sym('{', MathClass.OPEN));
        m.put("rbrace", new Sym('}', MathClass.CLOSE));
        m.put("lbrack", new Sym('[', MathClass.OPEN));
        m.put("rbrack", new Sym(']', MathClass.CLOSE));

        return Map.copyOf(m);
    }


    /**
     * The text-family commands captured whole at lex time (so their inter-word
     * spaces survive math mode's whitespace stripping): {@code \text} and its
     * shape variants, plus the math-mode upright {@code \mathrm}.
     */
    static final Map<String, TextStyle> TEXT_COMMANDS = Map.of(
        "text", TextStyle.ROMAN,
        "textrm", TextStyle.ROMAN,
        "mathrm", TextStyle.ROMAN,
        "textbf", TextStyle.BOLD,
        "textit", TextStyle.ITALIC,
        "texttt", TextStyle.MONO);

    /** The fixed properties of a supported environment (delimiters + layout kind). */
    record EnvSpec(int leftDelim, int rightDelim, MatrixKind kind, ColumnAlign uniform) {
    }

    static final int NO_DELIM = Fenced.NULL_DELIMITER;

    /** The supported grid environments and their enclosing delimiters. */
    static final Map<String, EnvSpec> ENVIRONMENTS = Map.ofEntries(
        Map.entry("matrix", new EnvSpec(NO_DELIM, NO_DELIM, MatrixKind.MATRIX, ColumnAlign.CENTER)),
        Map.entry("pmatrix", new EnvSpec('(', ')', MatrixKind.MATRIX, ColumnAlign.CENTER)),
        Map.entry("bmatrix", new EnvSpec('[', ']', MatrixKind.MATRIX, ColumnAlign.CENTER)),
        Map.entry("Bmatrix", new EnvSpec('{', '}', MatrixKind.MATRIX, ColumnAlign.CENTER)),
        Map.entry("vmatrix", new EnvSpec('|', '|', MatrixKind.MATRIX, ColumnAlign.CENTER)),
        Map.entry("Vmatrix", new EnvSpec(0x2016, 0x2016, MatrixKind.MATRIX, ColumnAlign.CENTER)),
        Map.entry("smallmatrix", new EnvSpec(NO_DELIM, NO_DELIM, MatrixKind.SMALL, ColumnAlign.CENTER)),
        Map.entry("array", new EnvSpec(NO_DELIM, NO_DELIM, MatrixKind.ARRAY, ColumnAlign.CENTER)),
        Map.entry("cases", new EnvSpec('{', NO_DELIM, MatrixKind.CASES, ColumnAlign.LEFT)));
}
