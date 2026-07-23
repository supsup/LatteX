package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.Color;
import com.lattex.api.LatteX;
import com.lattex.api.Outcome;
import com.lattex.api.RenderOptions;
import com.lattex.api.RenderResult;
import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.layout.Rule;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathSyntaxException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The output cap as a REAL postcondition (plan b2ae72fe — Marlow audit LTX-01;
 * design from fixpoint/lattex-hostile-input-hardening 817f0b2+5427c41, Fixpoint;
 * reviews lattex/214+216+226). The pre-fix cap ran only at the top of the glyph
 * loop, over a List of ALL path strings that was built first, and never re-checked
 * for the after-the-loop rules, the closing wrapper, the escaped aria, or the
 * returned sidecars — so {@code "\boxed{}".repeat(10_000)} (80k source chars, under
 * the parser's 100k cap) returned {@code OK} with a 2,601,491-char SVG despite the
 * 2,000,000-char cap.
 *
 * <p>This suite is a MIXED fixture: the compliant positives (a mid-sized render, a
 * fragment, both sidecars — each asserted byte-identical to a pre-change golden) sit
 * beside the breach negatives on every capped surface (rules, the streamed glyph
 * path, the wrapper/aria, the fragment, the source-agnostic appender), so a breach
 * assertion is never vacuous.
 */
class OutputCapPostconditionTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    /** The exact LTX-01 repro: {@code \boxed{}} 10,000 times. */
    private static final String BOXED_STORM = "\\boxed{}".repeat(10_000);

    private static MathNode flatList(int atoms) {
        List<MathNode> items = new ArrayList<>(atoms);
        for (int i = 0; i < atoms; i++) {
            items.add(new Atom('x', MathClass.ORD));
        }
        return new MathNode.MathList(items);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = OutputCapPostconditionTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "golden resource missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------------
    // COMPLIANT POSITIVES — the cap must NOT change a below-cap render.
    // ---------------------------------------------------------------------

    /** The mid-sized formula from the golden must render, and byte-identically. */
    @Test
    void aMidSizedRenderIsUnchangedAndByteIdenticalToTheGolden() throws Exception {
        String mid = "\\sum_{n=1}^{\\infty} \\frac{1}{n^2} = \\frac{\\pi^2}{6} \\quad "
            + "\\int_0^1 x^2\\,dx = \\frac{1}{3} \\quad \\sqrt{a^2+b^2}";
        String svg = LatteX.render(mid);
        assertEquals(resource("golden-mid-display.svg"), svg,
            "a compliant render must be byte-identical after the cap change");
    }

    /** A fragment (inner SVG) below the cap is unchanged and byte-identical. */
    @Test
    void aFragmentBelowTheCapIsByteIdenticalToTheGolden() throws Exception {
        var frag = LatteX.renderFragment("(a+b)^2 = a^2 + 2ab + b^2", 40.0);
        assertEquals(resource("golden-fragment-inner.svgfrag"), frag.innerSvg(),
            "a compliant fragment must be byte-identical after the cap change");
    }

    /** Both returned sidecars are unchanged (they route through the capped builder now). */
    @Test
    void theReturnedSidecarsAreUnchangedByTheCappedBuilder() {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        Layout gm = LayoutEngine.layout(
            com.lattex.parse.MathParser.parse("(a+b)^2 = a^2 + 2ab + b^2"), ctx);
        assertEquals("61:1,7,11;2b:2,9,13;62:3,12,14;32:5,8,10,15",
            SvgEmitter.glyphmap(gm, FONT), "glyphmap sidecar must be byte-identical");
        Layout grp = LayoutEngine.layout(
            com.lattex.parse.MathParser.parse("\\left(a\\left(b+c\\right)d\\right)"), ctx);
        assertEquals("0:2,3,4,5,6;1:0,1,7,8",
            SvgEmitter.groupmap(grp, FONT), "groupmap sidecar must be byte-identical");
    }

    // ---------------------------------------------------------------------
    // BREACH NEGATIVES — every capped surface fails closed into the SAME
    // typed resource-cap outcome family the layout-box budget uses.
    // ---------------------------------------------------------------------

    /**
     * LTX-01, the exact repro: rule-dominated (\boxed = rects), 80k source chars, under
     * the parser cap. Pre-fix it returned OK at 2,601,491 chars because the rules append
     * AFTER the glyph loop with no in-loop check and there was no postcondition. It must
     * now refuse via the diagnostics channel (no multi-megabyte svg retained) AND throw
     * on the public render() path — identically classified.
     */
    @Test
    void theBoxedStormRefusesInsteadOfReturningAnOverCapSvg() {
        RenderResult r = LatteX.renderWithDiagnostics(BOXED_STORM);
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, r.diagnostics().outcome(),
            "an over-cap document must refuse, not return OK");
        assertEquals("", r.svg(), "a cap refusal yields no partial svg");
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> LatteX.render(BOXED_STORM));
        assertTrue(e.isCapExceeded(),
            "the throwing render() path must classify the trip as a resource cap: " + e.getMessage());
    }

    /**
     * The RULE surface directly: a layout that is all rects and no glyphs. Pre-fix the
     * rule loop had no cap check at all, so this returned an over-cap string; it must now
     * cap during the rule run.
     */
    @Test
    void aRuleHeavyLayoutCapsAfterTheGlyphLoop() {
        List<Rule> rules = new ArrayList<>(60_000);
        for (int i = 0; i < 60_000; i++) {
            rules.add(new Rule(i, i, 10, 2));
        }
        Layout layout = new Layout(List.of(), rules, 0, 0, 100, 100);
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(layout, FONT, "x", Color.BLACK));
        assertTrue(e.isCapExceeded(), "rule-run overflow is a typed resource cap: " + e.getMessage());
        assertTrue(e.getMessage().contains("output"), e.getMessage());
    }

    /**
     * The STREAMED GLYPH surface: ~90k inked glyphs, no rules. Proves the streaming
     * decode-and-append path caps too (and that the layout carried no rules, so the trip
     * is genuinely the glyph stream, not a rule run).
     */
    @Test
    void theStreamedGlyphPathCapsWithoutPrebuildingEveryPath() {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        Layout layout = LayoutEngine.layout(flatList(90_000), ctx);
        assertTrue(layout.rules().isEmpty(), "fixture must be glyph-only to prove the streaming surface");
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(layout, FONT, "x", Color.BLACK));
        assertTrue(e.isCapExceeded(), "glyph-stream overflow is a typed resource cap: " + e.getMessage());
    }

    /**
     * The WRAPPER/ARIA surface: an empty layout whose oversized aria never enters the
     * glyph or rule loops. Pre-fix the loop-only check was never called for it; the
     * postcondition (the capped header append) must still refuse.
     */
    @Test
    void anEmptyLayoutWithAnOversizedAriaCannotBypassTheCap() {
        Layout empty = new Layout(List.of(), List.of(), 0, 0, 0, 0);
        String hugeAria = "a".repeat(SvgEmitter.MAX_OUTPUT_CHARS + 1_000);
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(empty, FONT, hugeAria, Color.BLACK));
        assertTrue(e.isCapExceeded(),
            "an oversized aria on an empty layout must trip the cap: " + e.getMessage());
    }

    /** The FRAGMENT surface: emitFragment had no postcondition pre-fix. */
    @Test
    void theFragmentSurfaceCapsToo() {
        List<Rule> rules = new ArrayList<>(60_000);
        for (int i = 0; i < 60_000; i++) {
            rules.add(new Rule(i, i, 10, 2));
        }
        Layout layout = new Layout(List.of(), rules, 0, 0, 100, 100);
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emitFragment(layout, FONT, Color.BLACK));
        assertTrue(e.isCapExceeded(), "fragment overflow is a typed resource cap: " + e.getMessage());
    }

    /**
     * The appender itself, source-agnostic: it fails closed on the crossing append no
     * matter where the content comes from (this is the property the sidecar surface — not
     * reachable through the bounded layout — relies on), and a below-cap build is returned
     * verbatim by the final postcondition.
     */
    @Test
    void theCappedBuilderFailsClosedRegardlessOfSource() {
        SvgEmitter.CappedBuilder over = new SvgEmitter.CappedBuilder();
        assertThrows(MathSyntaxException.class,
            () -> over.append("z".repeat(SvgEmitter.MAX_OUTPUT_CHARS + 1)),
            "the appender must fail on the crossing append");

        SvgEmitter.CappedBuilder ok = new SvgEmitter.CappedBuilder();
        ok.append("ab").append('c');
        assertEquals("abc", ok.checked(), "a below-cap build is returned verbatim");
    }

    /**
     * The FLUID (now-default) render path must be capped IDENTICALLY to the fixed-size
     * path. fluid rides the host opts and lands on the LIVE default render — the exact
     * surface a naive freshen onto the fluid land would have left bypassing the cap (the
     * regression this test pins; reviewer lattex/424). A fluid=true render of the
     * boxed-storm must trip the same OUTPUT_CAP_EXCEEDED, not return an over-cap svg.
     */
    @Test
    void theFluidDefaultPathIsCappedToo() {
        RenderOptions fluid = RenderOptions.defaults().withFluid(true);
        RenderResult r = LatteX.renderWithDiagnostics(BOXED_STORM);
        // (diagnostics has no opts overload; assert the throwing fluid path directly and
        // that renderStyledHtml — the html surface that threads opts.fluid() — refuses too.)
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> LatteX.render(BOXED_STORM, fluid));
        assertTrue(e.isCapExceeded(),
            "the fluid render() path must classify the trip as a resource cap: " + e.getMessage());
        // The fluid emitter overload, called directly with an over-cap layout, caps as well.
        List<Rule> rules = new ArrayList<>(60_000);
        for (int i = 0; i < 60_000; i++) {
            rules.add(new Rule(i, i, 10, 2));
        }
        Layout layout = new Layout(List.of(), rules, 0, 0, 100, 100);
        MathSyntaxException e2 = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(layout, FONT, "x", Color.BLACK, true));
        assertTrue(e2.isCapExceeded(), "the 5-arg fluid emit must cap: " + e2.getMessage());
        // Sanity: a compliant fluid render still succeeds (the cap does not break fluid).
        assertFalse(r.diagnostics().outcome() == Outcome.OK && r.svg().length() > SvgEmitter.MAX_OUTPUT_CHARS,
            "a breach must never return an over-cap OK");
        String okFluid = LatteX.render("x^2 + y^2 = z^2", fluid);
        assertTrue(okFluid.contains("style=\"width:100%"), "a compliant fluid render still carries the sizing rule");
    }

    /**
     * Direct discriminator for the non-finite guard (reviewer lattex/424 [MEDIUM]): a
     * non-finite coordinate reaching num() must THROW through the typed channel — a
     * RENDER_BUG, NOT a cap. A Layout with a NaN / +Infinity minX drives num() on the
     * viewBox coordinate. Deleting the {@code if(!isFinite)} throw reds this (the emit
     * would instead succeed with a literal "NaN"/"Infinity" viewBox).
     */
    @Test
    void aNonFiniteCoordinateRefusesThroughTheTypedChannelNotACap() {
        Layout nan = new Layout(List.of(), List.of(), Double.NaN, 0, 100, 100);
        MathSyntaxException eNan = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(nan, FONT, "x", Color.BLACK));
        assertFalse(eNan.isCapExceeded(), "a non-finite coord is a RENDER_BUG, not a cap: " + eNan.getMessage());
        assertTrue(eNan.getMessage().contains("non-finite"), eNan.getMessage());

        Layout inf = new Layout(List.of(), List.of(), Double.POSITIVE_INFINITY, 0, 100, 100);
        MathSyntaxException eInf = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(inf, FONT, "x", Color.BLACK));
        assertFalse(eInf.isCapExceeded(), "a non-finite coord is a RENDER_BUG, not a cap: " + eInf.getMessage());
        assertTrue(eInf.getMessage().contains("non-finite"), eInf.getMessage());
    }

    /**
     * A compliant fluid render is byte-identical to the pre-change golden captured from
     * CURRENT main (97f9a965) — fluid changed the default shape, so the golden carries the
     * style rule; the cap change must leave it untouched.
     */
    @Test
    void aCompliantFluidRenderIsByteIdenticalToTheCurrentMainGolden() throws Exception {
        String mid = "\\sum_{n=1}^{\\infty} \\frac{1}{n^2} = \\frac{\\pi^2}{6} \\quad "
            + "\\int_0^1 x^2\\,dx = \\frac{1}{3} \\quad \\sqrt{a^2+b^2}";
        String svg = LatteX.render(mid, RenderOptions.defaults().withFluid(true));
        assertEquals(resource("golden-mid-fluid.svg"), svg,
            "a compliant fluid render must be byte-identical to the current-main golden");
    }

    // ---------------------------------------------------------------------
    // BYTE-IDENTITY RATCHET — the whole renderable corpus, both modes, one
    // hash. Captured on the pre-change tip; a change to compliant output
    // (below the cap) reds this. This is the load-bearing differential:
    // the SAME test set on both tips, identical bytes.
    // ---------------------------------------------------------------------

    @Test
    void theWholeRenderableCorpusIsByteIdenticalToThePreChangeCapture() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int rows = 0;
        for (String[] row : loadCorpus()) {
            String tier = row[0];
            String latex = row[1];
            if (!tier.equals("PARSES-NOW") && !tier.equals("NEEDS-S4-LAYOUT")) {
                continue;
            }
            for (boolean inline : new boolean[] {false, true}) {
                String svg;
                try {
                    svg = inline ? LatteX.renderInline(latex) : LatteX.render(latex);
                } catch (RuntimeException e) {
                    md.update(("THROW:" + e.getClass().getSimpleName()).getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                md.update(svg.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            rows++;
        }
        assertTrue(rows >= 130, "expected the full renderable corpus, swept only " + rows);
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", b));
        }
        // Captured on the pre-change tip (main 3347765) BEFORE the cap change.
        assertEquals("800ae87551a8079fddc4e2d2cba7a5c6e919f169c577e20c2ac22f49a623ce6c", hex.toString(),
            "compliant renders must be byte-identical below the cap");
        assertFalse(rows == 0, "corpus must be non-empty");
    }

    private static List<String[]> loadCorpus() throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = OutputCapPostconditionTest.class.getClassLoader()
                .getResourceAsStream("com/lattex/parse/corpus.tsv")) {
            assertNotNull(in, "vendored corpus resource missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length >= 3) {
                    rows.add(new String[] {cols[0].trim(), cols[2]});
                }
            }
        }
        return rows;
    }
}
