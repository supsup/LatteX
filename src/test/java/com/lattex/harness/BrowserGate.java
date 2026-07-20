package com.lattex.harness;

import com.brewshot.BrewShot;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The ONE browser-availability gate for the real-browser pins (blob audit, fx lifecycle, GIF
/// liveness). A local run without Chrome assume-skips — the pins are environment-dependent, not
/// optional. Under `LATTEX_REQUIRE_BROWSER=1` (set by CI, whose runner verifies Chrome) an
/// unavailable browser FAILS the pin instead: a runner-image or discovery regression must never
/// silently remove the whole browser safety net while the build stays green and still calls
/// itself the full gate (review lattex 246 F2 — the reviewer proved all five pins could
/// assumption-skip with the forced suite passing 509/0/0/5).
final class BrowserGate {
    private BrowserGate() {}

    /// Call at the top of every real-browser pin, replacing the bare
    /// `assumeTrue(BrewShot.available(), …)` idiom.
    static void browserPin() {
        browserPin(BrewShot.available(), System.getenv("LATTEX_REQUIRE_BROWSER"));
    }

    /// Seam for {@link BrowserGateTest}: the fail-closed arm must be provable red, not
    /// code-read-only — a gate whose failure path no test injects is the fail-open defect
    /// one layer up.
    static void browserPin(boolean available, String requireEnv) {
        if ("1".equals(requireEnv)) {
            if (!available) {
                fail("LATTEX_REQUIRE_BROWSER=1 but no Chrome is available — the browser pins"
                    + " would silently skip; install/verify Chrome on this runner");
            }
            return;
        }
        assumeTrue(available, "no local Chrome; skipping browser pin");
    }
}
