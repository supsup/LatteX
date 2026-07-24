package com.lattex.cli;

import com.lattex.api.LatteX;
import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * The artifact version, read from {@code lattex-version.properties} — which the
     * build stamps from {@code project.version}. Single-sourced, so the CLI can never
     * drift from the build (it used to: a hand-synced constant read 0.2.1 against a
     * 0.5.0 artifact). Falls back to {@code "dev"} only if the resource is absent.
     */
    private static final String VERSION = readVersion();

    private static String readVersion() {
        try (java.io.InputStream in = Main.class.getResourceAsStream("/lattex-version.properties")) {
            if (in != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) {
                    return v.strip();
                }
            }
        } catch (java.io.IOException ignored) {
            // fall through to the dev fallback
        }
        return "dev";
    }

    private static final String USAGE = """
        lattex — render LaTeX math to a self-contained SVG (LatteX %s)

        USAGE:
            lattex [OPTIONS] [EXPRESSION]
            echo EXPRESSION | lattex [OPTIONS]

        The math EXPRESSION is taken from the (space-joined) positional
        arguments, or — when none are given — read from standard input.
        The SVG document is written to standard output, or to a file with -o.
        Stdin is read and capped incrementally (never buffered whole before a
        check): an expression over 100,000 characters fails loud. No new read is
        issued once the cap trips; the decoder's bounded read-ahead may already
        hold the remaining bytes when the stream is short, but it is never an
        unbounded read of the whole stream.

        OPTIONS:
            -o, --output <FILE>   Write the SVG to FILE instead of stdout.
            --batch               Render MANY expressions in one process: read
                                  and stream them from stdin (one per line) and
                                  write one NUL-terminated SVG record per input to
                                  stdout, in order, AS EACH IS PRODUCED — the batch
                                  is never buffered whole. A malformed expression
                                  yields a 'lattex: error: …' record and does not
                                  abort the batch; each record is also capped at
                                  100,000 characters (read incrementally, with
                                  read-ahead bounded to a small overshoot past the
                                  cap — never the whole stream), and an OVERSIZED record
                                  DOES abort the rest of the batch (its own error
                                  record is still emitted; everything already
                                  produced before it already reached stdout).
                                  There is no cap on how many records a batch may
                                  hold. Amortizes startup/spawn cost.
            -0, --null            In --batch, split stdin on NUL instead of
                                  newlines (for expressions containing newlines).
            --inline              Render in INLINE (text) style — smaller fractions
                                  and scripts, big-operator limits set to the side —
                                  for math that sits on a line of prose. The default
                                  is DISPLAY style (full size, its own block). Works
                                  standalone and in --batch.
            --scale <N>           Output size multiplier (default 1.0), folded into
                                  the vector geometry (crisp, not a CSS zoom). Bounded
                                  to [0.1, 20.0].
            --macro <NAME=BODY>   Define a user macro before parsing (repeatable) —
                                  the CLI face of RenderOptions.macros. NAME is ASCII
                                  letters (no backslash); BODY may use #1..#9 (arity
                                  is inferred). Additive-only: a built-in name is
                                  refused. Example:
                                  --macro 'R=\\mathbb{R}' --macro 'norm=\\lVert #1 \\rVert'
            --color <C>           Ink color: 'currentColor' (default — inherits text
                                  color) or a #rgb / #rrggbb hex literal.
            -h, --help            Show this help and exit.
            -V, --version         Print the version and exit.
            --                    Treat all following arguments as the
                                  expression (even if they start with '-').

        EXAMPLES:
            lattex '\\frac{a}{b}'
            lattex 'x^2 + y^2 = z^2' -o pythagoras.svg
            printf '\\sqrt{2}' | lattex
            lattex --inline '\\frac{a}{b}'                  # text-size, sits on a line
            printf 'x^2\\n\\frac a b\\n' | lattex --batch   # 2 NUL-delimited SVGs

        EXIT STATUS:
            0  success
            1  render/IO error (invalid LaTeX, unwritable output, …); in --batch,
               at least one record failed — malformed records are still all
               emitted, but an oversized (>100,000-char) record ends the batch
            2  usage error (unknown flag, missing argument, …)
        """.formatted(VERSION);

    private Main() {
    }

    /** Process entry point; delegates to {@link #run} and maps the result to an exit code. */
    public static void main(String[] args) {
        int code = run(args, System.in, System.out, System.err);
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
        return run(args, System.in, out, err);
    }

    /**
     * Runs the CLI with explicit input + output streams (testable). Never calls
     * {@link System#exit}. In {@code --batch} mode, reads many expressions from
     * {@code in} and writes one NUL-delimited SVG (or error) record per input.
     *
     * <p><strong>Stream ownership.</strong> When {@code in} is consumed (stdin mode or
     * {@code --batch}), this method takes ownership of it and CLOSES it before returning
     * (the streaming {@link DelimitedRecordReader} is managed with try-with-resources,
     * and closing the reader closes the underlying stream). This differs from the
     * pre-streaming implementation, which read {@code in} via {@code readAllBytes()}
     * without closing it. Callers that must keep {@code in} open after {@code run}
     * returns should pass a close-shielded wrapper. {@code out} and {@code err} are NOT
     * closed. In production {@code in} is {@link System#in}, for which closing is benign.
     *
     * @param args the raw command-line arguments
     * @param in   the input stream read when no positional expression is given / in batch mode
     * @param out  the stream for SVG output / help / version
     * @param err  the stream for diagnostics
     * @return the process exit code (0 ok, 1 render/IO error, 2 usage error)
     */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Path outputFile = null;
        StringBuilder expr = new StringBuilder();
        boolean sawExpr = false;
        boolean optionsEnded = false;
        boolean batch = false;
        boolean nullDelim = false;
        boolean inline = false;
        Double scale = null;
        com.lattex.api.Color color = null;
        java.util.Map<String, String> macros = new java.util.LinkedHashMap<>();

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
                    case "--batch" -> batch = true;
                    case "-0", "--null" -> nullDelim = true;
                    case "--inline" -> inline = true;
                    case "--scale" -> {
                        if (i + 1 >= args.length) {
                            err.println("lattex: error: --scale requires a NUMBER argument");
                            return 2;
                        }
                        try {
                            scale = com.lattex.api.RenderOptions.parseScale(args[++i]);
                        } catch (IllegalArgumentException e) {
                            err.println("lattex: error: " + e.getMessage());
                            return 2;
                        }
                    }
                    case "--color" -> {
                        if (i + 1 >= args.length) {
                            err.println("lattex: error: --color requires a COLOR argument");
                            return 2;
                        }
                        try {
                            color = com.lattex.api.RenderOptions.parseColor(args[++i]);
                        } catch (IllegalArgumentException e) {
                            err.println("lattex: error: " + e.getMessage());
                            return 2;
                        }
                    }
                    case "--macro" -> {
                        // L8: --macro name=body, repeatable. Validation (letters-only
                        // name, additive-only vs built-ins, body lexes) happens at
                        // parse time with a positioned message; here we only need the
                        // name=body shape.
                        if (i + 1 >= args.length) {
                            err.println("lattex: error: --macro requires a NAME=BODY argument");
                            return 2;
                        }
                        String def = args[++i];
                        int eq = def.indexOf('=');
                        if (eq <= 0) {
                            err.println("lattex: error: --macro expects NAME=BODY, got '" + def + "'");
                            return 2;
                        }
                        macros.put(def.substring(0, eq), def.substring(eq + 1));
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

        // Fold the render flags into a single validated options object; --inline is
        // TEXT math style, and --scale/--color ride the same api-only parsers. A
        // top-level \lx in the source still overrides these (existing render() rule).
        com.lattex.api.RenderOptions opts = com.lattex.api.RenderOptions.defaults();
        if (inline) {
            opts = opts.inline();
        }
        if (scale != null) {
            opts = opts.withScale(scale);
        }
        if (color != null) {
            opts = opts.withColor(color);
        }
        if (!macros.isEmpty()) {
            opts = opts.withMacros(macros);
        }

        if (batch) {
            return runBatch(in, out, err, outputFile, sawExpr, nullDelim, opts);
        }

        String latex;
        if (sawExpr) {
            latex = expr.toString();
        } else {
            // No expression on the command line — read it from stdin. Streamed
            // (LTX-09, plan ac28238e): the reader enforces MathParser's own
            // MAX_SOURCE_LENGTH cap WHILE reading, so an adversarial/huge stdin
            // fails loud without ever buffering more than a small overshoot past
            // the cap — no more readAllBytes()-then-check on the raw stream.
            try (DelimitedRecordReader reader =
                     new DelimitedRecordReader(in, DelimitedRecordReader.NO_DELIMITER,
                         MathParser.MAX_SOURCE_LENGTH)) {
                // The reader caps the RAW decoded length; the strip() below runs only
                // AFTER it returns. So a whitespace-padded record that crosses the cap
                // is rejected before we ever trim it — the cap is fail-closed on raw
                // length, not on the post-strip content (see DelimitedRecordReader).
                latex = reader.next().strip();
            } catch (DelimitedRecordReader.TooLongException e) {
                err.println("lattex: error: " + e.getMessage());
                return 1;
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
            svg = LatteX.render(latex, opts);
        } catch (MathSyntaxException e) {
            // caretString() points a '^' at the offending column when the position is
            // known; it falls back to just the message otherwise.
            err.println("lattex: error: invalid LaTeX:");
            for (String line : e.caretString().split("\n", -1)) {
                err.println("  " + line);
            }
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

    /**
     * Batch mode: render many expressions in ONE process (amortizing JVM/native startup and
     * process-spawn cost — the point of a jar-vs-binary comparison and of rendering a page's many
     * math spans efficiently). Reads {@code in} split into records — one expression per line, or
     * NUL-separated when {@code nullDelim} — and writes one NUL-TERMINATED record per input to
     * {@code out}, IN ORDER: the SVG on success, or a {@code lattex: error: …} line on failure.
     * A single bad expression is isolated (its error record is emitted) and never aborts the batch;
     * the exit code is 1 if any record failed, else 0. Blank records are skipped.
     *
     * <p><strong>Streaming (LTX-09, plan ac28238e).</strong> Records are read and rendered
     * ONE AT A TIME via {@link DelimitedRecordReader}, and each record's output is written
     * and flushed to {@code out} before the next record is even read — the whole input is
     * never buffered, and results are never accumulated before flushing. Each record is
     * capped at {@link MathParser#MAX_SOURCE_LENGTH} chars, enforced DURING its read (the
     * same cap the parser itself applies, just moved earlier so it bounds the READ, not just
     * the parse).
     *
     * <p><strong>Aggregate cap: none, by design.</strong> There is no cap on the total
     * number of records or total bytes across a batch — only on each record individually.
     * This is safe because nothing is accumulated across records: peak memory is
     * O(one record + its rendered output), not O(input size), so record COUNT does not
     * threaten memory the way an unbounded single read did. An unbounded batch is a CPU/time
     * concern for the caller (it runs until stdin closes), not a memory one, and the caller
     * can always kill the process or close the pipe. Do not add a total-record/byte cap
     * without also updating this note and the CLI docs.
     *
     * <p><strong>An oversized record aborts the rest of the batch.</strong> When a record
     * exceeds the per-record cap, {@link DelimitedRecordReader} has already stopped reading
     * it (and hasn't located its end) — locating the next record's start would require
     * reading past the cap we just enforced, which is exactly the unbounded read this whole
     * change exists to prevent. So an oversized record's error is emitted and the batch stops
     * there (exit 1); every record already produced before it has already been flushed to
     * {@code out}. This is the one place batch's "isolate a bad record, keep going" promise
     * narrows to "isolate a bad record, then stop, once bad means unreadable" — previously a
     * too-long record still isolated-and-continued because the whole input was already
     * in memory as a split array; that same case now only reaches memory safety by giving up
     * the ability to skip past it.
     */
    private static int runBatch(InputStream in, PrintStream out, PrintStream err,
                                Path outputFile, boolean sawExpr, boolean nullDelim,
                                com.lattex.api.RenderOptions opts) {
        if (outputFile != null) {
            err.println("lattex: error: -o/--output cannot be combined with --batch"
                + " (batch writes NUL-delimited records to stdout)");
            return 2;
        }
        if (sawExpr) {
            err.println("lattex: error: --batch reads expressions from stdin;"
                + " do not also pass an expression argument");
            return 2;
        }
        boolean anyFailed = false;
        boolean anyEmitted = false;
        int delimiter = nullDelim ? DelimitedRecordReader.NUL : DelimitedRecordReader.NEWLINE;
        try (DelimitedRecordReader reader =
                 new DelimitedRecordReader(in, delimiter, MathParser.MAX_SOURCE_LENGTH)) {
            while (true) {
                String rec;
                try {
                    rec = reader.next();
                } catch (DelimitedRecordReader.TooLongException e) {
                    out.print("lattex: error: " + e.getMessage());
                    out.write(0); // NUL record terminator (SVGs never contain NUL)
                    out.flush();
                    anyFailed = true;
                    anyEmitted = true;
                    break; // see "An oversized record aborts the rest of the batch" above
                }
                if (rec == null) {
                    break; // stream exhausted; every record (including the trailing empty) is in
                }
                String latex = rec.strip();
                if (latex.isEmpty()) {
                    continue;
                }
                String record;
                try {
                    record = LatteX.render(latex, opts);
                } catch (MathSyntaxException e) {
                    record = "lattex: error: invalid LaTeX: " + e.getMessage();
                    anyFailed = true;
                } catch (RuntimeException e) {
                    record = "lattex: error: could not render expression: " + e.getMessage();
                    anyFailed = true;
                }
                out.print(record);
                out.write(0); // NUL record terminator (SVGs never contain NUL)
                out.flush(); // progressive: this record reaches the consumer before the next is read
                anyEmitted = true;
                if (out.checkError()) {
                    // The downstream consumer went away (e.g. a broken pipe): PrintStream
                    // swallows the write IOException and only raises this flag, so without
                    // the check we'd keep reading an unbounded stdin and rendering into a
                    // dead pipe. Stop loud instead of silently spinning.
                    err.println("lattex: error: failed writing batch output to stdout"
                        + " (downstream consumer closed the pipe?)");
                    return 1;
                }
            }
        } catch (IOException e) {
            err.println("lattex: error: failed to read stdin: " + e.getMessage());
            return 1;
        }
        if (!anyEmitted) {
            err.println("lattex: error: --batch got no expressions on stdin");
            return 2;
        }
        return anyFailed ? 1 : 0;
    }
}
