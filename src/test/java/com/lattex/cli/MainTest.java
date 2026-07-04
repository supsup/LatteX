package com.lattex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void helpExitsZeroAndPrintsUsage() {
        Result r = invoke("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("USAGE"), "help should show usage");
    }

    @Test
    void versionExitsZero() {
        Result r = invoke("--version");
        assertEquals(0, r.code());
        assertTrue(r.out().startsWith("lattex "));
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
}
