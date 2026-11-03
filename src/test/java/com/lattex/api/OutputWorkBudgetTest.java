package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    "decoded-escape", "decoded-dollar", "decoded-nested")) {
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
        String nested = "\\text{$\\text{" + "a".repeat(40_000) + "}$}";
        String source = nested + nested;
        assertTrue(source.length() < 100_000);
        // Final text is only 80k, but decoding both outer and inner arguments is
        // over 100k work. A counter reset at each nested parser would admit it.
        MathSyntaxException failure = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse(source));
        assertTrue(failure.isCapExceeded());
        assertTrue(failure.getMessage().contains("materialized text during parse"));
        assertTrue(LatteX.toMathML("\\text{$\\text{healthy}$}").contains("healthy"));
    }

    @Test
    void independentReentrantParseHasItsOwnDecodeAllowance() {
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
