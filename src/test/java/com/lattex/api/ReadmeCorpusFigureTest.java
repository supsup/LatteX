package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Ties the README's wild-corpus figure to the ratchet's enforced floor (plan 398daca1
/// item 1).
///
/// WHY. The README's CURRENT Status section claimed "100% of the wild corpus (484/484) as
/// of 0.7.0" while the project sat at 0.11.0 and the ratchet enforced 502. The claim was
/// not even false — coverage really is 100% — which is what made it durable: a stale figure
/// that is still technically true understates breadth by ~18 formulas and pins a milestone
/// four releases behind HEAD, and no reader can tell it apart from a current one.
///
/// The plan's acceptance is explicit that the figure must be DERIVED rather than hand-copied
/// "so it cannot rot again the same way". Markdown cannot compute, so the derivation lives
/// here: the prose is pinned to {@link WildCorpusCoverageTest#PASS_SET_FLOOR}, and raising
/// the floor without updating the README is now a red test rather than a silent drift.
///
/// WHY THE VERSION PIN WAS REMOVED RATHER THAN UPDATED. "as of 0.7.0" was the half that
/// actually rotted. Rewriting it to "as of 0.11.0" would have re-armed the same trap for
/// 0.12.0 — a claim in a section headed CURRENT does not need a version stamp, it needs to
/// be current. Nothing here guards a version pin, because there should not be one to guard.
///
/// SCOPE, stated so it is not read as broader: this matches the two `N/N` corpus figures.
/// It does not audit any other README number.
class ReadmeCorpusFigureTest {

    private static final Path README = Path.of("README.md");

    /// `484/484 real-world formulas` and `(502/502)` — the corpus-figure shape, which is
    /// always a pair of identical counts because coverage is 100%.
    private static final Pattern CORPUS_FIGURE =
        Pattern.compile("(\\d{3,})/(\\d{3,})");

    @Test
    void readmeCorpusFigureMatchesTheEnforcedFloor() throws IOException {
        assertTrue(Files.exists(README), "README.md not found from the test working dir "
            + Path.of("").toAbsolutePath() + " — this guard is inert, not passing");
        String text = Files.readString(README, StandardCharsets.UTF_8);

        List<String> wrong = new ArrayList<>();
        int figures = 0;
        for (Matcher m = CORPUS_FIGURE.matcher(text); m.find(); ) {
            figures++;
            if (Integer.parseInt(m.group(1)) != WildCorpusCoverageTest.PASS_SET_FLOOR
                    || Integer.parseInt(m.group(2)) != WildCorpusCoverageTest.PASS_SET_FLOOR) {
                wrong.add(m.group(0));
            }
        }
        // NON-VACUITY. Without this, a README that lost its corpus figures entirely — or a
        // pattern that stopped matching — would pass by asserting over an empty set, which is
        // the exact silent-clean this plan is about.
        assertTrue(figures >= 2,
            "expected at least the two N/N corpus figures in README.md, found " + figures
                + " — the pattern has lost its anchor and this assertion is now vacuous");
        assertEquals(List.of(), wrong,
            "README corpus figure disagrees with the enforced ratchet floor ("
                + WildCorpusCoverageTest.PASS_SET_FLOOR + "). Raise the floor and the prose "
                + "together, or the README goes stale the way it did at 484/484 while the "
                + "corpus was already 502.");
    }

    /// A version pin: the `as of` clause in any casing, or a bare release stamp. Both corpus
    /// paragraphs contain ZERO version-like tokens today (verified before widening this), so
    /// keying on the token itself is safe and does not risk a false fail on legitimate prose.
    private static final Pattern VERSION_PIN =
        Pattern.compile("(?i)\\bas of\\b|\\b\\d+\\.\\d+(?:\\.\\d+)?\\b");

    /// Blank-line-delimited paragraphs that state a corpus figure.
    private static List<String> corpusParagraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n")) {
            if (CORPUS_FIGURE.matcher(p).find()) {
                out.add(p);
            }
        }
        return out;
    }

    @Test
    void noCorpusClaimCarriesAVersionPin() throws IOException {
        // THE ROTTED HALF. "as of 0.7.0" in a section headed CURRENT is a milestone stamp that
        // ages every release; re-stating it as 0.11.0 would just reset the fuse. This pins its
        // absence.
        //
        // SCOPED TO THE PARAGRAPH, NOT A FORWARD WINDOW — and this is Fixpoint's finding at
        // lattex/815, demonstrated rather than argued. My first version took
        // indexOf("100% of the wild corpus") and scanned 120 chars FORWARD, so a pin placed
        // BEFORE the phrase was invisible:
        //
        //     "...to SVG today, as of 0.12.0 — **100% of the wild corpus** (502/502)."
        //     -> :test EXECUTED (the guard really ran) -> BUILD SUCCESSFUL
        //
        // That matters more than an off-by-one because of where English puts the clause. The
        // shape that ALREADY rotted was trailing ("(484/484) as of 0.7.0"), which is why a
        // forward window caught it — but the natural way a future editor reintroduces it is
        // LEADING ("As of 0.12.0, LatteX renders 100% of the wild corpus"). I had pinned the
        // absence of the exact shape that already failed while the most likely NEXT shape
        // passed: fixing the instance and naming it the class.
        //
        // Keying on the PARAGRAPH containing the corpus FIGURE closes it in three ways a wider
        // window would not: position no longer matters, the anchor is the figure (which the
        // sibling test already pins) rather than a prose phrase that can be reworded, and any
        // phrasing of a version stamp is caught, not just the literal "as of".
        String text = Files.readString(README, StandardCharsets.UTF_8);
        List<String> paragraphs = corpusParagraphs(text);
        assertTrue(paragraphs.size() >= 2,
            "expected at least the two corpus paragraphs, found " + paragraphs.size()
                + " — the anchor has moved and this assertion is now vacuous");
        for (String p : paragraphs) {
            Matcher m = VERSION_PIN.matcher(p);
            // The message is built EAGERLY by assertTrue, so the matched text has to be captured
            // BEFORE asserting — calling m.group() inside the message throws IllegalStateException
            // on the passing path, turning a green guard into a red error for its own reasons.
            boolean pinned = m.find();
            String found = pinned ? m.group() : "";
            assertTrue(!pinned,
                "a corpus claim carries a version pin again (\"" + found + "\") — a claim in a "
                    + "CURRENT section should be current, not stamped with a release that ages. "
                    + "Offending paragraph: " + p.strip());
        }
    }
}
