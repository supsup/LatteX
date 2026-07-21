package com.lattex.fx;

import com.lattex.api.LatteX;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx-runtime JS harness (plan e09b28be S1): executes the REAL lattex-fx.js inside a
 * GraalJS context (no Node/jsdom toolchain — hermetic under {@code ./gradlew test})
 * and pins the two highest-value runtime behaviors behaviorally, not by string-grep:
 *
 * <ol>
 *   <li><b>Placement compose</b> — the blob-bug class Charles smoke-caught twice on
 *       2026-07-06: a per-path effect must COMPOSE its motion delta in front of the
 *       path's placement transform (setPathDelta), and must pin transform-origin to
 *       0 0 (the CSS property otherwise applies about the viewBox centre and
 *       scrunches the equation). Deleting either behavior in lattex-fx.js fails a
 *       test here — previously NOTHING failed.</li>
 *   <li><b>thread glyphmap grammar</b> — parseGlyphmap accepts exactly the contract
 *       grammar, refuses the WHOLE map on any out-of-bounds index, and groups
 *       indices so every member of a token group lights every other member.</li>
 * </ol>
 *
 * The internals surface via the guarded {@code window.__lxTestHook} seam at the tail
 * of lattex-fx.js — a no-op in browsers, captured here before the script loads.
 */
class FxRuntimeJsHarnessTest {

    private Context context;
    private Value fx; // the internals object handed to __lxTestHook

    @BeforeEach
    void loadRuntime() throws IOException {
        context = Context.newBuilder("js").build();
        context.eval(source("/com/lattex/fx/harness-dom-stub.js", "harness-dom-stub.js"));
        // Capture the internals object the tail seam hands out.
        context.eval(Source.create("js",
            "globalThis.__lxTestHook = function (internals) { globalThis.__lxInternals = internals; };"));
        context.eval(source("/com/lattex/fx/lattex-fx.js", "lattex-fx.js"));
        fx = context.getBindings("js").getMember("__lxInternals");
        assertNotNull(fx, "lattex-fx.js must hand its internals to a pre-installed __lxTestHook");
        assertFalse(fx.isNull(), "internals object must not be null");
    }

    @AfterEach
    void close() {
        context.close();
    }

    private static Source source(String resource, String name) throws IOException {
        try (InputStream in = FxRuntimeJsHarnessTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing classpath resource " + resource);
            return Source.newBuilder("js", new String(in.readAllBytes(), StandardCharsets.UTF_8), name)
                .buildLiteral();
        }
    }

    private Value makeEl(String script) {
        return context.eval(Source.create("js", script));
    }

    // ---- pin 0: the public page API (plan 51051447) -----------------------------

    @Test
    void publicLatteXFxPlayIsExportedAndFailsClosedOnNonElements() {
        // The ONE supported programmatic trigger: pages/galleries/capture tooling and
        // touch UIs play an element's configured effect without synthetic pointer
        // events into runtime internals. Exported only when window exists; refuses
        // anything that is not an fx-configured element.
        Value play = context.eval(Source.create("js",
            "window.LatteXFx && window.LatteXFx.play"));
        assertNotNull(play, "window.LatteXFx.play must be exported");
        assertTrue(play.canExecute(), "play is a function");
        assertFalse(context.eval(Source.create("js",
            "window.LatteXFx.play(null)")).asBoolean(), "null refuses");
        assertFalse(context.eval(Source.create("js",
            "window.LatteXFx.play({})")).asBoolean(), "non-element refuses");
        assertFalse(context.eval(Source.create("js",
            "window.LatteXFx.play({getAttribute: function () { return null; }})")).asBoolean(),
            "element with no fx attributes refuses");
    }

    // ---- pin 0b: fx.unfold toggle (element-anchored, idempotent) ----------------

