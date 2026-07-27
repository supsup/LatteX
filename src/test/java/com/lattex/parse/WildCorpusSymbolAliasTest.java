package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.CommandRegistry.Descriptor;
import com.lattex.parse.CommandRegistry.GrammarKind;
import com.lattex.parse.CommandRegistry.Handler;
import com.lattex.parse.CommandRegistry.OutputKind;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Two wild-corpus coverage gaps that real harvested sources hit constantly:
 *
 * <ul>
 *   <li>{@code \#} — cardinality notation ({@code \#E(\mathbb{F}_p)}, the order of
 *       an elliptic curve group over a finite field). A single-character control
 *       sequence with no symbol-table entry, so it failed loud as an unknown
 *       command.</li>
 *   <li>{@code \mathds} — the dsfont alias for {@code \mathbb}, near-universal for
 *       the indicator function {@code \mathds{1}}. The old failure even suggested
 *       {@code \mathbb} as the nearest match; the alias makes that literal.</li>
 * </ul>
 *
 * <p>Both land as ONE table row each, because {@link CommandRegistry} derives its
 * descriptors from {@link Symbols#SYMBOLS} and {@link Symbols#FONT_VARIANTS}. The
 * registry assertions below are the load-bearing part: they pin that each new name
 * arrives with the grammar, output contract, delimiter metadata and macro-reservation
 * policy of its handler, rather than through a side switch. The negative controls
 * pin that neither addition WIDENED anything else — an additive symbol must not
 * become a delimiter, and unrelated unknown commands must still fail loud.
 */
class WildCorpusSymbolAliasTest {

    private static void assertRendersGlyphs(String latex) {
        String svg = LatteX.render(latex);
        assertTrue(svg.contains("<path"), "renders at least one glyph path: " + latex);
        assertTrue(svg.contains("viewBox="), "has a viewBox: " + latex);
    }

    // ------------------------------------------------------------------
    // \# as an ordinary symbol.
    // ------------------------------------------------------------------

    @Test
    void cardinalityHashParsesAsAnOrdinaryAtom() {
        Atom hash = (Atom) MathParser.parse("\\#");
        assertEquals('#', hash.codePoint());
        assertEquals(MathClass.ORD, hash.mathClass());
    }

    @Test
    void cardinalityHashRendersInARealisticExpression() {
        assertRendersGlyphs("\\#E(\\mathbb{F}_p)");
        assertRendersGlyphs("\\#\\{n : n \\le x\\}");
    }

    @Test
    void hashIsAuthorizedByTheRegistryAsASymbol() {
        Descriptor hash = CommandRegistry.get("#");
        assertNotNull(hash, "\\# must be a registry descriptor, not a loose parser branch");
        assertEquals(Handler.SYMBOL, hash.handler());
        assertEquals(GrammarKind.SYMBOL, hash.grammarKind());
        assertEquals(OutputKind.RENDERING, hash.outputKind());
        assertEquals("\\#", hash.displayName());
        assertTrue(CommandRegistry.hasHandler("#", Handler.SYMBOL));

        // The generated command index is the descriptor projection, so a new
        // accepted name must appear there without any second registration.
        assertTrue(MathParser.supportedCommands().stream()
            .anyMatch(command -> command.command().equals("\\#")),
            "generated coverage must include \\#");
    }

    @Test
    void hashIsReservedAgainstUserMacrosLikeEveryBuiltIn() {
        assertFalse(CommandRegistry.userMacroMayClaim("#"),
            "an accepted built-in name must be macro-reserved by the same policy");
        // Both gates are closed for \#, and the OUTER one fires first: macro names
        // (preset and inline alike) must be ASCII letters, so "#" is refused before
        // the registry reservation is even consulted. Asserted as observed, not as
        // the "built-in" message the letter-named commands produce.
        MathSyntaxException preset = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x", Map.of("#", "y")));
        assertTrue(preset.getMessage().contains("ASCII letters"), preset.getMessage());
        MathSyntaxException inline = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\def\\#{y}\\#"));
        assertTrue(inline.getMessage().contains("ASCII letters only"), inline.getMessage());
    }

    @Test
    void mathdsIsReservedAgainstUserMacrosByTheRegistry() {
        // \mathds is letter-named, so it DOES reach the registry reservation gate —
        // the alias must be as unclaimable as \mathbb itself.
        assertFalse(CommandRegistry.userMacroMayClaim("mathds"));
        MathSyntaxException preset = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x", Map.of("mathds", "y")));
        assertTrue(preset.getMessage().contains("built-in"), preset.getMessage());
        MathSyntaxException inline = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\newcommand{\\mathds}{y}\\mathds"));
        assertTrue(inline.getMessage().contains("built-in"), inline.getMessage());
    }

    @Test
    void hashDidNotWidenTheDelimiterGrammar() {
        // Negative control for the additive change: an ordinary symbol carries NO
        // delimiter metadata, so \left\#…\right\# must stay rejected.
        assertTrue(CommandRegistry.delimiterCodePoint("#").isEmpty(),
            "\\# is an ordinary symbol, never a contextual delimiter");
        assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\left\\# x\\right\\#"));
    }

    @Test
    void aBareHashCharacterIsUnaffected() {
        // The symbol table's reverse code-point class map now knows U+0023; a
        // literal '#' was already an ordinary atom and must stay one.
        Atom bare = (Atom) MathParser.parse("#");
        assertEquals('#', bare.codePoint());
        assertEquals(MathClass.ORD, bare.mathClass());
    }

    // ------------------------------------------------------------------
    // \mathds as an alias to \mathbb.
    // ------------------------------------------------------------------

    @Test
    void mathdsProducesTheSameAtomAsMathbb() {
        assertEquals(MathParser.parse("\\mathbb{1}"), MathParser.parse("\\mathds{1}"));
        assertEquals(MathParser.parse("\\mathbb{R}^n"), MathParser.parse("\\mathds{R}^n"));
        Atom one = (Atom) MathParser.parse("\\mathds{1}");
        assertEquals(0x1D7D9, one.codePoint(), "\\mathds{1} is the double-struck one 𝟙");
    }

    @Test
    void mathdsRendersTheIndicatorFunctionShape() {
        assertRendersGlyphs("\\mathds{1}_{[0,1]}(x)");
        assertRendersGlyphs("\\mathds{E}[X]");
    }

    @Test
    void mathdsIsAuthorizedByTheRegistryAsAFontVariant() {
        Descriptor mathds = CommandRegistry.get("mathds");
        assertNotNull(mathds, "\\mathds must be a registry descriptor");
        assertEquals(Handler.FONT_VARIANT, mathds.handler());
        // The alias cannot claim a contract that disagrees with the routine that
        // parses it: grammar and output are derived from the shared handler.
        assertEquals(CommandRegistry.get("mathbb").handler(), mathds.handler());
        assertEquals(GrammarKind.ONE_ARGUMENT, mathds.grammarKind());
        assertEquals(OutputKind.RENDERING, mathds.outputKind());
        assertFalse(CommandRegistry.userMacroMayClaim("mathds"));
        assertTrue(CommandRegistry.delimiterCodePoint("mathds").isEmpty());
        assertTrue(MathParser.supportedCommands().stream()
            .anyMatch(command -> command.command().equals("\\mathds")),
            "generated coverage must include \\mathds");
    }

    @Test
    void mathdsIsNowAValidSuggestionTargetAndNotAnUnknownCommand() {
        assertTrue(CommandRegistry.suggestionNames().contains("mathds"));
        // Before the alias, \mathds itself threw "did you mean \mathbb?".
        assertEquals("mathbb", CommandRegistry.nearestAlternative("mathds").orElseThrow());
    }

    // ------------------------------------------------------------------
    // Negative control: nothing else got wider. Neither addition may turn an
    // unrelated unknown command into an accepted one.
    // ------------------------------------------------------------------

    @Test
    void unrelatedUnknownCommandsStillFailLoud() {
        for (String unknown : new String[] {
                "\\totallynotarealcommand{x}", "\\mathdss{1}", "\\mathdd{1}"}) {
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(unknown), unknown);
            assertTrue(failure.getMessage().contains("Unknown command"), failure.getMessage());
            assertTrue(failure.isUnsupportedConstruct(), "classified as an unsupported construct");
            assertTrue(failure.isUnknownCommand(), unknown);
        }
    }
}
