package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * L6.2 — Sirentide-parity diagnostics (plan lattex-containment-diagnostics; the
 * lattex/126 contract). renderWithDiagnostics NEVER throws; core fields mirror
 * Sirentide's record; offset/caretString are the LatteX-only enhancement.
 */
class RenderWithDiagnosticsTest {

    @Test
    void successReturnsOkWithTheSameSvgAsRender() {
        RenderResult r = LatteX.renderWithDiagnostics("\\frac{a}{b}");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("emit", r.diagnostics().stage());
        assertEquals(LatteX.render("\\frac{a}{b}"), r.svg(),
            "diagnostic twin produces the SAME svg — one pipeline semantics");
    }

    @Test
    void syntaxErrorClassifiesParseErrorWithOffsetAndCaret() {
        RenderResult r = LatteX.renderWithDiagnostics("\\frac{a}{");
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.PARSE_ERROR, d.outcome());
        assertEquals("parse", d.stage());
        assertEquals("", r.svg(), "no svg on failure — consumer renders its own fallback");
        assertFalse(d.message().isBlank());
        // LatteX-only enhancements ride along.
        assertTrue(d.offset() >= 0 || !d.caretString().isBlank(),
            "positional diagnostics carried");
        assertTrue(d.line() >= 1 || d.offset() < 0, "line derived when offset known");
    }

    @Test
    void deepNestingNeverThrowsAndClassifies() {
        // The never-throw promise end-to-end on the plan's named fixture.
        String deep = "{".repeat(600) + "x" + "}".repeat(600);
        RenderResult r = LatteX.renderWithDiagnostics(deep);
        assertEquals(Outcome.PARSE_ERROR, r.diagnostics().outcome());
        assertEquals("", r.svg());
    }

    @Test
    void enumNamesMirrorSirentideExactly() {
        // Local pin of the lattex/126 parity vocabulary (Conf's source-mirror
        // drift-guard upgrades this to a cross-repo check). Names AND order.
        assertEquals("OK,PARSE_ERROR,OUTPUT_CAP_EXCEEDED,UNSUPPORTED_CONSTRUCT,RENDER_BUG",
            String.join(",", java.util.Arrays.stream(Outcome.values())
                .map(Enum::name).toArray(String[]::new)));
    }

    @Test
    void lineOfDerivesOneBasedLines() {
        RenderResult r = LatteX.renderWithDiagnostics("x +\n\\frac{a}{");
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.PARSE_ERROR, d.outcome());
        if (d.offset() >= 4) {
            assertEquals(2, d.line(), "offset past the newline lands on line 2");
        }
    }
}
