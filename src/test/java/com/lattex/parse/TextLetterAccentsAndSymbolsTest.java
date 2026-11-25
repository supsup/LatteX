package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.RenderOptions;
import com.lattex.api.RenderResult;
import java.text.Normalizer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Plan 2d5a59ad (lattex project): commands a LaTeX author types for characters LatteX
/// already draws stop being refused. Found by the crew's census of the showcase pages
/// (2026-11-25); each was "Unknown command" on main 5cb804f7 while the raw character rendered.
/// Shaped by Fixpoint's review of a3713921: no \S or \P (they stay free for user macros), no
/// stretched group brackets, a user macro wins over a text word symbol, and every pair and
/// word pinned to its character.
class TextLetterAccentsAndSymbolsTest {

    private static String pp(String latex) {
        return MathParserTest.pp(MathParser.parse(latex));
    }

    private static void assertCleanRender(String latex) {
        RenderResult r = LatteX.renderWithDiagnostics(latex);
        assertEquals(com.lattex.api.Outcome.OK, r.diagnostics().outcome(), latex + ": " + r.diagnostics().message());
        assertEquals("", r.diagnostics().detail(), latex + " must draw real glyphs, no .notdef box");
    }

    // ---- the four letter accents ------------------------------------------------------

    @Test
    void letterAccentsComposeInNames() {
        assertEquals("Txt[ROMAN](Erdős, Čech, Gödel, François, Ţiţeica, Ğ)",
            pp("\\text{Erd\\H{o}s, \\v{C}ech, G\\\"odel, Fran\\c{c}ois, \\c{T}i\\c{t}eica, \\u{G}}"));
    }

    @Test
    void aLetterAccentTakesTheNextLetterAfterSpacesAsTeXDoes() {
        assertEquals("Txt[ROMAN](Erdős)", pp("\\text{Erd\\H os}"));
        assertEquals("Txt[ROMAN](šum)", pp("\\text{\\v{s}um}"));
        assertEquals(pp("\\text{\\v{\\j}}"), pp("\\text{\\v\\j}"), "bare \\j as \\i already was");
    }

