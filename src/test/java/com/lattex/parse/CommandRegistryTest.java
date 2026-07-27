package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.CommandRegistry.Descriptor;
import com.lattex.parse.CommandRegistry.GrammarKind;
import com.lattex.parse.CommandRegistry.Handler;
import com.lattex.parse.CommandRegistry.OutputKind;
import com.lattex.parse.MathSyntaxException.UnsupportedKind;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Load-bearing drift guards for the typed command authority. */
class CommandRegistryTest {

    @Test
    void everyDescriptorHasAnAcceptedExampleAndEveryHandlerIsExercised() {
        Set<Handler> exercised = EnumSet.noneOf(Handler.class);
        Set<GrammarKind> grammars = EnumSet.noneOf(GrammarKind.class);
        for (Descriptor descriptor : CommandRegistry.descriptors()) {
            assertNotNull(descriptor.grammarKind(), descriptor.name());
            assertNotNull(descriptor.outputKind(), descriptor.name());
            assertTrue(descriptor.indexExample().contains(descriptor.displayName()),
                descriptor.displayName() + " example must actually name the command: "
                    + descriptor.indexExample());
            assertDoesNotThrow(
                () -> MathParser.parse(descriptor.indexExample()),
                descriptor.displayName() + " -> " + descriptor.indexExample());
            exercised.add(descriptor.handler());
            grammars.add(descriptor.grammarKind());
        }
        assertEquals(EnumSet.allOf(Handler.class), exercised,
            "a handler without a descriptor/example is an untested dispatch arm");
        assertEquals(EnumSet.allOf(GrammarKind.class), grammars,
            "a grammar kind without an accepted descriptor/example is dead metadata");
    }

    @Test
    void everyHandlerOwnsOneCanonicalGrammarAndOutputContract() {
        for (Handler handler : Handler.values()) {
            GrammarKind expectedGrammar = switch (handler) {
                case SYMBOL, BIG_OPERATOR, NAMED_OPERATOR, SPACE, MATHSTRUT, BMOD ->
                    GrammarKind.SYMBOL;
                case NOT -> GrammarKind.PREFIX;
                case ACCENT, FONT_VARIANT, ATOM_CLASS, BOXED, CANCEL, BRA, KET, BRAKET, SUBSTACK,
                        PHANTOM, HPHANTOM, VPHANTOM, UNDERBRACE, OVERBRACE, BORDER_MATRIX,
                        OPERATOR_NAME, PMOD, LABEL -> GrammarKind.ONE_ARGUMENT;
                case FRACTION, CONTINUED_FRACTION, DISPLAY_FRACTION, TEXT_FRACTION,
                        TEXT_COLOR, CANCEL_TO, BINOM, DISPLAY_BINOM, TEXT_BINOM, OVERSET,
                        UNDERSET, STACKREL -> GrammarKind.TWO_ARGUMENTS;
                case PRESCRIPT -> GrammarKind.THREE_ARGUMENTS;
                case RADICAL, X_ARROW -> GrammarKind.OPTIONAL_THEN_ARGUMENT;
                case STYLE_SWITCH, COLOR_SWITCH -> GrammarKind.SWITCH;
                case DIMENSION_SPACE -> GrammarKind.DIMENSION;
                case SIZED_DELIMITER -> GrammarKind.DELIMITER;
                case INFIX_FRACTION -> GrammarKind.INFIX;
                case BEGIN -> GrammarKind.ENVIRONMENT;
                case LEFT, END, ROW_RULE, RIGHT, LIMITS_MODIFIER, DELIMITER,
                        EQUATION_SUPPRESSOR, MIDDLE, ROW_SEPARATOR -> GrammarKind.CONTEXTUAL;
                case TEXT -> GrammarKind.TEXT_ARGUMENT;
                case DEFINITION -> GrammarKind.DEFINITION;
                case LX, TAG -> GrammarKind.TOP_LEVEL;
            };
            OutputKind expectedOutput = switch (handler) {
                case END, EQUATION_SUPPRESSOR, LABEL, DEFINITION ->
                    OutputKind.NON_RENDERING;
                default -> OutputKind.RENDERING;
            };
            assertEquals(expectedGrammar, handler.grammarKind(), handler.name());
            assertEquals(expectedOutput, handler.outputKind(), handler.name());
        }

        for (Descriptor descriptor : CommandRegistry.descriptors()) {
            assertEquals(descriptor.handler().grammarKind(), descriptor.grammarKind(),
                descriptor.displayName() + " must derive grammar from its handler");
            assertEquals(descriptor.handler().outputKind(), descriptor.outputKind(),
                descriptor.displayName() + " must derive output from its handler");
        }
    }

