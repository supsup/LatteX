package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.MathList;
import org.junit.jupiter.api.Test;

/// {@code \prescript{sup}{sub}{base}} — left-attached scripts (physics/tensor
/// pre-indices, e.g. carbon-14 as `\prescript{14}{6}{\mathrm{C}}`). Pure parser
/// sugar: an empty-base {@code SupSub} carrying the pre-scripts, then the base — so
/// it renders identically to the manual {@code {}^{sup}_{sub} base} idiom.
class PrescriptTest {

    @Test
    void prescriptIsAnEmptyBaseSupSubThenTheBase() {
        assertEquals("L(SS(L(),^A(a,ORD),_A(b,ORD)) A(C,ORD))",
            MathParserTest.pp(MathParser.parse("\\prescript{a}{b}{C}")));
    }

    @Test
    void prescriptRendersIdenticallyToTheManualEmptyGroupIdiom() {
        String sugar = LatteX.render("\\prescript{14}{6}{\\mathrm{C}}");
        String manual = LatteX.render("{}^{14}_{6}{\\mathrm{C}}");
        assertEquals(manual, sugar, "prescript sugar == the {}^{}_{} base idiom");
    }

    @Test
    void anEmptyScriptSlotRendersNothingOnThatSide() {
        // \prescript{}{2}{X} carries only a pre-subscript.
        MathList list = assertInstanceOf(MathList.class, MathParser.parse("\\prescript{}{2}{X}"));
        assertEquals("L(SS(L(),^L(),_A(2,ORD)) A(X,ORD))", MathParserTest.pp(list));
    }

    @Test
    void rendersInAlphabet() {
        String svg = LatteX.render("\\prescript{n}{k}{X}");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertTrue(!svg.contains("<line") && !svg.contains("<marker"), "in-alphabet only");
    }

    @Test
    void missingArgumentThrows() {
        assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\prescript{a}{b}"), "needs 3 args");
    }
}
