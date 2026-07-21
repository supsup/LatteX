package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lattex.api.LatteX;
import com.lattex.api.RenderOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Q2 MANDATORY containment guard for the fx.unfold payload. The pre-rendered
 * expanded {@code <svg>} is a NEW inner-DOM surface that no existing guard audits —
 * {@link S8LeftContainmentTest} audits {@code render()} (which drops fx), and
 * {@code ContainerDriftTest} audits only the {@code <span>} open tag. This closes the
 * gap: it renders an unfold expression through {@code renderStyledHtml} (flag ON) and
 * asserts (a) exactly TWO {@code <svg>} and no element type outside the trusted set,
 * and (b) the pre-rendered expanded svg is ⊆ the S8 alphabet — using the SAME auditor
 * as {@link S8LeftContainmentTest}, so it can never drift from the emitter contract.
 */
class UnfoldPayloadContainmentTest {

    private static final RenderOptions FLAG_ON = RenderOptions.defaults().withInteractiveExpansion(true);

    /** The only element types the styled-html unfold surface may carry. */
    private static final Set<String> ALLOWED_ELEMENTS = Set.of("span", "svg", "g", "path", "rect");

    @Test
    void unfoldPayloadStaysWithinTheContract() {
        String html = LatteX.renderStyledHtml("\\lx[fx.click=unfold]{\\sum_{i=1}^{4} f(i)}", FLAG_ON);

        // (a) exactly two <svg>: the collapsed sum + the pre-rendered expanded payload.
        assertEquals(2, count(html, "<svg"), "expected exactly two <svg> (collapsed + payload): " + html);
        assertEquals(2, count(html, "</svg>"), "unbalanced <svg> tags: " + html);

        // The marker pairs the interaction with the payload; value is the term count.
        assertTrue(html.contains("data-lx-fx-expand=\"4\""),
            "expected data-lx-fx-expand=\"4\" marker: " + html);
        assertTrue(html.contains("class=\"lx-fx-expanded\""),
            "payload must ride a .lx-fx-expanded wrapper span: " + html);

        // No element type outside the trusted set (the container span, the payload
        // wrapper span, and the two emitter svgs' g/path/rect). No new element leaked.
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)").matcher(html);
        while (tag.find()) {
            assertTrue(ALLOWED_ELEMENTS.contains(tag.group(1)),
                "unfold styled-html introduced a new element <" + tag.group(1) + ">: " + html);
        }

        // (b) the pre-rendered expanded svg is ⊆ the S8 alphabet — the SAME auditor.
        String expandedSvg = expandedSvgOf(html);
        List<String> failures = new ArrayList<>();
        int tags = S8LeftContainmentTest.auditOne("unfold payload", expandedSvg, failures);
        assertTrue(tags > 0, "audited an empty payload svg");
        if (!failures.isEmpty()) {
            fail("unfold payload svg escaped the S8 alphabet:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    void collapsedSvgIsByteIdenticalToTheSumRender() {
        // The visible collapsed svg is exactly what render() produces for the sum — the
        // payload adds a sibling, it never mutates the original.
        String html = LatteX.renderStyledHtml("\\lx[fx.click=unfold]{\\sum_{i=1}^{4} f(i)}", FLAG_ON);
        String collapsed = html.substring(html.indexOf("<svg"), html.indexOf("</svg>") + "</svg>".length());
        assertEquals(LatteX.render("\\sum_{i=1}^{4} f(i)"), collapsed,
            "collapsed svg must be byte-identical to render() of the sum");
    }

    // ---- helpers -----------------------------------------------------------

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** The svg inside the .lx-fx-expanded wrapper (the payload). */
    private static String expandedSvgOf(String html) {
        int wrap = html.indexOf("class=\"lx-fx-expanded\"");
        assertTrue(wrap >= 0, "no .lx-fx-expanded wrapper in: " + html);
        int start = html.indexOf("<svg", wrap);
        int end = html.indexOf("</svg>", start) + "</svg>".length();
        return html.substring(start, end);
    }
}
