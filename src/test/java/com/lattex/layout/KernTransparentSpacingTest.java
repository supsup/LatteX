package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Explicit kerns and glue ({@code \!}, {@code \,}, {@code \;}, {@code \quad},
 * {@code \kern}) are transparent to Appendix G inter-atom spacing (plan e749291c).
 *
 * <p>In TeX they are not noads: mlist_to_hlist (TeX82 §760-761) leaves the previous
 * atom type unchanged across a kern or glue item, so {@code a\!-\!a} keeps the Bin's
 * medmuskip (4mu) on both sides and the two {@code \!} take 3mu each off it, net 1mu.
 * LatteX used to reset the previous class on every Spacing node, collapsing the dash
 * onto its neighbours ({@code 30^\circ\!-\!60^\circ} rendered touching).
 *
 * <p>Every expected width is derived from TeX's rules, not measured: the baseline
 * {@code a{-}a} (three Ord atoms, no glue) is 74.6 units, 1mu = 40/18 units, Bin
 * glue is 4mu a side, Rel glue 5mu a side, {@code \!} -3mu, {@code \,} 3mu,
 * {@code \;} 5mu, {@code \quad} 18mu, and 1pt = 1.8mu (= 4 units) in this engine's
 * 10pt-em approximation. {@code -}, {@code +} and {@code =} share an advance width
 * in the bundled font, so one baseline serves all three.
 */
class KernTransparentSpacingTest {

    private static final double MU = 40.0 / 18.0;
    private static final double EPS = 1e-3;
    private static final double BASE = 74.6; // a{-}a: three Ords, zero inter-atom glue

    private static final Pattern VIEWBOX = Pattern.compile(
        "viewBox=\"[-0-9.]+ [-0-9.]+ ([0-9.]+) ([0-9.]+)\"");

    private static double width(String latex) {
        Matcher m = VIEWBOX.matcher(LatteX.render(latex));
        assertTrue(m.find(), "svg has a viewBox: " + latex);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void baselinesAreTheAdvanceSumsTheDerivationAssumes() {
        assertEquals(BASE, width("a{-}a"), EPS, "braced minus is Ord: no glue");
        assertEquals(BASE, width("a{=}a"), EPS, "= shares the minus advance");
        assertEquals(BASE, width("a{+}a"), EPS, "+ shares the minus advance");
        assertEquals(BASE + 8 * MU, width("a-a"), EPS); // 92.3778
    }

    @Test
    void kernsAndGlueDoNotResetThePreviousAtomClass() {
        // Bin keeps 4mu+4mu; the kerns add on top.
        assertEquals(BASE + 8 * MU - 6 * MU, width("a\\!-\\!a"), EPS);   // 79.0444
        assertEquals(BASE + 8 * MU - 6 * MU, width("a\\!+\\!a"), EPS);   // 79.0444
        assertEquals(BASE + 8 * MU + 6 * MU, width("a\\,-\\,a"), EPS);   // 105.7111
        assertEquals(BASE + 8 * MU - 3 * MU, width("a\\!-a"), EPS);      // 85.7111
        assertEquals(BASE + 8 * MU + 2 * 1.8 * MU, width("a\\kern1pt-\\kern1pt a"), EPS); // 100.3778
        // Rel keeps 5mu+5mu.
        assertEquals(BASE + 10 * MU - 6 * MU, width("a\\!=\\!a"), EPS);  // 83.4889
        assertEquals(BASE + 10 * MU + 10 * MU, width("a\\;=\\;a"), EPS); // 119.0444
        assertEquals(BASE + 10 * MU + 18 * MU, width("a\\quad=a"), EPS); // 136.8222
    }

    @Test
    void theTableValuesFromThePlanHold() {
        assertEquals(74.6, width("a{-}a"), EPS);
        assertEquals(92.3778, width("a-a"), EPS);
        assertEquals(79.0444, width("a\\!-\\!a"), EPS);
        assertEquals(83.4889, width("a\\!=\\!a"), EPS);
        assertEquals(79.0444, width("a\\!+\\!a"), EPS);
        assertEquals(105.7111, width("a\\,-\\,a"), EPS);
        assertEquals(119.0444, width("a\\;=\\;a"), EPS);
        assertEquals(85.7111, width("a\\!-a"), EPS);
        assertEquals(136.8222, width("a\\quad=a"), EPS);
        assertEquals(100.3778, width("a\\kern1pt-\\kern1pt a"), EPS);
    }

    @Test
    void aKernDoesNotGiveAUnaryMinusALeftOperand() {
        // A row-leading minus has no left operand, kern or not: it is Ord, no Bin glue.
        // The viewBox is ink-bounded, so a LEADING kern moves ink without changing the
        // width: \!-a must measure exactly -a (a wrongly-Bin minus would add 4mu).
        double unary = width("-a");
        assertEquals(unary, width("\\!-a"), EPS, "\\!-a: leading minus stays Ord");
        // Same, made measurable by an ink anchor to the left: the group is its own row.
        assertEquals(width("a{-a}") - 3 * MU, width("a{\\!-a}"), EPS,
            "\\!-a inside a group: leading minus stays Ord, the kern just pulls it left");
        assertEquals(unary - 3 * MU, width("-\\!a"), EPS, "-\\!a: leading minus stays Ord");
        // Bin after Rel (across a kern) is demoted to Ord: only the Rel's 5mu, left side.
        double relOrd = width("={-}a"); // Rel Ord Ord: Rel|Ord 5mu, Ord|Ord 0
        assertEquals(relOrd - 3 * MU, width("=\\!-a"), EPS, "=\\!-a: Bin after Rel stays Ord");
    }
}
