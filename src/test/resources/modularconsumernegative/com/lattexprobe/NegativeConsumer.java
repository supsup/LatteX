package com.lattexprobe;

import com.lattex.api.LatteX;
import com.lattex.api.LatteXException;

/**
 * The fence probe: a modular consumer that tries to name the NON-exported concrete
 * exception type {@code com.lattex.parse.MathSyntaxException}.
 *
 * <p>This MUST NOT compile. {@code com.lattex} exports only {@code com.lattex.api}, so
 * {@code com.lattex.parse} is invisible to any named module that requires it. The
 * exported {@link LatteXException} supertype is the supported handle instead — adding it
 * must NOT have widened the fence.
 *
 * <p>Every line marked {@code // FENCE-PROBE} is the offending one. {@code
 * ModularBoundaryTest} compiles this file twice: verbatim (must FAIL, and specifically
 * on {@code com.lattex.parse} not being visible) and with the marked lines stripped
 * (must SUCCEED) — the positive control proving the failure is caused by the fence and
 * not by an unrelated defect in the fixture.
 */
public final class NegativeConsumer {

    private NegativeConsumer() {
    }

    /** Renders {@code latex}, attempting to catch the non-exported concrete type. */
    public static String render(String latex) {
        try {
            return "OK:" + LatteX.render(latex);
        } catch (com.lattex.parse.MathSyntaxException e) { // FENCE-PROBE
            return "CAUGHT-CONCRETE:" + e.getMessage(); // FENCE-PROBE
        } catch (LatteXException e) {
            return "CAUGHT:" + e.getMessage();
        }
    }
}
