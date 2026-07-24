package com.lattex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the CLI plumbing in {@link Main} via its stream-injectable {@link Main#run}. */
final class MainTest {

    private record Result(int code, String out, String err) {
    }

    private static Result invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rendersExpressionFromArgvToSvg() {
        Result r = invoke("\\frac{a}{b}");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertEquals(com.lattex.api.LatteX.render("\\frac{a}{b}") + System.lineSeparator(), r.out(),
            "the new stdout check must not change successful one-shot bytes");
        assertTrue(r.out().startsWith("<svg"), "should emit an SVG document");
        assertTrue(r.out().contains("</svg>"), "SVG should be closed");
        assertTrue(r.out().contains("<path"), "a fraction should contain glyph paths");
    }

    @Test
    void joinsMultiplePositionalTokens() {
        Result r = invoke("x^2", "+", "y^2");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertTrue(r.out().startsWith("<svg"));
    }

    @Test
    void inlineFlagRendersTextStyleDifferentFromDisplay() {
        Result display = invoke("\\frac{a}{b}");
        Result inline = invoke("--inline", "\\frac{a}{b}");
        assertEquals(0, inline.code(), () -> "stderr: " + inline.err());
        assertTrue(inline.out().startsWith("<svg"), "inline still emits an SVG");
        assertTrue(inline.out().contains("<path"), "inline fraction still has glyph paths");
        assertNotEquals(display.out(), inline.out(),
            "--inline (text style) should differ from the default display render");
    }

    @Test
    void helpExitsZeroAndPrintsUsage() {
        Result r = invoke("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("USAGE"), "help should show usage");
    }

    @Test
    void versionExitsZero() {
        Result r = invoke("--version");
        assertEquals(0, r.code());
        // Single-sourced: --version must equal the build version (from
        // lattex-version.properties, stamped by Gradle from project.version). No
        // hardcoded version string here or in Main — so the CLI cannot drift.
        String expected = "lattex " + buildVersion();
        assertTrue(r.out().trim().equals(expected),
            "CLI --version must match the build artifact version: expected '"
                + expected + "' but got " + r.out());
    }

    private static String buildVersion() {
        try (java.io.InputStream in =
                 MainTest.class.getResourceAsStream("/lattex-version.properties")) {
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            String v = props.getProperty("version");
            assertTrue(v != null && !v.isBlank() && !v.contains("${"),
                "lattex-version.properties must be Gradle-expanded to a real version, got: " + v);
            return v.strip();
        } catch (java.io.IOException e) {
            throw new AssertionError("lattex-version.properties must be on the test classpath", e);
        }
    }

    @Test
    void unknownOptionIsUsageError() {
        Result r = invoke("--bogus");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("unknown option"));
    }

    @Test
    void missingOutputArgumentIsUsageError() {
        Result r = invoke("-o");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("requires a FILE"));
    }

    @Test
    void invalidLatexIsRenderError() {
        Result r = invoke("\\frac{a}"); // missing denominator
        assertEquals(1, r.code());
        assertTrue(r.err().contains("invalid LaTeX") || r.err().contains("could not render"));
    }

    @Test
    void doubleDashTreatsFollowingAsExpression() {
        Result r = invoke("--", "x^2");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertTrue(r.out().startsWith("<svg"));
    }

    @Test
    void writesToOutputFile(@TempDir Path dir) throws Exception {
        Path svg = dir.resolve("out.svg");
        Result r = invoke("-o", svg.toString(), "x^2");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertTrue(r.out().isEmpty(), "nothing should go to stdout when writing a file");
        String written = Files.readString(svg, StandardCharsets.UTF_8);
        assertTrue(written.startsWith("<svg") && written.contains("</svg>"), "file should hold the SVG");
    }

    @Test
    void closedStdoutMakesOneShotFailWithABoundedDiagnostic() {
        PrintStream closedOut =
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
        closedOut.close();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int code = Main.run(new String[] {"x^2"}, closedOut,
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        String diagnostic = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "silently lost SVG output must never exit successfully");
        assertEquals("lattex: error: failed to write output to stdout; output may be incomplete"
            + System.lineSeparator(), diagnostic);
        assertTrue(diagnostic.length() < 200, "stdout failure diagnostics must stay bounded");
    }

    @Test
    void closedProcessStdoutMakesTheRealJvmExitNonzero() throws Exception {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            Main.class.getName()).start();
        try {
            // Closing the parent's read end gives the child a real closed pipe, not only
            // an injected PrintStream seam. The child blocks reading its expression from
            // stdin until after that close, making the ordering deterministic.
            process.getInputStream().close();
            try (OutputStream childStdin = process.getOutputStream()) {
                childStdin.write("x^2".getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the child CLI should terminate");
            String diagnostic =
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, process.exitValue(),
                "Main.main must map the detected stdout failure to a nonzero process exit");
            assertEquals("lattex: error: failed to write output to stdout; output may be incomplete"
                + System.lineSeparator(), diagnostic);
            assertTrue(diagnostic.length() < 200, "process diagnostics must stay bounded");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void stdoutFlushFailureMakesOneShotFailWithoutEchoingTheException() {
        FlushFailingOutputStream failingOut = new FlushFailingOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int code = Main.run(new String[] {"x^2"},
            new PrintStream(failingOut, false, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        String diagnostic = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "a flush failure means the SVG was not delivered reliably");
        assertTrue(failingOut.bytes().startsWith("<svg"),
            "the injected failure must occur at flush, after the write path ran");
        assertEquals("lattex: error: failed to write output to stdout; output may be incomplete"
            + System.lineSeparator(), diagnostic);
        assertTrue(diagnostic.length() < 200,
            "the injected 10,000-character exception message must not reach stderr");
    }

    // --- batch mode (plan e25aa315) -----------------------------------------

    /** Invoke with a stdin payload via the stream-injectable 4-arg run. */
    private static Result invokeStdin(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        int code = Main.run(args, in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Split a NUL-terminated batch stdout into its records (drops the trailing empty). */
    private static String[] records(String out) {
        String[] parts = out.split("\0", -1);
        // A trailing NUL yields a final empty element — drop it.
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            return java.util.Arrays.copyOf(parts, parts.length - 1);
        }
        return parts;
    }

    @Test
    void batchRendersManyExpressionsInOrderOneProcess() {
        Result r = invokeStdin("x^2\n\\frac{a}{b}\n\\sqrt{2}\n", "--batch");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertEquals(com.lattex.api.LatteX.render("x^2") + "\0"
            + com.lattex.api.LatteX.render("\\frac{a}{b}") + "\0"
            + com.lattex.api.LatteX.render("\\sqrt{2}") + "\0", r.out(),
            "per-record flush checks must not change successful batch bytes");
        String[] recs = records(r.out());
        assertEquals(3, recs.length, "three inputs -> three records");
        for (String rec : recs) {
            assertTrue(rec.startsWith("<svg") && rec.contains("</svg>"), "each record is a complete SVG");
        }
        // Order preserved: the fraction (record 2) is the one with a fraction rule <rect>.
        assertTrue(recs[1].contains("<rect"), "the fraction renders in position 2");
    }

    @Test
    void batchSkipsBlankLinesAndTrailingSeparator() {
        Result r = invokeStdin("x^2\n\n\ny^2\n", "--batch");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertEquals(2, records(r.out()).length, "blank lines produce no records");
    }

    @Test
    void batchIsolatesAPerRecordErrorWithoutAbortingSiblings() {
        // Middle expression is malformed; the two valid siblings must still render.
        Result r = invokeStdin("x^2\n\\frac{a}\ny^2\n", "--batch");
        assertEquals(1, r.code(), "a failed record makes the batch exit 1");
        String[] recs = records(r.out());
        assertEquals(3, recs.length, "still one record per input");
        assertTrue(recs[0].startsWith("<svg"), "first sibling rendered");
        assertTrue(recs[1].startsWith("lattex: error:"), "the bad record is a marked error, in place");
        assertTrue(recs[2].startsWith("<svg"), "second sibling rendered despite the earlier failure");
    }

    @Test
    void batchStdoutFailureKeepsOnlyTheConfirmedPrefixAndFailsHonestly() {
        Result firstRecord = invokeStdin("x^2\n", "--batch");
        byte[] confirmedPrefix = firstRecord.out().getBytes(StandardCharsets.UTF_8);
        FailAfterBytesOutputStream failingOut =
            new FailAfterBytesOutputStream(confirmedPrefix.length);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        InputStream in =
            new ByteArrayInputStream("x^2\ny^2\nz^2\n".getBytes(StandardCharsets.UTF_8));

        int code = Main.run(new String[] {"--batch"}, in,
            new PrintStream(failingOut, false, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        String diagnostic = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "partial batch output must never exit successfully");
        assertEquals(firstRecord.out(), failingOut.bytes(),
            "the first checked record is a valid prefix; the failed and later records are absent");
        assertEquals("lattex: error: failed to write batch output to stdout after 1 complete record;"
            + " current record may be incomplete; no later records were emitted"
            + System.lineSeparator(), diagnostic);
        assertTrue(diagnostic.length() < 200,
            "the injected 10,000-character exception message must not reach stderr");
    }

    @Test
    void batchFlushFailureFailsBeforeClaimingTheRecordIsComplete() {
        FlushFailingOutputStream failingOut = new FlushFailingOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream("x^2\n".getBytes(StandardCharsets.UTF_8));

        int code = Main.run(new String[] {"--batch"}, in,
            new PrintStream(failingOut, false, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        String diagnostic = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "a failed record flush must fail the batch");
        assertTrue(failingOut.bytes().endsWith("\0"),
            "the injected failure must occur while flushing the fully written record");
        assertEquals("lattex: error: failed to write batch output to stdout after 0 complete records;"
            + " current record may be incomplete; no later records were emitted"
            + System.lineSeparator(), diagnostic);
        assertTrue(diagnostic.length() < 200,
            "the injected 10,000-character exception message must not reach stderr");
    }

    @Test
    void batchNullDelimiterSplitsOnNul() {
        Result r = invokeStdin("x^2\0\\frac{a}{b}", "--batch", "--null");
        assertEquals(0, r.code(), () -> "stderr: " + r.err());
        assertEquals(2, records(r.out()).length, "NUL-separated inputs -> two records");
    }

    @Test
    void batchRejectsOutputFile(@TempDir Path dir) {
        Result r = invokeStdin("x^2\n", "--batch", "-o", dir.resolve("x.svg").toString());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("cannot be combined with --batch"));
    }

    @Test
    void batchRejectsPositionalExpression() {
        Result r = invokeStdin("x^2\n", "--batch", "y^2");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("do not also pass an expression"));
    }

    @Test
    void batchWithEmptyStdinIsUsageError() {
        Result r = invokeStdin("   \n\n", "--batch");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("no expressions"));
    }

    @Test
    void scaleAndColorFlagsReachTheRender() {
        // --color stamps the ink fill.
        assertTrue(invoke("--color", "#ff0000", "x").out().contains("fill=\"#ff0000\""),
            "--color should set the ink fill");
        // --scale grows the vector geometry (crisp, not a CSS zoom).
        double plain = svgWidth(invoke("x^2").out());
        double scaled = svgWidth(invoke("--scale", "2", "x^2").out());
        assertTrue(scaled > plain * 1.5,
            "--scale 2 width " + scaled + " should clearly exceed plain " + plain);
        // Bad or missing values fail loud (exit 2), never a stack trace.
        assertEquals(2, invoke("--scale", "abc", "x").code());
        assertEquals(2, invoke("--color", "notacolor", "x").code());
        assertEquals(2, invoke("--scale").code());
    }

    private static double svgWidth(String svg) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("width=\"([0-9.]+)").matcher(svg);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
    }

    private static final class FlushFailingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        @Override
        public void write(int value) {
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            throw injectedFailure();
        }

        String bytes() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FailAfterBytesOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private int remaining;

        FailAfterBytesOutputStream(int permittedBytes) {
            remaining = permittedBytes;
        }

        @Override
        public void write(int value) throws IOException {
            if (remaining == 0) {
                throw injectedFailure();
            }
            delegate.write(value);
            remaining--;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            int accepted = Math.min(remaining, length);
            delegate.write(bytes, offset, accepted);
            remaining -= accepted;
            if (accepted != length) {
                throw injectedFailure();
            }
        }

        String bytes() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }

    private static IOException injectedFailure() {
        return new IOException("injected output failure: " + "x".repeat(10_000));
    }
}
