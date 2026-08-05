package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// The UNMAPPED-CODE-POINT guard (plan a1866884).
///
/// WHY THIS EXISTS. Measured before it was written, on lattex-0.11.0:
///
///     x + U+1F600 = y        -> outcome=OK "Rendered successfully."  5 <path> elements
///     x + y = z  (control)   -> outcome=OK "Rendered successfully."  5 <path> elements
///
/// The emoji case emits the SAME path count as the plain-ASCII control, so the unmapped
/// character is NOT dropped — it is rendered. STIX Two Math's `.notdef` is a non-empty
/// visible rectangle, so the reader gets a box, the API reports success, and the
/// `aria-label` still claims the original text. This is the silent-wrong-output class:
/// the failure that is never reported because nothing failed.
///
/// WHY THE SIGNAL RIDES ON `detail` AND NOT ON `outcome`. Two constraints meet here and
/// only one shape satisfies both:
///
///  1. `DiagnosticsParityDriftGuardTest` pins BOTH the `Outcome` enum's names and order
///     AND the `Diagnostics` core components against Sirentide (the lattex/126 parity
///     contract). A new enum value or a new record field breaks a consumer that switches
///     over one shared vocabulary — so neither is available.
///  2. A non-OK outcome is NOT non-fatal. `renderWithDiagnostics` replaces the SVG with an
///     error card whenever `outcome != OK` and the host opted into `renderErrors()`. A doc
///     build must not lose its formula over one stray character.
///
/// So `outcome` stays OK and the signal rides on `detail`, which is `""` on every clean
/// render today — that emptiness is what makes a populated `detail` a real discriminator
/// rather than a string convention. The acceptance criterion is "neither silently reports
/// plain OK"; an OK carrying a populated detail that names the code point is not silent.
///
/// COVERAGE IS PER-SITE ON PURPOSE. Three layout sites emit author-facing glyphs
/// (`atomBox`, the operator-name word run, `textRunBox`), and the third ALREADY tested
/// `gid == 0` — but only to fall back from a shaped variant to the plain glyph, proceeding
/// silently when the plain glyph is absent too. That is this repo's one-arm-of-a-two-arm
/// condition shape (cf. f42b236d): the author was demonstrably thinking about `gid == 0`
/// and guarded the arm in front of them. So each site gets its own test, and a mutant
/// restoring the unchecked call at ANY ONE of them must kill a test that names that site —
/// a shared assertion would let two sites hide behind the third.
class UnmappedGlyphDiagnosticTest {

    /// U+1F600 GRINNING FACE — far outside any math font's coverage.
    private static final String EMOJI = "😀";

    /// U+65E5 CJK UNIFIED IDEOGRAPH 日 — the realistic case: a paste from a
    /// mixed-language document, not a deliberately hostile input.
    private static final String CJK = "日";

    // ------------------------------------------------------------------
    // The three author-facing sites, one test each.
    // ------------------------------------------------------------------

    @Test
    void bareUnmappedAtomIsNamed_atomBoxSite() {
        RenderResult r = LatteX.renderWithDiagnostics("x + " + EMOJI + " = y");
        assertEquals(Outcome.OK, r.diagnostics().outcome(),
            "a stray character must not fail the render — it is a diagnostic, not a refusal");
        assertTrue(r.diagnostics().detail().contains("U+1F600"),
            "atomBox emitted .notdef for U+1F600 and reported a plain OK with an empty detail; "
                + "the reader gets a visible box and nothing anywhere says so. detail was: \""
                + r.diagnostics().detail() + "\"");
        assertTrue(r.diagnostics().message().contains("U+1F600"),
            "the author-facing message must NAME the character — \"something was unmapped\" is "
                + "not actionable. message was: \"" + r.diagnostics().message() + "\"");
    }

