package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L10 — hostile-input hardening (plan lattex-hostile-input-hardening). The fuzz
 * corpus mixes HOSTILE inputs with a POSITIVE render in the SAME fixture (Conf's
 * mixed-fixture rule, lattex/126): a containment assertion over inputs that all
 * fail proves nothing about the pipeline still working. Invariants per input:
 * never an Error escapes renderWithDiagnostics; any produced SVG stays within the
 * output cap AND the svg/g/path/rect alphabet with no control characters.
 *
 * <p>Control characters in AUTHOR INPUT are the real target — an unescaped NUL
 * used to leak into the aria-label (found by this very fuzzer while it was being
 * written, the Sirentide control-char defect class). {@code \\u0000} escapes keep
 * the source itself clean text; {@link #controlCharsInInputNeverReachOutput()}
 * pins the fix.
 */
class HostileInputHardeningTest {

    private static final Pattern TAG = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)");

    private static void assertInvariants(String label, RenderResult r) {
        String svg = r.svg();
        assertTrue(svg.length() <= 2_000_000, label + ": output within cap");
        for (int i = 0; i < svg.length(); i++) {
            char c = svg.charAt(i);
            assertTrue(c >= 0x20 || c == '\n' || c == '\t',
                label + ": no control chars in output (found 0x" + Integer.toHexString(c) + ")");
        }
        Matcher m = TAG.matcher(svg);
        while (m.find()) {
            String el = m.group(1);
            assertTrue(el.equals("svg") || el.equals("g") || el.equals("path") || el.equals("rect"),
                label + ": element out of alphabet: <" + el + ">");
        }
    }

    @Test
    void fuzzCorpusNeverEscapesAndKeepsTheAlphabet() {
        List<String> corpus = new ArrayList<>();
        // POSITIVE renders FIRST (the mixed-fixture rule): the same fixture must
        // prove the pipeline still renders real math within the caps.
        corpus.add("\\frac{a}{b} + \\sum_{i=1}^n x_i^2");
        corpus.add("P\\left(A=2\\middle|\\frac{A^2}{B}>4\\right)");
        // Hostile: garbage commands, deep nesting, wide rows, unterminated groups,
        // literal control chars in the INPUT, seeded-random ASCII soup (deterministic).
        corpus.add("x y");
        corpus.add("abc");
        corpus.add("\\nosuchcommandever{x}");
        corpus.add("x\u0000y");           // NUL between atoms (the fuzz-find)
        corpus.add("a\u0007b\u001f");     // BEL + unit-separator
        corpus.add("{".repeat(600) + "x" + "}".repeat(600));
        corpus.add("\\begin{matrix}" + "1&".repeat(2000) + "1\\end{matrix}");
        corpus.add("\\frac{\\frac{\\frac{a}{b}}{c}}{");
        corpus.add("$$$$}{}{}{\\\\");
        Random rnd = new Random(42);
        for (int i = 0; i < 40; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 80; j++) {
                sb.append((char) (0x20 + rnd.nextInt(0x5f)));
            }
            corpus.add(sb.toString());
        }

        int okCount = 0;
        for (String input : corpus) {
            // NEVER an Error: renderWithDiagnostics must classify, not throw.
            RenderResult r = LatteX.renderWithDiagnostics(input);
            assertInvariants(shortLabel(input), r);
            if (r.diagnostics().outcome() == Outcome.OK) {
                okCount++;
            }
        }
        // The POSITIVE half of the mixed fixture: real math rendered fine.
        assertTrue(okCount >= 2, "positive renders succeeded alongside the hostile set");
    }

    @Test
    void controlCharsInInputNeverReachOutput() {
        // The fuzz-find, pinned: a control character in author input (NUL, BEL,
        // unit-separator) must not appear in the SVG output, and must not throw —
        // the aria-label escape drops XML-forbidden control chars.
        for (String bad : new String[] {"x\u0000y", "\u0001", "a\u0007b", "\u001f"}) {
            String svg = LatteX.renderWithDiagnostics(bad).svg();
            for (int i = 0; i < svg.length(); i++) {
                char c = svg.charAt(i);
                assertTrue(c >= 0x20 || c == '\n' || c == '\t',
                    "control char leaked into output from input 0x"
                        + Integer.toHexString(bad.charAt(0)));
            }
        }
    }

    @Test
    void layoutBoxBudgetTripsAsTypedCapNotBug() {
        // Wide-but-shallow fan-out: passes the parser's depth guard, would build a
        // huge layout graph — the box budget must trip FIRST, typed as a cap.
        StringBuilder wide = new StringBuilder("\\begin{matrix}");
        for (int r = 0; r < 60; r++) {
            wide.append("\\begin{matrix}").append("x&".repeat(60)).append("x\\end{matrix}");
            if (r < 59) { wide.append("\\\\"); }
        }
        wide.append("\\end{matrix}");
        RenderResult r = LatteX.renderWithDiagnostics(wide.toString());
        // Either the matrix-cell parse cap or the L10 box budget stops it — but if
        // it reached LAYOUT, the classification must be the CAP, not RENDER_BUG.
        assertTrue(r.diagnostics().outcome() != Outcome.RENDER_BUG,
            "a resource trip is a cap/parse classification, got " + r.diagnostics().outcome()
                + " (" + r.diagnostics().message() + ")");
    }

    @Test
    void concurrentRendersAreIsolatedAndIdentical() throws Exception {
        // L10 concurrency regression: N threads render the same formula; outputs are
        // byte-identical and no Error escapes (guards the ThreadLocal counters and
        // any future shared cache against silent cross-thread corruption).
        String latex = "\\sum_{i=1}^n \\frac{x_i^2}{\\sigma}";
        String expected = LatteX.render(latex);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                jobs.add(() -> LatteX.render(latex));
            }
            for (Future<String> f : pool.invokeAll(jobs)) {
                assertEquals(expected, f.get(), "concurrent render byte-identical");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String shortLabel(String s) {
        String clean = s.replaceAll("[^ -~]", "?");
        return clean.length() > 40 ? clean.substring(0, 40) + "..." : clean;
    }
}
