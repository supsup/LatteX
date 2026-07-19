package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/corpus.md} from the single-source-of-truth corpus
 * data file ({@code /com/lattex/parse/corpus.tsv}) and asserts the committed
 * copy is byte-identical, so the human-readable corpus document can never drift
 * from the tsv (the way the old hand-maintained {@code corpus.md} did — it grew
 * a stale stats line and a "ranked frontier" roadmap that listed already-shipped
 * features as still-needed).
 *
 * <p>Follows the hermetic generator split (plan 32148cc8, reviewer 234 F1): under
 * the ordinary {@code test} suite this writes ONLY {@code build/examples/corpus.md}
 * and the drift assertion against the tracked copy is READ-ONLY — a normal test run
 * never touches (and can never erase) working-tree content. Only
 * {@code generateExamples} ({@code -Dlattex.examples.write=true}, which includes the
 * {@code examples} tag) regenerates the tracked file; a stale committed copy fails
 * the normal build with the regenerate-and-commit instruction. The hand-written
 * intro + the Coverage tiers table are preserved verbatim as a {@link #HEADER}
 * constant; everything downstream (per-category tables, the stats line, the
 * not-yet frontier) is generated from the tsv, so it is always accurate.
 *
 * <p>Sibling to {@link CorpusParseTest}, which verifies each tsv tier against the
 * actual {@code parse()} outcome; this test turns the same tsv into the doc.
 */
@org.junit.jupiter.api.Tag("examples")
class CorpusDocTest {

    private static final String CORPUS_RESOURCE = "/com/lattex/parse/corpus.tsv";
    /** The committed copy — READ-ONLY under the ordinary test suite. */
    private static final Path TRACKED = Path.of("examples", "corpus.md");

    /** A single corpus row. */
    private record Entry(String tier, String group, String latex, String description) {
    }

    /** A titled group of rows (one {@code # ==== <title> ====} block in the tsv). */
    private record Section(String title, List<Entry> entries) {
    }

    /** Canonical tier display order (matches the Coverage-tiers table). */
    private static final List<String> TIER_ORDER = List.of(
        "PARSES-NOW", "NEEDS-S4-LAYOUT", "NEEDS-PARSER-NODE", "NEEDS-FONT-STYLE", "PARSER-BUG");

    /** One-line gloss per not-yet tier, used as the frontier sub-headings. */
    private static final Map<String, String> TIER_GLOSS = Map.of(
        "NEEDS-S4-LAYOUT", "parses today; faithful rendering needs *new* 2D layout "
            + "(delimiter sizing to content, eval bars)",
        "NEEDS-PARSER-NODE", "needs a new `MathNode` / parser feature (named in the row)",
        "NEEDS-FONT-STYLE", "the missing feature is fundamentally a font-variant glyph set "
            + "or emitter color",
        "PARSER-BUG", "`parse()` crashes with a *non*-`MathSyntaxException` — a robustness bug");

    // ------------------------------------------------------------------
    // The generator + drift guard
    // ------------------------------------------------------------------

    @Test
    void corpusMarkdownIsGeneratedFromTsvAndDriftFree() throws IOException {
        List<Section> sections = loadSections();
        String generated = render(sections);

        // WHERE this writes follows the ExampleOutputs split (reviewer 234 F1):
        // ordinary `test` -> build/examples/corpus.md only, so a normal run can
        // never overwrite tracked content or erase uncommitted local work; only
        // generateExamples (-Dlattex.examples.write=true) writes the tracked file.
        java.nio.file.Path out = com.lattex.api.ExampleOutputs.dir().resolve("corpus.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, generated);

        // READ-ONLY drift gate against the committed copy. Under generateExamples
        // the tracked file was just regenerated above, so this always passes there
        // (regeneration is the point); under `test` it fails on real drift with
        // the regenerate instruction — without having touched the checkout.
        String committed = Files.exists(TRACKED) ? Files.readString(TRACKED) : "";
        assertEquals(generated, committed,
            "examples/corpus.md is stale relative to corpus.tsv — regenerate the "
                + "tracked copy with `./gradlew generateExamples` and commit the diff "
                + "(a normal test run writes only build/examples/corpus.md and never "
                + "touches the tracked file).");
    }

    // ------------------------------------------------------------------
    // Corpus loading (sections tracked from the # ==== ... ==== comments)
    // ------------------------------------------------------------------

    private static List<Section> loadSections() throws IOException {
        List<Section> sections = new ArrayList<>();
        Section current = null;
        try (InputStream in = CorpusDocTest.class.getResourceAsStream(CORPUS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("corpus resource not found: " + CORPUS_RESOURCE);
            }
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    if (line.startsWith("# ====")) {
                        current = new Section(sectionTitle(line), new ArrayList<>());
                        sections.add(current);
                        continue;
                    }
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 4) {
                        throw new IllegalStateException(
                            "corpus.tsv line " + lineNo + ": expected 4 tab-separated columns, got "
                                + parts.length + " -> " + line);
                    }
                    if (current == null) {
                        throw new IllegalStateException(
                            "corpus.tsv line " + lineNo + ": data row before any # ==== section ====");
                    }
                    current.entries().add(
                        new Entry(parts[0].trim(), parts[1].trim(), parts[2], parts[3]));
                }
            }
        }
        return sections;
    }

    /** {@code "# ==== scripts / roots ===="} -> {@code "Scripts / roots"}. */
    private static String sectionTitle(String comment) {
        String s = comment.replaceFirst("^#+", "").trim();   // strip leading '#'
        s = s.replaceAll("^=+", "").replaceAll("=+$", "").trim(); // strip '=' fences
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static String render(List<Section> sections) {
        List<Entry> all = new ArrayList<>();
        sections.forEach(sec -> all.addAll(sec.entries()));

        Map<String, Integer> tierCounts = new LinkedHashMap<>();
        TIER_ORDER.forEach(t -> tierCounts.put(t, 0));
        for (Entry e : all) {
            tierCounts.merge(e.tier(), 1, Integer::sum);
        }

        StringBuilder md = new StringBuilder();
        md.append(HEADER);
        md.append('\n');
        md.append(statsLine(all.size(), tierCounts));
        md.append('\n');
        md.append(SPLIT_NOTE);
        md.append("\n---\n");

        for (Section sec : sections) {
            md.append("\n## ").append(sec.title()).append("\n\n");
            md.append("| LaTeX | Description | Tier |\n");
            md.append("| --- | --- | --- |\n");
            for (Entry e : sec.entries()) {
                md.append("| ").append(latexCell(e.latex()))
                    .append(" | ").append(cellText(e.description()))
                    .append(" | `").append(e.tier()).append("` |\n");
            }
        }

        md.append(notYetFrontier(sections, tierCounts));
        return md.toString();
    }

    /** The generated, always-accurate stats blockquote. */
    private static String statsLine(int total, Map<String, Integer> tierCounts) {
        StringBuilder counts = new StringBuilder();
        for (String tier : TIER_ORDER) {
            int n = tierCounts.getOrDefault(tier, 0);
            if (n == 0 && !tier.equals("PARSER-BUG")) {
                continue; // only ever-show PARSER-BUG's zero (the "no crashes" fact)
            }
            if (counts.length() > 0) {
                counts.append(", ");
            }
            counts.append('`').append(tier).append("` **").append(n).append("**");
        }
        return "> **Empirical frontier** over **" + total + " entries** — the tier column is "
            + "the source of truth in [`corpus.tsv`](../src/test/resources/com/lattex/parse/corpus.tsv), "
            + "verified against `parse()` by `CorpusParseTest`: " + counts + ". The parser fails "
            + "cleanly (a named `MathSyntaxException`) on the entire not-yet frontier — no crashes.\n";
    }

    /** The generated "Not-yet frontier" — current non-`PARSES-NOW` rows, by tier. */
    private static String notYetFrontier(List<Section> sections, Map<String, Integer> tierCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n---\n\n## Not-yet frontier\n\n");
        sb.append("Every row that does **not** yet parse into a tree — i.e. everything outside "
            + "`PARSES-NOW` — grouped by tier, generated straight from `corpus.tsv` so it can never "
            + "list an already-shipped feature as still-needed.\n");

        boolean any = false;
        for (String tier : TIER_ORDER) {
            if (tier.equals("PARSES-NOW")) {
                continue;
            }
            List<Entry> rows = new ArrayList<>();
            for (Section sec : sections) {
                for (Entry e : sec.entries()) {
                    if (e.tier().equals(tier)) {
                        rows.add(e);
                    }
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            any = true;
            sb.append("\n### `").append(tier).append("` — ")
                .append(TIER_GLOSS.getOrDefault(tier, "")).append(" (").append(rows.size())
                .append(")\n\n");
            sb.append("| LaTeX | Description |\n");
            sb.append("| --- | --- |\n");
            for (Entry e : rows) {
                sb.append("| ").append(latexCell(e.latex()))
                    .append(" | ").append(cellText(e.description())).append(" |\n");
            }
        }
        if (!any) {
            sb.append("\n_Nothing outstanding — every corpus row parses today._\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Markdown-cell escaping
    // ------------------------------------------------------------------

    /**
     * Wraps LaTeX in a code span and escapes table-breaking {@code |} as {@code \|}.
     * Inside a code span backslashes are literal, so only pipes need escaping; a
     * backtick in the source (none today) would still break the span, so the fence
     * grows to one more backtick than the longest run and pads with spaces.
     */
    private static String latexCell(String latex) {
        String body = latex.replace("|", "\\|");
        int longestRun = 0;
        int run = 0;
        for (int i = 0; i < latex.length(); i++) {
            if (latex.charAt(i) == '`') {
                run++;
                longestRun = Math.max(longestRun, run);
            } else {
                run = 0;
            }
        }
        if (longestRun == 0) {
            return "`" + body + "`";
        }
        String fence = "`".repeat(longestRun + 1);
        return fence + " " + body + " " + fence;
    }

    /**
     * Makes arbitrary prose safe inside a GFM table cell: escape {@code \} then
     * {@code |} (order matters), so a literal {@code \|} in a description survives
     * and stray {@code |} don't split the row.
     */
    private static String cellText(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|");
    }

    // ------------------------------------------------------------------
    // Preserved hand-written header (intro + single-source note + tiers table)
    // ------------------------------------------------------------------

    private static final String HEADER =
        """
        # LatteX LaTeX-math test corpus

        A tiered regression/acceptance corpus for the LatteX math renderer, derived from
        the canonical LaTeX math gallery (Wikipedia *"Displaying a formula"*). It is the
        target that drives the S3→S8 build-out and will feed the S8 render gallery.

        **Single source of truth.** Every entry below is a row in
        [`src/test/resources/com/lattex/parse/corpus.tsv`](../src/test/resources/com/lattex/parse/corpus.tsv).
        `CorpusParseTest` drives each row through `com.lattex.parse.MathParser.parse` and
        asserts the declared tier matches what the parser actually does today, so this
        document and the parser cannot drift. **Tiers here are empirical, not guessed.**

        > **Generated file — do not edit by hand.** `CorpusDocTest` regenerates this
        > document from `corpus.tsv` on every build and fails if the committed copy is
        > stale. Edit the tsv (and rerun the build), never this file.

        ## Coverage tiers

        | Tier | Meaning | `parse()` today |
        | --- | --- | --- |
        | **`PARSES-NOW`** | The S3 parser builds a valid tree today (verified). | succeeds |
        | **`NEEDS-S4-LAYOUT`** | Parses today, but faithful rendering needs *new* 2D layout (delimiter sizing to content, eval bars). | succeeds |
        | **`NEEDS-PARSER-NODE`** | Needs a new `MathNode` / parser feature (named in the entry). | throws `MathSyntaxException` |
        | **`NEEDS-FONT-STYLE`** | Missing feature is fundamentally a font-variant glyph set or emitter color. | throws `MathSyntaxException` |
        | **`PARSER-BUG`** | `parse()` crashes with a *non*-`MathSyntaxException` (NPE/SOE/CCE). A robustness bug. | crashes |
        """;

    private static final String SPLIT_NOTE =
        """
        Note on the split: `PARSES-NOW` vs `NEEDS-S4-LAYOUT` both parse today; the layout
        tier is reserved for parsed trees whose faithful rendering needs a *new* S4
        capability (stretchy delimiter sizing, eval bars) beyond the box-stacking that
        `\\frac`/`\\sqrt`/scripts already establish. Constructs that don't parse yet are
        tiered by their current frontier (see **Not-yet frontier** below), with S4 layout
        following once the node exists.
        """;
}
