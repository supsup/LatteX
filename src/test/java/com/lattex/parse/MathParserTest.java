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
            case Fraction(var num, var den) -> "Frac(" + pp(num) + "," + pp(den) + ")";
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
            case Spacing(double mu) -> "Sp(" + mu + ")";
            case Accent(var cmd, var base, var cp, var stretchy, var under) -> {
                String kind = cp == Accent.RULE ? (under ? "under" : "over") : sym(cp);
                yield "Acc(" + cmd + "[" + kind + (stretchy ? ",wide" : "") + "]," + pp(base) + ")";
            }
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
}
