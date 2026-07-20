package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lattex.parse.MathParser;
import com.lattex.parse.MathSyntaxException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bundled physics pack (L8 scope 4) — every macro proven by NODE EQUALITY
 * against its hand-expanded form (the L8 oracle), plus the pack SELF-CENSUS:
 * every pack entry must be definable (additive-only holds) and every body must
 * parse standalone, so shipping the pack can never break an input that parsed
 * without it, and a future pack edit that collides with a built-in fails here
 * rather than at a tenant's render.
 */
class MacroPacksTest {

    @Test
    void everyPhysicsMacroExpandsToItsHandWrittenForm() {
        assertEquals(MathParser.parse("\\langle A \\rangle"),
            MathParser.parse("\\ev{A}", MacroPacks.PHYSICS));
        assertEquals(MathParser.parse("\\mathrm{d} x"),
            MathParser.parse("\\dd x", MacroPacks.PHYSICS));
        assertEquals(MathParser.parse("\\nabla f"),
            MathParser.parse("\\grad f", MacroPacks.PHYSICS));
        assertEquals(MathParser.parse("| x |"),
            MathParser.parse("\\abs{x}", MacroPacks.PHYSICS));
        assertEquals(MathParser.parse("\\Vert v \\Vert"),
            MathParser.parse("\\norm{v}", MacroPacks.PHYSICS));
        assertEquals(MathParser.parse("\\frac{\\mathrm{d} f}{\\mathrm{d} x}"),
            MathParser.parse("\\dv{f}{x}", MacroPacks.PHYSICS));
        assertEquals(MathParser.parse("\\frac{\\partial f}{\\partial x}"),
            MathParser.parse("\\pdv{f}{x}", MacroPacks.PHYSICS));
    }

    @Test
    void thePackSelfCensusHoldsForEveryEntry() {
        for (Map.Entry<String, String> e : MacroPacks.PHYSICS.entrySet()) {
            // Definable: the name passes the additive-only deny check (a collision
            // with a built-in would throw here — the census the pack ships under).
            MathParser.parse("x", Map.of(e.getKey(), "y"));
            // Body parses standalone with the arguments stubbed: substitute
            // #k -> a k-specific atom and require a clean parse, so a body typo
            // (or a body using an unsupported command) fails at build time.
            String stubbed = e.getValue().replace("#1", "u").replace("#2", "w");
            MathParser.parse(stubbed);
        }
    }

    @Test
    void packComposesWithUserMacrosViaPlus() {
        Map<String, String> merged = MacroPacks.plus(MacroPacks.PHYSICS,
            Map.of("state", "\\ev{\\psi}"));
        assertEquals(MathParser.parse("\\langle \\psi \\rangle"),
            MathParser.parse("\\state", merged));
        // extra wins on a duplicate name
        Map<String, String> overridden = MacroPacks.plus(MacroPacks.PHYSICS,
            Map.of("abs", "\\langle #1 \\rangle"));
        assertEquals(MathParser.parse("\\langle x \\rangle"),
            MathParser.parse("\\abs{x}", overridden));
    }

    @Test
    void withoutThePackTheNamesFailLoud() {
        // Non-vacuity: the pack is load-bearing — the same input without it errors.
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\pdv{f}{x}"));
        assertNotEquals(MathParser.parse("x", MacroPacks.PHYSICS).getClass().getName(), "");
    }
}
