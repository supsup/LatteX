package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.Color;
import com.lattex.api.MathStyle;
import com.lattex.parse.MathNode.Fraction;
import com.lattex.parse.MathNode.StyledMath;
import java.util.Map;
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
    void bodyQuotesAreOrdinaryContentNotDelimiters() {
        // Lattice (lattex/42): \lx{"} falsely reported unterminated because the
        // brace scanner toggled quote-state on body quotes. Quotes are syntax
        // ONLY inside [options].
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> com.lattex.api.LatteX.renderStyledHtml("\\lx{\"}"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> com.lattex.api.LatteX.renderStyledHtml("\\lx{\\text{\"}}"));
        // options keep their quote semantics: a bracket inside a quoted value
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> com.lattex.api.LatteX.renderStyledHtml("\\lx[a11y.label=\"[x]\"]{x}"));
    }

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

    @Test
    void parsesLightningEffect() {
        // lightning validates + parses on every trigger, exactly like the others.
        assertEquals(Effect.LIGHTNING,
            lx("\\lx[fx.hover=lightning]{ x }").fx().effect(Trigger.HOVER).orElseThrow());
        assertEquals(Effect.LIGHTNING,
            lx("\\lx[fx.enter=lightning]{ x }").fx().effect(Trigger.ENTER).orElseThrow());
        assertEquals(Effect.LIGHTNING,
            lx("\\lx[fx.click=lightning]{ x }").fx().effect(Trigger.CLICK).orElseThrow());
    }

    @Test
    void parsesStormEffect() {
        // storm (night lightning) validates + parses on every trigger, like lightning.
        assertEquals(Effect.STORM,
            lx("\\lx[fx.hover=storm]{ x }").fx().effect(Trigger.HOVER).orElseThrow());
        assertEquals(Effect.STORM,
            lx("\\lx[fx.enter=storm]{ x }").fx().effect(Trigger.ENTER).orElseThrow());
        assertEquals(Effect.STORM,
            lx("\\lx[fx.click=storm]{ x }").fx().effect(Trigger.CLICK).orElseThrow());
    }

    @Test
    void numericKnobsPreserveAbsentStateAndParseBothEffectsAtTheirBounds() {
        for (String effect : new String[]{"lightning", "storm"}) {
            EffectSpec omitted = lx("\\lx[fx.hover=" + effect + "]{x}").fx();
            assertNull(omitted.bolts());
            assertNull(omitted.intensity());
            for (int[] values : new int[][]{{1, 0}, {2, 100}, {8, 300}}) {
                EffectSpec fx = lx("\\lx[ fx.hover = " + effect + ", fx.bolts = " + values[0]
                    + " ,\tfx.intensity = " + values[1] + " \n]{x}").fx();
                assertEquals(Integer.valueOf(values[0]), fx.bolts());
                assertEquals(Integer.valueOf(values[1]), fx.intensity());
                assertEquals(Effect.parse(effect), fx.effect(Trigger.HOVER).orElseThrow());
            }
        }
        EffectSpec quoted = lx("\\lx[fx.bolts=\"002\", fx.intensity=\"000\"]{x}").fx();
        assertEquals(Integer.valueOf(2), quoted.bolts());
        assertEquals(Integer.valueOf(0), quoted.intensity());
        EffectSpec boltsOnly = lx("\\lx[fx.bolts=2]{x}").fx();
        assertNull(boltsOnly.intensity());
        assertTrue(!boltsOnly.isEmpty(), "explicit default is still authored state");
        EffectSpec zeroOnly = lx("\\lx[fx.intensity=0]{x}").fx();
        assertNull(zeroOnly.bolts());
        assertTrue(!zeroOnly.isEmpty(), "explicit zero must not disappear");
        assertTrue(EffectSpec.none().isEmpty());
    }

    @Test
    void numericKnobsRejectMalformedValuesWithKeyValueAndRangeEvenAtTokenizerFailures() {
        String[][] malformed = {
            {"", ""}, {"\"\"", ""}, {"-1", "-1"}, {"+1", "+1"},
            {"1.0", "1.0"}, {"1e0", "1e0"}, {"1E0", "1E0"}, {"1ms", "1ms"},
            {"2%", "2%"}, {"0x2", "0x2"}, {"NaN", "NaN"}, {"Infinity", "Infinity"},
            {"1x", "1x"}, {"١", "١"}, {"１", "１"},
            {"1 2", "1 2"}, {"1\t2", "1\t2"}, {"\" 2\"", " 2"},
            {"\"2 \"", "2 "}, {"\"1 2\"", "1 2"}, {"\"2\"junk", "\"2\"junk"},
            {"\"2,3\"", "2,3"}, {"2\u00a0", "2\u00a0"},
            {"2147483648", "2147483648"},
            {"999999999999999999999999999999999999", "999999999999999999999999999999999999"}
        };
        for (String effect : new String[]{"lightning", "storm"}) {
            for (String key : new String[]{"fx.bolts", "fx.intensity"}) {
                for (String[] bad : malformed) {
                    assertNumericRejected(effect, key, bad[0], bad[1]);
                }
            }
            for (String bad : new String[]{"0", "00", "9"}) {
                assertNumericRejected(effect, "fx.bolts", bad, bad);
            }
            assertNumericRejected(effect, "fx.intensity", "301", "301");
        }
    }

    private static void assertNumericRejected(String effect, String key, String sourceValue,
                                              String suppliedValue) {
        MathSyntaxException error = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[fx.hover=" + effect + "," + key + "=" + sourceValue + "]{x}"));
        assertTrue(error.getMessage().contains(key), error.getMessage());
        assertTrue(error.getMessage().contains("\"" + suppliedValue + "\""), error.getMessage());
        assertTrue(error.getMessage().contains(key.equals("fx.bolts") ? "1..8" : "0..300"),
            error.getMessage());
    }

    @Test
    void onlyTheTwoFlatNumericKnobsAreKnown() {
        for (String key : new String[]{"fx.strikes", "fx.arc", "fx.lightning.bolts",
                "fx.storm.intensity", "fx.bolts.extra", "fx.intensity.extra"}) {
            MathSyntaxException error = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("\\lx[" + key + "=1]{x}"));
            assertTrue(error.getMessage().contains("unknown"), error.getMessage());
            assertTrue(error.getMessage().contains(key), error.getMessage());
        }
    }

    @Test
    void directEffectConstructionSharesBoundsAndRetainsFiveArgumentConstructor() {
        EffectSpec legacy = new EffectSpec(Map.of(Trigger.HOVER, Effect.LIGHTNING),
            "250ms", Color.CURRENT, null, null);
        assertNull(legacy.bolts());
        assertNull(legacy.intensity());
        assertEquals("250ms", legacy.duration());
        assertEquals(Effect.LIGHTNING, legacy.effects().get(Trigger.HOVER));
        assertTrue(new EffectSpec(Map.of(), null, null, null, null).isEmpty());
        for (int[] values : new int[][]{{1, 0}, {2, 100}, {8, 300}}) {
            EffectSpec fx = new EffectSpec(Map.of(), null, null, null, null, values[0], values[1]);
            assertEquals(Integer.valueOf(values[0]), fx.bolts());
            assertEquals(Integer.valueOf(values[1]), fx.intensity());
            assertTrue(!fx.isEmpty());
        }
        for (int bad : new int[]{Integer.MIN_VALUE, -1, 0, 9, Integer.MAX_VALUE}) {
            MathSyntaxException error = assertThrows(MathSyntaxException.class,
                () -> new EffectSpec(Map.of(), null, null, null, null, bad, null));
            assertTrue(error.getMessage().contains("fx.bolts"), error.getMessage());
            assertTrue(error.getMessage().contains("\"" + bad + "\""), error.getMessage());
            assertTrue(error.getMessage().contains("1..8"), error.getMessage());
        }
        for (int bad : new int[]{Integer.MIN_VALUE, -1, 301, Integer.MAX_VALUE}) {
            MathSyntaxException error = assertThrows(MathSyntaxException.class,
                () -> new EffectSpec(Map.of(), null, null, null, null, null, bad));
            assertTrue(error.getMessage().contains("fx.intensity"), error.getMessage());
            assertTrue(error.getMessage().contains("\"" + bad + "\""), error.getMessage());
            assertTrue(error.getMessage().contains("0..300"), error.getMessage());
        }
    }

    @Test
    void parsesGlowColorThroughTheColorBoundary() {
        // A valid colour flows through Color.parse and is stored on the EffectSpec.
        StyledMath hex = lx("\\lx[fx.hover=glow, fx.glow-color=#e0a13a]{ \\zeta(s) }");
        assertEquals("#e0a13a", hex.fx().glowColorValue().orElseThrow().svgValue());
        assertEquals(Color.CURRENT,
            lx("\\lx[fx.glow-color=currentColor]{ x }").fx().glowColorValue().orElseThrow());
        // 3-digit shorthand canonicalizes like every other colour.
        assertEquals("#aabbcc",
            lx("\\lx[fx.glow-color=#abc]{ x }").fx().glowColorValue().orElseThrow().svgValue());
        // Absent by default (unset = currentColor behaviour downstream).
        assertTrue(lx("\\lx[fx.hover=glow]{ x }").fx().glowColorValue().isEmpty());
    }

    @Test
    void invalidGlowColorFailsLoud() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[fx.glow-color=amber]{ x }"));
        assertTrue(e.getMessage().contains("fx.glow-color"), "names the bad key: " + e.getMessage());
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\lx[fx.glow-color=#gggggg]{ x }"));
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
        // Plan cfd12523: the label is stored RAW; HTML-escaping is applied ONCE at the
        // container boundary (renderStyledHtml), not at parse.
        StyledMath esc = lx("\\lx[a11y.label=\"a < b & c\"]{ x }");
        assertEquals("a < b & c", esc.sem().a11yLabel(), "stored raw (unescaped)");
        assertTrue(com.lattex.api.LatteX.renderStyledHtml("\\lx[a11y.label=\"a < b & c\"]{ x }")
                .contains("aria-label=\"a &lt; b &amp; c\""),
            "escaped at the container boundary");
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
        // Plan cfd12523: the plottable body is stored RAW in the Semantics data map;
        // HTML-escaping is applied ONCE at the container boundary (renderStyledHtml).
        StyledMath sm = lx("\\lx[graph.domain=-2..2]{a < b}");
        assertEquals("a < b", sm.sem().data().get("graph-expr"), "stored raw (unescaped)");
        assertTrue(com.lattex.api.LatteX.renderStyledHtml("\\lx[graph.domain=-2..2]{a < b}")
                .contains("data-lx-graph-expr=\"a &lt; b\""),
            "escaped at the container boundary");
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
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\lx[style.color=notacolor]{ x }"));
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
