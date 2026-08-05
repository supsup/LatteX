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

    @Test
    void theReadmeCarriesNoVersionPinOnTheCorpusClaim() throws IOException {
        // The rotted half. "as of 0.7.0" in a CURRENT Status section is a milestone stamp that
        // ages every release; re-stating it as 0.11.0 would just reset the fuse. This pins its
        // absence so a future edit does not helpfully reintroduce one.
        String text = Files.readString(README, StandardCharsets.UTF_8);
        int idx = text.indexOf("100% of the wild corpus");
        assertTrue(idx >= 0, "the corpus claim moved or was reworded — this guard is now inert");
        String window = text.substring(idx, Math.min(text.length(), idx + 120));
        assertTrue(!window.contains("as of"),
            "the corpus claim carries a version pin again: \"" + window.strip() + "\" — a claim "
                + "in a CURRENT section should be current, not stamped with a release that ages");
    }
}
