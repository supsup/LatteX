package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * WHOLE-INPUT MATH DELIMITERS (plan 467f691d; crew RFC stafficy/16752, unanimous — Fixpoint
 * 16754 co-ranked it first among features, Lattice 16763, Marlow 16762).
 *
 * <p>THE DEFECT: {@code render("$x^2$")} SUCCEEDED and drew literal dollar glyphs — 2587 bytes
 * against 1247 for the bare body. The most common way anyone writes inline maths rendered
 * silently WRONG, while {@code \begin{equation}} correctly threw. The wrapper failed loud; the
 * delimiter failed silent, and the delimiter is the one everybody types.
 *
 * <p>THE RISK THIS FILE EXISTS TO BOUND: a strip that is too wide eats content. Every negative
 * control below was a REQUIRED condition of Marlow's vote, not an afterthought — the escaped
 * dollar, the mismatched pair, and the multi-pair input are each a distinct way this could have
 * silently deleted something the author wrote.
 */
class MathDelimiterWrapperTest {

    // ---- the fix ---------------------------------------------------------------------------

    @Test
    void aWholeInputDollarPairRendersExactlyAsTheBareBody() {
        // Byte-identity is the assertion, not "it renders": the delimiters must leave NO trace.
        assertEquals(LatteX.render("x^2"), LatteX.render("$x^2$"),
            "a whole-input $…$ wrapper renders exactly as the bare body");
    }

    @Test
    void theParenSpellingIsTreatedIdentically() {
        assertEquals(LatteX.render("x^2"), LatteX.render("\\(x^2\\)"),
            "\\(…\\) is the same wrapper in a different spelling");
    }

    @Test
    void surroundingWhitespaceDoesNotDefeatTheWrapper() {
        // Real pasted LaTeX carries whitespace. If the strip were anchored to charAt(0) of the
        // RAW string, "  $x^2$  " would fall through and render dollars again — the defect
        // surviving behind a space.
        assertEquals(LatteX.render("x^2"), LatteX.render("  $x^2$  "));
    }

    // ---- Marlow's binding negative controls (16762 item 3) ----------------------------------

    @Test
    void anEscapedDollarStillDrawsALiteralGlyph() {
        // THE control that proves stripping is scoped to a wrapper and has not become "delete
        // dollars". \$ is also the documented migration path for anyone who WANTED visible
        // dollar signs, so if this ever fails the release note is a lie too.
        String escaped = LatteX.render("\\$5");
        assertNotEquals(LatteX.render("5"), escaped,
            "an escaped dollar is content and must still render a glyph");
        assertTrue(escaped.contains("<path"), "and it is drawn, not dropped: " + escaped.length());
    }

    @Test
    void aMismatchedOrUnclosedDelimiterIsNotStripped() {
        // Unclosed: the opener is NOT a wrapper, so it is left alone rather than silently
        // swallowed. The two spellings then diverge, and BOTH outcomes are correct — pinned
        // separately because a single assertion would have hidden the difference.
        //
        // `$x^2` THROWS, with the caret on the dollar. It used to render with the dollar as a
        // literal glyph; since audit-20261012 finding 7 a `$` that is not a whole-input wrapper
        // is refused like TeX's other misplaced specials. Either way it is NOT stripped: a
        // strip would have rendered `x^2` and deleted a character the author wrote.
        MathSyntaxException unclosed = assertThrows(MathSyntaxException.class,
            () -> LatteX.render("$x^2"), "an unclosed $ must NOT strip");
        assertEquals(0, unclosed.offset(), "the refused character is the dollar itself");

        // `\(x^2` THROWS. Discovered by running it, not assumed: I first wrote this expecting a
        // render and the test failed. Failing loud is the better outcome and is now the pin —
        // an unclosed \( is an unknown command, which is exactly what it is.
        try {
            LatteX.render("\\(x^2");
            org.junit.jupiter.api.Assertions.fail("an unclosed \\( must not strip, and throws");
        } catch (RuntimeException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("\\("),
                "and it names the offending delimiter: " + expected.getMessage());
        }
    }

    @Test
    void twoSeparatePairsAreNotOneWrapper() {
        // The sharpest case. "$a$ + $b$" begins and ends with $, so a naive
        // starts-with-and-ends-with test would strip to "a$ + $b" — normalization EATING
        // content, which is the one failure this feature must never have.
        // Both spellings are refused now (a mid-formula $ is not drawn, audit-20261012
        // finding 7), so the pin is on WHAT was parsed: the whole source, first dollar
        // included. A strip would have parsed "a$ + $b" and put the caret at offset 1.
        MathSyntaxException twoPairs = assertThrows(MathSyntaxException.class,
            () -> LatteX.render("$a$ + $b$"));
        assertEquals("$a$ + $b$", twoPairs.source(),
            "the first and last $ are not a matched pair; stripping them corrupts the source");
        assertEquals(0, twoPairs.offset());
        assertEquals(1, assertThrows(MathSyntaxException.class,
            () -> LatteX.render("a$ + $b")).offset(), "control: the stripped spelling differs");
    }

    @Test
    void displayDelimitersAreOutOfScopeAndRefused() {
        // $$…$$ and \[…\] are DISPLAY maths. The crew agreed the inline pair only: accepting a
        // display delimiter and rendering it inline would accept a notation and then drop its
        // meaning — the same shape the RFC rejected for unnumbered `equation`. Pinned so the
        // exclusion is a decision rather than an oversight. Not stripped used to mean "drawn
        // as four literal dollar signs"; it now means refused, naming the delimiter
        // (audit-20261012 finding 7).
        MathSyntaxException display = assertThrows(MathSyntaxException.class,
            () -> LatteX.render("$$x^2$$"), "$$…$$ is display maths and is deliberately NOT stripped");
        assertEquals(0, display.offset());
        assertTrue(display.getMessage().contains("$$"), display.getMessage());
    }

    @Test
    void aWrapperAroundUnparseableInputStillFails() {
        // Stripping must not convert one error into another, or mask one. The body is what
        // fails, and it fails for its own reason.
        try {
            LatteX.render("$\\begin{nosuchenv} x \\end{nosuchenv}$");
            org.junit.jupiter.api.Assertions.fail("a bad body inside a wrapper must still throw");
        } catch (RuntimeException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("nosuchenv"),
                "and it must fail for the BODY's reason, not the wrapper's: "
                    + expected.getMessage());
        }
    }

    @Test
    void aBareDollarOrEmptyWrapperIsNotAWrapper() {
        // Degenerate lengths. "$" alone has no pair; "$$" is the display opener, not an empty
        // inline wrapper. Neither may strip into nothing.
        assertTrue(LatteX.render("\\$").contains("<path"), "a lone escaped dollar renders");
        MathSyntaxException empty = assertThrows(MathSyntaxException.class,
            () -> LatteX.render("$$"), "‘$$’ must not strip to empty");
        assertEquals("$$", empty.source());
    }
}
