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
     * Parses a glyph's outline from {@code loca}/{@code glyf}. Composite glyphs
     * are not supported in M0 (the skeleton's {@code x} and {@code 2} are both
     * simple glyphs).
     */
    public GlyphOutline outline(int glyphId) {
        if (glyphId < 0 || glyphId >= numGlyphs) {
            throw new IndexOutOfBoundsException("glyphId " + glyphId);
        }
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
        if (numberOfContours < 0) {
            throw new UnsupportedOperationException(
                "Composite glyph (glyphId " + glyphId + ") not supported in M0");
        }
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
        return new GlyphOutline(contours, xMin, yMin, xMax, yMax);
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
        int constRel = u16(math + 4); // header: major(2) minor(2) MathConstants(2) ...
        int c = math + constRel;
        // MathConstants layout (OpenType MATH spec):
        //   int16  scriptPercentScaleDown              (offset 0)
        //   int16  scriptScriptPercentScaleDown        (offset 2)
        //   uint16 delimitedSubFormulaMinHeight        (offset 4)
        //   uint16 displayOperatorMinHeight            (offset 6)
        //   then MathValueRecords (4 bytes each): mathLeading, axisHeight,
        //   accentBaseHeight, flattenedAccentBaseHeight, subscriptShiftDown,
        //   subscriptTopMax, subscriptBaselineDropMin (7 records = 28 bytes),
        //   then superscriptShiftUp at offset 8 + 28 = 36 (its int16 value).
        int scriptPercentScaleDown = s16(c);
        int superscriptShiftUp = s16(c + 36);
        return new MathConstants(scriptPercentScaleDown, superscriptShiftUp);
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
}
