package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.api.MathStyle;
import com.lattex.parse.MathSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Opt-in rendered diagnostic cards: compatibility, privacy, bounds, and
 * secondary-stage containment.
 */
class RenderedErrorCardTest {

    private static final RenderOptions CARD_ON =
        RenderOptions.defaults().withRenderedErrors(true);

    @Test
    void defaultAndExplicitFlagOffKeepTheHistoricalFailureShape() {
        String source = "\\frac{a}{";
        RenderResult historical = LatteX.renderWithDiagnostics(source);
        RenderResult explicit = LatteX.renderWithDiagnostics(
            source, RenderOptions.defaults().withRenderedErrors(false));
        assertEquals(historical, explicit);
        assertEquals("", historical.svg());
        assertEquals(Outcome.PARSE_ERROR, historical.diagnostics().outcome());
    }

    @Test
    void flagOnRendersACardAndPreservesTheOriginalDiagnostics() {
        String source = "\\frac{a}{";
        RenderResult off = LatteX.renderWithDiagnostics(source);
        RenderResult on = LatteX.renderWithDiagnostics(source, CARD_ON);

        assertFalse(on.svg().isBlank());
        assertEquals(off.diagnostics(), on.diagnostics());
        assertTrue(on.svg().startsWith("<svg "));
        assertTrue(on.svg().contains("aria-label=\"LatteX render error\""));
        assertTrue(on.svg().contains("<rect "), "fixed border uses renderer-owned rules");
        assertTrue(on.svg().length() < 500_000, "small bounded card, not a runaway document");
    }

    @Test
    void successIsByteIdenticalAcrossEveryHostOption() {
        RenderOptions options = new RenderOptions(
            1.4, Color.parse("#2457a7"), MathStyle.TEXT,
            java.util.Map.of("half", "\\frac{1}{2}"), true, true, true);
        String source = "\\lx[style.color=#1f7d72,style.scale=1.3]{\\half + x^2}";

        RenderResult result = LatteX.renderWithDiagnostics(source, options);
        assertEquals(Outcome.OK, result.diagnostics().outcome());
        assertEquals(LatteX.render(source, options), result.svg(),
            "macros, source style, scale, color, math style and host fluid stay exact");
    }

    @Test
    void sourceCannotEnableTheHostGateButCannotEraseAnOuterOptIn() {
        String attemptedAuthorGate = "\\lx[renderErrors=true]{x}";
        RenderResult authorOnly = LatteX.renderWithDiagnostics(attemptedAuthorGate);
        RenderResult hostEnabled = LatteX.renderWithDiagnostics(attemptedAuthorGate, CARD_ON);

        assertEquals("", authorOnly.svg(), "unknown author option cannot enable a card");
        assertNotEquals(Outcome.OK, authorOnly.diagnostics().outcome());
        assertFalse(hostEnabled.svg().isBlank(), "outer host opt-in still renders the failure");
        assertEquals(authorOnly.diagnostics(), hostEnabled.diagnostics());
    }

    @Test
    void cardTextUsesOnlyBoundedLegalRowsAndReanchorsTheCaret() {
        String source = "first line\n"
            + "a".repeat(120) + "🔭" + "b".repeat(220) + "\u0007tail\nlast";
        int offset = source.indexOf("🔭") + "🔭".length() + 80;
        Diagnostics diagnostics = new Diagnostics(
            Outcome.PARSE_ERROR, "parse",
            "message " + "m".repeat(240) + "\nsecond line",
            2, "DETAIL-SECRET", offset, "RAW-CARET-SECRET");

        RenderedErrorCard.CardText text =
            RenderedErrorCard.cardText(source, diagnostics);

        assertEquals("PARSE_ERROR", text.outcome());
        assertTrue(codePoints(text.message())
            <= RenderedErrorCard.MAX_MESSAGE_CODE_POINTS);
        assertTrue(codePoints(text.excerpt())
            <= RenderedErrorCard.MAX_SOURCE_EXCERPT_CODE_POINTS);
        assertTrue(codePoints(text.caret())
            <= RenderedErrorCard.MAX_CARET_CODE_POINTS);
        assertTrue(text.excerpt().startsWith("…"));
        assertTrue(text.excerpt().endsWith("…"));
        assertTrue(text.caret().endsWith("^"));
        assertTrue(text.caret().indexOf('^') > 1,
            "the reanchored caret must retain a non-zero bounded column");
        assertFalse(text.message().contains("\n"));
        assertFalse(text.excerpt().contains("\u0007"));
        for (String forbidden : List.of(
                diagnostics.detail(), diagnostics.caretString(), "first line", "last")) {
            assertFalse(text.outcome().contains(forbidden));
            assertFalse(text.message().contains(forbidden));
            assertFalse(text.excerpt().contains(forbidden));
            assertFalse(text.caret().contains(forbidden));
        }
    }

