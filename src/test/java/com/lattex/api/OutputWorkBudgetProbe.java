package com.lattex.api;

import com.lattex.parse.MathSyntaxException;

/** Standalone small-heap public-API probe; an escaping Error must make the fork fail. */
public final class OutputWorkBudgetProbe {
    private OutputWorkBudgetProbe() { }

    static String witness(String command) {
        // ~9.5k source chars, 900 macro calls, ~900 text tokens/boxes, but 4.5M
        // materialized characters. Parser splice volume is intentionally well below
        // its limit: a TextRun's payload, rather than token count, is the multiplier.
        return "\\newcommand{\\zzq}{\\" + command + "{" + "a".repeat(5_000) + "}}"
            + "\\zzq ".repeat(900);
    }

    public static void main(String[] args) {
        String mode = args[0];
        String hostile = witness(args[1]);
        if (hostile.length() >= 100_000) {
            throw new AssertionError("witness must remain under the source cap");
        }
        // Repeat the refusal on the SAME thread, interleaved with a healthy render:
        // no leaked per-render state may poison the next invocation.
        for (int i = 0; i < 2; i++) {
            try {
                if (mode.equals("svg")) {
                    LatteX.render(hostile);
                } else if (mode.equals("mathml")) {
                    LatteX.toMathML(hostile);
                } else {
                    throw new AssertionError("unknown probe mode: " + mode);
                }
                throw new AssertionError("hostile output was accepted");
            } catch (MathSyntaxException cap) {
                if (!cap.isCapExceeded() || !cap.getMessage().contains("output work budget")) {
                    throw new AssertionError("expected allocation-time output work refusal", cap);
                }
            }
            if (!LatteX.render("\\frac{x^2}{2}+\\text{healthy}").startsWith("<svg")
                    || !LatteX.toMathML("x^2").contains("<msup>")) {
                throw new AssertionError("healthy control failed after refusal");
            }
        }
        if (mode.equals("svg")) {
            RenderResult result = LatteX.renderWithDiagnostics(hostile);
            if (result.diagnostics().outcome() != Outcome.OUTPUT_CAP_EXCEEDED
                    || !result.diagnostics().stage().equals("layout")
                    || LatteX.tryRenderMath(hostile).isPresent()) {
                throw new AssertionError("diagnostic/fail-soft seams lost the output cap");
            }
        }
        System.out.println("OUTPUT_WORK_CAP_OK " + mode + " " + args[1]);
    }
}
