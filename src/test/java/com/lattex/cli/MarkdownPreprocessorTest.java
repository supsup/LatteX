package com.lattex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MarkdownPreprocessorTest {

    @Test
    void documentTextReachesRendererOnlyThroughStdin(@TempDir Path directory) throws Exception {
        Path marker = directory.resolve("must not exist");
        String expression = "--leading \"quoted\"; `touch " + marker + "`; \\$(touch " + marker
            + ")\nnext";
        Path markdown = directory.resolve("post with spaces.md");
        Files.writeString(markdown, "before $" + expression + "$ after", StandardCharsets.UTF_8);

        Result result = runHelper(directory, markdown.toString());

        assertEquals(0, result.exit(), result.error());
        assertEquals("before " + rendered(expression) + " after", result.output());
        assertFalse(Files.exists(marker), "document text must never execute a side command");
        assertEquals("--", Files.readString(directory.resolve("renderer-args")),
            "only a constant option terminator may reach renderer argv");
    }

    @Test
    void displayAndUnmatchedDelimitersStayVerbatimWhileInlineMathRenders(@TempDir Path directory)
            throws Exception {
        Path markdown = directory.resolve("delimiters.md");
        String source = "keep $$x $y$ z$$, render $z$, keep $$cost $5$$, keep $unfinished";
        Files.writeString(markdown, source, StandardCharsets.UTF_8);

        Result result = runHelper(directory, markdown.toString());

        assertEquals(0, result.exit(), result.error());
        assertEquals("keep $$x $y$ z$$, render " + rendered("z")
            + ", keep $$cost $5$$, keep $unfinished", result.output());
    }

    @Test
    void unmatchedDisplayOpeningPreservesTheRemainder(@TempDir Path directory) throws Exception {
        Path markdown = directory.resolve("unmatched-display.md");
        String source = "before $$x $y$ and $unfinished";
        Files.writeString(markdown, source, StandardCharsets.UTF_8);

        Result result = runHelper(directory, markdown.toString());

        assertEquals(0, result.exit(), result.error());
        assertEquals(source, result.output());
    }

    @Test
    void ordinaryClosingDelimitersBeforePunctuationDoNotCoalesceSpans(@TempDir Path directory)
            throws Exception {
        Path markdown = directory.resolve("punctuation.md");
        Files.writeString(markdown, "render $x$(next) and $y${suffix}", StandardCharsets.UTF_8);

        Result result = runHelper(directory, markdown.toString());

        assertEquals(0, result.exit(), result.error());
        assertEquals("render " + rendered("x") + "(next) and " + rendered("y") + "{suffix}",
            result.output());
    }

    @Test
    void rendererFailureKeepsOnlyThatSourceSpanAndContinues(@TempDir Path directory)
            throws Exception {
        Path markdown = directory.resolve("failure.md");
        Files.writeString(markdown, "$first$ then $FAIL$ then $last$", StandardCharsets.UTF_8);

        Result result = runHelper(directory, markdown.toString());

        assertEquals(0, result.exit(), result.error());
        assertEquals(rendered("first") + " then $FAIL$ then " + rendered("last"),
            result.output());
        assertTrue(result.error().contains("renderer failed for inline span 2 (exit 9)"),
            result.error());
    }

    @Test
    void stdinModeUsesTheSameBoundary(@TempDir Path directory) throws Exception {
        Result result = runHelper(directory, "-", "stdin $x+y$");

        assertEquals(0, result.exit(), result.error());
        assertEquals("stdin " + rendered("x+y"), result.output());
    }

    @Test
    void rendererStdoutAndStderrAreDrainedTogether(@TempDir Path directory) throws Exception {
        Path markdown = directory.resolve("noisy.md");
        Files.writeString(markdown, "$NOISY$", StandardCharsets.UTF_8);

        Result result = runHelper(directory, markdown.toString());

        assertEquals(0, result.exit(), result.error());
        assertEquals(rendered("NOISY"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void helperContainsNoCommandStringExecutionSurface() throws IOException {
        String helper = Files.readString(Path.of("bin", "lattex-markdown"));

        assertFalse(helper.contains("`"), "backtick command execution is forbidden");
        assertFalse(helper.contains("eval"), "replacement or shell evaluation is forbidden");
        assertFalse(helper.contains("sh -c"), "shell command strings are forbidden");
        assertFalse(helper.contains("bash -c"), "shell command strings are forbidden");
        assertTrue(helper.contains(
            "open3($child_input, $child_output, $child_error, $command, '--')"));
        assertTrue(helper.contains("print {$child_input} $expression"));
    }

    @Test
    void slowstartUsesTheCheckedInHelperAndDropsTheExecutableRecipe() throws IOException {
        String slowstart = Files.readString(Path.of("SLOWSTART.md"));

        assertTrue(slowstart.contains("bin/lattex-markdown post.md > post.expanded.md"));
        assertTrue(slowstart.contains("never constructs a shell command from document text"));
        assertFalse(slowstart.contains("perl -pe"));
    }

    private static Result runHelper(Path directory, String source) throws Exception {
        return runHelper(directory, source, "");
    }

    private static Result runHelper(Path directory, String source, String stdin) throws Exception {
        Path renderer = directory.resolve("fake-lattex");
        Path args = directory.resolve("renderer-args");
        String script = """
            #!/usr/bin/env perl
            use strict;
            use warnings;
            open my $args, '>:raw', $ENV{RENDERER_ARGS} or die $!;
            print {$args} join("\\n", @ARGV);
            close $args;
            binmode STDIN;
            binmode STDOUT;
            local $/;
            my $expression = <STDIN> // '';
            exit 9 if $expression eq 'FAIL';
            print STDERR 'n' x 200_000 if $expression eq 'NOISY';
            print '<svg data-hex="' . unpack('H*', $expression) . '"/>';
            """;
        Files.writeString(renderer, script, StandardCharsets.UTF_8);
        assertTrue(renderer.toFile().setExecutable(true), "fake renderer must be executable");

        ProcessBuilder builder = new ProcessBuilder("perl", "bin/lattex-markdown", source);
        builder.environment().put("LATTEX_BIN", renderer.toString());
        builder.environment().put("RENDERER_ARGS", args.toString());
        Process process = builder.start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "preprocessor timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output, error);
    }

    private static String rendered(String expression) {
        return "<svg data-hex=\"" + HexFormat.of().formatHex(
            expression.getBytes(StandardCharsets.UTF_8)) + "\"/>";
    }

    private record Result(int exit, String output, String error) {
    }
}