    @Test
    void grammarShapesHaveProductionBehaviorWitnesses() {
        Map<GrammarKind, String> accepted = Map.ofEntries(
            Map.entry(GrammarKind.SYMBOL, "\\alpha"),
            Map.entry(GrammarKind.PREFIX, "\\not="),
            Map.entry(GrammarKind.ONE_ARGUMENT, "\\boxed{x}"),
            Map.entry(GrammarKind.TWO_ARGUMENTS, "\\frac{a}{b}"),
            Map.entry(GrammarKind.THREE_ARGUMENTS, "\\prescript{a}{b}{x}"),
            Map.entry(GrammarKind.OPTIONAL_THEN_ARGUMENT, "\\sqrt[3]{x}"),
            Map.entry(GrammarKind.SWITCH, "\\displaystyle x"),
            Map.entry(GrammarKind.DIMENSION, "a\\hspace{9mu}b"),
            Map.entry(GrammarKind.DELIMITER, "\\big("),
            Map.entry(GrammarKind.INFIX, "a\\over b"),
            Map.entry(GrammarKind.ENVIRONMENT, "\\begin{matrix}x\\end{matrix}"),
            Map.entry(GrammarKind.CONTEXTUAL, "\\left(a\\middle|b\\right)"),
            Map.entry(GrammarKind.TEXT_ARGUMENT, "\\text{hello world}"),
            Map.entry(GrammarKind.DEFINITION, "\\def\\fresh{x}\\fresh"),
            Map.entry(GrammarKind.TOP_LEVEL, "\\lx{x}"));
        assertEquals(EnumSet.allOf(GrammarKind.class), accepted.keySet(),
            "every grammar shape needs a behavioral witness");
        accepted.forEach((grammar, source) ->
            assertDoesNotThrow(() -> MathParser.parse(source), grammar + " -> " + source));

        Descriptor sizedDelimiter = CommandRegistry.get("big");
        assertEquals(GrammarKind.DELIMITER, sizedDelimiter.grammarKind());
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\big{(}"),
            "a sized delimiter consumes one delimiter token, not a brace-group argument");
    }

    @Test
    void nonRenderingHandlersHavePinnedControlOnlyBehavior() {
        Set<Handler> nonRendering = EnumSet.noneOf(Handler.class);
        for (Handler handler : Handler.values()) {
            if (handler.outputKind() == OutputKind.NON_RENDERING) {
                nonRendering.add(handler);
            }
        }
        assertEquals(
            EnumSet.of(Handler.END, Handler.EQUATION_SUPPRESSOR, Handler.LABEL,
                Handler.DEFINITION),
            nonRendering);

        String x = LatteX.render("x");
        assertEquals(x, LatteX.render("x\\nonumber"));
        assertEquals(x, LatteX.render("x\\notag"));
        assertEquals(x, LatteX.render("x\\label{eq:x}"));
        assertEquals(x, LatteX.render("\\newcommand{\\fresh}{x}\\fresh"));
        assertEquals(x, LatteX.render("\\def\\fresh{x}\\fresh"));
        assertDoesNotThrow(() -> MathParser.parse("\\begin{matrix}x\\end{matrix}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\end"),
            "\\end is a context-only terminator, never a standalone output node");
    }

    @Test
    void supportedCommandsIsExactlyTheDescriptorIndexInDeterministicOrder() {
        List<String> descriptorNames = CommandRegistry.descriptors().stream()
            .map(Descriptor::displayName)
            .toList();
        List<String> supportedNames = MathParser.supportedCommands().stream()
            .map(MathParser.SupportedCommand::command)
            .toList();
        assertEquals(descriptorNames, supportedNames);
        assertEquals(supportedNames.size(), new HashSet<>(supportedNames).size(),
            "normalized command names must be unique");
        assertSame(MathParser.supportedCommands(), MathParser.supportedCommands(),
            "the immutable descriptor projection should be cached");
        assertEquals(supportedNames, MathParser.supportedCommands().stream()
            .map(MathParser.SupportedCommand::command)
            .toList(), "enumeration order must be deterministic");
    }

    @Test
    void auditCommandsCannotDisappearFromTheAuthority() {
        // Mutation guard: deleting (for example) the boxed descriptor while leaving
        // its old parser branch behind must fail here and make \boxed unknown.
        for (String name : List.of("boxed", "cancel", "bra", "prescript", "bordermatrix")) {
            Descriptor descriptor = CommandRegistry.get(name);
            assertNotNull(descriptor, "missing audit command descriptor: \\" + name);
            assertEquals(OutputKind.RENDERING, descriptor.outputKind(), "\\" + name);
            assertTrue(MathParser.supportedCommands().stream()
                .anyMatch(command -> command.command().equals("\\" + name)),
                "generated coverage must include \\" + name);
        }
    }

