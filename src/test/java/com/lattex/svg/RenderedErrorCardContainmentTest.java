package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.Outcome;
import com.lattex.api.RenderOptions;
import com.lattex.api.RenderResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The opt-in error card must stay inside the exact same minimal SVG alphabet as
 * ordinary math. This deliberately reuses the single S8 auditor rather than
 * maintaining a weaker card-specific allow-list.
 */
class RenderedErrorCardContainmentTest {

    @Test
    void renderedFailureCardPassesTheCanonicalSvgAlphabetAudit() {
        String hostile = "\\frac{<script>&javascript:data:}{";
        RenderResult result = LatteX.renderWithDiagnostics(hostile,
            RenderOptions.defaults().withRenderedErrors(true));

        assertTrue(result.diagnostics().outcome() != Outcome.OK);
        assertFalse(result.svg().isBlank());

        List<String> failures = new ArrayList<>();
        int tags = S8LeftContainmentTest.auditOne("rendered error card", result.svg(), failures);
        assertTrue(failures.isEmpty(), () -> "error-card containment failures:\n  "
            + String.join("\n  ", failures));
        assertTrue(tags > 4, "the audit must scan real card geometry, not an empty shell");

        // All human-readable content is converted to font paths; neither the hostile
        // source nor diagnostic-only values can survive as raw SVG text/attributes.
        assertFalse(result.svg().contains(hostile));
        if (!result.diagnostics().detail().isBlank()) {
            assertFalse(result.svg().contains(result.diagnostics().detail()));
        }
        if (!result.diagnostics().caretString().isBlank()) {
            assertFalse(result.svg().contains(result.diagnostics().caretString()));
        }
    }
}
