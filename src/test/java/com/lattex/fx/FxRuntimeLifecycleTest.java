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

    // ---- cancel: the exactly-twice strike-and-puff (semantic effect #3) -----------

    private static final String PAIR = "'data-lx-glyphmap': '78:0,1'"; // x (0x78) occurs twice

    /** Reduced motion: snap to the static grayed end-state — struck glyphs at the ghost
     *  opacity, a static strike overlay, and NO animation timers/frames. */
    @Test
    void cancelReducedMotionSnapsToTheStaticGrayedEndState() throws IOException {
        boot(true);
        Value el = js("globalThis.__c = __lxMakeEl({" + PAIR + "}, 2); __c");

        fx.getMember("play").execute(el, "cancel", "400ms");

        assertEquals("0.18", js("__c.__paths[0].style.opacity").asString(),
            "reduced motion must snap the first x to the grayed ghost");
        assertEquals("0.18", js("__c.__paths[1].style.opacity").asString(),
            "reduced motion must snap the second x to the grayed ghost");
        assertEquals(1, intOf("__lxBodyChildren()"),
            "the static strike overlay is present in the reduced end-state");
        assertEquals(0, intOf("__lxActiveTimeouts()"),
            "reduced motion must schedule NO puff/settle timers");
        assertEquals(0, intOf("__lxActiveIntervals()"), "reduced motion arms no loops");
    }

    /** Full run: a code point occurring exactly twice strikes (body overlay) then puffs
     *  BOTH glyphs to the grayed ghost, and the overlay tears down leaving only the ghost. */
    @Test
    void cancelStrikesTheExactlyTwicePairAndPuffsToTheGhost() throws IOException {
        boot(false);
        Value el = js("globalThis.__c = __lxMakeEl({" + PAIR + "}, 2); __c");

        fx.getMember("play").execute(el, "cancel", "400ms");
        assertEquals(1, intOf("__lxBodyChildren()"),
            "the strike overlay arms on a body-level element (never the inner <svg>)");

        js("__lxRunTimeouts(6)"); // drive strike-hold → puff-up → settle → overlay-fade
        assertEquals("0.18", js("__c.__paths[0].style.opacity").asString(),
            "the first x settles to the grayed ghost");
        assertEquals("0.18", js("__c.__paths[1].style.opacity").asString(),
            "the second x settles to the grayed ghost");
        assertEquals(0, intOf("__lxBodyChildren()"),
            "the strike overlay is removed after the puff, leaving only the ghost glyphs");
        assertEquals(0, intOf("__lxActiveTimeouts()"), "no puff/settle timers leak");
    }

    /** 3+ occurrences of a code point never form an exactly-twice pair → inert (no overlay,
     *  glyphs untouched), the whole-expression fail-honest posture. */
    @Test
    void cancelIsInertOnThreePlusOccurrences() throws IOException {
        boot(false);
        // x appears three times: one 3-member group, never an exactly-2 pair.
        Value el = js("globalThis.__c = __lxMakeEl({'data-lx-glyphmap': '78:0,1,2'}, 3); __c");

        fx.getMember("play").execute(el, "cancel", "400ms");

        assertEquals(0, intOf("__lxBodyChildren()"), "a 3-member group draws no strike (inert)");
        assertEquals("", valueOrEmpty(js("__c.__paths[0].style.opacity")),
            "an inert cancel leaves the glyphs untouched");
        assertEquals(0, intOf("__lxActiveTimeouts()"), "inert cancel schedules nothing");
    }

    /** Replay is idempotent: a second play tears the first run down before rebuilding,
     *  so overlays never stack (LatteXFx.play / re-trigger safety). */
    @Test
    void cancelReplayIsIdempotentAndDoesNotStackOverlays() throws IOException {
        boot(false);
        Value el = js("globalThis.__c = __lxMakeEl({" + PAIR + "}, 2); __c");

        fx.getMember("play").execute(el, "cancel", "400ms");
        fx.getMember("play").execute(el, "cancel", "400ms");

        assertEquals(1, intOf("__lxBodyChildren()"),
            "a replay must restore the prior run first — exactly one strike overlay, never two");
    }

    // ---- cancel: scroll-abort routes through scrollKillable (reviewer HIGH, lattex 287) ----

    /** Normal-motion scroll-abort: the strike overlay is a position:fixed body {@code <svg>}
     *  drawn from the glyphs' trigger-time rects, so a mid-sequence scroll must tear the WHOLE
     *  show down. Mirrors Lattice's probe for the first assertion (play → scroll → body
     *  children 1 → 0), then proves no puff/settle/fade timer survives to resurrect the overlay
     *  or gray the ghost back in once the clock runs past the ~1.15s sequence, and (my
     *  documented choice) the glyphs fully restore to pristine so a replay re-triggers cleanly. */
    @Test
    void cancelNormalMotionScrollAbortTearsDownOverlayTimersAndGlyphs() throws IOException {
        boot(false);
        Value el = js("globalThis.__c = __lxMakeEl({" + PAIR + "}, 2); __c");

        fx.getMember("play").execute(el, "cancel", "400ms");
        assertEquals(1, intOf("__lxBodyChildren()"),
            "the strike overlay arms on the body while the sequence runs");
        assertTrue(intOf("__lxActiveTimeouts()") >= 1, "the puff timer is pending mid-run");
        assertTrue(intOf("__lxScrollListeners()") >= 1, "the scroll-kill listener is armed");

        // Lattice's exact probe: a real (>4px) scroll drops the fixed overlay to zero.
        js("__lxFireScroll(0, 10)");
        assertEquals(0, intOf("__lxBodyChildren()"),
            "scroll must remove the fixed strike overlay from the body (was 1)");
        assertEquals(0, intOf("__lxActiveTimeouts()"),
            "scroll teardown must clear every pending puff/settle/fade timer");
        assertEquals(0, intOf("__lxScrollListeners()"),
            "the scroll-kill listener must release itself");
        assertEquals("", valueOrEmpty(js("__c.__paths[0].style.opacity")),
            "scroll-abort restores the first glyph to pristine (documented full-restore)");
        assertEquals("", valueOrEmpty(js("__c.__paths[1].style.opacity")),
            "scroll-abort restores the second glyph to pristine");

        // Advance the clock PAST the whole sequence: no timer may resurrect the overlay or
        // gray the ghost back in (the leak these tests pin).
        js("__lxRunTimeouts(6)");
        assertEquals(0, intOf("__lxBodyChildren()"), "no overlay resurrects after the abort");
        assertEquals(0, intOf("__lxActiveTimeouts()"), "no timer re-arms after the abort");
        assertEquals("", valueOrEmpty(js("__c.__paths[0].style.opacity")),
            "the killed puff must NOT resurrect the grayed ghost after the abort");
        assertEquals("", valueOrEmpty(js("__c.__paths[1].style.opacity")),
            "the killed puff must NOT resurrect the grayed ghost after the abort");
    }

    /** Reduced-motion scroll-abort: the reduced path snaps to a STATIC struck-ghost end-state
     *  with NO timer, so before this fix its fixed overlay hung on the body indefinitely after
     *  a scroll (over unrelated content). Now die() — armed even on the timer-less reduced path
     *  — removes it: overlay gone, listener released, glyphs restored, nothing resurrects. */
    @Test
    void cancelReducedMotionScrollAbortRemovesTheStaticOverlay() throws IOException {
        boot(true);
        Value el = js("globalThis.__c = __lxMakeEl({" + PAIR + "}, 2); __c");

        fx.getMember("play").execute(el, "cancel", "400ms");
        assertEquals(1, intOf("__lxBodyChildren()"),
            "the reduced static strike overlay is present as the end-state");
        assertEquals("0.18", js("__c.__paths[0].style.opacity").asString(),
            "reduced motion grays the first glyph to the ghost");
        assertTrue(intOf("__lxScrollListeners()") >= 1,
            "even the timer-less reduced path must arm the scroll-kill listener");
        assertEquals(0, intOf("__lxActiveTimeouts()"), "reduced motion schedules no timers");

        // The bug: the static overlay had no timer and outlived a scroll indefinitely.
        js("__lxFireScroll(0, 10)");
        assertEquals(0, intOf("__lxBodyChildren()"),
            "scroll must remove the otherwise-permanent reduced overlay (was 1)");
        assertEquals(0, intOf("__lxScrollListeners()"),
            "the scroll-kill listener must release itself");
        assertEquals("", valueOrEmpty(js("__c.__paths[0].style.opacity")),
            "scroll-abort restores the first glyph to pristine (documented full-restore)");
        assertEquals("", valueOrEmpty(js("__c.__paths[1].style.opacity")),
            "scroll-abort restores the second glyph to pristine");

        // No timer exists to resurrect anything; advancing the clock keeps it clean.
        js("__lxRunTimeouts(6)");
        assertEquals(0, intOf("__lxBodyChildren()"), "nothing resurrects the reduced overlay");
        assertEquals(0, intOf("__lxActiveTimeouts()"), "no timer appears after the abort");
    }
}
