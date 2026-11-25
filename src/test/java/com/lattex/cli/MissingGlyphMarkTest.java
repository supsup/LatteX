package com.lattex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.Outcome;
import com.lattex.api.RenderOptions;
import com.lattex.api.RenderResult;
import com.lattex.parse.MathSyntaxException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// Plan 14579fe4 (lattex project), part (b), rulings stafficy/35638 and 35665. A character
/// the bundled font has no glyph for is still drawn as a box by default (plan a1866884 stands),
/// but no longer silently: the SVG's aria-label ends with the reserved mark, the CLI warns on
/// stderr (exit status still 0, Fixpoint review of 6160f84: a host reads nonzero as 'leave the
/// source in place'), and strictGlyphs / --strict-glyphs turn the same condition into a failure.
/// The three faces are one condition, asserted to agree on the same inputs.
final class MissingGlyphMarkTest {

    private static final String BALLOT_X = "✗";      // no glyph in STIX Two Math, no base to rewrite to
    private static final String GEORGIAN_NAR = "ჼ";  // a script letter whose base the font lacks too
    private static final String CONTROL = "\\text{A report (missing glyph: U+1D62)}"; // Lattice 35668

    private record Result(int code, String out, String err) {
    }

    private static Result cli(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String label(String svg) {
        int a = svg.indexOf("aria-label=\"") + "aria-label=\"".length();
        return svg.substring(a, svg.indexOf('"', a));
    }

    @Test
    void theLabelEndsWithTheReservedMarkInFirstAppearanceOrderEachOnce() {
        assertTrue(label(LatteX.render("x " + BALLOT_X)).endsWith(" (missing glyph: U+2717)"));
        String two = label(LatteX.render(BALLOT_X + "+" + BALLOT_X + "+" + GEORGIAN_NAR));
        assertTrue(two.endsWith(" (missing glyph: U+2717, U+10FC)"), two);
        assertEquals("(missing glyph: U+", LatteX.MISSING_GLYPH_MARK);
    }

    @Test
    void aCleanRenderNeverCarriesTheMarkEvenWhenItsTextSpellsIt() {
        String svg = LatteX.render(CONTROL);
        assertFalse(svg.contains(LatteX.MISSING_GLYPH_MARK), label(svg));
        assertTrue(label(svg).contains("(missing glyph​: U+1D62)"), "the author's words survive, unvoiced ZWSP after 'glyph'");
        assertEquals("", LatteX.renderWithDiagnostics(CONTROL).diagnostics().detail());
        assertFalse(LatteX.render("x^2").contains(LatteX.MISSING_GLYPH_MARK));
    }

    @Test
    void theMarkCannotBeForgedThroughAStrippedCharacterOrAnAuthorLabel() {
        // Fixpoint review of 6160f84: a control character between 'glyph' and ':' was stripped
        // AFTER the guard, rejoining the spelling; an a11y.label reached the styled span unguarded
        String forged = label(LatteX.render("\\text{A report (missing glyph\u0001: U+1D62)}"));
        assertFalse(forged.contains("(missing glyph: U+1D62)"), forged);
        String styled = LatteX.renderStyledHtml("\\lx[a11y.label=\"(missing glyph: U+0041)\"]{x^2}");
        assertFalse(styled.contains(LatteX.MISSING_GLYPH_MARK), styled);
        assertTrue(styled.contains("(missing glyph\u200B: U+0041)"), "the author's words survive, unvoiced ZWSP");
    }

    @Test
    void theMarkNamesTheCharacterTheAuthorTyped() {
        // Fixpoint review of 6160f84: in a rewritten cluster, a script letter whose base the
        // font also lacks was replaced by that base, so the mark named U+10DC, never typed
        assertTrue(label(LatteX.render("x\u1D62" + GEORGIAN_NAR)).endsWith(" (missing glyph: U+10FC)"),
            label(LatteX.render("x\u1D62" + GEORGIAN_NAR)));
    }

    @Test
    void theLibraryKeepsPlanA1866884ByDefaultAndFailsWhenStrict() {
        RenderResult lenient = LatteX.renderWithDiagnostics(BALLOT_X);
        assertEquals(Outcome.OK, lenient.diagnostics().outcome());
        assertEquals("unmapped-glyph:U+2717", lenient.diagnostics().detail());
        RenderOptions strict = RenderOptions.defaults().withStrictGlyphs(true);
        MathSyntaxException e = assertThrows(MathSyntaxException.class, () -> LatteX.render(BALLOT_X, strict));
        assertTrue(e.getMessage().contains("U+2717"), e.getMessage());
        RenderResult failed = LatteX.renderWithDiagnostics(BALLOT_X, strict);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, failed.diagnostics().outcome());
        assertEquals("", failed.svg());
        assertEquals(LatteX.render("x^2"), LatteX.render("x^2", strict), "strict changes nothing on a clean render");
        // the styled and seam APIs honour the same flag (Lattice review of 4d0f03a)
        assertThrows(MathSyntaxException.class, () -> LatteX.renderStyledHtml(BALLOT_X, strict));
        assertTrue(LatteX.tryRenderMath(BALLOT_X, strict).isEmpty());
        assertTrue(LatteX.tryRenderMath(BALLOT_X).isPresent(), "lenient by default");
        // a host that ALSO opted in to the rendered error card gets that card, not an empty SVG
        RenderResult card = LatteX.renderWithDiagnostics(BALLOT_X, strict.withRenderedErrors(true));
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, card.diagnostics().outcome());
        assertTrue(card.svg().startsWith("<svg"), "the opted-in card is drawn");
        assertFalse(card.svg().contains(LatteX.MISSING_GLYPH_MARK), "the card draws its message, not the box");
    }

    @Test
    void theCliWarnsWithExitZeroOrFailsWithStrictGlyphs() {
        Result warn = cli("", "x " + BALLOT_X);
        assertEquals(0, warn.code(), "a warning never changes the exit status: " + warn.err());
        assertEquals("lattex: warning: no glyph for U+2717 (drawn as a box)" + System.lineSeparator(), warn.err());
        assertEquals(LatteX.render("x " + BALLOT_X) + System.lineSeparator(), warn.out(), "the SVG is still written");
        Result strict = cli("", "--strict-glyphs", "x " + BALLOT_X);
        assertEquals(1, strict.code());
        assertEquals("", strict.out());
        assertTrue(strict.err().contains("lattex: error: no glyph for U+2717 (--strict-glyphs)"), strict.err());
        assertEquals(0, cli("", "--strict-glyphs", "x^2").code());
    }

    @Test
    void theBatchNamesEachRecordByItsOneBasedNumber() {
        String in = "x^2\n" + BALLOT_X + "\nx" + GEORGIAN_NAR + "\n" + CONTROL + "\n";
        Result warn = cli(in, "--batch");
        assertEquals(0, warn.code(), warn.err());
        assertEquals("lattex: warning: record 2: no glyph for U+2717 (drawn as a box)" + System.lineSeparator()
            + "lattex: warning: record 3: no glyph for U+10FC (drawn as a box)" + System.lineSeparator(), warn.err());
        assertEquals(4, warn.out().split("\0", -1).length - 1, "all four records emitted");
        Result strict = cli(in, "--batch", "--strict-glyphs");
        assertEquals(1, strict.code());
        String[] recs = strict.out().split("\0", -1);
        assertTrue(recs[0].startsWith("<svg"));
        assertEquals("lattex: error: record 2: no glyph for U+2717 (--strict-glyphs)", recs[1]);
        assertEquals("lattex: error: record 3: no glyph for U+10FC (--strict-glyphs)", recs[2]);
        assertTrue(recs[3].startsWith("<svg"), "the control renders cleanly under strict");
    }

    @Test
    void theThreeFacesAgreeOnEveryInput() {
        String[] inputs = {"x^2", BALLOT_X, "x" + GEORGIAN_NAR, CONTROL, "xᵢ", "͸", "a✗b✗"};
        RenderOptions strict = RenderOptions.defaults().withStrictGlyphs(true);
        for (String in : inputs) {
            boolean label = LatteX.render(in).contains(LatteX.MISSING_GLYPH_MARK);
            boolean detail = !LatteX.renderWithDiagnostics(in).diagnostics().detail().isEmpty();
            Result plain = cli("", in);
            assertEquals(0, plain.code(), in + ": a warning never changes the exit status");
            boolean warned = plain.err().contains("lattex: warning: no glyph for ");
            boolean strictFails;
            try {
                LatteX.render(in, strict);
                strictFails = false;
            } catch (MathSyntaxException e) {
                strictFails = true;
            }
            boolean cliStrictFails = cli("", "--strict-glyphs", in).code() == 1;
            assertEquals(label, detail, in + ": label mark vs diagnostic detail");
            assertEquals(label, warned, in + ": label mark vs CLI warning");
            assertEquals(label, strictFails, in + ": label mark vs strictGlyphs");
            assertEquals(label, cliStrictFails, in + ": label mark vs --strict-glyphs");
        }
    }
}
