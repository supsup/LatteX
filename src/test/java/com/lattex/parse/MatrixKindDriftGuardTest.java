package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.stream.Collectors.toSet;

import com.lattex.api.LatteX;
import com.lattex.parse.MathNode.MatrixKind;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Drift-guard for the {@code MatrixKind} / {@code MathClass} closed sets (code-health,
 * Fixpoint plan bb5cd72f Item 1).
 *
 * <p>The layout/describe/categorize switches that fan out on these enums —
 * {@code LayoutEngine} (cell style, inter-row gap, edge/col gaps), {@code MathParser.categorize},
 * {@code LatteX.describeMatrix} — are now EXHAUSTIVE with no {@code default} arm. So a new
 * {@code MatrixKind} or {@code MathClass} constant fails to COMPILE at every one of those sites
 * until its style/gap/category/description is a deliberate decision; that compile-time
 * exhaustiveness is the primary guard. These tests pin the calibrated member set (so a change is
 * loud and reviewed, and points the reviewer at those switches) and smoke that representative
 * kinds still render through the refactored switches (behavior preservation).
 */
class MatrixKindDriftGuardTest {

    @Test
    void matrixKindClosedSetIsPinned() {
        assertEquals(
            Set.of("ALIGN", "ARRAY", "CASES", "CD", "GATHER", "MATRIX", "MULTLINE", "SMALL", "SUBSTACK"),
            Arrays.stream(MatrixKind.values()).map(Enum::name).collect(toSet()),
            "MatrixKind changed — every no-default switch on it (LayoutEngine kindStyle/interRowGap/"
            + "gaps, LatteX.describeMatrix) is exhaustive and will fail to compile until the new kind "
            + "is handled; update those decisions and this pin together.");
    }

    @Test
    void mathClassClosedSetIsPinned() {
        assertEquals(
            Set.of("BIN", "CLOSE", "INNER", "OP", "OPEN", "ORD", "PUNCT", "REL"),
            Arrays.stream(MathNode.MathClass.values()).map(Enum::name).collect(toSet()),
            "MathClass changed — MathParser.categorize is exhaustive over it (no default) and will "
            + "fail to compile until the new class is categorized; update it and this pin together.");
    }

    /** Behavior preservation: the refactored (default-free) switches still render the common kinds. */
    @Test
    void representativeMatrixKindsStillRenderClean() {
        for (String dsl : new String[] {
            "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",       // MATRIX
            "\\begin{cases}a&x\\\\b&y\\end{cases}",           // CASES
            "\\begin{array}{cc}a&b\\\\c&d\\end{array}",       // ARRAY
            "\\begin{aligned}a&=b\\\\c&=d\\end{aligned}",     // ALIGN
        }) {
            String svg = LatteX.render(dsl);
            assertTrue(svg.contains("<svg"), "must render an svg: " + dsl);
        }
    }
}
