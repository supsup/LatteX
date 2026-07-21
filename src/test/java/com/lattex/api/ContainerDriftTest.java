package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lattex.parse.MathSyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Container drift guard — the {@link com.lattex.svg.SvgEmitter} inner-SVG alphabet is
/// pinned by {@code S8LeftContainmentTest}; this is the same guard for the OUTER
/// {@code <span class="lx-math" …>} container that {@link LatteX#renderStyledHtml}
/// stamps (plan lattex-security-hardening-resource-guard S2). The container's attribute
/// surface is closed TODAY, but nothing failed the build if a future {@code fx.*} or
/// semantics feature stamped a NEW attribute name or forwarded an UNESCAPED author
/// value. Two universal invariants over a battery of every option, positive and hostile:
///   1. Every attribute on the opening tag is in a CLOSED allow-list (name drift → red).
///   2. No attribute value can break out of the tag — no unescaped `"`/`<`/`>`/`&`, and
///      the whole opening tag re-parses cleanly. Hostile author input either parse-
///      rejects or is escaped; it can never inject an attribute or markup.
class ContainerDriftTest {

    /// The complete set of attribute NAMES the container may carry. A name outside this
    /// set (an exact member or a `data-lx-<identifier>` data-attr) fails the build.
    private static final Set<String> ALLOWED_EXACT = Set.of(
        "class",
        "aria-label",
        "data-lx-fx-enter", "data-lx-fx-hover", "data-lx-fx-click",
        "data-lx-fx-duration", "data-lx-fx-glow-color",
        "data-lx-glyphmap", "data-lx-groupmap");

    /// The open data-attr lane: `data.<key>` / `intent` / `concept` stamp
    /// `data-lx-<identifier>` (keys are parse-time-validated identifiers, no hyphens).
    /// This lane is INTENTIONALLY open — a `data-lx-<identifier>` is author-controlled
    /// data, so the guard cannot (and should not) distinguish a code-stamped identifier
    /// attr from a legitimate `data.<key>`. What the guard DOES catch: any non-`data-lx`
    /// attribute (e.g. `onclick`, `style`) and any HYPHENATED `data-lx-*` outside the
    /// known `fx-*`/`glyphmap`/`groupmap` set (e.g. a new `data-lx-fx-<name>`) — both
    /// mutation-verified — plus value injection on every lane.
    private static final Pattern DATA_ATTR = Pattern.compile("data-lx-[a-z][a-z0-9_]*");

    private static final Pattern OPEN_TAG = Pattern.compile("^<span\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*\"([^\"]*)\"");

    // ------------------------------------------------------------------------------------

    @Test
    void everyContainerAttributeIsInTheClosedAllowList_positiveBattery() {
        for (String latex : positiveBattery()) {
            String tag = openTagOf(LatteX.renderStyledHtml(latex));
            for (String name : attrNames(tag)) {
                assertTrue(isAllowed(name),
                    "container attribute drift: <span> stamped a non-allow-listed attribute '"
                        + name + "' for " + latex + "\n  tag: " + tag);
            }
            // class is fixed, exactly "lx-math".
            assertTrue(tag.contains("class=\"lx-math\""), "class must be exactly lx-math: " + tag);
        }
    }

    @Test
    void containerValuesCarryNoUnescapedMarkupAndTheTagIsWellFormed() {
        for (String latex : positiveBattery()) {
            String tag = openTagOf(LatteX.renderStyledHtml(latex));
            assertNoInjection(tag, latex);
        }
    }

    @Test
    void knownAttributeValuesMatchTheirPinnedShape() {
        // Spot-check the value grammars the container contract advertises, so a value
        // shape (not just a name) that drifts is caught too.
        String tag = openTagOf(LatteX.renderStyledHtml(
            "\\lx[fx.enter=glow, fx.hover=thread, fx.duration=1200ms, fx.glow-color=#ff8800,"
                + " intent=ratio, concept=normalized_score, data.foo=bar]{\\frac{a}{b}}"));
        assertValue(tag, "data-lx-fx-enter", "[a-z]+");
        assertValue(tag, "data-lx-fx-hover", "[a-z]+");
        assertValue(tag, "data-lx-fx-duration", "[0-9]{1,5}ms");
        assertValue(tag, "data-lx-fx-glow-color", "currentColor|#[0-9a-fA-F]{3,8}");
        assertValue(tag, "data-lx-intent", "[a-z][a-z0-9_]*");
        assertValue(tag, "data-lx-concept", "[a-z][a-z0-9_]*");
        assertValue(tag, "data-lx-foo", "[^\"<>&]*");
        // The renderer-derived thread/precedence sidecars.
        String thread = openTagOf(LatteX.renderStyledHtml("\\lx[fx.hover=thread]{x + x}"));
        assertValue(thread, "data-lx-glyphmap", "[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*");
        String prec = openTagOf(LatteX.renderStyledHtml("\\lx[fx.enter=precedence]{\\left(a+b\\right)c}"));
        assertValue(prec, "data-lx-groupmap", "[0-9]+:[0-9]+(,[0-9]+)*(;[0-9]+:[0-9]+(,[0-9]+)*)*");
        // cancel is the SECOND consumer of the glyphmap sidecar (the zero-surface proof):
        // it reuses data-lx-glyphmap — same allow-listed attribute, same value grammar — and
        // stamps NO new attribute name (the allow-list above is unchanged for it).
        String cancel = openTagOf(LatteX.renderStyledHtml("\\lx[fx.enter=cancel]{\\frac{x}{x}}"));
        assertValue(cancel, "data-lx-fx-enter", "[a-z]+");
        assertValue(cancel, "data-lx-glyphmap", "[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*");
        for (String name : attrNames(cancel)) {
            assertTrue(isAllowed(name),
                "fx.enter=cancel stamped a non-allow-listed attribute '" + name + "' — cancel must"
                    + " add ZERO new container surface (reuse data-lx-glyphmap)\n  tag: " + cancel);
        }
    }

