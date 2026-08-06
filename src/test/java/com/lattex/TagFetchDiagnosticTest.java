package com.lattex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// Pins the ONE case [TagFetchDiagnostic] exists to catch, and pins that it stays quiet
/// everywhere else (review lattex/828).
///
/// The hint is deliberately parameterised on the tag count rather than reading git itself,
/// so the zero-tag case is directly testable. Testing it through the ambient repo would be
/// impossible — this checkout HAS tags, which is exactly the state where the hint must not
/// fire, so an ambient-only test could only ever verify the silent half.
class TagFetchDiagnosticTest {

    @Test
    void aTaglessCheckoutIsNamedAsAPOSSIBLEUnfetchedCheckout() {
        String hint = TagFetchDiagnostic.unfetchedCheckoutHint(0);
        assertTrue(hint.contains("fetch-tags"),
            "the hint must name the concrete fix (`fetch-tags: true`) — a diagnostic that says "
                + "'something is wrong' without naming the lever is the vague-error problem this "
                + "guard exists to remove; got: " + hint);
        assertTrue(hint.contains("ls-remote"),
            "the hint must name how to CHECK which of the two causes it is, rather than asserting "
                + "one; got: " + hint);
    }

    @Test
    void aCheckoutThatHasTagsGetsNoHint() {
        // The silent half, and the load-bearing one: when tags ARE present, "no matching tag"
        // is a true statement and must not be muddied by a speculative fetch explanation.
        assertEquals("", TagFetchDiagnostic.unfetchedCheckoutHint(1));
        assertEquals("", TagFetchDiagnostic.unfetchedCheckoutHint(7));
    }

    @Test
    void aFailedProbeGetsNoHint() {
        // -1 means the `git tag` probe itself could not run. A diagnostic must not invent a
        // confident explanation from a measurement that failed — the same stance the guards
        // themselves take about git being unavailable.
        assertEquals("", TagFetchDiagnostic.unfetchedCheckoutHint(-1));
    }

    @Test
    void theProbeCanReadTheAmbientRepository() {
        // POSITIVE CONTROL. If totalTags() silently returned -1 everywhere — wrong binary,
        // sandboxed process, changed git output — the hint would never fire in production
        // and the tests above would still pass against the pure function. This checkout is a
        // real git repo, so the probe must succeed here.
        assertTrue(TagFetchDiagnostic.totalTags() >= 0,
            "git tag could not be consulted from the test working dir — TagFetchDiagnostic is "
                + "INERT in this environment, so the hint would never fire where it matters");
    }
}
