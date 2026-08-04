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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/// MATH-MODE census of the single-non-letter escape space (plan d2f3447c
/// residual — the math-side counterpart to {@link TextControlSymbolTest}, which
/// closed the same hole inside `\text{…}`).
///
/// The lexer turns `\` + one NON-letter into a single-character control sequence
/// (`MathParser.lex`, the `else` arm of the `'\\'` case) whose name is that one
/// character. Whether such a name is SUPPORTED is decided in exactly one place —
/// {@link CommandRegistry}, whose descriptors are derived from the
/// {@link Symbols} tables — so this space is finite, enumerable, and has a
/// single authority. This test enumerates it and pins the acceptance property:
///
/// > every single-non-letter escape either renders with correct LaTeX semantics,
/// > or fails LOUD. There is no silent third path.
///
/// ## The residual this test closes
///
/// Standard LaTeX escapes SEVEN specials to their literal character:
/// `\#  \$  \%  \&  \_  \{  \}`. Before this change LatteX registered only
/// `\#`, `\{`, `\}` (plus `\|`, an unrelated double-bar). `\%`, `\&`, `\_`, `\$`
/// hit the unknown-command throw — LOUD, so not a silent-corruption defect, but
/// WRONG: `50\%` and `A \& B` are ordinary, correct LaTeX that a renderer of
/// harvested sources meets constantly. The four rows added to
/// {@link Symbols#SYMBOLS} make them ordinary literal-glyph atoms, which is what
/// the documented LaTeX behavior of those four control symbols is.
///
/// ## On the evidence for "correct semantics"
///
/// There is NO TeX engine on the build machine, so nothing here is a
/// pdflatex-differential. The semantics asserted are the DOCUMENTED, standard,
/// uncontroversial behavior of the four escapable specials (a literal `%`, `&`,
/// `_`, `$` glyph, ordinary atom class) — stated as such, not as an engine
/// comparison. What IS measured here is LatteX's own behavior: the atom, the
/// class, the glyph path, and the accessible text.
///
/// ## Why the negative control is not vacuous
///
/// {@link #unknownNonLetterEscapesStillFailLoudAfterTheAdditions} names escapes
/// that stay OUT of the accepted set (`\@`, `\^`, `\~`, `\.`, `\-`). The mutation
/// check for the pair is: delete the `%` row from {@link Symbols}, and the `\%`
/// fixture must DIE while this control stays GREEN — a control that dies with the
/// fixture is measuring the fixture, not the boundary.
class NonLetterEscapeCensusTest {

    /// The four escapes this change adds, with the literal character each must
    /// produce. All four are ORDINARY atoms in LaTeX.
    private static final Map<String, Integer> ADDED_LITERAL_ESCAPES = Map.of(
        "%", (int) '%',
        "&", (int) '&',
        "_", (int) '_',
        "$", (int) '$');

    /// Every single-non-letter escape the registry accepts, pinned exactly. A
    /// future addition or removal must show up as a diff HERE, not as a silent
    /// widening discovered by a corpus.
    ///
    /// Grouped by why each is accepted:
    ///  - literal specials: `#`, `$`, `%`, `&`, `_`, `{`, `}` (LaTeX's escapable set;
    ///    `\{`/`\}` additionally carry delimiter metadata);
    ///  - `|` — NOT a literal pipe: `\|` is the double bar ‖, a synonym of `\Vert`;
    ///  - spacing: `\,` `\:` `\;` `\!` `\>` and the control space `\ `;
    ///  - `\\` — the row separator, valid only inside a matrix/array context.
    private static final Set<String> ACCEPTED_NON_LETTER_ESCAPES = Set.of(
        "#", "$", "%", "&", "_", "{", "}", "|",
        ",", ":", ";", "!", ">", " ",
        "\\");

    /// The census domain: `\` followed by ONE printable-ASCII non-letter.
    private static List<String> escapeSpace() {
        List<String> names = new ArrayList<>();
        for (int c = 0x20; c <= 0x7E; c++) {
            if (!Character.isLetter(c)) {
                names.add(String.valueOf((char) c));
            }
        }
        return List.copyOf(names);
    }