    @Test
    void unmappedInsideTextRunIsNamed_textRunBoxSite() {
        RenderResult r = LatteX.renderWithDiagnostics("\\text{Hi " + CJK + " there}");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertTrue(r.diagnostics().detail().contains("U+65E5"),
            "textRunBox already checks gid == 0 for the shaped-variant fallback, then proceeds "
                + "silently when the PLAIN glyph is missing too — the unguarded second arm. "
                + "detail was: \"" + r.diagnostics().detail() + "\"");
    }

    @Test
    void unmappedInsideOperatorNameIsNamed_operatorNameSite() {
        RenderResult r = LatteX.renderWithDiagnostics("\\operatorname{" + CJK + "}(x)");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertTrue(r.diagnostics().detail().contains("U+65E5"),
            "the operator-name word run was absent from the original audit's site list and is "
                + "reachable with arbitrary author text via \\operatorname. detail was: \""
                + r.diagnostics().detail() + "\"");
    }

    @Test
    void unmappedSurvivesAColorWrapper_paintedWithCopy() {
        // A glyph is copied in exactly two places: Box's flattening translate and
        // PositionedGlyph.paintedWith. The flattening copy is exercised by every test above
        // (all glyphs reach the final Layout through it), but paintedWith only runs under a
        // \color wrapper — so without this test that pass-through is untested code, and a
        // mutant dropping it would survive. A guard that works "except when the character is
        // coloured" is the kind of partial fix this plan exists to prevent.
        RenderResult r = LatteX.renderWithDiagnostics("\\textcolor{red}{" + EMOJI + "}");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertTrue(r.diagnostics().detail().contains("U+1F600"),
            "a \\color-wrapped stray character is still a stray character. detail was: \""
                + r.diagnostics().detail() + "\"");
    }

    // ------------------------------------------------------------------
    // Negative controls. These must pass BOTH before and after the change —
    // a guard that fires on mapped input is worse than no guard.
    // ------------------------------------------------------------------

    @Test
    void fullyMappedMathStaysPlainOk() {
        RenderResult r = LatteX.renderWithDiagnostics("x + y = z");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("", r.diagnostics().detail(),
            "a fully-mapped formula must carry NO diagnostic — a false positive here would fire "
                + "on every document in the corpus");
    }

    @Test
    void mappedTextRunAndOperatorNameStayPlainOk() {
        // Aimed at the two sites this change touches: if the guard is written to fire on
        // "no source code point recorded" rather than on "glyph id 0", every \text and
        // \sin letter trips it. This is the test that catches that inversion.
        RenderResult r = LatteX.renderWithDiagnostics("\\sin x + \\text{for all } y");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("", r.diagnostics().detail(),
            "mapped text-run and operator-name letters must not be reported as unmapped");
    }

    // ------------------------------------------------------------------
    // The SVG must not move. The plan's acceptance is that render() output stays
    // byte-identical; only renderWithDiagnostics gains the signal.
    // ------------------------------------------------------------------

    @Test
    void theDiagnosticDoesNotChangeTheRenderedSvg() {
        String src = "x + " + EMOJI + " = y";
        assertEquals(LatteX.render(src), LatteX.renderWithDiagnostics(src).svg(),
            "the diagnostic is a report ABOUT the render, not a change TO it");
    }

    @Test
    void mappedRenderIsUnaffectedBySidecarsAndStaysIdentical() {
        // The design rejected setting `sourceCodePoint` at the text-run and operator-name
        // sites, even though that reads like a free win: SvgEmitter.glyphmap keys token
        // identity off exactly that field and documents text letters as never threading, so
        // populating it would silently change the data-lx-glyphmap sidecar on MAPPED input.
        // This pins that the two sites' glyphs stay out of the threading map.
        String svg = LatteX.render("\\text{aa} + \\sin x");
        int mapAt = svg.indexOf("data-lx-glyphmap");
        String map = mapAt < 0 ? "" : svg.substring(mapAt, Math.min(svg.length(), mapAt + 120));
        assertTrue(mapAt < 0 || !map.contains("61:"),
            "the repeated 'a' inside \\text{aa} must NOT form a thread group — text letters are "
                + "documented as NO_SOURCE and never thread. glyphmap fragment: " + map);
    }
}
