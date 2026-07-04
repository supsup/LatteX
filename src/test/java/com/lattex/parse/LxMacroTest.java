package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.Color;
import com.lattex.layout.MathStyle;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.StyledMath;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code \lx[options]{body}} macro parser: options are validated and
 * reduced to typed L1 {@link com.lattex.api.RenderOptions} / {@link EffectSpec} /
 * {@link Semantics} fields at parse, unknown keys and invalid values fail loud,
 * and the wrapped body parses normally.
 */
class LxMacroTest {

    private static StyledMath lx(String src) {
        return assertInstanceOf(StyledMath.class, MathParser.parse(src));
    }

    // ---- style options reduce to a typed RenderOptions ----

    @Test
    void parsesStyleColorAndScale() {
        StyledMath sm = lx("\\lx[style.color=#c0392b, style.scale=1.4]{ \\frac{a+b}{c} }");
        assertEquals(1.4, sm.style().scale());
        assertEquals("#c0392b", sm.style().color().svgValue());
        assertEquals(MathStyle.DISPLAY, sm.style().mathStyle());
        assertInstanceOf(Fraction.class, sm.body(), "body parses as ordinary math");
        assertTrue(sm.fx().isEmpty());
        assertTrue(sm.sem().isEmpty());
    }

    @Test
    void parsesMathStyleAndScaleBucket() {
        StyledMath sm = lx("\\lx[style.mathstyle=text, style.scale=lg]{ \\sum_{i=1}^{n} i }");
        assertEquals(MathStyle.TEXT, sm.style().mathStyle());
        assertEquals(1.4, sm.style().scale());
    }

    @Test
    void colorShorthandAndCurrentColorParse() {
        assertEquals("#aabbcc", lx("\\lx[style.color=#abc]{ x }").style().color().svgValue());
        assertEquals(Color.CURRENT, lx("\\lx[style.color=currentColor]{ x }").style().color());
    }

    @Test
    void defaultsWhenNoStyleGiven() {
        // \lx opts into currentColor by default (prose/dark-mode friendly), unlike
        // L1 RenderOptions.defaults() which is black for byte-identical legacy output.
        StyledMath sm = lx("\\lx[intent=area]{ x }");
        assertEquals(1.0, sm.style().scale());
        assertEquals(Color.CURRENT, sm.style().color());
        assertEquals(MathStyle.DISPLAY, sm.style().mathStyle());
    }

    @Test
    void bodyMayOmitOptions() {
        StyledMath sm = lx("\\lx{ x^2 }");
        assertEquals(1.0, sm.style().scale());
        assertTrue(sm.fx().isEmpty());
        assertTrue(sm.sem().isEmpty());
    }

    // ---- fx.* reduce to a typed EffectSpec ----

    @Test
    void parsesEffectsAndDuration() {
        StyledMath sm = lx("\\lx[fx.enter=boom, fx.hover=pulse, fx.duration=250ms]{ x }");
        assertEquals(Effect.BOOM, sm.fx().effect(Trigger.ENTER).orElseThrow());
        assertEquals(Effect.PULSE, sm.fx().effect(Trigger.HOVER).orElseThrow());
        assertEquals("250ms", sm.fx().durationValue().orElseThrow());
        assertTrue(sm.fx().effect(Trigger.CLICK).isEmpty());
    }

    // ---- semantics reduce to a typed Semantics ----

    @Test
    void parsesConceptAndIntent() {
        StyledMath sm = lx("\\lx[concept=normalized_score, intent=ratio]{ \\frac{a+b}{c} }");
        assertEquals("normalized_score", sm.sem().concept());
        assertEquals("ratio", sm.sem().intent());
    }

    @Test
    void parsesQuotedA11yLabelWithSpacesAndEscapes() {
        StyledMath sm = lx("\\lx[a11y.label=\"the sum of i from 1 to n\"]{ \\sum_{i=1}^{n} i }");
        assertEquals("the sum of i from 1 to n", sm.sem().a11yLabel());
        // HTML-escaping happens at parse.
        StyledMath esc = lx("\\lx[a11y.label=\"a < b & c\"]{ x }");
        assertEquals("a &lt; b &amp; c", esc.sem().a11yLabel());
    }

