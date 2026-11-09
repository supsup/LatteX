package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.layout.RenderWorkBudget;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputWorkBudgetTest {
    @TempDir Path temporary;

    @Test
    void svgAndMathMLRefuseAmplificationInSmallHeapForks() throws Exception {
        for (String mode : List.of("svg", "mathml")) {
            for (String command : List.of("text", "textbf", "decoded-tilde", "decoded-braces",
                    "decoded-escape", "decoded-dollar", "decoded-nested",
                    "parsed-dollar", "parsed-paren")) {
                assertSmallHeapProbe(mode, command);
            }
        }
    }

    @Test
    void geometryWithoutTextRefusesBeforeAllocationInSmallHeapForks() throws Exception {
        // The disabled-accounting mutant must fail only in these bounded forks,
        // never by constructing a billion-rule witness in the test worker JVM.
        assertSmallHeapProbe("svg", "dashed");
        assertSmallHeapProbe("svg", "separator");
    }

    private void assertSmallHeapProbe(String mode, String command) throws Exception {
        Path log = temporary.resolve(mode + "-" + command + ".log");
        String javaName = System.getProperty("os.name").startsWith("Windows")
            ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Xmx48m", "-cp", probeClasspath(),
            OutputWorkBudgetProbe.class.getName(), mode, command)
            .redirectErrorStream(true).redirectOutput(log.toFile());
        // The heap bound must not be replaced or supplemented by ambient flags.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process fork = builder.start();
        try {
            assertTrue(fork.waitFor(30, TimeUnit.SECONDS), "small-heap probe timed out");
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, fork.exitValue(), output);
            assertEquals("OUTPUT_WORK_CAP_OK " + mode + " " + command,
                output.strip(), "typed cap, never OOM or an unrelated parser cap");
        } finally {
            if (fork.isAlive()) {
                fork.destroyForcibly();
                fork.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static String probeClasspath() throws Exception {
        // Gradle's java.class.path may contain only its worker bootstrap jar. Include
        // the actual main/test code sources and the bundled font's resource root too.
        Set<String> entries = new LinkedHashSet<>();
        entries.add(System.getProperty("java.class.path"));
        for (Class<?> type : List.of(OutputWorkBudgetProbe.class, LatteX.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString());
        }
        URL font = LatteX.class.getResource("/com/lattex/font/STIXTwoMath-Regular.ttf");
        if (font == null) {
            throw new AssertionError("bundled font missing from test classpath");
        }
        if (font.getProtocol().equals("jar")) {
            entries.add(Path.of(((JarURLConnection) font.openConnection()).getJarFileURL().toURI())
                .toString());
        } else {
            Path root = Path.of(font.toURI());
            for (int i = 0; i < 4; i++) {
                root = root.getParent();
            }
            entries.add(root.toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    @Test
    void representativeMacroAndSpliceVolumeStillAdmit() {
        String expanded = "\\text{healthy}".repeat(100);
        String macro = "\\newcommand{\\word}{\\text{healthy}}" + "\\word ".repeat(100);
        assertEquals(LatteX.render(expanded), LatteX.render(macro));
        assertEquals(LatteX.toMathML(expanded), LatteX.toMathML(macro));

        // Existing parser acceptance controls: preserve 90k body-spliced atoms and
        // 96k placeholder-spliced atoms. Rendering those may independently hit SVG's
        // established 2M-character cap; do not pretend that parser success promises SVG.
        String body = "x ".repeat(1_000).strip();
        MathNode ninetyThousand = MathParser.parse("\\newcommand{\\bigrun}{" + body + "}"
            + "\\bigrun ".repeat(90));
        assertEquals(MathParser.parse("x".repeat(90_000)), ninetyThousand);
        RenderWorkBudget.checkLayout(ninetyThousand);
        String def = "\\newcommand{\\rep}[1]{#1#1#1#1#1#1}";
        MathNode ninetySixThousand = MathParser.parse(def + ("\\rep{" + body + "} ").repeat(16));
        assertEquals(MathParser.parse("x".repeat(96_000)), ninetySixThousand);
        RenderWorkBudget.checkLayout(ninetySixThousand);
    }

    @Test
    void sharedTextAndOperatorPayloadsAreChargedPerOccurrence() {
        for (MathNode payload : List.of(
                new MathNode.TextRun("a".repeat(50_001), MathNode.TextStyle.ROMAN),
                new MathNode.OperatorName("a".repeat(50_001), false))) {
            MathNode repeated = new MathNode.MathList(List.of(payload, payload));
            assertTrue(assertThrows(MathSyntaxException.class,
                () -> RenderWorkBudget.checkLayout(repeated)).isCapExceeded());
            assertTrue(assertThrows(MathSyntaxException.class,
                () -> RenderWorkBudget.checkMathML(repeated)).isCapExceeded());
        }
    }

    @Test
    void textLimitCountsSpacesAndUtf16PairsWithAnAdmittedBoundaryControl() {
        for (String payload : List.of(" ".repeat(50_000), "😀".repeat(25_000))) {
            MathNode text = new MathNode.TextRun(payload, MathNode.TextStyle.ROMAN);
            MathNode atBoundary = new MathNode.MathList(List.of(text, text));
            // Two shared 50k-unit payloads must admit; node-identity deduplication
            // would incorrectly admit a third, including inkless spaces.
            RenderWorkBudget.checkLayout(atBoundary);
            RenderWorkBudget.checkMathML(atBoundary);
            MathNode over = new MathNode.MathList(List.of(text, text, text));
            assertTrue(assertThrows(MathSyntaxException.class,
                () -> RenderWorkBudget.checkLayout(over)).isCapExceeded());
            assertTrue(assertThrows(MathSyntaxException.class,
                () -> RenderWorkBudget.checkMathML(over)).isCapExceeded());
        }
    }

    @Test
    void programmaticFanOutAndDepthAreBoundedWithoutRecursiveOutput() {
        MathNode node = MathNode.Atom.ord('x');
        for (int i = 0; i < 600; i++) {
            node = new MathNode.Boxed(node);
        }
        MathNode deep = node;
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkLayout(deep)).isCapExceeded());
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkMathML(deep)).isCapExceeded());
        MathNode wide = new MathNode.MathList(java.util.Collections.nCopies(100_001,
            MathNode.Atom.ord('x')));
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkLayout(wide)).isCapExceeded());
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkMathML(wide)).isCapExceeded());
    }

    @Test
    void ordinaryWideMathMLIsPresentInDirectAndFragmentResults() {
        String row = String.join("&", java.util.Collections.nCopies(100, "x"));
        String array = "\\begin{array}{" + "c".repeat(100) + "}"
            + String.join("\\\\", java.util.Collections.nCopies(100, row)) + "\\end{array}";
        for (String source : List.of("x".repeat(20_000), array)) {
            String mathml = LatteX.toMathML(source);
            assertTrue(mathml.startsWith("<math "));
            assertTrue(mathml.endsWith("</math>"));
            // Preserve SVG's independent 2M-character cap: repeated inline font
            // outlines can exceed it before MathML runs. A real phantom renders
            // the SAME large body without ink, while preserving all its MathML.
            String fragmentSource = "\\phantom{" + source + "}";
            assertEquals(LatteX.toMathML(fragmentSource),
                LatteX.renderFragment(fragmentSource, 12).mathml(),
                "ordinary assistive output must not silently degrade");
        }
        for (String source : List.of("x".repeat(256),
                "\\begin{array}{cc}a&b\\\\c&d\\end{array}")) {
            assertEquals(LatteX.toMathML(source), LatteX.renderFragment(source, 12).mathml(),
                "inked ordinary fragments keep their full assistive output too");
        }
    }

    @Test
    void independentCopyAndMarkupLimitsHaveSpecificOracles() {
        MathNode deep = new MathNode.TextRun("a".repeat(2_000), MathNode.TextStyle.ROMAN);
        for (int i = 0; i < 350; i++) {
            deep = new MathNode.Boxed(deep);
        }
        MathNode copying = deep;
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkLayout(copying)).getMessage().contains("layout copying"));
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkMathML(copying)).getMessage().contains("MathML copying"));
        MathNode markup = new MathNode.MathList(java.util.Collections.nCopies(8_000,
            new MathNode.Boxed(MathNode.Atom.ord('x'))));
        assertTrue(assertThrows(MathSyntaxException.class,
            () -> RenderWorkBudget.checkMathML(markup)).getMessage().contains("MathML markup"));
    }

    @Test
    void genuinelyAmplifiedTextStillRefusesThroughDirectMathML() {
        MathSyntaxException failure = assertThrows(MathSyntaxException.class,
            () -> LatteX.toMathML(OutputWorkBudgetProbe.witness("decoded-nested")));
        assertTrue(failure.isCapExceeded());
        assertTrue(failure.getMessage().contains("materialized text during parse"));
        assertTrue(LatteX.toMathML("\\text{healthy}").contains("healthy"));
    }

    @Test
    void decodeWorkFollowsNestedParsersAndIsFreshAfterFailure() {
        String payload = "a".repeat(40_000);
        for (boolean paren : List.of(false, true)) {
            String nested = "\\text{" + (paren ? "\\(" : "$") + "\\text{" + payload + "}"
                + (paren ? "\\)" : "$") + "}";
            String definition = "\\newcommand{\\nested}{" + nested + "}";
            assertEquals(MathParser.parse(("\\text{" + payload + "}").repeat(2)),
                MathParser.parse(definition + "\\nested ".repeat(2)),
                "80k literal units admit without counting the surrounding math spans again");
            String source = definition + "\\nested ".repeat(3);
            assertTrue(source.length() < 100_000);
            // Each nested parser decodes 40k, for 120k actual literal work. Resetting
            // the shared counter or dropping the cap flag in either delimiter's
            // nested-span rewrap would incorrectly admit or misclassify it.
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(source));
            assertTrue(failure.isCapExceeded());
            assertTrue(failure.getMessage().contains("materialized text during parse"));
        }
        assertTrue(LatteX.toMathML("\\text{$\\text{healthy}$}").contains("healthy"));
    }

    @Test
    void capRefusedTagTextCannotRetryAsMath() {
        String definition = "\\newcommand{\\zzq}{\\tag{" + "a".repeat(40_000) + "}}";
        String source = definition + "\\begin{gather}x\\zzq\\\\x\\zzq\\\\x\\zzq\\end{gather}";
        assertTrue(source.length() < 100_000);
        // The third label exceeds 100k literal work. Retrying that refusal as
        // 40k math atoms would fit the token allowance and incorrectly accept it.
        MathSyntaxException failure = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(source));
        assertTrue(failure.isCapExceeded());
        assertTrue(failure.getMessage().contains("materialized text during parse"));
    }

    @Test
    void cumulativeParsedTokenBoundaryIsIndependentOfSourceLength() {
        String definition = "\\newcommand{\\zzq}{\\text{$" + "x".repeat(999) + "$}}";
        String below = definition + "\\zzq ".repeat(99) + "x".repeat(999);
        String at = definition + "\\zzq ".repeat(100);
        String above = at + "x";
        for (String source : List.of(below, at, above)) {
            assertTrue(source.length() < 100_000);
        }
        // 99 TEXT + 999 root atoms + 99*999 inner atoms = 99,999 tokens.
        // 100 TEXT + 100*999 inner atoms = 100,000; one root atom makes 100,001.
        // Definitions disappear before this counter, and EOF never consumes work.
        assertEquals(1_098, ((MathNode.MathList) MathParser.parse(below)).items().size());
        assertEquals(100, ((MathNode.MathList) MathParser.parse(at)).items().size());
        assertParsedTokenCap(above);
        // A failed scope cannot poison a later public parse. No render/preflight
        // participates in this direct ordinary-source boundary admit control.
        assertEquals(99_999,
            ((MathNode.MathList) MathParser.parse("x".repeat(99_999))).items().size());
        MathSyntaxException oldSourceCap = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x".repeat(100_001)));
        assertFalse(oldSourceCap.isCapExceeded(), "the existing source-length guard is unchanged");
        assertTrue(oldSourceCap.getMessage().contains("input too long"));
    }

    @Test
    void conservativeParsedTokenPolicyRefusesSmallOutputWithRepeatedBraces() {
        String source = "\\newcommand{\\zzq}{\\text{$" + "{".repeat(32) + "x"
            + "}".repeat(32) + "$}}" + "\\zzq ".repeat(2_000);
        assertEquals(10_093, source.length());
        // 2,000 root TEXT + 2,000*65 inner tokens = 132,000 work tokens.
        // Singleton braces collapse to only 2,000 final atoms: this is the named
        // conservative policy refusal, not an output-size or literal-text refusal.
        assertParsedTokenCap(source);
    }

    private static void assertParsedTokenCap(String source) {
        MathSyntaxException failure = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(source));
        assertTrue(failure.isCapExceeded());
        assertTrue(failure.getMessage().contains("output work budget exceeded: parsed tokens during parse"));
    }

    @Test
    void deeplyNestedMathSpansOnlyChargeTheirOwnLiteralSegments() {
        // This legal 79-deep input (the deepest the 80-level nesting limit admits:
        // 79 text levels plus the innermost y) collapses to y. Use the existing roomy-stack
        // positive venue so this checks accounting, independently of host stack size.
        int levels = StackStarved.PARSER_DEPTH_LIMIT - 1;
        assertEquals(79, levels, "the arithmetic below is written for the 80-level limit");
        String deepest = StackStarved.nestedText(levels);
        String[] result = (String[]) StackStarved.assertSucceedsOnRoomyStack(
            "79-deep text remains admitted by the output budget", () -> new String[] {
                LatteX.render(deepest), LatteX.toMathML(deepest)
            });
        assertTrue(result[0].startsWith("<svg"));
        assertTrue(result[0].contains("<path"));
        assertEquals(LatteX.toMathML("y"), result[1]);

        // Admit case, close to the cap on purpose: 79 levels x 1,253 literal units is 98,987
        // of the 100,000 allowance. Billing any enclosing level's payload again, or as little
        // as 13 extra units per segment, refuses it. Parse only: rendering this nested literal
        // tree has a separate copying allowance.
        int units = 99_000 / levels;
        assertEquals(1_253, units);
        String nearCap = ("\\text{" + "a".repeat(units) + "$").repeat(levels)
            + "y" + "$}".repeat(levels);
        assertTrue(nearCap.length() < 100_000);
        assertTrue(StackStarved.assertSucceedsOnRoomyStack(
            levels + " levels charge only their own " + units + " literal units",
            () -> MathParser.parse(nearCap)) instanceof MathNode.MathList);

        String literalDeep = ("\\text{" + "a".repeat(1_000) + "$").repeat(50)
            + "y" + "$}".repeat(50);
        assertTrue(literalDeep.length() < 100_000);
        String source = "\\newcommand{\\zzq}{" + literalDeep + "}"
            + "\\text{" + "b".repeat(5_000) + "}" + "\\zzq ".repeat(2);
        assertTrue(source.length() < 100_000);
        // 5k + two 50k payloads = 105k actual literal work, but only 103
        // non-EOF parsed tokens. Depth (51 of 80 levels) and source length stay
        // within their caps.
        MathSyntaxException failure = (MathSyntaxException) StackStarved.assertSucceedsOnRoomyStack(
            "50-deep literal amplification refuses with its typed decode reason",
            () -> assertThrows(MathSyntaxException.class, () -> MathParser.parse(source)));
        assertTrue(failure.isCapExceeded());
        assertTrue(failure.getMessage().contains("materialized text during parse"));
    }

    @Test
    void aRealOverflowLeavesItsThreadAbleToParseWithAFreshWorkAllowance() {
        // The parser binds its text allowance with a ScopedValue around the parse. A real
        // StackOverflowError unwinds THROUGH that scope before the entry point turns it into
        // the typed refusal. Afterwards the same thread must parse normally: two 60k literals
        // each fit their own 100k allowance, and would not fit one that leaked from the
        // failed scope or was shared between the two calls.
        String sixtyK = "\\text{" + "b".repeat(60_000) + "}";
        // Warm-up on a roomy stack first, so that nothing below is initialised or linked for the
        // first time inside the overflow window when this test runs alone: a shallow parse, the
        // 60k literal the follow-up uses, and the deep input's ordinary depth refusal.
        StackStarved.assertSucceedsOnRoomyStack("MathParser.parse(SHALLOW)",
            () -> MathParser.parse(StackStarved.SHALLOW));
        StackStarved.assertSucceedsOnRoomyStack("MathParser.parse(60k literal)",
            () -> MathParser.parse(sixtyK));
        StackStarved.assertRoomyParseIsGuardRefusal("MathParser.parse",
            () -> MathParser.parse(StackStarved.DEEP));
        StackStarved.assertStarvedParseHitsBackstop("MathParser.parse",
            () -> MathParser.parse(StackStarved.DEEP));
        StackStarved.Outcome[] outcomes = StackStarved.runThenOnSameThread(
            () -> MathParser.parse(StackStarved.DEEP),
            () -> {
                MathParser.parse(sixtyK);
                return MathParser.parse(sixtyK);
            });
        assertTrue(outcomes[0].threw() && StackStarved.isBackstop(outcomes[0].thrown()),
            "the starved parse must end in the BACKSTOP but " + outcomes[0].describe());
        assertFalse(outcomes[1].threw(),
            "after a real overflow the same thread must parse twice but " + outcomes[1].describe());
        // A lone \\text{...} parses to a single node, not a list: any parsed node is the point.
        assertTrue(outcomes[1].value() instanceof MathNode,
            "the second parse after the overflow must return a node but returned " + outcomes[1].describe());
    }

    @Test
    void independentReentrantParseHasItsOwnWorkAllowance() {
        List<String> environments = new java.util.ArrayList<>() {
            @Override public boolean add(String name) {
                MathParser.parse("\\text{" + "b".repeat(60_000) + "}");
                return super.add(name);
            }
        };
        // The caller-owned environment sink can enter the public parser again.
        // Each independent call admits 60k; sharing the outer allowance would cap.
        MathParser.parse("\\text{" + "a".repeat(60_000) + "}\\begin{align}x\\end{align}",
            java.util.Map.of(), environments);
        assertEquals(List.of("align"), environments);

        List<String> tokenEnvironments = new java.util.ArrayList<>() {
            @Override public boolean add(String name) {
                MathParser.parse("x".repeat(60_000));
                return super.add(name);
            }
        };
        // Both outer and reentrant public calls use over half the token allowance.
        // Sharing counters would refuse even though each independent call fits.
        MathParser.parse("x".repeat(60_000) + "\\begin{align}x\\end{align}",
            java.util.Map.of(), tokenEnvironments);
        assertEquals(List.of("align"), tokenEnvironments);
    }

    @Test
    void serializerTransparentWrappersDoNotConsumeMarkupAllowance() {
        String definition = "\\newcommand{\\z}{\\mathord{x}}";
        String ordinary = definition + "\\z ".repeat(8);
        assertEquals(LatteX.toMathML(ordinary), LatteX.renderFragment(ordinary, 12).mathml());

        String repeated = definition + "\\phantom{" + "\\z ".repeat(8_000) + "}";
        assertTrue(repeated.length() < 100_000);
        String mathml = LatteX.toMathML(repeated);
        assertEquals(8_000, mathml.split("<mi>x</mi>", -1).length - 1,
            "all repeated atoms remain, despite transparent class wrappers");
        assertEquals(mathml, LatteX.renderFragment(repeated, 12).mathml(),
            "phantom isolates MathML calibration from the independent SVG cap");
    }
}
