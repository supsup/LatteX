package com.lattex.cli;

import com.lattex.api.LatteX;
import com.lattex.parse.MathSyntaxException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LatteX command-line entry point — a thin, dependency-free wrapper around
 * {@link com.lattex.api.LatteX} that reads a LaTeX math expression and writes a
 * self-contained SVG document to stdout (or a file via {@code -o}).
 *
 * <p>Usage:
 * <pre>{@code
 *   lattex '\frac{a}{b}'          # expression from argv
 *   echo '\frac{a}{b}' | lattex   # expression from stdin
 *   lattex -o out.svg 'x^2'       # write to a file
 * }</pre>
 *
 * <p>The binary shells out to nothing and reimplements no rendering: it delegates
 * entirely to the public API, so its SVG output is byte-identical to the library's
 * and stays inside the minimal {@code svg/g/path/rect} alphabet.
 *
 * <p>This class is deliberately reflection-free and allocation-light so it builds
 * cleanly as a GraalVM native image.
 */
public final class Main {

    /** Kept in step with the library artifact version. */
    private static final String VERSION = "0.1.0";

    private static final String USAGE = """
        lattex — render LaTeX math to a self-contained SVG (LatteX %s)

        USAGE:
            lattex [OPTIONS] [EXPRESSION]
            echo EXPRESSION | lattex [OPTIONS]

        The math EXPRESSION is taken from the (space-joined) positional
        arguments, or — when none are given — read from standard input.
        The SVG document is written to standard output, or to a file with -o.

        OPTIONS:
            -o, --output <FILE>   Write the SVG to FILE instead of stdout.
            -h, --help            Show this help and exit.
            -V, --version         Print the version and exit.
            --                    Treat all following arguments as the
                                  expression (even if they start with '-').

        EXAMPLES:
            lattex '\\frac{a}{b}'
            lattex 'x^2 + y^2 = z^2' -o pythagoras.svg
            printf '\\sqrt{2}' | lattex

        EXIT STATUS:
            0  success
            1  render/IO error (invalid LaTeX, unwritable output, …)
            2  usage error (unknown flag, missing argument, …)
        """.formatted(VERSION);

    private Main() {
    }

    /** Process entry point; delegates to {@link #run} and maps the result to an exit code. */
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Runs the CLI with explicit streams (testable). Never calls {@link System#exit}.
     *
     * @param args the raw command-line arguments
     * @param out  the stream for SVG output / help / version
     * @param err  the stream for diagnostics
     * @return the process exit code (0 ok, 1 render/IO error, 2 usage error)
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path outputFile = null;
        StringBuilder expr = new StringBuilder();
        boolean sawExpr = false;
        boolean optionsEnded = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!optionsEnded && arg.startsWith("-") && !arg.equals("-")) {
                switch (arg) {
                    case "-h", "--help" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    case "-V", "--version" -> {
                        out.println("lattex " + VERSION);
                        return 0;
                    }
                    case "-o", "--output" -> {
                        if (i + 1 >= args.length) {
                            err.println("lattex: error: " + arg + " requires a FILE argument");
                            return 2;
                        }
                        outputFile = Path.of(args[++i]);
                    }
                    case "--" -> optionsEnded = true;
                    default -> {
                        err.println("lattex: error: unknown option '" + arg + "'");
                        err.println("Try 'lattex --help' for more information.");
                        return 2;
                    }
                }
            } else {
                // A positional token (or a bare '-', or anything after '--'): part of the expression.
                if (sawExpr) {
                    expr.append(' ');
                }
                expr.append(arg);
                sawExpr = true;
            }
        }

        String latex;
        if (sawExpr) {
            latex = expr.toString();
        } else {
            // No expression on the command line — read it from stdin.
            try {
                latex = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                err.println("lattex: error: failed to read stdin: " + e.getMessage());
                return 1;
            }
            if (latex.isEmpty()) {
                err.println("lattex: error: no expression given (pass one as an argument or on stdin)");
                err.println("Try 'lattex --help' for more information.");
                return 2;
            }
        }

        String svg;
        try {
            svg = LatteX.render(latex);
        } catch (MathSyntaxException e) {
            err.println("lattex: error: invalid LaTeX: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            err.println("lattex: error: could not render expression: " + e.getMessage());
            return 1;
        }

        if (outputFile == null) {
            out.println(svg);
        } else {
            try {
                Files.writeString(outputFile, svg + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.println("lattex: error: could not write '" + outputFile + "': " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }
}
