package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lattex.api.LatteX;
import com.lattex.parse.CommandRegistry.Descriptor;
import com.lattex.parse.CommandRegistry.GrammarKind;
import com.lattex.parse.CommandRegistry.Handler;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TeX's undelimited-argument rule (The TeXbook, Ch. 20, "Definitions"): an argument
 * that is not delimited is the NEXT TOKEN — one character, one control sequence, or
 * one brace group — with spaces before it skipped. So {@code \mathrm u} is
 * {@code \mathrm{u}}, {@code x\pmod q} is {@code x\pmod{q}} and {@code \frac12} is
 * {@code \frac{1}{2}}.
 *
 * <p>Plan 18e34d82. Measured on 138,172 research-paper display formulas (LatteX main
 * 066b90f): {@code \mathrm u} refused 1,040 times and {@code \pmod 2/q/p/M/D/r}
 * several hundred more, every one of them ordinary, correct LaTeX.
 *
 * <p>The property pinned here is EQUIVALENCE, not mere acceptance: the unbraced
 * form must parse to the SAME tree as the braced form, so "renders without
 * throwing" cannot pass for a mis-scoped argument (e.g. {@code \pmod q r} taking
 * {@code qr}).
 */
class SingleTokenArgumentTest {

    private static void assertSameAsBraced(String braced, String unbraced) {
        MathNode expected = MathParser.parse(braced);
        MathNode actual;
        try {
            actual = MathParser.parse(unbraced);
        } catch (MathSyntaxException e) {
            fail(unbraced + " must read as " + braced + " (TeX single-token argument), but refused: "
                + e.getMessage());
            return;
        }
        assertEquals(expected, actual, unbraced + " must parse exactly as " + braced);
    }

    // ------------------------------------------------------------------
    // The corpus cases, each red on 066b90f unless noted.
    // ------------------------------------------------------------------

    @Test
    void textArgumentCommandsTakeOneCharacter() {
        assertSameAsBraced("M_{\\mathrm{u}}", "M_{\\mathrm u}");
        assertSameAsBraced("\\text{a}", "\\text a");
        assertSameAsBraced("\\textbf{x}y", "\\textbf x y");
        // The space between the command and its token is skipped, as TeX does; a
        // space AFTER the token ends nothing — the argument was one token.
        assertSameAsBraced("\\mathrm{d}x", "\\mathrm dx");
    }

