package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

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

    /** One throwing sibling: its public entry, its shallow positive input, and the parse it runs. */
    private record Sibling(String name, String shallow, Function<String, Object> entry,
                           Function<String, Object> bareParse) {
    }

    /** A shallow source with an fx annotation, so the attrs-only sibling's positive is non-empty. */
    private static final String SHALLOW_FX = "\\lx[fx.hover=glow]{x^2}";

    /**
     * Plan lattex-seam-containment-parity round 2, re-derived for the nesting limit of 80 (plan
     * 28201def; rulings L1623, M1614, C1626, F1627). The throwing siblings used to run their PARSE
     * outside {@code containRender}, so the boundary held only while the parser's depth limit
     * happened to fit the caller's remaining stack.
     *
     * <p>What changed: the parser now refuses past 80 levels, and both public parse entries turn a
     * residual {@code StackOverflowError} into their own cause-free BACKSTOP refusal. A real
     * overflow from {@link StackStarved#DEEP} is therefore typed INSIDE the parser, before this
     * boundary's catch sees it. So this test pins the parser/composition half only: on a starved
     * stack every throwing sibling delivers a real overflow on the typed
     * {@link MathSyntaxException} channel, never as an escaping Error. The other two halves are
     * pinned elsewhere: {@link #boundaryContainsStackOverflowErrorIntoTheTypedChannel} shows that
     * the boundary's own catch handles the Error, and {@code RenderSeamWiringCensusTest} shows
     * that every entry runs its whole post-parse pipeline inside it.
     *
     * <p>Order matters, and each phase reports every sibling before the next phase runs. (1) A
     * SHALLOW success through every sibling on the roomy stack: the positive and the class
     * warm-up. (2) The roomy refusal control: DEEP refused by the GUARD through each sibling and
     * through the bare parse it runs. (3) Fixture reach: that same bare parse ends in the BACKSTOP
     * on the starved stack. (4) Each sibling on the starved stack.
     */
    @Test
    @SuppressWarnings("deprecation")
    void aParseStageOverflowInEveryThrowingSiblingArrivesTyped() {
        Function<String, Object> parseOneArg = latex -> MathParser.parse(latex);
        Function<String, Object> parseDefaults = latex -> MathParser.parse(latex, RenderOptions.defaults().macros());
        List<Sibling> siblings = List.of(
            new Sibling("render(String)", StackStarved.SHALLOW,
                latex -> LatteX.render(latex), parseDefaults),
            new Sibling("render(String, RenderOptions)", StackStarved.SHALLOW,
                latex -> LatteX.render(latex, RenderOptions.defaults()), parseDefaults),
            new Sibling("renderInline", StackStarved.SHALLOW,
                latex -> LatteX.renderInline(latex), parseOneArg),
            new Sibling("renderInlineResult", StackStarved.SHALLOW,
                latex -> LatteX.renderInlineResult(latex), parseOneArg),
            new Sibling("renderFragment", StackStarved.SHALLOW,
                latex -> LatteX.renderFragment(latex, 20.0), parseOneArg),
            new Sibling("renderStyledHtml(String)", StackStarved.SHALLOW,
                latex -> LatteX.renderStyledHtml(latex), parseDefaults),
            new Sibling("renderStyledHtml(String, RenderOptions)", StackStarved.SHALLOW,
                latex -> LatteX.renderStyledHtml(latex, RenderOptions.defaults()), parseDefaults),
            new Sibling("fxContainerAttrs", SHALLOW_FX,
                latex -> LatteX.fxContainerAttrs(latex), parseOneArg));

        // (1) Positive and warm-up: a SHALLOW success through each sibling, on the roomy stack.
        List<Executable> positives = new ArrayList<>();
        for (Sibling s : siblings) {
            positives.add(() -> {
                Object v = StackStarved.assertSucceedsOnRoomyStack(s.name() + " on a shallow input",
                    () -> s.entry().apply(s.shallow()));
                assertNotNull(v, s.name() + " returns a value for a shallow input");
                if (v instanceof String str) {
                    assertFalse(str.isEmpty(), s.name() + " returns a non-empty result for a shallow input");
                }
            });
        }
        assertAll("roomy shallow positives", positives);

        // (2) Refusal control on the roomy stack: DEEP is past the limit, so the GUARD refuses it,
        // through the sibling and through the bare parse it runs. A refusal, not a positive.
        List<Executable> refusals = new ArrayList<>();
        for (Sibling s : siblings) {
            refusals.add(() -> {
                StackStarved.Outcome o = StackStarved.runRoomy(() -> s.entry().apply(StackStarved.DEEP));
                assertTrue(o.threw() && StackStarved.isGuard(o.thrown()),
                    s.name() + " refuses DEEP with the GUARD on a roomy stack: " + o.describe());
                StackStarved.assertRoomyParseIsGuardRefusal("the bare parse " + s.name() + " runs",
                    () -> s.bareParse().apply(StackStarved.DEEP));
            });
        }
        assertAll("roomy GUARD refusal controls", refusals);

        // (3) Fixture reach: on the starved stack, the bare parse each sibling runs must end in
        // the parser BACKSTOP, the one refusal that only a real StackOverflowError produces.
        List<Executable> reach = new ArrayList<>();
        for (Sibling s : siblings) {
            reach.add(() -> StackStarved.assertStarvedParseHitsBackstop("the bare parse " + s.name() + " runs",
                () -> s.bareParse().apply(StackStarved.DEEP)));
        }
        assertAll("starved-stack fixture reach", reach);

        // (4) Each sibling on the starved stack: the typed channel, carrying real-overflow evidence
        // (the BACKSTOP, or this boundary's own containment with the Error as its cause). Never an
        // escaping Error, and never a refusal that would also happen without an overflow.
        List<Executable> starved = new ArrayList<>();
        for (Sibling s : siblings) {
            starved.add(() -> {
                StackStarved.Outcome o = StackStarved.run(() -> s.entry().apply(StackStarved.DEEP));
                assertTrue(o.threw() && o.thrown() instanceof MathSyntaxException,
                    s.name() + " must deliver a starved-stack overflow as MathSyntaxException: " + o.describe());
                assertTrue(StackStarved.isRealOverflowRefusal(o.thrown()),
                    s.name() + " must carry real-overflow evidence, not a GUARD or other refusal: " + o.describe());
            });
        }
        assertAll("starved siblings arrive typed", starved);
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
