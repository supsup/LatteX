package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exported-throwable boundary regression.
 *
 * <p>{@code module com.lattex} exports only {@code com.lattex.api}, but the exception the
 * public render methods throw — {@code com.lattex.parse.MathSyntaxException} — lives in a
 * NON-exported package. A modular consumer therefore could not name what it was documented
 * to catch, and its only fallback ({@link IllegalArgumentException}) is ALSO thrown for a
 * bad {@code fontSizePx}, so "your LaTeX is malformed" and "you passed a bad parameter"
 * were indistinguishable across the module boundary. {@link LatteXException} closes that:
 * {@code IllegalArgumentException → LatteXException → MathSyntaxException}.
 *
 * <p>A same-module unit test cannot prove any of this — JPMS does not enforce a module's
 * boundary against itself. So both checks here compile a GENUINE separate named module
 * against the built modular jar with the real {@code javac}, and the positive check then
 * loads that module in its own {@link ModuleLayer} and RUNS it:
 *
 * <ol>
 *   <li><b>{@link #modularConsumerCatchesRenderFailureViaExportedSupertype}</b> (POSITIVE) —
 *       a consumer whose only LatteX imports are {@code com.lattex.api.LatteX} and
 *       {@code com.lattex.api.LatteXException} compiles, catches a malformed-LaTeX failure
 *       through the exported supertype, and — the discriminator — sees a bad
 *       {@code fontSizePx} arrive as a plain {@code IllegalArgumentException} that is NOT a
 *       {@code LatteXException}.</li>
 *   <li><b>{@link #modularConsumerStillCannotNameTheNonExportedConcreteType}</b> (NEGATIVE) —
 *       a consumer naming {@code com.lattex.parse.MathSyntaxException} still FAILS to compile,
 *       specifically because that package is not visible. If this ever starts compiling the
 *       fence has been silently widened. A positive control in the same fixture (the same
 *       source with the offending lines stripped) must still compile, so the failure is
 *       attributable to the fence and not to an unrelated defect in the fixture.</li>
 * </ol>
 *
 * <p>The module under test is supplied by the {@code test} task in {@code build.gradle.kts}
 * as the system property {@code lattex.moduleJar} — the real modular jar, not the exploded
 * class dir, so the bundled font resource is present and the consumer can actually render.
 */
class ModularBoundaryTest {

    /** Marker comment on the lines of the negative fixture that name the non-exported type. */
    private static final String FENCE_PROBE = "// FENCE-PROBE";

    /** Outcome of one in-process javac invocation. */
    private record CompileResult(boolean ok, List<String> diagnostics, String extraOutput) {

        String report() {
            StringBuilder sb = new StringBuilder();
            diagnostics.forEach(d -> sb.append("  ").append(d).append('\n'));
            sb.append(extraOutput);
            return sb.toString();
        }

        boolean anyDiagnosticContainsAll(String... needles) {
            return diagnostics.stream().anyMatch(d -> {
                String lower = d.toLowerCase(Locale.ROOT);
                for (String needle : needles) {
                    if (!lower.contains(needle.toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                }
                return true;
            });
        }
    }

    private static Path moduleJar() {
        String v = System.getProperty("lattex.moduleJar");
        assertNotNull(v, "system property lattex.moduleJar not set by the test task");
        Path p = Path.of(v);
        assertTrue(Files.isRegularFile(p), "lattex.moduleJar does not exist: " + p);
        return p;
    }

    /** Copies a fixture source resource to {@code dst}, creating parent dirs. */
    private static Path copyResource(String resource, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try (InputStream in = ModularBoundaryTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource on the test classpath: " + resource);
            Files.write(dst, in.readAllBytes());
        }
        return dst;
    }

    /** Copies a fixture source resource, dropping every {@value #FENCE_PROBE} line. */
    private static Path copyResourceWithoutProbeLines(String resource, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        String text;
        try (InputStream in = ModularBoundaryTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource on the test classpath: " + resource);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (String line : text.split("\n", -1)) {
            if (line.contains(FENCE_PROBE)) {
                dropped++;
            } else {
                kept.add(line);
            }
        }
        // The control is worthless if the marker ever stops matching: a "stripped" copy that
        // is byte-identical to the original would silently turn the control into a duplicate
        // of the negative case.
        assertTrue(dropped > 0, "no " + FENCE_PROBE + " lines found in " + resource);
        Files.writeString(dst, String.join("\n", kept), StandardCharsets.UTF_8);
        return dst;
    }

    /** Runs the real javac over {@code sources} with {@code modulePath} on --module-path. */
    private static CompileResult compileModule(List<Path> sources, Path modulePath, Path out)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "no system Java compiler — run the test on a JDK, not a JRE");

        Files.createDirectories(out);
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StringWriter extra = new StringWriter();
        boolean ok;
        try (StandardJavaFileManager fm =
                compiler.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                fm.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList());
            List<String> options = List.of(
                "--module-path", modulePath.toString(),
                "-d", out.toString());
            ok = compiler.getTask(extra, fm, diags, options, null, units).call();
        }
        List<String> messages = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            messages.add(d.toString());
        }
        return new CompileResult(ok, messages, extra.toString());
    }

    // ---- POSITIVE ---------------------------------------------------------------

    @Test
    void modularConsumerCatchesRenderFailureViaExportedSupertype(@TempDir Path tmp)
            throws IOException, ReflectiveOperationException {
        Path modulePath = moduleJar();

        Path src = tmp.resolve("src");
        List<Path> sources = List.of(
            copyResource("/modularconsumer/module-info.java", src.resolve("module-info.java")),
            copyResource("/modularconsumer/com/lattexprobe/Consumer.java",
                src.resolve("com/lattexprobe/Consumer.java")));

        Path out = tmp.resolve("out");
        CompileResult result = compileModule(sources, modulePath, out);
        if (!result.ok()) {
            fail("the modular consumer FAILED to compile against the exported com.lattex "
                + "surface — a modular consumer cannot name the exception it must catch. "
                + "Diagnostics:\n" + result.report());
        }

        // Compiling is only half the claim: load the compiled module in its own layer and
        // RUN it, so "catches" is observed and not assumed.
        ModuleFinder finder = ModuleFinder.of(modulePath, out);
        Configuration cf = ModuleLayer.boot().configuration()
            .resolve(finder, ModuleFinder.of(), Set.of("lattexprobe"));
        ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(
                cf, List.of(ModuleLayer.boot()), ClassLoader.getPlatformClassLoader())
            .layer();
        Class<?> consumer = layer.findLoader("lattexprobe").loadClass("com.lattexprobe.Consumer");

        // Sanity: the consumer really renders through the real pipeline (so a "CAUGHT"
        // below cannot be some unrelated always-failing path).
        String good = (String) invoke(consumer, "render", String.class, "x^2");
        assertTrue(good.startsWith("OK:<svg"),
            "modular consumer could not render valid LaTeX; got: " + truncate(good));

        // POSITIVE: malformed LaTeX is caught through the exported supertype.
        String caught = (String) invoke(consumer, "render", String.class, "\\frac{a}{");
        assertTrue(caught.startsWith("CAUGHT:"),
            "malformed LaTeX was not caught as com.lattex.api.LatteXException; got: "
                + truncate(caught));

        // DISCRIMINATOR: a bad parameter is a plain IllegalArgumentException, NOT a
        // LatteXException — the distinction a modular consumer previously could not make.
        String badSize = (String) invoke(consumer, "fragmentBadSize", double.class, Double.NaN);
        assertTrue(badSize.startsWith("CAUGHT-IAE:"),
            "a non-finite fontSizePx must surface as a plain IllegalArgumentException that is "
                + "NOT a LatteXException, so a modular consumer can tell bad-argument from "
                + "malformed-LaTeX; got: " + truncate(badSize));
    }

    // ---- MathStyle nameability (RenderOptions canonical constructor) -------------

    /**
     * The {@code MathStyle}-move regression: {@link RenderOptions} — exported — used to
     * have a record component of the NON-exported type {@code com.lattex.layout.MathStyle}.
     * A modular consumer could reach {@code RenderOptions} but could neither name what
     * {@code mathStyle()} returns nor call the canonical constructor for any argument
     * list. {@code MathStyle} now lives in {@code com.lattex.api}, so a consumer whose
     * only LatteX imports are exported {@code com.lattex.api} types compiles and can call
     * the seven-arg canonical constructor directly.
     */
    @Test
    void modularConsumerNamesMathStyleAndCallsRenderOptionsCanonicalConstructor(@TempDir Path tmp)
            throws IOException, ReflectiveOperationException {
        Path modulePath = moduleJar();

        Path src = tmp.resolve("src");
        List<Path> sources = List.of(
            copyResource("/modularconsumer/module-info.java", src.resolve("module-info.java")),
            copyResource("/modularconsumer/com/lattexprobe/Consumer.java",
                src.resolve("com/lattexprobe/Consumer.java")));

        Path out = tmp.resolve("out");
        CompileResult result = compileModule(sources, modulePath, out);
        if (!result.ok()) {
            fail("the modular consumer FAILED to compile against the exported com.lattex "
                + "surface — MathStyle must be nameable, and RenderOptions's canonical "
                + "constructor callable, from a module. Diagnostics:\n" + result.report());
        }

        ModuleFinder finder = ModuleFinder.of(modulePath, out);
        Configuration cf = ModuleLayer.boot().configuration()
            .resolve(finder, ModuleFinder.of(), Set.of("lattexprobe"));
        ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(
                cf, List.of(ModuleLayer.boot()), ClassLoader.getPlatformClassLoader())
            .layer();
        Class<?> consumer = layer.findLoader("lattexprobe").loadClass("com.lattexprobe.Consumer");

        String result2 = (String) invokeNoArgs(consumer, "canonicalRenderOptions");
        assertEquals("MATHSTYLE:TEXT", result2,
            "a modular consumer could not name com.lattex.api.MathStyle and call "
                + "RenderOptions's canonical constructor");
    }

    // ---- NEGATIVE (the fence) ----------------------------------------------------

    @Test
    void modularConsumerStillCannotNameTheNonExportedConcreteType(@TempDir Path tmp)
            throws IOException {
        Path modulePath = moduleJar();

        // (a) Verbatim: naming com.lattex.parse.MathSyntaxException must NOT compile.
        Path src = tmp.resolve("src");
        List<Path> sources = List.of(
            copyResource("/modularconsumernegative/module-info.java",
                src.resolve("module-info.java")),
            copyResource("/modularconsumernegative/com/lattexprobe/NegativeConsumer.java",
                src.resolve("com/lattexprobe/NegativeConsumer.java")));
        CompileResult fenced = compileModule(sources, modulePath, tmp.resolve("out"));

        assertFalse(fenced.ok(),
            "a modular consumer COMPILED while naming com.lattex.parse.MathSyntaxException — "
                + "the module fence has been widened and com.lattex.parse is now visible to "
                + "named modules. module-info must export com.lattex.api only.");
        assertTrue(fenced.anyDiagnosticContainsAll("com.lattex.parse", "not visible"),
            "the negative fixture failed to compile, but NOT with a 'package com.lattex.parse "
                + "is not visible' diagnostic — the failure is not attributable to the module "
                + "fence. Diagnostics:\n" + fenced.report());

        // (b) Positive control on the same instrument: the SAME source with only the
        // FENCE-PROBE lines removed must compile. Without this, (a) proves nothing — any
        // typo in the fixture would produce a passing "negative".
        Path controlSrc = tmp.resolve("control-src");
        List<Path> controlSources = List.of(
            copyResource("/modularconsumernegative/module-info.java",
                controlSrc.resolve("module-info.java")),
            copyResourceWithoutProbeLines(
                "/modularconsumernegative/com/lattexprobe/NegativeConsumer.java",
                controlSrc.resolve("com/lattexprobe/NegativeConsumer.java")));
        CompileResult control = compileModule(controlSources, modulePath, tmp.resolve("control-out"));

        assertTrue(control.ok(),
            "the negative fixture does not compile even WITHOUT the non-exported reference, so "
                + "the negative assertion above measures nothing. Diagnostics:\n"
                + control.report());
        assertEquals(List.of(), control.diagnostics().stream()
                .filter(d -> d.toLowerCase(Locale.ROOT).contains("not visible")).toList(),
            "the control compile still reports a visibility problem");
    }

    private static Object invoke(Class<?> type, String method, Class<?> paramType, Object arg)
            throws ReflectiveOperationException {
        try {
            return type.getMethod(method, paramType).invoke(null, arg);
        } catch (InvocationTargetException e) {
            throw new AssertionError(
                "the modular consumer threw out of " + method + " instead of handling it: "
                    + e.getCause(), e.getCause());
        }
    }

    private static Object invokeNoArgs(Class<?> type, String method)
            throws ReflectiveOperationException {
        try {
            return type.getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            throw new AssertionError(
                "the modular consumer threw out of " + method + " instead of returning: "
                    + e.getCause(), e.getCause());
        }
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
