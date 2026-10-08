package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 636d214f: the next standard-LaTeX gaps the research corpus (746 preprints,
 * 137,880 display formulas, LatteX main cc8186a) still refused, each pinned by the
 * corpus formula that exposed it plus the property it must hold. Every corpus
 * source here is copied verbatim from that run's failures.tsv; each was red on
 * cc8186a. Layout-level geometry for the spanning/overlaid forms lives in
 * {@code StandardGapsLayoutTest}.
 */
class StandardGapsTest {

    private static void assertRenders(String latex) {
        String svg = LatteX.render(latex);
        assertTrue(svg.startsWith("<svg"), latex);
    }

    // ------------------------------------------------------------------
    // \vert — the plain bar as a symbol, not only as a delimiter
    // ------------------------------------------------------------------

    @Test
    void vertIsTheOrdinaryBarSymbol() {
        // Boundary-graph-deformations / Spacetime-Penrose / circulant-Hadamard.
        assertRenders("\\int_{\\Gamma_f\\vert_Q}e^{2Z}\\,dV_{\\Gamma_f}\\le C(N,Q).");
        assertRenders("c=\\tfrac12h\\vert_{Z=1},\\qquad d=\\tfrac12h\\vert_{Z=-1},"
            + "\\qquad g=\\tfrac12h\\vert_{Z=i}.");
        // \vert IS a typed |: the same tree as the bare character.
        assertEquals(MathParser.parse("h|_{Z=1}"), MathParser.parse("h\\vert_{Z=1}"));
        assertEquals(new Atom('|', MathClass.ORD), MathParser.parse("\\vert"));
    }

    @Test
    void theBarFamilyIsConsistentAsSymbolsAndAsDelimiters() {
        // Every spelling of the single and double bar is accepted bare, after
        // \left/\right, and after a sized-delimiter command, and each pair of
        // spellings of one glyph agrees on the glyph.
        record Bar(String name, int codePoint) { }
        List<Bar> bars = List.of(new Bar("vert", '|'), new Bar("lvert", '|'), new Bar("rvert", '|'),
            new Bar("Vert", 0x2016), new Bar("lVert", 0x2016), new Bar("rVert", 0x2016),
            new Bar("|", 0x2016));
        for (Bar bar : bars) {
            String cs = "\\" + bar.name();
            MathNode bare = MathParser.parse(cs);
            assertInstanceOf(Atom.class, bare, cs);
            assertEquals(bar.codePoint(), ((Atom) bare).codePoint(), cs);
            MathNode fenced = MathParser.parse("\\left" + cs + " x\\right" + cs);
            assertEquals(new MathNode.Fenced(bar.codePoint(), Atom.ord('x'), bar.codePoint()),
                fenced, cs + " as a \\left/\\right delimiter");
            assertRenders("\\bigl" + cs + " x\\bigr" + cs);
        }
    }

    // ------------------------------------------------------------------
    // \genfrac — amsmath's general fraction
    // ------------------------------------------------------------------

    @Test
    void genfracCorpusFormulasRender() {
        // A-counterexample-to-Sidorenkos-conjecture: the Gaussian binomial.
        assertRenders("\\genfrac{[}{]}{0pt}{}{n}{t}_q =\\prod_{i=0}^{t-1}\\frac{q^{n-i}-1}{q^{t-i}-1},"
            + " \\qquad L_N=\\prod_{j=1}^N(q^j+1),\\qquad L_0=1.");
        assertRenders("F_j=\\frac{q^{-b(D)}\\genfrac{[}{]}{0pt}{}{2r}{r}_q} {\\pi_{\\xi_1}\\pi_{\\xi_2}}"
            + " f_{\\xi_1}(Q) =(4+O(q^{-1}))f_{\\xi_1}(Q).");
        assertRenders("\\begin{align}L_N&=(1+O_N(q^{-1}))q^{b(N)}, &\\genfrac{[}{]}{0pt}{}{n}{t}_q"
            + "&=(1+O_n(q^{-1}))q^{t(n-t)}\\end{align}");
    }

