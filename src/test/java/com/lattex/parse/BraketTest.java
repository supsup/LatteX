package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

/// Physics braket sugar: {@code \bra{ψ}} → ⟨ψ|, {@code \ket{φ}} → |φ⟩,
/// {@code \braket{a|b}} → ⟨a|b⟩. Pure parser sugar over existing delimiter atoms —
/// so a braket must render byte-identically to its manual angle-bracket form.
class BraketTest {

    @Test
    void braIsAngleBarAroundTheBody() {
        assertEquals("L(A(U+27E8,OPEN) A(x,ORD) A(|,CLOSE))", MathParserTest.pp(MathParser.parse("\\bra{x}")));
    }

    @Test
    void ketIsBarAngleAroundTheBody() {
        assertEquals("L(A(|,OPEN) A(x,ORD) A(U+27E9,CLOSE))", MathParserTest.pp(MathParser.parse("\\ket{x}")));
    }

    @Test
    void braketIsAnglesAroundTheBody() {
        assertEquals("L(A(U+27E8,OPEN) A(x,ORD) A(U+27E9,CLOSE))", MathParserTest.pp(MathParser.parse("\\braket{x}")));
    }

    @Test
    void braketRendersIdenticallyToTheManualAngleForm() {
        // The sugar expands to the same atoms as the manual \langle…\rangle, so the SVG matches.
        String sugar = LatteX.render("\\braket{\\psi | \\hat{H} | \\psi}");
        String manual = LatteX.render("\\langle \\psi | \\hat{H} | \\psi \\rangle");
        assertEquals(manual, sugar, "braket sugar == manual angle-bracket form");
    }

    @Test
    void braketRendersInAlphabet() {
        String svg = LatteX.render("\\bra{a}\\ket{b}");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"), "in-alphabet only");
    }
}
