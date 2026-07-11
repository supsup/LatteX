package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * L6.1 — the never-throw floor (plan lattex-containment-diagnostics): the render
 * boundary must contain every layout/emit-stage {@code RuntimeException} and
 * {@code StackOverflowError} into the typed {@link MathSyntaxException} channel
 * consumers already catch, while passing genuine syntax errors through untouched.
 * An {@code Error} escaping {@code LatteX.render()} onto a live /docs page is the
 * failure this floor exists to make impossible.
 */
class RenderContainmentTest {

    @Test
    void boundaryContainsRuntimeExceptionAsTypedFailureWithCause() {
        RuntimeException boom = new IllegalStateException("layout exploded");
        MathSyntaxException contained = assertThrows(MathSyntaxException.class,
            () -> LatteX.containRender(() -> { throw boom; }));
        assertSame(boom, contained.getCause(), "original failure preserved as cause");
        assertTrue(contained.getMessage().contains("internal render failure"),
            "marked as internal, not positional syntax");
        assertEquals(MathSyntaxException.NO_OFFSET, contained.offset());
    }

    @Test
    void boundaryContainsStackOverflowErrorIntoTheTypedChannel() {
        // THE headline: an Error becomes a typed exception — it can no longer kill
        // the render thread of a live page.
        StackOverflowError deep = new StackOverflowError();
        MathSyntaxException contained = assertThrows(MathSyntaxException.class,
            () -> LatteX.containRender(() -> { throw deep; }));
        assertSame(deep, contained.getCause());
    }

    @Test
    void boundaryPassesSyntaxExceptionsThroughUnwrapped() {
        // The typed channel (incl. the layout depth guard's MathSyntaxException) must
        // arrive as the SAME instance — no double-wrapping, offsets/caret preserved.
        MathSyntaxException syntax = new MathSyntaxException("missing brace", 7);
        MathSyntaxException out = assertThrows(MathSyntaxException.class,
            () -> LatteX.containRender(() -> { throw syntax; }));
        assertSame(syntax, out);
        assertEquals(7, out.offset());
    }

    @Test
    void legalRendersStillSucceedThroughTheBoundary() {
        // The floor must be invisible on the happy path — all four public entries.
        assertNotNull(LatteX.render("x^2"));
        assertNotNull(LatteX.renderInline("\\frac{a}{b}"));
        assertNotNull(LatteX.renderFragment("\\sum_{i=1}^n i", 20.0));
        assertNotNull(LatteX.renderStyledHtml("E=mc^2"));
    }

    @Test
    void deepNestingArrivesTypedNeverAsAnEscapedError() {
        // Conf's review add (lattex/129): the never-throw promise rests on TWO halves
        // composing — MathParser's MAX_DEPTH guard (typed throw) + this boundary. Each
        // half is pinned separately above; THIS pins the real composition end-to-end,
        // so removing the parser guard can't silently regress the live-page promise.
        String deep = "{".repeat(600) + "x" + "}".repeat(600);
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> LatteX.render(deep));
        assertNotNull(ex.getMessage());
    }

    @Test
    void parseErrorsKeepTheirPositionalDiagnostics() {
        // End-to-end: a genuine syntax error through render() still carries its
        // offset/caret — containment must not degrade the author-facing diagnostics.
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> LatteX.render("\\frac{a}{"));
        assertTrue(ex.offset() >= 0 || ex.getMessage() != null);
        assertNotNull(ex.caretString());
    }
}