    @Test
    void genfracIsTheBaseOfTheAmsmathFractionFamily() {
        // amsmath defines these six AS \genfrac calls; the trees must agree exactly.
        assertEquals(MathParser.parse("\\frac{a}{b}"), MathParser.parse("\\genfrac{}{}{}{}{a}{b}"));
        assertEquals(MathParser.parse("\\dfrac{a}{b}"), MathParser.parse("\\genfrac{}{}{}{0}{a}{b}"));
        assertEquals(MathParser.parse("\\tfrac{a}{b}"), MathParser.parse("\\genfrac{}{}{}{1}{a}{b}"));
        assertEquals(MathParser.parse("\\binom{n}{k}"), MathParser.parse("\\genfrac{(}{)}{0pt}{}{n}{k}"));
        assertEquals(MathParser.parse("\\dbinom{n}{k}"), MathParser.parse("\\genfrac(){0pt}{0}{n}{k}"));
        assertEquals(MathParser.parse("\\tbinom{n}{k}"), MathParser.parse("\\genfrac(){0pt}1nk"));
        // The corpus form: square brackets, no rule, inherited style.
        assertEquals(new MathNode.Fenced('[',
                new MathNode.Fraction(Atom.ord('n'), Atom.ord('t'), false, MathNode.FractionStyle.INHERIT),
                ']'),
            MathParser.parse("\\genfrac{[}{]}{0pt}{}{n}{t}"));
        // Control: an empty thickness keeps the rule, so the [0pt] form is not vacuous.
        assertNotEquals(MathParser.parse("\\genfrac{[}{]}{}{}{n}{t}"),
            MathParser.parse("\\genfrac{[}{]}{0pt}{}{n}{t}"));
        // One null side is a one-sided fence; \{ and \langle are delimiters as after \left.
        assertEquals(new MathNode.Fenced(MathNode.Fenced.NULL_DELIMITER,
                new MathNode.Fraction(Atom.ord('a'), Atom.ord('b'), true, MathNode.FractionStyle.INHERIT),
                0x27E9),
            MathParser.parse("\\genfrac{.}{\\rangle}{}{}{a}{b}"));
        // Script styles have no FractionStyle: the whole construction is style-switched,
        // as amsmath's \genfrac sets it.
        assertEquals(new MathNode.StyleSwitch(MathNode.StyleLevel.SCRIPT,
                new MathNode.Fraction(Atom.ord('a'), Atom.ord('b'), true, MathNode.FractionStyle.INHERIT)),
            MathParser.parse("\\genfrac{}{}{}{2}{a}{b}"));
    }

    @Test
    void genfracRefusesWhatItCannotDrawExactly() {
        // A non-zero explicit rule thickness has no LatteX model; it fails loud rather
        // than silently drawing the default rule.
        MathSyntaxException thick = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\genfrac{}{}{2pt}{}{a}{b}"));
        assertTrue(thick.getMessage().contains("thickness"), thick.getMessage());
        // TeX requires a unit on a dimension.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\genfrac{}{}{0}{}{a}{b}"));
        MathSyntaxException style = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\genfrac{}{}{}{4}{a}{b}"));
        assertTrue(style.getMessage().contains("style"), style.getMessage());
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\genfrac{x}{}{}{}{a}{b}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\genfrac{}{}{}{}{a}"));
    }

    // ------------------------------------------------------------------
    // \multicolumn — spanning cells
    // ------------------------------------------------------------------

