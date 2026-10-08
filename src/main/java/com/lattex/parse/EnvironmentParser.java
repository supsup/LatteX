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
            throw MathSyntaxException.unknownEnvironment(
                "Unknown environment: \\begin{" + env + "}" + suggestion,
                MathSyntaxException.NO_OFFSET);
        }

        // array carries a user column spec {ccc|c} and subarray a single-letter {c}/{l};
        // eqnarray synthesises a fixed one. Every other env derives its columns from the body.
        List<ColumnAlign> specAligns = null;
        List<Integer> specVlines = null;
        List<MathNode.ColumnSeparator> specSeparators = null;
        if (isEqnarray(env)) {
            // eqnarray is a FIXED 3-column right/center/left grid (LHS, relation, RHS)
            // with NO user column spec to read. Reuse ARRAY's machinery by synthesising
            // its spec here, keyed on the env name (checked before the ARRAY branch since
            // eqnarray registers as MatrixKind.ARRAY).
            specAligns = List.of(ColumnAlign.RIGHT, ColumnAlign.CENTER, ColumnAlign.LEFT);
            specVlines = List.of(0, 0, 0, 0);
        } else if (spec.kind() == MatrixKind.ARRAY) {
            // LaTeX array accepts an OPTIONAL [t]/[b]/[c] vertical-position argument
            // before its mandatory column spec. LatteX renders the grid standalone,
            // so consume the argument but do not carry it into the layout tree.
            readAndIgnorePositionArg(parser, env);
            ColumnSpec cs = readColumnSpec(parser);
            specAligns = cs.aligns();
            specVlines = cs.vlines();
            specSeparators = cs.separators();
        } else if (isSubarray(env)) {
            // subarray takes a MANDATORY single-letter {c}/{l} column spec. Like eqnarray
            // above, it hands buildMatrix a DECLARED column spec rather than letting the
            // column count be derived from the body — subarray is one column by definition,
            // so a stray '&' must fail loud rather than silently widening the stack.
            specAligns = List.of(readSubarrayColSpec(parser));
            specVlines = List.of(0, 0);
        } else if (isAlignat(env)) {
            // alignat has a MANDATORY {n} column-pair count. Read and DISCARD it (mirrors
            // the ARRAY column-spec read above); LatteX's ALIGN path then derives the
            // alternating right/left columns from the & structure exactly like align.
            discardBraceArg(parser, "\\begin{" + env + "}");
        } else if (env.equals("alignedat")) {
            // alignedat = aligned's optional [t]/[b]/[c] position, THEN alignat's
            // mandatory {n} (amsmath: \begin{alignedat}[pos]{n}). Both read, neither
            // rendered, for the reasons given on the branches above and below.
            readAndIgnorePositionArg(parser, env);
            discardBraceArg(parser, "\\begin{" + env + "}");
        } else if (takesPositionArg(env)) {
            // aligned/split take LaTeX's OPTIONAL [t]/[b]/[c] vertical-position argument.
            // Read and IGNORE it: LatteX renders the environment standalone (no
            // surrounding text baseline to align the box against), so the position has
            // no effect on output — but it must be PARSED, not served as row content
            // (0.11.0 silently rendered "[ t ] a" as math — plan 08eed9a5). Anything
            // other than t/b/c in the bracket fails loud, matching array's argument
            // discipline.
            readAndIgnorePositionArg(parser, env);
        }

        // Read the body: cells (& separated) into rows (\\ separated), tracking
        // \hline/\hdashline rules per inter-row gap (gap index = rows completed).
        List<List<MathNode>> rawRows = new ArrayList<>();
        Map<Integer, RowRule> hlines = new java.util.HashMap<>();
        List<MathNode> row = new ArrayList<>();
        List<MathNode> cell = new ArrayList<>();
        int pendingCover = 0;
        // Plan 636d214f: \multicolumn cells of this grid, and the \tag of each row of a
        // row-numbered display environment (align/gather/alignat). A \tag anywhere in such
        // a row, at any depth, reaches the sink; elsewhere it tags the equation.
        List<MathNode.CellSpan> spans = new ArrayList<>();
        Map<Integer, MathNode> rowTags = new java.util.HashMap<>();
        boolean rowTagged = ROW_TAGGED_ENVIRONMENTS.contains(env);
        java.util.function.Consumer<MathNode> previousSink = rowTagged
            ? parser.swapRowTagSink(label -> {
                if (rowTags.putIfAbsent(rawRows.size(), label) != null) {
                    throw new MathSyntaxException("Multiple \\tag on one row of \\begin{" + env + "}");
                }
            })
            : null;
        try {

        while (true) {
            Token t = parser.peek();
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException(
                    "Unterminated \\begin{" + env + "}: missing \\end{" + env + "}");
            }
            if (parser.isCommand(t, CommandRegistry.Handler.END)) {
                parser.next();
                String endEnv = readBraceName(parser, "\\end");
                if (!endEnv.equals(env)) {
                    throw new MathSyntaxException(
                        "\\begin{" + env + "} closed by \\end{" + endEnv + "}");
                }
                break;
            }
            if (parser.isCommand(t, CommandRegistry.Handler.ROW_RULE)) {
                RowRule rule = t.name().equals("hline") ? RowRule.SOLID : RowRule.DASHED;
                parser.next();
                hlines.merge(rawRows.size(), rule,
                    (a, b) -> a == RowRule.SOLID ? a : b); // a solid line wins
                continue;
            }
            if (parser.isCommand(t, CommandRegistry.Handler.EQUATION_SUPPRESSOR)
                    || parser.isCommand(t, CommandRegistry.Handler.QED_MARKER)) {
                // Equation-numbering suppressors: LatteX renders no equation numbers, so
                // these are inert no-ops (skipped here so an env body containing them parses).
                // \qedhere likewise: the proof's end mark belongs to the surrounding
                // document, not the formula (plan edbda088).
                parser.next();
                continue;
            }
            if (parser.isCommand(t, CommandRegistry.Handler.TAG)) {
                // At the cell level, consumed without leaving an empty item in the cell.
                parser.next();
                parser.acceptTag(parser.parseTagLabel());
                continue;
            }
            if (parser.isCommand(t, CommandRegistry.Handler.MULTICOLUMN)) {
                if (!cell.isEmpty()) {
                    throw new MathSyntaxException(
                        "\\multicolumn must open its cell (TeX: misplaced \\omit)", t.offset());
                }
                if (!MULTICOLUMN_KINDS.contains(spec.kind()) || isEqnarray(env)) {
                    throw new MathSyntaxException(
                        "\\multicolumn is not supported in \\begin{" + env + "}", t.offset());
                }
                parser.next();
                MathNode.CellSpan span = readMulticolumn(parser, rawRows.size(), row.size());
                spans.add(span);
                cell.add(parser.parseArgument("\\multicolumn body"));
                // The covered columns are empty cells; the span ends where its cell ends.
                pendingCover = span.span() - 1;
                continue;
            }
            if (parser.isCommand(t, CommandRegistry.Handler.ROW_SEPARATOR)) {
                int separatorEnd = t.offset() + 1 + t.name().length();
                parser.next();
                // an optional \\[len] / \\* is accepted and ignored
                skipRowBreakOptions(parser, separatorEnd, spacesBeforeOption(env));
                row.add(MathParser.wrap(cell));
                for (; pendingCover > 0; pendingCover--) {
                    row.add(new MathList(List.of()));
                }
                cell = new ArrayList<>();
                rawRows.add(row);
                row = new ArrayList<>();
                continue;
            }
            if (t.kind() == Kind.CHAR && t.codePoint() == '&') {
                parser.next();
                row.add(MathParser.wrap(cell));
                for (; pendingCover > 0; pendingCover--) {
                    row.add(new MathList(List.of()));
                }
                cell = new ArrayList<>();
                continue;
            }
            cell.add(parser.parseComponent());
        }
        // Finalize a trailing row (content with no closing \\). A bare trailing \\
        // (row + cell both empty) adds no phantom row, matching LaTeX — UNLESS that row
        // carries a \tag: amsmath's "a\\ \tag{1}" is a real numbered empty last row
        // (review lattex/1015 F1), so the tag keeps its row instead of pointing past
        // the grid.
        if (!cell.isEmpty() || !row.isEmpty() || rowTags.containsKey(rawRows.size())) {
            row.add(MathParser.wrap(cell));
            for (; pendingCover > 0; pendingCover--) {
                row.add(new MathList(List.of()));
            }
            rawRows.add(row);
        }
        } finally {
            if (rowTagged) {
                parser.swapRowTagSink(previousSink);
            }
        }
        if (rawRows.isEmpty()) {
            throw new MathSyntaxException("empty \\begin{" + env + "} environment");
        }

        // Report a NUMBERED display environment to the caller's sink, at the last moment the name
        // still exists: buildMatrix returns a Matrix carrying a MatrixKind and not a name, and the
        // starred twin maps to an identical spec, so after this line the two are indistinguishable.
        // No-op unless the caller asked (MathParser.parse's three-arg overload).
        if (Symbols.NUMBERED_ENVIRONMENTS.contains(env)) {
            parser.recordNumberedEnvironment(env);
        }
        MathNode grid = buildMatrix(env, spec, specAligns, specVlines, specSeparators, rawRows, hlines);
        if (spans.isEmpty() && rowTags.isEmpty()) {
            return grid;
        }
        Matrix m = (Matrix) grid;
        // The Matrix constructor re-checks that every span and row tag lies inside the
        // grid. The parser upholds that by construction; should a future parser decision
        // not, the author gets a typed refusal naming the environment, never a raw
        // IllegalArgumentException out of render/toMathML (review lattex/1015 F1).
        for (Integer r : rowTags.keySet()) {
            if (r >= m.rows().size()) {
                throw new MathSyntaxException("\\tag on row " + (r + 1) + " of \\begin{" + env
                    + "}, which has only " + m.rows().size() + " rows");
            }
        }
        try {
            return new Matrix(m.rows(), m.columnAligns(), m.columnRules(), m.rowRules(),
                m.leftDelim(), m.rightDelim(), m.kind(), m.columnSeparators(), spans, rowTags);
        } catch (IllegalArgumentException e) {
            throw new MathSyntaxException("\\begin{" + env + "}: " + e.getMessage());
        }
    }

    /**
     * The display environments amsmath numbers ROW BY ROW, so a {@code \tag} belongs to
     * its row (plan 636d214f). {@code equation}/{@code multline} carry one number for the
     * whole display and the inner forms ({@code aligned}, {@code gathered}, {@code split},
     * ...) none of their own, so there a {@code \tag} tags the equation.
     */
    private static final java.util.Set<String> ROW_TAGGED_ENVIRONMENTS = java.util.Set.of(
        "align", "align*", "gather", "gather*", "alignat", "alignat*");

    /** Grid kinds built on LaTeX's array, where {@code \multicolumn} is defined. */
    private static final java.util.Set<MatrixKind> MULTICOLUMN_KINDS =
        java.util.EnumSet.of(MatrixKind.ARRAY, MatrixKind.MATRIX, MatrixKind.SMALL, MatrixKind.CASES);

    /**
     * Whether a SPACE may separate {@code \\} from its {@code [len]} option. LaTeX's own
     * {@code array} and {@code eqnarray} read the option with {@code \@ifnextchar},
     * which skips spaces; every amsmath environment (and the matrices and cases amsmath
     * builds) uses {@code \new@ifnextchar}, which does NOT, precisely so that a row may
     * begin with a bracket: {@code \\ [B]_2} is content there. Plan 636d214f (the
     * corpus's "no nucleus" bucket).
     */
    private static boolean spacesBeforeOption(String env) {
        return env.equals("array") || isEqnarray(env);
    }

    /**
     * Reads {@code {n}{spec}} of a {@code \multicolumn} (the command consumed; its body is
     * read by the caller): a positive column count and a ONE-column spec, one of
     * {@code l c r} with optional {@code |} rules before and after it.
     */
    private static MathNode.CellSpan readMulticolumn(MathParser parser, int row, int column) {
        String count = parser.readRawArgument("\\multicolumn column count").strip();
        if (!count.matches("[0-9]{1,4}") || Integer.parseInt(count) < 1) {
            throw new MathSyntaxException(
                "\\multicolumn column count must be a positive integer, but found '" + count + "'");
        }
        String colSpec = parser.readRawArgument("\\multicolumn column spec").replace(" ", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\|*)([lcr])(\\|*)")
            .matcher(colSpec);
        if (!m.matches()) {
            throw new MathSyntaxException("\\multicolumn spec must be one column (l, c or r with"
                + " optional | rules), but found '" + colSpec + "'");
        }
        ColumnAlign align = switch (m.group(2)) {
            case "l" -> ColumnAlign.LEFT;
            case "r" -> ColumnAlign.RIGHT;
            default -> ColumnAlign.CENTER;
        };
        return new MathNode.CellSpan(row, column, Integer.parseInt(count), align,
            m.group(1).length(), m.group(3).length());
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
            if (parser.isCommand(t, CommandRegistry.Handler.END)) {
                parser.next();
                String endEnv = readBraceName(parser, "\\end");
                if (!endEnv.equals("CD")) {
                    throw new MathSyntaxException("\\begin{CD} closed by \\end{" + endEnv + "}");
                }
                break;
            }
            if (parser.isCommand(t, CommandRegistry.Handler.ROW_SEPARATOR)) {
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
            if (t.kind() == Kind.EOF || parser.isCommand(t, CommandRegistry.Handler.END)) {
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
                                        List<Integer> specVlines,
                                        List<MathNode.ColumnSeparator> specSeparators,
                                        List<List<MathNode>> rawRows,
                                        Map<Integer, RowRule> hlines) {
        int cols;
        List<ColumnAlign> aligns;
        List<Integer> vlines;
        // A DECLARED column spec (array's {lcr|}, eqnarray's synthesised three columns,
        // subarray's single {c}/{l}) fixes the grid width and makes an over-wide row an
        // error; every other environment derives its width from the widest body row.
        if (specAligns != null) {
            cols = specAligns.size();
            for (List<MathNode> r : rawRows) {
                if (r.size() > cols) {
                    throw new MathSyntaxException("\\begin{" + env + "} row has " + r.size()
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

        if (specSeparators == null) {
            return new Matrix(grid, aligns, vlines, rowRules,
                spec.leftDelim(), spec.rightDelim(), spec.kind());
        }
        return new Matrix(grid, aligns, vlines, rowRules,
            spec.leftDelim(), spec.rightDelim(), spec.kind(), specSeparators);
    }

    /** True for {@code eqnarray}/{@code eqnarray*} — the fixed right/center/left 3-column grid. */
    private static boolean isEqnarray(String env) {
        return env.equals("eqnarray") || env.equals("eqnarray*");
    }

    /**
     * True for the environments that take amsmath's optional {@code [t]}/{@code [b]}/
     * {@code [c]} vertical-position argument: the inner {@code aligned}/{@code split}
     * forms. The display forms ({@code align}/{@code gather}/…) take no such argument
     * in LaTeX and get none here.
     */
    private static boolean takesPositionArg(String env) {
        // gathered and multlined are inner forms too (plan edbda088); alignedat reads its
        // position on its own branch, ahead of its mandatory {n}.
        return env.equals("aligned") || env.equals("split")
            || env.equals("gathered") || env.equals("multlined");
    }

    /**
     * Reads and IGNORES an optional {@code [t]}/{@code [b]}/{@code [c]}
     * vertical-position argument; a no-op when no {@code [} follows
     * {@code \begin{env}}. The position selects which row's baseline anchors the box
     * in surrounding text — LatteX renders the environment standalone, so it has no
     * visual effect. Every other bracket value fails loud rather than leaking into
     * the body or being mistaken for array's mandatory column spec.
     */
    private static void readAndIgnorePositionArg(MathParser parser, String env) {
        Token t = parser.peek();
        if (t.kind() != Kind.CHAR || t.codePoint() != '[') {
            return; // the argument is optional — no '[' means no argument
        }
        parser.next(); // consume '['
        Token pos = parser.peek();
        if (pos.kind() != Kind.CHAR
                || (pos.codePoint() != 't' && pos.codePoint() != 'b'
                    && pos.codePoint() != 'c')) {
            throw new MathSyntaxException("\\begin{" + env + "} position must be "
                + "[t], [b] or [c],"
                + " but found " + MathParser.describe(pos));
        }
        parser.next(); // consume the position letter
        Token close = parser.peek();
        if (close.kind() != Kind.CHAR || close.codePoint() != ']') {
            throw new MathSyntaxException("\\begin{" + env + "} position must be "
                + "[t], [b] or [c],"
                + " but found " + MathParser.describe(close));
        }
        parser.next(); // consume ']'
    }

    /** True for {@code alignat}/{@code alignat*} — align with a mandatory {@code {n}} argument. */
    private static boolean isAlignat(String env) {
        return env.equals("alignat") || env.equals("alignat*");
    }

    /**
     * True for {@code subarray} — amsmath's single-column stack, which takes a mandatory
     * one-letter {@code {c}}/{@code {l}} column spec instead of {@code array}'s general one.
     */
    private static boolean isSubarray(String env) {
        return env.equals("subarray");
    }

    /**
     * Reads {@code subarray}'s mandatory {@code {c}} or {@code {l}} column spec. Unlike
     * {@code array}'s multi-column {@code {lcr|}} ({@link #readColumnSpec}), amsmath's
     * {@code subarray} declares exactly ONE column and accepts only {@code c} or
     * {@code l} — no {@code r}, and no {@code |} rules, since it is always a bare
     * limit stack. A missing brace, an unsupported letter, or any extra character
     * fails loud, matching {@code array}'s argument discipline.
     */
    private static ColumnAlign readSubarrayColSpec(MathParser parser) {
        if (parser.peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\begin{subarray} requires a {c} or {l} column spec but found "
                    + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '{'
        Token t = parser.peek();
        if (t.kind() != Kind.CHAR) {
            throw new MathSyntaxException(
                "subarray column spec must be 'c' or 'l', but found " + MathParser.describe(t));
        }
        int cp = t.codePoint();
        ColumnAlign align = switch (cp) {
            case 'c' -> ColumnAlign.CENTER;
            case 'l' -> ColumnAlign.LEFT;
            default -> throw new MathSyntaxException(
                "unsupported subarray column type '" + new String(Character.toChars(cp))
                    + "' (only c and l are supported)");
        };
        parser.next(); // consume the column letter
        if (parser.peek().kind() != Kind.RBRACE) {
            throw new MathSyntaxException(
                "subarray column spec must be a single 'c' or 'l', but found "
                    + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '}'
        return align;
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
        skipRowBreakOptions(parser, -1, true);
    }

    /**
     * As {@link #skipRowBreakOptions(MathParser)}, but when {@code spacesAllowed} is false
     * a {@code [} separated from the {@code \\} (or its {@code *}) by source whitespace
     * is NOT an option: it is left as the next row's content. {@code end} is the source
     * offset just past the {@code \\}.
     */
    private static void skipRowBreakOptions(MathParser parser, int end, boolean spacesAllowed) {
        if (parser.peek().kind() == Kind.CHAR && parser.peek().codePoint() == '*') {
            end = parser.peek().offset() + 1;
            parser.next();
        }
        if (parser.peek().kind() == Kind.CHAR && parser.peek().codePoint() == '['
                && (spacesAllowed || !parser.onlyWhitespaceBetween(end, parser.peek().offset()))) {
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
     * Reads a {@code {name}} argument — the run of CHAR tokens between the braces
     * (the environment name after {@code \begin}/{@code \end}). The name is only ever
     * looked up in {@code ENVIRONMENTS} (or exact-matched for {@code CD}), never
     * emitted, so a non-environment name simply fails that lookup cleanly; a missing
     * opening/closing brace fails loud here.
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

    /**
     * The parsed {@code array} column spec: per-column alignment, boundary rules, and
     * the {@code @{...}}/{@code !{...}} material at each boundary (null where none).
     */
    private record ColumnSpec(List<ColumnAlign> aligns, List<Integer> vlines,
                              List<MathNode.ColumnSeparator> separators) {
    }

    /**
     * Reads an {@code array} column spec {@code {lcr|}}: {@code l}/{@code c}/{@code r}
     * declare left/centre/right columns and {@code |} adds a vertical rule at the
     * current boundary. {@code @{math}} puts its material at the current boundary IN
     * PLACE OF the intercolumn space (TeX's array semantics: {@code @{}} removes the
     * edge or intercolumn space); {@code !{math}} puts it there and keeps the space.
     * {@code vlines} and {@code separators} have one entry per {@code columns+1}
     * boundary. Two expressions, or an expression and a {@code |}, at ONE boundary
     * fail loud (LaTeX would concatenate them in spec order; LatteX carries one item
     * per boundary rather than guess at the order). Other column types ({@code p{}},
     * {@code *}, …) fail loud. The material is parsed by the ordinary parser, so it is
     * bounded by the same depth and size limits as any cell. Plan fc988bc4.
     */
    private static ColumnSpec readColumnSpec(MathParser parser) {
        if (parser.peek().kind() != Kind.LBRACE) {
            throw new MathSyntaxException(
                "\\begin{array} requires a {column spec} but found " + MathParser.describe(parser.peek()));
        }
        parser.next(); // consume '{'
        List<ColumnAlign> aligns = new ArrayList<>();
        List<Integer> vlines = new ArrayList<>();
        List<MathNode.ColumnSeparator> separators = new ArrayList<>();
        vlines.add(0); // boundary before the first column
        separators.add(null);
        while (parser.peek().kind() != Kind.RBRACE) {
            Token t = parser.peek();
            if (t.kind() == Kind.EOF) {
                throw new MathSyntaxException("unterminated array column spec");
            }
            if (t.kind() != Kind.CHAR) {
                throw new MathSyntaxException(
                    "array column spec must be l/c/r, '|', @{...} or !{...}, but found "
                        + MathParser.describe(t));
            }
            int cp = t.codePoint();
            int last = vlines.size() - 1;
            switch (cp) {
                case 'l' -> { aligns.add(ColumnAlign.LEFT); vlines.add(0); separators.add(null); }
                case 'c' -> { aligns.add(ColumnAlign.CENTER); vlines.add(0); separators.add(null); }
                case 'r' -> { aligns.add(ColumnAlign.RIGHT); vlines.add(0); separators.add(null); }
                case '|' -> {
                    if (separators.get(last) != null) {
                        throw sharedBoundary();
                    }
                    vlines.set(last, vlines.get(last) + 1);
                }
                case '@', '!' -> {
                    parser.next(); // consume '@' / '!'
                    if (parser.peek().kind() != Kind.LBRACE) {
                        throw new MathSyntaxException("array column spec: '"
                            + Character.toString(cp) + "' must be followed by a {...} group, but found "
                            + MathParser.describe(parser.peek()));
                    }
                    if (separators.get(last) != null) {
                        throw new MathSyntaxException("array column spec: two @{...}/!{...}"
                            + " expressions at one column boundary are not supported");
                    }
                    if (vlines.get(last) > 0) {
                        throw sharedBoundary();
                    }
                    MathNode material = parser.parseComponent(); // the {...} group
                    separators.set(last, new MathNode.ColumnSeparator(material, cp == '!'));
                    continue; // the group is consumed; do not consume another token
                }
                default -> throw new MathSyntaxException(
                    "unsupported array column type '" + new String(Character.toChars(cp))
                        + "' (only l, c, r, |, @{...} and !{...} are supported)");
            }
            parser.next();
        }
        parser.next(); // consume '}'
        if (aligns.isEmpty()) {
            throw new MathSyntaxException("array column spec must declare at least one column");
        }
        return new ColumnSpec(aligns, vlines, separators);
    }

    private static MathSyntaxException sharedBoundary() {
        return new MathSyntaxException("array column spec: a '|' rule and an @{...}/!{...}"
            + " expression at one column boundary are not supported");
    }
}
