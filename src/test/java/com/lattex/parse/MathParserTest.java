package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.Fenced;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.LimitsMode;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Phantom;
import com.lattex.parse.MathNode.Radical;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.SupSub;
import org.junit.jupiter.api.Test;

/**
 * Parser tests: representative expressions parse to the expected trees (checked
 * both structurally and via a canonical {@link #pp pretty-printer}), and
 * malformed inputs fail loudly with {@link MathSyntaxException}.
 */
class MathParserTest {

    // ------------------------------------------------------------------
    // Canonical pretty-printer — an EXHAUSTIVE switch over MathNode with NO
    // default branch. If a new node kind is added to the sealed interface and
    // not handled here, this stops compiling (which is the point).
    // ------------------------------------------------------------------
    static String pp(MathNode node) {
        return switch (node) {
            case Atom(int cp, MathClass cls) -> "A(" + sym(cp) + "," + cls + ")";
            case MathNode.MiddleDelim(int cp) -> "MID(" + sym(cp) + ")";
            case MathList(var items) -> {
                StringBuilder sb = new StringBuilder("L(");
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(pp(items.get(i)));
                }
                yield sb.append(')').toString();
            }
            case SupSub(var base, var sup, var sub) -> {
                StringBuilder sb = new StringBuilder("SS(").append(pp(base));
                if (sup != null) {
                    sb.append(",^").append(pp(sup));
                }
                if (sub != null) {
                    sb.append(",_").append(pp(sub));
                }
                yield sb.append(')').toString();
            }
            case Fraction f -> {
                String tag = f.hasRule() ? "Frac" : "Binom";
                String sty = f.fractionStyle() == MathNode.FractionStyle.INHERIT
                    ? "" : "[" + f.fractionStyle() + "]";
                yield tag + sty + "(" + pp(f.numerator()) + "," + pp(f.denominator()) + ")";
            }
            case Radical(var rad, var idx) -> {
                String prefix = idx == null ? "Sqrt({" : "Sqrt([" + pp(idx) + "]{";
                yield prefix + pp(rad) + "})";
            }
            case BigOperator(var op, var lower, var upper, LimitsMode mode) -> {
                StringBuilder sb = new StringBuilder("Big(").append(pp(op));
                if (lower != null) {
                    sb.append(",_").append(pp(lower));
                }
                if (upper != null) {
                    sb.append(",^").append(pp(upper));
                }
                yield sb.append(',').append(mode).append(')').toString();
            }
            case Fenced(int left, var body, int right) ->
                "Fen(" + delim(left) + " " + pp(body) + " " + delim(right) + ")";
            case MathNode.SizedDelim sd ->
                "Big" + sd.sizeLevel() + "[" + sd.mathClass() + "](" + delim(sd.delimCp()) + ")";
            case Spacing(double mu) -> "Sp(" + mu + ")";
            case Phantom(var content, var keepW, var keepV) -> {
                String kind = keepW && keepV ? "phantom" : keepW ? "hphantom" : "vphantom";
                yield "Ph[" + kind + "](" + pp(content) + ")";
            }
            case Accent(var cmd, var base, var cp, var stretchy, var under) -> {
                String kind = cp == Accent.RULE ? (under ? "under" : "over") : sym(cp);
                yield "Acc(" + cmd + "[" + kind + (stretchy ? ",wide" : "") + "]," + pp(base) + ")";
            }
            case MathNode.Colored(var body, var color) ->
                "Col[" + color.svgValue() + "](" + pp(body) + ")";
            case MathNode.Boxed(var body) -> "Box(" + pp(body) + ")";
            case MathNode.Tagged(var body, var label) ->
                "Tag(" + pp(body) + "|" + pp(label) + ")";
            case MathNode.OperatorName(var name, var takesLimits) ->
                "Op(" + name + (takesLimits ? ",lim" : "") + ")";
            case MathNode.TextRun(var text, var style) ->
                "Txt[" + style + "](" + text + ")";
            case MathNode.Matrix m -> {
                StringBuilder sb = new StringBuilder("Mat[").append(m.kind());
                if (m.hasDelimiters()) {
                    sb.append(',').append(delim(m.leftDelim())).append(delim(m.rightDelim()));
                }
                sb.append("](");
                for (int r = 0; r < m.rows().size(); r++) {
                    if (r > 0) {
                        sb.append("\\\\");
                    }
                    for (int col = 0; col < m.columnCount(); col++) {
                        if (col > 0) {
                            sb.append('&');
                        }
                        sb.append(pp(m.rows().get(r).get(col)));
                    }
                }
                yield sb.append(')').toString();
            }
            case MathNode.Stack st -> {
                StringBuilder sb = new StringBuilder("Stk[").append(st.kind()).append("](")
                    .append(pp(st.base()));
                if (st.above() != null) {
                    sb.append(",^").append(pp(st.above()));
                }
                if (st.below() != null) {
                    sb.append(",_").append(pp(st.below()));
                }
                yield sb.append(')').toString();
            }
            case MathNode.XArrow xa -> {
                StringBuilder sb = new StringBuilder("XArr[")
                    .append(xa.kind().ppTag()).append("](^").append(pp(xa.above()));
                if (xa.below() != null) {
                    sb.append(",_").append(pp(xa.below()));
                }
                yield sb.append(')').toString();
            }
            case MathNode.CdArrow cd -> {
                StringBuilder sb = new StringBuilder("Cd[").append(cd.kind().ppTag()).append("](");
                if (cd.labelA() != null) {
                    sb.append("A:").append(pp(cd.labelA()));
                }
                if (cd.labelB() != null) {
                    sb.append(cd.labelA() == null ? "" : ",").append("B:").append(pp(cd.labelB()));
                }
                yield sb.append(')').toString();
            }
            case MathNode.StyledMath sm -> "Lx(" + pp(sm.body()) + ")";
            case MathNode.StyleSwitch sw -> "Style(" + sw.level() + " " + pp(sw.body()) + ")";
        };
    }

    private static String sym(int cp) {
        if (cp >= 0x21 && cp <= 0x7E) {
            return String.valueOf((char) cp);
        }
        return String.format("U+%04X", cp);
    }

    private static String delim(int cp) {
        return cp == Fenced.NULL_DELIMITER ? "." : sym(cp);
    }

    // ------------------------------------------------------------------
    // Representative expressions -> expected canonical trees.
    // ------------------------------------------------------------------

    @Test
    void superscript() {
        assertEquals("SS(A(x,ORD),^A(2,ORD))", pp(MathParser.parse("x^2")));
    }

    @Test
    void subscript() {
        assertEquals("SS(A(a,ORD),_A(i,ORD))", pp(MathParser.parse("a_i")));
    }

    @Test
    void supAndSubEitherOrder() {
        // x^a_b and x_b^a describe the same node.
        assertEquals("SS(A(x,ORD),^A(a,ORD),_A(b,ORD))", pp(MathParser.parse("x^a_b")));
        assertEquals("SS(A(x,ORD),^A(a,ORD),_A(b,ORD))", pp(MathParser.parse("x_b^a")));
    }

    @Test
    void primeIsRaisedSuperscript() {
        // Math-mode ' is TeX's ^{\prime}: a superscript holding the prime glyph
        // (U+2032 ′), NOT a baseline apostrophe.
        assertEquals("SS(A(f,ORD),^A(U+2032,ORD))", pp(MathParser.parse("f'")));
    }

    @Test
    void primesStackInSourceOrder() {
        // A run of primes accumulates into ONE superscript, in source order.
        assertEquals("SS(A(f,ORD),^L(A(U+2032,ORD) A(U+2032,ORD)))",
            pp(MathParser.parse("f''")));
    }

    @Test
    void primeThenExplicitSuperscriptMerge() {
        // f'^2 : prime and the explicit superscript merge, prime first.
        assertEquals("SS(A(f,ORD),^L(A(U+2032,ORD) A(2,ORD)))",
            pp(MathParser.parse("f'^2")));
    }

    @Test
    void explicitSuperscriptThenPrimeMerge() {
        // f^{2}' : explicit superscript then prime, source order — NOT a double
        // superscript error.
        assertEquals("SS(A(f,ORD),^L(A(2,ORD) A(U+2032,ORD)))",
            pp(MathParser.parse("f^{2}'")));
    }

    @Test
    void primeWithSubscript() {
        // f'_2 : prime is the superscript, _2 the subscript.
        assertEquals("SS(A(f,ORD),^A(U+2032,ORD),_A(2,ORD))",
            pp(MathParser.parse("f'_2")));
    }

    @Test
    void twoExplicitSuperscriptsStillThrow() {
        // Primes merge with one ^, but two explicit carets still error.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("f^2^3"));
    }

    @Test
    void fraction() {
        assertEquals("Frac(L(A(a,ORD) A(+,BIN) A(b,ORD)),A(c,ORD))",
            pp(MathParser.parse("\\frac{a+b}{c}")));
    }

    @Test
    void sqrt() {
        assertEquals("Sqrt({L(SS(A(x,ORD),^A(2,ORD)) A(+,BIN) A(1,ORD))})",
            pp(MathParser.parse("\\sqrt{x^2+1}")));
    }

    @Test
    void sqrtWithIndex() {
        assertEquals("Sqrt([A(3,ORD)]{A(x,ORD)})", pp(MathParser.parse("\\sqrt[3]{x}")));
    }

    @Test
    void narrowAccent() {
        assertEquals("Acc(hat[U+0302],A(a,ORD))", pp(MathParser.parse("\\hat{a}")));
        // Brace-optional single-token argument, like LaTeX.
        assertEquals("Acc(vec[U+20D7],A(v,ORD))", pp(MathParser.parse("\\vec v")));
    }

    @Test
    void wideAndRuleAccents() {
        assertEquals("Acc(widehat[U+0302,wide],L(A(A,ORD) A(B,ORD)))",
            pp(MathParser.parse("\\widehat{AB}")));
        assertEquals("Acc(overline[over],A(a,ORD))", pp(MathParser.parse("\\overline{a}")));
        assertEquals("Acc(underline[under],A(a,ORD))", pp(MathParser.parse("\\underline{a}")));
    }

    @Test
    void accentTakesScripts() {
        // An accented nucleus can still carry a superscript.
        assertEquals("SS(Acc(hat[U+0302],A(a,ORD)),^A(2,ORD))",
            pp(MathParser.parse("\\hat{a}^2")));
    }

    @Test
    void danglingAccentFailsCleanly() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\hat"));
    }

    @Test
    void sumWithLimits() {
        assertEquals(
            "L(Big(A(U+2211,OP),_L(A(i,ORD) A(=,REL) A(1,ORD)),^A(n,ORD),DEFAULT) A(i,ORD))",
            pp(MathParser.parse("\\sum_{i=1}^{n} i")));
    }

    @Test
    void integralWithSpacing() {
        assertEquals(
            "L(Big(A(U+222B,OP),_A(0,ORD),^A(U+221E,ORD),DEFAULT) "
                + "A(f,ORD) A((,OPEN) A(x,ORD) A(),CLOSE) Sp(3.0) A(d,ORD) A(x,ORD))",
            pp(MathParser.parse("\\int_0^\\infty f(x)\\,dx")));
    }

    @Test
    void fencedFraction() {
        assertEquals("Fen(( Frac(A(a,ORD),A(b,ORD)) ))",
            pp(MathParser.parse("\\left(\\frac{a}{b}\\right)")));
    }

    @Test
    void singleTokenFracAndSqrtArguments() {
        // \frac1x ≡ \frac{1}{x}, and a single \command token is one argument too.
        assertEquals("Frac(A(1,ORD),A(x,ORD))", pp(MathParser.parse("\\frac1x")));
        assertEquals("Frac(A(1,ORD),A(x,ORD))", pp(MathParser.parse("\\frac{1}{x}")));
        assertEquals("Frac(A(U+03B1,ORD),A(U+03B2,ORD))",
            pp(MathParser.parse("\\frac\\alpha\\beta")));
        // A braced first argument mixes with a single-token second: \frac{x^3}3.
        assertEquals("Frac(SS(A(x,ORD),^A(3,ORD)),A(3,ORD))",
            pp(MathParser.parse("\\frac{x^3}3")));
        // \sqrt2 ≡ \sqrt{2}; the optional [index] still reads its single token.
        assertEquals("Sqrt({A(2,ORD)})", pp(MathParser.parse("\\sqrt2")));
        assertEquals("Sqrt([A(3,ORD)]{A(8,ORD)})", pp(MathParser.parse("\\sqrt[3]8")));
    }

    @Test
    void binomIsRulelessFencedStack() {
        // \binom{n}{r}: a rule-less n-over-r stack wrapped in parentheses.
        MathNode n = MathParser.parse("\\binom{n}{r}");
        Fenced fen = assertInstanceOf(Fenced.class, n);
        assertEquals('(', fen.leftDelim());
        assertEquals(')', fen.rightDelim());
        Fraction stack = assertInstanceOf(Fraction.class, fen.body());
        assertTrue(!stack.hasRule(), "\\binom has no fraction rule");
        assertEquals(MathNode.FractionStyle.INHERIT, stack.fractionStyle());
        assertEquals("Fen(( Binom(A(n,ORD),A(r,ORD)) ))", pp(n));
        // \dbinom / \tbinom force the display / text math style.
        assertEquals("Fen(( Binom[DISPLAY](A(n,ORD),A(r,ORD)) ))",
            pp(MathParser.parse("\\dbinom{n}{r}")));
        assertEquals("Fen(( Binom[TEXT](A(n,ORD),A(r,ORD)) ))",
            pp(MathParser.parse("\\tbinom{n}{r}")));
        // Single-token binom arguments read too: \binom nr.
        assertEquals("Fen(( Binom(A(n,ORD),A(r,ORD)) ))", pp(MathParser.parse("\\binom nr")));
    }

    @Test
    void infixFractionOperatorsSplitTheirGroup() {
        // The TeX INFIX operators sit BETWEEN numerator and denominator and split
        // their enclosing group: `n` before, `k` after. \over is a ruled fraction
        // (== \frac), \atop is rule-less (no fence), and \choose/\brace/\brack are
        // rule-less fractions fenced by ( ) / { } / [ ] (== \binom with a delimiter).
        assertEquals("Frac(A(a,ORD),A(b,ORD))", pp(MathParser.parse("a \\over b")));
        assertEquals("Binom(A(n,ORD),A(k,ORD))", pp(MathParser.parse("{n \\atop k}")));

        // \choose -> ( ) fence around the rule-less stack, structurally verified.
        MathNode ch = MathParser.parse("{n \\choose k}");
        Fenced chf = assertInstanceOf(Fenced.class, ch);
        assertEquals('(', chf.leftDelim());
        assertEquals(')', chf.rightDelim());
        Fraction chStack = assertInstanceOf(Fraction.class, chf.body());
        assertTrue(!chStack.hasRule(), "\\choose has no fraction rule");
        assertEquals("Fen(( Binom(A(n,ORD),A(k,ORD)) ))", pp(ch));
        // \brace -> { }, \brack -> [ ].
        assertEquals("Fen({ Binom(A(n,ORD),A(k,ORD)) })", pp(MathParser.parse("{n \\brace k}")));
        assertEquals("Fen([ Binom(A(n,ORD),A(k,ORD)) ])", pp(MathParser.parse("{n \\brack k}")));

        // \over is IDENTICAL to \frac (ruled, inherited style).
        Fraction over = assertInstanceOf(Fraction.class, MathParser.parse("a \\over b"));
        assertTrue(over.hasRule(), "\\over keeps the fraction rule");
        assertEquals(MathNode.FractionStyle.INHERIT, over.fractionStyle());
    }

    @Test
    void infixDenominatorParsesScriptsAndNestsPerGroup() {
        // Script binding: the denominator is parsed with the ordinary component
        // parser, so `^2` binds to `b` INSIDE the denominator (b^2, not just b).
        assertEquals("Frac(A(a,ORD),SS(A(b,ORD),^A(2,ORD)))",
            pp(MathParser.parse("a \\over b^2")));
        // Nesting is by group: the inner {a \over b} splits only a/b; the outer
        // `+ c` is unaffected and stays at top level.
        assertEquals("L(Frac(A(a,ORD),A(b,ORD)) A(+,BIN) A(c,ORD))",
            pp(MathParser.parse("{a \\over b} + c")));
    }

    @Test
    void twoInfixOperatorsInOneGroupAreAmbiguous() {
        // TeX allows at most ONE infix fraction operator per group; a second one is
        // ambiguous and must fail loudly rather than silently mis-nest.
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x \\over y \\over z"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("{n \\brace k \\atop m}"));
    }

    @Test
    void cfracForcesDisplayStyle() {
        Fraction f = assertInstanceOf(Fraction.class, MathParser.parse("\\cfrac{1}{x}"));
        assertTrue(f.hasRule(), "\\cfrac keeps the fraction rule");
        assertEquals(MathNode.FractionStyle.DISPLAY, f.fractionStyle());
        assertEquals("Frac[DISPLAY](A(1,ORD),A(x,ORD))", pp(f));
        // The nested continued-fraction form parses (recursion of the \cfrac node).
        assertInstanceOf(MathList.class, MathParser.parse("x=a_0+\\cfrac{1}{a_1+\\cfrac{1}{a_2}}"));
    }

    @Test
    void dfracAndTfracForceTheirStyle() {
        // The ruled-fraction siblings of \dbinom/\tbinom: \dfrac pins DISPLAY, \tfrac
        // pins TEXT, regardless of surrounding style (a full-size fraction inline, or a
        // small one in display math). \dfrac == \cfrac's styling minus the continued framing.
        Fraction d = assertInstanceOf(Fraction.class, MathParser.parse("\\dfrac{1}{2}"));
        assertTrue(d.hasRule(), "\\dfrac keeps the fraction rule");
        assertEquals(MathNode.FractionStyle.DISPLAY, d.fractionStyle());
        assertEquals("Frac[DISPLAY](A(1,ORD),A(2,ORD))", pp(d));

        Fraction t = assertInstanceOf(Fraction.class, MathParser.parse("\\tfrac{1}{2}"));
        assertTrue(t.hasRule(), "\\tfrac keeps the fraction rule");
        assertEquals(MathNode.FractionStyle.TEXT, t.fractionStyle());
        assertEquals("Frac[TEXT](A(1,ORD),A(2,ORD))", pp(t));
    }

    @Test
    void textcolorPaintsItsBodyValidatingTheColor() {
        MathNode.Colored red = assertInstanceOf(MathNode.Colored.class,
            MathParser.parse("\\textcolor{red}{x}"));
        assertEquals("#ff0000", red.color().svgValue());
        assertEquals("Col[#ff0000](A(x,ORD))", pp(red));
        // named + hex both route through Color — the single validated fill boundary
        assertEquals("#0000ff", assertInstanceOf(MathNode.Colored.class,
            MathParser.parse("\\textcolor{blue}{y}")).color().svgValue());
        assertEquals("#ff8800", assertInstanceOf(MathNode.Colored.class,
            MathParser.parse("\\textcolor{#ff8800}{z}")).color().svgValue());
        // an unknown/malformed color is a clean parse error, never an emitted raw string
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\textcolor{bogus}{x}"));
    }

    @Test
    void colorSwitchRecoloursTheRestOfItsGroup() {
        // \\color{red} is a SWITCH (like \\displaystyle): it paints everything AFTER it to the
        // end of the enclosing group — unlike \\textcolor{red}{x} which paints only its argument.
        // The corpus case k={\\color{red}x}-2: inside the group, \\color{red} scopes exactly x.
        MathNode grouped = MathParser.parse("{\\color{red}x}");
        MathNode.Colored red = assertInstanceOf(MathNode.Colored.class, grouped);
        assertEquals("#ff0000", red.color().svgValue());
        assertEquals("Col[#ff0000](A(x,ORD))", pp(red));

        // At top level with no braces, it colours to the end of the stream.
        MathNode top = MathParser.parse("\\color{blue}x+y");
        MathNode.Colored blue = assertInstanceOf(MathNode.Colored.class, top);
        assertEquals("#0000ff", blue.color().svgValue());
        assertEquals("Col[#0000ff](L(A(x,ORD) A(+,BIN) A(y,ORD)))", pp(blue));

        // The switch STOPS at the group boundary: {\\color{red}a}b -> only a is red, b is bare.
        MathNode.MathList outer = assertInstanceOf(MathNode.MathList.class,
            MathParser.parse("{\\color{red}a}b"));
        assertEquals("L(Col[#ff0000](A(a,ORD)) A(b,ORD))", pp(outer));

        // A later \\color in the same group overrides for what follows it (nesting).
        assertEquals("Col[#ff0000](L(A(x,ORD) Col[#0000ff](A(y,ORD))))",
            pp(MathParser.parse("\\color{red}x\\color{blue}y")));

        // hex + named both validate through Color; a bogus colour fails loud (never a raw string).
        assertEquals("#ff8800", assertInstanceOf(MathNode.Colored.class,
            MathParser.parse("\\color{#ff8800}z")).color().svgValue());
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\color{bogus}x"));
    }

    @Test
    void tagHoistsAnEquationNumberToTheTopLevel() {
        MathNode.Tagged t = assertInstanceOf(MathNode.Tagged.class,
            MathParser.parse("x \\tag{1}"));
        assertEquals("A(x,ORD)", pp(t.body()));
        assertEquals("A(1,ORD)", pp(t.label()));
        assertEquals("Tag(A(x,ORD)|A(1,ORD))", pp(t));
        // \tag is equation-global — it hoists out wherever it appears in the stream
        assertInstanceOf(MathNode.Tagged.class, MathParser.parse("\\tag{1} x + y"));
        // two \tag on one equation, or a \tag with no { label } group, fail loudly
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("x \\tag{1} \\tag{2}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("x \\tag 1"));
    }

    @Test
    void operatorNameAllowsSpacingCommandsInItsArgument() {
        // arg\,max / lim\;sup: a spacing command in an operator name renders as one
        // literal space, not a parse error; \! (negative) collapses.
        assertEquals("Op(arg max,lim)", pp(MathParser.parse("\\operatorname*{arg\\,max}")));
        assertEquals("Op(lim sup)", pp(MathParser.parse("\\operatorname{lim\\;sup}")));
        assertEquals("Op(argmax)", pp(MathParser.parse("\\operatorname{arg\\!max}")));
        // a non-spacing command in the name is still a loud error (name is plain text)
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\operatorname{a\\alpha b}"));
    }

    @Test
    void oiintSurfaceIntegralParsesAsABigOperator() {
        // \oiint / \oiiint are big operators (surface/volume integrals), not Unknown command
        assertInstanceOf(MathNode.BigOperator.class, MathParser.parse("\\oiint_S"));
        assertInstanceOf(MathNode.BigOperator.class, MathParser.parse("\\oiiint_V"));
    }

    @Test
    void bigFamilyProducesFixedSizeDelimiters() {
        // \big/\Big/\bigg/\Bigg = size levels 1..4; l/r/m set the spacing class.
        assertEquals("Big1[ORD](|)", pp(MathParser.parse("\\big|")));
        assertEquals("Big2[OPEN](()", pp(MathParser.parse("\\Bigl(")));
        assertEquals("Big3[CLOSE]())", pp(MathParser.parse("\\biggr)")));
        assertEquals("Big4[REL](|)", pp(MathParser.parse("\\Biggm|")));
        // Name-collision guard: \bigcup is still a big OPERATOR, not a sized delimiter.
        assertInstanceOf(MathNode.BigOperator.class, MathParser.parse("\\bigcup_i"));
        // An unknown \big-ish suffix stays an Unknown command (not silently a delimiter).
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\bigx"));
    }

    @Test
    void styleSwitchWrapsTheRestOfItsGroup() {
        // {\displaystyle x y}: a single StyleSwitch(DISPLAY, ...) capturing BOTH x and y.
        MathNode.StyleSwitch sw = assertInstanceOf(MathNode.StyleSwitch.class,
            MathParser.parse("{\\displaystyle x y}"));
        assertEquals(MathNode.StyleLevel.DISPLAY, sw.level());
        assertInstanceOf(MathNode.MathList.class, sw.body()); // both x and y are captured

        // Bare, top-level switches carry their level.
        assertEquals(MathNode.StyleLevel.TEXT, assertInstanceOf(MathNode.StyleSwitch.class,
            MathParser.parse("\\textstyle x")).level());
        assertEquals(MathNode.StyleLevel.SCRIPT_SCRIPT, assertInstanceOf(MathNode.StyleSwitch.class,
            MathParser.parse("\\scriptscriptstyle z")).level());

        // Boundary containment: the switch stops at the brace — it does NOT swallow the
        // `a` before or the `c` after, so the whole thing is a plain list, not one switch.
        assertInstanceOf(MathNode.MathList.class, MathParser.parse("a {\\displaystyle b} c"));
    }

    @Test
    void greekAndRelations() {
        assertEquals(
            "L(A(U+03B1,ORD) A(+,BIN) A(U+03B2,ORD) A(U+2264,REL) A(U+03B3,ORD))",
            pp(MathParser.parse("\\alpha + \\beta \\leq \\gamma")));
    }

    // ------------------------------------------------------------------
    // Node-type + feature coverage.
    // ------------------------------------------------------------------

    @Test
    void limitsModes() {
        assertEquals("Big(A(U+2211,OP),^A(n,ORD),LIMITS)",
            pp(MathParser.parse("\\sum\\limits^n")));
        assertEquals("Big(A(U+2211,OP),^A(n,ORD),NOLIMITS)",
            pp(MathParser.parse("\\sum\\nolimits^n")));
    }

    @Test
    void nullDelimiters() {
        // \left. x \right| — a null opening delimiter (the "evaluated-at" bar).
        MathNode n = MathParser.parse("\\left. x \\right|");
        Fenced f = assertInstanceOf(Fenced.class, n);
        assertTrue(!f.hasLeft(), "\\left. is a null delimiter");
        assertEquals('|', f.rightDelim());
        assertEquals("Fen(. A(x,ORD) |)", pp(n));
    }

    @Test
    void atomCarriesMathClass() {
        Atom plus = assertInstanceOf(Atom.class, MathParser.parse("+"));
        assertEquals(MathClass.BIN, plus.mathClass());
        assertEquals('+', plus.codePoint());
    }

    @Test
    void bigOperatorNodeShape() {
        BigOperator bo = assertInstanceOf(BigOperator.class,
            MathParser.parse("\\prod_{k}^{m}"));
        assertEquals(0x220F, bo.op().codePoint());
        assertEquals(MathClass.OP, bo.op().mathClass());
        assertTrue(bo.lowerLimit().isPresent());
        assertTrue(bo.upperLimit().isPresent());
        assertEquals(LimitsMode.DEFAULT, bo.limitsMode());
    }

    @Test
    void multiIntegralsAreBigOperators() {
        // \iint / \iiint / \idotsint flow through the existing BigOperator node,
        // rendered with the precomposed STIX integral glyphs (no new node/layout).
        assertEquals(0x222C, assertInstanceOf(BigOperator.class,
            MathParser.parse("\\iint")).op().codePoint());
        assertEquals(0x222D, assertInstanceOf(BigOperator.class,
            MathParser.parse("\\iiint")).op().codePoint());
        assertEquals(0x2A0C, assertInstanceOf(BigOperator.class,
            MathParser.parse("\\idotsint")).op().codePoint());
        // They take limits like every other big operator.
        BigOperator withLimits = assertInstanceOf(BigOperator.class,
            MathParser.parse("\\iint_{D}"));
        assertEquals(MathClass.OP, withLimits.op().mathClass());
        assertTrue(withLimits.lowerLimit().isPresent());
    }

    @Test
    void bareOrdinaryDelimitersParseWithDelimiterAtomClasses() {
        // Delimiters usable as bare atoms outside \left..\right, with the atom
        // class TeX assigns them (openers OPEN, closers CLOSE).
        assertEquals(MathClass.OPEN, assertInstanceOf(Atom.class,
            MathParser.parse("\\langle")).mathClass());
        assertEquals(MathClass.CLOSE, assertInstanceOf(Atom.class,
            MathParser.parse("\\rangle")).mathClass());
        assertEquals(MathClass.OPEN, assertInstanceOf(Atom.class,
            MathParser.parse("\\lfloor")).mathClass());
        assertEquals(MathClass.CLOSE, assertInstanceOf(Atom.class,
            MathParser.parse("\\rfloor")).mathClass());
        assertEquals(MathClass.OPEN, assertInstanceOf(Atom.class,
            MathParser.parse("\\lceil")).mathClass());
        assertEquals(MathClass.CLOSE, assertInstanceOf(Atom.class,
            MathParser.parse("\\rceil")).mathClass());
        assertEquals(MathClass.OPEN, assertInstanceOf(Atom.class,
            MathParser.parse("\\ulcorner")).mathClass());
        assertEquals(MathClass.CLOSE, assertInstanceOf(Atom.class,
            MathParser.parse("\\urcorner")).mathClass());
        // \backslash is an ordinary symbol (∖).
        assertEquals(MathClass.ORD, assertInstanceOf(Atom.class,
            MathParser.parse("\\backslash")).mathClass());
    }

    @Test
    void radicalOptionalIndex() {
        Radical sq = assertInstanceOf(Radical.class, MathParser.parse("\\sqrt{x}"));
        assertTrue(sq.indexNode().isEmpty());
        Radical cube = assertInstanceOf(Radical.class, MathParser.parse("\\sqrt[3]{x}"));
        assertTrue(cube.indexNode().isPresent());
    }

    @Test
    void spacingNode() {
        assertInstanceOf(Spacing.class, MathParser.parse("\\,"));
        assertInstanceOf(Spacing.class, MathParser.parse("\\quad"));
    }

    @Test
    void spacingCommandWidths() {
        // Each command maps to its TeXbook mu-width (18mu = 1em).
        assertEquals(3.0, spacingMu("\\,"), "\\, is 3mu");
        assertEquals(3.0, spacingMu("\\thinspace"), "\\thinspace is 3mu");
        assertEquals(4.0, spacingMu("\\:"), "\\: is 4mu");
        assertEquals(4.0, spacingMu("\\>"), "\\> aliases \\: (4mu)");
        assertEquals(4.0, spacingMu("\\medspace"), "\\medspace is 4mu");
        assertEquals(5.0, spacingMu("\\;"), "\\; is 5mu");
        assertEquals(5.0, spacingMu("\\thickspace"), "\\thickspace is 5mu");
        assertEquals(-3.0, spacingMu("\\!"), "\\! is -3mu");
        assertEquals(-3.0, spacingMu("\\negthinspace"), "\\negthinspace is -3mu");
        assertEquals(-4.0, spacingMu("\\negmedspace"), "\\negmedspace is -4mu");
        assertEquals(-5.0, spacingMu("\\negthickspace"), "\\negthickspace is -5mu");
        assertEquals(18.0, spacingMu("\\quad"), "\\quad is 18mu (1em)");
        assertEquals(36.0, spacingMu("\\qquad"), "\\qquad is 36mu (2em)");
        assertEquals(9.0, spacingMu("\\enspace"), "\\enspace is 9mu (0.5em)");
        assertEquals(6.0, spacingMu("~"), "~ (tie) is an interword space");
    }

    private static double spacingMu(String latex) {
        return assertInstanceOf(Spacing.class, MathParser.parse(latex)).muWidth();
    }

    @Test
    void phantomFamilyParses() {
        assertEquals("Ph[phantom](A(x,ORD))", pp(MathParser.parse("\\phantom{x}")));
        assertEquals("Ph[hphantom](A(x,ORD))", pp(MathParser.parse("\\hphantom{x}")));
        assertEquals("Ph[vphantom](A(x,ORD))", pp(MathParser.parse("\\vphantom{x}")));
        // \mathstrut is a zero-width strut with the metrics of '('.
        Phantom strut = assertInstanceOf(Phantom.class, MathParser.parse("\\mathstrut"));
        assertTrue(!strut.keepWidth() && strut.keepVertical(), "\\mathstrut keeps only vertical extent");
        assertEquals("Ph[vphantom](A((,OPEN))", pp(strut));
    }

    @Test
    void emptyGroupIsEmptyList() {
        MathNode n = MathParser.parse("{}");
        MathList list = assertInstanceOf(MathList.class, n);
        assertTrue(list.items().isEmpty());
    }

    // ------------------------------------------------------------------
    // Malformed input must fail cleanly, naming the problem.
    // ------------------------------------------------------------------

    @Test
    void danglingSuperscript() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x^"));
        assertTrue(e.getMessage().toLowerCase().contains("dangling"), e.getMessage());
    }

    @Test
    void danglingSubscript() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("x_"));
    }

    @Test
    void incompleteFraction() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\frac{a}"));
    }

    @Test
    void unbalancedOpenBrace() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("{a"));
        assertTrue(e.getMessage().contains("}"), e.getMessage());
    }

    @Test
    void unbalancedCloseBrace() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("a}"));
    }

    @Test
    void unknownCommand() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\bogus"));
        assertTrue(e.getMessage().contains("\\bogus"), e.getMessage());
    }

    @Test
    void doubleSuperscript() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("x^y^z"));
    }

    @Test
    void unbalancedLeftRight() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\left( x"));
    }

    @Test
    void sqrtMissingArgument() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\sqrt"));
    }

    @Test
    void danglingBackslash() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("a\\"));
    }

    @Test
    void badDelimiter() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\left x \\right y"));
    }

    // ------------------------------------------------------------------
    // Stack mechanism: \\underbrace \overbrace \stackrel \overset \\underset
    // (a base with material above/below) and \substack (single-column grid).
    // ------------------------------------------------------------------

    @Test
    void oversetPutsAnnotationAbove() {
        // \overset{above}{base}: base keeps its class, annotation in ^ slot.
        assertEquals("Stk[OVERSET](A(=,REL),^A(a,ORD))",
            pp(MathParser.parse("\\overset{a}{=}")));
    }

    @Test
    void undersetPutsAnnotationBelow() {
        assertEquals("Stk[UNDERSET](A(y,ORD),_A(x,ORD))",
            pp(MathParser.parse("\\underset{x}{y}")));
    }

    @Test
    void stackrelIsAStackKind() {
        assertEquals("Stk[STACKREL](A(=,REL),^Txt[ROMAN](def))",
            pp(MathParser.parse("\\stackrel{\\text{def}}{=}")));
    }

    @Test
    void underbraceTakesSubscriptLabelAsUnderMark() {
        // The _ label is attached as an under-limit into the below slot.
        assertEquals("Stk[UNDERBRACE](L(A(a,ORD) A(b,ORD)),_A(n,ORD))",
            pp(MathParser.parse("\\underbrace{ab}_{n}")));
    }

    @Test
    void overbraceTakesSuperscriptLabelAsOverMark() {
        assertEquals("Stk[OVERBRACE](L(A(a,ORD) A(b,ORD)),^A(n,ORD))",
            pp(MathParser.parse("\\overbrace{ab}^{n}")));
    }

    @Test
    void bareUnderbraceHasNoLabel() {
        assertEquals("Stk[UNDERBRACE](A(x,ORD))",
            pp(MathParser.parse("\\underbrace{x}")));
    }

    @Test
    void substackIsASingleColumnCentredGrid() {
        assertEquals("Mat[SUBSTACK](A(a,ORD)\\\\A(b,ORD))",
            pp(MathParser.parse("\\substack{a\\\\b}")));
    }

    @Test
    void substackNeedsABraceArgument() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\substack a"));
    }

    @Test
    void overbraceRejectsDoubleLabel() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\overbrace{x}^{a}^{b}"));
    }

    // ---- L2: \middle (plan lattex-middle-evalbar) ----

    @Test
    void middleParsesInsideFencedGroupAsMiddleDelimNode() {
        MathNode n = MathParser.parse("P\\left(A=2\\middle|\\frac{A^2}{B}>4\\right)");
        // The Fenced body carries the MiddleDelim in place.
        boolean found = new Object() {
            boolean scan(MathNode node) {
                if (node instanceof MathNode.MiddleDelim) return true;
                if (node instanceof MathNode.MathList(var items)) {
                    for (MathNode i : items) if (scan(i)) return true;
                }
                if (node instanceof MathNode.Fenced(var l, var body, var r)) return scan(body);
                if (node instanceof MathNode.SupSub(var b, var sup, var sub)) {
                    return (b != null && scan(b)) || (sup != null && scan(sup)) || (sub != null && scan(sub));
                }
                return false;
            }
        }.scan(n);
        org.junit.jupiter.api.Assertions.assertTrue(found, "MiddleDelim node present in the fenced body");
    }

    @Test
    void middleOutsideFencedGroupStillFails() {
        // TeX's "extra \middle" error: outside \left..\right the command is illegal.
        org.junit.jupiter.api.Assertions.assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x\\middle|y"));
    }
}
