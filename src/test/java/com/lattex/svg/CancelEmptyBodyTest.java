package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lattex.api.LatteX;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Zero-extent policy for the {@code cancel} strike family (reviewer HIGH finding). A
/// legal empty braced argument — {@code \cancel{}}, {@code \bcancel{}}, {@code \xcancel{}},
/// {@code \cancelto{0}{}} — lays out to a body with NO ink extent, so the corner-to-corner
/// diagonal collapses to a point and its unit vector is undefined ({@code 0/0}). Before the
/// fix that {@code NaN} flowed into the strike vertices, the {@code \cancelto} arrowhead and
/// target placement, and the box metrics, emitting {@code NaN}/{@code Infinity} viewBox,
/// width/height, transforms, and path coordinates.
///
/// These are the reviewer's probes promoted to committed DISCRIMINATORS: each renders in
/// BOTH display and inline modes and must yield a finite, contract-valid canvas — no
/// {@code NaN}/{@code Infinity} anywhere in the SVG, a viewBox of four finite numbers with
/// positive width/height, and full alphabet/numeric-shape containment via the SAME
/// {@link S8LeftContainmentTest#auditOne} judge the security battery uses.
class CancelEmptyBodyTest {

    /** The four empty-body commands the reviewer probed, plus zero-ink/zero-extent controls. */
    private static final String[] CASES = {
        "\\cancel{}",              // up strike, empty body
        "\\bcancel{}",             // down strike, empty body
        "\\xcancel{}",             // both diagonals, empty body
        "\\cancelto{0}{}",         // arrow + target, empty struck body
        "\\cancelto{}{}",          // control: empty target AND empty body (both zero-extent)
        "\\xcancel{\\phantom{x}}", // control: zero INK but non-degenerate extent (strike still draws)
    };

    @Test
    void emptyBodiesRenderFiniteAndContained() {
        List<String> failures = new ArrayList<>();
        for (String latex : CASES) {
            for (boolean inline : new boolean[] {false, true}) {
                String mode = inline ? "inline" : "display";
                String svg;
                try {
                    svg = inline ? LatteX.renderInline(latex) : LatteX.render(latex);
                } catch (RuntimeException e) {
                    failures.add(mode + " [" + latex + "] threw: " + e);
                    continue;
                }
                // (1) No non-finite token may reach the emitted SVG at all. num() now
                // REFUSES a non-finite coordinate through the typed channel (plan b2ae72fe /
                // 5427c41 N2), so a non-finite would throw and be caught above (a failure);
                // this substring scan stays as a second, independent gate against a
                // non-finite viewBox, width/height, <g transform>, <rect> metric, or <path d>.
                if (svg.contains("NaN") || svg.contains("Infinity")) {
                    failures.add(mode + " [" + latex + "] emitted a non-finite token: " + svg);
                }
                // (2) Real root metrics: a viewBox of four finite numbers, width/height > 0.
                auditCanvas(latex, mode, svg, failures);
                // (3) Contract-valid: alphabet + narrow numeric attribute shapes (this judge
                // also rejects NaN/Infinity structurally, a second independent finite gate).
                S8LeftContainmentTest.auditOne("[" + latex + "] (" + mode + ")", svg, failures);
                // (4) Well-formed shell.
                if (!inline) {
                    assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"),
                        "display SVG must be a complete <svg> element: " + latex);
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("cancel empty-body policy violated (" + failures.size() + "):\n  "
                + String.join("\n  ", failures));
        }
    }

    /** A rendered canvas must be real: viewBox = four finite numbers, width/height > 0. */
    private static void auditCanvas(String latex, String mode, String svg, List<String> failures) {
        int vb = svg.indexOf("viewBox=\"");
        if (vb < 0) {
            if (!mode.equals("inline")) {
                failures.add("no viewBox in " + mode + " [" + latex + "]");
            }
            return; // inline fragments carry no viewBox by contract
        }
        int end = svg.indexOf('"', vb + 9);
        String[] nums = svg.substring(vb + 9, end).trim().split("[\\s,]+");
        if (nums.length != 4) {
            failures.add("viewBox is not 4 numbers in " + mode + " [" + latex + "]");
            return;
        }
        try {
            double w = Double.parseDouble(nums[2]);
            double h = Double.parseDouble(nums[3]);
            if (!Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0) {
                failures.add("degenerate canvas " + w + "x" + h + " in " + mode + " [" + latex + "]");
            }
        } catch (NumberFormatException e) {
            failures.add("non-numeric viewBox in " + mode + " [" + latex + "]: " + e.getMessage());
        }
    }

    /**
     * Direct evidence of the pre-fix failure mode as a guard on the fix itself: the plain
     * empty-body variants draw NO strike polygon (there is nothing to strike), while a
     * zero-INK but sized body ({@code \phantom{x}}) still draws a finite one.
     */
    @Test
    void emptyBodyDrawsNoStrikeButSizedInvisibleBodyDoes() {
        // \cancel{} — nothing to strike: the plain up/down variants decorate nothing.
        assertFalse(LatteX.render("\\cancel{}").contains("<path"),
            "\\cancel{} draws no strike path over an empty body");
        assertFalse(LatteX.render("\\xcancel{}").contains("<path"),
            "\\xcancel{} draws no strike path over an empty body");
        // \phantom{x} reserves x's extent (invisible ink) — the diagonal is non-degenerate,
        // so a finite strike IS drawn, proving the policy keys on extent, not on glyphs.
        assertTrue(LatteX.render("\\xcancel{\\phantom{x}}").contains("<path"),
            "\\xcancel{\\phantom{x}} still strikes the reserved (invisible) extent");
    }
}