    @Test
    void unfoldTogglesBetweenCollapsedAndPayloadAndIsIdempotent() {
        // A .lx-math with a collapsed <svg> child and a hidden .lx-fx-expanded payload.
        context.eval(Source.create("js",
            "globalThis.__lxCollapsed = __lxMakeEl({});"
            + "globalThis.__lxPayload = __lxMakeEl({}); globalThis.__lxPayload.hidden = true;"
            + "globalThis.__lxUnfoldEl = __lxMakeEl({});"
            + "globalThis.__lxUnfoldEl.querySelector = function (sel) {"
            + "  if (sel === ':scope > svg') return globalThis.__lxCollapsed;"
            + "  if (sel === ':scope > .lx-fx-expanded') return globalThis.__lxPayload;"
            + "  return null; };"));
        Value el = context.getBindings("js").getMember("__lxUnfoldEl");
        Value collapsed = context.getBindings("js").getMember("__lxCollapsed");
        Value payload = context.getBindings("js").getMember("__lxPayload");

        // First trigger: expand — payload revealed, collapsed hidden, state flagged.
        fx.getMember("unfold").execute(el);
        assertTrue(el.getMember("__lxUnfolded").asBoolean(), "first trigger expands");
        assertFalse(payload.getMember("hidden").asBoolean(), "payload revealed");
        assertEquals("inline-block", payload.getMember("style").getMember("display").asString());
        assertTrue(collapsed.getMember("hidden").asBoolean(), "collapsed hidden");
        assertEquals("none", collapsed.getMember("style").getMember("display").asString());
        // The bloom fade-in commits on the next animation frame.
        context.eval(Source.create("js", "__lxFlushRaf(4)"));
        assertEquals("1", payload.getMember("style").getMember("opacity").asString(), "payload fades in");

        // Second trigger: collapse — the toggle is idempotent (no desync on re-entry).
        fx.getMember("unfold").execute(el);
        assertFalse(el.getMember("__lxUnfolded").asBoolean(), "second trigger collapses");
        assertFalse(collapsed.getMember("hidden").asBoolean(), "collapsed shown again");
        assertTrue(payload.getMember("hidden").asBoolean(), "payload hidden again");
    }

    @Test
    void unfoldWithNoPayloadIsAnInertNoOp() {
        // Host flag off / unsupported sum → no payload sibling. unfold must no-op
        // (and never throw), leaving no state on the element.
        Value el = makeEl("__lxMakeEl({})"); // querySelector returns null (no payload)
        fx.getMember("unfold").execute(el);
        assertFalse(el.hasMember("__lxUnfolded"), "inert unfold sets no toggle state");
    }

    // ---- pin 1: placement compose + transform-origin ---------------------------

    @Test
    void setPathDeltaComposesDeltaInFrontOfPlacementAndPinsOrigin() {
        Value path = makeEl(
            "__lxMakeEl({ transform: 'translate(12.5 30) scale(0.04 -0.04)' })");

        fx.getMember("setPathDelta").execute(path, "translate(3px,4px)");

        // THE PIN (lattex-fx.js setPathDelta): transform-origin must be 0 0 — the
        // CSS transform property otherwise applies about the viewBox centre and
        // scrunches every glyph into a bar. Deleting the pin line fails HERE.
        assertEquals("0px 0px", path.getMember("style").getMember("transformOrigin").asString(),
            "transform-origin must be pinned to the user-space origin");
        // Compose order: delta FIRST, then the placement replay — reversing or
        // dropping the placement (the blob bug) fails here.
        assertEquals("translate(3px,4px) translate(12.5px,30px) scale(0.04,-0.04)",
            path.getMember("style").getMember("transform").asString(),
            "motion delta must compose IN FRONT of the placement replay");
    }

    @Test
    void clearPathDeltaRestoresTheAttributeRule() {
        Value path = makeEl(
            "__lxMakeEl({ transform: 'translate(12.5 30) scale(0.04 -0.04)' })");
        fx.getMember("setPathDelta").execute(path, "translate(1px,1px)");
        fx.getMember("clearPathDelta").execute(path);

        assertEquals("", path.getMember("style").getMember("transform").asString());
        assertEquals("", path.getMember("style").getMember("transformOrigin").asString());
    }

    @Test
    void pathWithoutPlacementPassesDeltaThrough() {
        Value path = makeEl("__lxMakeEl({})");

        fx.getMember("setPathDelta").execute(path, "translate(5px,6px)");

        assertEquals("translate(5px,6px)",
            path.getMember("style").getMember("transform").asString(),
            "no placement attribute -> the delta rides alone");
        assertEquals("0px 0px", path.getMember("style").getMember("transformOrigin").asString());
    }

    @Test
    void pivotScaleDeltaScalesAboutTheGlyphsOwnUserSpaceCentre() {
        // bbox 0,0,100x50 (stub) pushed through translate(12.5 30) scale(0.04 -0.04):
        // centre = (12.5 + 0.04*50, 30 + -0.04*25) = (14.5, 29).
        Value path = makeEl(
            "__lxMakeEl({ transform: 'translate(12.5 30) scale(0.04 -0.04)' })");

        String delta = fx.getMember("pivotScaleDelta").execute(path, 2).asString();

        assertEquals("translate(14.50px,29.00px) scale(2) translate(-14.50px,-29.00px)", delta,
            "pivot scale must sandwich about the placed centre, not the origin");
    }