    @Test
    void hostileAuthorInputCannotInjectAnAttributeOrMarkup() {
        // Every attribute value is fed a hostile author string. The contract is: parse
        // REJECTS it, or it is escaped — never a raw attribute break / markup injection.
        for (String latex : hostileBattery()) {
            String html;
            try {
                html = LatteX.renderStyledHtml(latex);
            } catch (MathSyntaxException rejected) {
                continue; // parse-rejected the hostile option — fine
            }
            String tag = openTagOf(html);
            for (String name : attrNames(tag)) {
                assertTrue(isAllowed(name),
                    "hostile input stamped a non-allow-listed attribute '" + name + "' for " + latex);
            }
            assertNoInjection(tag, latex);
        }
    }

    // ---- battery -----------------------------------------------------------------------

    private static List<String> positiveBattery() {
        return List.of(
            "\\frac{a}{b}",                                            // bare, no data attrs
            "\\lx[fx.enter=glow]{x}",
            "\\lx[fx.hover=thread]{x + x}",                            // glyphmap sidecar
            "\\lx[fx.enter=cancel]{\\frac{x}{x}}",                     // glyphmap sidecar (2nd consumer)
            "\\lx[fx.click=shatter]{x}",
            "\\lx[fx.enter=precedence]{\\left(a+b\\right)\\times c}",  // groupmap sidecar
            "\\lx[fx.enter=glow, fx.duration=800ms, fx.glow-color=currentColor]{x}",
            "\\lx[intent=ratio]{\\frac{a}{b}}",
            "\\lx[concept=normalized_score]{x}",
            "\\lx[data.foo=bar, data.baz=qux]{x}",
            "\\lx[a11y.label=\"the normalized score\"]{x}",
            "\\lx[fx.enter=glow, intent=ratio, concept=score, data.k=v, a11y.label=\"lbl\"]{\\frac{a}{b}}");
    }

    private static List<String> hostileBattery() {
        // Injection attempts across every author-controlled attribute.
        return List.of(
            "\\lx[a11y.label=\"x\\\" onload=\\\"evil\"]{y}",  // quote break in the label
            "\\lx[a11y.label=\"<img src=x onerror=1>\"]{y}",  // markup in the label (escaped)
            "\\lx[a11y.label=\"a & b < c > d\"]{y}",          // raw entities in the label
            "\\lx[intent=x\\\" onmouseover=1]{y}",            // quote break in intent
            "\\lx[concept=<script>]{y}",                       // markup in concept
            "\\lx[data.k=\"v\\\" x=\\\"y\"]{y}",              // quote break in a data value
            "\\lx[data.bad-key=1]{y}",                          // hyphen key (would collide with fx-*)
            "\\lx[data.\"evil\"=1]{y}");                        // quoted key
    }

    // ---- helpers -----------------------------------------------------------------------

    private static boolean isAllowed(String name) {
        return ALLOWED_EXACT.contains(name) || DATA_ATTR.matcher(name).matches();
    }

    private static String openTagOf(String html) {
        Matcher m = OPEN_TAG.matcher(html);
        assertTrue(m.find(), "no <span> opening tag in: " + html);
        return m.group();
    }

    private static List<String> attrNames(String tag) {
        List<String> names = new java.util.ArrayList<>();
        Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static void assertValue(String tag, String name, String valueRegex) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        assertTrue(m.find(), "expected attribute " + name + " in: " + tag);
        assertTrue(m.group(1).matches(valueRegex),
            name + " value '" + m.group(1) + "' does not match " + valueRegex);
    }

    private static void assertNoInjection(String tag, String latex) {
        // The full tag re-tokenizes into name="value" pairs with the trailing '>' — so a
        // value that smuggled a '"' or '>' would leave dangling text the attr scanner
        // can't account for. Reconstruct from the parsed attrs and confirm we consumed
        // everything but the fixed skeleton.
        Matcher m = ATTR.matcher(tag);
        StringBuilder consumed = new StringBuilder("<span");
        while (m.find()) {
            String value = m.group(2);
            // No unescaped markup metacharacters may survive inside an attribute value.
            // ('&' is legitimate as the start of an entity — &amp;/&lt;/… — and is
            // validated separately by bareAmpersandFree; a raw < > " would break out.)
            for (char c : new char[]{'<', '>', '"'}) {
                if (value.indexOf(c) >= 0) {
                    fail("attribute value for " + m.group(1) + " carries a raw '" + c
                        + "' (injection risk) for " + latex + "\n  tag: " + tag);
                }
            }
            consumed.append(' ').append(m.group(1)).append("=\"").append(value).append('"');
        }
        consumed.append('>');
        assertTrue(consumed.toString().equals(tag),
            "opening tag has content outside the parsed name=\"value\" attributes (injection?) for "
                + latex + "\n  tag:      " + tag + "\n  consumed: " + consumed);
        // '&' must always be an entity (&amp; / &lt; / …), never a bare ampersand.
        assertTrue(bareAmpersandFree(tag), "opening tag has a bare '&' (must be an entity): " + tag);
    }

    private static boolean bareAmpersandFree(String tag) {
        Matcher amp = Pattern.compile("&(?!#?[a-zA-Z0-9]+;)").matcher(tag);
        return !amp.find();
    }
}
