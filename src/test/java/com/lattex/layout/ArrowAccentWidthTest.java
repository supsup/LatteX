package com.lattex.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.api.MathStyle;
import com.lattex.font.GlyphOutline;
import com.lattex.font.SfntFont;
import com.lattex.parse.MathParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Geometry of the stretchy ARROW accents (plan b3f198f2): an arrow over or under a
 * base is never wider than the base's box, and the next atom never runs into it.
 *
 * <p>Found on a BrewShot picture of main 4a76848: {@code \varprojlim} drew its
 * U+20EE arrow 1786 design units wide under a 1420-unit "lim" (the smallest MATH
 * variant AT LEAST the base width, then centred), so the head hung ~183 units out on
 * the left and the shaft ran under the next atom ({@code \varprojlim M}). amsmath's
 * {@code \varprojlim} is {@code \\underleftarrow{\mathrm{lim}}}: the arrow is the width
 * of the box. The edbda088 tests checked acceptance and code points, not geometry.
 *
 * <p>Everything here is measured from the positioned glyphs' INK at font size =
 * units-per-em, so one user unit is one design unit. The oracle is independent of the
 * engine's sizing code: the arrow is identified by glyph id (any glyph of the arrow
 * code point's horizontal construction), the base by its letters' glyph ids, and the
 * base box by the letters' origins and advances.
 */
class ArrowAccentWidthTest {

    private static final SfntFont FONT = SfntFont.loadBundled();
    private static final double SIZE = FONT.unitsPerEm(); // 1 user unit == 1 design unit
    private static final double TOL = 2.0;

    private static final int ITALIC_A = 0x1D434;
    private static final int ITALIC_B = 0x1D435;
    private static final int ITALIC_K = 0x1D458;

