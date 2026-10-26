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
            for (String command : List.of("text", "textbf")) {
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
}
