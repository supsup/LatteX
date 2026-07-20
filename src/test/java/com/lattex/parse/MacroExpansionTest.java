package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L8 macro expansion (plan 68f2eb85): {@code \newcommand}/{@code \renewcommand}/
 * {@code \def} subset, {@code #1..#9} argument splicing, preset maps, the
 * additive-only namespace rule, and the two DoS caps.
 *
 * <p>The load-bearing oracle is NODE EQUALITY: {@code parse(macro input)} must
 * equal {@code parse(hand-expanded input)} (every MathNode is a record), so a
 * macro that expands to the wrong tokens cannot pass by "parses without
 * throwing". Negatives assert positioned {@link MathSyntaxException}s with the
 * cap-vs-author split pinned via {@link MathSyntaxException#isCapExceeded()}.
 */
class MacroExpansionTest {

    // ---- expansion semantics -------------------------------------------------

    @Test
    void zeroArgMacroExpandsToItsBody() {
        assertEquals(MathParser.parse("\\frac{1}{2} + x"),
            MathParser.parse("\\newcommand{\\half}{\\frac{1}{2}} \\half + x"));
    }

    @Test
    void argumentsSpliceIntoBody() {
        assertEquals(MathParser.parse("\\langle a+b \\rangle"),
            MathParser.parse("\\newcommand{\\norm}[1]{\\langle #1 \\rangle} \\norm{a+b}"));
    }

    @Test
    void multipleArgumentsAndRepeatedUse() {
        assertEquals(MathParser.parse("{a+b} \\cdot {c} \\cdot {a+b}"),
            MathParser.parse(
                "\\newcommand{\\pair}[2]{{#1} \\cdot {#2} \\cdot {#1}} \\pair{a+b}{c}"));
    }

    @Test
    void undelimitedSingleTokenArgument() {
        // LaTeX's undelimited-argument rule: \sq x grabs the single token x.
        assertEquals(MathParser.parse("x^{2}"),
            MathParser.parse("\\newcommand{\\sq}[1]{#1^{2}} \\sq x"));
    }

    @Test
    void macrosMayUseOtherMacros() {
        assertEquals(MathParser.parse("\\langle \\frac{1}{2} \\rangle"),
            MathParser.parse("\\newcommand{\\half}{\\frac{1}{2}}"
                + "\\newcommand{\\norm}[1]{\\langle #1 \\rangle} \\norm{\\half}"));
    }

    @Test
    void renewcommandRedefinesAUserMacro() {
        assertEquals(MathParser.parse("y"),
            MathParser.parse(
                "\\newcommand{\\val}{x} \\renewcommand{\\val}{y} \\val"));
    }

    @Test
    void defDefinesWithInferredArity() {
        assertEquals(MathParser.parse("\\frac{a}{b}"),
            MathParser.parse("\\def\\quot{\\frac{#1}{#2}} \\quot{a}{b}"));
    }

    @Test
    void presetMacrosViaParseOverload() {
        assertEquals(MathParser.parse("\\mathbb{R}^{n}"),
            MathParser.parse("\\R^{n}", Map.of("R", "\\mathbb{R}")));
    }

    @Test
    void presetAndInlineShareOneNamespace() {
        // \renewcommand may redefine a preset (it is a user macro)…
        assertEquals(MathParser.parse("y"),
            MathParser.parse("\\renewcommand{\\val}{y} \\val", Map.of("val", "x")));
        // …and \newcommand of a preset name is a duplicate.
        MathSyntaxException dup = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\val}{y} \\val", Map.of("val", "x")));
        assertTrue(dup.getMessage().contains("already defined"), dup.getMessage());
    }

    @Test
    void macrosApplyInsideAnLxBody() {
        assertEquals(MathParser.parse("\\lx{\\mathbb{R}}"),
            MathParser.parse("\\lx{\\R}", Map.of("R", "\\mathbb{R}")));
    }

    @Test
    void expansionChangesTheParseNotJustSurvivesIt() {
        // Non-vacuity for the equality oracle itself: the macro input differs from
        // an unrelated expression, so equality above cannot hold trivially.
        assertNotEquals(MathParser.parse("z"),
            MathParser.parse("\\newcommand{\\half}{\\frac{1}{2}} \\half"));
    }

    // ---- additive-only namespace --------------------------------------------

    @Test
    void definingABuiltinIsRefused() {
        for (String probe : new String[] {
            "\\newcommand{\\frac}{x}",       // structural switch case
            "\\newcommand{\\alpha}{x}",      // Symbols table

            "\\def\\sum{x}",                 // big operator, via \def
        }) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(probe), probe);
            assertTrue(e.getMessage().contains("built-in"), probe + " -> " + e.getMessage());
        }
        // Text-family names (\textbf …) are LEXER-reserved: the lexer eats a {…}
        // argument for them, so a definition cannot even NAME one — it fails at
        // lex with its own message, which is additive-only enforced one level
        // deeper than the deny check.
        MathSyntaxException textFamily = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\textbf}{x}"));
        assertTrue(textFamily.getMessage().contains("text argument"), textFamily.getMessage());
        MathSyntaxException preset = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x", Map.of("frac", "y")));
        assertTrue(preset.getMessage().contains("built-in"), preset.getMessage());
    }

    @Test
    void renewcommandOfUndefinedOrBuiltinIsRefused() {
        MathSyntaxException undef = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\renewcommand{\\nope}{x} \\nope"));
        assertTrue(undef.getMessage().contains("not a defined macro"), undef.getMessage());
        MathSyntaxException builtin = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\renewcommand{\\frac}{x}"));
        assertTrue(builtin.getMessage().contains("built-in"), builtin.getMessage());
    }

    @Test
    void duplicateNewcommandIsRefused() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\val}{x} \\newcommand{\\val}{y}"));
        assertTrue(e.getMessage().contains("already defined"), e.getMessage());
    }

    @Test
    void unknownCommandsStillFailLoudWithoutMacros() {
        // The pre-macro behavior is untouched: an undefined \foo still errors.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\notdefinedanywhere"));
        assertTrue(e.getMessage().contains("Unknown command"), e.getMessage());
    }

    /// lattex 253 F1: the deny check must come from ONE parser authority. These six were all
    /// shadowable under the hand-maintained list (each is parser-known but was absent from it);
    /// the probe-based check must refuse every one — and still admit a genuinely free name in
    /// the same fixture, so the probe cannot pass by denying everything.
    @org.junit.jupiter.api.Test
    void theSixEscapeesAreDeniedAndFreshNamesStillAdmit() {
        for (String name : new String[] {"fbox", "mkern", "kern", "mskip", "hdashline", "nolimits"}) {
            MathSyntaxException e = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("x", Map.of(name, "y")),
                "parser-known name must be denied: " + name);
            assertTrue(e.getMessage().contains("built-in"), name + " -> " + e.getMessage());
        }
        assertEquals(MathParser.parse("y"), MathParser.parse("\\freshname", Map.of("freshname", "y")));
    }

    /// lattex 253 F2a: splice VOLUME is its own axis — 1,000-token body x 1,000 invocations is
    /// ~4KB of source and 1,000,001 materialized tokens; invocation count (1,000 < 10,000) and
    /// depth (1) never fire. The output budget must trip as a resource cap, and the just-under
    /// control must still parse.
    @org.junit.jupiter.api.Test
    void spliceVolumeTripsTheOutputBudgetAsACap() {
        String body = "x ".repeat(1000).strip();               // 1,000 CHAR tokens
        String calls = "\\bigrun ".repeat(1000);
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\bigrun}{" + body + "}" + calls));
        assertTrue(e.getMessage().contains("output budget"), e.getMessage());
        assertTrue(e.isCapExceeded(), "splice volume is a resource cap");
        // just-under control: 90 invocations x 1,000 tokens = 90,000 < 100,000
        assertEquals(MathParser.parse("x".repeat(90_000)),
            MathParser.parse("\\newcommand{\\bigrun}{" + body + "}" + "\\bigrun ".repeat(90)));
    }

    /// lattex 261: repeated PLACEHOLDER references are the second amplification axis — a body
    /// of six `#1`s splices six copies of the argument per invocation, so charging each argument
    /// once under-counted 6x. 6 refs x 1,000-token arg x 20 invocations = 120,000 materialized
    /// tokens from ~40KB of source (source ceiling, invocation count, and depth all green); the
    /// exact per-occurrence count must trip the output budget as a resource cap.
    @org.junit.jupiter.api.Test
    void repeatedPlaceholderReferencesAreChargedPerOccurrence() {
        String arg = "x ".repeat(1000).strip();                // 1,000 CHAR tokens
        String def = "\\newcommand{\\rep}[1]{#1#1#1#1#1#1}";
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(def + ("\\rep{" + arg + "} ").repeat(20)));
        assertTrue(e.getMessage().contains("output budget"), e.getMessage());
        assertTrue(e.isCapExceeded(), "placeholder amplification is a resource cap");
        // just-under control: 16 invocations x 6,000 spliced tokens = 96,000 < 100,000,
        // node-equal to its hand form.
        assertEquals(MathParser.parse("x".repeat(96_000)),
            MathParser.parse(def + ("\\rep{" + arg + "} ").repeat(16)));
    }

    /// lattex 253 F2b: preset bodies lex through the same lexer as source, so the source ceiling
    /// applies to them (cumulatively — the map is one input), used or not.
    @org.junit.jupiter.api.Test
    void oversizedPresetBodiesAreRefusedEvenWhenUnused() {
        String huge = "x".repeat(100_001);
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("y", Map.of("unusedbig", huge)));
        assertTrue(e.getMessage().contains("source ceiling"), e.getMessage());
    }

    // ---- malformed definitions / invocations --------------------------------

    @Test
    void strayHashInBodyIsRefused() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\val}{a # b} \\val"));
        assertTrue(e.getMessage().contains("stray #"), e.getMessage());
    }

    @Test
    void bodyReferencingMoreArgsThanDeclaredIsRefused() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\val}[1]{#1 #2} \\val{a}{b}"));
        assertTrue(e.getMessage().contains("declares only"), e.getMessage());
    }

    @Test
    void missingArgumentsAreRefused() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\pair}[2]{#1#2} \\pair{a}"));
        assertTrue(e.getMessage().contains("expects 2 argument"), e.getMessage());
    }

    @Test
    void definitionsInsideMacroBodiesAreRefused() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\val}{\\newcommand{\\w}{x}} \\val"));
        assertTrue(e.getMessage().contains("may not contain definitions"), e.getMessage());
    }

    // ---- the two DoS caps: depth (author error) vs count (resource cap) -----

    @Test
    void selfRecursionTripsTheDepthCapAsAPositionedAuthorError() {
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\a}{\\a} \\a"));
        assertTrue(e.getMessage().contains("macro recursion too deep"), e.getMessage());
        assertFalse(e.isCapExceeded(),
            "runaway recursion is an author-facing error (the MAX_DEPTH shape), not a resource cap");
    }

    @Test
    void expansionBombTripsTheBudgetAsACapExceededResourceError() {
        // Doubling chain 13 deep: total invocations 2^14-1 = 16383 > 10_000, while
        // nesting stays at 13 — the bomb the depth cap alone cannot see.
        MathSyntaxException e = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(doublingChain(13)));
        assertTrue(e.getMessage().contains("macro expansion budget exceeded"), e.getMessage());
        assertTrue(e.isCapExceeded(),
            "an expansion bomb is a resource cap (the MAX_LAYOUT_BOXES shape)");
    }

    @Test
    void legalDeepNestingJustUnderTheDepthCapStillParses() {
        // 63 nested distinct macros — one under MAX_MACRO_DEPTH. The paired
        // just-under-legal test: without it the depth cap could reject everything.
        StringBuilder sb = new StringBuilder("\\newcommand{\\mA}{x}");
        String prev = "mA";
        for (int i = 1; i < 63; i++) {
            String name = "m" + (char) ('A' + i / 26) + (char) ('a' + i % 26);
            sb.append("\\newcommand{\\").append(name).append("}{\\").append(prev).append("}");
            prev = name;
        }
        sb.append("\\").append(prev);
        assertEquals(MathParser.parse("x"), MathParser.parse(sb.toString()));
    }

    @Test
    void legalWideExpansionJustUnderTheBudgetStillParses() {
        // Doubling chain 12 deep: 2^13-1 = 8191 invocations < 10_000 — the paired
        // just-under-legal test for the budget.
        assertEquals(MathParser.parse("x".repeat(4096)),
            MathParser.parse(doublingChain(12)));
    }

    /** Doubling chain: 2^(k+1)-1 total invocations for depth k ("q"-prefixed names — no Greek collisions). */
    private static String doublingChain(int k) {
        StringBuilder sb = new StringBuilder("\\newcommand{\\qqa}{x}");
        String prev = "qqa";
        for (int i = 1; i <= k; i++) {
            String name = "qq" + (char) ('a' + i);
            sb.append("\\newcommand{\\").append(name).append("}{\\").append(prev)
                .append("\\").append(prev).append("}");
            prev = name;
        }
        return sb.append("\\").append(prev).toString();
    }
}
