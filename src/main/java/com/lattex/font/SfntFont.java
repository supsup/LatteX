package com.lattex.font;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, clean-room reader for the sfnt (TrueType/{@code glyf}) font
 * container. Written from the public OpenType/sfnt specification; parses only
 * the tables the LatteX walking skeleton needs: {@code head}, {@code maxp},
 * {@code hhea}, {@code hmtx}, {@code cmap} (formats 4 and 12), {@code loca},
 * {@code glyf}, and {@code MATH}.
 *
 * <p>All multi-byte integers in sfnt are big-endian. Font design coordinates
 * are y-up; callers flip to SVG's y-down space at layout/emit time.
 *
 * <p>This reads the TrueType/{@code glyf} outline variant (quadratic Béziers),
 * not the CFF/OTF variant.
 */
public final class SfntFont {

    /** Classpath resource name, resolved relative to this class's package. */
    private static final String RESOURCE = "STIXTwoMath-Regular.ttf";

    private final byte[] data;
    private final Map<String, Integer> tableOffset = new HashMap<>();
    private final Map<String, Integer> tableLength = new HashMap<>();

    private final int unitsPerEm;
    private final int numGlyphs;
    private final int numberOfHMetrics;
    private final int[] loca;          // numGlyphs + 1 byte offsets into glyf
    private final MathConstants mathConstants;

    // Selected Unicode cmap subtable.
    private final int cmapSubtableOffset;
    private final int cmapFormat;

