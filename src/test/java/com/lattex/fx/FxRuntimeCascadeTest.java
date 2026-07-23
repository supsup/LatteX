package com.lattex.fx;

import com.lattex.api.LatteX;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx-runtime CASCADE harness (plan 851f95e8, SLICE-1): drives the REAL lattex-fx.js
 * {@code cascade} routine inside GraalJS (same hermetic seam as {@link
 * FxRuntimeJsHarnessTest}/{@link FxRuntimeLifecycleTest}) and pins the effect's
 * design-critical FAIL-HONEST boundary behaviorally.
 *
 * <p>cascade is an ENTER effect: a stacked multi-row block (aligned / cases / an array
 * of equations) reveals row by row, top to bottom, a beat between rows, OPACITY-ONLY.
 * Rows are found by pure DOM geometry — clustering the inner SVG's glyph {@code <path>}
 * baseline-y (the placement transform's {@code ty}) into horizontal bands. When the band
 * structure is ambiguous (a single flat line, a script-only spread whose offsets are
 * script-scale not row-scale, or bands compressed closer than the row-step threshold) the
 * effect is INERT: the math is shown immediately, fully visible, no animation.
 *
 * <p>The positive geometry and the negatives are built from GENUINE {@link
 * LatteX#render(String)} output (real path placement transforms), and the "bands closer
 * than threshold" negative is a STRUCTURAL MUTATION of that real geometry (its baselines
 * compressed), never string-edited rendered text — so a renamed/garbled value could not
 * make the negative silently pass: the assertion keys on the geometric outcome.
 */
class FxRuntimeCascadeTest {

    private Context context;
    private Value fx;

    private void boot(boolean reducedMotion) throws IOException {
        context = Context.newBuilder("js").build();
        if (reducedMotion) {
            context.eval(Source.create("js", "globalThis.__lxStubReducedMotion = true;"));
        }
        context.eval(source("/com/lattex/fx/harness-dom-stub.js", "harness-dom-stub.js"));
        // A builder that wraps an array of REAL placement-transform strings into an
        // .lx-math container whose inner <svg> exposes those glyph <path>s. Only the
        // transform attribute + a settable style.opacity are needed (cascade reads the
        // baseline from the transform and animates opacity — it never reads getBBox or
        // any attribute besides 'transform').
        context.eval(Source.create("js",
            "globalThis.__lxMakeCascadeEl = function (transforms) {"
            + "  var paths = [];"
            + "  for (var i = 0; i < transforms.length; i++) {"
            + "    paths.push(__lxMakePath({ transform: transforms[i] }));"
            + "  }"
            + "  var svg = {"
            + "    querySelectorAll: function (s) { return s === 'path' ? paths.slice() : []; },"
            + "    querySelector: function () { return null; },"
            + "    addEventListener: function () {},"
            + "    getBBox: function () { return { x:0, y:0, width:200, height:80 }; },"
            + "    getBoundingClientRect: function () { return { left:0, top:0, width:200, height:80 }; }"
            + "  };"
            + "  return {"
            + "    __paths: paths,"
            + "    style: {},"
            + "    getAttribute: function () { return null; },"
            + "    setAttribute: function () {},"
            + "    appendChild: function () {},"
            + "    addEventListener: function () {},"
            + "    querySelector: function (s) { return s === 'svg' ? svg : null; },"
            + "    querySelectorAll: function () { return []; }"
            + "  };"
            + "};"));
        context.eval(Source.create("js",
            "globalThis.__lxTestHook = function (internals) { globalThis.__lxInternals = internals; };"));
        context.eval(source("/com/lattex/fx/lattex-fx.js", "lattex-fx.js"));
        fx = context.getBindings("js").getMember("__lxInternals");
        assertNotNull(fx);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private static Source source(String resource, String name) throws IOException {
        try (InputStream in = FxRuntimeCascadeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing classpath resource " + resource);
            return Source.newBuilder("js", new String(in.readAllBytes(), StandardCharsets.UTF_8), name)
                .buildLiteral();
        }
    }

    private Value js(String script) {
        return context.eval(Source.create("js", script));
    }

    private int intOf(String expr) {
        return js(expr).asInt();
    }

    private static String valueOrEmpty(Value v) {
        return v == null || v.isNull() ? "" : v.asString();
    }

    // ---- genuine geometry helpers -----------------------------------------------

    private static final Pattern PATH_TRANSFORM =
        Pattern.compile("<path[^>]*transform=\"([^\"]+)\"");
    private static final Pattern TRANSLATE =
        Pattern.compile("translate\\(([-0-9.]+) ([-0-9.]+)\\)(.*)");

    /** Every glyph-path placement transform from a REAL render, in emit order. */
    private static String[] transformsOf(String latex) {
        Matcher m = PATH_TRANSFORM.matcher(LatteX.render(latex));
        List<String> ts = new ArrayList<>();
        while (m.find()) {
            ts.add(m.group(1));
        }
        return ts.toArray(new String[0]);
    }

    /** STRUCTURAL MUTATION of real geometry: scale every baseline (ty) toward 0 by
     *  {@code factor}, preserving the real tx and scale — i.e. move the rows CLOSER
     *  together without touching anything else, to drop the inter-row gap below the
     *  row-step threshold. */
    private static String[] compressBaselines(String[] transforms, double factor) {
        String[] out = new String[transforms.length];
        for (int i = 0; i < transforms.length; i++) {
            Matcher m = TRANSLATE.matcher(transforms[i]);
            if (m.find()) {
                double tx = Double.parseDouble(m.group(1));
                double ty = Double.parseDouble(m.group(2));
                out[i] = "translate(" + tx + " " + (ty * factor) + ")" + m.group(3);
            } else {
                out[i] = transforms[i];
            }
        }
        return out;
    }

    private Value makeCascadeEl(String[] transforms) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < transforms.length; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append('\'').append(transforms[i]).append('\'');
        }
        arr.append(']');
        return js("globalThis.__cel = __lxMakeCascadeEl(" + arr + "); __cel");
    }

    private double opacity(int pathIndex) {
        return Double.parseDouble(js("String(__cel.__paths[" + pathIndex + "].style.opacity)").asString());
    }

    private String rawOpacity(int pathIndex) {
        return valueOrEmpty(js("__cel.__paths[" + pathIndex + "].style.opacity"));
    }

    // A genuinely multi-row block: two rows, three glyphs each, clean display spacing
    // (ty = -20.72 for row 1, +27.92 for row 2 at LatteX's metrics — a ~1.2em gap,
    // within-row gap exactly 0). paths 0..2 are the top row, 3..5 the bottom row.
    private static final String ALIGNED_2ROW =
        "\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}";

    // ---- pin 1: rows stagger in band order, top to bottom -----------------------

    @Test
    void cascadeStaggersRealAlignedRowsTopToBottom() throws IOException {
        boot(false);
        Value el = makeCascadeEl(transformsOf(ALIGNED_2ROW));

        fx.getMember("cascade").execute(el);

        // Armed: the container is revealed and every glyph is hidden, ready to ramp.
        assertEquals("1", valueOrEmpty(js("__cel.style.opacity")),
            "cascade reveals the container (defeats any enter-hide) before staggering");
        assertEquals("0", rawOpacity(0), "top-row glyph starts hidden");
        assertEquals("0", rawOpacity(3), "bottom-row glyph starts hidden");

        // Partway in: the TOP band is ramping while the bottom band has not begun —
        // the beat between bands means band 0 leads band 1 strictly.
        js("__lxFlushRaf(10)");
        double top = opacity(0);
        assertTrue(top > 0.0 && top < 1.0, "the top row is mid-reveal, got " + top);
        assertEquals(0.0, opacity(3), 1e-9,
            "the bottom row has NOT started while the top row reveals (band-ordered stagger)");

        // Later: the bottom band now reveals too — AFTER the top (proves sequencing,
        // not a simultaneous fade).
        js("__lxFlushRaf(6)");
        assertTrue(opacity(3) > 0.0, "the bottom row reveals after the top's beat");
        assertTrue(opacity(0) >= top, "the top row never regresses");

        // Run to completion: every glyph settles fully visible (pristine '') and the
        // rAF timeline stops (no leaked frames, guard cleared) — opacity-only, no leak.
        js("__lxFlushRaf(60)");
        assertEquals("", rawOpacity(0), "top row settles to pristine (fully visible)");
        assertEquals("", rawOpacity(3), "bottom row settles to pristine (fully visible)");
        assertFalse(js("__cel.__lxCascade").asBoolean(), "the re-entry guard clears at the end");
        assertEquals(0, js("__lxFlushRaf(5)").asInt(), "no rAF frames leak past completion");
    }

    // ---- pin 2: inert — a single flat line (one band) ---------------------------

    @Test
    void cascadeIsInertOnASingleFlatLine() throws IOException {
        boot(false);
        // Real single-line render: every glyph shares one baseline -> one band.
        Value el = makeCascadeEl(transformsOf("x + y + z"));

        fx.getMember("cascade").execute(el);

        assertEquals("1", valueOrEmpty(js("__cel.style.opacity")),
            "a one-band line is shown immediately (container revealed)");
        assertEquals("", rawOpacity(0), "no glyph is hidden — the line is fully visible, untouched");
        assertEquals(0, js("__lxFlushRaf(5)").asInt(), "an inert cascade schedules no animation frames");
        assertEquals("undefined", js("typeof __cel.__lxCascade").asString(),
            "inert cascade arms no animation state");
    }

    // ---- pin 3: inert — a script-only line (spread is script-scale, not rows) ----

    @Test
    void cascadeIsInertOnAScriptOnlyLine() throws IOException {
        boot(false);
        // x^2 + y_1 : the superscript/subscript raise the '2'/'1' baselines, but a
        // ~0.36em script offset is NOT a row step — this is the "single tall line must
        // NOT misfire as multiple rows" case, built from a REAL render (no mutation).
        String[] t = transformsOf("x^2 + y_1");
        assertTrue(t.length >= 4, "the script line must render its glyphs");
        Value el = makeCascadeEl(t);

        fx.getMember("cascade").execute(el);

        assertEquals("1", valueOrEmpty(js("__cel.style.opacity")), "the line is shown immediately");
        for (int i = 0; i < t.length; i++) {
            assertEquals("", rawOpacity(i),
                "a script-only line is fully visible and untouched (glyph " + i + ")");
        }
        assertEquals(0, js("__lxFlushRaf(5)").asInt(), "script-only line schedules no frames");
    }

    // ---- pin 4: inert — bands compressed BELOW the row-step threshold ------------

    @Test
    void cascadeIsInertWhenRowGapCompressedBelowThreshold() throws IOException {
        boot(false);
        // Take the GENUINE two-row aligned geometry (which DOES animate — pin 1) and
        // structurally MUTATE it: pull the two rows toward each other (baselines *0.3)
        // so the inter-band gap collapses below the row-step threshold. Same glyphs,
        // same tx/scale — only the vertical spacing changed — and now the structure is
        // ambiguous, so cascade must go INERT. (A garbled non-geometric value could not
        // pass this: the assertion is that the compressed gap yields NO animation.)
        String[] real = transformsOf(ALIGNED_2ROW);
        Value el = makeCascadeEl(compressBaselines(real, 0.3));

        fx.getMember("cascade").execute(el);

        assertEquals("1", valueOrEmpty(js("__cel.style.opacity")), "the block is shown immediately");
        assertEquals("", rawOpacity(0), "compressed rows are fully visible, not hidden");
        assertEquals("", rawOpacity(3), "compressed rows are fully visible, not hidden");
        assertEquals(0, js("__lxFlushRaf(5)").asInt(), "ambiguous spacing schedules no frames");
    }

    // ---- pin 4b: inert — a bare fraction (single-glyph bands) --------------------

    @Test
    void cascadeIsInertOnABareFraction() throws IOException {
        boot(false);
        // \frac{a}{b} stacks numerator/denominator ~1.3em apart — as far as a real
        // display row, so gap-magnitude alone cannot reject it. The min-band-population
        // gate does: single-glyph num/denom bands are ambiguous -> INERT. (Documented
        // fragility: a MULTI-glyph fraction still reads as rows to pure geometry; that
        // is the case reserved for the later renderer-emitted rowmap.)
        Value el = makeCascadeEl(transformsOf("\\frac{a}{b}"));

        fx.getMember("cascade").execute(el);

        assertEquals("", rawOpacity(0), "a bare fraction is not animated (single-glyph bands)");
        assertEquals("", rawOpacity(1), "a bare fraction is not animated (single-glyph bands)");
        assertEquals(0, js("__lxFlushRaf(5)").asInt(), "a bare fraction schedules no frames");
    }

    // ---- pin 5: reduced motion snaps to fully visible ---------------------------

    @Test
    void cascadeReducedMotionSnapsToVisible() throws IOException {
        boot(true);
        // A genuinely multi-row block that WOULD stagger under normal motion.
        Value el = makeCascadeEl(transformsOf(ALIGNED_2ROW));

        fx.getMember("cascade").execute(el);

        assertEquals("1", valueOrEmpty(js("__cel.style.opacity")),
            "reduced motion reveals the container immediately");
        assertEquals("", rawOpacity(0), "reduced motion leaves every glyph fully visible (no hide)");
        assertEquals("", rawOpacity(3), "reduced motion leaves every glyph fully visible (no hide)");
        assertEquals(0, js("__lxFlushRaf(5)").asInt(), "reduced motion arms no animation frames");
    }

    // ---- pin 6: zero-surface (behavioral) — cascade reads/writes no data-lx-* ----

    @Test
    void cascadeReadsAndWritesNoDataLxAttributeSurface() throws IOException {
        boot(false);
        // Instrumented element: every getAttribute/setAttribute on the container AND its
        // paths is logged. Running cascade must touch NO data-lx-* attribute — it adds no
        // container attribute and reads no sidecar; the ONLY attribute it reads is the
        // glyph placement 'transform' already on the rendered path.
        String[] t = transformsOf(ALIGNED_2ROW);
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < t.length; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append('\'').append(t[i]).append('\'');
        }
        arr.append(']');
        js("globalThis.__attrLog = [];"
            + "globalThis.__cel = (function (transforms) {"
            + "  var log = globalThis.__attrLog;"
            + "  var paths = [];"
            + "  for (var i = 0; i < transforms.length; i++) {"
            + "    (function (tv) {"
            + "      var a = { transform: tv };"
            + "      paths.push({"
            + "        style: {},"
            + "        getAttribute: function (n) { log.push('GET ' + n); return a.hasOwnProperty(n) ? a[n] : null; },"
            + "        setAttribute: function (n, v) { log.push('SET ' + n); a[n] = String(v); }"
            + "      });"
            + "    })(transforms[i]);"
            + "  }"
            + "  var svg = { querySelectorAll: function (s) { return s === 'path' ? paths.slice() : []; } };"
            + "  return {"
            + "    __paths: paths, style: {},"
            + "    getAttribute: function (n) { log.push('GET ' + n); return null; },"
            + "    setAttribute: function (n, v) { log.push('SET ' + n); },"
            + "    querySelector: function (s) { return s === 'svg' ? svg : null; }"
            + "  };"
            + "})(" + arr + "); __cel");
        Value el = js("__cel");

        fx.getMember("cascade").execute(el);
        js("__lxFlushRaf(60)"); // run the whole reveal — attribute access happens during it too

        // Positive control: the instrument DID observe the placement read (else a broken
        // log would vacuously "prove" zero surface).
        assertTrue(intOf("__attrLog.filter(function (n) { return n === 'GET transform'; }).length") >= 1,
            "the instrument must observe cascade reading the placement 'transform'");
        // The property under test: NOTHING data-lx-* was read or written.
        assertEquals(0, intOf(
            "__attrLog.filter(function (n) { return String(n).indexOf('data-lx') >= 0; }).length"),
            "cascade must read/write NO data-lx-* attribute (no new container attribute, no sidecar): "
                + valueOrEmpty(js("JSON.stringify(__attrLog)")));
        // cascade sets NO attribute at all (pure geometry read + opacity write).
        assertEquals(0, intOf(
            "__attrLog.filter(function (n) { return String(n).indexOf('SET ') === 0; }).length"),
            "cascade must not setAttribute anything");
    }

    // ---- pin 7: zero-surface (source) — no new data-lx-* family in the runtime ---

    @Test
    void runtimeAddsNoNewDataLxAttributeFamilyForCascade() {
        // Enumerate every data-lx-* name the runtime SOURCE references (not a hand-kept
        // list), and require each to belong to a family main already knew at 8b2ff899:
        // the fx.* config attributes (data-lx-fx-*), or the two renderer sidecars
        // (data-lx-glyphmap / data-lx-groupmap). A cascade row/band sidecar would be a
        // NEW family and fail here; the explicit forbidden-name checks name the scope's
        // prohibitions directly so a differently-named sidecar cannot slip by.
        String runtime = LatteX.fxRuntimeJs();
        Matcher m = Pattern.compile("data-lx-[a-z0-9-]+").matcher(runtime);
        Set<String> names = new TreeSet<>();
        while (m.find()) {
            names.add(m.group());
        }
        assertTrue(names.contains("data-lx-glyphmap") && names.contains("data-lx-groupmap"),
            "sanity: the known renderer sidecars must still be present (enumeration works)");
        Pattern known = Pattern.compile(
            "^data-lx-fx-[a-z-]*$|^data-lx-glyphmap$|^data-lx-groupmap$");
        for (String n : names) {
            assertTrue(known.matcher(n).matches(),
                "runtime references a data-lx-* attribute outside the known families "
                    + "(fx.* config / glyphmap / groupmap): " + n);
            assertFalse(n.contains("cascade") || n.contains("row") || n.contains("band"),
                "cascade must add NO row/band/cascade sidecar attribute: " + n);
        }
        assertFalse(runtime.contains("data-lx-rowmap"), "no data-lx-rowmap sidecar");
        assertFalse(runtime.contains("data-lx-cascade"), "no data-lx-cascade sidecar");
    }
}
