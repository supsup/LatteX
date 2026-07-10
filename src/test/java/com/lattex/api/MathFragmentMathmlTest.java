package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// Receipts for the fragment-level MathML surface (plan lattex-mathfragment-mathml; the
/// Sirentide consumer contract from stafficy #6259): the record grows ADDITIVELY, the MathML
/// comes from the SAME parsed body as the SVG (one parse, two serializations), and MathML is
/// per-fragment fail-soft — never null, never a throw, never a blanked label.
class MathFragmentMathmlTest {

    /// THE compile-shape pin (consumer requirement #1): the pre-mathml 5-arg construction —
    /// exactly how the Sirentide math-bridge copies fragments field-by-field — still compiles
    /// and yields an EMPTY (not null) mathml.
    @Test
    void preMathmlArityStillConstructsWithEmptyMathml() {
        MathFragment f = new MathFragment("<g/>", 10.0, 8.0, 2.0, "");
        assertEquals("", f.mathml(), "old arity delegates with empty mathml");
        assertEquals("<g/>", f.innerSvg());
        assertEquals(10.0, f.widthPx());
    }

    /// The fragment's MathML is a real <math> document for a plain formula, and matches the
    /// top-level toMathML of the same source — same serializer, same content.
    @Test
    void fragmentMathmlMatchesTopLevelToMathML() {
        MathFragment f = LatteX.renderFragment("\\frac{a}{b}", 16.0);
        assertNotNull(f.mathml());
        assertTrue(f.mathml().startsWith("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"));
        assertEquals(LatteX.toMathML("\\frac{a}{b}"), f.mathml(),
            "one serializer, one content for an unwrapped formula");
        assertFalse(f.innerSvg().isBlank(), "the visual surface is untouched");
    }

    /// Wrapper-transparency consistency: a \lx-wrapped fragment's mathml equals the bare
    /// body's. (Honesty note: this does NOT discriminate re-parse from same-tree — the
    /// serializer is itself wrapper-transparent, so a re-parse produces identical output;
    /// a divergent-emit mutant survived this test. The no-re-parse property is pinned by
    /// {@link #renderFragmentSerializesTheSameBodyItLaidOut_sourcePin} instead.)
    @Test
    void wrappedFragmentMathmlEqualsBareBodyMathml() {
        MathFragment wrapped = LatteX.renderFragment("\\lx[style.scale=1.2]{ x^2 }", 16.0);
        MathFragment bare = LatteX.renderFragment("x^2", 16.0);
        assertEquals(bare.mathml(), wrapped.mathml());
    }

    /// THE no-re-parse pin (consumer requirement #1: same tree, no drift). The property is
    /// not black-box observable (a re-parse of the same source yields an equal tree), so —
    /// same pattern as a release-gate script pin — assert the SOURCE calls mathmlOrEmpty on
    /// the already-parsed `body`, not on a fresh MathParser.parse. If this fails, someone
    /// reintroduced a second parse: restore the single-parse call or renegotiate the
    /// contract with the Sirentide consumer first.
    @Test
    void renderFragmentSerializesTheSameBodyItLaidOut_sourcePin() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
            "src", "main", "java", "com", "lattex", "api", "LatteX.java");
        String code = java.nio.file.Files.readString(src);
        assertTrue(code.contains("mathmlOrEmpty(body)"),
            "renderFragment must serialize the SAME parsed body it laid out");
        assertFalse(code.contains("mathmlOrEmpty(MathParser.parse"),
            "no second parse feeding the MathML surface");
    }

    /// Per-fragment fail-soft (consumer requirement #2): across a spread of fragment shapes,
    /// mathml is never null and never throws — and the SVG surface never depends on it.
    @Test
    void mathmlIsNeverNullAcrossFragmentShapes() {
        for (String latex : new String[] {
                "x", "\\sum_{i=1}^{n} i", "\\sqrt{2}", "\\text{hello}", "a_1 + b^2",
                "\\textcolor{red}{x}", "\\frac{\\partial f}{\\partial x}"}) {
            MathFragment f = LatteX.renderFragment(latex, 16.0);
            assertNotNull(f.mathml(), latex);
            assertFalse(f.innerSvg().isBlank(), latex + ": visual surface independent of mathml");
        }
    }
}
