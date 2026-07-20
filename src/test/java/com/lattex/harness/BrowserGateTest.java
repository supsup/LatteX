package com.lattex.harness;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins {@link BrowserGate}'s contract through its injection seam — most importantly the
/// FAIL-CLOSED arm, which otherwise only executes on a broken CI runner: require-mode with no
/// browser must be a test FAILURE (red build), never a skip (green build minus the safety net).
class BrowserGateTest {

    @Test
    void requireModeWithoutBrowserFailsTheBuild() {
        AssertionFailedError e = assertThrows(AssertionFailedError.class,
            () -> BrowserGate.browserPin(false, "1"));
        assertTrue(e.getMessage().contains("LATTEX_REQUIRE_BROWSER"),
            "the failure must name the require flag so the operator knows why CI went red");
    }

    @Test
    void requireModeWithBrowserProceeds() {
        assertDoesNotThrow(() -> BrowserGate.browserPin(true, "1"));
    }

    @Test
    void withoutRequireModeAnUnavailableBrowserSkips() {
        assertThrows(TestAbortedException.class, () -> BrowserGate.browserPin(false, null),
            "local no-Chrome runs keep the assumption-skip behavior");
    }

    @Test
    void withoutRequireModeAnAvailableBrowserProceeds() {
        assertDoesNotThrow(() -> BrowserGate.browserPin(true, null));
    }
}
