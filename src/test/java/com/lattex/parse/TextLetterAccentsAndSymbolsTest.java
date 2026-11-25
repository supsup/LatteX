package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.RenderResult;
import org.junit.jupiter.api.Test;

/// Plan 2d5a59ad (lattex project): commands a LaTeX author types for characters LatteX
/// already draws stop being refused. Found by the crew's census of the showcase pages
/// (2026-11-25): the accents H, v, u and c inside `\text`; `\S`; `\lgroup`/`\rgroup`;
/// `\blacktriangleright`/`\blacktriangleleft`. Each was "Unknown command" on main 5cb804f7,
/// while the raw characters rendered.
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
    }

    @Test
    void aLongerControlWordIsNotALetterAccent() {
        // \vs is one control word in TeX, not \v applied to s; it stays refused.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{\\vs}"));
        assertTrue(e.getMessage().contains("\\vs"), e.getMessage());
    }

    @Test
    void aCaronTheFontCannotDrawFailsLoudNamingIt() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{\\v{a}}"));
        assertTrue(e.getMessage().contains("\\v{a} has no precomposed character"), e.getMessage());
    }

    @Test
    void everyLetterAccentOnEveryAsciiLetterRendersAMappedGlyphOrFailsLoud() {
        int composed = 0;
        for (String accent : new String[] {"\\H", "\\v", "\\u", "\\c"}) {
            for (char base = 'A'; base <= 'z'; base++) {
                if (!Character.isLetter(base)) {
                    continue;
                }
                String latex = "\\text{" + accent + "{" + base + "}}";
                try {
                    MathParser.parse(latex);
                } catch (MathSyntaxException e) {
                    assertTrue(e.getMessage().contains("no precomposed character"), latex);
                    continue;
                }
                composed++;
                assertCleanRender(latex);
            }
        }
        assertEquals(57, composed, "the four letter-accent tables, pinned (4 + 19 + 12 + 22)");
    }

    // ---- text-mode word symbols ---------------------------------------------------------

    @Test
    void wordSymbolsDecodeAndSwallowTheFollowingSpaceAsTeXDoes() {
        assertEquals("Txt[ROMAN](§3, Große, Øre, łódź, ¶, æ)",
            pp("\\text{\\S 3, Gro\\ss e, \\O re, \\l\\'od\\'z, \\P, \\ae}"));
    }

    @Test
    void everyWordSymbolRendersARealGlyph() {
        String[] words = {"S", "P", "ss", "o", "O", "ae", "AE", "oe", "OE", "aa", "AA", "l", "L",
            "dag", "ddag", "copyright", "pounds"};
        for (String w : words) {
            assertCleanRender("\\text{\\" + w + "}");
        }
        assertEquals(17, words.length);
    }

    // ---- math-mode symbols and delimiters -------------------------------------------------

    @Test
    void sectionAndPilcrowWorkInMath() {
        assertCleanRender("\\S 3.2");
        assertCleanRender("\\P");
    }

    @Test
    void blackTrianglesRenderAndAreRelationsLikeVartriangleright() {
        assertCleanRender("a \\blacktriangleright b");
        assertCleanRender("a \\blacktriangleleft b");
        String var = pp("a \\vartriangleright b");
        assertEquals(var.replace("U+22B3", "U+25B8"), pp("a \\blacktriangleright b"),
            "same atom class as \\vartriangleright, glyph U+25B8");
        assertEquals(var.replace("U+22B3", "U+25C2"), pp("a \\blacktriangleleft b"),
            "same atom class as \\vartriangleright, glyph U+25C2");
    }

    @Test
    void groupBracketsRenderAsSymbolsAndAsStretchedDelimiters() {
        assertCleanRender("\\lgroup x \\rgroup");
        assertCleanRender("\\left\\lgroup \\frac{a}{b} \\right\\rgroup");
        assertCleanRender("\\bigl\\lgroup x \\bigr\\rgroup");
        assertEquals(pp("\\langle x \\rangle").replace("U+27E8", "U+27EE").replace("U+27E9", "U+27EF"),
            pp("\\lgroup x \\rgroup"), "open and close classes like \\langle and \\rangle");
    }

    @Test
    void theRawCharactersAndTheCommandsDrawTheSameGlyph() {
        assertEquals(LatteX.render("\u00A7"), LatteX.render("\\S"));
    }
}