    private static String ariaOf(String svg) {
        int open = svg.indexOf("aria-label=\"");
        assertTrue(open >= 0, "rendered SVG must carry an accessible label");
        int close = svg.indexOf('"', open + "aria-label=\"".length());
        return svg.substring(open + "aria-label=\"".length(), close);
    }

    // ------------------------------------------------------------------
    // Red-first, one fixture per newly accepted escape.
    // ------------------------------------------------------------------

    @Test
    void percentIsALiteralPercentAtom() {
        // Pre-change: "Unknown command: \%".
        Atom percent = (Atom) MathParser.parse("\\%");
        assertEquals('%', percent.codePoint());
        assertEquals(MathClass.ORD, percent.mathClass());
        assertEquals("A(%,ORD)", MathParserTest.pp(MathParser.parse("\\%")));
        // The realistic source shape: a percentage in an expression. Each digit
        // is its own atom, so the accessible text is "5 0 %" — the assertion is
        // on the literal '%' arriving, not on digit grouping.
        assertEquals("5 0 %", ariaOf(LatteX.render("50\\%")));
        assertTrue(LatteX.render("50\\%").contains("<path"), "renders glyph paths");
    }

    @Test
    void ampersandIsALiteralAmpersandAtom() {
        // Pre-change: "Unknown command: \&".
        Atom and = (Atom) MathParser.parse("\\&");
        assertEquals('&', and.codePoint());
        assertEquals(MathClass.ORD, and.mathClass());
        assertEquals("A(&,ORD)", MathParserTest.pp(MathParser.parse("\\&")));
        // The literal character reaches the accessible text XML-ESCAPED — which is
        // how a raw '&' is CARRIED, not a substitution for it. An unescaped '&' in
        // an attribute would be malformed XML.
        String svg = LatteX.render("A \\& B");
        assertEquals("A &amp; B", ariaOf(svg));
        assertFalse(svg.contains("aria-label=\"A & B\""), "a bare '&' would be malformed XML");
        assertTrue(svg.contains("<path"), "renders glyph paths");
    }

    @Test
    void underscoreIsALiteralUnderscoreAtomAndNotASubscript() {
        // Pre-change: "Unknown command: \_".
        Atom underscore = (Atom) MathParser.parse("\\_");
        assertEquals('_', underscore.codePoint());
        assertEquals(MathClass.ORD, underscore.mathClass());
        // The load-bearing distinction: BARE '_' is the subscript operator (a SUB
        // token), ESCAPED '\_' is a literal glyph. x\_y is three atoms in a row,
        // NOT "x subscript y".
        assertEquals("L(A(x,ORD) A(_,ORD) A(y,ORD))",
            MathParserTest.pp(MathParser.parse("x\\_y")));
        assertEquals("SS(A(x,ORD),_A(y,ORD))", MathParserTest.pp(MathParser.parse("x_y")));
        assertEquals("x _ y", ariaOf(LatteX.render("x\\_y")));
    }

    @Test
    void dollarIsALiteralDollarAtom() {
        // Pre-change: "Unknown command: \$".
        Atom dollar = (Atom) MathParser.parse("\\$");
        assertEquals('$', dollar.codePoint());
        assertEquals(MathClass.ORD, dollar.mathClass());
        assertEquals("A($,ORD)", MathParserTest.pp(MathParser.parse("\\$")));
        assertEquals("$ 5", ariaOf(LatteX.render("\\$5")));
    }

    @Test
    void escapedAmpersandIsNotAColumnSeparator() {
        // The sharpest semantic edge of the four. BARE '&' separates matrix
        // columns; ESCAPED '\&' must be cell CONTENT. If \& were routed as a
        // separator (or if the addition leaked into the CHAR path), this 1x1
        // matrix would become 1x2 and the difference would be invisible in a
        // "renders without throwing" check.
        String escaped = MathParserTest.pp(
            MathParser.parse("\\begin{matrix}a\\&b\\end{matrix}"));
        String bare = MathParserTest.pp(
            MathParser.parse("\\begin{matrix}a&b\\end{matrix}"));
        assertTrue(escaped.contains("&"), escaped);
        assertFalse(escaped.equals(bare),
            "\\& must be cell content, never the column separator: " + escaped);
        // And the bare-'&' separator behavior is untouched by the addition.
        assertEquals(MathParserTest.pp(MathParser.parse("\\begin{matrix}a&b\\end{matrix}")), bare);
    }

