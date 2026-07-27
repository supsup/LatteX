package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.ClassOverride;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathNode.MathList;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parse-side contract for the atom-class wrapper commands {@code \mathopen}
 * {@code \mathclose} {@code \mathord} {@code \mathbin} {@code \mathrel}
 * {@code \mathpunct} — TeX's noad-class-override primitives (TeXbook Ch.17),
 * which LaTeXML emits routinely to pin inter-atom spacing explicitly.
 *
 * <p>The spacing consequence is pinned separately in
 * {@code com.lattex.layout.AtomClassSpacingTest}; this class pins the node shape,
 * the registry authority, and the presentation-transparency of the wrapper in the
 * a11y/MathML surfaces.
 */
class AtomClassWrapperTest {

    @Test
    void everyWrapperParsesToItsForcedClass() {
        Map<String, MathClass> expected = Map.of(
            "mathopen", MathClass.OPEN,
            "mathclose", MathClass.CLOSE,
            "mathord", MathClass.ORD,
            "mathbin", MathClass.BIN,
            "mathrel", MathClass.REL,
            "mathpunct", MathClass.PUNCT);
        expected.forEach((name, forced) -> {
            ClassOverride node = assertInstanceOf(ClassOverride.class,
                MathParser.parse("\\" + name + "{x}"), "\\" + name);
            assertEquals(forced, node.forcedClass(), "\\" + name);
            Atom body = assertInstanceOf(Atom.class, node.body(), "\\" + name + " body");
            assertEquals('x', body.codePoint());
            // The BODY keeps its own class — only the row's view of the wrapper changes.
            assertEquals(MathClass.ORD, body.mathClass(),
                "the wrapper must not rewrite the body's own class");
        });
        assertEquals(expected.keySet(), Symbols.ATOM_CLASS_WRAPPERS.keySet(),
            "the wrapper table and this expectation must not drift apart");
    }

    @Test
    void mathopenWithEmptyContentIsAZeroWidthOpenMarker() {
        // LaTeXML emits a bare \mathopen{} as a class marker with no content. It must
        // not be rejected as a missing argument — an empty group is a valid body.
        ClassOverride node = assertInstanceOf(ClassOverride.class,
            MathParser.parse("\\mathopen{}"));
        assertEquals(MathClass.OPEN, node.forcedClass());
        assertTrue(assertInstanceOf(MathList.class, node.body()).items().isEmpty(),
            "\\mathopen{} carries an empty body");
        assertDoesNotThrow(() -> LatteX.render("\\mathopen{} x + y"));
    }

    @Test
    void aMissingArgumentStillFailsLoud() {
        // Negative control for the empty-group case above: "{}" is an EMPTY argument,
        // but no argument at all is still an error, with the same message shape the
        // font-variant commands use (both go through parseFontArg).
        MathSyntaxException end = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("x\\mathbin"));
        assertTrue(end.getMessage().contains("\\mathbin needs an argument"), end.getMessage());
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("{\\mathrel}"));
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\mathord^2"));
    }

    @Test
    void theWrapperNestsAndComposesWithTheOtherMathXCommands() {
        // \mathX{...} shares its surface shape with the font variants; the two must
        // compose in either order without one swallowing the other.
        ClassOverride outer = assertInstanceOf(ClassOverride.class,
            MathParser.parse("\\mathbin{\\mathbf{v}}"));
        assertEquals(MathClass.BIN, outer.forcedClass());
        // A font variant OUTSIDE the wrapper rewrites the nucleus but keeps the class.
        ClassOverride inside = assertInstanceOf(ClassOverride.class,
            MathParser.parse("\\mathbf{\\mathbin{v}}"));
        assertEquals(MathClass.BIN, inside.forcedClass());
        Atom bolded = assertInstanceOf(Atom.class, inside.body());
        assertEquals(0x1D42F, bolded.codePoint(), "\\mathbf must still reach the nucleus");
        // Nesting: the innermost wrapper wins for the atom, the outermost for the row.
        ClassOverride nested = assertInstanceOf(ClassOverride.class,
            MathParser.parse("\\mathrel{\\mathbin{x}}"));
        assertEquals(MathClass.REL, nested.forcedClass());
        assertEquals(MathClass.BIN,
            assertInstanceOf(ClassOverride.class, nested.body()).forcedClass());
    }

    @Test
    void theRegistryIsTheOnlyAuthorityForTheseNames() {
        for (String name : Symbols.ATOM_CLASS_WRAPPERS.keySet()) {
            assertTrue(
                CommandRegistry.hasHandler(name, CommandRegistry.Handler.ATOM_CLASS),
                "\\" + name + " must dispatch through the ATOM_CLASS handler");
            assertTrue(MathParser.supportedCommands().stream()
                .anyMatch(c -> c.command().equals("\\" + name)),
                "\\" + name + " must appear in the generated command index");
            // Reserved like every other built-in: a user macro cannot shadow it.
            MathSyntaxException claimed = assertThrows(MathSyntaxException.class,
                () -> MathParser.parse("x", Map.of(name, "y")));
            assertTrue(claimed.getMessage().contains("built-in"), claimed.getMessage());
        }
        // NEGATIVE CONTROL: a near-miss outside the six is still unknown, and the
        // suggestion machinery can now reach the real name.
        MathSyntaxException unknown = assertThrows(MathSyntaxException.class,
            () -> MathParser.parse("\\mathbinn{x}"));
        assertTrue(unknown.getMessage().startsWith("Unknown command: \\mathbinn"),
            unknown.getMessage());
        assertTrue(unknown.getMessage().contains("did you mean \\mathbin?"),
            unknown.getMessage());
    }

    @Test
    void spacingClassIsInvisibleToTheAccessibilityAndMathMLSurfaces() {
        // The wrapper is presentation-only: neither the aria prose nor the MathML
        // structure may gain a node for it (MathML spaces from its own operator
        // dictionary and takes no TeX class hint). Re-forcing the class an atom
        // already has must therefore be byte-identical end to end, aria label
        // and geometry included.
        assertEquals(LatteX.render("x \\cdot y"), LatteX.render("x \\mathbin{\\cdot} y"));
        assertEquals(LatteX.toMathML("x \\cdot y"), LatteX.toMathML("x \\mathbin{\\cdot} y"));
        assertEquals(LatteX.toMathML("f(x)"), LatteX.toMathML("f\\mathopen{(}x\\mathclose{)}"));
        assertTrue(LatteX.toMathML("\\mathopen{} x").contains("<math"),
            "an empty wrapper still yields a well-formed document");
    }
}
