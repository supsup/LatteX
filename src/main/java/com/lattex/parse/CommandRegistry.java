package com.lattex.parse;

import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathParser.Category;
import com.lattex.parse.Symbols.AccentSpec;
import com.lattex.parse.Symbols.Sym;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The single authority for every control sequence LatteX accepts.
 *
 * <p>A descriptor owns the normalized name, index category/example, grammar
 * shape, parser/lexer handler identity, output role, and macro-reservation
 * policy. The parser still uses its established, focused parse routines; the
 * descriptor's {@link Handler} selects the routine, so adding a name to an
 * unrelated switch or suggestion list cannot make it accidentally supported.
 */
final class CommandRegistry {

    /** Where in the grammar a command is recognized. */
    enum GrammarKind {
        SYMBOL,
        PREFIX,
        ONE_ARGUMENT,
        TWO_ARGUMENTS,
        THREE_ARGUMENTS,
        OPTIONAL_THEN_ARGUMENT,
        SWITCH,
        DIMENSION,
        DELIMITER,
        INFIX,
        ENVIRONMENT,
        CONTEXTUAL,
        TEXT_ARGUMENT,
        DEFINITION,
        TOP_LEVEL
    }

    /**
     * Whether consuming the command itself contributes a node/layout effect, or
     * is control-only. A definition may affect a later invocation, for example,
     * while the definition token itself emits nothing.
     */
    enum OutputKind {
        RENDERING,
        NON_RENDERING
    }

