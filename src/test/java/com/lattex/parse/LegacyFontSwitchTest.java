package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.CommandRegistry.GrammarKind;
import com.lattex.parse.CommandRegistry.Handler;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The legacy TeX 2.09 font switches — {@code {\rm …}} {@code {\bf …}}
 * {@code {\it …}} {@code {\cal …}} — plus {@code \textnormal}.
 *
 * <p>The load-bearing property is the GRAMMAR SHAPE, not the glyph table: these
 * are DECLARATIONS, so the font runs from the switch to the end of the enclosing
 * group and stops dead at the closing brace. Their {@code \mathbf}-style cousins
 * take one argument instead, and the two shapes disagree observably on
 * {@code \bf{x}y}. Every mapping assertion below is therefore paired with a scope
 * assertion.
 */
class LegacyFontSwitchTest {

    /** The code point of a formula that parses to exactly one atom. */
    private static int soleAtom(String latex) {
        MathNode node = MathParser.parse(latex);
        assertTrue(node instanceof Atom, latex + " should parse to a single atom, got " + node);
        return ((Atom) node).codePoint();
    }

    /**
     * The atom code points of a formula in source order. Grouping braces are
     * invisible in the tree (a group is just a nested list), so flattening keeps
     * these assertions about WHICH glyph each letter became rather than about the
     * incidental nesting depth a particular input produces.
     */
    private static List<Integer> atoms(String latex) {
        List<Integer> out = new java.util.ArrayList<>();
        collectAtoms(MathParser.parse(latex), out);
        assertFalse(out.isEmpty(), "no atoms parsed from: " + latex);
        return out;
    }

    private static void collectAtoms(MathNode node, List<Integer> out) {
        if (node instanceof Atom atom) {
            out.add(atom.codePoint());
        } else if (node instanceof MathList list) {
            for (MathNode item : list.items()) {
                collectAtoms(item, out);
            }
        }
    }

    /** The code point of atom {@code index} of a formula, in source order. */
    private static int atomAt(String latex, int index) {
        return atoms(latex).get(index);
    }

    // ------------------------------------------------------------------
    // Registry shape: a declaration is SWITCH grammar, not ONE_ARGUMENT.
    // ------------------------------------------------------------------

    @Test
    void theRegistryModelsTheSwitchesAsDeclarationsNotArgumentCommands() {
        for (String name : List.of("rm", "bf", "it", "cal")) {
            assertTrue(CommandRegistry.hasHandler(name, Handler.FONT_SWITCH),
                "\\" + name + " must dispatch through the font-switch handler");
            assertTrue(CommandRegistry.hasGrammar(name, GrammarKind.SWITCH),
                "\\" + name + " is a declaration: its grammar is SWITCH");
            assertFalse(CommandRegistry.hasGrammar(name, GrammarKind.ONE_ARGUMENT),
                "\\" + name + " must NOT be modelled as an argument-taking command");
        }
        // Negative control on the same probe: the modern cousins keep the argument
        // grammar, so the assertions above are discriminating rather than vacuous.
        for (String name : List.of("mathbf", "mathit", "mathcal")) {
            assertTrue(CommandRegistry.hasGrammar(name, GrammarKind.ONE_ARGUMENT),
                "\\" + name + " takes one argument");
            assertFalse(CommandRegistry.hasGrammar(name, GrammarKind.SWITCH), "\\" + name);
        }
        assertTrue(CommandRegistry.hasGrammar("textnormal", GrammarKind.TEXT_ARGUMENT));
    }

    // ------------------------------------------------------------------
    // They render at all (the wild-corpus shapes).
    // ------------------------------------------------------------------

    @Test
    void legacyFontSwitchesRender() {
        for (String latex : List.of(
                "{\\cal L}(\\alpha)",
                "{\\bf F} = m{\\bf a}",
                "{\\it O}(n \\log n)",
                "{\\rm d}x",
                "\\textnormal{for all } x")) {
            String svg = assertDoesNotThrow(() -> LatteX.render(latex), latex);
            assertTrue(svg.contains("<path"), "no glyph ink rendered for: " + latex);
        }
    }

    // ------------------------------------------------------------------
    // Mapped semantics — the same alphabets \mathbf/\mathit/\mathcal use.
    // ------------------------------------------------------------------

    @Test
    void mappedSemanticsMatchTheModernEquivalent() {
        assertEquals(soleAtom("\\mathbf{y}"), atomAt("x + {\\bf y} + z", 2));
        assertEquals(soleAtom("\\mathit{y}"), atomAt("x + {\\it y} + z", 2));
        assertEquals(soleAtom("\\mathcal{L}"), atomAt("x + {\\cal L} + z", 2));
        // Non-vacuity: those variant code points really are different from the base.
        assertNotEquals(soleAtom("y"), soleAtom("\\mathbf{y}"));
        assertNotEquals(soleAtom("y"), soleAtom("\\mathit{y}"));
        assertNotEquals(soleAtom("L"), soleAtom("\\mathcal{L}"));
    }

    @Test
    void romanSwitchIsUprightAndThereforeAnIdentityRemap() {
        // A bare math atom already draws its own (upright) code point in this
        // renderer, so \rm's whole job is the scoping. Pin BOTH halves: the atom is
        // untouched, and the rendered output is byte-identical to the bare letter.
        assertEquals((int) 'd', soleAtom("{\\rm d}"));
        assertEquals(LatteX.render("dx"), LatteX.render("{\\rm d}x"));
        // Control: the same probe DOES see a difference for a switch that remaps.
        assertNotEquals(LatteX.render("dx"), LatteX.render("{\\bf d}x"));
    }