    @Test
    void multicolumnCorpusArraysRender() {
        // Pointwise-convergence-of-fourfold-ergodic-averages.
        assertRenders("\\begin{array}{c|rrrr} S&\\multicolumn{4}{c}{(c_j)_{j\\in S}\\text{ in increasing role order}}"
            + "\\\\ \\hline \\{0,1,2,3\\}&-1&3&-3&1\\\\ \\{0,1,2,4\\}&-3&8&-6&1 \\end{array}");
        // Stable-Self-Similar-Blowup: two spans in one row, one carrying its own rule.
        assertRenders("\\begin{array}{c|cc|cc} &\\multicolumn{2}{c|}{\\text{positive roots}}&"
            + " \\multicolumn{2}{c}{\\text{negative roots}}\\\\ \\ell&\\mathcal R_\\ell&\\mathcal I_\\ell"
            + "&\\mathcal R_\\ell&\\mathcal I_\\ell\\\\\\hline 0&5&6&2&1 \\end{array}");
        // The-periodic-spin-one-Haldane-gap: a span with one cell left of it.
        assertRenders("\\begin{array}{c|rrr} &\\multicolumn{3}{c}{b=21/2}\\\\ n&g=1&g=P&g=C\\\\ \\hline"
            + " 6&8945303&346734&939987 \\end{array}");
    }