    private static Layout layout(String latex, MathStyle style) {
        LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), SIZE, style, false);
        return LayoutEngine.layout(MathParser.parse(latex), ctx);
    }

    /** Every glyph the font can draw for this arrow: the natural one, variants, parts. */
    private static Set<Integer> arrowGlyphs(int... codePoints) {
        Set<Integer> out = new HashSet<>();
        for (int cp : codePoints) {
            int gid = FONT.glyphId(cp);
            out.add(gid);
            var c = FONT.horizontalVariants(gid);
            if (c != null) {
                c.variants().forEach(v -> out.add(v.glyphId()));
                if (c.hasAssembly()) {
                    c.assembly().parts().forEach(p -> out.add(p.glyphId()));
                }
            }
        }
        return out;
    }

    private static Set<Integer> glyphsOf(String text) {
        Set<Integer> out = new HashSet<>();
        text.codePoints().forEach(cp -> out.add(FONT.glyphId(cp)));
        return out;
    }

    private static double inkMin(PositionedGlyph g) {
        GlyphOutline o = FONT.outline(g.glyphId());
        return g.originX() + g.scale() * o.xMin();
    }

    private static double inkMax(PositionedGlyph g) {
        GlyphOutline o = FONT.outline(g.glyphId());
        return g.originX() + g.scale() * o.xMax();
    }

    /** What one layout measures: base box, arrow ink, and every other glyph. */
    private record Measured(double baseLeft, double baseRight, double arrowLeft,
                            double arrowRight, List<PositionedGlyph> others) {
        double opRight() {
            return Math.max(baseRight, arrowRight);
        }
    }

    private static Measured measure(String latex, MathStyle style, Set<Integer> base,
                                    Set<Integer> arrow, Set<Integer> ignored) {
        Layout l = layout(latex, style);
        double baseLeft = Double.POSITIVE_INFINITY;
        double baseRight = Double.NEGATIVE_INFINITY;
        double arrowLeft = Double.POSITIVE_INFINITY;
        double arrowRight = Double.NEGATIVE_INFINITY;
        List<PositionedGlyph> others = new ArrayList<>();
        for (PositionedGlyph g : l.glyphs()) {
            if (arrow.contains(g.glyphId())) {
                arrowLeft = Math.min(arrowLeft, inkMin(g));
                arrowRight = Math.max(arrowRight, inkMax(g));
            } else if (base.contains(g.glyphId())) {
                baseLeft = Math.min(baseLeft, g.originX());
                baseRight = Math.max(baseRight,
                    g.originX() + g.scale() * FONT.advanceWidth(g.glyphId()));
            } else if (!ignored.contains(g.glyphId())) {
                others.add(g);
            }
        }
        assertTrue(arrowLeft < arrowRight, latex + ": an arrow is drawn");
        assertTrue(baseLeft < baseRight, latex + ": the base is drawn");
        return new Measured(baseLeft, baseRight, arrowLeft, arrowRight, others);
    }

    /** The arrow lies within the base box, and nothing after it overlaps the operator. */
    private static Measured assertArrowWithinBase(String latex, MathStyle style,
                                                  Set<Integer> base, Set<Integer> arrow,
                                                  Set<Integer> ignored) {
        Measured m = measure(latex, style, base, arrow, ignored);
        String where = latex + " (" + style + "): arrow ink " + m.arrowLeft() + ".."
            + m.arrowRight() + " vs base box " + m.baseLeft() + ".." + m.baseRight();
        assertTrue(m.arrowLeft() >= m.baseLeft() - TOL, where + " - arrow hangs out on the left");
        assertTrue(m.arrowRight() <= m.baseRight() + TOL, where + " - arrow runs past the base");
        for (PositionedGlyph g : m.others()) {
            assertTrue(inkMin(g) >= m.opRight() - TOL, where + " - glyph " + g.glyphId()
                + " starts at " + inkMin(g) + ", inside the operator");
        }
        return m;
    }

    private static final Set<Integer> LIM = glyphsOf("lim");
    private static final Set<Integer> LEFT_BELOW = arrowGlyphs(0x20EE);
    private static final Set<Integer> RIGHT_BELOW = arrowGlyphs(0x20EF);

    @Test
    void varprojlimAndVarinjlimArrowsLieWithinLim() {
        for (MathStyle style : List.of(MathStyle.TEXT, MathStyle.DISPLAY)) {
            // In display the limit k sits UNDER lim (centred, may be anywhere below);
            // in text style it is a subscript to the right, so it is a neighbour too.
            Set<Integer> ignored = style == MathStyle.DISPLAY
                ? Set.of(FONT.glyphId(ITALIC_K)) : Set.of();
            for (String body : List.of(" M", "_k\\bigl(M/\\mathfrak m^k M\\bigr)\\Subset U",
                    "_k\\bigl(M\\bigr)")) {
                assertArrowWithinBase("\\varprojlim" + body, style, LIM, LEFT_BELOW, ignored);
                assertArrowWithinBase("\\varinjlim" + body, style, LIM, RIGHT_BELOW, ignored);
            }
        }
    }

    @Test
    void varLimitArrowIsAsWideAsTheFontAllowsUnderLim() {
        // Not a token arrow: STIX's construction cannot hit 1420 exactly within the
        // OpenType connector limits (an assembly spans 1206..1306 or 1519..1719), so
        // the widest construction that fits is the 1340 variant. Pin "at least 90%".
        Measured m = measure("\\varprojlim M", MathStyle.TEXT, LIM, LEFT_BELOW, Set.of());
        double base = m.baseRight() - m.baseLeft();
        assertTrue(m.arrowRight() - m.arrowLeft() >= 0.9 * base,
            "arrow " + (m.arrowRight() - m.arrowLeft()) + " under a " + base + " base");
    }

    @Test
    void underleftarrowRendersAndObeysTheSameRule() {
        Set<Integer> ab = Set.of(FONT.glyphId(ITALIC_A), FONT.glyphId(ITALIC_B));
        for (MathStyle style : List.of(MathStyle.TEXT, MathStyle.DISPLAY)) {
            assertArrowWithinBase("\\underleftarrow{AB}C", style, ab, LEFT_BELOW, Set.of());
            assertArrowWithinBase("\\underrightarrow{AB}C", style, ab, RIGHT_BELOW, Set.of());
            assertArrowWithinBase("\\underleftrightarrow{AB}C", style, ab,
                arrowGlyphs(0x034D, 0x20E1), Set.of());
            assertArrowWithinBase("\\overleftarrow{AB}C", style, ab, arrowGlyphs(0x20D6), Set.of());
            assertArrowWithinBase("\\overrightarrow{AB}C", style, ab, arrowGlyphs(0x20D7), Set.of());
            assertArrowWithinBase("\\overleftrightarrow{AB}C", style, ab,
                arrowGlyphs(0x20E1), Set.of());
        }
        String svg = LatteX.render("\\underleftarrow{AB}");
        assertTrue(svg.startsWith("<svg"), "renders");
    }

    @Test
    void underArrowsSitBelowAndOverArrowsAbove() {
        for (String cmd : List.of("underleftarrow", "underrightarrow", "underleftrightarrow")) {
            Layout l = layout("\\" + cmd + "{AB}", MathStyle.TEXT);
            assertTrue(l.maxY() > 1.0, cmd + ": ink below the baseline");
        }
        for (String cmd : List.of("overleftarrow", "overrightarrow", "overleftrightarrow")) {
            Layout l = layout("\\" + cmd + "{ab}", MathStyle.TEXT);
            assertTrue(l.minY() < -500.0, cmd + ": ink above the x-height");
        }
    }

    @Test
    void aWideBaseGetsAnArrowExactlyItsWidth() {
        // Where the assembly CAN reach the base width (overlaps within the connector
        // limits), the arrow is exactly the base width - TeX's \leftarrowfill.
        Set<Integer> base = new HashSet<>();
        for (int cp = ITALIC_A; cp <= ITALIC_A + 7; cp++) {
            base.add(FONT.glyphId(cp));
        }
        for (String cmd : List.of("underleftarrow", "underrightarrow", "overleftarrow",
                "overrightarrow", "overleftrightarrow", "underleftrightarrow")) {
            int cp = switch (cmd) {
                case "underleftarrow" -> 0x20EE;
                case "underrightarrow" -> 0x20EF;
                case "overleftarrow" -> 0x20D6;
                case "overrightarrow" -> 0x20D7;
                default -> 0x20E1;
            };
            Measured m = assertArrowWithinBase("\\" + cmd + "{ABCDEFGH}x", MathStyle.TEXT,
                base, arrowGlyphs(cp, 0x034D), Set.of());
            assertEquals(m.baseRight() - m.baseLeft(), m.arrowRight() - m.arrowLeft(), TOL,
                cmd + ": arrow spans the base exactly");
        }
    }

    @Test
    void aBaseNarrowerThanTheSmallestArrowWidensTheBox() {
        // \\underleftarrow{i}: no construction is as narrow as an i, so the box widens
        // to the arrow (base centred) and the neighbour starts after the arrow.
        Measured m = measure("\\underleftarrow{i}x", MathStyle.TEXT,
            glyphsOf(Character.toString(0x1D456)), LEFT_BELOW, Set.of());
        assertTrue(m.arrowLeft() >= -TOL, "arrow starts inside the box: " + m.arrowLeft());
        assertFalse(m.others().isEmpty());
        for (PositionedGlyph g : m.others()) {
            assertTrue(inkMin(g) >= m.arrowRight() - TOL,
                "x starts at " + inkMin(g) + ", arrow ends at " + m.arrowRight());
        }
        double baseCentre = (m.baseLeft() + m.baseRight()) / 2.0;
        double arrowCentre = (m.arrowLeft() + m.arrowRight()) / 2.0;
        assertEquals(arrowCentre, baseCentre, TOL, "base centred over the arrow");
    }
}
