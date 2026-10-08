package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.BigOperator;
import com.lattex.parse.MathNode.ClassOverride;
import com.lattex.parse.MathNode.LimitsMode;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import com.lattex.parse.MathNode.OperatorName;
import com.lattex.parse.MathNode.Spacing;
import com.lattex.parse.MathNode.SupSub;
import com.lattex.parse.MathNode.TextRun;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The amsmath / amssymb names real research papers use most and LatteX refused
 * (plan edbda088). Each was MEASURED as a refusal on main 066b90f over 138,172
 * research display formulas; the count is in each test's comment, and every
 * example below is quoted verbatim from that corpus (only the surrounding
 * {@code \label}s were stripped by the harvest).
 *
 * <p>Every name is registered through the existing tables ({@link Symbols} /
 * {@link CommandRegistry}), so suggestion, grammar index, macro reservation and
 * MathML follow from the one row; {@link #everyNewNameIsInTheRegistryIndex} is
 * the census that pins that.
 */
class AmsGapsTest {

    /** Renders without throwing and emits real glyph ink. */
    private static String assertRenders(String latex) {
        String svg = assertDoesNotThrow(() -> LatteX.render(latex), latex);
        assertTrue(svg.contains("<path"), "renders glyph ink: " + latex);
        return svg;
    }

    private static final Pattern VIEWBOX = Pattern.compile(
        "viewBox=\"[-0-9.]+ [-0-9.]+ ([0-9.]+) ([0-9.]+)\"");

    private static double width(String svg) {
        Matcher m = VIEWBOX.matcher(svg);
        assertTrue(m.find(), "svg has a viewBox");
        return Double.parseDouble(m.group(1));
    }

    private static double height(String svg) {
        Matcher m = VIEWBOX.matcher(svg);
        assertTrue(m.find(), "svg has a viewBox");
        return Double.parseDouble(m.group(2));
    }

    // ------------------------------------------------------------------
    // \hbox (957) - a text box, exactly \text's contract.
    // ------------------------------------------------------------------

    @Test
    void hboxIsATextBox() {
        assertRenders("\\{y\\in Q_{\\mathcal D}:yRx\\} \\quad\\hbox{and}\\quad "
            + "\\{y\\in Q_{\\mathcal D}:yRx'\\}.");
        assertEquals(MathParser.parse("\\text{and}"), MathParser.parse("\\hbox{and}"));
        assertEquals(new TextRun("for all ", MathNode.TextStyle.ROMAN),
            MathParser.parse("\\hbox{for all }"), "inter-word spaces survive, like \\text");
        // Nested math re-enters math mode, as in \text.
        assertEquals(MathParser.parse("\\text{if $x>0$}"), MathParser.parse("\\hbox{if $x>0$}"));
    }

    // ------------------------------------------------------------------
    // gathered (897) + the aligned family check: aligned, split present;
    // alignedat and multlined added.
    // ------------------------------------------------------------------

    @Test
    void gatheredIsTheInnerGather() {
        String corpus = "\\begin{gathered} b(t)=0\\quad\\Longleftrightarrow\\quad t=50\\pmod{100}, "
            + "\\qquad b(t)=1\\quad\\text{if }|t-50|\\ge2\\text{ in }[0,100],\\\\ "
            + "t+b(t)\\le50\\qquad(0\\le t\\le50). \\end{gathered}";
        assertRenders(corpus);
        Matrix m = assertInstanceOf(Matrix.class, MathParser.parse(corpus));
        assertEquals(MatrixKind.GATHER, m.kind());
        assertEquals(2, m.rows().size());
        // Same grid as gather*, and the optional [t]/[b]/[c] position is read, not rendered.
        assertEquals(MathParser.parse("\\begin{gather*}a\\\\b\\end{gather*}"),
            MathParser.parse("\\begin{gathered}a\\\\b\\end{gathered}"));
        assertEquals(MathParser.parse("\\begin{gathered}a\\\\b\\end{gathered}"),
            MathParser.parse("\\begin{gathered}[t]a\\\\b\\end{gathered}"));
    }

    @Test
    void theInnerAlignmentFamilyIsComplete() {
        // Already present on 066b90f - confirmed, not added.
        assertRenders("\\begin{aligned}a&=b\\\\c&=d\\end{aligned}");
        assertRenders("\\begin{split}a&=b\\\\&=d\\end{split}");
        // alignedat: aligned with a mandatory {n} column-pair count (read and discarded,
        // exactly as alignat) and aligned's optional position argument.
        assertEquals(MathParser.parse("\\begin{aligned}a&=b&c&=d\\end{aligned}"),
            MathParser.parse("\\begin{alignedat}{2}a&=b&c&=d\\end{alignedat}"));
        assertEquals(MathParser.parse("\\begin{aligned}a&=b&c&=d\\end{aligned}"),
            MathParser.parse("\\begin{alignedat}[t]{2}a&=b&c&=d\\end{alignedat}"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{alignedat}a&=b\\end{alignedat}"),
            "the {n} count is mandatory, as for alignat");
        // multlined (mathtools): the inner multline.
        assertEquals(MathParser.parse("\\begin{multline*}a+b\\\\+c\\end{multline*}"),
            MathParser.parse("\\begin{multlined}a+b\\\\+c\\end{multlined}"));
    }

    // ------------------------------------------------------------------
    // \lVert \rVert (118) \lvert \rvert (55), and as delimiters (23).
    // ------------------------------------------------------------------

    @Test
    void pairedBarsAreOpenAndCloseAtoms() {
        assertRenders("\\lVert F\\rVert_\\square =\\sup_{S,T\\subseteq[0,1]} "
            + "\\left|\\int_{S\\times T}F(x,y)\\,dx\\,dy\\right|,");
        assertRenders("0=2\\lvert i_\\xi F\\rvert_{\\mathbf g}^2.");
        assertEquals(new Atom(0x2016, MathClass.OPEN), MathParser.parse("\\lVert"));
        assertEquals(new Atom(0x2016, MathClass.CLOSE), MathParser.parse("\\rVert"));
        assertEquals(new Atom('|', MathClass.OPEN), MathParser.parse("\\lvert"));
        assertEquals(new Atom('|', MathClass.CLOSE), MathParser.parse("\\rvert"));
    }

    @Test
    void pairedBarsAreDelimitersAfterLeftRightAndBig() {
        assertRenders("\\left\\lVert \\tau_{K_AK_BE'}- \\frac12\\sum_{i=0}^1|i,i\\rangle"
            + "\\langle i,i|\\otimes\\sigma_{E'} \\right\\rVert_1\\geq\\frac15.");
        assertRenders("\\bigl\\lVert z_h(F+J_2/(l-2))\\bigr\\rVert_\\infty "
            + "=z_h\\left(1+\\frac2{l-2}\\right)");
        assertEquals(MathParser.parse("\\left\\| x\\right\\|"),
            MathParser.parse("\\left\\lVert x\\right\\rVert"));
        assertEquals(MathParser.parse("\\left| x\\right|"),
            MathParser.parse("\\left\\lvert x\\right\\rvert"));
        assertEquals(MathParser.parse("\\bigl\\| x\\bigr\\|"),
            MathParser.parse("\\bigl\\lVert x\\bigr\\rVert"));
        assertEquals(MathParser.parse("\\Bigl| x\\Bigr|"),
            MathParser.parse("\\Bigl\\lvert x\\Bigr\\rvert"));
    }

    @Test
    void aPastedBarKeepsItsOwnClassDeterministically() {
        // \lVert/\rVert share U+2016 with \| and \Vert (Ord); lhd, rhd, unlhd, unrhd share
        // U+22B2..U+22B5 with \vartriangleleft & co (Rel). The class-tagged spellings must not
        // claim the code point: a PASTED glyph keeps the bare form's class, every run.
        assertEquals(MathClass.ORD, Symbols.classForCodePoint(0x2016));
        for (int cp = 0x22B2; cp <= 0x22B5; cp++) {
            assertEquals(MathClass.REL, Symbols.classForCodePoint(cp), Integer.toHexString(cp));
        }
    }

    // ------------------------------------------------------------------
    // \mathop (57): an Op atom with TeX's display-limits behaviour.
    // ------------------------------------------------------------------

    @Test
    void mathopGroupsAnOperatorThatTakesLimitsInDisplay() {
        String corpus = "p_H^*(X)=\\mathop{\\mathrm{colim}}_{(C,c)\\in\\mathcal C_0/H}X(C), "
            + "\\qquad p_H^*(y_A)=\\operatorname{Hom}_K(A,H).";
        assertRenders(corpus);
        SupSub s = assertInstanceOf(SupSub.class,
            MathParser.parse("\\mathop{\\mathrm{colim}}_{i\\in I}"));
        ClassOverride op = assertInstanceOf(ClassOverride.class, s.base());
        assertEquals(MathClass.OP, op.forcedClass());

        // Display: the limit sits UNDER the operator, so the row is narrower and deeper
        // than the same scripts set beside (\nolimits); text style sets them beside.
        String lim = "\\mathop{\\mathrm{colim}}_{abcdefghij}";
        String side = "\\mathop{\\mathrm{colim}}\\nolimits_{abcdefghij}";
        assertTrue(width(LatteX.render(lim)) < width(LatteX.render(side)),
            "display \\mathop stacks its limit");
        assertTrue(height(LatteX.render(lim)) > height(LatteX.render(side)),
            "the stacked limit adds depth");
        assertEquals(width(LatteX.renderInline(side)), width(LatteX.renderInline(lim)), 1e-6,
            "text style sets the scripts beside");
        assertTrue(width(LatteX.renderInline("\\mathop{\\mathrm{colim}}\\limits_{abcdefghij}"))
            < width(LatteX.renderInline(lim)), "\\limits forces stacking in text style");
    }

    @Test
    void mathopOfASingleSymbolIsALargeOperator() {
        // TeXbook App. G rule 13: an Op whose nucleus is a single symbol is centred on the
        // axis (and enlarged in display) - exactly the existing large-operator path.
        assertRenders("\\mathop{\\boxtimes}_{i\\in I}V_i");
        BigOperator op = assertInstanceOf(BigOperator.class,
            MathParser.parse("\\mathop{\\boxtimes}_{i}"));
        assertEquals(new Atom(0x22A0, MathClass.OP), op.op());
        BigOperator sum = assertInstanceOf(BigOperator.class,
            MathParser.parse("\\mathop{\\sum}\\nolimits_F"));
        assertEquals(LimitsMode.NOLIMITS, sum.limitsMode());
        assertEquals(0x2211, sum.op().codePoint());
        assertRenders("\\mathop{\\bigwedge}\\nolimits^p V");
        assertRenders("\\mathop{\\dot\\bigcup}_{n\\in\\mathbb N}A_n");
    }

    // ------------------------------------------------------------------
    // \Subset (50) \Supset
    // ------------------------------------------------------------------

    @Test
    void doubleSubsetRelations() {
        assertRenders("p\\in W\\Subset V\\Subset U, \\qquad W=B_0\\times I_0,");
        assertEquals(new Atom(0x22D0, MathClass.REL), MathParser.parse("\\Subset"));
        assertEquals(new Atom(0x22D1, MathClass.REL), MathParser.parse("\\Supset"));
    }

    // ------------------------------------------------------------------
    // \varprojlim (44) \varinjlim (14): lim with an arrow under it, an Op with limits.
    // ------------------------------------------------------------------

    @Test
    void varLimitsAreDecoratedLimOperators() {
        assertRenders("\\Omega^j(Z)\\widehat\\otimes B =\\varprojlim_k\\bigl(\\Omega^j(Z)"
            + "\\otimes_{\\mathbb C} B/\\mathfrak m^k\\bigr).");
        assertRenders("L=\\varinjlim\\bigl(U\\xrightarrow{f}U\\xrightarrow{f}U "
            + "\\xrightarrow{f}\\cdots\\bigr),");
        ClassOverride proj = assertInstanceOf(ClassOverride.class, MathParser.parse("\\varprojlim"));
        assertEquals(MathClass.OP, proj.forcedClass());
        Accent arrow = assertInstanceOf(Accent.class, proj.body());
        assertEquals(0x20EE, arrow.accentCodePoint(), "combining left arrow below");
        assertTrue(arrow.under() && arrow.stretchy());
        assertEquals(new OperatorName("lim", false), arrow.base());
        Accent inj = assertInstanceOf(Accent.class,
            assertInstanceOf(ClassOverride.class, MathParser.parse("\\varinjlim")).body());
        assertEquals(0x20EF, inj.accentCodePoint(), "combining right arrow below");
        // Limits under it in display, like \lim.
        assertTrue(width(LatteX.render("\\varprojlim_{abcdefghij}"))
            < width(LatteX.render("\\varprojlim\\nolimits_{abcdefghij}")));
        // The siblings of the family: underlined / overlined lim.
        Accent inf = assertInstanceOf(Accent.class,
            assertInstanceOf(ClassOverride.class, MathParser.parse("\\varliminf")).body());
        assertTrue(inf.isRule() && inf.under());
        Accent sup = assertInstanceOf(Accent.class,
            assertInstanceOf(ClassOverride.class, MathParser.parse("\\varlimsup")).body());
        assertTrue(sup.isRule() && !sup.under());
    }

    // ------------------------------------------------------------------
    // \dashrightarrow (39) \dashleftarrow
    // ------------------------------------------------------------------

    @Test
    void dashedArrows() {
        assertRenders("\\ell=\\min\\{\\dim V: a\\text{ good},\\quad b:P_a\\dashrightarrow V"
            + "\\text{ a fibration},\\quad K/C_b\\text{ infinite}\\}.");
        assertEquals(new Atom(0x21E2, MathClass.REL), MathParser.parse("\\dashrightarrow"));
        assertEquals(new Atom(0x21E0, MathClass.REL), MathParser.parse("\\dashleftarrow"));
    }

    // ------------------------------------------------------------------
    // \lhook\joinrel (35)
    // ------------------------------------------------------------------

    @Test
    void lhookJoinrelBuildsTheHookedArrow() {
        assertRenders("\\kappa_S:T\\longrightarrow W_S\\otimes W_S "
            + "\\lhook\\joinrel\\longrightarrow H^2(A_S\\times A_S,\\mathbb Q).");
        // \lhook\joinrel\rightarrow IS plain TeX's definition of \hookrightarrow.
        assertEquals(MathParser.parse("a\\hookrightarrow b"),
            MathParser.parse("a\\lhook\\joinrel\\rightarrow b"));
        // The long form is the extensible hooked arrow with no label.
        assertEquals(MathParser.parse("a\\xhookrightarrow{} b"),
            MathParser.parse("a\\lhook\\joinrel\\longrightarrow b"));
        // \lhook alone has no glyph in Unicode or STIX Two Math: refuse loud, never fake.
        MathSyntaxException alone = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("a\\lhook b"));
        assertTrue(alone.getMessage().contains("\\hookrightarrow"), alone.getMessage());
        // \joinrel by itself is TeX's -3mu relation kern.
        MathList kern = assertInstanceOf(MathList.class, MathParser.parse("a\\joinrel b"));
        assertEquals(new Spacing(-3.0), kern.items().get(1));
    }

    // ------------------------------------------------------------------
    // \Box (24) \restriction (10) \Bbbk (7), the latexsym triangles (unrhd and co)
    // ------------------------------------------------------------------

    @Test
    void boxIsTheWhiteSquare() {
        assertRenders("\\Box(wF\\circ\\Phi)=w^3(\\Box F)\\circ\\Phi.");
        assertEquals(new Atom(0x25A1, MathClass.ORD), MathParser.parse("\\Box"));
    }

    @Test
    void restrictionIsTheUpHarpoonRelation() {
        assertRenders("s_{t\\restriction J,\\beta} =\\iota_{|J|}(\\mathcal D_{t\\restriction J,\\beta})");
        assertEquals(MathParser.parse("\\upharpoonright"), MathParser.parse("\\restriction"));
    }

    @Test
    void bbbkIsTheDoubleStruckK() {
        assertRenders("R\\longrightarrow\\mathcal O_L[1/N]\\longrightarrow "
            + "\\mathcal O_L/\\mathfrak l=\\Bbbk");
        assertEquals(new Atom(0x1D55C, MathClass.ORD), MathParser.parse("\\Bbbk"));
        assertEquals(MathParser.parse("\\mathbb{k}"), MathParser.parse("\\Bbbk"));
    }

    @Test
    void latexsymTrianglesAreBinaryOperators() {
        assertRenders("H\\unlhd G,\\quad N\\lhd G,\\quad G\\rhd N,\\quad G\\unrhd H");
        assertEquals(new Atom(0x22B2, MathClass.BIN), MathParser.parse("\\lhd"));
        assertEquals(new Atom(0x22B3, MathClass.BIN), MathParser.parse("\\rhd"));
        assertEquals(new Atom(0x22B4, MathClass.BIN), MathParser.parse("\\unlhd"));
        assertEquals(new Atom(0x22B5, MathClass.BIN), MathParser.parse("\\unrhd"));
    }

    // ------------------------------------------------------------------
    // \qedhere (8): accepted, emits nothing.
    // ------------------------------------------------------------------

    @Test
    void qedhereIsAcceptedAndEmitsNothing() {
        String body = "\\|P_n a P_t\\|\\le \\|P_{\\min(n,t)}aP_{\\min(n,t)}\\|.";
        assertEquals(LatteX.render(body), LatteX.render(body + " \\qedhere"));
        assertEquals(MathParser.parse("\\begin{align*}a&=b\\end{align*}"),
            MathParser.parse("\\begin{align*}a&=b\\qedhere\\end{align*}"));
    }

    // ------------------------------------------------------------------
    // \hspace physical units: mm (149) cm (24) + in bp pc dd cc sp.
    // ------------------------------------------------------------------

    private static double spacingMu(String latex) {
        for (MathNode item : assertInstanceOf(MathList.class, MathParser.parse(latex)).items()) {
            if (item instanceof Spacing s) {
                return s.muWidth();
            }
        }
        throw new AssertionError("no Spacing in " + latex);
    }

    @Test
    void hspaceTakesTexPhysicalUnits() {
        assertRenders("\\begin{align}&\\sum_{|v|=i}r^iX_v\\\\ &\\hspace{35mm}=M\\lambda^{j-i}\\end{align}");
        assertRenders("\\begin{align}&(1-o(1))\\pi\\\\ &\\hspace{1cm}\\le 2^{C_LM_0n}.\\end{align}");
        // TeXbook Ch.10: 1in = 72.27pt, 2.54cm = 1in, 10mm = 1cm, 72bp = 1in, 1pc = 12pt,
        // 1157dd = 1238pt, 1cc = 12dd, 65536sp = 1pt. LatteX's anchor: 1pt = 1.8mu.
        double pt = spacingMu("a\\hspace{1pt}b");
        assertEquals(1.8, pt, 1e-12);
        assertEquals(72.27 * pt, spacingMu("a\\hspace{1in}b"), 1e-9);
        assertEquals(72.27 / 2.54 * pt, spacingMu("a\\hspace{1cm}b"), 1e-9);
        assertEquals(72.27 / 25.4 * pt, spacingMu("a\\hspace{1mm}b"), 1e-9);
        assertEquals(72.27 / 72.0 * pt, spacingMu("a\\hspace{1bp}b"), 1e-9);
        assertEquals(12.0 * pt, spacingMu("a\\hspace{1pc}b"), 1e-9);
        assertEquals(1238.0 / 1157.0 * pt, spacingMu("a\\hspace{1dd}b"), 1e-9);
        assertEquals(12.0 * 1238.0 / 1157.0 * pt, spacingMu("a\\hspace{1cc}b"), 1e-9);
        assertEquals(pt / 65536.0, spacingMu("a\\hspace{1sp}b"), 1e-12);
        assertEquals(35 * 72.27 / 25.4 * pt, spacingMu("a\\hspace{35mm}b"), 1e-9);
        // Bare (\kern) form reads the two-letter unit too.
        assertEquals(72.27 / 2.54 * pt, spacingMu("a\\kern1cm b"), 1e-9);
        MathSyntaxException bad = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("a\\hspace{2qq}b"));
        assertTrue(bad.getMessage().contains("mm"), "the refusal lists the accepted units: "
            + bad.getMessage());
    }

    // ------------------------------------------------------------------
    // control space `\ ` inside \mathrm / \text-family (34)
    // ------------------------------------------------------------------

    @Test
    void controlSpaceInsideTextFamilyIsAWordSpace() {
        assertRenders("\\sum_{x(p)\\ \\mathrm{in\\ the\\ interval}}\\frac1p "
            + "=\\int_{\\mathrm{interval}}\\frac{dx}{x}+O(E(u))");
        assertEquals(new TextRun("in the interval", MathNode.TextStyle.ROMAN),
            MathParser.parse("\\mathrm{in\\ the\\ interval}"));
        assertEquals(MathParser.parse("\\text{a b}"), MathParser.parse("\\text{a\\ b}"));
    }

    // ------------------------------------------------------------------
    // Census: every new name is a registry descriptor, in the generated index,
    // reserved against user macros, and (for glyph rows) drawn by a real glyph.
    // ------------------------------------------------------------------

    static final List<String> NEW_NAMES = List.of(
        "hbox", "lVert", "rVert", "lvert", "rvert", "mathop", "Subset", "Supset",
        "varprojlim", "varinjlim", "varliminf", "varlimsup", "dashrightarrow",
        "dashleftarrow", "lhook", "joinrel", "Box", "restriction", "Bbbk",
        "lhd", "rhd", "unlhd", "unrhd", "qedhere");

    @Test
    void everyNewNameIsInTheRegistryIndex() {
        List<String> indexed = MathParser.supportedCommands().stream()
            .map(MathParser.SupportedCommand::command).toList();
        for (String name : NEW_NAMES) {
            CommandRegistry.Descriptor d = CommandRegistry.get(name);
            assertNotNull(d, "\\" + name + " has a registry descriptor");
            assertTrue(indexed.contains("\\" + name), "\\" + name + " is in the generated index");
            assertTrue(!CommandRegistry.userMacroMayClaim(name), "\\" + name + " is reserved");
            assertDoesNotThrow(() -> MathParser.parse(d.indexExample()), d.indexExample());
        }
        for (String name : List.of("lVert", "rVert", "lvert", "rvert")) {
            assertTrue(CommandRegistry.delimiterCodePoint(name).isPresent(),
                "\\" + name + " is registered as a delimiter");
        }
        assertNull(CommandRegistry.get("lhookx"), "control: a near-miss stays unknown");
        SfntFont font = SfntFont.loadBundled();
        for (int cp : new int[] {0x22D0, 0x22D1, 0x21E2, 0x21E0, 0x25A1, 0x21BE, 0x1D55C,
                0x22B2, 0x22B3, 0x22B4, 0x22B5, 0x2016, 0x20EE, 0x20EF, 0x21AA}) {
            assertTrue(font.glyphId(cp) != 0, "STIX Two Math has U+" + Integer.toHexString(cp));
        }
    }
}
