package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.Diagnostics;
import com.lattex.api.LatteX;
import com.lattex.api.Outcome;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * "Did you mean?" fuzzy suggestions on an unknown command / environment: the
 * {@link FuzzyMatch} edit-distance helper in isolation, and the two throw sites
 * (unknown command in {@link MathParser}, unknown environment in
 * {@link EnvironmentParser}) that append a nearest-match hint to their message.
 */
class FuzzyMatchTest {

    // ------------------------------------------------------------------
    // FuzzyMatch helper — unit-tested directly.
    // ------------------------------------------------------------------

    @Test
    void editDistanceKnownCases() {
        assertEquals(0, FuzzyMatch.editDistance("frac", "frac"));
        assertEquals(1, FuzzyMatch.editDistance("fract", "frac")); // one deletion
        assertEquals(1, FuzzyMatch.editDistance("alligned", "aligned")); // one deletion
        assertEquals(2, FuzzyMatch.editDistance("qux", "cup")); // sub q->c, sub x->p
    }

    @Test
    void editDistanceClassic() {
        // Classic textbook example: kitten -> sitting is 3 (sub k->s, sub e->i, insert g).
        assertEquals(3, FuzzyMatch.editDistance("kitten", "sitting"));
        assertEquals(3, FuzzyMatch.editDistance("", "abc")); // pure insertions
        assertEquals(3, FuzzyMatch.editDistance("abc", "")); // pure deletions
    }

    @Test
    void nearestReturnsWithinThreshold() {
        Optional<String> hit = FuzzyMatch.nearest("fract", List.of("frac", "sqrt", "sum"));
        assertTrue(hit.isPresent());
        assertEquals("frac", hit.get());
    }

    @Test
    void nearestReturnsEmptyWhenNothingClose() {
        // "qux" is edit-distance >= 2 from all of these; threshold for length 3 is 1.
        assertTrue(FuzzyMatch.nearest("qux", List.of("frac", "sqrt", "sum")).isEmpty());
    }

    @Test
    void nearestGivesNothingForVeryShortToken() {
        // Length 1-2 tokens get a bound of 0, so they are never "corrected" to noise.
        assertTrue(FuzzyMatch.nearest("q", List.of("a", "b", "pi")).isEmpty());
    }

    // ------------------------------------------------------------------
    // Unknown command throw site (MathParser).
    // ------------------------------------------------------------------

    @Test
    void unknownCommandSuggestsNearest() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\fract{a}{b}"));
        assertTrue(e.getMessage().contains("did you mean"),
            "expected a suggestion, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("\\frac"),
            "expected \\frac to be the nearest hit, got: " + e.getMessage());
    }

    @Test
    void unknownCommandWithNothingCloseHasNoSuggestion() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\qux"));
        assertTrue(e.getMessage().startsWith("Unknown command: \\qux"));
        assertFalse(e.getMessage().contains("did you mean"),
            "should not suggest garbage for a far-off token, got: " + e.getMessage());
    }

    /**
     * Non-vacuity guard: the suggestion is genuinely APPENDED, not baked into the
     * fixture. If the suggestion logic were removed, the message would be exactly the
     * bare prefix and this assertion would fail.
     */
    @Test
    void unknownCommandMessageIsLongerThanBarePrefix() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\fract"));
        String bare = "Unknown command: \\fract";
        assertFalse(e.getMessage().equals(bare),
            "suggestion logic appears to be a no-op, got exactly: " + e.getMessage());
        assertTrue(e.getMessage().length() > bare.length());
    }

    // ------------------------------------------------------------------
    // Unknown environment throw site (EnvironmentParser).
    // ------------------------------------------------------------------

    @Test
    void unknownEnvironmentSuggestsNearest() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{alligned} a &= b \\end{alligned}"));
        assertTrue(e.getMessage().contains("did you mean"),
            "expected an environment suggestion, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("aligned"),
            "expected 'aligned' to be the nearest env, got: " + e.getMessage());
    }

    @Test
    void unknownEnvironmentWithNothingCloseHasNoSuggestion() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{zzzzzz} a \\end{zzzzzz}"));
        assertFalse(e.getMessage().contains("did you mean"),
            "should not suggest garbage for a far-off env, got: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // Outcome classification: an unsupported construct is distinguishable
    // from malformed syntax.
    // ------------------------------------------------------------------

    @Test
    void unknownCommandClassifiedUnsupportedConstruct() {
        Diagnostics d = LatteX.renderWithDiagnostics("\\fract").diagnostics();
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, d.outcome());
        assertTrue(d.message().contains("did you mean"));
    }

    @Test
    void malformedSyntaxStaysParseError() {
        // A genuine syntax error (unbalanced brace) must remain PARSE_ERROR, not get
        // swept into UNSUPPORTED_CONSTRUCT.
        Diagnostics d = LatteX.renderWithDiagnostics("\\frac{a}{b").diagnostics();
        assertEquals(Outcome.PARSE_ERROR, d.outcome());
    }
}
