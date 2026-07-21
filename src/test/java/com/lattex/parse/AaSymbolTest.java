package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode.Accent;
import com.lattex.parse.MathNode.Atom;
import org.junit.jupiter.api.Test;

/**
 * {@code \aa} — the letter å (a-with-ring, U+00E5). It is a SINGLE precomposed code
 * point, NOT {@code \mathring{a}} (which composes a ring accent over an {@code a}),
 * so it must parse to a bare Ord {@link Atom} and NOT be node-equal to the
 * {@code \mathring{a}} accent tree. The bundled STIX Two Math font carries the
 * precomposed glyph (U+00E5 → {@code aring}), so this is the honest single-glyph
 * symbol route — no {@code <text>} fallback, no faked composition.
 */
class AaSymbolTest {

    @Test
    void aaIsPrecomposedRingLetterNotTheMathringAccent() {
        Atom aa = assertInstanceOf(Atom.class, MathParser.parse("\\aa"),
            "\\aa parses to a single precomposed Atom");
        assertEquals(0x00E5, aa.codePoint(), "\\aa is å (U+00E5)");
        assertEquals(MathNode.MathClass.ORD, aa.mathClass());

        MathNode ring = MathParser.parse("\\mathring{a}");
        assertInstanceOf(Accent.class, ring, "\\mathring{a} is an Accent, not a bare Atom");
        assertNotEquals(aa, ring,
            "\\aa is a precomposed letter, not the \\mathring{a} accent composition");
    }

    @Test
    void aaGlyphIsRealInTheBundledFontAndRenders() {
        // The precomposed glyph really exists (not .notdef) — the symbol route is honest.
        SfntFont font = SfntFont.loadBundled();
        assertTrue(font.glyphId(0x00E5) != 0, "STIX Two Math carries U+00E5 (aring)");
        // It renders to a real glyph path, alone and in a word-ish context.
        assertTrue(LatteX.render("\\aa").contains("<path"), "\\aa renders a glyph path");
        assertTrue(LatteX.render("K\\aa re").contains("<path"),
            "\\aa renders in a word-ish context");
    }
}