    // ------------------------------------------------------------------
    // The census proper: zero silent paths across the whole space.
    // ------------------------------------------------------------------

    @Test
    void everyNonLetterEscapeIsEitherRegisteredOrFailsLoud() {
        List<String> silent = new ArrayList<>();
        Set<String> observedAccepted = new TreeSet<>();
        for (String name : escapeSpace()) {
            String source = "\\" + name;
            Descriptor descriptor = CommandRegistry.get(name);
            if (descriptor != null) {
                observedAccepted.add(name);
                continue;
            }
            // UNREGISTERED: the only acceptable outcome is a loud, classified
            // unknown-command failure. A parse that SUCCEEDS here is the silent
            // path this census exists to forbid.
            MathSyntaxException failure;
            try {
                MathNode node = MathParser.parse(source);
                silent.add(source + " parsed silently to " + MathParserTest.pp(node));
                continue;
            } catch (MathSyntaxException e) {
                failure = e;
            }
            assertTrue(failure.isUnknownCommand(), source + ": " + failure.getMessage());
            assertTrue(failure.isUnsupportedConstruct(), source + ": " + failure.getMessage());
            assertTrue(failure.getMessage().contains("Unknown command"), failure.getMessage());
        }
        assertTrue(silent.isEmpty(), "silent (neither correct nor loud) escapes: " + silent);
        assertEquals(new TreeSet<>(ACCEPTED_NON_LETTER_ESCAPES), observedAccepted,
            "the accepted single-non-letter escape set changed — update the pin deliberately");
    }

    @Test
    void everyAcceptedNonLetterEscapeHasRealSemantics() {
        // "Registered" must not be a rubber stamp: each accepted name carries a
        // typed descriptor whose handler decides its grammar and output, and each
        // one is exercised by an accepted example. \\ is the one CONTEXTUAL member
        // (a row separator), so its example is a matrix, not the bare escape.
        for (String name : ACCEPTED_NON_LETTER_ESCAPES) {
            Descriptor descriptor = CommandRegistry.get(name);
            assertNotNull(descriptor, "\\" + name + " must be a registry descriptor");
            assertEquals(OutputKind.RENDERING, descriptor.outputKind(), "\\" + name);
            assertEquals("\\" + name, descriptor.displayName());
            assertTrue(descriptor.indexExample().contains("\\" + name),
                "\\" + name + " -> " + descriptor.indexExample());
            MathParser.parse(descriptor.indexExample()); // throws on regression
            // Every built-in name is macro-reserved by the same policy.
            assertFalse(CommandRegistry.userMacroMayClaim(name),
                "\\" + name + " must be macro-reserved like every built-in");
            assertTrue(MathParser.supportedCommands().stream()
                .anyMatch(command -> command.command().equals("\\" + name)),
                "generated coverage must include \\" + name);
        }
    }

