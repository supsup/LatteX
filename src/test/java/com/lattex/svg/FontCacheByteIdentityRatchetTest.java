package com.lattex.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.LatteX;
import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.parse.MathNode;
import com.lattex.parse.MathParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BYTE-IDENTITY RATCHET for the font/emission caching refactor (plan 725c1488,
 * Marlow audit LTX-03). This is the load-bearing acceptance gate: a whole-corpus
 * SHA-256 over EVERY renderable output surface that touches the memoized hot paths
 * — {@code SfntFont.outline}, {@code GlyphPath} path data, and the cmap/coverage
 * lookups — for every {@code PARSES-NOW} corpus row.
 *
 * <p>Per row the digest folds in, in a fixed order:
 * <ul>
 *   <li>{@link LatteX#render(String)} — the display-math self-contained SVG document;</li>
 *   <li>{@link LatteX#renderInline(String)} — the inline-styled SVG document;</li>
 *   <li>{@link SvgEmitter#emitFragment(Layout, SfntFont)} — the bare re-based fragment;</li>
 *   <li>{@link SvgEmitter#glyphmap(Layout, SfntFont)} — the token-identity sidecar;</li>
 *   <li>{@link SvgEmitter#groupmap(Layout, SfntFont)} — the precedence sidecar.</li>
 * </ul>
 * The last three come from a layout the test builds directly (display style, 40 px),
 * so the emission-plan sharing between the SVG and the semantic sidecars is exercised
 * on the whole corpus, not just fx-annotated specimens.
 *
 * <p>The hash is pinned as {@link #PINNED_SHA256}. Caching must NOT change one byte of
 * any surface, so this constant is IDENTICAL before and after the refactor. If a cache
 * ever returns a wrong-glyph outline / stale path / mis-mapped cmap result, the fold
 * diverges and this test goes RED. The deliberate-break red-proof
 * ({@code CachePoisonRedProofTest}) confirms the ratchet actually guards output.
 */
class FontCacheByteIdentityRatchetTest {

    /**
     * The whole-corpus output hash. Captured from the PRISTINE pre-caching tree and
     * pinned here; the post-caching tree must reproduce it byte-for-byte. Re-pinned
     * when the corpus intentionally grew 181 -> 186 rows for Tier 2 after a control
     * run over the original 181 rows still reproduced the prior hash exactly.
     */
    private static final String PINNED_SHA256 =
        "bd49f921c29b49b298836d34d61e23351962c8cc19f60be11b40e7fed490f342";

    private static final SfntFont FONT = SfntFont.loadBundled();

    private record Row(int line, String latex) { }

    private static List<Row> parsesNowRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = FontCacheByteIdentityRatchetTest.class.getClassLoader()
                .getResourceAsStream("com/lattex/parse/corpus.tsv")) {
            assertNotNull(in, "vendored corpus resource missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length >= 3 && "PARSES-NOW".equals(cols[0].trim())) {
                    rows.add(new Row(lineNo, cols[2]));
                }
            }
        }
        return rows;
    }

    /** The whole-corpus fold, computed live from the current tree. */
    static String computeCorpusHash() throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int rowsFolded = 0;
        for (Row row : parsesNowRows()) {
            String latex = row.latex();
            // Full-document surfaces via the public API.
            fold(md, LatteX.render(latex));
            fold(md, LatteX.renderInline(latex));
            // Fragment + sidecar surfaces from a directly-built display-style layout —
            // the SAME emission producers glyphmap/groupmap key off, so the emission-plan
            // sharing is covered corpus-wide.
            MathNode node = MathParser.parse(latex);
            MathNode body = node instanceof MathNode.StyledMath sm ? sm.body() : node;
            LayoutContext ctx = new LayoutContext(FONT, FONT.mathConstants(), 40.0);
            Layout layout = LayoutEngine.layout(body, ctx);
            fold(md, SvgEmitter.emitFragment(layout, FONT));
            fold(md, SvgEmitter.glyphmap(layout, FONT));
            fold(md, SvgEmitter.groupmap(layout, FONT));
            rowsFolded++;
        }
        assertTrue(rowsFolded >= 150,
            "expected the full PARSES-NOW corpus, folded only " + rowsFolded);
        return toHex(md.digest());
    }

    private static void fold(MessageDigest md, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        // Length-prefix each surface (decimal length, then a single NUL separator byte,
        // then the surface bytes) so concatenation is unambiguous (no boundary aliasing).
        // The separator is written as an explicit md.update((byte) 0) rather than a raw NUL
        // in a string literal, so this test file stays reviewable text (Marlow LTX-03);
        // the folded input — and thus PINNED_SHA256 — is byte-for-byte unchanged.
        md.update(String.valueOf(b.length).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(b);
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte x : digest) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    void wholeCorpusOutputIsByteIdenticalToThePinnedHash() throws Exception {
        String actual = computeCorpusHash();
        assertEquals(PINNED_SHA256, actual,
            "whole-corpus render/emit output changed — a cache altered a byte. "
            + "If this is an INTENTIONAL output change (it must not be for plan 725c1488), "
            + "re-pin PINNED_SHA256 to: " + actual);
    }
}
