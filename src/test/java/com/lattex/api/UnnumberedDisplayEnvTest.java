package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

/**
 * EXPLICITLY UNNUMBERED DISPLAY WRAPPERS — {@code equation*} and {@code displaymath} (plan
 * 4f1ffc87; crew RFC stafficy/16752, unanimous).
 *
 * <p>LatteX already renders every construct INSIDE such a wrapper; only the outermost container
 * was unknown, so pasting real LaTeX from a paper threw {@code Unknown environment}. These two
 * spellings promise NO equation number, so accepting them drops nothing and owes no caveat.
 *
 * <p><strong>The numbered {@code equation} is deliberately NOT here</strong> and still throws.
 * The crew made it binding that a numbered form must state or implement its numbering rather
 * than drop it silently; the complication is that {@code align}/{@code gather}/{@code multline}/
 * {@code eqnarray} already drop it silently today. That fork is with the reviewer at lattex/865
 * — {@link #theNumberedFormIsStillRefusedPendingTheCrewRuling} pins the current state so the
 * gap is visible rather than forgotten.
 *
 * <p><strong>Why the corpus never caught any of this:</strong> LatteX's 502-row wild corpus was
 * harvested as bare BODIES, so a ratchet sitting at 502/502 is structurally blind to a missing
 * outer wrapper. Green was never evidence here.
 */
class UnnumberedDisplayEnvTest {

    @Test
    void equationStarRendersTheSameGLYPHSAtTheSameSizeAsItsBareBody() {
        // I first asserted BYTE-IDENTITY here and it FAILED, which is the useful part. These
        // wrappers are implemented as a one-row GATHER, and that container re-centres the row:
        // same 6 glyph paths, same width (144.4942) and height (36.832), but the viewBox
        // y-origin moves from -34.432 to -28.736 — a 5.696-unit baseline shift.
        //
        // Pinned as it actually is rather than weakened to something vague. The wrapper is
        // transparent to CONTENT and SIZE, not to baseline origin, and a reader of this file
        // should know which of those it may rely on.
        String bare = LatteX.render("E = mc^2");
        String wrapped = LatteX.render("\\begin{equation*} E = mc^2 \\end{equation*}");
        assertEquals(countPaths(bare), countPaths(wrapped),
            "same glyphs — the wrapper adds no marks of its own");
        assertTrue(wrapped.contains("width=\"144.4942\"") && wrapped.contains("height=\"36.832\""),
            "and the same rendered size as the bare body: " + wrapped.substring(0, 120));
    }

    @Test
    void displaymathIsTreatedIdenticallyToEquationStar() {
        // The two spellings mean the same thing in LaTeX, so they must not drift apart here.
        assertEquals(LatteX.render("\\begin{equation*} E = mc^2 \\end{equation*}"),
            LatteX.render("\\begin{displaymath} E = mc^2 \\end{displaymath}"));
    }

    @Test
    void theUnnumberedWrapperMatchesItsSHIPPEDSiblingExactly() {
        // The strongest available identity, and the one that says this introduces no NEW
        // behaviour: equation* is byte-identical to gather*, an environment that already ships.
        // So the baseline shift noted above is not something this change invents — it is what
        // the whole one-row display family already does.
        assertEquals(LatteX.render("\\begin{gather*} E = mc^2 \\end{gather*}"),
            LatteX.render("\\begin{equation*} E = mc^2 \\end{equation*}"),
            "equation* must behave exactly as the shipped gather*, not as a new dialect");
    }

    private static int countPaths(String svg) {
        return svg.split("<path").length;
    }

    @Test
    void anUnnumberedWrapperCarriesNoCaveat() {
        // The verdict must stay CLEAN, not merely OK. `detail` is "" on every clean render and a
        // populated one is the real discriminator (LatteX.java:200), so asserting the outcome
        // alone would pass even if the wrapper started apologising for nothing.
        RenderResult r = LatteX.renderWithDiagnostics(
            "\\begin{equation*} E = mc^2 \\end{equation*}");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("", r.diagnostics().detail(),
            "an explicitly unnumbered form drops nothing, so it owes no caveat: "
                + r.diagnostics().detail());
    }

    @Test
    void theWrapperCarriesRealContentNotJustAnEmptyBox() {
        // The positive control for every byte-identity assertion above: if BOTH sides rendered
        // an empty canvas they would still be equal, and equality would prove nothing.
        assertTrue(LatteX.render("\\begin{equation*} E = mc^2 \\end{equation*}").contains("<path"),
            "the wrapped body must actually draw glyphs");
    }

    @Test
    void theNumberedFormIsAcceptedAndSAYSItDroppedTheNumber() {
        // THE TRIPWIRE ABOVE FIRED AND THIS IS ITS DELIBERATE REPLACEMENT, not its deletion. The
        // prior test pinned `equation` as REFUSED while the fork at lattex/865 was open, so the
        // half-finished feature could not be mistaken for a finished one. The ruling landed
        // (lattex/868 via PROJECT/stafficy 25843): accept it, and SAY what was dropped.
        RenderResult r = LatteX.renderWithDiagnostics(
            "\\begin{equation} E = mc^2 \\end{equation}");

        assertEquals(Outcome.OK, r.diagnostics().outcome(), "the numbered form now renders");

        // THE CREW'S BINDING CONDITION, and the reason this test is not just an OK assertion:
        // unnumbered-and-SILENT is not support. Whichever branch was chosen had to SPEAK.
        assertFalse(r.diagnostics().caveats().isEmpty(),
            "accepting a numbered environment without saying the number was dropped is exactly the "
                + "honesty failure the crew's binding condition refused");
        assertTrue(r.diagnostics().caveats().stream().anyMatch(c -> c.contains("equation")),
            "and the caveat names the environment: " + r.diagnostics().caveats());

        // THE CHANNEL IS THE OTHER HALF OF THE RULING. detail is documented as the cleanliness
        // discriminator, "" on every clean render, and this IS a clean render - the SVG is exactly
        // what the page will serve. Routing the caveat through detail would have made every existing
        // empty-detail gate report dirt on correct output.
        assertEquals("", r.diagnostics().detail(),
            "the caveat must NOT arrive through detail, or a documented discriminator changes "
                + "meaning for every consumer already gating on it: " + r.diagnostics().detail());
    }

    @Test
    void theStarredFormStillOwesNoCaveatAndThatIsWhatMakesTheCaveatMeanSomething() {
        // THE CONTROL. Without it, a caveat emitted on every render would pass the test above while
        // telling a reader nothing - the same "a gate that fires on everything is not a gate" shape.
        RenderResult r = LatteX.renderWithDiagnostics(
            "\\begin{equation*} E = mc^2 \\end{equation*}");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertTrue(r.diagnostics().caveats().isEmpty(),
            "an EXPLICITLY unnumbered form promises no number, so it drops nothing and owes no "
                + "caveat: " + r.diagnostics().caveats());
    }

    @Test
    void aMalformedWrapperStillFails() {
        // Acceptance must not mask a structural error: an unterminated environment is still an
        // error, and it fails for its OWN reason rather than being swallowed by the new entry.
        try {
            LatteX.render("\\begin{equation*} E = mc^2");
            org.junit.jupiter.api.Assertions.fail("an unterminated wrapper must still throw");
        } catch (RuntimeException expected) {
            assertNotEquals("", String.valueOf(expected.getMessage()),
                "and it says something");
        }
    }
}
