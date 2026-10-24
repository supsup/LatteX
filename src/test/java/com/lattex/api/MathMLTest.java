package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathSyntaxException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/**
 * {@link LatteX#toMathML} — Presentation-MathML from the same parse tree as the SVG.
 * The load-bearing property is that it's always WELL-FORMED XML (an interop/a11y
 * surface that emits malformed markup is a liability), verified across the whole
 * wild corpus via the JDK's XML parser.
 */
class MathMLTest {

    /// Overload carrying the corpus coordinate, so a sweep failure names the ROW that produced it
    /// rather than only the emitted XML (plan bc50471c).
    private static void assertWellFormed(String xml, String where) {
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }, () -> "MathML must be well-formed XML at " + where + ": " + xml);
    }

    private static void assertWellFormed(String xml) {
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }, "MathML must be well-formed XML: " + xml);
    }

    @Test
    void mapsCoreConstructsToTheRightMathMLElements() {
        assertTrue(LatteX.toMathML("x")
            .startsWith("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"));
        // digit -> mn, letter -> mi, script -> msup
        assertTrue(LatteX.toMathML("x^2").contains("<msup><mi>x</mi><mn>2</mn></msup>"),
            LatteX.toMathML("x^2"));
        assertTrue(LatteX.toMathML("\\frac{a}{b}").contains("<mfrac><mi>a</mi><mi>b</mi></mfrac>"));
        assertTrue(LatteX.toMathML("\\sqrt{x}").contains("<msqrt>"));
        assertTrue(LatteX.toMathML("\\sqrt[3]{x}").contains("<mroot>"));
        assertTrue(LatteX.toMathML("\\sum_{i=1}^n i").contains("<munderover>"));
        assertTrue(LatteX.toMathML("\\left( x \\right)").contains("fence=\"true\""));
        assertTrue(LatteX.toMathML("x_i").contains("<msub>"));
    }

    @Test
    void escapesXmlSpecialCharacters() {
        String ml = LatteX.toMathML("a < b > c"); // < and > are relation atoms
        assertTrue(ml.contains("&lt;"), "'<' must be escaped: " + ml);
        assertTrue(ml.contains("&gt;"), "'>' must be escaped: " + ml);
        assertWellFormed(ml);
    }

    @Test
    void everyOkCorpusRowEmitsWellFormedMathML() throws Exception {
        // THE SAME CORPUS THE SVG RENDERER SWEEPS -- and until plan bc50471c it was not.
        // This comment claimed sameness while the code read /com/lattex/wild-corpus.tsv, where
        // all four SVG sweeps (CorpusRenderSweepTest, OutputCapPostconditionTest,
        // FontCacheByteIdentityRatchetTest, GroupmapGrammarContractTest) read
        // com/lattex/parse/corpus.tsv. A guard whose comment asserts a sameness it does not have
        // is why the rule-accent crash never reddened here.
        //
        // The two corpora are NOT the same shape, so this is a re-parse and not a path swap: this
        // file is tab-separated tier/group/latex/description with # comments, latex in COLUMN 2,
        // where wild-corpus.tsv put it in column 3 behind an "OK" flag. Swapping only the path
        // would have matched nothing and failed on the count -- a red for the wrong reason.
        int checked = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("com/lattex/parse/corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length < 3 || !"PARSES-NOW".equals(cols[0].trim())) {
                    continue;
                }
                String latex = cols[2];
                // Name the corpus LINE, as CorpusRenderSweepTest does, so a red points at its input.
                assertWellFormed(LatteX.toMathML(latex), "corpus.tsv:" + lineNo + " [" + latex + "]");
                checked++;
            }
        }
        assertTrue(checked > 150,
            "expected to sweep the whole PARSES-NOW corpus, only did " + checked);
    }

    @Test
    void accentsEmitTheRightCharacter() {
        // NEEDS-FIX PROJECT/lattex 950. Both of this branch's other checks are SHAPE assertions:
        // assertWellFormed accepts ANY valid code point, and the boundary test only inspects
        // exception classes. So the reviewer turned RULE_OVER_CODE_POINT into a snowman (0x2603),
        // swapped the ternary so overline emitted LOW LINE and underline emitted OVERLINE, and
        // hard-wired every non-rule accent to a caret -- and all 996 tests passed each time.
        // Nothing in the repository asserted what an accent actually EMITS.
        //
        // That is the difference between "it no longer crashes" and "it works".
        String math = "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">";

        // U+203E OVERLINE. This assertion ALONE kills both the snowman mutation and the ternary
        // swap; it is not half of anything.
        assertEquals(math + "<mover accent=\"true\"><mi>a</mi><mo>\u203E</mo></mover></math>",
            LatteX.toMathML("\\overline{a}"),
            "overline must emit U+203E OVERLINE over its base");

        // U+005F LOW LINE, under. CORRECTED (review 953): I claimed here that swapping the
        // ternary fails both assertions in opposite directions, which is what made them
        // non-redundant. That was FALSE and the reviewer measured it -- deleting them one at a
        // time, EITHER the overline or the underline alone kills the swap, so one assertEquals
        // was sufficient and my argument for a second was wrong. A single-site ternary has
        // exactly one swap, and a swap leaving overline correct would not be a swap of that
        // ternary. This assertion stays for a REAL reason instead: it is the only coverage of
        // the munder/accentunder serialization branch, which the overline case cannot reach.
        assertEquals(math + "<munder accentunder=\"true\"><mi>a</mi><mo>_</mo></munder></math>",
            LatteX.toMathML("\\underline{a}"),
            "underline must emit U+005F LOW LINE under its base");

        // A NON-RULE accent, so the isRule() guard cannot be satisfied by ignoring its else arm.
        // The reviewer's third mutation hard-wired mo(a.accentCodePoint()) to a caret and stayed
        // green; it is pre-existing rather than this branch's regression, but it is exactly the
        // hole these two new constants land in, so it is closed here rather than left as the
        // instance next to the class.
        assertEquals(math + "<mover accent=\"true\"><mi>a</mi><mo>\u0302</mo></mover></math>",
            LatteX.toMathML("\\hat{a}"),
            "a non-rule accent must still emit its own code point, not a shared constant");
    }

    @Test
    void toMathMLThrowsOnlyLatteXException() throws Exception {
        // STEP 4 of plan bc50471c: the public surface's exception boundary. Anything escaping
        // toMathML must be a LatteXException; a raw RuntimeException is a leak.
        //
        // THE ASSERTION IS ON LatteXException AND NOT ON IllegalArgumentException, DELIBERATELY.
        // DO NOT SIMPLIFY THIS TO THE SUPERCLASS. The reviewer proved the trap by weakening this
        // catch to IllegalArgumentException and running it against the BROKEN production code: it
        // PASSES. With LatteXException it FAILS (needs-fix PROJECT/lattex 950).
        // LatteXException EXTENDS IllegalArgumentException, so asserting the superclass would have
        // been satisfied by the very defect this plan fixes: the rule-accent crash threw a BARE
        // IllegalArgumentException ("Not a valid Unicode code point: 0xFFFFFFFF") out of mo(). An
        // assertion both the fixed and the broken code satisfy distinguishes neither.
        java.util.List<String> inputs = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("com/lattex/parse/corpus.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length >= 3) {
                    inputs.add(cols[2]);
                }
            }
        }
        // Malformed and edge inputs alongside the corpus: the boundary must hold on the failure
        // path too, which is where a raw exception is most likely to escape.
        inputs.addAll(java.util.List.of(
            "x^", "\\frac{a}", "", "   ", "\\overline{}", "\\underline{}",
            "\\begin{matrix}", "\\notacommand{x}", "{", "}"));

        for (String latex : inputs) {
            try {
                LatteX.toMathML(latex);
            } catch (LatteXException expected) {
                // the declared boundary
            } catch (Throwable leaked) {
                throw new AssertionError("toMathML leaked a non-LatteXException for ["
                    + latex + "]: " + leaked.getClass().getName() + ": " + leaked.getMessage(),
                    leaked);
            }
        }
    }

    @Test
    void malformedInputFailsLoudNotSilentlyEmpty() {
        // toMathML shares parse() — malformed input must throw, not emit junk MathML.
        assertThrows(MathSyntaxException.class, () -> LatteX.toMathML("x^"));
        assertThrows(MathSyntaxException.class, () -> LatteX.toMathML("\\frac{a}"));
    }

    @Test
    void delimitedMatricesWrapTheTableInFences() {
        // pmatrix carries ( ) delimiters -> stretchy mo fences, so a screen reader hears
        // the brackets instead of a bare table.
        String pm = LatteX.toMathML("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}");
        assertTrue(pm.contains("<mtable>") && pm.contains("fence=\"true\""),
            "pmatrix should wrap its table in mo fences: " + pm);
        assertWellFormed(pm);
        // a plain matrix (no delimiters) stays a bare mtable — no phantom fences.
        String plain = LatteX.toMathML("\\begin{matrix} a & b \\end{matrix}");
        assertFalse(plain.contains("fence=\"true\""), "plain matrix has no fences: " + plain);
    }

    @Test
    void tablesCarryTheirColumnAlignment() {
        // Audit cb3b4518 F2. matrixMathML never read Matrix.columnAligns, so every table came
        // out as a bare <mtable> and every cell was centred (MathML's default): the = signs of
        // an aligned block did not line up and a cases body was centred. The parser computes
        // the alignment and the SVG uses it; the MathML now states it on the table.
        String aligned = LatteX.toMathML("\\begin{aligned} a &= b+c \\\\ &= d \\end{aligned}");
        assertTrue(aligned.contains("<mtable columnalign=\"right left\">"), aligned);
        String cases = LatteX.toMathML(
            "\\begin{cases} x & \\text{if } x>0 \\\\ -x & \\text{otherwise} \\end{cases}");
        assertTrue(cases.contains("<mtable columnalign=\"left left\">"), cases);
        String array = LatteX.toMathML("\\begin{array}{lcr} a & b & c \\end{array}");
        assertTrue(array.contains("<mtable columnalign=\"left center right\">"), array);
        assertWellFormed(aligned);
        assertWellFormed(cases);
        assertWellFormed(array);
        // Control: an all-centred grid states nothing; centre is already MathML's default.
        String pm = LatteX.toMathML("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}");
        assertTrue(pm.contains("<mtable>"), pm);
        assertFalse(pm.contains("columnalign"), pm);
        // A \multicolumn cell aligned unlike the column it starts in says so on its own mtd;
        // one aligned like its column inherits and says nothing.
        String span = LatteX.toMathML(
            "\\begin{array}{lr} \\multicolumn{2}{c}{h} \\\\ a & b \\end{array}");
        assertTrue(span.contains("<mtd columnspan=\"2\" columnalign=\"center\">"), span);
        String same = LatteX.toMathML(
            "\\begin{array}{lr} \\multicolumn{2}{l}{h} \\\\ a & b \\end{array}");
        assertTrue(same.contains("<mtd columnspan=\"2\">"), same);
        assertWellFormed(span);
    }

    @Test
    void nolimitsPutsScriptsBesideNotAboveBelow() {
        // \sum_{i}^{n} keeps limits above/below (munderover); an explicit \nolimits sets
        // them to the side (msubsup), matching the SVG.
        assertTrue(LatteX.toMathML("\\sum_{i=1}^n i").contains("<munderover>"));
        String nolim = LatteX.toMathML("\\int\\nolimits_a^b f");
        assertTrue(nolim.contains("<msubsup>"), "\\nolimits should emit msubsup: " + nolim);
    }

    @Test
    void integralsKeepSideLimitsAsTheSvgDoes() {
        // Audit cb3b4518 F3. The SVG layout sets an integral's limits BESIDE it by default,
        // even in display style (LayoutEngine.isIntegral); the MathML emitter only did so for an
        // explicit \nolimits, so the two outputs of one parse disagreed: \int_0^1 came out as
        // <munderover>, stacking 0 and 1 under and over the sign.
        String i = LatteX.toMathML("\\int_0^1 f");
        assertTrue(i.contains("<msubsup><mo>\u222B</mo>"), "\\int_0^1 sets its limits beside: " + i);
        assertFalse(i.contains("<munderover>"), "\\int_0^1 must not stack its limits: " + i);
        // The whole integral family, inside and outside the U+222B..U+2233 block.
        for (String cmd : List.of("iint", "iiint", "oint", "oiint", "oiiint", "idotsint",
                "fint", "sqint", "ointclockwise", "ointctrclockwise")) {
            String m = LatteX.toMathML("\\" + cmd + "_a^b f");
            assertTrue(m.contains("<msubsup>") && !m.contains("<munderover>"),
                "\\" + cmd + " sets its limits beside: " + m);
        }
        // One limit only: msub / msup, never munder / mover.
        String lower = LatteX.toMathML("\\oint_C f");
        assertTrue(lower.contains("<msub><mo>\u222E</mo>") && !lower.contains("<munder>"), lower);
        String upper = LatteX.toMathML("\\int^b f");
        assertTrue(upper.contains("<msup><mo>\u222B</mo>") && !upper.contains("<mover>"), upper);
        // An explicit \limits still stacks an integral's limits, as in the SVG.
        String forced = LatteX.toMathML("\\int\\limits_0^1 f");
        assertTrue(forced.contains("<munderover><mo>\u222B</mo>"), "\\limits stacks: " + forced);
        // A non-integral operator keeps the default above/below form.
        assertTrue(LatteX.toMathML("\\prod_{i=1}^n x").contains("<munderover>"));
    }

    @Test
    void aLabelledBraceKeepsItsBrace() {
        // Audit cb3b4518 F5. stackMathML emitted the brace <mo> only when the label was null,
        // so \underbrace{x+y}_{n} came out as <munder><mrow>x+y</mrow><mi>n</mi></munder>: the
        // label sat directly under the base and the brace itself was gone. The SVG layout
        // stacks base, brace, label (LayoutEngine.stackBox); MathML now nests the same way.
        String ub = LatteX.toMathML("\\underbrace{x+y}_{n}");
        int inner = ub.indexOf("<munder><munder>");
        int brace = ub.indexOf("<mo>&#9183;</mo></munder>");
        int label = ub.indexOf("<mi>n</mi>");
        assertTrue(inner >= 0 && brace > inner && label > brace,
            "underbrace nests base, brace, label: " + ub);
        assertWellFormed(ub);
        String ob = LatteX.toMathML("\\overbrace{x+y}^{n}");
        inner = ob.indexOf("<mover><mover>");
        brace = ob.indexOf("<mo>&#9182;</mo></mover>");
        label = ob.indexOf("<mi>n</mi>");
        assertTrue(inner >= 0 && brace > inner && label > brace,
            "overbrace nests base, brace, label: " + ob);
        assertWellFormed(ob);
        // Control: a label-less brace is one level, brace directly on the base, as before.
        String bare = LatteX.toMathML("\\underbrace{x+y}");
        assertFalse(bare.contains("<munder><munder>"), bare);
        assertTrue(bare.contains("<mo>&#9183;</mo></munder>"), bare);
    }

    @Test
    void cancelFamilyMapsToMencloseStrikesAndCanceltoUsesTheArrowNotation() {
        // Plain strikes carry the diagonal-strike notations.
        assertTrue(LatteX.toMathML("\\cancel{x}").contains("notation=\"updiagonalstrike\""),
            LatteX.toMathML("\\cancel{x}"));
        assertTrue(LatteX.toMathML("\\bcancel{x}").contains("notation=\"downdiagonalstrike\""),
            LatteX.toMathML("\\bcancel{x}"));
        assertTrue(LatteX.toMathML("\\xcancel{x}")
                .contains("notation=\"updiagonalstrike downdiagonalstrike\""),
            LatteX.toMathML("\\xcancel{x}"));

        // \cancelto uses MathML 4's recommended northeastarrow notation (NOT a plain
        // diagonal strike), and still carries its target value accessibly (as the
        // superscript on the arrowed body).
        String to = LatteX.toMathML("\\cancelto{0}{x^2}");
        assertTrue(to.contains("notation=\"northeastarrow\""),
            "\\cancelto should use menclose northeastarrow: " + to);
        assertFalse(to.contains("notation=\"updiagonalstrike\""),
            "\\cancelto is an arrow, not a plain strike: " + to);
        assertTrue(to.contains("<msup>") && to.contains("<mn>0</mn>"),
            "\\cancelto keeps its target value accessible: " + to);
        assertWellFormed(to);
        assertWellFormed(LatteX.toMathML("\\xcancel{\\frac{a}{b}}"));
    }
}