    @Test
    void malformedRowsFallBackIndependentlyAndUnknownOffsetsOmitSource() {
        Diagnostics malformed = new Diagnostics(
            Outcome.RENDER_BUG, "emit", "bad\uD800message",
            1, "secret detail", 1, "raw caret");
        RenderedErrorCard.CardText text =
            RenderedErrorCard.cardText("x\uD800y", malformed);
        assertEquals("Render failed.", text.message());
        assertEquals("Source excerpt unavailable.", text.excerpt());
        assertNull(text.caret());

        Diagnostics unknown = new Diagnostics(
            Outcome.RENDER_BUG, "emit", "fixed",
            -1, "secret detail", -1, "raw caret");
        RenderedErrorCard.CardText noPosition =
            RenderedErrorCard.cardText("ENTIRE-SOURCE-MUST-NOT-APPEAR", unknown);
        assertNull(noPosition.excerpt());
        assertNull(noPosition.caret());
    }

    @Test
    void secondaryStageFailuresReturnEmptySvgWithTheSameDiagnostics() {
        Diagnostics diagnostics = new Diagnostics(
            Outcome.PARSE_ERROR, "parse", "bad input", 1, "secret", 2, "x\n ^");

        for (Callable<String> renderer : List.<Callable<String>>of(
                () -> { throw new MathSyntaxException("card cap"); },
                () -> { throw new IllegalStateException("layout failed"); },
                () -> { throw new StackOverflowError(); })) {
            RenderResult result = LatteX.renderedErrorFailSoft(diagnostics, () -> {
                try {
                    return renderer.call();
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception impossible) {
                    throw new AssertionError(impossible);
                }
            });
            assertEquals("", result.svg());
            assertSame(diagnostics, result.diagnostics());
        }

        assertThrows(OutOfMemoryError.class,
            () -> LatteX.renderedErrorFailSoft(diagnostics,
                () -> { throw new OutOfMemoryError("do not conceal JVM state"); }));
    }

    @Test
    void worstCaseRowsStayBoundedBeforeTheyReachTheCappedEmitter() {
        String source = "s".repeat(5_000) + "🔭" + "t".repeat(5_000);
        Diagnostics diagnostics = new Diagnostics(
            Outcome.RENDER_BUG, "emit", "m".repeat(10_000),
            1, "DETAIL-SECRET", 5_002, "RAW-CARET-SECRET");

        String svg = RenderedErrorCard.render(source, diagnostics, SfntFont.loadBundled());
        assertFalse(svg.isBlank());
        assertTrue(svg.length() < 500_000,
            "four capped rows must remain far below the global 2M SVG ceiling");
        assertFalse(svg.contains("DETAIL-SECRET"));
        assertFalse(svg.contains("RAW-CARET-SECRET"));
    }

    @Test
    void concurrentCardRendersConvergeOnTheSameResult() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<RenderResult>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                calls.add(() -> LatteX.renderWithDiagnostics(
                    "x +\n\\frac{a}{", CARD_ON));
            }
            List<RenderResult> results = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) {
                results.add(future.get());
            }
            for (RenderResult result : results) {
                assertEquals(results.get(0), result);
                assertFalse(result.svg().isBlank());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