    @Test
    void theFourAdditionsAreOrdinarySymbolsAndNothingMore() {
        for (Map.Entry<String, Integer> entry : ADDED_LITERAL_ESCAPES.entrySet()) {
            String name = entry.getKey();
            Descriptor descriptor = CommandRegistry.get(name);
            assertNotNull(descriptor, "\\" + name + " must be a registry descriptor");
            assertEquals(Handler.SYMBOL, descriptor.handler(), "\\" + name);
            assertEquals(GrammarKind.SYMBOL, descriptor.grammarKind(), "\\" + name);
            assertTrue(CommandRegistry.hasHandler(name, Handler.SYMBOL), "\\" + name);

            Atom atom = (Atom) MathParser.parse("\\" + name);
            assertEquals(entry.getValue().intValue(), atom.codePoint(), "\\" + name);
            assertEquals(MathClass.ORD, atom.mathClass(), "\\" + name);

            // NOT delimiters: an ordinary symbol must not become valid after
            // \left/\right just because a table row appeared.
            assertTrue(CommandRegistry.delimiterCodePoint(name).isEmpty(),
                "\\" + name + " is an ordinary symbol, never a contextual delimiter");
            assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("\\left\\" + name + " x\\right\\" + name),
                "\\left\\" + name);

            // NOT claimable by a user macro. The OUTER gate fires first: macro
            // names must be ASCII letters, so the name is refused before the
            // registry reservation is consulted (same observed shape as \#).
            assertFalse(CommandRegistry.userMacroMayClaim(name), "\\" + name);
            MathSyntaxException preset = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("x", Map.of(name, "y")));
            assertTrue(preset.getMessage().contains("ASCII letters"), preset.getMessage());
        }
    }

    @Test
    void theAdditionsDidNotDisturbTheBareCharacters() {
        // CLASS_BY_CODEPOINT deliberately excludes ASCII, so four new ASCII rows
        // must not reclassify any literal character. '%', '$' and '_' as BARE
        // input keep exactly the behavior they had before the additions: '%' and
        // '$' are ordinary atoms, '_' is the subscript operator.
        assertEquals("A(%,ORD)", MathParserTest.pp(MathParser.parse("%")));
        assertEquals("A($,ORD)", MathParserTest.pp(MathParser.parse("$")));
        assertEquals("SS(A(x,ORD),_A(y,ORD))", MathParserTest.pp(MathParser.parse("x_y")));
        assertTrue(Symbols.CLASS_BY_CODEPOINT.keySet().stream().allMatch(cp -> cp > 0x7F),
            "the reverse class map stays non-ASCII; ASCII belongs to classify()");
    }

    // ------------------------------------------------------------------
    // Negative control. Must survive the additions AND survive the mutant.
    // ------------------------------------------------------------------

    @Test
    void unknownNonLetterEscapesStillFailLoudAfterTheAdditions() {
        // Deliberately chosen OUTSIDE the accepted set and never proposed for it:
        // \@ and \. are real LaTeX control symbols we do not implement, \^ and \~
        // are real LaTeX text accents we do not implement, \- is the discretionary
        // hyphen. Accepting any of them would be a widening; each must stay loud.
        for (String unknown : new String[] {"\\@", "\\^", "\\~", "\\.", "\\-", "\\?", "\\+"}) {
            MathSyntaxException failure = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse(unknown), unknown);
            assertTrue(failure.isUnknownCommand(), unknown + ": " + failure.getMessage());
            assertTrue(failure.isUnsupportedConstruct(), unknown);
            assertTrue(failure.getMessage().contains("Unknown command"), failure.getMessage());
            assertTrue(CommandRegistry.get(unknown.substring(1)) == null, unknown);
        }
        // And in the same realistic positions the four additions occupy, so the
        // control is not merely a bare-token check.
        for (String unknown : new String[] {"50\\@", "A \\^ B", "x\\~y", "\\-5"}) {
            assertThrows(MathSyntaxException.class, () -> MathParser.parse(unknown), unknown);
        }
    }

    @Test
    void theAcceptedSetIsExactlyTheEscapableSpecialsPlusThePreexistingMembers() {
        // A second, independent spelling of the boundary: LaTeX's seven escapable
        // specials must ALL be accepted (that is the residual's whole point), and
        // the rest of the accepted set must be the pre-existing spacing/structural
        // members — nothing else crept in.
        Set<String> latexEscapableSpecials =
            new LinkedHashSet<>(List.of("#", "$", "%", "&", "_", "{", "}"));
        assertTrue(ACCEPTED_NON_LETTER_ESCAPES.containsAll(latexEscapableSpecials),
            "all seven LaTeX escapable specials must be accepted");
        for (String special : latexEscapableSpecials) {
            assertNotNull(CommandRegistry.get(special), "\\" + special);
        }
        Set<String> remainder = new TreeSet<>(ACCEPTED_NON_LETTER_ESCAPES);
        remainder.removeAll(latexEscapableSpecials);
        assertEquals(new TreeSet<>(List.of("!", " ", ",", ":", ";", ">", "\\", "|")), remainder,
            "the non-special accepted escapes are spacing, the row separator, and \\|");
    }
}
