package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exported-API-boundary regression (plan 0c4f6015, Marlow audit LTX-08).
 *
 * <p>Two load-bearing checks, both driving the real {@code javac} in-process:
 *
 * <ol>
 *   <li><b>{@link #modularConsumerCompilesAgainstExportedSurface}</b> — compiles the modular
 *       consumer fixture ({@code src/test/resources/modularconsumer/}: a named module that
 *       {@code requires com.lattex}, catches the render exception, and selects a math style) on the
 *       {@code --module-path} of the compiled {@code com.lattex} module. It uses ONLY exported
 *       {@code com.lattex.api} types. Pre-fix this FAILED to compile ({@code com.lattex.parse} not
 *       visible; {@code com.lattex.api.MathStyle} / {@code LatteXException} absent). It is the
 *       standing proof the boundary is self-sufficient and stays so.</li>
 *   <li><b>{@link #exportedApiLeaksNoNonExportedType}</b> — recompiles the whole main source tree
 *       with {@code -Xlint:exports} and asserts ZERO exports warnings, i.e. no public API signature
 *       names a type from a non-exported package (the {@code RenderOptions}/{@code MathStyle} leak
 *       LTX-08 flagged, six warnings pre-fix).</li>
 * </ol>
 *
 * <p>Both read their inputs from system properties set by the {@code test} task in
 * {@code build.gradle.kts} — {@code lattex.mainClasses} (the exploded module dir) and
 * {@code lattex.mainSrc} (the main source root).
 */
class ModularBoundaryTest {

    private static JavaCompiler compiler() {
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        assertNotNull(c, "no system Java compiler — run the test on a JDK, not a JRE");
        return c;
    }

    private static Path requiredPathProperty(String key) {
        String v = System.getProperty(key);
        assertNotNull(v, "system property " + key + " not set by the test task");
        Path p = Path.of(v);
        assertTrue(Files.exists(p), key + " does not exist: " + p);
        return p;
    }

    /** Copies a fixture source resource to {@code dst}, creating parent dirs. */
    private static void copyResource(String resource, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try (InputStream in = ModularBoundaryTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource on the test classpath: " + resource);
            Files.write(dst, in.readAllBytes());
        }
    }

    @Test
    void modularConsumerCompilesAgainstExportedSurface(@TempDir Path tmp) throws IOException {
        Path modulePath = requiredPathProperty("lattex.mainClasses");

        // Materialize the fixture with its real JPMS layout: module-info at the source root,
        // the consumer under its package dir.
        Path src = tmp.resolve("src");
        Path moduleInfo = src.resolve("module-info.java");
        Path consumer = src.resolve("com/lattexprobe/Consumer.java");
        copyResource("/modularconsumer/module-info.java", moduleInfo);
        copyResource("/modularconsumer/com/lattexprobe/Consumer.java", consumer);

        Path out = Files.createDirectories(tmp.resolve("out"));

        JavaCompiler compiler = compiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StringWriter extra = new StringWriter();
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null,
                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                fm.getJavaFileObjectsFromFiles(List.of(moduleInfo.toFile(), consumer.toFile()));
            List<String> options = List.of(
                "--module-path", modulePath.toString(),
                "-d", out.toString());
            ok = compiler.getTask(extra, fm, diags, options, null, units).call();
        }

        if (!ok) {
            StringBuilder sb = new StringBuilder(
                "modular consumer FAILED to compile against the exported com.lattex surface — "
                + "the LTX-08 boundary defect is present. Diagnostics:\n");
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                sb.append("  ").append(d).append('\n');
            }
            sb.append(extra);
            fail(sb.toString());
        }
    }

    @Test
    void exportedApiLeaksNoNonExportedType() throws IOException {
        Path mainSrc = requiredPathProperty("lattex.mainSrc");

        List<java.io.File> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainSrc)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toFile()));
        }
        assertTrue(sources.size() > 1, "expected the full main source tree, found " + sources.size());

        JavaCompiler compiler = compiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        Path out;
        try {
            out = Files.createTempDirectory("lattex-exports-lint");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null,
                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
            // Zero runtime dependencies, so the module compiles with no classpath — the only
            // lint we enable is `exports`, isolating the API-boundary leak from any other warning.
            List<String> options = List.of("-Xlint:exports", "-d", out.toString());
            ok = compiler.getTask(null, fm, diags, options, null, units).call();
        }
        assertTrue(ok, "main sources did not compile under -Xlint:exports");

        List<String> exportsWarnings = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            String msg = d.getMessage(Locale.ROOT);
            if (d.getKind() == Diagnostic.Kind.WARNING && msg != null && msg.contains("is not exported")) {
                exportsWarnings.add(d.toString());
            }
        }
        assertEquals(List.of(), exportsWarnings,
            "exported API leaks a non-exported type (LTX-08 regression): " + exportsWarnings);
    }
}
