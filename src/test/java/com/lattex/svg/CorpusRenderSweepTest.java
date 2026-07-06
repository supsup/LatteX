package com.lattex.svg;

import com.lattex.api.LatteX;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The FULL-corpus render sweep (lattex-testnet plan, S1 — the "biggest coverage gap"):
 * the corpus used to drive {@code parse()} only, so a construct that parses but emits
 * out-of-alphabet SVG, throws in layout, or yields a degenerate canvas was unguarded
 * by LatteX's own build. This sweep pushes <em>every</em> renderable corpus row through
 * {@link LatteX#render} (display) and {@link LatteX#renderInline} (inline) and asserts,
 * per row:
 *
 * <ul>
 *   <li><b>No throw</b> for {@code PARSES-NOW} rows — the whole advertised surface renders;</li>
 *   <li><b>Alphabet containment</b> via {@link S8LeftContainmentTest#auditOne} — the SAME
 *       judge as the hand-picked battery, so the trust claim ("only svg/g/path/rect,
 *       never script/foreignObject/use/on*") is now corpus-wide, not specimen-wide;</li>
 *   <li><b>Non-degenerate canvas</b> — a viewBox of four finite numbers with positive
 *       width and height (a zero-area or NaN canvas is a silent rendering failure).</li>
 * </ul>
 *
 * {@code NEEDS-S4-LAYOUT} rows parse but may legitimately lack layout: if they render,
 * they must pass the same audit; if they throw, it must be a RuntimeException (a caught,
 * degradable failure) — an {@link Error} escaping here fails the test by design.
 */
class CorpusRenderSweepTest {

    private record Row(String tier, String latex) {}

    private static List<Row> loadCorpus() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = CorpusRenderSweepTest.class.getClassLoader()
                .getResourceAsStream("com/lattex/parse/corpus.tsv")) {
            assertNotNull(in, "vendored corpus resource missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length >= 3) {
                    rows.add(new Row(cols[0].trim(), cols[2]));
                }
            }
        }
        return rows;
    }

    @Test
    void everyParsesNowRowRendersContainedInBothModes() throws IOException {
        List<Row> corpus = loadCorpus();
        List<String> failures = new ArrayList<>();
        int swept = 0, scannedTags = 0;

        for (Row row : corpus) {
            boolean mustRender = row.tier().equals("PARSES-NOW");
            boolean mayRender = row.tier().equals("NEEDS-S4-LAYOUT");
            if (!mustRender && !mayRender) {
                continue; // NEEDS-PARSER-NODE / NEEDS-FONT-STYLE rows must FAIL parse — CorpusParseTest owns them
            }
            swept++;
            for (boolean inline : new boolean[] {false, true}) {
                String mode = inline ? "inline" : "display";
                String svg;
                try {
                    svg = inline ? LatteX.renderInline(row.latex()) : LatteX.render(row.latex());
                } catch (RuntimeException e) {
                    if (mustRender) {
                        failures.add("PARSES-NOW row threw in " + mode + " [" + row.latex() + "]: " + e);
                    }
                    continue; // NEEDS-S4-LAYOUT: a caught RuntimeException is the accepted degrade
                }
                scannedTags += S8LeftContainmentTest.auditOne(row.latex() + " (" + mode + ")", svg, failures);
                auditCanvas(row.latex(), mode, svg, failures);
            }
        }

        if (!failures.isEmpty()) {
            fail("corpus render sweep violated (" + failures.size() + "):\n  "
                + String.join("\n  ", failures));
        }
        // Non-vacuity: the sweep must really cover the corpus breadth (133 PARSES-NOW
        // rows x 2 modes yields thousands of tags), never an accidentally-empty filter.
        assertTrue(swept >= 130, "expected the full renderable corpus, swept only " + swept);
        assertTrue(scannedTags > 2000, "expected a corpus-wide tag body, scanned only " + scannedTags);
    }

    /** A rendered canvas must be real: viewBox = four finite numbers, width/height > 0. */
    private static void auditCanvas(String latex, String mode, String svg, List<String> failures) {
        int at = svg.indexOf("viewBox=\"");
        if (at < 0) {
            failures.add("no viewBox in " + mode + " [" + latex + "]");
            return;
        }
        int end = svg.indexOf('"', at + 9);
        String[] nums = svg.substring(at + 9, end).trim().split("[\\s,]+");
        if (nums.length != 4) {
            failures.add("viewBox is not 4 numbers in " + mode + " [" + latex + "]");
            return;
        }
        try {
            double w = Double.parseDouble(nums[2]);
            double h = Double.parseDouble(nums[3]);
            if (!Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0) {
                failures.add("degenerate canvas " + w + "x" + h + " in " + mode + " [" + latex + "]");
            }
        } catch (NumberFormatException e) {
            failures.add("non-numeric viewBox in " + mode + " [" + latex + "]: " + e.getMessage());
        }
    }
}