    @Test
    void multicolumnCountsAsItsSpanAgainstTheColumnSpec() {
        // A span of 2 fills two declared columns: one more cell overflows the spec,
        // exactly as a third plain cell would.
        assertRenders("\\begin{array}{ccc}\\multicolumn{2}{c}{x}&y\\\\a&b&c\\end{array}");
        assertThrows(MathSyntaxException.class, () -> MathParser.parse(
            "\\begin{array}{cc}\\multicolumn{2}{c}{x}&y\\end{array}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse(
            "\\begin{array}{cc}\\multicolumn{3}{c}{x}\\end{array}"));
        // TeX's \multicolumn must open its cell (\omit), and is meaningless outside a grid.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse(
            "\\begin{array}{cc}a\\multicolumn{1}{c}{x}&y\\end{array}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\multicolumn{1}{c}{x}"));
        // Its spec is one column: one alignment letter with optional rules.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse(
            "\\begin{array}{cc}\\multicolumn{2}{cc}{x}\\end{array}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse(
            "\\begin{array}{cc}\\multicolumn{0}{c}{x}\\end{array}"));
    }

    // ------------------------------------------------------------------
    // \sf / \tt — the rest of the legacy font switches
    // ------------------------------------------------------------------

    private static List<Integer> atoms(String latex) {
        List<Integer> out = new ArrayList<>();
        collect(MathParser.parse(latex), out);
        return out;
    }

    private static void collect(MathNode node, List<Integer> out) {
        if (node instanceof Atom a) {
            out.add(a.codePoint());
        } else if (node instanceof MathList l) {
            l.items().forEach(i -> collect(i, out));
        } else if (node instanceof MathNode.SupSub s) {
            collect(s.base(), out);
            if (s.sub() != null) {
                collect(s.sub(), out);
            }
            if (s.sup() != null) {
                collect(s.sup(), out);
            }
        }
    }

    @Test
    void sfAndTtAreDeclarationsLikeTheirSiblings() {
        // Conformal-Limits-of-Critical-Square-Lattice-Random-Cluster-Interfaces.
        assertRenders("P_WD_W,\\qquad [D_W,D_O]_{\\sf D},\\qquad D_OP_O,\\qquad [P_O,P_W]_{\\sf P}.");
        // Scalar-Potentials-and-Slow-Clocks.
        assertRenders("(\\tau_{q,a},\\beta_{q,a},\\mu_{q,a})= \\begin{cases}({\\sf Mark}_{(q,a)},b_{(q,a)},"
            + "d_{(q,a)}),&(q,a)\\in E,\\\\ ({\\sf Stop}_q,a,0),&(q,a)\\notin E. \\end{cases}");
        assertEquals(atoms("\\mathsf{Mark}y"), atoms("{\\sf Mark}y"), "\\sf ends at its group");
        assertEquals(atoms("\\mathtt{run}y"), atoms("{\\tt run}y"), "\\tt ends at its group");
        assertNotEquals(atoms("{\\sf D}"), atoms("{\\rm D}"), "control: \\sf actually restyles");
    }

    // ------------------------------------------------------------------
    // esint's \fint and its siblings
    // ------------------------------------------------------------------

    @Test
    void esintIntegralsAreIntegralOperators() {
        // De-Giorgis-conjecture-in-dimension-eight (loads esint).
        assertRenders("\\frac12|\\nabla_{x'}F_s(0)| \\le \\zeta\\fint_{B_{S/2}^m}F(x',0)\\,dx'.");
        // Uniform-real-Lipschitz-surfaces (loads esint).
        assertRenders("Cs\\left(\\int\\fint_{B(x,Cs)}|\\nabla u(y)|^p dy d\\mu(x)\\right)^{1/p}");
        record Op(String name, int codePoint) { }
        for (Op op : List.of(new Op("fint", 0x2A0F), new Op("sqint", 0x2A16),
                new Op("ointclockwise", 0x2232), new Op("ointctrclockwise", 0x2233))) {
            MathNode node = MathParser.parse("\\" + op.name() + "_{B}f");
            assertTrue(node.toString().contains("codePoint=" + op.codePoint()), op + " -> " + node);
            assertTrue(node.toString().contains("BigOperator"), op + " is a large operator: " + node);
        }
    }

    // ------------------------------------------------------------------
    // \text inside \mathrm (and the other text-family nestings)
    // ------------------------------------------------------------------

    @Test
    void aTextCommandNestsInsideMathrm() {
        // Power-law-violations-of-Yaus-nodal-upper-bound.
        assertRenders("S_x^{-2}\\sum_{v\\ \\mathrm{non\\text{-}tail}}|v(x)|^2=1-o(1).");
        // The-p-adic-section-conjecture.
        assertRenders("C(K)\\subseteq C(\\mathbb A_K)_\\bullet^{\\mathrm{f\\text{-}cov}}"
            + " \\subseteq C(\\mathbb A_K)_\\bullet^{\\mathrm{f\\text{-}ab}}.");
        assertEquals(new MathList(List.of(new TextRun("non", TextStyle.ROMAN),
                new TextRun("-", TextStyle.ROMAN), new TextRun("tail", TextStyle.ROMAN))),
            MathParser.parse("\\mathrm{non\\text{-}tail}"));
        // The nested run keeps ITS style, and math inside it still re-enters math mode.
        assertEquals(new MathList(List.of(new TextRun("a ", TextStyle.ROMAN),
                new TextRun("b", TextStyle.BOLD))),
            MathParser.parse("\\text{a \\textbf{b}}"));
        assertEquals(MathParser.parse("\\text{if }x\\text{ holds}"),
            MathParser.parse("\\text{if \\text{$x$} holds}"));
        // A combination TextStyle cannot express (bold AND italic) fails loud.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\textbf{a \\textit{b}}"));
        // \mathrm is math-only: nesting it in text stays the established refusal.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\text{a \\mathrm{b}}"));
    }

    @Test
    void nestedTextIsDepthBounded() {
        String deep = "\\text{".repeat(MathParser.MAX_DEPTH + 10) + "x"
            + "}".repeat(MathParser.MAX_DEPTH + 10);
        MathSyntaxException e = assertThrows(MathSyntaxException.class, () -> MathParser.parse(deep));
        assertTrue(e.getMessage().contains("nesting too deep"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // \tag inside display environments
    // ------------------------------------------------------------------

    @Test
    void tagIsAcceptedOnTheRowsOfAlignAndGather() {
        // A-density-uniform-condensate-bound-for-dilute-Bose-gases.
        assertRenders("\\begin{align}F_N&\\geq c_v\\rho N,\\tag{4}\\\\ F_{N+k}&\\geq F_N+c_v\\rho k"
            + "\\qquad(k\\geq0).\\tag{5}\\end{align}");
        // An-unconditional-first-moment-for-cubic-Gauss-sums: align* takes \tag too.
        assertRenders("\\begin{align*}T\\ll X^{.01},&\\qquad cX^{1/3}\\le B,\\quad c>0\\text{ fixed},\\tag{a}"
            + "\\\\ T\\gg X^{.01},&\\qquad X^{1/3-3\\kappa}\\le B.\\tag{b}\\end{align*}");
        // Critical-local-smoothing: only the last row tagged.
        assertRenders("\\begin{align}\\mu(B(x,R))&\\leq \\Delta^{-\\eta_0}R^d &&(\\Delta\\leq R\\leq1),\\\\"
            + " (\\mu\\times\\mu)(E)&\\geq\\Delta^{\\eta_0}. \\tag{rad-assume}\\end{align}");
        // One tag per row, as amsmath ("Multiple \tag").
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{align}a&=b\\tag{1}\\tag{2}\\end{align}"));
        // Two rows, two tags: legal.
        assertRenders("\\begin{gather}a\\tag{1}\\\\b\\tag{2}\\end{gather}");
    }

    @Test
    void tagInsideAnInnerEnvironmentTagsTheEquation() {
        // The-low-temperature-Sherrington-Kirkpatrick: \tag in gathered tags the display.
        MathNode node = MathParser.parse("\\begin{gathered} T_2 \\ll P_1,\\\\ L T_1 \\ll G_1 . \\tag{C18}"
            + " \\end{gathered}");
        assertInstanceOf(MathNode.Tagged.class, node);
        assertEquals(MathParser.parse("C18"), ((MathNode.Tagged) node).label());
        // Inside a style switch or a group, too: the tag is equation-global wherever it sits.
        assertInstanceOf(MathNode.Tagged.class, MathParser.parse("\\displaystyle x \\tag{1}"));
        assertInstanceOf(MathNode.Tagged.class, MathParser.parse("{x \\tag{1}}"));
        assertInstanceOf(MathNode.Tagged.class,
            MathParser.parse("\\begin{equation}x\\tag{1}\\end{equation}"));
        // Still one per equation.
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{gathered}a\\tag{1}\\\\b\\tag{2}\\end{gathered}"));
    }

    // ------------------------------------------------------------------
    // Investigation buckets: the style switch ran past \end / \right / \middle
    // ------------------------------------------------------------------

    @Test
    void aStyleSwitchStopsAtTheEndOfItsCellOrFence() {
        // Computation-under-Rapidly-Vanishing-Navier-Stokes-Forcing (\end bucket).
        assertRenders("\\begin{align}v_{\\rm in}(t,\\xi)&=\\sigma'(t)(0,\\epsilon_0c_0), \\\\ v_n(t,\\xi)"
            + "&=\\sigma'(t-1-n) \\begin{pmatrix} \\beta_n-\\beta_{n+1} +2\\beta_{n+1}\\displaystyle"
            + "\\sum_{j\\in\\mathbb Z}h_n(j)J_{n,j}(\\xi_2)\\\\ \\epsilon_{n+1}\\displaystyle\\sum_{j\\in"
            + "\\mathbb Z}F_n(j)J_{n,j}(\\xi_2) \\end{pmatrix}, \\\\ v&=v_{\\rm in}+\\sum_{n\\ge0}v_n.\\end{align}");
        // Pettys-projection-volume-conjecture (\right bucket).
        assertRenders("\\frac{\\left(\\displaystyle\\binom{n-1}{i} \\frac{\\kappa_{n-1}}{\\kappa_{n-i-1}}"
            + "\\right)^{i+1}} {\\left(\\displaystyle\\binom{n}{i+1} \\frac{\\kappa_n}{\\kappa_{n-i-1}}\\right)^{i-1}}");
        // Routing-densities (\right bucket).
        assertRenders("\\left\\|\\mathbb E_s(\\textstyle\\sum s_p W_p)^{2l}\\right\\| \\le"
            + " \\big(C(M\\sqrt l+K_0 l)\\big)^{2l}.");
        // The scope is exactly "to the end of the cell / fence": same tree as a braced switch.
        assertEquals(MathParser.parse("\\begin{pmatrix}a{\\displaystyle\\sum_j x}\\end{pmatrix}"),
            MathParser.parse("\\begin{pmatrix}a\\displaystyle\\sum_j x\\end{pmatrix}"));
        assertEquals(MathParser.parse("\\left({\\displaystyle\\binom{n}{i}}\\right)"),
            MathParser.parse("\\left(\\displaystyle\\binom{n}{i}\\right)"));
        assertEquals(MathParser.parse("\\left(a\\middle|{\\textstyle b}\\right)"),
            MathParser.parse("\\left(a\\middle|\\textstyle b\\right)"));
        assertEquals(MathParser.parse("\\left({\\textstyle a}\\middle|b\\right)"),
            MathParser.parse("\\left(\\textstyle a\\middle|b\\right)"));
        // The same for the colour and legacy-font switches, which share the boundary.
        assertEquals(MathParser.parse("\\left({\\color{red}a}\\right)"),
            MathParser.parse("\\left(\\color{red}a\\right)"));
        assertEquals(MathParser.parse("\\begin{matrix}{\\bf a}\\end{matrix}"),
            MathParser.parse("\\begin{matrix}\\bf a\\end{matrix}"));
    }

    // ------------------------------------------------------------------
    // Investigation bucket: "\\ [x]" in an amsmath environment is content
    // ------------------------------------------------------------------

    @Test
    void aSpacedBracketAfterARowBreakIsContentInAmsmathEnvironments() {
        // amsmath reads \\'s optional [len] with \new@ifnextchar, which does NOT skip
        // spaces, precisely so a row may begin with a bracket. Contact-Fano-manifolds:
        assertRenders("\\begin{gathered} B\\in H^0(X\\times X,\\mathcal I_\\Delta^2\\otimes(L\\boxtimes L)),\\\\"
            + " [B]_2=\\theta^2 \\quad\\text{in }H^0(X,\\Omega_X^1\\otimes L^{\\otimes2}). \\end{gathered}");
        // Continuous-Phase-Foliations (split) and The-strong-thin-tree-conjecture (cases).
        assertRenders("\\begin{split} C_n(f)&=-\\frac{q^2}{6\\pi}[W_q(\\cdot;f)]_n, \\qquad n=\\pm q,\\\\"
            + " [v]_n&=\\frac1{2\\pi}\\int_0^{2\\pi}v(x)e^{-inx}\\,dx. \\end{split}");
        assertRenders("p_i(H)= \\begin{cases} \\delta_i-6\\Delta_{i+1},&H\\text{ is fresh},\\\\"
            + " [w(H)-6\\Delta_i]_+,&H\\text{ is old}, \\end{cases}");
        // The bracket is the second row's first content: same tree as an explicit empty option.
        assertEquals(MathParser.parse("\\begin{gathered}a\\\\[0pt] [B]_2\\end{gathered}"),
            MathParser.parse("\\begin{gathered}a\\\\ [B]_2\\end{gathered}"));
        assertEquals(MathParser.parse("\\begin{pmatrix}a\\\\*[0pt] [b]\\end{pmatrix}"),
            MathParser.parse("\\begin{pmatrix}a\\\\* [b]\\end{pmatrix}"));
        // An ADJACENT bracket is still the spacing option everywhere ...
        assertEquals(MathParser.parse("\\begin{gathered}a\\\\b\\end{gathered}"),
            MathParser.parse("\\begin{gathered}a\\\\[2pt]b\\end{gathered}"));
        // ... and LaTeX's own array/eqnarray \\ DOES skip spaces (\@ifnextchar).
        assertEquals(MathParser.parse("\\begin{array}{c}a\\\\b\\end{array}"),
            MathParser.parse("\\begin{array}{c}a\\\\ [2pt] b\\end{array}"));
        assertEquals(MathParser.parse("\\begin{eqnarray}a&=&b\\\\c&=&d\\end{eqnarray}"),
            MathParser.parse("\\begin{eqnarray}a&=&b\\\\ [2pt] c&=&d\\end{eqnarray}"));
    }
}
