package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/// Both directions of amsmath's optional `[t]`/`[b]`/`[c]` vertical-position
/// argument, which only the INNER environments take:
///
/// * `aligned` ACCEPTS one. It is meant to be embedded inside a larger
///   expression, where the position picks which of its own rows supplies the
///   baseline the surrounding material aligns to. LatteX renders the
///   environment standalone, so there is no surrounding baseline and a valid
///   argument is read and DISCARDED — but it must be genuinely parsed, never
///   left in the body.
/// * `split` REJECTS one, loud. Real amsmath gives `split` no `[pos]` option:
///   it is always used inside a host equation environment, which supplies the
///   baseline. Before this pin, `split[t]` parsed happily — the general grid
///   body loop absorbed `[`, `t`, `]` as three ordinary glyphs in the first
///   cell and rendered them as visible math. That is the silent-WRONG trap
///   these tests exist to keep closed.
///
/// The accept-side assertions are deliberately SEMANTIC (canonical tree with
/// the argument == canonical tree without it), not "it rendered something":
/// the buggy silent-absorption behaviour also renders something, so a
/// renders-without-throwing assertion would have passed against the defect.
class EnvironmentPositionArgTest {

    // ---- aligned ACCEPTS [t]/[b]/[c] -------------------------------------

    @Test
    void alignedAcceptsEachPositionLetterAndLeavesNoResidue() {
        String bare = MathParserTest.pp(
            MathParser.parse("\\begin{aligned} x &= y \\\\ z &= w \\end{aligned}"));
        for (String pos : new String[] {"t", "b", "c"}) {
            assertEquals(bare,
                MathParserTest.pp(MathParser.parse(
                    "\\begin{aligned}[" + pos + "] x &= y \\\\ z &= w \\end{aligned}")),
                "aligned[" + pos + "] must parse identically to aligned with no argument");
        }
    }

    @Test
    void alignedPositionArgumentNeverSurfacesInTheRenderedOutput() {
        String svg = LatteX.render("\\begin{aligned}[t] x &= y \\end{aligned}");
        assertTrue(svg.startsWith("<svg"), "well-formed SVG");
        String aria = ariaOf(svg);
        assertFalse(aria.contains("["), "no stray bracket in the label: " + aria);
        // Strongest form: the accessible label with the argument is byte-identical to
        // the label without it, so nothing about the argument leaked into the render.
        assertEquals(ariaOf(LatteX.render("\\begin{aligned} x &= y \\end{aligned}")), aria);
    }

    @Test
    void alignedStillWorksWithNoPositionArgumentAtAll() {
        // The argument is OPTIONAL — the accept path must not have made it required.
        assertEquals("aligned equations of 1 rows and 2 columns; row 1: x, = y", ariaOf(
            LatteX.render("\\begin{aligned} x &= y \\end{aligned}")));
    }

    @Test
    void alignedStillRejectsANonPositionBracket() {
        // Narrowing takesPositionArg must not have loosened aligned's own discipline.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{aligned}[q] x &= y \\end{aligned}"));
        assertTrue(e.getMessage().contains("aligned"), e.getMessage());
        assertTrue(e.getMessage().contains("position"), e.getMessage());
    }

    // ---- split REJECTS [t]/[b]/[c] ---------------------------------------

    @Test
    void splitRejectsEachPositionLetterLoudly() {
        for (String pos : new String[] {"t", "b", "c"}) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(
                    "\\begin{split}[" + pos + "] x &= y \\\\ z &= w \\end{split}"),
                "split[" + pos + "] must not parse");
            assertTrue(e.getMessage().contains("split"), e.getMessage());
            assertTrue(e.getMessage().contains("position"), e.getMessage());
        }
    }

    @Test
    void splitRejectsAnInvalidBracketArgumentToo() {
        // The rejection is of the BRACKET, not of the letters t/b/c specifically —
        // there is no split argument to be well-formed, so [q] fails the same way.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{split}[q] x &= y \\end{split}"));
        assertTrue(e.getMessage().contains("split"), e.getMessage());
    }

    @Test
    void splitWithNoPositionArgumentIsUnaffected() {
        String svg = LatteX.render("\\begin{split} x &= y \\\\ z &= w \\end{split}");
        assertTrue(svg.startsWith("<svg"), "well-formed SVG");
        assertEquals("aligned equations of 2 rows and 2 columns; row 1: x, = y; row 2: z, = w",
            ariaOf(svg));
    }

    @Test
    void aBracketInsideTheSplitBodyIsStillOrdinaryContent() {
        // MIXED fixture: the rejection must fire only on a bracket IMMEDIATELY after
        // \begin{split}. A bracket in the body is legitimate math and must survive —
        // without this positive instance the rejection test above would still pass if
        // split had been made to reject brackets everywhere.
        assertEquals(
            MathParserTest.pp(MathParser.parse("\\begin{split} x &= [y] \\end{split}")),
            MathParserTest.pp(MathParser.parse("\\begin{split} x &= [y] \\end{split}")));
        String svg = LatteX.render("\\begin{split} x &= [y] \\end{split}");
        assertTrue(ariaOf(svg).contains("["), "body bracket must render: " + ariaOf(svg));
    }

    // ---- negative control -------------------------------------------------

    @Test
    void otherAlignFamilyEnvironmentsAreUntouchedByThisChange() {
        // align/gather never took a position argument and this change must not have
        // given them one, nor started rejecting brackets on their behalf. Whatever
        // they did with a leading bracket before, they still parse their ordinary
        // bodies exactly as they always have.
        assertTrue(ariaOf(LatteX.render("\\begin{align} x &= y \\end{align}"))
            .contains("row 1: x, = y"));
        assertTrue(ariaOf(LatteX.render("\\begin{gather} x = y \\end{gather}"))
            .contains("row 1: x = y"));
    }

    private static String ariaOf(String svg) {
        int i = svg.indexOf("aria-label=\"");
        int j = svg.indexOf('"', i + "aria-label=\"".length());
        return svg.substring(i + "aria-label=\"".length(), j);
    }
}