    @Test
    void parsesDataStar() {
        StyledMath sm = lx("\\lx[data.role=header, data.group=scores]{ x }");
        assertEquals("header", sm.sem().data().get("role"));
        assertEquals("scores", sm.sem().data().get("group"));
    }

    // ---- graph.* marks a plottable expression (rides the container) ----

    @Test
    void parsesGraphDomainAndOpen() {
        StyledMath sm = lx("\\lx[graph.domain=-3..3, graph.open=multi]{x^2-3}");
        assertEquals("-3..3", sm.sem().data().get("graph-domain"));
        assertEquals("multi", sm.sem().data().get("graph-open"));
        // The body's raw LaTeX rides along so the runtime knows what to plot.
        assertEquals("x^2-3", sm.sem().data().get("graph-expr"));
    }

    @Test
    void graphDefaultsDomainAndOpen() {
        StyledMath sm = lx("\\lx[graph.open=single]{2x - 1}");
        assertEquals("-10..10", sm.sem().data().get("graph-domain"), "domain defaults to -10..10");
        assertEquals("single", sm.sem().data().get("graph-open"));
        assertEquals("2x - 1", sm.sem().data().get("graph-expr"), "expr trimmed from the body");
    }

    @Test
    void graphExprIsHtmlEscaped() {
        // A body with '<' (a relation) is HTML-escaped for the attribute value.
        StyledMath sm = lx("\\lx[graph.domain=-2..2]{a < b}");
        assertEquals("a &lt; b", sm.sem().data().get("graph-expr"));
    }

    @Test
    void invalidGraphValuesFailLoud() {
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[graph.domain=lots]{ x }"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[graph.open=both]{ x }"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[graph.bogus=1]{ x }"));
    }

    // ---- unknown keys / invalid values fail loud ----

    @Test
    void unknownTopLevelKeyFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[bogus=1]{ x }"));
        assertTrue(e.getMessage().contains("bogus"), "names the bad key: " + e.getMessage());
    }

    @Test
    void unknownSubKeyFailsLoud() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[style.bogus=1]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[fx.bogus=boom]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[a11y.title=\"x\"]{ x }"));
    }

    @Test
    void invalidValuesFailLoud() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[style.color=red]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[style.scale=huge]{ x }"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[style.mathstyle=bogus]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[fx.enter=zoom]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[fx.duration=250]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[intent=Bad]{ x }"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[concept=1x]{ x }"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[data.role=\"has space\"]{ x }"));
    }

    // ---- malformed macro shapes ----

    @Test
    void missingBodyFailsLoud() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[style.scale=2]"));
    }

    @Test
    void trailingContentFailsLoud() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx{ x } + 1"));
    }

    @Test
    void malformedOptionFailsLoud() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[style.scale]{ x }"));
    }

    // ---- nesting: \lx is top-level-only for the MVP ----

    @Test
    void nestedLxInBodyFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[style.scale=2]{ \\lx[style.scale=3]{ x } }"));
        assertTrue(e.getMessage().contains("nested"), "clear nested message: " + e.getMessage());
    }

    @Test
    void lxNotAtTopLevelFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x + \\lx[style.scale=2]{ y }"));
        assertTrue(e.getMessage().contains("nested"), "clear nested message: " + e.getMessage());
    }

    // ---- \lxfoo is a different command, not the macro ----

    @Test
    void lxPrefixIsNotTheMacro() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lxfoo{ x }"));
        assertTrue(e.getMessage().contains("Unknown command"), e.getMessage());
    }

    // ---- record-level validation still guards direct construction ----

    @Test
    void semanticsRecordRejectsBadIdentifier() {
        assertThrows(MathSyntaxException.class,
            () -> new Semantics("Bad", null, null, java.util.Map.of()));
        assertNull(Semantics.none().intent());
    }
}