    @Test
    void contextualRecognitionIsAuthorizedByTheExpectedHandler() {
        Map<String, Handler> contextual = Map.ofEntries(
            Map.entry("tag", Handler.TAG),
            Map.entry("limits", Handler.LIMITS_MODIFIER),
            Map.entry("nolimits", Handler.LIMITS_MODIFIER),
            Map.entry("right", Handler.RIGHT),
            Map.entry("middle", Handler.MIDDLE),
            Map.entry("vert", Handler.DELIMITER),
            Map.entry("\\", Handler.ROW_SEPARATOR),
            Map.entry("cr", Handler.ROW_SEPARATOR),
            Map.entry("end", Handler.END),
            Map.entry("hline", Handler.ROW_RULE),
            Map.entry("hdashline", Handler.ROW_RULE),
            Map.entry("nonumber", Handler.EQUATION_SUPPRESSOR),
            Map.entry("notag", Handler.EQUATION_SUPPRESSOR));
        for (Map.Entry<String, Handler> expected : contextual.entrySet()) {
            assertTrue(CommandRegistry.hasHandler(expected.getKey(), expected.getValue()),
                "\\" + expected.getKey() + " must be authorized as " + expected.getValue());
            Descriptor descriptor = CommandRegistry.get(expected.getKey());
            assertDoesNotThrow(() -> MathParser.parse(descriptor.indexExample()),
                descriptor.displayName());
        }
        assertFalse(CommandRegistry.hasHandler("tag", Handler.ROW_RULE),
            "a known name with the wrong contextual handler is not authorized");
    }

    @Test
    void delimiterAcceptanceComesOnlyFromDescriptorMetadata() {
        for (Descriptor descriptor : CommandRegistry.descriptors()) {
            if (descriptor.delimiterCodePoint().isPresent()) {
                String delimiter = descriptor.displayName();
                assertDoesNotThrow(
                    () -> MathParser.parse("\\left" + delimiter + " x\\right" + delimiter),
                    delimiter + " descriptor declares contextual delimiter support");
            }
        }
        assertEquals('|', CommandRegistry.delimiterCodePoint("vert").orElseThrow());
        assertTrue(CommandRegistry.delimiterCodePoint("alpha").isEmpty());
        assertTrue(CommandRegistry.delimiterCodePoint("ulcorner").isEmpty(),
            "do not silently widen the pre-existing delimiter grammar");
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\left\\ulcorner x\\right\\urcorner"));
    }

    @Test
    void macroReservationComesDirectlyFromDescriptors() {
        Set<String> authorityNames = CommandRegistry.descriptors().stream()
            .map(Descriptor::name)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> reservedNames = authorityNames.stream()
            .filter(name -> !CommandRegistry.userMacroMayClaim(name))
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(authorityNames, reservedNames,
            "every accepted built-in name must be reserved to additive-only macros");

        for (String name : List.of(
                "boxed", "bordermatrix", "hline", "textbf", "newcommand")) {
            assertFalse(CommandRegistry.userMacroMayClaim(name), "\\" + name);
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("x", Map.of(name, "y")));
            assertTrue(failure.getMessage().contains("built-in"), failure.getMessage());
        }
        assertTrue(CommandRegistry.userMacroMayClaim("freshcommand"));
    }

    @Test
    void unknownCommandHasATypedReasonWithoutChangingTextOrOffset() {
        MathSyntaxException failure = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("a+\\zzzzzzzz"));
        assertEquals(2, failure.offset());
        assertEquals("Unknown command: \\zzzzzzzz", failure.getMessage());
        assertEquals(UnsupportedKind.UNKNOWN_COMMAND, failure.unsupportedKind());
        assertTrue(failure.isUnknownCommand());
        assertTrue(failure.isUnsupportedConstruct());

        MathSyntaxException misplacedDelimiter = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\vert"));
        assertEquals(0, misplacedDelimiter.offset());
        assertEquals("Unknown command: \\vert — did you mean \\Vert?",
            misplacedDelimiter.getMessage(),
            "a known contextual command must exclude itself without losing the base alternative");
        assertTrue(misplacedDelimiter.isUnknownCommand());

        MathSyntaxException nested = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\text{for $\\zzzzzzzz$}"));
        assertEquals(UnsupportedKind.UNKNOWN_COMMAND, nested.unsupportedKind(),
            "context wrapping must preserve the typed unknown-command reason");
        assertTrue(nested.getMessage().contains("in \\text nested math"), nested.getMessage());

        MathSyntaxException environment = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\begin{zzzzzzzz}x\\end{zzzzzzzz}"));
        assertEquals(UnsupportedKind.UNKNOWN_ENVIRONMENT, environment.unsupportedKind());
        assertTrue(environment.isUnknownEnvironment());
    }

    @Test
    void commandSuggestionsCanOnlyNameAuthorityEntries() {
        Set<String> authorityNames = CommandRegistry.descriptors().stream()
            .map(Descriptor::name)
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(authorityNames, CommandRegistry.suggestionNames());
        List<String> deterministicOrder = List.copyOf(CommandRegistry.suggestionNames());
        assertEquals(deterministicOrder.stream().sorted().toList(), deterministicOrder,
            "cached suggestion candidates must iterate deterministically");
        String suggestion = CommandRegistry.nearestSuggestion("boxe").orElseThrow();
        assertEquals("boxed", suggestion);
        assertTrue(authorityNames.contains(suggestion));
        assertEquals("Vert", CommandRegistry.nearestAlternative("vert").orElseThrow(),
            "contextual commands must retain the nearest different registry suggestion");
    }
}
