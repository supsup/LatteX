package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Source-mirror drift-guard for the lattex/126 parity contract (Confluence's
 * contribution to L6.2, upgrading the local enum-name pin). A consumer that
 * renders BOTH Sirentide diagrams and LatteX math (the Stafficy MathMarkerConverter)
 * switches over ONE shared {@code Outcome} vocabulary and reads ONE shared
 * {@code Diagnostics} core — so these must not silently diverge.
 *
 * <p>LatteX and Sirentide are separate repos, so this can't compile-time reference
 * {@code com.sirentide.api.*}; instead it MIRRORS the canonical shape (verified by
 * hand against Sirentide {@code main} on 2026-07-11) and pins LatteX's side to it.
 * If a LatteX-side change trips this, reconcile with the canonical source and update
 * BOTH the mirror below AND Sirentide — never just this file.
 *
 * <p>Canonical sources (Sirentide repo):
 * <ul>
 *   <li>{@code com.sirentide.api.Outcome} — the classification enum</li>
 *   <li>{@code com.sirentide.api.Diagnostics} — {@code (Outcome, String stage,
 *       String message, int line, String detail)}</li>
 * </ul>
 */
class DiagnosticsParityDriftGuardTest {

    /** MIRROR of com.sirentide.api.Outcome — names AND order (verified 2026-07-11). */
    private static final List<String> SIRENTIDE_OUTCOME_MIRROR = List.of(
        "OK", "PARSE_ERROR", "OUTPUT_CAP_EXCEEDED", "UNSUPPORTED_CONSTRUCT", "RENDER_BUG");

    /** MIRROR of com.sirentide.api.Diagnostics core components — name+type, in order. */
    private static final List<String> SIRENTIDE_DIAGNOSTICS_CORE_MIRROR = List.of(
        "outcome:com.lattex.api.Outcome",   // Outcome is the LATTEX enum, name-parity pinned above
        "stage:java.lang.String",
        "message:java.lang.String",
        "line:int",
        "detail:java.lang.String");

    @Test
    void outcomeEnumMirrorsSirentideNamesAndOrderExactly() {
        List<String> lattex = Arrays.stream(Outcome.values()).map(Enum::name).toList();
        assertEquals(SIRENTIDE_OUTCOME_MIRROR, lattex,
            "LatteX Outcome drifted from the canonical com.sirentide.api.Outcome vocabulary "
            + "(lattex/126 parity contract). Reconcile BOTH repos, not just the mirror.");
    }

    @Test
    void diagnosticsCoreFieldsMirrorSirentideExactly() {
        RecordComponent[] rc = Diagnostics.class.getRecordComponents();
        // Core 5 must match Sirentide field-for-field (name + type, in order)...
        List<String> core = Arrays.stream(rc)
            .limit(5)
            .map(c -> c.getName() + ":" + c.getType().getName())
            .toList();
        assertEquals(SIRENTIDE_DIAGNOSTICS_CORE_MIRROR, core,
            "LatteX Diagnostics core drifted from com.sirentide.api.Diagnostics — the shared "
            + "consumer path reads these five; changing any name/type/order breaks it.");
    }

    @Test
    void diagnosticsLatteXOnlyTailIsAppendedAfterTheSharedCore() {
        RecordComponent[] rc = Diagnostics.class.getRecordComponents();
        // ...and the LatteX-only progressive-enhancement fields come STRICTLY after the
        // shared core, so the parity of the first five can never be disturbed by them.
        assertEquals(7, rc.length, "expected 5 shared-core + 2 LatteX-only components");
        assertEquals("offset", rc[5].getName());
        assertEquals(int.class, rc[5].getType());
        assertEquals("caretString", rc[6].getName());
        assertEquals(String.class, rc[6].getType());
    }

    @Test
    void latteXEmitsOnlyThePromisedV1Subset() {
        // v1 emits OK / PARSE_ERROR / RENDER_BUG; the reserved members exist for the
        // shared vocabulary but must remain part of the enum so a consumer's switch is
        // exhaustive. This pins that they are PRESENT (not that they're emitted yet).
        List<String> names = Arrays.stream(Outcome.values()).map(Enum::name).toList();
        assertTrue(names.containsAll(List.of("OK", "PARSE_ERROR", "RENDER_BUG")));
        assertTrue(names.containsAll(List.of("OUTPUT_CAP_EXCEEDED", "UNSUPPORTED_CONSTRUCT")),
            "reserved members must stay in the shared vocabulary");
    }
}