    /**
     * The parser routine selected for a descriptor, together with that routine's
     * canonical grammar and output contract.
     *
     * <p>Grammar/output belong to the handler rather than each command row: aliases
     * cannot claim metadata that disagrees with the production routine that parses
     * them. Context gates still use the handler when they need a narrower semantic
     * role, while generic gates (infix, text arguments, definitions) consume the
     * derived grammar.
     */
    enum Handler {
        SYMBOL(GrammarKind.SYMBOL, OutputKind.RENDERING),
        BIG_OPERATOR(GrammarKind.SYMBOL, OutputKind.RENDERING),
        NAMED_OPERATOR(GrammarKind.SYMBOL, OutputKind.RENDERING),
        ACCENT(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        FONT_VARIANT(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        ATOM_CLASS(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        SPACE(GrammarKind.SYMBOL, OutputKind.RENDERING),
        FRACTION(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        CONTINUED_FRACTION(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        DISPLAY_FRACTION(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        TEXT_FRACTION(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        STYLE_SWITCH(GrammarKind.SWITCH, OutputKind.RENDERING),
        TEXT_COLOR(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        COLOR_SWITCH(GrammarKind.SWITCH, OutputKind.RENDERING),
        FONT_SWITCH(GrammarKind.SWITCH, OutputKind.RENDERING),
        BOXED(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        CANCEL(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        CANCEL_TO(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        BRA(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        KET(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        BRAKET(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        DIMENSION_SPACE(GrammarKind.DIMENSION, OutputKind.RENDERING),
        PRESCRIPT(GrammarKind.THREE_ARGUMENTS, OutputKind.RENDERING),
        BINOM(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        DISPLAY_BINOM(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        TEXT_BINOM(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        RADICAL(GrammarKind.OPTIONAL_THEN_ARGUMENT, OutputKind.RENDERING),
        OVERSET(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        UNDERSET(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        STACKREL(GrammarKind.TWO_ARGUMENTS, OutputKind.RENDERING),
        UNDERBRACE(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        OVERBRACE(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        X_ARROW(GrammarKind.OPTIONAL_THEN_ARGUMENT, OutputKind.RENDERING),
        SUBSTACK(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        PHANTOM(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        HPHANTOM(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        VPHANTOM(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        MATHSTRUT(GrammarKind.SYMBOL, OutputKind.RENDERING),
        LEFT(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        NOT(GrammarKind.PREFIX, OutputKind.RENDERING),
        BEGIN(GrammarKind.ENVIRONMENT, OutputKind.RENDERING),
        BORDER_MATRIX(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        END(GrammarKind.CONTEXTUAL, OutputKind.NON_RENDERING),
        ROW_RULE(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        RIGHT(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        LX(GrammarKind.TOP_LEVEL, OutputKind.RENDERING),
        LIMITS_MODIFIER(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        OPERATOR_NAME(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        BMOD(GrammarKind.SYMBOL, OutputKind.RENDERING),
        PMOD(GrammarKind.ONE_ARGUMENT, OutputKind.RENDERING),
        SIZED_DELIMITER(GrammarKind.DELIMITER, OutputKind.RENDERING),
        DELIMITER(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        EQUATION_SUPPRESSOR(GrammarKind.CONTEXTUAL, OutputKind.NON_RENDERING),
        LABEL(GrammarKind.ONE_ARGUMENT, OutputKind.NON_RENDERING),
        INFIX_FRACTION(GrammarKind.INFIX, OutputKind.RENDERING),
        TAG(GrammarKind.TOP_LEVEL, OutputKind.RENDERING),
        MIDDLE(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        ROW_SEPARATOR(GrammarKind.CONTEXTUAL, OutputKind.RENDERING),
        TEXT(GrammarKind.TEXT_ARGUMENT, OutputKind.RENDERING),
        DEFINITION(GrammarKind.DEFINITION, OutputKind.NON_RENDERING);

        private final GrammarKind grammarKind;
        private final OutputKind outputKind;

        Handler(GrammarKind grammarKind, OutputKind outputKind) {
            this.grammarKind = grammarKind;
            this.outputKind = outputKind;
        }

        GrammarKind grammarKind() {
            return grammarKind;
        }

        OutputKind outputKind() {
            return outputKind;
        }
    }

    /**
     * One normalized command descriptor.
     *
     * @param name normalized command name, without the leading backslash
     * @param category symbol-index category
     * @param handler parser/lexer dispatch identity
     * @param indexExample self-contained accepted input used by generated coverage
     * @param userMacroMayClaimName whether a user macro may claim this known name
     * @param delimiterCodePoint contextual delimiter value, when this command is
     *        valid after {@code \left}, {@code \right}, {@code \middle}, or a
     *        sized-delimiter command
     */
    record Descriptor(
            String name,
            Category category,
            Handler handler,
            String indexExample,
            boolean userMacroMayClaimName,
            OptionalInt delimiterCodePoint) {

        Descriptor {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("command name must be normalized and non-empty");
            }
            if (category == null || handler == null) {
                throw new IllegalArgumentException("command descriptor fields must not be null");
            }
            if (indexExample == null || indexExample.isEmpty()) {
                throw new IllegalArgumentException("command descriptor needs an index example: " + name);
            }
            if (delimiterCodePoint == null) {
                throw new IllegalArgumentException(
                    "command delimiter metadata must not be null: " + name);
            }
        }

        String displayName() {
            return "\\" + name;
        }

        GrammarKind grammarKind() {
            return handler.grammarKind();
        }

        OutputKind outputKind() {
            return handler.outputKind();
        }
    }

    private static final List<Descriptor> DESCRIPTORS = buildDescriptors();
    private static final Map<String, Descriptor> BY_NAME = indexByName(DESCRIPTORS);
    private static final Set<String> SUGGESTION_NAMES =
        Collections.unmodifiableSet(new java.util.TreeSet<>(BY_NAME.keySet()));

    private CommandRegistry() {
    }

    static Descriptor get(String name) {
        return BY_NAME.get(name);
    }

    static boolean hasHandler(String name, Handler handler) {
        Descriptor descriptor = BY_NAME.get(name);
        return descriptor != null && descriptor.handler() == handler;
    }

    static boolean hasGrammar(String name, GrammarKind grammarKind) {
        Descriptor descriptor = BY_NAME.get(name);
        return descriptor != null && descriptor.grammarKind() == grammarKind;
    }

    static boolean userMacroMayClaim(String name) {
        Descriptor descriptor = BY_NAME.get(name);
        return descriptor == null || descriptor.userMacroMayClaimName();
    }

    static OptionalInt delimiterCodePoint(String name) {
        Descriptor descriptor = BY_NAME.get(name);
        return descriptor == null ? OptionalInt.empty() : descriptor.delimiterCodePoint();
    }

    static List<Descriptor> descriptors() {
        return DESCRIPTORS;
    }

    static Set<String> suggestionNames() {
        return SUGGESTION_NAMES;
    }

    static Optional<String> nearestSuggestion(String name) {
        return FuzzyMatch.nearest(name, SUGGESTION_NAMES);
    }

    /**
     * Finds the nearest different registry name for a command that is known but
     * invalid in the current parser context. Excluding the exact name prevents a
     * misleading self-suggestion without discarding a useful alternative.
     */
    static Optional<String> nearestAlternative(String name) {
        return FuzzyMatch.nearest(name,
            SUGGESTION_NAMES.stream().filter(candidate -> !candidate.equals(name)).toList());
    }

    private static List<Descriptor> buildDescriptors() {
        Map<String, Descriptor> out = new LinkedHashMap<>();

        Symbols.SYMBOLS.forEach((name, symbol) ->
            add(out, name, categorize(symbol), Handler.SYMBOL,
                "\\" + name, delimiterCodePointFor(name)));
        Symbols.BIG_OPERATORS.forEach((name, symbol) ->
            add(out, name, Category.BIG_OPERATOR, Handler.BIG_OPERATOR,
                "\\" + name + "_{i=1}^{n}"));
        Symbols.NAMED_OPS.forEach((name, operator) ->
            add(out, name, Category.NAMED_OPERATOR, Handler.NAMED_OPERATOR,
                operator.takesLimits() ? "\\" + name + "_{x\\to0}" : "\\" + name + " x"));
        Symbols.ACCENTS.forEach((name, accent) ->
            add(out, name, Category.ACCENT, Handler.ACCENT,
                "\\" + name + accentBase(accent)));
        Symbols.FONT_VARIANTS.forEach((name, style) ->
            add(out, name, Category.FONT_VARIANT, Handler.FONT_VARIANT,
                (name.equals("boldsymbol") || name.equals("bm"))
                    ? "\\" + name + "{\\alpha\\beta\\gamma}"
                    : "\\" + name + "{RQZ}"));
        // Atom-class wrappers are indexed under SPACING: their entire observable
        // effect is the inter-atom glue the enclosing row inserts around them
        // (the glyphs are untouched), so the example puts the wrapper BETWEEN two
        // ordinary atoms where that glue is visible.
        Symbols.ATOM_CLASS_WRAPPERS.forEach((name, forcedClass) ->
            add(out, name, Category.SPACING, Handler.ATOM_CLASS,
                "x\\" + name + "{y}z"));
        Symbols.SPACES.forEach((name, mu) ->
            add(out, name, Category.SPACING, Handler.SPACE, "a\\" + name + " b"));
        Symbols.TEXT_COMMANDS.forEach((name, style) ->
            add(out, name, Category.TEXT, Handler.TEXT, "\\" + name + "{hello world}"));

        add(out, "frac", Category.STRUCTURE, Handler.FRACTION, "\\frac{a}{b}");
        add(out, "cfrac", Category.STRUCTURE, Handler.CONTINUED_FRACTION, "\\cfrac{1}{x}");
        add(out, "dfrac", Category.STRUCTURE, Handler.DISPLAY_FRACTION, "\\dfrac{a}{b}");
        add(out, "tfrac", Category.STRUCTURE, Handler.TEXT_FRACTION, "\\tfrac{a}{b}");
        for (String name : List.of(
                "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle")) {
            add(out, name, Category.STRUCTURE, Handler.STYLE_SWITCH, "\\" + name + " x");
        }
        // Legacy TeX 2.09 font switches. These are DECLARATIONS — {\bf x} restyles
        // everything from the switch to the end of the enclosing group — so their
        // grammar is SWITCH (the \displaystyle / \color shape), NOT the
        // ONE_ARGUMENT shape of their \mathbf/\mathit/\mathcal cousins. Modelling
        // them as argument-takers would silently change what {\bf x}y means.
        for (String name : List.of("rm", "bf", "it", "cal")) {
            add(out, name, Category.FONT_VARIANT, Handler.FONT_SWITCH,
                "{\\" + name + " x} y");
        }
        add(out, "textcolor", Category.STRUCTURE, Handler.TEXT_COLOR, "\\textcolor{red}{x}");
        add(out, "color", Category.STRUCTURE, Handler.COLOR_SWITCH, "{\\color{red}x}");
        for (String name : List.of("boxed", "fbox")) {
            add(out, name, Category.STRUCTURE, Handler.BOXED, "\\" + name + "{x}");
        }
        for (String name : List.of("cancel", "bcancel", "xcancel")) {
            add(out, name, Category.STRUCTURE, Handler.CANCEL, "\\" + name + "{x}");
        }
        add(out, "cancelto", Category.STRUCTURE, Handler.CANCEL_TO, "\\cancelto{0}{x}");
        add(out, "bra", Category.STRUCTURE, Handler.BRA, "\\bra{\\psi}");
        add(out, "ket", Category.STRUCTURE, Handler.KET, "\\ket{\\psi}");
        add(out, "braket", Category.STRUCTURE, Handler.BRAKET, "\\braket{a|b}");
        for (String name : List.of("hspace", "mkern", "kern", "mskip")) {
            add(out, name, Category.SPACING, Handler.DIMENSION_SPACE,
                "a\\" + name + "{9mu}b");
        }
        add(out, "prescript", Category.STRUCTURE, Handler.PRESCRIPT,
            "\\prescript{14}{6}{\\mathrm{C}}");
        add(out, "binom", Category.STRUCTURE, Handler.BINOM, "\\binom{n}{k}");
        add(out, "dbinom", Category.STRUCTURE, Handler.DISPLAY_BINOM, "\\dbinom{n}{k}");
        add(out, "tbinom", Category.STRUCTURE, Handler.TEXT_BINOM, "\\tbinom{n}{k}");
        add(out, "sqrt", Category.STRUCTURE, Handler.RADICAL, "\\sqrt[3]{x}");
        add(out, "overset", Category.STRUCTURE, Handler.OVERSET, "\\overset{!}{=}");
        add(out, "underset", Category.STRUCTURE, Handler.UNDERSET, "\\underset{i}{x}");
        add(out, "stackrel", Category.STRUCTURE, Handler.STACKREL, "\\stackrel{!}{=}");
        add(out, "underbrace", Category.STRUCTURE, Handler.UNDERBRACE,
            "\\underbrace{a+b}_{n}");
        add(out, "overbrace", Category.STRUCTURE, Handler.OVERBRACE, "\\overbrace{a+b}^{n}");
        for (String name : List.of(
                "xrightarrow", "xleftarrow", "xleftrightarrow", "xRightarrow",
                "xLeftarrow", "xLeftrightarrow", "xmapsto", "xhookrightarrow",
                "xhookleftarrow", "xrightleftharpoons", "xlongequal")) {
            add(out, name, Category.ARROW, Handler.X_ARROW, "\\" + name + "{f}");
        }
        add(out, "substack", Category.STRUCTURE, Handler.SUBSTACK,
            "\\sum_{\\substack{i<j\\\\j<n}}");
        add(out, "phantom", Category.STRUCTURE, Handler.PHANTOM, "a\\phantom{x}b");
        add(out, "hphantom", Category.STRUCTURE, Handler.HPHANTOM, "a\\hphantom{x}b");
        add(out, "vphantom", Category.STRUCTURE, Handler.VPHANTOM, "a\\vphantom{x}b");
        add(out, "mathstrut", Category.STRUCTURE, Handler.MATHSTRUT, "a\\mathstrut b");
        add(out, "left", Category.STRUCTURE, Handler.LEFT, "\\left(x\\right)");
        add(out, "right", Category.STRUCTURE, Handler.RIGHT, "\\left(x\\right)");
        add(out, "middle", Category.STRUCTURE, Handler.MIDDLE,
            "\\left(a\\middle|b\\right)");
        add(out, "not", Category.RELATION, Handler.NOT, "\\not=");
        add(out, "begin", Category.STRUCTURE, Handler.BEGIN,
            "\\begin{matrix}a&b\\\\c&d\\end{matrix}");
        add(out, "end", Category.CONTROL, Handler.END, "\\begin{matrix}x\\end{matrix}");
        add(out, "bordermatrix", Category.STRUCTURE, Handler.BORDER_MATRIX,
            "\\bordermatrix{&1&2\\\\1&a&b\\\\2&c&d}");
        add(out, "hline", Category.STRUCTURE, Handler.ROW_RULE,
            "\\begin{matrix}\\hline a\\end{matrix}");
        add(out, "hdashline", Category.STRUCTURE, Handler.ROW_RULE,
            "\\begin{matrix}\\hdashline a\\end{matrix}");
        add(out, "lx", Category.STRUCTURE, Handler.LX, "\\lx{x}");
        for (String name : List.of("limits", "nolimits")) {
            add(out, name, Category.CONTROL, Handler.LIMITS_MODIFIER,
                "\\sum\\" + name + "_{i=1}^{n}");
        }
        add(out, "operatorname", Category.NAMED_OPERATOR, Handler.OPERATOR_NAME,
            "\\operatorname{rank} A");
        add(out, "bmod", Category.NAMED_OPERATOR, Handler.BMOD, "a\\bmod b");
        add(out, "pmod", Category.NAMED_OPERATOR, Handler.PMOD, "a\\pmod{m}");

        for (String prefix : List.of("big", "Big", "bigg", "Bigg")) {
            for (String suffix : List.of("", "l", "r", "m")) {
                String name = prefix + suffix;
                String delimiter = suffix.equals("r") ? ")" : suffix.equals("m") ? "|" : "(";
                add(out, name, Category.ORDINARY, Handler.SIZED_DELIMITER,
                    "\\" + name + delimiter);
            }
        }
        add(out, "vert", Category.ORDINARY, Handler.DELIMITER,
            "\\left\\vert x\\right\\vert",
            delimiterCodePointFor("vert"));

        for (String name : List.of("nonumber", "notag")) {
            add(out, name, Category.CONTROL, Handler.EQUATION_SUPPRESSOR, "x\\" + name);
        }
        add(out, "label", Category.CONTROL, Handler.LABEL, "x\\label{eq:x}");
        add(out, "tag", Category.CONTROL, Handler.TAG, "x\\tag{1}");
        for (String name : List.of("over", "atop", "choose", "brace", "brack")) {
            add(out, name, Category.STRUCTURE, Handler.INFIX_FRACTION, "a\\" + name + " b");
        }
        add(out, "\\", Category.CONTROL, Handler.ROW_SEPARATOR,
            "\\begin{matrix}a\\\\b\\end{matrix}");
        add(out, "cr", Category.CONTROL, Handler.ROW_SEPARATOR,
            "\\begin{matrix}a\\cr b\\end{matrix}");

        add(out, "newcommand", Category.CONTROL, Handler.DEFINITION,
            "\\newcommand{\\fresh}{x}\\fresh");
        add(out, "renewcommand", Category.CONTROL, Handler.DEFINITION,
            "\\newcommand{\\fresh}{x}\\renewcommand{\\fresh}{y}\\fresh");
        add(out, "def", Category.CONTROL, Handler.DEFINITION, "\\def\\fresh{x}\\fresh");

        List<Descriptor> descriptors = new ArrayList<>(out.values());
        descriptors.sort(Comparator
            .comparingInt((Descriptor descriptor) -> descriptor.category().ordinal())
            .thenComparing(Descriptor::displayName));
        ensureEveryHandlerHasADescriptor(descriptors);
        return List.copyOf(descriptors);
    }

    private static void add(
            Map<String, Descriptor> out,
            String name,
            Category category,
            Handler handler,
            String indexExample) {
        add(out, name, category, handler, indexExample, OptionalInt.empty());
    }

    private static void add(
            Map<String, Descriptor> out,
            String name,
            Category category,
            Handler handler,
            String indexExample,
            OptionalInt delimiterCodePoint) {
        Descriptor descriptor = new Descriptor(
            name, category, handler, indexExample, false, delimiterCodePoint);
        Descriptor previous = out.putIfAbsent(name, descriptor);
        if (previous != null) {
            throw new IllegalStateException(
                "duplicate command descriptor for \\" + name + ": "
                    + previous.handler() + " and " + handler);
        }
    }

    /**
     * Contextual delimiter metadata lives here, at descriptor construction, not
     * in a second parser-side acceptance switch. Names absent from this mapping
     * remain ordinary symbols even if their glyph happens to look delimiter-like.
     */
    private static OptionalInt delimiterCodePointFor(String name) {
        return switch (name) {
            case "{" -> OptionalInt.of('{');
            case "}" -> OptionalInt.of('}');
            case "|", "Vert" -> OptionalInt.of(0x2016);
            case "vert" -> OptionalInt.of('|');
            case "langle" -> OptionalInt.of(0x27E8);
            case "rangle" -> OptionalInt.of(0x27E9);
            case "lfloor" -> OptionalInt.of(0x230A);
            case "rfloor" -> OptionalInt.of(0x230B);
            case "lceil" -> OptionalInt.of(0x2308);
            case "rceil" -> OptionalInt.of(0x2309);
            default -> OptionalInt.empty();
        };
    }

    private static Map<String, Descriptor> indexByName(List<Descriptor> descriptors) {
        Map<String, Descriptor> byName = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptors) {
            Descriptor previous = byName.putIfAbsent(descriptor.name(), descriptor);
            if (previous != null) {
                throw new IllegalStateException("duplicate command descriptor: " + descriptor.name());
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    private static void ensureEveryHandlerHasADescriptor(List<Descriptor> descriptors) {
        Set<Handler> seen = EnumSet.noneOf(Handler.class);
        for (Descriptor descriptor : descriptors) {
            seen.add(descriptor.handler());
        }
        Set<Handler> missing = EnumSet.allOf(Handler.class);
        missing.removeAll(seen);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("command handlers without descriptors: " + missing);
        }
    }

    /** Categorises a symbol-table entry by its code-point range and math class. */
    private static Category categorize(Sym symbol) {
        int cp = symbol.codePoint();
        if (cp >= 0x0370 && cp <= 0x03FF) {
            return Category.GREEK;
        }
        if ((cp >= 0x2190 && cp <= 0x21FF) || (cp >= 0x27F0 && cp <= 0x27FF)) {
            return Category.ARROW;
        }
        return switch (symbol.mathClass()) {
            case REL -> Category.RELATION;
            case BIN -> Category.BINARY_OPERATOR;
            case ORD, OP, INNER, OPEN, CLOSE, PUNCT -> Category.ORDINARY;
        };
    }

    private static String accentBase(AccentSpec accent) {
        if (accent.codePoint() == Accent.RULE) {
            return "{a+b}";
        }
        return accent.stretchy() ? "{abc}" : "{x}";
    }
}