    private SfntFont(byte[] data) {
        this.data = data;

        int sfntVersion = (int) u32(0);
        if (sfntVersion != 0x00010000) {
            // 'OTTO' (0x4F54544F) would be the CFF variant, which we do not parse.
            throw new IllegalArgumentException(
                "Not a TrueType/glyf sfnt (sfntVersion=0x%08X)".formatted(sfntVersion));
        }
        int numTables = u16(4);
        int p = 12;
        for (int i = 0; i < numTables; i++) {
            String tag = new String(data, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int off = (int) u32(p + 8);
            int len = (int) u32(p + 12);
            tableOffset.put(tag, off);
            tableLength.put(tag, len);
            p += 16;
        }
        require("head", "maxp", "hhea", "hmtx", "cmap", "loca", "glyf", "MATH");

        int head = tableOffset.get("head");
        this.unitsPerEm = u16(head + 18);
        int indexToLocFormat = s16(head + 50);

        this.numGlyphs = u16(tableOffset.get("maxp") + 4);
        this.numberOfHMetrics = u16(tableOffset.get("hhea") + 34);

        this.loca = readLoca(indexToLocFormat);
        int[] chosen = selectCmap();
        this.cmapSubtableOffset = chosen[0];
        this.cmapFormat = chosen[1];
        this.mathConstants = readMathConstants();
    }

    // -- public API ---------------------------------------------------------

    /** Loads the bundled STIX Two Math font from the classpath. */
    public static SfntFont loadBundled() {
        try (InputStream in = SfntFont.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled font resource not found: " + RESOURCE);
            }
            return new SfntFont(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled font", e);
        }
    }

    public int unitsPerEm() {
        return unitsPerEm;
    }

    public int numGlyphs() {
        return numGlyphs;
    }

    public MathConstants mathConstants() {
        return mathConstants;
    }

    /** Maps a Unicode code point to a glyph id, or 0 (.notdef) if unmapped. */
    public int glyphId(int codePoint) {
        return switch (cmapFormat) {
            case 4 -> cmap4(codePoint);
            case 12 -> cmap12(codePoint);
            default -> throw new IllegalStateException("No usable cmap subtable");
        };
    }

    /** Horizontal advance width of a glyph, in font design units. */
    public int advanceWidth(int glyphId) {
        int hmtx = tableOffset.get("hmtx");
        int idx = Math.min(glyphId, numberOfHMetrics - 1);
        return u16(hmtx + idx * 4);
    }

    /**
     * Left-side bearing of a glyph, in font design units, from {@code hmtx}.
     *
     * <p>The first {@code numberOfHMetrics} glyphs carry a full
     * {@code longHorMetric} (advance + lsb); trailing glyphs share the last
     * advance and store only an lsb in the {@code leftSideBearings} array.
     */
    public int leftSideBearing(int glyphId) {
        if (glyphId < 0 || glyphId >= numGlyphs) {
            throw new IndexOutOfBoundsException("glyphId " + glyphId);
        }
        int hmtx = tableOffset.get("hmtx");
        if (glyphId < numberOfHMetrics) {
            return s16(hmtx + glyphId * 4 + 2);
        }
        int trailing = glyphId - numberOfHMetrics;
        return s16(hmtx + numberOfHMetrics * 4 + trailing * 2);
    }

    /**
     * Italic correction of a glyph, in font design units, from the MATH table's
     * {@code MathItalicsCorrectionInfo}. Returns 0 when the glyph has no entry
     * (or the font provides no italics-correction info).
     */
    public int italicCorrection(int glyphId) {
        int math = tableOffset.get("MATH");
        int glyphInfoRel = u16(math + 6);
        if (glyphInfoRel == 0) {
            return 0;
        }
        int glyphInfo = math + glyphInfoRel;
        int italicInfoRel = u16(glyphInfo); // first offset: mathItalicsCorrectionInfo
        if (italicInfoRel == 0) {
            return 0;
        }
        int italicInfo = glyphInfo + italicInfoRel;
        int coverageRel = u16(italicInfo);
        int count = u16(italicInfo + 2);
        if (coverageRel == 0 || count == 0) {
            return 0;
        }
        int idx = coverageIndex(italicInfo + coverageRel, glyphId);
        if (idx < 0 || idx >= count) {
            return 0;
        }
        // MathValueRecord[count] begins at italicInfo + 4; record = {int16 value, Offset16 device}.
        return s16(italicInfo + 4 + idx * 4);
    }

    /**
     * Top-accent attachment point of a glyph (horizontal position, design
     * units), from the MATH table's {@code MathTopAccentAttachment}. This is the
     * x at which an accent centres over the glyph. Returns 0 when the glyph has
     * no entry.
     *
     * <p>Structurally identical to {@code MathItalicsCorrectionInfo}: a Coverage
     * plus a parallel {@code MathValueRecord} array.
     */
    public int topAccentAttachment(int glyphId) {
        int glyphInfo = mathGlyphInfo();
        if (glyphInfo < 0) {
            return 0;
        }
        int rel = u16(glyphInfo + 2); // mathTopAccentAttachment
        if (rel == 0) {
            return 0;
        }
        int t = glyphInfo + rel;
        int coverageRel = u16(t);
        int count = u16(t + 2);
        if (coverageRel == 0 || count == 0) {
            return 0;
        }
        int idx = coverageIndex(t + coverageRel, glyphId);
        if (idx < 0 || idx >= count) {
            return 0;
        }
        return s16(t + 4 + idx * 4);
    }

    /**
     * Per-glyph math kern staircases (the four corners), from the MATH table's
     * {@code MathKernInfo}. Returns {@code null} when the glyph has no kern
     * record (or the font provides no kern info). Individual corners inside the
     * returned {@link MathKernInfo} may also be {@code null}.
     */
    public MathKernInfo mathKernInfo(int glyphId) {
        int glyphInfo = mathGlyphInfo();
        if (glyphInfo < 0) {
            return null;
        }
        int rel = u16(glyphInfo + 6); // mathKernInfo
        if (rel == 0) {
            return null;
        }
        int t = glyphInfo + rel;
        int coverageRel = u16(t);
        int count = u16(t + 2);
        if (coverageRel == 0 || count == 0) {
            return null;
        }
        int idx = coverageIndex(t + coverageRel, glyphId);
        if (idx < 0 || idx >= count) {
            return null;
        }
        // MathKernInfoRecord[count] at t+4: four Offset16 (topRight, topLeft,
        // bottomRight, bottomLeft), each relative to the MathKernInfo table (t).
        int rec = t + 4 + idx * 8;
        return new MathKernInfo(
            readMathKern(t, u16(rec)),
            readMathKern(t, u16(rec + 2)),
            readMathKern(t, u16(rec + 4)),
            readMathKern(t, u16(rec + 6)));
    }

    private MathKern readMathKern(int base, int rel) {
        if (rel == 0) {
            return null;
        }
        int k = base + rel;
        int heightCount = u16(k);
        List<Integer> heights = new ArrayList<>(heightCount);
        for (int i = 0; i < heightCount; i++) {
            heights.add(s16(k + 2 + i * 4)); // MathValueRecord value
        }
        int kernBase = k + 2 + heightCount * 4;
        List<Integer> kerns = new ArrayList<>(heightCount + 1);
        for (int i = 0; i <= heightCount; i++) {
            kerns.add(s16(kernBase + i * 4));
        }
        return new MathKern(heights, kerns);
    }

    /**
     * Vertical stretch construction for a glyph (tall delimiters, radicals,
     * braces), from {@code MathVariants.vertGlyphConstruction}. Returns
     * {@code null} when the glyph has no vertical construction.
     */
    public MathGlyphConstruction verticalVariants(int glyphId) {
        return glyphConstruction(glyphId, true);
    }

    /**
     * Horizontal stretch construction for a glyph (wide arrows, over/underbraces),
     * from {@code MathVariants.horizGlyphConstruction}. Returns {@code null} when
     * the glyph has no horizontal construction.
     */
    public MathGlyphConstruction horizontalVariants(int glyphId) {
        return glyphConstruction(glyphId, false);
    }

    /** The minimum overlap (design units) between adjacent assembly parts. */
    public int minConnectorOverlap() {
        int mv = mathVariants();
        return mv < 0 ? 0 : u16(mv);
    }

    private MathGlyphConstruction glyphConstruction(int glyphId, boolean vertical) {
        int mv = mathVariants();
        if (mv < 0) {
            return null;
        }
        // MathVariants header:
        //   uint16   minConnectorOverlap        (mv+0)
        //   Offset16 vertGlyphCoverage          (mv+2)
        //   Offset16 horizGlyphCoverage         (mv+4)
        //   uint16   vertGlyphCount             (mv+6)
        //   uint16   horizGlyphCount            (mv+8)
        //   Offset16 vertGlyphConstruction[vertGlyphCount]   (mv+10)
        //   Offset16 horizGlyphConstruction[horizGlyphCount] (after vert array)
        int vertCount = u16(mv + 6);
        int coverageRel = vertical ? u16(mv + 2) : u16(mv + 4);
        int count = vertical ? vertCount : u16(mv + 8);
        if (coverageRel == 0 || count == 0) {
            return null;
        }
        int idx = coverageIndex(mv + coverageRel, glyphId);
        if (idx < 0 || idx >= count) {
            return null;
        }
        int arrayBase = mv + 10 + (vertical ? 0 : vertCount * 2);
        int constrRel = u16(arrayBase + idx * 2);
        if (constrRel == 0) {
            return null;
        }
        return readGlyphConstruction(mv + constrRel);
    }

    private MathGlyphConstruction readGlyphConstruction(int gc) {
        // MathGlyphConstruction:
        //   Offset16 glyphAssembly (may be 0)   (gc+0)  — relative to gc
        //   uint16   variantCount               (gc+2)
        //   MathGlyphVariantRecord[variantCount] (gc+4): {uint16 glyph, uint16 advance}
        int assemblyRel = u16(gc);
        int variantCount = u16(gc + 2);
        List<MathGlyphVariant> variants = new ArrayList<>(variantCount);
        for (int i = 0; i < variantCount; i++) {
            int b = gc + 4 + i * 4;
            variants.add(new MathGlyphVariant(u16(b), u16(b + 2)));
        }
        GlyphAssembly assembly = assemblyRel == 0 ? null : readAssembly(gc + assemblyRel);
        return new MathGlyphConstruction(variants, assembly);
    }

    private GlyphAssembly readAssembly(int a) {
        // GlyphAssembly:
        //   MathValueRecord italicsCorrection   (a+0)  value at a+0
        //   uint16 partCount                    (a+4)
        //   GlyphPartRecord[partCount]          (a+6): five uint16 (10 bytes each)
        int italics = s16(a);
        int partCount = u16(a + 4);
        List<GlyphPart> parts = new ArrayList<>(partCount);
        for (int i = 0; i < partCount; i++) {
            int b = a + 6 + i * 10;
            parts.add(new GlyphPart(u16(b), u16(b + 2), u16(b + 4), u16(b + 6), u16(b + 8)));
        }
        return new GlyphAssembly(italics, parts);
    }

    /** Absolute offset of the {@code MathGlyphInfo} sub-table, or -1 if absent. */
    private int mathGlyphInfo() {
        int math = tableOffset.get("MATH");
        int rel = u16(math + 6);
        return rel == 0 ? -1 : math + rel;
    }

    /** Absolute offset of the {@code MathVariants} sub-table, or -1 if absent. */
    private int mathVariants() {
        int math = tableOffset.get("MATH");
        int rel = u16(math + 8);
        return rel == 0 ? -1 : math + rel;
    }

    /**
     * Parses a glyph's outline from {@code loca}/{@code glyf}. Handles both
     * simple glyphs (quadratic contours) and composite glyphs (components
     * assembled from other glyphs via affine transforms, recursively).
     */
    public GlyphOutline outline(int glyphId) {
        if (glyphId < 0 || glyphId >= numGlyphs) {
            throw new IndexOutOfBoundsException("glyphId " + glyphId);
        }
        return outline(glyphId, 0);
    }

    /** Maximum composite-component nesting depth (guards against cyclic glyphs). */
    private static final int MAX_COMPOSITE_DEPTH = 8;

    private GlyphOutline outline(int glyphId, int depth) {
        int start = loca[glyphId];
        int end = loca[glyphId + 1];
        int glyf = tableOffset.get("glyf");
        if (end <= start) {
            // No outline data (e.g. whitespace glyph): empty outline, zero bbox.
            return new GlyphOutline(List.of(), 0, 0, 0, 0);
        }
        int g = glyf + start;
        int numberOfContours = s16(g);
        int xMin = s16(g + 2);
        int yMin = s16(g + 4);
        int xMax = s16(g + 6);
        int yMax = s16(g + 8);
        List<Contour> contours = numberOfContours >= 0
            ? readSimpleContours(g, numberOfContours)
            : readCompositeContours(g, glyphId, depth);
        return new GlyphOutline(contours, xMin, yMin, xMax, yMax);
    }

    /** Reads the contours of a simple (non-composite) {@code glyf} glyph. */
    private List<Contour> readSimpleContours(int g, int numberOfContours) {
        int pos = g + 10;

        int[] endPts = new int[numberOfContours];
        for (int i = 0; i < numberOfContours; i++) {
            endPts[i] = u16(pos);
            pos += 2;
        }
        int numPoints = numberOfContours == 0 ? 0 : endPts[numberOfContours - 1] + 1;

        int instructionLength = u16(pos);
        pos += 2 + instructionLength;

        // Flags (with the REPEAT_FLAG run-length encoding).
        int[] flags = new int[numPoints];
        for (int i = 0; i < numPoints; ) {
            int f = u8(pos++);
            flags[i++] = f;
            if ((f & 0x08) != 0) { // REPEAT_FLAG
                int repeat = u8(pos++);
                while (repeat-- > 0 && i < numPoints) {
                    flags[i++] = f;
                }
            }
        }

        // X coordinates (delta-encoded).
        int[] xs = new int[numPoints];
        int x = 0;
        for (int i = 0; i < numPoints; i++) {
            int f = flags[i];
            if ((f & 0x02) != 0) { // X_SHORT_VECTOR: 1 byte, sign from bit 0x10
                int dx = u8(pos++);
                x += (f & 0x10) != 0 ? dx : -dx;
            } else if ((f & 0x10) == 0) { // not short, not "same": signed 2-byte delta
                x += s16(pos);
                pos += 2;
            } // else: X_IS_SAME → delta 0
            xs[i] = x;
        }

        // Y coordinates (delta-encoded).
        int[] ys = new int[numPoints];
        int y = 0;
        for (int i = 0; i < numPoints; i++) {
            int f = flags[i];
            if ((f & 0x04) != 0) { // Y_SHORT_VECTOR: 1 byte, sign from bit 0x20
                int dy = u8(pos++);
                y += (f & 0x20) != 0 ? dy : -dy;
            } else if ((f & 0x20) == 0) {
                y += s16(pos);
                pos += 2;
            }
            ys[i] = y;
        }

        // Split the flat point list into contours.
        List<Contour> contours = new ArrayList<>(numberOfContours);
        int startPt = 0;
        for (int c = 0; c < numberOfContours; c++) {
            int endPt = endPts[c];
            List<GlyphPoint> pts = new ArrayList<>(endPt - startPt + 1);
            for (int i = startPt; i <= endPt; i++) {
                pts.add(new GlyphPoint(xs[i], ys[i], (flags[i] & 0x01) != 0));
            }
            contours.add(new Contour(pts));
            startPt = endPt + 1;
        }
        return contours;
    }

    // Composite-glyph component flags (OpenType glyf spec).
    private static final int ARG_1_AND_2_ARE_WORDS   = 0x0001;
    private static final int ARGS_ARE_XY_VALUES      = 0x0002;
    private static final int ROUND_XY_TO_GRID        = 0x0004;
    private static final int WE_HAVE_A_SCALE         = 0x0008;
    private static final int MORE_COMPONENTS         = 0x0020;
    private static final int WE_HAVE_AN_X_AND_Y_SCALE = 0x0040;
    private static final int WE_HAVE_A_TWO_BY_TWO    = 0x0080;
    private static final int WE_HAVE_INSTRUCTIONS    = 0x0100;
    private static final int SCALED_COMPONENT_OFFSET = 0x0800;

    /**
     * Reads a composite glyph: a chain of component records, each naming another
     * glyph plus an affine transform (offset + optional scale / 2×2). Component
     * outlines are fetched recursively and transformed into this glyph's space,
     * per the OpenType {@code glyf} composite-description spec.
     */
    private List<Contour> readCompositeContours(int g, int glyphId, int depth) {
        if (depth > MAX_COMPOSITE_DEPTH) {
            throw new IllegalStateException(
                "Composite glyph nesting too deep at glyphId " + glyphId
                + " (possible cycle)");
        }
        List<Contour> out = new ArrayList<>();
        int pos = g + 10;
        boolean more = true;
        while (more) {
            int flags = u16(pos);
            int componentGlyph = u16(pos + 2);
            pos += 4;

            int arg1;
            int arg2;
            if ((flags & ARG_1_AND_2_ARE_WORDS) != 0) {
                arg1 = s16(pos);
                arg2 = s16(pos + 2);
                pos += 4;
            } else {
                arg1 = (byte) u8(pos);      // int8 when XY values
                arg2 = (byte) u8(pos + 1);
                pos += 2;
            }

            // 2×2 transform, defaulting to identity. F2Dot14 fixed-point.
            double a = 1.0;
            double b = 0.0;
            double c = 0.0;
            double d = 1.0;
            if ((flags & WE_HAVE_A_SCALE) != 0) {
                a = d = f2dot14(pos);
                pos += 2;
            } else if ((flags & WE_HAVE_AN_X_AND_Y_SCALE) != 0) {
                a = f2dot14(pos);
                d = f2dot14(pos + 2);
                pos += 4;
            } else if ((flags & WE_HAVE_A_TWO_BY_TWO) != 0) {
                a = f2dot14(pos);
                b = f2dot14(pos + 2);
                c = f2dot14(pos + 4);
                d = f2dot14(pos + 6);
                pos += 8;
            }

            double dx = 0.0;
            double dy = 0.0;
            if ((flags & ARGS_ARE_XY_VALUES) != 0) {
                dx = arg1;
                dy = arg2;
                // SCALED_COMPONENT_OFFSET: pre-transform the offset by the 2×2.
                // (Default/UNSCALED, as used by OpenType/MS, leaves it as-is.)
                if ((flags & SCALED_COMPONENT_OFFSET) != 0) {
                    double ox = dx;
                    double oy = dy;
                    dx = a * ox + c * oy;
                    dy = b * ox + d * oy;
                }
                if ((flags & ROUND_XY_TO_GRID) != 0) {
                    dx = Math.round(dx);
                    dy = Math.round(dy);
                }
            }
            // else: args are point-match indices (rare in math fonts); we do not
            // reposition by matched points, which would need the assembled-so-far
            // point set — components in STIX Two Math use XY offsets.

            for (Contour ct : outline(componentGlyph, depth + 1).contours()) {
                List<GlyphPoint> tp = new ArrayList<>(ct.points().size());
                for (GlyphPoint p : ct.points()) {
                    double px = p.x();
                    double py = p.y();
                    int nx = (int) Math.round(a * px + c * py + dx);
                    int ny = (int) Math.round(b * px + d * py + dy);
                    tp.add(new GlyphPoint(nx, ny, p.onCurve()));
                }
                out.add(new Contour(tp));
            }
            more = (flags & MORE_COMPONENTS) != 0;
        }
        return out;
    }

    /**
     * The component glyph ids referenced by a composite glyph, in order. Empty
     * for a simple glyph or a glyph with no outline. Intended for inspection and
     * tests; {@link #outline(int)} does the actual assembly.
     */
    public List<Integer> compositeComponents(int glyphId) {
        if (glyphId < 0 || glyphId >= numGlyphs) {
            throw new IndexOutOfBoundsException("glyphId " + glyphId);
        }
        int start = loca[glyphId];
        int end = loca[glyphId + 1];
        if (end <= start) {
            return List.of();
        }
        int g = tableOffset.get("glyf") + start;
        if (s16(g) >= 0) {
            return List.of(); // simple glyph
        }
        List<Integer> comps = new ArrayList<>();
        int pos = g + 10;
        boolean more = true;
        while (more) {
            int flags = u16(pos);
            comps.add(u16(pos + 2));
            pos += 4;
            pos += (flags & ARG_1_AND_2_ARE_WORDS) != 0 ? 4 : 2; // args
            if ((flags & WE_HAVE_A_SCALE) != 0) {
                pos += 2;
            } else if ((flags & WE_HAVE_AN_X_AND_Y_SCALE) != 0) {
                pos += 4;
            } else if ((flags & WE_HAVE_A_TWO_BY_TWO) != 0) {
                pos += 8;
            }
            more = (flags & MORE_COMPONENTS) != 0;
        }
        return comps;
    }

    // -- table parsing helpers ----------------------------------------------

    private int[] readLoca(int indexToLocFormat) {
        int locaOff = tableOffset.get("loca");
        int[] offsets = new int[numGlyphs + 1];
        if (indexToLocFormat == 0) { // short: uint16, stored as (offset / 2)
            for (int i = 0; i <= numGlyphs; i++) {
                offsets[i] = u16(locaOff + i * 2) * 2;
            }
        } else { // long: uint32
            for (int i = 0; i <= numGlyphs; i++) {
                offsets[i] = (int) u32(locaOff + i * 4);
            }
        }
        return offsets;
    }

    private MathConstants readMathConstants() {
        int math = tableOffset.get("MATH");
        int constRel = u16(math + 4); // header: major(2) minor(2) mathConstants(2) ...
        int c = math + constRel;
        // MathConstants layout (OpenType MATH spec). Four leading scalars, then a
        // run of MathValueRecords (4 bytes each: int16 value + Offset16 device;
        // we keep only the value), then one trailing int16 percentage.
        //   c+0  int16  scriptPercentScaleDown
        //   c+2  int16  scriptScriptPercentScaleDown
        //   c+4  uint16 delimitedSubFormulaMinHeight
        //   c+6  uint16 displayOperatorMinHeight
        //   c+8  MathValueRecord[51]   (value of record i at c+8+i*4)
        //   c+212 int16 radicalDegreeBottomRaisePercent
        int mv = c + 8; // base of the MathValueRecord run
        return new MathConstants(
            s16(c),          // scriptPercentScaleDown
            s16(c + 2),      // scriptScriptPercentScaleDown
            u16(c + 4),      // delimitedSubFormulaMinHeight
            u16(c + 6),      // displayOperatorMinHeight
            mvr(mv, 0),      // mathLeading
            mvr(mv, 1),      // axisHeight
            mvr(mv, 2),      // accentBaseHeight
            mvr(mv, 3),      // flattenedAccentBaseHeight
            mvr(mv, 4),      // subscriptShiftDown
            mvr(mv, 5),      // subscriptTopMax
            mvr(mv, 6),      // subscriptBaselineDropMin
            mvr(mv, 7),      // superscriptShiftUp
            mvr(mv, 8),      // superscriptShiftUpCramped
            mvr(mv, 9),      // superscriptBottomMin
            mvr(mv, 10),     // superscriptBaselineDropMax
            mvr(mv, 11),     // subSuperscriptGapMin
            mvr(mv, 12),     // superscriptBottomMaxWithSubscript
            mvr(mv, 13),     // spaceAfterScript
            mvr(mv, 14),     // upperLimitGapMin
            mvr(mv, 15),     // upperLimitBaselineRiseMin
            mvr(mv, 16),     // lowerLimitGapMin
            mvr(mv, 17),     // lowerLimitBaselineDropMin
            mvr(mv, 18),     // stackTopShiftUp
            mvr(mv, 19),     // stackTopDisplayStyleShiftUp
            mvr(mv, 20),     // stackBottomShiftDown
            mvr(mv, 21),     // stackBottomDisplayStyleShiftDown
            mvr(mv, 22),     // stackGapMin
            mvr(mv, 23),     // stackDisplayStyleGapMin
            mvr(mv, 24),     // stretchStackTopShiftUp
            mvr(mv, 25),     // stretchStackBottomShiftDown
            mvr(mv, 26),     // stretchStackGapAboveMin
            mvr(mv, 27),     // stretchStackGapBelowMin
            mvr(mv, 28),     // fractionNumeratorShiftUp
            mvr(mv, 29),     // fractionNumeratorDisplayStyleShiftUp
            mvr(mv, 30),     // fractionDenominatorShiftDown
            mvr(mv, 31),     // fractionDenominatorDisplayStyleShiftDown
            mvr(mv, 32),     // fractionNumeratorGapMin
            mvr(mv, 33),     // fractionNumDisplayStyleGapMin
            mvr(mv, 34),     // fractionRuleThickness
            mvr(mv, 35),     // fractionDenominatorGapMin
            mvr(mv, 36),     // fractionDenomDisplayStyleGapMin
            mvr(mv, 37),     // skewedFractionHorizontalGap
            mvr(mv, 38),     // skewedFractionVerticalGap
            mvr(mv, 39),     // overbarVerticalGap
            mvr(mv, 40),     // overbarRuleThickness
            mvr(mv, 41),     // overbarExtraAscender
            mvr(mv, 42),     // underbarVerticalGap
            mvr(mv, 43),     // underbarRuleThickness
            mvr(mv, 44),     // underbarExtraDescender
            mvr(mv, 45),     // radicalVerticalGap
            mvr(mv, 46),     // radicalDisplayStyleVerticalGap
            mvr(mv, 47),     // radicalRuleThickness
            mvr(mv, 48),     // radicalExtraAscender
            mvr(mv, 49),     // radicalKernBeforeDegree
            mvr(mv, 50),     // radicalKernAfterDegree
            s16(mv + 51 * 4) // radicalDegreeBottomRaisePercent (trailing int16)
        );
    }

    /** Value of the {@code i}-th {@code MathValueRecord} in a run based at {@code base}. */
    private int mvr(int base, int i) {
        return s16(base + i * 4);
    }

    /**
     * Selects a Unicode cmap subtable, preferring a format-12 (full Unicode)
     * table, then a format-4 (BMP) table. Returns {@code {subtableOffset, format}}.
     */
    private int[] selectCmap() {
        int cmap = tableOffset.get("cmap");
        int numTables = u16(cmap + 2);
        int best4 = -1;
        int best12 = -1;
        for (int i = 0; i < numTables; i++) {
            int rec = cmap + 4 + i * 8;
            int platformId = u16(rec);
            int encodingId = u16(rec + 2);
            int subOff = cmap + (int) u32(rec + 4);
            int format = u16(subOff);
            boolean unicode = platformId == 0
                || (platformId == 3 && (encodingId == 1 || encodingId == 10));
            if (!unicode) {
                continue;
            }
            if (format == 12 && best12 < 0) {
                best12 = subOff;
            } else if (format == 4 && best4 < 0) {
                best4 = subOff;
            }
        }
        if (best12 >= 0) {
            return new int[] {best12, 12};
        }
        if (best4 >= 0) {
            return new int[] {best4, 4};
        }
        throw new IllegalStateException("No Unicode cmap format 4 or 12 subtable found");
    }

    private int cmap4(int c) {
        if (c > 0xFFFF) {
            return 0;
        }
        int t = cmapSubtableOffset;
        int segCountX2 = u16(t + 6);
        int segCount = segCountX2 / 2;
        int endCodes = t + 14;
        int startCodes = endCodes + segCountX2 + 2; // + reservedPad
        int idDeltas = startCodes + segCountX2;
        int idRangeOffsets = idDeltas + segCountX2;
        for (int i = 0; i < segCount; i++) {
            int end = u16(endCodes + i * 2);
            if (c > end) {
                continue;
            }
            int start = u16(startCodes + i * 2);
            if (c < start) {
                return 0;
            }
            int idDelta = s16(idDeltas + i * 2);
            int idRangeOffsetPos = idRangeOffsets + i * 2;
            int idRangeOffset = u16(idRangeOffsetPos);
            if (idRangeOffset == 0) {
                return (c + idDelta) & 0xFFFF;
            }
            int glyphAddr = idRangeOffsetPos + idRangeOffset + (c - start) * 2;
            int g = u16(glyphAddr);
            return g == 0 ? 0 : (g + idDelta) & 0xFFFF;
        }
        return 0;
    }

    private int cmap12(int c) {
        int t = cmapSubtableOffset;
        int numGroups = (int) u32(t + 12);
        for (int i = 0; i < numGroups; i++) {
            int grp = t + 16 + i * 12;
            long startChar = u32(grp);
            long endChar = u32(grp + 4);
            if (c >= startChar && c <= endChar) {
                long startGlyph = u32(grp + 8);
                return (int) (startGlyph + (c - startChar));
            }
        }
        return 0;
    }

    /** OpenType Coverage table lookup (formats 1 and 2); -1 if not covered. */
    private int coverageIndex(int coverage, int glyphId) {
        int format = u16(coverage);
        if (format == 1) {
            int count = u16(coverage + 2);
            for (int i = 0; i < count; i++) {
                if (u16(coverage + 4 + i * 2) == glyphId) {
                    return i;
                }
            }
        } else if (format == 2) {
            int rangeCount = u16(coverage + 2);
            for (int i = 0; i < rangeCount; i++) {
                int base = coverage + 4 + i * 6;
                int start = u16(base);
                int end = u16(base + 2);
                int startCoverageIndex = u16(base + 4);
                if (glyphId >= start && glyphId <= end) {
                    return startCoverageIndex + (glyphId - start);
                }
            }
        }
        return -1;
    }

    private void require(String... tags) {
        for (String tag : tags) {
            if (!tableOffset.containsKey(tag)) {
                throw new IllegalArgumentException("Missing required sfnt table: " + tag);
            }
        }
    }

    // -- big-endian primitive reads -----------------------------------------

    private int u8(int p) {
        return data[p] & 0xFF;
    }

    private int u16(int p) {
        return ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
    }

    private int s16(int p) {
        return (short) u16(p);
    }

    private long u32(int p) {
        return ((long) u16(p) << 16) | (u16(p + 2) & 0xFFFFL);
    }

    /** Reads a 2.14 signed fixed-point number (F2Dot14): value = int16 / 16384. */
    private double f2dot14(int p) {
        return s16(p) / 16384.0;
    }
}
