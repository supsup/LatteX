package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.Contour;
import com.lattex.font.GlyphOutline;
import com.lattex.font.GlyphPoint;
import com.lattex.font.SfntFont;
import com.lattex.parse.CommandRegistry.Descriptor;
import com.lattex.parse.CommandRegistry.Handler;
import com.lattex.parse.MathNode.Atom;
import com.lattex.parse.MathNode.MathClass;
import com.lattex.parse.MathParser.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Upright Greek — the {@code upgreek} package family ({@code \\upalpha} …
 * {@code \\upomega}, the {@code \\upvar*} symbol forms, and the uppercase
 * {@code \Upgamma} … {@code \Upomega}).
 *
 * <p>ISO 80000-2 spells units, constants and particle names with upright Greek
 * ({@code \\upmu m}, an {@code \\upalpha} particle) and reserves slanted Greek for
 * variables, so these commands are everywhere in physics/chemistry sources.
 *
 * <p>The load-bearing claim is a claim about the BUNDLED FONT: that the Greek and
 * Coptic block STIX Two Math draws — the block these commands map into — really is
 * the upright alphabet, and that the slanted Greek lives elsewhere (Mathematical
 * Italic Greek). {@link #baseGreekBlockIsUprightAndMathItalicGreekIsSlanted()}
 * measures that on the font's own outlines rather than trusting it, so swapping
 * the bundled font cannot silently turn {@code \\upalpha} into a slanted glyph.
 */
class UprightGreekTest {

    /** The upgreek family: command name (no backslash) -> its upright code point. */
    private static final Map<String, Integer> UPGREEK = upgreekTable();

    private static Map<String, Integer> upgreekTable() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("upalpha", 0x03B1);
        m.put("upbeta", 0x03B2);
        m.put("upgamma", 0x03B3);
        m.put("updelta", 0x03B4);
        m.put("upepsilon", 0x03F5);
        m.put("upzeta", 0x03B6);
        m.put("upeta", 0x03B7);
        m.put("uptheta", 0x03B8);
        m.put("upiota", 0x03B9);
        m.put("upkappa", 0x03BA);
        m.put("uplambda", 0x03BB);
        m.put("upmu", 0x03BC);
        m.put("upnu", 0x03BD);
        m.put("upxi", 0x03BE);
        m.put("upomicron", 0x03BF);
        m.put("uppi", 0x03C0);
        m.put("uprho", 0x03C1);
        m.put("upsigma", 0x03C3);
        m.put("uptau", 0x03C4);
        m.put("upupsilon", 0x03C5);
        m.put("upphi", 0x03D5);
        m.put("upchi", 0x03C7);
        m.put("uppsi", 0x03C8);
        m.put("upomega", 0x03C9);
        m.put("upvarepsilon", 0x03B5);
        m.put("upvartheta", 0x03D1);
        m.put("upvarpi", 0x03D6);
        m.put("upvarrho", 0x03F1);
        m.put("upvarsigma", 0x03C2);
        m.put("upvarphi", 0x03C6);
        m.put("Upgamma", 0x0393);
        m.put("Updelta", 0x0394);
        m.put("Uptheta", 0x0398);
        m.put("Uplambda", 0x039B);
        m.put("Upxi", 0x039E);
        m.put("Uppi", 0x03A0);
        m.put("Upsigma", 0x03A3);
        m.put("Upupsilon", 0x03A5);
        m.put("Upphi", 0x03A6);
        m.put("Uppsi", 0x03A8);
        m.put("Upomega", 0x03A9);
        return Map.copyOf(m);
    }

    // ------------------------------------------------------------------
    // Parse + code point + atom class.
    // ------------------------------------------------------------------

    @Test
    void everyUpgreekCommandParsesToItsUprightGreekCodePoint() {
        assertEquals(41, UPGREEK.size(),
            "upgreek is 24 lowercase + 6 variant forms + the 11 uppercase letters LaTeX provides");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Integer> e : UPGREEK.entrySet()) {
            MathNode node = MathParser.parse("\\" + e.getKey());
            if (!(node instanceof Atom atom)) {
                wrong.add("\\" + e.getKey() + " parsed as " + node.getClass().getSimpleName());
                continue;
            }
            if (atom.codePoint() != e.getValue()) {
                wrong.add("\\%s -> U+%04X, expected U+%04X"
                    .formatted(e.getKey(), atom.codePoint(), e.getValue()));
            }
            if (atom.mathClass() != MathClass.ORD) {
                wrong.add("\\" + e.getKey() + " class " + atom.mathClass() + ", expected ORD");
            }
        }
        assertTrue(wrong.isEmpty(), "upgreek mappings wrong: " + wrong);
    }

    /**
     * The code point the SHIPPED table maps a command to. The font checks below
     * measure this rather than the expectation table above, so remapping the
     * family in {@link Symbols} to a slanted run fails the font guard too, not
     * only the expectation.
     */
    private static int shippedCodePoint(String name) {
        Symbols.Sym symbol = Symbols.SYMBOLS.get(name);
        assertNotNull(symbol, "\\" + name + " is not in the shipped symbol table");
        return symbol.codePoint();
    }

    @Test
    void everyUpgreekCodePointLandsInTheUprightGreekAndCopticBlock() {
        // The whole point of the family: never the Mathematical Italic Greek run
        // (U+1D6E2..U+1D71B), never the Mathematical Bold Greek run (U+1D6A8..).
        for (String name : UPGREEK.keySet()) {
            int cp = shippedCodePoint(name);
            assertTrue(cp >= 0x0370 && cp <= 0x03FF,
                "\\%s must map into Greek and Coptic, got U+%04X".formatted(name, cp));
        }
    }

    // ------------------------------------------------------------------
    // Render: parse -> layout -> emit, real glyph paths.
    // ------------------------------------------------------------------

    private static void assertRendersGlyphs(String latex) {
        String svg = LatteX.render(latex);
        assertTrue(svg.contains("<path"), "renders at least one glyph path: " + latex);
        assertTrue(svg.contains("viewBox="), "has a viewBox: " + latex);
    }

    @Test
    void everyUpgreekCommandRendersARealGlyph() {
        for (String name : UPGREEK.keySet()) {
            assertRendersGlyphs("\\" + name);
        }
    }

    @Test
    void upgreekRendersInRealisticPhysicsExpressions() {
        assertRendersGlyphs("\\uptau_{\\uprho} = \\upbeta \\cdot \\upmu");
        assertRendersGlyphs("m = 5.2\\,\\upmu\\mathrm{g}");
        assertRendersGlyphs("\\upvarepsilon \\upvartheta \\upvarpi \\upvarrho "
            + "\\upvarsigma \\upvarphi");
        assertRendersGlyphs("\\Upomega = \\Uppi \\Updelta \\Upsigma");
    }

    // ------------------------------------------------------------------
    // The typed command authority owns these names.
    // ------------------------------------------------------------------

    @Test
    void theCommandRegistryOwnsEveryUpgreekNameAsAGreekSymbol() {
        for (String name : UPGREEK.keySet()) {
            Descriptor descriptor = CommandRegistry.get(name);
            assertNotNull(descriptor, "no descriptor for \\" + name);
            assertEquals(Handler.SYMBOL, descriptor.handler(), "\\" + name);
            assertEquals(Category.GREEK, descriptor.category(),
                "\\" + name + " belongs in the Greek section of the symbol index");
            assertEquals("\\" + name, descriptor.indexExample(), "\\" + name);
            assertTrue(MathParser.supportedCommands().stream()
                .anyMatch(command -> command.command().equals("\\" + name)),
                "generated coverage must include \\" + name);
        }
    }

    // ------------------------------------------------------------------
    // Negative controls: names upgreek does NOT define stay unknown.
    // ------------------------------------------------------------------

    @Test
    void namesUpgreekDoesNotDefineStayUnknown() {
        // \Alpha/\Beta/... do not exist in LaTeX (they are Latin A, B), so their
        // upright forms must not exist either; \varkappa and \digamma have no
        // upgreek command. Accepting these would mean the family was generated by
        // pattern rather than by the package's actual contents.
        for (String absent : List.of("Upalpha", "Upbeta", "Upeta", "Upiota", "Upomicron",
                "Uprho", "Upzeta", "Upchi", "Upkappa", "Upmu", "Upnu", "Uptau",
                "upvarkappa", "updigamma", "upvarupsilon")) {
            assertThrows(MathSyntaxException.class, () -> MathParser.parse("\\" + absent),
                "\\" + absent + " is not an upgreek command and must fail loud");
        }
    }

    @Test
    void theExistingSlantedGreekCommandsAreUntouched() {
        // \\upsilon is the Greek letter, not an "up-silon"; adding the upgreek
        // family must not have shadowed any pre-existing name.
        assertEquals(0x03C5, ((Atom) MathParser.parse("\\upsilon")).codePoint());
        assertEquals(0x03A5, ((Atom) MathParser.parse("\\Upsilon")).codePoint());
        assertEquals(0x228E, ((Atom) MathParser.parse("\\uplus")).codePoint());
        assertEquals(0x2191, ((Atom) MathParser.parse("\\uparrow")).codePoint());
        assertEquals(0x03F0, ((Atom) MathParser.parse("\\varkappa")).codePoint());
    }

    // ------------------------------------------------------------------
    // FONT TRUTH. The claim "the base Greek block is the upright alphabet" is
    // measured on the bundled outlines, not assumed.
    // ------------------------------------------------------------------

    // Stem-angle instrument tuning. A "stem" is a STRAIGHT outline segment (both
    // endpoints on-curve) that runs at least MIN_STEM_HEIGHT of the glyph's height
    // and leans no more than MAX_STEM_LEAN horizontally — i.e. an upright or
    // italic stroke, never a bowl or an 'A'-style diagonal. Readings are trusted
    // only when a glyph's stems AGREE to within STEM_AGREEMENT; a glyph whose
    // near-vertical segments disagree is a diagonal design (Α, Δ, Λ) and is
    // reported unmeasurable rather than guessed at.
    private static final double MIN_STEM_HEIGHT = 0.20;
    private static final double MAX_STEM_LEAN = 0.35;
    private static final double STEM_AGREEMENT = 0.12;

    /** An upright reading; anything at or above this is a slanted design. */
    private static final double UPRIGHT_CEILING = 0.10;

    /**
     * The median lean (dx/dy) of a glyph's stems, or empty when the glyph has no
     * stem this instrument will vouch for. Zero is a perfectly vertical stroke;
     * STIX Two Math's math-italic stems sit around +0.20.
     */
    private static java.util.OptionalDouble stemLean(SfntFont font, int codePoint) {
        int gid = font.glyphId(codePoint);
        assertTrue(gid > 0, "no glyph for U+%04X".formatted(codePoint));
        GlyphOutline outline = font.outline(gid);
        if (outline.isEmpty()) {
            return java.util.OptionalDouble.empty();
        }
        double height = outline.yMax() - outline.yMin();
        if (height <= 0) {
            return java.util.OptionalDouble.empty();
        }
        List<Double> leans = new ArrayList<>();
        for (Contour contour : outline.contours()) {
            List<GlyphPoint> points = contour.points();
            for (int i = 0; i < points.size(); i++) {
                GlyphPoint a = points.get(i);
                GlyphPoint b = points.get((i + 1) % points.size());
                if (!a.onCurve() || !b.onCurve()) {
                    continue; // not a straight segment
                }
                double dx = b.x() - a.x();
                double dy = b.y() - a.y();
                if (Math.abs(dy) < MIN_STEM_HEIGHT * height) {
                    continue;
                }
                if (Math.abs(dx) > MAX_STEM_LEAN * Math.abs(dy)) {
                    continue;
                }
                leans.add(dx / dy);
            }
        }
        if (leans.isEmpty()) {
            return java.util.OptionalDouble.empty();
        }
        java.util.Collections.sort(leans);
        if (leans.get(leans.size() - 1) - leans.get(0) > STEM_AGREEMENT) {
            return java.util.OptionalDouble.empty(); // diagonals, not stems
        }
        return java.util.OptionalDouble.of(leans.get(leans.size() / 2));
    }

    @Test
    void theStemInstrumentSeparatesKnownUprightFromKnownSlantedGlyphs() {
        // POSITIVE AND NEGATIVE CONTROL for the measurement itself, on an alphabet
        // whose posture is not in question. Positive: math-italic Latin must read
        // as slanted. Negative: upright Latin AND math-BOLD Latin must both read
        // as upright — bold proves the instrument is measuring slant and not
        // weight or width, which a naive centroid measure fails.
        SfntFont font = SfntFont.loadBundled();
        int measured = 0;
        for (int letter = 'a'; letter <= 'z'; letter++) {
            java.util.OptionalDouble upright = stemLean(font, letter);
            java.util.OptionalDouble italic =
                stemLean(font, MathVariant.map(MathVariant.Style.ITALIC, letter));
            java.util.OptionalDouble bold =
                stemLean(font, MathVariant.map(MathVariant.Style.BOLD, letter));
            if (upright.isEmpty() || italic.isEmpty() || bold.isEmpty()) {
                continue;
            }
            measured++;
            String at = " ('%c' upright=%.4f italic=%.4f bold=%.4f)"
                .formatted(letter, upright.getAsDouble(), italic.getAsDouble(),
                    bold.getAsDouble());
            assertTrue(Math.abs(upright.getAsDouble()) < UPRIGHT_CEILING,
                "upright Latin must read upright" + at);
            assertTrue(Math.abs(bold.getAsDouble()) < UPRIGHT_CEILING,
                "math-bold Latin is upright too — the instrument must not read weight "
                + "as slant" + at);
            assertTrue(italic.getAsDouble() > UPRIGHT_CEILING,
                "math-italic Latin must read slanted" + at);
        }
        assertTrue(measured >= 15,
            "the control is vacuous if almost nothing is measurable: " + measured + " letters");
    }

    @Test
    void baseGreekBlockIsUprightAndMathItalicGreekIsSlanted() {
        // THE CLAIM THESE MAPPINGS REST ON. Every upgreek command points into the
        // Greek and Coptic block; the differential below is what makes "upright"
        // a measured fact about the bundled font rather than an assumption. Many
        // Greek letters are drawn with no straight stem at all (α, ο, σ, ω …), so
        // the differential runs over the letters that can carry it, with a floor
        // so a font change that makes everything unmeasurable cannot pass silently.
        SfntFont font = SfntFont.loadBundled();
        List<String> slanted = new ArrayList<>();
        int measured = 0;
        for (String name : UPGREEK.keySet()) {
            int upright = shippedCodePoint(name);
            int italic = MathVariant.map(MathVariant.Style.ITALIC, upright);
            assertTrue(italic != upright,
                "no math-italic counterpart for U+%04X — the differential would be vacuous"
                    .formatted(upright));
            java.util.OptionalDouble uprightLean = stemLean(font, upright);
            java.util.OptionalDouble italicLean = stemLean(font, italic);
            if (uprightLean.isEmpty() || italicLean.isEmpty()) {
                continue;
            }
            measured++;
            if (Math.abs(uprightLean.getAsDouble()) >= italicLean.getAsDouble() - UPRIGHT_CEILING) {
                slanted.add("\\%s U+%04X lean=%.4f vs math-italic U+%04X lean=%.4f"
                    .formatted(name, upright, uprightLean.getAsDouble(), italic,
                        italicLean.getAsDouble()));
            }
        }
        assertTrue(measured >= 8,
            "too few upgreek letters carried a stem reading (" + measured + ") — the "
            + "upright claim would be untested; re-tune the instrument, do not weaken it");
        assertTrue(slanted.isEmpty(),
            "the bundled font's Greek and Coptic block is no longer the UPRIGHT alphabet "
            + "these upgreek commands assume — remap the family or drop it, never ship a "
            + "silently slanted \\upalpha: " + slanted);
    }

    @Test
    void everyUpgreekCodePointHasARealStixGlyph() {
        // Redundant with SymbolCoverageTest by construction (these code points are
        // already carried by \alpha etc.), and kept anyway so this family fails on
        // its own terms if it is ever remapped to a code point STIX lacks.
        SfntFont font = SfntFont.loadBundled();
        List<String> gaps = new ArrayList<>();
        for (String name : UPGREEK.keySet()) {
            int cp = shippedCodePoint(name);
            if (font.glyphId(cp) == 0) {
                gaps.add("\\%s (U+%04X)".formatted(name, cp));
            }
        }
        assertTrue(gaps.isEmpty(), "STIX Two Math has no glyph for: " + gaps);
        // Positive-and-negative control on the instrument: glyphId must return 0
        // for a code point STIX genuinely lacks, or "no gaps" means nothing.
        assertEquals(0, font.glyphId(0x10FFFD),
            "glyphId must report a real absence as 0 — otherwise this scan is blind");
        assertTrue(font.glyphId(0x03B1) > 0, "and must report a present glyph as non-zero");
    }
}
