package com.lattex.fx;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx-runtime lifecycle pins (plan e09b28be S2+S3): the leak-fix contracts from the
 * 2026-07-06 review, pinned behaviorally against the REAL lattex-fx.js in GraalJS.
 *
 * <ul>
 *   <li>hologram (review HIGH): the idle interval, resize listener, and body overlay
 *       must ALL release on scroll teardown, and the element's styles restore.</li>
 *   <li>neonsign (review HIGH): re-entry must not stack a second perpetual flicker
 *       loop; scroll-away clears the timer and the guard.</li>
 *   <li>matrixrain (review LOW): re-entry guard holds while active; teardown
 *       restores position/isolation and clears the guard.</li>
 *   <li>constellation (review MEDIUM): the zero-star degrade must REVEAL the math
 *       (the CSS pre-hide would otherwise leave it invisible forever).</li>
 *   <li>scrollKillable: sub-4px scroll jitter (load-restoration events) must NOT
 *       kill a show; a real scroll tears down exactly once and releases the
 *       listener.</li>
 *   <li>prefers-reduced-motion: effects boot inert — no intervals, no flicker
 *       timers, no rain guard.</li>
 * </ul>
 */
class FxRuntimeLifecycleTest {

    private Context context;
    private Value fx;

    private void boot(boolean reducedMotion) throws IOException {
        context = Context.newBuilder("js").build();
        if (reducedMotion) {
            context.eval(Source.create("js", "globalThis.__lxStubReducedMotion = true;"));
        }
        context.eval(source("/com/lattex/fx/harness-dom-stub.js", "harness-dom-stub.js"));
        context.eval(Source.create("js",
            "globalThis.__lxTestHook = function (internals) { globalThis.__lxInternals = internals; };"));
        context.eval(source("/com/lattex/fx/lattex-fx.js", "lattex-fx.js"));
        fx = context.getBindings("js").getMember("__lxInternals");
        assertNotNull(fx);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private static Source source(String resource, String name) throws IOException {
        try (InputStream in = FxRuntimeLifecycleTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing classpath resource " + resource);
            return Source.newBuilder("js", new String(in.readAllBytes(), StandardCharsets.UTF_8), name)
                .buildLiteral();
        }
    }

    private Value js(String script) {
        return context.eval(Source.create("js", script));
    }

    private int intOf(String expr) {
        return js(expr).asInt();
    }

    // ---- hologram: full teardown (review HIGH) ---------------------------------

    @Test
    void hologramScrollTeardownReleasesIntervalOverlayAndStyles() throws IOException {
        boot(false);
        Value el = js("globalThis.__el = __lxMakeEl({}, 1); __el");

        fx.getMember("play").execute(el, "hologram", "400ms");
        // Drive the ignition-stutter setTimeout chain until the idle interval arms.
        js("__lxRunTimeouts(20)");

        assertEquals(1, intOf("__lxActiveIntervals()"),
            "the idle wobble interval must be armed after ignition");
        assertEquals(1, intOf("__lxBodyChildren()"),
            "the scanline overlay must be on the body while the show runs");
        assertTrue(intOf("__lxScrollListeners()") >= 1, "the kill listener must be armed");
        assertEquals("#5fe8ff", el.getMember("style").getMember("color").asString(),
            "the hologram tint is applied while alive");

        js("__lxFireScroll(0, 10)");

        // The review-HIGH leak: interval, listener, and overlay outlived the element.
        assertEquals(0, intOf("__lxActiveIntervals()"),
            "teardown must clearInterval the idle loop");
        assertEquals(0, intOf("__lxBodyChildren()"),
            "teardown must remove the scanline overlay");
        assertEquals(0, intOf("__lxScrollListeners()"),
            "the kill listener must release itself");
        assertFalse("#5fe8ff".equals(valueOrEmpty(el.getMember("style").getMember("color"))),
            "teardown must restore the pristine color, not keep the tint");
        assertFalse(el.getMember("_lxHolo").asBoolean(),
            "the re-entry guard must clear so a fresh show can arm later");
    }

    private static String valueOrEmpty(Value v) {
        return v == null || v.isNull() ? "" : v.asString();
    }

    // ---- neonsign: single loop + scroll teardown (review HIGH) ------------------

    @Test
    void neonsignReentryDoesNotStackASecondFlickerLoop() throws IOException {
        boot(false);
        Value el = js("globalThis.__el = __lxMakeEl({}, 3); __el");

        fx.getMember("play").execute(el, "neonsign", "400ms");
        int pendingAfterFirst = intOf("__lxActiveTimeouts()");
        assertTrue(pendingAfterFirst >= 1, "the ignition sequence + flicker loop must arm timers");

        // The review-HIGH bug: every hover re-entry stacked a fresh perpetual loop.
        fx.getMember("play").execute(el, "neonsign", "400ms");
        assertEquals(pendingAfterFirst, intOf("__lxActiveTimeouts()"),
            "re-entry while armed must schedule NOTHING new");

        // Let the loop run a few self-rescheduling rounds, then scroll away.
        js("__lxRunTimeouts(4)");
        assertTrue(intOf("__lxActiveTimeouts()") >= 1, "the flicker loop keeps rescheduling itself");
        js("__lxFireScroll(0, 10)");
        js("__lxRunTimeouts(4)"); // a killed loop must not re-arm

        assertEquals(0, intOf("__lxActiveTimeouts()"),
            "scroll teardown must clear the pending flicker timer for good");
        assertFalse(el.getMember("__lxNeon").asBoolean(),
            "the guard must clear on teardown so a fresh show can arm later");
    }

    // ---- matrixrain: guard + restore (review LOW) --------------------------------

    @Test
    void matrixrainGuardsReentryAndRestoresStylesOnTeardown() throws IOException {
        boot(false);
        Value el = js("globalThis.__el = __lxMakeEl({}, 2); __el");

        fx.getMember("play").execute(el, "matrixrain", "400ms");
        assertTrue(el.getMember("__lxRain").asBoolean(), "the rain guard must arm");
        assertEquals("isolate", el.getMember("style").getMember("isolation").asString(),
            "the rain isolates its stacking context while active");
        int listeners = intOf("__lxScrollListeners()");

        // Re-entry while active must be a no-op (would corrupt the style restore).
        fx.getMember("play").execute(el, "matrixrain", "400ms");
        assertEquals(listeners, intOf("__lxScrollListeners()"),
            "a re-trigger while active must not arm a second kill listener");

        js("__lxFireScroll(0, 10)");
        assertFalse(el.getMember("__lxRain").asBoolean(), "teardown must clear the guard");
        assertFalse("isolate".equals(valueOrEmpty(el.getMember("style").getMember("isolation"))),
            "teardown must restore the pristine isolation, not keep 'isolate'");
    }

    // ---- constellation: zero-star degrade reveals the math (review MEDIUM) ------

    @Test
    void constellationWithUnsampleablePathsRevealsTheMathAndBails() throws IOException {
        boot(false);
        // Stub paths have no getTotalLength/getScreenCTM -> star sampling yields
        // nothing, exactly like a display:none or detached container at load.
        Value el = js("globalThis.__el = __lxMakeEl({}, 2); __el");

        fx.getMember("play").execute(el, "constellation", "400ms");

        assertEquals("1", el.getMember("style").getMember("opacity").asString(),
            "zero stars must REVEAL the equation (the CSS pre-hide would otherwise"
                + " leave it permanently invisible)");
        assertFalse(el.getMember("__lxConst").asBoolean(),
            "the guard must clear on the degrade path");
        assertEquals(0, intOf("__lxBodyChildren()"), "no star canvas on the degrade path");
        assertEquals(0, intOf("__lxActiveIntervals()"), "no twinkle loop on the degrade path");
    }

    // ---- scrollKillable: jitter threshold + exactly-once (S3) --------------------

    @Test
    void scrollKillableIgnoresSubFourPixelJitterAndKillsExactlyOnce() throws IOException {
        boot(false);
        js("globalThis.__kills = 0;");
        Value die = fx.getMember("scrollKillable")
            .execute(js("(function () { globalThis.__kills++; })"));
        int armed = intOf("__lxScrollListeners()");
        assertTrue(armed >= 1);

        // Browsers fire spurious scroll events at load ("sparkler seems to not
        // always load"): a <4px wiggle must NOT end the show.
        js("__lxFireScroll(0, 3)");
        assertFalse(die.getMember("dead").execute().asBoolean(),
            "sub-threshold jitter must not kill the show");
        assertEquals(0, intOf("__kills"));

        js("__lxFireScroll(0, 10)");
        assertTrue(die.getMember("dead").execute().asBoolean(), "a real scroll kills");
        assertEquals(1, intOf("__kills"), "teardown runs exactly once");
        assertEquals(armed - 1, intOf("__lxScrollListeners()"),
            "the kill listener releases itself");

        js("__lxFireScroll(0, 50)");
        assertEquals(1, intOf("__kills"), "a dead show never tears down twice");
    }

    // ---- prefers-reduced-motion: inert boot (S3) ---------------------------------

    @Test
    void reducedMotionBootsEffectsInert() throws IOException {
        boot(true);
        Value holo = js("globalThis.__h = __lxMakeEl({}, 1); __h");
        Value neon = js("globalThis.__n = __lxMakeEl({}, 3); __n");
        Value rain = js("globalThis.__r = __lxMakeEl({}, 2); __r");

        fx.getMember("play").execute(holo, "hologram", "400ms");
        js("__lxRunTimeouts(10)");
        fx.getMember("play").execute(neon, "neonsign", "400ms");
        fx.getMember("play").execute(rain, "matrixrain", "400ms");

        assertEquals(0, intOf("__lxActiveIntervals()"),
            "reduced motion must arm NO idle loops");
        assertEquals(0, intOf("__lxActiveTimeouts()"),
            "reduced motion must arm NO flicker/ignition timers");
        Value rainGuard = rain.getMember("__lxRain");
        assertTrue(rainGuard == null || rainGuard.isNull() || !rainGuard.asBoolean(),
            "matrixrain must fully decline under reduced motion");
        assertTrue(valueOrEmpty(neon.getMember("style").getMember("filter")).contains("drop-shadow"),
            "neonsign's reduced path still applies the steady glow (visible, just static)");
    }
}
