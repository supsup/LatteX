package com.lattex.parse;

import com.lattex.parse.MathNode.ColumnAlign;
import com.lattex.parse.MathParser.Kind;
import com.lattex.parse.MathParser.Token;
import com.lattex.parse.MathNode.MathList;
import com.lattex.parse.MathNode.Matrix;
import com.lattex.parse.MathNode.MatrixKind;
import com.lattex.parse.MathNode.RowRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.lattex.parse.Symbols.ENVIRONMENTS;
import com.lattex.parse.Symbols.EnvSpec;

/**
 * The {@code \begin{env}…\end{env}} grid parser (matrix family, array, cases),
 * split out of {@link MathParser} verbatim. {@code '&'} separates columns and
 * {@code '\\'} separates rows (TeXbook {@code \halign}); the grid is parsed into
 * a {@link Matrix} node and layout (S4) turns it into 2-D geometry.
 *
 * <p>This is a stateful sub-parser: it drives {@code MathParser}'s live token
 * cursor via the passed-in instance ({@code parser.peek()/next()/parseComponent()}
 * etc.). No cursor is duplicated — the single {@code MathParser} cursor is shared,
 * so parse behavior is identical.
 */
final class EnvironmentParser {

    private EnvironmentParser() {
    }

    /**
     * Parses a {@code \begin{env}…\end{env}} grid into a {@link Matrix}. The
     * current token is just past {@code \begin}. Fails loud on an unknown
     * environment, a mismatched {@code \end}, a ragged {@code array} row (more cells
     * than the column spec), or an unbalanced environment.
     */
    static MathNode parseEnvironment(MathParser parser) {
        String env = readBraceName(parser, "\\begin");
        EnvSpec spec = ENVIRONMENTS.get(env);
        if (spec == null) {
            throw new MathSyntaxException("Unknown environment: \\begin{" + env + "}");
        }

        // array carries a user column spec {ccc|c}; other envs are uniform.
        List<ColumnAlign> specAligns = null;
        List<Integer> specVlines = null;
        if (spec.kind() == MatrixKind.ARRAY) {
            ColumnSpec cs = readColumnSpec(parser);
            specAligns = cs.aligns();
            specVlines = cs.vlines();
        }

        // Read the body: cells (& separated) into rows (\\ separated), tracking
        // \hline/\hdashline rules per inter-row gap (gap index = rows completed).
        List<List<MathNode>> rawRows = new ArrayList<>();
        Map<Integer, RowRule> hlines = new java.util.HashMap<>();
        List<MathNode> row = new ArrayList<>();
        List<MathNode> cell = new ArrayList<>();

        while (true) {
            Token t = parser.peek();
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException(
                    "Unterminated \\begin{" + env + "}: missing \\end{" + env + "}");
            }
            if (parser.isCommand(t, "end")) {
                parser.next();
                String endEnv = readBraceName(parser, "\\end");
                if (!endEnv.equals(env)) {
                    throw new MathSyntaxException(
                        "\\begin{" + env + "} closed by \\end{" + endEnv + "}");
                }
                break;
            }
            if (parser.isCommand(t, "hline") || parser.isCommand(t, "hdashline")) {
                RowRule rule = t.name().equals("hline") ? RowRule.SOLID : RowRule.DASHED;
                parser.next();
                hlines.merge(rawRows.size(), rule,
                    (a, b) -> a == RowRule.SOLID ? a : b); // a solid line wins
                continue;
            }
            if (parser.isCommand(t, "\\") || parser.isCommand(t, "cr")) {
                parser.next();
                skipRowBreakOptions(parser); // an optional \\[len] / \\* is accepted and ignored
                row.add(MathParser.wrap(cell));
                cell = new ArrayList<>();
                rawRows.add(row);
                row = new ArrayList<>();
                continue;
            }
            if (t.kind() == Kind.CHAR && t.codePoint() == '&') {
                parser.next();
                row.add(MathParser.wrap(cell));
                cell = new ArrayList<>();
                continue;
            }
            cell.add(parser.parseComponent());
        }
        // Finalize a trailing row (content with no closing \\). A bare trailing \\
        // (row + cell both empty) adds no phantom row, matching LaTeX.
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(MathParser.wrap(cell));
            rawRows.add(row);
        }
        if (rawRows.isEmpty()) {
            throw new MathSyntaxException("empty \\begin{" + env + "} environment");
        }

        return buildMatrix(env, spec, specAligns, specVlines, rawRows, hlines);
    }

    /**
     * Assembles the parsed rows into a rectangular {@link Matrix}: determines the
     * column count, pads short rows with empty cells, builds the per-column
     * alignment + vertical-rule lists, and materialises the inter-row rule list.
     */
    static MathNode buildMatrix(String env, EnvSpec spec, List<ColumnAlign> specAligns,
                                        List<Integer> specVlines, List<List<MathNode>> rawRows,
                                        Map<Integer, RowRule> hlines) {
        int cols;
        List<ColumnAlign> aligns;
        List<Integer> vlines;
        if (spec.kind() == MatrixKind.ARRAY) {
            cols = specAligns.size();
            for (List<MathNode> r : rawRows) {
                if (r.size() > cols) {
                    throw new MathSyntaxException("array row has " + r.size()
                        + " cells but the column spec declares only " + cols);
                }
            }
            aligns = specAligns;
            vlines = specVlines;
        } else {
            cols = 0;
            for (List<MathNode> r : rawRows) {
                cols = Math.max(cols, r.size());
            }
            List<ColumnAlign> a = new ArrayList<>(cols);
            for (int i = 0; i < cols; i++) {
                if (spec.kind() == MatrixKind.ALIGN) {
                    // align/aligned: even (0-indexed) columns hold an equation LHS and
                    // are right-aligned; the following odd column holds the RHS and is
                    // left-aligned, so each &-separated pair meets at the alignment point.
                    a.add((i % 2 == 0) ? ColumnAlign.RIGHT : ColumnAlign.LEFT);
                } else {
                    a.add(spec.uniform());
                }
            }
            aligns = a;
            List<Integer> v = new ArrayList<>(cols + 1);
            for (int i = 0; i <= cols; i++) {
                v.add(0);
            }
            vlines = v;
        }

        // Pad short rows to the column count with empty cells (TeX pads with nulls).
        MathNode empty = new MathList(List.of());
        List<List<MathNode>> grid = new ArrayList<>(rawRows.size());
        for (List<MathNode> r : rawRows) {
            List<MathNode> padded = new ArrayList<>(r);
            while (padded.size() < cols) {
                padded.add(empty);
            }
            grid.add(padded);
        }

        List<RowRule> rowRules = new ArrayList<>(grid.size() + 1);
        for (int g = 0; g <= grid.size(); g++) {
            rowRules.add(hlines.getOrDefault(g, RowRule.NONE));
        }

        return new Matrix(grid, aligns, vlines, rowRules,
            spec.leftDelim(), spec.rightDelim(), spec.kind());
    }

    /** Silently consumes an optional {@code \\*} and/or {@code \\[len]} row-break modifier. */
    private static void skipRowBreakOptions(MathParser parser) {
        if (parser.peek().kind() == Kind.CHAR && parser.peek().codePoint() == '*') {
            parser.next();
        }
        if (parser.peek().kind() == Kind.CHAR && parser.peek().codePoint() == '[') {
            parser.next(); // consume '['
            while (parser.peek().kind() != Kind.EOF
                    && !(parser.peek().kind() == Kind.CHAR && parser.peek().codePoint() == ']')) {
                parser.next();
            }
            if (parser.peek().kind() == Kind.EOF) {
                throw new MathSyntaxException("unterminated \\\\[...] row-break length");
            }
            parser.next(); // consume ']'
        }
    }

    /**
     * Reads a {@code {name}} argument of plain ASCII-letter characters (the
     * environment name after {@code \begin}/{@code \end}). Rejects anything that is
     * not a run of letters, so a malformed {@code \begin{...}} fails cleanly.
     */
    private static String readBraceName(MathParser parser, String context) {
        if (parser.peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                context + " expects a '{name}' but found " + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '{'
        StringBuilder sb = new StringBuilder();
        while (parser.peek().kind() == Kind.CHAR) {
            sb.appendCodePoint(parser.peek().codePoint());
            parser.next();
        }
        if (parser.peek().kind() != Kind.RBRACE) {
            throw new MathSyntaxException(
                context + " environment name must be plain letters, but found " + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '}'
        if (sb.length() == 0) {
            throw new MathSyntaxException(context + " environment name must be non-empty");
        }
        return sb.toString();
    }

    /** The parsed {@code array} column spec: per-column alignment + boundary rules. */
    private record ColumnSpec(List<ColumnAlign> aligns, List<Integer> vlines) {
    }

    /**
     * Reads an {@code array} column spec {@code {lcr|}}: {@code l}/{@code c}/{@code r}
     * declare left/centre/right columns and {@code |} adds a vertical rule at the
     * current boundary. {@code vlines} has one count per {@code columns+1} boundary.
     * Unsupported column types ({@code p{}}, {@code @{}}, {@code *}, …) fail loud.
     */
    private static ColumnSpec readColumnSpec(MathParser parser) {
        if (parser.peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\begin{array} requires a {column spec} but found " + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '{'
        List<ColumnAlign> aligns = new ArrayList<>();
        List<Integer> vlines = new ArrayList<>();
        vlines.add(0); // boundary before the first column
        while (parser.peek().kind() != Kind.RBRACE) {
            Token t = parser.peek();
            if (t.kind() != Kind.CHAR) {
                throw new MathSyntaxException(
                    "array column spec must be l/c/r and '|', but found " + MathParser.describe(t));
            }
            int cp = t.codePoint();
            switch (cp) {
                case 'l' -> { aligns.add(ColumnAlign.LEFT); vlines.add(0); }
                case 'c' -> { aligns.add(ColumnAlign.CENTER); vlines.add(0); }
                case 'r' -> { aligns.add(ColumnAlign.RIGHT); vlines.add(0); }
                case '|' -> vlines.set(vlines.size() - 1, vlines.get(vlines.size() - 1) + 1);
                default -> throw new MathSyntaxException(
                    "unsupported array column type '" + new String(Character.toChars(cp))
                        + "' (only l, c, r and | are supported)");
            }
            parser.next();
        }
        parser.next(); // consume '}'
        if (aligns.isEmpty()) {
            throw new MathSyntaxException("array column spec must declare at least one column");
        }
        return new ColumnSpec(aligns, vlines);
    }
}
