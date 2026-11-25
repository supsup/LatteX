package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.MathClass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// Plan a9dde3b4 (lattex project): the class of a PASTED character no longer depends on the
/// symbol table's per-JVM iteration order. Found by Fixpoint's reader (stafficy/35720: 'a ⊥ b'
/// gave widths 72.36 and 94.5822 from one jar) and Lattice (35723: △ and ▽ alternate too, in
/// fresh JVMs on main 5cb804f). The choices are Unicode's MathClass-15.txt: 22A5;R 25B3;B 25BD;B.
class SymbolsRawClassTest {

    private static String pp(String latex) {
        return MathParserTest.pp(MathParser.parse(latex));
    }

    @Test
    void theThreePastedCharactersTakeUnicodesClass() {
        assertEquals(MathClass.REL, Symbols.classForCodePoint(0x22A5));
        assertEquals(MathClass.BIN, Symbols.classForCodePoint(0x25B3));
        assertEquals(MathClass.BIN, Symbols.classForCodePoint(0x25BD));
        // and so each renders exactly as the command whose class it takes
        assertEquals(LatteX.render("a \\perp b"), LatteX.render("a ⊥ b"));
        assertEquals(LatteX.render("a \\bigtriangleup b"), LatteX.render("a △ b"));
        assertEquals(LatteX.render("a \\bigtriangledown b"), LatteX.render("a ▽ b"));
    }

    @Test
    void theNamedCommandsKeepTheirOwnClasses() {
        assertEquals("L(A(a,ORD) A(U+22A5,ORD) A(b,ORD))", pp("a \\bot b"));
        assertEquals("L(A(a,ORD) A(U+22A5,REL) A(b,ORD))", pp("a \\perp b"));
        assertEquals("L(A(a,ORD) A(U+25B3,ORD) A(b,ORD))", pp("a \\triangle b"));
        assertEquals("L(A(a,ORD) A(U+25B3,BIN) A(b,ORD))", pp("a \\bigtriangleup b"));
        assertEquals("L(A(a,ORD) A(U+25BD,ORD) A(b,ORD))", pp("a \\triangledown b"));
        assertEquals("L(A(a,ORD) A(U+25BD,BIN) A(b,ORD))", pp("a \\bigtriangledown b"));
    }

    @Test
    void theRawClassTableIsTheSameInEveryIterationOrder() {
        List<Map.Entry<String, Symbols.Sym>> entries = new ArrayList<>(Symbols.SYMBOLS.entrySet());
        Random random = new Random(20261125L);
        for (int run = 0; run < 64; run++) {
            Collections.shuffle(entries, random);
            Map<String, Symbols.Sym> shuffled = new LinkedHashMap<>();
            for (var e : entries) {
                shuffled.put(e.getKey(), e.getValue());
            }
            assertEquals(Symbols.CLASS_BY_CODEPOINT,
                Symbols.buildClassByCodepoint(shuffled, Set.of("lVert", "rVert", "lhd", "rhd", "unlhd", "unrhd"),
                    Symbols.RAW_CLASS_CHOICE),
                "order " + run);
        }
    }

    @Test
    void aConflictWithoutAChoiceDoesNotBuildAndAStaleChoiceIsRefused() {
        Map<String, Symbols.Sym> pair = new LinkedHashMap<>();
        pair.put("sunord", new Symbols.Sym(0x2600, MathClass.ORD));
        pair.put("sunrel", new Symbols.Sym(0x2600, MathClass.REL));
        IllegalStateException none = assertThrows(IllegalStateException.class,
            () -> Symbols.buildClassByCodepoint(pair, Set.of(), Map.of()));
        assertTrue(none.getMessage().contains("U+2600 has spellings of classes"), none.getMessage());
        assertEquals(MathClass.REL,
            Symbols.buildClassByCodepoint(pair, Set.of(), Map.of(0x2600, MathClass.REL)).get(0x2600));
        Map<String, Symbols.Sym> agree = Map.of("one", new Symbols.Sym(0x2601, MathClass.ORD));
        IllegalStateException stale = assertThrows(IllegalStateException.class,
            () -> Symbols.buildClassByCodepoint(agree, Set.of(), Map.of(0x2601, MathClass.ORD)));
        assertTrue(stale.getMessage().contains("remove the stale entry"), stale.getMessage());
    }

    @Test
    void exactlyThreeExplicitChoicesExist() {
        assertEquals(Set.of(0x22A5, 0x25B3, 0x25BD), Symbols.RAW_CLASS_CHOICE.keySet(),
            "Lattice's census (35723): the only mixed-class code points after CLASS_TAGGED_SPELLINGS");
    }
}