    @Test
    void textArgumentCommandsTakeOneControlSequence() {
        // \mathrm\alpha IS \mathrm{\alpha}: same tree or the same refusal.
        assertSameAsBraced("\\text{\\%}", "\\text\\%");
        String braced = "\\mathrm{\\alpha}";
        String unbraced = "\\mathrm\\alpha";
        MathNode bracedTree = null;
        String bracedRefusal = null;
        try {
            bracedTree = MathParser.parse(braced);
        } catch (MathSyntaxException e) {
            bracedRefusal = e.getMessage();
        }
        if (bracedTree != null) {
            assertEquals(bracedTree, MathParser.parse(unbraced));
        } else {
            MathSyntaxException e = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(unbraced));
            assertEquals(bracedRefusal, e.getMessage(),
                "the unbraced control sequence must fail exactly as its braced form does");
        }
    }

    @Test
    void pmodTakesOneToken() {
        assertSameAsBraced("x \\pmod{q}", "x \\pmod q");
        assertSameAsBraced("a\\equiv b\\pmod{2}", "a\\equiv b\\pmod2");
        assertSameAsBraced("x\\pmod{\\varepsilon}", "x\\pmod\\varepsilon");
        // One token, not a run: \pmod q r is \pmod{q} r.
        assertSameAsBraced("x\\pmod{q}r", "x\\pmod q r");
    }

    @Test
    void boldsymbolTakesOneTokenAndItsScriptsAttachOutside() {
        // Green on 066b90f: the corpus failure on this line was its trailing \pmod2.
        assertSameAsBraced("\\boldsymbol{1}_{b}", "\\boldsymbol 1_{b}");
        assertSameAsBraced("\\boldsymbol{1}_{b\\in u}+\\boldsymbol{1}_{n\\in v}\\pmod{2}",
            "\\boldsymbol 1_{b\\in u} +\\boldsymbol 1_{n\\in v}\\pmod2");
        assertSameAsBraced("\\mathbf{1}_{A}", "\\mathbf 1_A");
    }

    @Test
    void theClassicMathCasesStillRead() {
        // Green on 066b90f; pinned so the shared reader cannot regress them.
        assertSameAsBraced("\\frac{1}{2}", "\\frac12");
        assertSameAsBraced("\\frac{1}{n}", "\\frac1{n}");
        assertSameAsBraced("\\frac{a}{b}", "\\frac{a}b");
        assertSameAsBraced("\\sqrt{2}", "\\sqrt2");
        assertSameAsBraced("\\hat{x}", "\\hat x");
        assertSameAsBraced("x^{\\alpha}", "x^\\alpha");
        assertSameAsBraced("\\mathbf{\\alpha}", "\\mathbf\\alpha");
    }

    @Test
    void phantomsLabelsReferencesAndTagsTakeOneToken() {
        assertSameAsBraced("a\\phantom{x}b", "a\\phantom x b");
        assertSameAsBraced("a\\hphantom{x}b", "a\\hphantom x b");
        assertSameAsBraced("a\\vphantom{x}b", "a\\vphantom x b");
        assertSameAsBraced("x\\label{k}", "x\\label k");
        assertSameAsBraced("x=\\ref{k}", "x=\\ref k");
        assertSameAsBraced("x=\\eqref{k}", "x=\\eqref k");
        assertSameAsBraced("x\\tag{1}", "x\\tag1");
        assertSameAsBraced("\\operatorname{f}x", "\\operatorname f x");
        assertSameAsBraced("\\sum_{\\substack{i}}", "\\sum_{\\substack i}");
    }

    // ------------------------------------------------------------------
    // No token at all: still a typed, caret-pointing refusal.
    // ------------------------------------------------------------------

    @Test
    void noTokenIsStillATypedPositionedRefusal() {
        // Each source has its argument slot at a known offset: end of input, '}',
        // '&' (a cell boundary), or '\\' (a row boundary). None may be silently
        // swallowed as an argument.
        record Probe(String source, int caret) { }
        List<Probe> probes = List.of(
            new Probe("x\\pmod", 6),
            new Probe("{x\\pmod}", 7),
            new Probe("\\begin{matrix}\\pmod&b\\end{matrix}", 19),
            new Probe("\\begin{matrix}\\pmod\\\\b\\end{matrix}", 19),
            new Probe("\\frac1", 6),
            new Probe("{\\frac1}", 7),
            new Probe("\\begin{matrix}\\frac1&b\\end{matrix}", 20),
            new Probe("\\begin{matrix}\\hat\\\\b\\end{matrix}", 18),
            new Probe("{\\phantom}", 9),
            new Probe("\\mathbf", 7),
            new Probe("\\sqrt", 5));
        for (Probe probe : probes) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(probe.source()), probe.source());
            assertEquals(probe.caret(), e.offset(),
                probe.source() + ": the caret must point at the missing argument; " + e.getMessage());
            assertFalse(e.caretString().isEmpty(), probe.source());
        }
        // Text-argument commands keep their established message at the command.
        MathSyntaxException text = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\mathrm"));
        assertEquals("\\mathrm expects a '{...}' text argument", text.getMessage());
        assertEquals(0, text.offset());
        MathSyntaxException textBrace = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("{\\text}"));
        assertEquals("\\text expects a '{...}' text argument", textBrace.getMessage());
    }

    @Test
    void aMatrixCellBoundaryIsNeverAnArgument() {
        // Before this plan, \frac1&2 inside a matrix read '&' as the denominator and
        // silently merged two cells. TeX: '&' ends the cell, so the argument is missing.
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{matrix}\\frac1&2\\end{matrix}"));
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{matrix}x^&2\\end{matrix}"));
    }

    // ------------------------------------------------------------------
    // The census: every argument-taking command in the registry.
    // ------------------------------------------------------------------

    /** Argument grammars whose slots follow TeX's undelimited-argument rule. */
    private static final Set<GrammarKind> ARGUMENT_GRAMMARS = EnumSet.of(
        GrammarKind.ONE_ARGUMENT, GrammarKind.TWO_ARGUMENTS, GrammarKind.THREE_ARGUMENTS,
        GrammarKind.OPTIONAL_THEN_ARGUMENT, GrammarKind.TEXT_ARGUMENT);

    /**
     * One braced/unbraced pair per handler, written with {@code %s} for the command
     * name. Every argument slot in the unbraced form is a single token.
     */
    private static final Map<Handler, String[]> PROBES = probes();

    private static Map<Handler, String[]> probes() {
        Map<Handler, String[]> p = new EnumMap<>(Handler.class);
        p.put(Handler.ACCENT, new String[] {"\\%s{x}", "\\%s x"});
        p.put(Handler.FONT_VARIANT, new String[] {"\\%s{x}", "\\%s x"});
        p.put(Handler.ATOM_CLASS, new String[] {"a\\%s{x}b", "a\\%s x b"});
        p.put(Handler.TEXT, new String[] {"\\%s{u}", "\\%s u"});
        p.put(Handler.FRACTION, new String[] {"\\%s{1}{2}", "\\%s12"});
        p.put(Handler.CONTINUED_FRACTION, new String[] {"\\%s{1}{2}", "\\%s12"});
        p.put(Handler.DISPLAY_FRACTION, new String[] {"\\%s{1}{2}", "\\%s12"});
        p.put(Handler.TEXT_FRACTION, new String[] {"\\%s{1}{2}", "\\%s12"});
        p.put(Handler.TEXT_COLOR, new String[] {"\\%s{red}{x}", "\\%s{red}x"});
        p.put(Handler.BOXED, new String[] {"\\%s{x}", "\\%s x"});
        p.put(Handler.CANCEL, new String[] {"\\%s{x}", "\\%s x"});
        p.put(Handler.CANCEL_TO, new String[] {"\\%s{0}{x}", "\\%s0x"});
        p.put(Handler.BRA, new String[] {"\\%s{\\psi}", "\\%s\\psi"});
        p.put(Handler.KET, new String[] {"\\%s{\\psi}", "\\%s\\psi"});
        p.put(Handler.BRAKET, new String[] {"\\%s{a}", "\\%s a"});
        p.put(Handler.PRESCRIPT, new String[] {"\\%s{1}{2}{C}", "\\%s12C"});
        p.put(Handler.BINOM, new String[] {"\\%s{n}{k}", "\\%s nk"});
        p.put(Handler.DISPLAY_BINOM, new String[] {"\\%s{n}{k}", "\\%s nk"});
        p.put(Handler.TEXT_BINOM, new String[] {"\\%s{n}{k}", "\\%s nk"});
        p.put(Handler.RADICAL, new String[] {"\\%s[3]{x}", "\\%s[3]x"});
        p.put(Handler.OVERSET, new String[] {"\\%s{!}{=}", "\\%s!="});
        p.put(Handler.UNDERSET, new String[] {"\\%s{i}{x}", "\\%s ix"});
        p.put(Handler.STACKREL, new String[] {"\\%s{!}{=}", "\\%s!="});
        p.put(Handler.UNDERBRACE, new String[] {"\\%s{x}_{n}", "\\%s x_n"});
        p.put(Handler.OVERBRACE, new String[] {"\\%s{x}^{n}", "\\%s x^n"});
        p.put(Handler.X_ARROW, new String[] {"\\%s{f}", "\\%s f"});
        p.put(Handler.SUBSTACK, new String[] {"\\sum_{\\%s{i}}", "\\sum_{\\%s i}"});
        p.put(Handler.PHANTOM, new String[] {"a\\%s{x}b", "a\\%s x b"});
        p.put(Handler.HPHANTOM, new String[] {"a\\%s{x}b", "a\\%s x b"});
        p.put(Handler.VPHANTOM, new String[] {"a\\%s{x}b", "a\\%s x b"});
        p.put(Handler.OPERATOR_NAME, new String[] {"\\%s{f}x", "\\%s f x"});
        p.put(Handler.PMOD, new String[] {"a\\%s{m}", "a\\%s m"});
        // \mathop (plan edbda088) reads its body through parseFontArg; with a script so the
        // probe also covers the Op-class limits path.
        p.put(Handler.MATHOP, new String[] {"\\%s{x}_a", "\\%s x_a"});
        p.put(Handler.LABEL, new String[] {"x\\%s{k}", "x\\%s k"});
        p.put(Handler.REFERENCE, new String[] {"x=\\%s{k}", "x=\\%s k"});
        // \tag is TOP_LEVEL-grammar (equation-global) but takes one argument too.
        p.put(Handler.TAG, new String[] {"x\\%s{1}", "x\\%s1"});
        return p;
    }

    /**
     * Handlers whose argument has NO valid single-token value, so a single token
     * must reach the handler's own DOMAIN check (and fail there, typed), never a
     * "needs a brace" refusal. The census asserts exactly that.
     */
    private static final Map<Handler, String[]> DOMAIN_ONLY = Map.of(
        // A colour is a name or a #hex literal; no colour is one character.
        Handler.TEXT_COLOR, new String[] {"\\%s r x", "color"},
        // A bordermatrix needs a header row AND a body row; one token is neither.
        Handler.BORDER_MATRIX, new String[] {"\\%s x", "body row"});

    @Test
    void censusEveryArgumentTakingCommandAcceptsASingleTokenArgument() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Descriptor d : CommandRegistry.descriptors()) {
            boolean takesArgument = ARGUMENT_GRAMMARS.contains(d.grammarKind())
                || d.handler() == Handler.TAG;
            if (!takesArgument) {
                continue;
            }
            String[] probe = PROBES.get(d.handler());
            String[] domain = DOMAIN_ONLY.get(d.handler());
            if (probe == null && domain == null) {
                failures.add(d.displayName() + ": handler " + d.handler()
                    + " takes an argument but has no single-token census probe");
                continue;
            }
            if (probe != null) {
                String braced = probe[0].formatted(d.name());
                String unbraced = probe[1].formatted(d.name());
                try {
                    MathNode expected = MathParser.parse(braced);
                    MathNode actual = MathParser.parse(unbraced);
                    if (!expected.equals(actual)) {
                        failures.add(unbraced + " parsed differently from " + braced);
                    }
                } catch (MathSyntaxException e) {
                    failures.add(unbraced + " refused: " + e.getMessage());
                }
                checked++;
            }
            if (domain != null) {
                String source = domain[0].formatted(d.name());
                try {
                    MathParser.parse(source);
                    failures.add(source + " was accepted; expected its domain refusal");
                } catch (MathSyntaxException e) {
                    String m = e.getMessage();
                    if (!m.contains(domain[1]) || m.contains("'{")) {
                        failures.add(source + ": a single token must reach the "
                            + domain[1] + " check, not a brace refusal; got: " + m);
                    }
                }
                checked++;
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " census failures:\n  "
            + String.join("\n  ", failures));
        // Census size floor: if the registry loses its argument commands, this is
        // a broken census, not a passing one.
        assertTrue(checked >= 80, "census checked only " + checked + " commands");
    }

    @Test
    void censusProbesNameOnlyRealHandlers() {
        // A probe for a handler with no argument-taking descriptor is dead weight
        // that would hide a renamed handler.
        Set<Handler> live = EnumSet.noneOf(Handler.class);
        for (Descriptor d : CommandRegistry.descriptors()) {
            live.add(d.handler());
        }
        for (Handler h : PROBES.keySet()) {
            assertTrue(live.contains(h), h + " has a probe but no descriptor");
        }
        assertNotNull(CommandRegistry.get("pmod"));
    }

    // ------------------------------------------------------------------
    // Bounded input still holds.
    // ------------------------------------------------------------------

    @Test
    void singleTokenNestingIsStillDepthBounded() {
        // \hat\hat\hat...x nests one accent per token; past MAX_DEPTH it must be a
        // typed refusal, not a stack overflow.
        String deep = "\\hat".repeat(MathParser.MAX_DEPTH + 10) + " x";
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(deep));
        assertTrue(e.getMessage().contains("nesting too deep"), e.getMessage());
        String deepPmod = "\\pmod".repeat(MathParser.MAX_DEPTH + 10) + " x";
        MathSyntaxException pm = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(deepPmod));
        assertTrue(pm.getMessage().contains("nesting too deep"), pm.getMessage());
    }

    @Test
    void manySingleTokenArgumentsRenderEndToEnd() {
        // The rendered path (layout + SVG) accepts the unbraced forms the corpus uses.
        for (String src : List.of("|I^{M_{\\mathrm u}}|=|V_\\eta|",
                "\\Pr[x'\\equiv x\\pmod q]", "p\\equiv u\\pmod M", "\\frac12+\\sqrt2")) {
            assertTrue(LatteX.render(src).startsWith("<svg"), src);
        }
    }
}
