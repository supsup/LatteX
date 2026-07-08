package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.Color;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * Layout's independent recursion guard. Layout recurses as deep as the node tree, and
 * a {@code StackOverflowError} there is an {@code Error} that escapes {@code catch
 * (RuntimeException)} and kills the render thread. The parser bounds the tree today,
 * but that coupling is coincidental (a different module); layout must fail SAFELY
 * (a caught {@link MathSyntaxException}) on an over-deep tree built any other way.
 */
class LayoutDepthGuardTest {

    private static final SfntFont FONT = SfntFont.loadBundled();

    private static LayoutContext ctx() {
        return new LayoutContext(FONT, FONT.mathConstants(), 40.0);
    }

    /** A programmatic tree {@code depth} levels deep — bypasses the parser's MAX_DEPTH. */
    private static MathNode nest(int depth) {
        MathNode n = new MathNode.Atom('x', MathNode.MathClass.ORD);
        for (int i = 0; i < depth; i++) {
            n = new MathNode.Colored(n, Color.CURRENT); // each wraps one body -> +1 layout level
        }
        return n;
    }

    @Test
    void overDeepTreeThrowsSyntaxExceptionNotStackOverflow() {
        MathNode tooDeep = nest(LayoutEngine.MAX_LAYOUT_DEPTH + 50);
        MathSyntaxException ex = assertThrows(MathSyntaxException.class,
            () -> LayoutEngine.layout(tooDeep, ctx()));
        assertTrue(ex.getMessage().contains("too deep"),
            "should name the layout depth limit: " + ex.getMessage());
    }

    @Test
    void aLegalDepthTreeStillLaysOut() {
        // Well under the cap — the guard must not false-positive.
        assertDoesNotThrow(() -> LayoutEngine.layout(nest(100), ctx()));
    }

    @Test
    void theCounterDoesNotLeakAfterAGuardTrip() {
        // A trip must not leave the per-thread depth elevated: a legal render on the
        // SAME thread afterwards must still succeed (the pre-throw decrement + finally
        // keep the counter balanced).
        assertThrows(MathSyntaxException.class,
            () -> LayoutEngine.layout(nest(LayoutEngine.MAX_LAYOUT_DEPTH + 50), ctx()));
        assertDoesNotThrow(() -> LayoutEngine.layout(nest(100), ctx()),
            "a legal render after a guard trip must still succeed (counter not leaked)");
    }
}
