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
 * The containment guard for the fx.substitute payload, cloned one-for-one from
 * {@link UnfoldPayloadContainmentTest} because the two effects emit the same KIND of new
 * surface: a pre-rendered sibling {@code <svg>} that no other guard audits.
 * {@code S8LeftContainmentTest} audits {@code render()} (which drops fx entirely) and
 * {@code ContainerDriftTest} audits only the {@code <span>} open tag, so without this the
 * payload's element alphabet would be unchecked.
 *
 * <p>It deliberately uses the SAME auditor ({@link S8LeftContainmentTest#auditOne}) rather
 * than a second copy of the alphabet, so the payload contract can never drift from the
 * emitter contract it is supposed to be a subset of.
 */
class SubstitutePayloadContainmentTest {

    private static final RenderOptions FLAG_ON =
        RenderOptions.defaults().withInteractiveExpansion(true);

    private static final String SRC =
        "\\lx[fx.click=substitute, fx.substitute-to=3]{x^2 + 2x + 1}";
    private static final String BODY = "x^2 + 2x + 1";

    /** The only element types the styled-html substitute surface may carry. */
    private static final Set<String> ALLOWED_ELEMENTS =
        Set.of("span", "svg", "g", "path", "rect");

    @Test
    void substitutePayloadStaysWithinTheContract() {
        String html = LatteX.renderStyledHtml(SRC, FLAG_ON);

        // (a) exactly two <svg>: the variable form + the pre-rendered substituted payload.
        assertEquals(2, count(html, "<svg"),
            "expected exactly two <svg> (collapsed + payload): " + html);
        assertEquals(2, count(html, "</svg>"), "unbalanced <svg> tags: " + html);

        assertTrue(html.contains("data-lx-fx-substitute=\"3\""),
            "expected the target marker: " + html);
        assertTrue(html.contains("class=\"lx-fx-substituted\""),
            "payload must ride a .lx-fx-substituted wrapper span: " + html);

        // No element type outside the trusted set — no new element leaked with the payload.
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)").matcher(html);
        while (tag.find()) {
            assertTrue(ALLOWED_ELEMENTS.contains(tag.group(1)),
                "substitute styled-html introduced a new element <" + tag.group(1) + ">: " + html);
        }

        // (b) the pre-rendered payload svg is ⊆ the S8 alphabet — the SAME auditor.
        String payloadSvg = payloadSvgOf(html);
        List<String> failures = new ArrayList<>();
        int tags = S8LeftContainmentTest.auditOne("substitute payload", payloadSvg, failures);
        assertTrue(tags > 0, "audited an empty payload svg");
        if (!failures.isEmpty()) {
            fail("substitute payload svg escaped the S8 alphabet:\n  "
                + String.join("\n  ", failures));
        }
    }

    @Test
    void collapsedSvgIsByteIdenticalToThePlainRender() {
        // Arming the effect adds a SIBLING; it never mutates the original. This is the
        // assertion that would catch the payload path accidentally re-laying-out or
        // re-emitting the visible form.
        String html = LatteX.renderStyledHtml(SRC, FLAG_ON);
        String collapsed = html.substring(html.indexOf("<svg"),
            html.indexOf("</svg>") + "</svg>".length());
        assertEquals(LatteX.render(BODY), collapsed,
            "the visible svg must be byte-identical to render() of the body");
    }

    @Test
    void varmapIndicesAddressRealPathsInTheVisibleSvg() {
        // The sidecar is only meaningful if its indices resolve. An index past the end of
        // the path list would leave the runtime dimming nothing — the effect would look
        // like a plain swap and no other test here would notice.
        String html = LatteX.renderStyledHtml(SRC, FLAG_ON);
        Matcher m = Pattern.compile("data-lx-var=\"([0-9a-f]+):([0-9,]+)\"").matcher(html);
        assertTrue(m.find(), "expected a varmap on the container: " + html);
        assertEquals("78", m.group(1), "the addressed code point must be x (0x78)");

        String collapsed = html.substring(html.indexOf("<svg"),
            html.indexOf("</svg>") + "</svg>".length());
        int pathCount = count(collapsed, "<path");
        String[] idx = m.group(2).split(",");
        assertEquals(2, idx.length, "x occurs twice in " + BODY + ", so the run has two indices");
        for (String s : idx) {
            int i = Integer.parseInt(s);
            assertTrue(i >= 0 && i < pathCount,
                "varmap index " + i + " is outside the visible svg's " + pathCount + " paths");
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** The svg inside the .lx-fx-substituted wrapper (the payload). */
    private static String payloadSvgOf(String html) {
        int wrap = html.indexOf("class=\"lx-fx-substituted\"");
        assertTrue(wrap >= 0, "no .lx-fx-substituted wrapper in: " + html);
        int start = html.indexOf("<svg", wrap);
        int end = html.indexOf("</svg>", start) + "</svg>".length();
        return html.substring(start, end);
    }
}