    // ---- pin 1b: emitter format <-> placement() regex coupling -----------------

    @Test
    void placementRegexParsesTheRealEmitterTransformString() {
        // The runtime's placement() regex and SvgEmitter's transform attribute are a
        // cross-language contract with no shared constant. Pin them END-TO-END: render
        // real math in-JVM, lift an actual path transform out of the SVG, and require
        // the runtime to parse it. An emitter format change that placement() cannot
        // read fails HERE (previously: silent inertness in the browser).
        String svg = LatteX.render("x+1");
        Matcher m = Pattern.compile("<path[^>]*transform=\"([^\"]+)\"").matcher(svg);
        assertTrue(m.find(), "rendered SVG must carry a path placement transform");
        String emitted = m.group(1);

        Value path = makeEl("__lxMakeEl({})");
        path.getMember("setAttribute").execute("transform", emitted);
        Value place = fx.getMember("placement").execute(path);

        assertFalse(place.isNull(),
            "placement() must parse the real emitter transform: " + emitted);
        assertTrue(place.getMember("sx").asDouble() > 0,
            "parsed x-scale must be the emitter's positive downscale");
        assertTrue(place.getMember("sy").asDouble() < 0,
            "parsed y-scale must be the emitter's negative (y-flip) downscale");
        String css = place.getMember("css").asString();
        assertTrue(css.startsWith("translate(") && css.contains("scale("),
            "css replay must round-trip the placement: " + css);
    }

    // ---- pin 2: thread glyphmap grammar -----------------------------------------

    private Value parseGlyphmap(String raw, int pathCount) {
        Value el = makeEl("__lxMakeEl({ 'data-lx-glyphmap': "
            + (raw == null ? "null" : "'" + raw + "'") + " })");
        return fx.getMember("parseGlyphmap").execute(el, pathCount);
    }

    @Test
    void glyphmapGroupsEveryMemberOfATokenGroupTogether() {
        Value groups = parseGlyphmap("2b:0,2;31:1", 3);

        assertFalse(groups.isNull(), "contract-grammar map must parse");
        // Both members of the 2b group map to the SAME [0,2] group array.
        Value group0 = groups.getMember("0");
        Value group2 = groups.getMember("2");
        assertEquals(2, group0.getArraySize());
        assertEquals(0, group0.getArrayElement(0).asInt());
        assertEquals(2, group0.getArrayElement(1).asInt());
        assertEquals(2, group2.getArraySize());
        // The singleton group carries just itself.
        assertEquals(1, groups.getMember("1").getArraySize());
        assertEquals(1, groups.getMember("1").getArrayElement(0).asInt());
    }

    @Test
    void glyphmapRefusesTheWholeMapOnAnyOutOfBoundsIndex() {
        // Index 9 >= pathCount 3: the WHOLE map is refused (defense in depth — a
        // partially-honored map could light the wrong glyphs), not just the bad run.
        assertTrue(parseGlyphmap("2b:0,9;31:1", 3).isNull(),
            "out-of-bounds index must refuse the whole map");
    }

    @Test
    void glyphmapRejectsNonContractGrammar() {
        assertTrue(parseGlyphmap(null, 3).isNull(), "missing attribute -> inert");
        assertTrue(parseGlyphmap("", 3).isNull(), "empty -> inert");
        assertTrue(parseGlyphmap("2B:0", 3).isNull(), "uppercase hex is outside the grammar");
        assertTrue(parseGlyphmap("2b:", 3).isNull(), "dangling colon");
        assertTrue(parseGlyphmap("2b:0,,1", 3).isNull(), "double comma");
        assertTrue(parseGlyphmap("2b:0;;31:1", 3).isNull(), "double semicolon");
        assertTrue(parseGlyphmap("2b:0 ,1", 3).isNull(), "whitespace is outside the grammar");
        assertTrue(parseGlyphmap("g1:0", 3).isNull(), "non-hex token id");
    }

    @Test
    void glyphmapAcceptsBoundaryIndexExactlyBelowPathCount() {
        // Boundary: index pathCount-1 is valid; pathCount is not.
        assertFalse(parseGlyphmap("2b:2", 3).isNull(), "index pathCount-1 must be accepted");
        assertTrue(parseGlyphmap("2b:3", 3).isNull(), "index == pathCount must refuse");
    }
}
