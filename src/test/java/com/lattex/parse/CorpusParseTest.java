package com.lattex.parse;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives EVERY entry of the tiered LaTeX-math corpus
 * ({@code /com/lattex/parse/corpus.tsv}) through {@link MathParser#parse} and
 * verifies that the tier declared in the corpus data matches what the S3 parser
 * actually does today. The corpus data file is the single source of truth shared
 * with {@code examples/corpus.md}, so the documented tier and the empirical
 * parse outcome cannot drift.
 *
 * <p>Contract enforced here (this is what makes the tiers <em>empirical</em>):
 * <ul>
 *   <li>{@code PARSES-NOW} / {@code NEEDS-S4-LAYOUT} entries MUST parse without
 *       throwing (they already build a valid tree; the layout tier only differs
 *       in what S4 rendering still owes).</li>
 *   <li>{@code NEEDS-PARSER-NODE} / {@code NEEDS-FONT-STYLE} entries MUST fail,
 *       and fail <em>cleanly</em>: a {@link MathSyntaxException} (a named
 *       failure), never a {@link NullPointerException}, {@link StackOverflowError},
 *       {@link ClassCastException}, or any other unnamed crash.</li>
 *   <li>Any entry that crashes with a non-{@code MathSyntaxException} is a parser
 *       robustness bug. Such an entry must be tagged {@code PARSER-BUG} in the
 *       corpus data (which documents-not-fails it here) AND reported. As of this
 *       writing there are none — the parser fails cleanly on the entire not-yet
 *       frontier.</li>
 * </ul>
 */
class CorpusParseTest {

    private static final String CORPUS_RESOURCE = "/com/lattex/parse/corpus.tsv";

    /** A single corpus row. */
    private record Entry(String tier, String group, String latex, String description) {
    }

    /** What {@link MathParser#parse} actually did with an entry. */
    private enum Outcome {
        /** Returned a tree. */
        PARSED,
        /** Threw {@link MathSyntaxException} — a clean, named refusal. */
        CLEAN_FAIL,
        /** Threw something else — an unnamed crash (robustness bug). */
        CRASH
    }

    // ------------------------------------------------------------------
    // Corpus loading
    // ------------------------------------------------------------------

    private static List<Entry> loadCorpus() throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = CorpusParseTest.class.getResourceAsStream(CORPUS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("corpus resource not found: " + CORPUS_RESOURCE);
            }
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 4) {
                        throw new IllegalStateException(
                            "corpus.tsv line " + lineNo + ": expected 4 tab-separated columns, got "
                                + parts.length + " -> " + line);
                    }
                    entries.add(new Entry(parts[0].trim(), parts[1].trim(), parts[2], parts[3]));
                }
            }
        }
        return entries;
    }

    private static Outcome probe(String latex) {
        try {
            MathParser.parse(latex);
            return Outcome.PARSED;
        } catch (MathSyntaxException e) {
            return Outcome.CLEAN_FAIL;
        } catch (Throwable t) { // NOPMD — deliberately catching Error too (SOE etc.)
            return Outcome.CRASH;
        }
    }

    /** True when a declared tier implies parse() should succeed today. */
    private static boolean tierExpectsParse(String tier) {
        return tier.equals("PARSES-NOW") || tier.equals("NEEDS-S4-LAYOUT");
    }

    // ------------------------------------------------------------------
    // The empirical driver
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every corpus entry's declared tier matches its actual parse outcome")
    void corpusTiersAreEmpirical() throws IOException {
        List<Entry> entries = loadCorpus();

        Map<String, Integer> tierCounts = new LinkedHashMap<>();
        List<String> mismatches = new ArrayList<>();
        List<String> crashes = new ArrayList<>();

        for (Entry e : entries) {
            tierCounts.merge(e.tier(), 1, Integer::sum);
            Outcome outcome = probe(e.latex());

            if (outcome == Outcome.CRASH) {
                String detail = crashDetail(e.latex());
                // A crash is only acceptable if the entry is explicitly tagged PARSER-BUG.
                if (!e.tier().equals("PARSER-BUG")) {
                    crashes.add(e.latex() + "  ->  " + detail + "  [tier=" + e.tier() + "]");
                }
                continue;
            }

            boolean shouldParse = tierExpectsParse(e.tier());
            if (e.tier().equals("PARSER-BUG")) {
                // Tagged as a crash but it did NOT crash — the bug is fixed; flag the stale tag.
                mismatches.add(e.latex() + "  tagged PARSER-BUG but did not crash (outcome="
                    + outcome + "); retag it");
                continue;
            }
            boolean didParse = outcome == Outcome.PARSED;
            if (shouldParse != didParse) {
                mismatches.add(e.latex()
                    + "  declared=" + e.tier()
                    + "  expected " + (shouldParse ? "PARSE" : "CLEAN_FAIL")
                    + "  but got " + outcome);
            }
        }

        // Emit the empirical frontier so the build log doubles as the report.
        System.out.println(renderSummary(entries.size(), tierCounts));

        if (!crashes.isEmpty()) {
            fail("PARSER-BUG: " + crashes.size() + " corpus entr(y/ies) crashed with a "
                + "non-MathSyntaxException (should fail cleanly):\n  " + String.join("\n  ", crashes));
        }
        if (!mismatches.isEmpty()) {
            fail(mismatches.size() + " corpus tier(s) disagree with the actual parse outcome:\n  "
                + String.join("\n  ", mismatches));
        }
    }

    private static String crashDetail(String latex) {
        try {
            MathParser.parse(latex);
            return "(no crash on re-run)";
        } catch (MathSyntaxException e) {
            return "(clean on re-run)";
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static String renderSummary(int total, Map<String, Integer> tierCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== LatteX corpus — empirical parse frontier ====\n");
        sb.append("total entries: ").append(total).append('\n');
        tierCounts.forEach((tier, count) ->
            sb.append(String.format("  %-18s %3d%n", tier, count)));
        return sb.toString();
    }
}
