package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// The shared fail-closed guard on {@link Rule#polygon} (reviewer HIGH finding, bullet 3):
/// the single factory every polygon rule is built through rejects a non-finite vertex
/// coordinate up front, so a future caller that produces {@code NaN}/{@code Infinity} from
/// a degenerate geometry cannot silently poison the box metrics or the emitted viewBox.
/// It fails CLOSED (throws) — it never clamps a bad coordinate to a guessed value.
class RulePolygonFiniteTest {

    @Test
    void rejectsNaNVertex() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Rule.polygon(new double[] {0, 0, Double.NaN, 1, 2, 2}, null));
        assertTrue(e.getMessage().contains("finite"), "message names the finiteness rule: " + e.getMessage());
    }

    @Test
    void rejectsPositiveAndNegativeInfinityVertices() {
        assertThrows(IllegalArgumentException.class,
            () -> Rule.polygon(new double[] {0, 0, 1, Double.POSITIVE_INFINITY, 2, 2}, null));
        assertThrows(IllegalArgumentException.class,
            () -> Rule.polygon(new double[] {Double.NEGATIVE_INFINITY, 0, 1, 1, 2, 2}, null));
    }

    @Test
    void acceptsAWellFormedFinitePolygon() {
        Rule r = Rule.polygon(new double[] {0, 0, 4, 0, 2, 3}, null);
        assertTrue(r.isPolygon(), "a finite polygon is accepted");
        // bbox metrics track the vertices
        assertEquals(0.0, r.x());
        assertEquals(0.0, r.y());
        assertEquals(4.0, r.width());
        assertEquals(3.0, r.height());
    }
}
