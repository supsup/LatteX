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

    /**
     * Total-cell cap for a single matrix/array/align grid (rows × columns). The
     * breadth analogue of {@link MathParser#MAX_DEPTH}: it bounds the rows×cols
     * blow-up that row-padding creates. Generous for real math (a 100×100 grid);
     * far below the ~4×10⁸ cells a 100 KB adversarial source could otherwise force.
     */
    static final int MAX_MATRIX_CELLS = 10_000;

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
        if (env.equals("CD")) {
            // amscd commutative diagrams have their own @-connector grammar (no & / column
            // spec), so they branch out before the ENVIRONMENTS spec lookup, exactly as the
            // eqnarray special-case does inside the spec path.
            return parseCd(parser);
        }
        EnvSpec spec = ENVIRONMENTS.get(env);
        if (spec == null) {
            String suggestion = FuzzyMatch.nearest(env, ENVIRONMENTS.keySet())
                .map(hit -> " — did you mean \\begin{" + hit + "}?")
                .orElse("");
            throw MathSyntaxException.unsupported(
                "Unknown environment: \\begin{" + env + "}" + suggestion,
                MathSyntaxException.NO_OFFSET);
        }

        // array carries a user column spec {ccc|c}; other envs are uniform.
        List<ColumnAlign> specAligns = null;
        List<Integer> specVlines = null;
        if (isEqnarray(env)) {
            // eqnarray is a FIXED 3-column right/center/left grid (LHS, relation, RHS)
            // with NO user column spec to read. Reuse ARRAY's machinery by synthesising
            // its spec here, keyed on the env name (checked before the ARRAY branch since
            // eqnarray registers as MatrixKind.ARRAY).
            specAligns = List.of(ColumnAlign.RIGHT, ColumnAlign.CENTER, ColumnAlign.LEFT);
            specVlines = List.of(0, 0, 0, 0);
        } else if (spec.kind() == MatrixKind.ARRAY) {
            ColumnSpec cs = readColumnSpec(parser);
            specAligns = cs.aligns();
            specVlines = cs.vlines();
        } else if (isAlignat(env)) {
            // alignat has a MANDATORY {n} column-pair count. Read and DISCARD it (mirrors
            // the ARRAY column-spec read above); LatteX's ALIGN path then derives the
            // alternating right/left columns from the & structure exactly like align.
            discardBraceArg(parser, "\\begin{" + env + "}");
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
            if (parser.isCommand(t, "nonumber") || parser.isCommand(t, "notag")) {
                // Equation-numbering suppressors: LatteX renders no equation numbers, so
                // these are inert no-ops (skipped here so an env body containing them parses).
                parser.next();
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

    // ------------------------------------------------------------------
    // amscd commutative diagrams — \begin{CD} … \end{CD}
    // ------------------------------------------------------------------

    /** One connector spec after {@code @}: the kind and its (already-parsed) labels. */
    private record CdConnector(MathNode.CdArrowKind kind, MathNode labelA, MathNode labelB) {
    }

    /** A parsed CD row: its cells in author order, and whether any cell is an object. */
    private record CdRow(List<Object> items, boolean hasObject) {
    }

    /**
     * Parses {@code \begin{CD} … \end{CD}} into a {@link Matrix} with
     * {@link MatrixKind#CD}. The current token is just past {@code \begin{CD}}.
     *
     * <p>amscd has no {@code &} and no column spec: a row is a run of object cells
     * and {@code @}-connectors. An OBJECT row alternates object / horizontal-connector
     * (placed at columns 0,1,2,…); a CONNECTOR row is only vertical connectors,
     * placed at the even (object) columns 0,2,4,… with the odd columns left empty so
     * they line up under the objects above and below. Fails loud (a positioned
     * {@link MathSyntaxException}) on a malformed {@code @}-construct or a missing
     * {@code \end{CD}}; never throws anything else.
     */
    private static MathNode parseCd(MathParser parser) {
        List<CdRow> rows = new ArrayList<>();
        List<Object> rowItems = new ArrayList<>();
        List<MathNode> objectCell = new ArrayList<>();
        boolean rowHasObject = false;

        while (true) {
            Token t = parser.peek();
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException("Unterminated \\begin{CD}: missing \\end{CD}");
            }
            if (parser.isCommand(t, "end")) {
                parser.next();
                String endEnv = readBraceName(parser, "\\end");
                if (!endEnv.equals("CD")) {
                    throw new MathSyntaxException("\\begin{CD} closed by \\end{" + endEnv + "}");
                }
                break;
            }
            if (parser.isCommand(t, "\\") || parser.isCommand(t, "cr")) {
                parser.next();
                skipRowBreakOptions(parser);
                if (!objectCell.isEmpty()) {
                    rowItems.add(MathParser.wrap(objectCell));
                    objectCell = new ArrayList<>();
                    rowHasObject = true;
                }
                rows.add(new CdRow(rowItems, rowHasObject));
                rowItems = new ArrayList<>();
                rowHasObject = false;
                continue;
            }
            if (t.kind() == Kind.CHAR && t.codePoint() == '@') {
                // An object cell ends where the connector begins.
                if (!objectCell.isEmpty()) {
                    rowItems.add(MathParser.wrap(objectCell));
                    objectCell = new ArrayList<>();
                    rowHasObject = true;
                }
                rowItems.add(readCdConnector(parser));
                continue;
            }
            objectCell.add(parser.parseComponent());
        }
        // Finalize a trailing row (content with no closing \\).
        if (!objectCell.isEmpty()) {
            rowItems.add(MathParser.wrap(objectCell));
            rowHasObject = true;
        }
        if (!rowItems.isEmpty()) {
            rows.add(new CdRow(rowItems, rowHasObject));
        }
        if (rows.isEmpty()) {
            throw new MathSyntaxException("empty \\begin{CD} environment");
        }
        return buildCdMatrix(rows);
    }

    /**
     * Reads one {@code @}-connector, the cursor sitting on the {@code @}. Consumes
     * the {@code @}, the type char, and (for arrows) the two label slots delimited by
     * repeats of the type char: {@code @D <labelA> D <labelB> D} where {@code D} is
     * {@code >}/{@code <}/{@code V}/{@code A}. The non-arrow forms {@code @=}, {@code @|},
     * {@code @.} take no labels.
     */
    private static CdConnector readCdConnector(MathParser parser) {
        int atOffset = parser.currentOffset();
        parser.next(); // consume '@'
        Token typeTok = parser.peek();
        if (typeTok.kind() != Kind.CHAR) {
            throw new MathSyntaxException(
                "expected a CD connector type (> < V A = | .) after '@'", atOffset);
        }
        int type = typeTok.codePoint();
        parser.next(); // consume the type char
        switch (type) {
            case '=' -> { return new CdConnector(MathNode.CdArrowKind.EQUAL, null, null); }
            case '|' -> { return new CdConnector(MathNode.CdArrowKind.VEQUAL, null, null); }
            case '.' -> { return new CdConnector(MathNode.CdArrowKind.EMPTY, null, null); }
            case '>' -> { return readCdArrow(parser, '>', MathNode.CdArrowKind.RIGHT, atOffset); }
            case '<' -> { return readCdArrow(parser, '<', MathNode.CdArrowKind.LEFT, atOffset); }
            case 'V' -> { return readCdArrow(parser, 'V', MathNode.CdArrowKind.DOWN, atOffset); }
            case 'A' -> { return readCdArrow(parser, 'A', MathNode.CdArrowKind.UP, atOffset); }
            default -> throw new MathSyntaxException(
                "'" + new String(Character.toChars(type)) + "' is not a CD connector type"
                    + " (expected > < V A = | .)", atOffset);
        }
    }

    /**
     * Reads the two delimiter-separated label slots of an arrow connector whose type
     * char {@code d} was already consumed: {@code <labelA> d <labelB> d}. Each label
     * is a run of math components up to the next top-level {@code d} CHAR (a {@code d}
     * inside a braced group is part of the label, since {@link MathParser#parseComponent}
     * consumes the whole group). An empty slot yields a {@code null} label.
     */
    private static CdConnector readCdArrow(MathParser parser, char d, MathNode.CdArrowKind kind,
                                           int atOffset) {
        MathNode labelA = readCdLabel(parser, d, atOffset);
        MathNode labelB = readCdLabel(parser, d, atOffset);
        return new CdConnector(kind, labelA, labelB);
    }

    /** One label slot up to (and consuming) the next top-level delimiter {@code d}. */
    private static MathNode readCdLabel(MathParser parser, char d, int atOffset) {
        List<MathNode> label = new ArrayList<>();
        while (true) {
            Token t = parser.peek();
            if (t.kind() == Kind.EOF || parser.isCommand(t, "end")) {
                throw new MathSyntaxException(
                    "unterminated CD connector: missing '" + d + "' delimiter", atOffset);
            }
            if (t.kind() == Kind.CHAR && t.codePoint() == d) {
                parser.next(); // consume the delimiter
                return label.isEmpty() ? null : MathParser.wrap(label);
            }
            label.add(parser.parseComponent());
        }
    }

    /**
     * Assembles parsed CD rows into a rectangular {@link Matrix} with the CD column
     * model (objects at even columns, horizontal connectors at odd columns; a
     * connector row's vertical connectors placed at the even columns).
     */
    private static MathNode buildCdMatrix(List<CdRow> rows) {
        int cols = 0;
        for (CdRow r : rows) {
            int rowCols = r.hasObject() ? r.items().size() : 2 * r.items().size() - 1;
            cols = Math.max(cols, rowCols);
        }
        cols = Math.max(cols, 1);
        long totalCells = (long) rows.size() * cols;
        if (totalCells > MAX_MATRIX_CELLS) {
            throw new MathSyntaxException("CD too large: " + rows.size() + " rows x " + cols
                + " cols = " + totalCells + " cells exceeds the " + MAX_MATRIX_CELLS + "-cell limit");
        }
        MathNode empty = new MathList(List.of());
        List<List<MathNode>> grid = new ArrayList<>(rows.size());
        for (CdRow r : rows) {
            List<MathNode> gridRow = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                gridRow.add(empty);
            }
            if (r.hasObject()) {
                for (int i = 0; i < r.items().size() && i < cols; i++) {
                    gridRow.set(i, asCell(r.items().get(i)));
                }
            } else {
                // connector row: place the k-th connector at even column 2k
                for (int k = 0; k < r.items().size(); k++) {
                    int c = 2 * k;
                    if (c < cols) {
                        gridRow.set(c, asCell(r.items().get(k)));
                    }
                }
            }
            grid.add(gridRow);
        }
        List<ColumnAlign> aligns = new ArrayList<>(cols);
        List<Integer> vlines = new ArrayList<>(cols + 1);
        for (int c = 0; c < cols; c++) {
            aligns.add(ColumnAlign.CENTER);
        }
        for (int c = 0; c <= cols; c++) {
            vlines.add(0);
        }
        List<RowRule> rowRules = new ArrayList<>(grid.size() + 1);
        for (int g = 0; g <= grid.size(); g++) {
            rowRules.add(RowRule.NONE);
        }
        return new Matrix(grid, aligns, vlines, rowRules,
            MathNode.Fenced.NULL_DELIMITER, MathNode.Fenced.NULL_DELIMITER, MatrixKind.CD);
    }

    /** An object item is already a MathNode; a connector item becomes a {@link MathNode.CdArrow}. */
    private static MathNode asCell(Object item) {
        if (item instanceof CdConnector cc) {
            return new MathNode.CdArrow(cc.kind(), cc.labelA(), cc.labelB());
        }
        return (MathNode) item;
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

        // DoS guard (breadth, not depth): cols is the widest row and EVERY row pads
        // out to it, so a pathological wide+tall grid materialises rows*cols cells
        // from O(rows+cols) source — MAX_DEPTH bounds nesting, nothing bounded breadth.
        // Adversary-reachable via the \lx author macro. Cap the total cell count so a
        // ~100 KB source can't force a multi-hundred-million-cell grid (OOM / long hang).
        long totalCells = (long) rawRows.size() * cols;
        if (totalCells > MAX_MATRIX_CELLS) {
            throw new MathSyntaxException("matrix too large: " + rawRows.size() + " rows x "
                + cols + " cols = " + totalCells + " cells exceeds the "
                + MAX_MATRIX_CELLS + "-cell limit");
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

    /** True for {@code eqnarray}/{@code eqnarray*} — the fixed right/center/left 3-column grid. */
    private static boolean isEqnarray(String env) {
        return env.equals("eqnarray") || env.equals("eqnarray*");
    }

    /** True for {@code alignat}/{@code alignat*} — align with a mandatory {@code {n}} argument. */
    private static boolean isAlignat(String env) {
        return env.equals("alignat") || env.equals("alignat*");
    }

    /**
     * Reads and DISCARDS a mandatory {@code {n}} brace argument (alignat's column-pair
     * count, which LatteX ignores since it derives columns from the {@code &} structure).
     * Fails loud on a missing or unterminated argument.
     */
    private static void discardBraceArg(MathParser parser, String context) {
        if (parser.peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                context + " requires a {n} column-pair count but found "
                    + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '{'
        while (parser.peek().kind() != Kind.RBRACE) {
            if (parser.peek().kind() == Kind.EOF) {
                throw new MathSyntaxException(context + " has an unterminated {n} argument");
            }
            parser.next();
        }
        parser.next(); // consume '}'
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
