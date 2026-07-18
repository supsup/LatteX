package com.lattex.harness;

import com.brewshot.BrewShot;
import com.brewshot.MiniJson;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Records showpiece effects as looping GIFs (beside the example pages via
 * {@code generateExamples}; into {@code build/brewshot-refs} during {@code test}) — the
 * "hover state you can't see in a still" (Charles). Each capture: scroll the
 * card into view, dispatch the trigger, then grab clipped frames of just that
 * card while the animation runs; JDK-only GIF assembly ({@link GifWriter}).
 *
 * <p>References, not goldens: frames differ run to run (randomized effects).
 * The assertion is liveness — the animation must actually CHANGE pixels across
 * frames (a dead trigger or a no-op effect produces identical frames and
 * fails), which pins trigger wiring + animation liveness per effect.
 */
@Tag("capture") // liveness pin stays in `test` (GIFs land in build/);
                // `generateExamples` re-runs it writing beside the pages (plan 32148cc8 S2)
class BrewShotFxGifTest {

    private static final int FRAMES = 14;
    private static final int FRAME_DELAY_MS = 110;

    /** effect → trigger kind; the showpieces Charles called out + one composer. */
    private static final List<String[]> SHOWPIECES = List.of(
        new String[] {"glitch", "hover"},
        new String[] {"shatter", "click"},
        new String[] {"wobble", "enter"},   // rides setPathDelta: the compose path on film
        new String[] {"precedence", "play"}, // fired via the PUBLIC LatteXFx.play trigger (plan 51051447)
        new String[] {"precedence", "enter"} // AUTOPLAY pin: reload liveness reds if the enter-autoplay line is deleted
    );

    private static Path examples() {
        return Path.of("examples").toAbsolutePath();
    }

    /** Where captured references land: beside the pages when regenerating
     * (`generateExamples` sets {@code -Dlattex.examples.write=true}), into
     * {@code build/brewshot-refs} during `test` so the working tree stays
     * clean (plan 32148cc8 S2). */
    private static Path refsOut() throws java.io.IOException {
        Path dir = Boolean.getBoolean("lattex.examples.write")
            ? examples() : Path.of("build", "brewshot-refs").toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void recordsShowpieceGifsBesideTheHtml() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping browser pin");
        Path page = examples().resolve("effects.html");
        assumeTrue(Files.exists(page), "examples/effects.html not generated");
        assumeTrue(Files.readString(page).contains("data-lx-fx-overlay"),
            "stale examples/effects.html (predates the current runtime) — "
                + "run ./gradlew generateExamples and commit the refreshed page");

        try (BrewShot chrome = BrewShot.launch(1200, 900)) {
            chrome.open(page.toUri().toString());
            chrome.settle(1500); // let load-time enter effects finish

            for (String[] piece : SHOWPIECES) {
                String effect = piece[0];
                String trigger = piece[1];
                recordOne(chrome, page.toUri().toString(), effect, trigger);
            }
        }
    }

    private void recordOne(BrewShot chrome, String pageUrl, String effect, String trigger)
            throws Exception {
        // Locate the card, scroll it into view, return its page-coordinate rect.
        // 'play' showpieces are authored as hover effects but FIRED via the public
        // programmatic trigger — locate by the hover attribute.
        String locateTrig = trigger.equals("play") ? "hover" : trigger;
        Object rect = chrome.eval("""
            (function () {
              var el = document.querySelector('[data-lx-fx-%TRIG%="%FX%"]');
              if (!el) { return null; }
              var card = el.closest('figure.card') || el;
              card.scrollIntoView({block: 'center'});
              var r = card.getBoundingClientRect();
              // Small margin so overlay effects (glitch offsets, shatter
              // shards) that spill past the card edge stay in frame.
              var m = 12;
              return { x: r.left + window.pageXOffset - m,
                       y: r.top + window.pageYOffset - m,
                       w: r.width + 2 * m, h: r.height + 2 * m };
            })()
            """.replace("%TRIG%", locateTrig).replace("%FX%", effect));
        assertTrue(rect instanceof Map, "no card found for fx." + trigger + "=" + effect);
        double x = (Double) MiniJson.get(rect, "x"), y = (Double) MiniJson.get(rect, "y");
        double w = (Double) MiniJson.get(rect, "w"), h = (Double) MiniJson.get(rect, "h");

        chrome.settle(250); // scrollIntoView settles (and any scroll-kills fire)

        // Fire the trigger. hover/click dispatch the element's own listener
        // path; 'enter' effects only play at load (play() lives inside the
        // IIFE), so re-navigate and record the fresh load immediately. The
        // page-coordinate rect stays valid across the reload (same layout).
        if (trigger.equals("enter")) {
            chrome.open(pageUrl);
            chrome.settle(60); // catch the animation young
        } else {
            String fire = switch (trigger) {
                case "hover" -> "el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}))";
                case "play" -> "window.LatteXFx.play(el)"; // the public trigger, end-to-end
                default -> "el.click()";
            };
            chrome.eval("""
                (function () {
                  var el = document.querySelector('[data-lx-fx-%TRIG%="%FX%"]');
                  %FIRE%;
                  return true;
                })()
                """.replace("%TRIG%", locateTrig).replace("%FX%", effect)
                   .replace("%FIRE%", fire));
        }

        List<byte[]> frames = new ArrayList<>(FRAMES);
        for (int i = 0; i < FRAMES; i++) {
            frames.add(chrome.screenshotClip(x, y, w, h));
            chrome.settle(FRAME_DELAY_MS);
        }

        // Liveness: a dead trigger yields identical frames.
        boolean anyChange = false;
        for (int i = 1; i < frames.size() && !anyChange; i++) {
            anyChange = !java.util.Arrays.equals(frames.get(0), frames.get(i));
        }
        assertTrue(anyChange, "fx." + trigger + "=" + effect
            + " produced identical frames — trigger dead or animation inert");

        // Any-pixel-change liveness is FALSE-GREEN for enter=precedence (page-load
        // rendering noise changes pixels even with autoplay deleted — survived-mutant
        // lesson, executed 2026-07-14). Pin the cascade's own signature instead: the
        // resolved paths carry inline stroke-width, which ONLY lightUpTo() writes.
        if (effect.equals("precedence")) {
            Object lit = chrome.eval("""
                (function () {
                  var svg = document.querySelector('[data-lx-fx-%TRIG%="precedence"] svg');
                  if (!svg) { return 'NO-SVG'; }
                  var ps = svg.querySelectorAll('path');
                  for (var i = 0; i < ps.length; i++) {
                    if (ps[i].style.strokeWidth) { return true; }
                  }
                  return false;
                })()""".replace("%TRIG%", locateTrig));
            assertTrue(Boolean.TRUE.equals(lit), "fx." + trigger + "=precedence: no path "
                + "carries the cascade stroke-width signature — the cascade never ran");
        }

        Path out = refsOut().resolve("fx-" + trigger + "-" + effect + ".gif");
        BrewShot.gif(frames, FRAME_DELAY_MS, out);
        assertTrue(Files.size(out) > 5_000, "suspiciously small GIF at " + out);
    }
}
