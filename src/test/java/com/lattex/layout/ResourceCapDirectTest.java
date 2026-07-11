package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathSyntaxException;
import com.lattex.api.Color;
import com.lattex.svg.SvgEmitter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L10 — the layout box budget and the emitter output cap, tested DIRECTLY on a
 * programmatically-built tree (plan lattex-hostile-input-hardening). The parser's
 * matrix-cell cap (~10k) shadows both caps for any STRING input, so a string-fed
 * fuzz test can never reach them — the mutation survivors that exposed this. These
 * build the oversized tree past the parser to prove each cap actually fires, typed
 * as a resource-cap (OUTPUT_CAP_EXCEEDED via renderWithDiagnostics), never an Error.
 */
class ResourceCapDirectTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static MathNode flatList(int atoms) {
        List<MathNode> items = new ArrayList<>(atoms);
        for (int i = 0; i < atoms; i++) {
            items.add(new Atom('x', MathClass.ORD));
        }
        return new MathNode.MathList(items);
    }

    @Test
    void layoutBoxBudgetTripsOnAWideShallowTree() {
        // ~150k atoms: shallow (depth 2, under the depth guard) but past the 100k box
        // budget — exactly the fan-out the depth guard can't see. Must trip as a cap.
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> LayoutEngine.layout(flatList(150_000), ctx));
        assertTrue(ex.isCapExceeded(), "box-budget trip is typed as a resource cap");
        assertTrue(ex.getMessage().contains("box budget"), ex.getMessage());
    }

    @Test
    void emitterOutputCapTripsBeforeBuildingAHugeSvg() {
        // ~90k atoms: layout SUCCEEDS (under the box budget), but emitting ~90k
        // <path> elements blows past the 2M-char output cap. The cap must fire
        // DURING emission (incremental), typed as a resource cap.
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
        Layout layout = LayoutEngine.layout(flatList(90_000), ctx);
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> SvgEmitter.emit(layout, FONT, "x", Color.BLACK));
        assertTrue(ex.isCapExceeded(), "output-cap trip is typed as a resource cap");
        assertTrue(ex.getMessage().contains("output"), ex.getMessage());
    }
}