    @Test
    void aLongerControlWordIsNotALetterAccent() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{\\vs}"));
        assertTrue(e.getMessage().contains("\\vs"), e.getMessage());
    }

    @Test
    void aCaronTheFontCannotDrawFailsLoudSayingItIsTheFont() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{\\v{a}}"));
        assertTrue(e.getMessage().contains("\\v{a} is not drawn: the bundled font has no glyph for its precomposed character U+01CE"), e.getMessage());
        MathSyntaxException none = assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\text{\\\"q}"));
        assertTrue(none.getMessage().contains("has no precomposed character"), "Unicode has none: " + none.getMessage());
    }

    @Test
    void everyLetterAccentPairIsItsCanonicalCompositionAndDraws() {
        Map<String, Character> marks = Map.of("H", '̋', "v", '̌', "u", '̆', "c", '̧');
        int composed = 0;
        for (var m : marks.entrySet()) {
            for (char base = 'A'; base <= 'z'; base++) {
                if (!Character.isLetter(base)) {
                    continue;
                }
                String latex = "\\text{\\" + m.getKey() + "{" + base + "}}";
                String nfc = Normalizer.normalize("" + base + m.getValue(), Normalizer.Form.NFC);
                String got;
                try {
                    got = pp(latex);
                } catch (MathSyntaxException e) {
                    // the message names the right reason: the font lacks it, or Unicode has none
                    assertTrue(e.getMessage().contains(nfc.length() == 1 ? "is not drawn" : "has no precomposed character"),
                        latex + ": " + e.getMessage());
                    continue;
                }
                composed++;
                assertEquals(1, nfc.length(), latex + " composes canonically");
                assertEquals("Txt[ROMAN](" + nfc + ")", got, latex + " is the canonical composition");
                assertCleanRender(latex);
            }
        }
        assertEquals(57, composed, "the four letter-accent tables, pinned (4 + 19 + 12 + 22)");
    }

    @Test
    void aBareDotlessJNowWorksForTheOldAccentsToo() {
        // Fixpoint re-review of 9c1686c: refused on main ('expects one letter'), now as \^{\j}
        assertEquals(pp("\\text{\\^{\\j}}"), pp("\\text{\\^\\j}"));
        assertEquals("Txt[ROMAN](ĵ)", pp("\\text{\\^\\j}"));
    }

    @Test
    void aUserDefinedDotlessBaseIsRefusedNotTakenAsTheBuiltIn() {
        for (String src : new String[] {"\\newcommand{\\j}{x} \\text{\\^\\j}", "\\newcommand{\\j}{x} \\text{\\^{\\j}}",
                "\\newcommand{\\i}{x} \\text{\\'\\i}", "\\newcommand{\\i}{x} \\text{\\v{\\i}}"}) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class, () -> MathParser.parse(src));
            assertTrue(e.getMessage().contains("which is a user macro here"), src + ": " + e.getMessage());
        }
        assertEquals("Txt[ROMAN](í)", pp("\\text{\\'\\i}"), "without the macro, the dotless i as before");
    }

    @Test
    void anOldAccentTheFontCannotDrawNamesTheCodePoint() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\text{\\'G}"));
        assertTrue(e.getMessage().contains("\\'G is not drawn: the bundled font has no glyph for its precomposed character U+01F4"),
            e.getMessage());
    }

    @Test
    void aNewlineAfterAControlWordIsSkippedLikeASpace() {
        assertEquals("Txt[ROMAN](Große)", pp("\\text{Gro\\ss\ne}"));
        assertEquals("Txt[ROMAN](Čech)", pp("\\text{\\v\nCech}"), "a letter accent's argument past an end of line");
        assertEquals("Txt[ROMAN](é)", pp("\\text{\\'\ne}"), "and an old accent's, refused on main");
    }

    // ---- text-mode word symbols ---------------------------------------------------------

    private static final Map<String, Character> WORDS = Map.ofEntries(
        Map.entry("ss", 'ß'), Map.entry("o", 'ø'), Map.entry("O", 'Ø'), Map.entry("ae", 'æ'),
        Map.entry("AE", 'Æ'), Map.entry("oe", 'œ'), Map.entry("OE", 'Œ'), Map.entry("aa", 'å'),
        Map.entry("AA", 'Å'), Map.entry("l", 'ł'), Map.entry("L", 'Ł'), Map.entry("dag", '†'),
        Map.entry("ddag", '‡'), Map.entry("copyright", '©'), Map.entry("pounds", '£'));

    @Test
    void everyWordSymbolIsItsCharacterAndDraws() {
        for (var w : WORDS.entrySet()) {
            String latex = "\\text{\\" + w.getKey() + "}";
            assertEquals("Txt[ROMAN](" + w.getValue() + ")", pp(latex), latex);
            assertCleanRender(latex);
        }
        assertEquals(15, WORDS.size());
    }

    @Test
    void wordSymbolsSwallowTheFollowingSpaceAsTeXDoes() {
        assertEquals("Txt[ROMAN](Große, Øre, łódź, æ)", pp("\\text{Gro\\ss e, \\O re, \\l\\'od\\'z, \\ae}"));
    }

    @Test
    void aUserMacroOfTheSameNameIsNotReadAsATextSymbol() {
        // Fixpoint review of a3713921: refused on main, and it must stay refused
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\newcommand{\\L}{\\mathbb{X}} \\text{in \\L here}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\newcommand{\\v}{x} \\text{\\v{s}}"));
        assertThrows(MathSyntaxException.class,
            () -> LatteX.render("\\text{in \\L here}", RenderOptions.defaults().withMacros(Map.of("L", "\\mathbb{X}"))));
        assertEquals("Txt[ROMAN](Łódź)", pp("\\text{\\L\\'od\\'z}"), "without the macro it decodes");
    }

    // ---- \S and \P stay free --------------------------------------------------------------

    @Test
    void theSectionAndPilcrowNamesStayFreeForUserMacros() {
        assertCleanRender("\\newcommand{\\P}{\\mathbb{P}} \\P(A)");
        assertCleanRender("\\def\\S{\\mathbb{S}} \\S^2");
        assertTrue(LatteX.render("\\P(A)", RenderOptions.defaults().withMacros(Map.of("P", "\\mathbb{P}"))).startsWith("<svg"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\S"), "not built in: unchanged from main");
    }

    // ---- math-mode symbols and delimiters -------------------------------------------------

    @Test
    void blackTrianglesRenderAndAreRelationsLikeVartriangleright() {
        assertCleanRender("a \\blacktriangleright b");
        assertCleanRender("a \\blacktriangleleft b");
        String var = pp("a \\vartriangleright b");
        assertEquals(var.replace("U+22B3", "U+25B8"), pp("a \\blacktriangleright b"));
        assertEquals(var.replace("U+22B3", "U+25C2"), pp("a \\blacktriangleleft b"));
    }

    @Test
    void theRawCharactersNowTakeTheirClassFromTheTable() {
        // Fixpoint review of a3713921: a deliberate spacing change, pinned. On main the raw
        // triangle was ordinary and the group brackets ordinary, so the minus was binary; now
        // the triangle is a relation and the brackets open and close.
        assertEquals("L(A(a,ORD) A(U+25B8,REL) A(b,ORD))", pp("a ▸ b"));
        assertEquals("L(A(a,ORD) A(U+25C2,REL) A(b,ORD))", pp("a ◂ b"));
        assertEquals(LatteX.render("a ◂ b"), LatteX.render("a \\blacktriangleleft b"));
        assertEquals(LatteX.render("a ▸ b"), LatteX.render("a \\blacktriangleright b"));
        assertEquals(pp("\\lgroup - x \\rgroup"), pp("⟮ - x ⟯"));
        assertTrue(pp("⟮ - x ⟯").startsWith("L(A(U+27EE,OPEN)"), pp("⟮ - x ⟯"));
    }

    @Test
    void groupBracketsSetAtNaturalSizeAndRefuseToStretch() {
        assertCleanRender("\\lgroup x \\rgroup");
        assertEquals(pp("\\langle x \\rangle").replace("U+27E8", "U+27EE").replace("U+27E9", "U+27EF"),
            pp("\\lgroup x \\rgroup"), "open and close classes like \\langle and \\rangle");
        for (String stretched : new String[] {"\\left\\lgroup x \\right\\rgroup", "\\bigl\\lgroup x \\bigr\\rgroup",
                "\\left( x \\right\\rgroup"}) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class, () -> MathParser.parse(stretched));
            assertTrue(e.getMessage().contains("cannot be stretched"), stretched + ": " + e.getMessage());
        }
    }

    @Test
    void theFourNewMathNamesAreReservedBuiltIns() {
        for (String name : new String[] {"lgroup", "rgroup", "blacktriangleright", "blacktriangleleft"}) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("\\newcommand{\\" + name + "}{x} x"));
            assertTrue(e.getMessage().contains("built-in"), name + ": " + e.getMessage());
        }
    }
}