    // ------------------------------------------------------------------
    // SCOPE — the declaration reaches the end of its group and no further.
    // ------------------------------------------------------------------

    @Test
    void theSwitchAffectsTheRestOfItsGroup() {
        // Everything after the switch, to the closing brace, is restyled — and only
        // that. One expression pins the whole scope in one shot.
        assertEquals(
            List.of(soleAtom("\\mathbf{x}"), soleAtom("\\mathbf{y}"), (int) 'z'),
            atoms("{\\bf x y} z"));
        // Content BEFORE the switch in the same group is untouched.
        assertEquals((int) 'a', atomAt("{a \\bf b}", 0));
        assertEquals(soleAtom("\\mathbf{b}"), atomAt("{a \\bf b}", 1));
    }

    @Test
    void theSwitchDoesNotLeakPastTheClosingBrace() {
        // The 'y' outside {\bf x} keeps the plain glyph.
        assertEquals(soleAtom("y"), atomAt("{\\bf x} y", 1));
        assertEquals(soleAtom("y"), atomAt("{\\it x} y", 1));
        assertEquals(soleAtom("L"), atomAt("{\\cal M} L", 1));
        // And the inside really WAS restyled, so the negative above is not vacuous.
        assertEquals(soleAtom("\\mathbf{x}"), atomAt("{\\bf x} y", 0));
        assertEquals(soleAtom("\\mathit{x}"), atomAt("{\\it x} y", 0));
        assertEquals(soleAtom("\\mathcal{M}"), atomAt("{\\cal M} L", 0));
    }

    @Test
    void aBracedGroupAfterTheSwitchIsNotTreatedAsItsArgument() {
        // THE discriminator between the declaration shape and the \mathbf shape:
        // \bf{x}y bolds x AND y (the braces are just a group inside the scope),
        // whereas \mathbf{x}y bolds only x.
        assertEquals(soleAtom("\\mathbf{x}"), atomAt("\\bf{x}y", 0));
        assertEquals(soleAtom("\\mathbf{y}"), atomAt("\\bf{x}y", 1));
        assertEquals(soleAtom("\\mathbf{x}"), atomAt("\\mathbf{x}y", 0));
        assertEquals((int) 'y', atomAt("\\mathbf{x}y", 1));
    }

    @Test
    void anUnbracedSwitchRunsToTheEndOfTheFormula() {
        assertEquals(soleAtom("\\mathbf{x}"), atomAt("\\bf x y", 0));
        assertEquals(soleAtom("\\mathbf{y}"), atomAt("\\bf x y", 1));
    }

    @Test
    void aCellSeparatorEndsTheScopeLikeAnyOtherSwitch() {
        // Same boundary rule as \displaystyle / \color: a matrix cell separator
        // stops the declaration, so the next cell is unstyled.
        MathNode bold = MathParser.parse("\\begin{matrix}\\bf a & b\\end{matrix}");
        assertTrue(bold instanceof MathNode.Matrix, bold.toString());
        List<List<MathNode>> rows = ((MathNode.Matrix) bold).rows();
        List<Integer> first = new java.util.ArrayList<>();
        List<Integer> second = new java.util.ArrayList<>();
        collectAtoms(rows.get(0).get(0), first);
        collectAtoms(rows.get(0).get(1), second);
        assertEquals(List.of(soleAtom("\\mathbf{a}")), first);
        assertEquals(List.of((int) 'b'), second,
            "the cell after '&' is outside the declaration's scope");
    }

    @Test
    void anInnerSwitchWinsInsideItsOwnGroup() {
        // Nesting mirrors \mathbf{\mathit{y}}: the inner alphabet has already
        // remapped the atom, and the outer style leaves a non-ASCII code point alone.
        assertEquals(soleAtom("\\mathbf{\\mathit{y}}"), soleAtom("{\\bf {\\it y}}"));
    }

    // ------------------------------------------------------------------
    // \textnormal
    // ------------------------------------------------------------------

    @Test
    void textnormalIsTheRomanTextCommand() {
        MathNode node = MathParser.parse("\\textnormal{for all}");
        assertTrue(node instanceof TextRun, "\\textnormal should lex as a text run: " + node);
        assertEquals("for all", ((TextRun) node).text());
        assertEquals(TextStyle.ROMAN, ((TextRun) node).style());
        // Text-mode spaces survive, exactly as for \text (the lexer path is shared).
        assertEquals(LatteX.render("\\text{for all } x"), LatteX.render("\\textnormal{for all } x"));
        // Control: a differently-styled text command does NOT match.
        assertNotEquals(LatteX.render("\\text{for all } x"), LatteX.render("\\textbf{for all } x"));
    }

    @Test
    void textnormalWithoutAnArgumentStillFailsLoud() {
        MathSyntaxException failure = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\textnormal x"));
        assertTrue(failure.getMessage().contains("\\textnormal"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // The new names join the built-in reservation, like every other descriptor.
    // ------------------------------------------------------------------

    @Test
    void theNewNamesAreReservedBuiltIns() {
        for (String name : List.of("rm", "bf", "it", "cal", "textnormal")) {
            assertFalse(CommandRegistry.userMacroMayClaim(name), "\\" + name);
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("x", Map.of(name, "y")));
            assertTrue(failure.getMessage().contains("built-in"), failure.getMessage());
        }
    }
}
