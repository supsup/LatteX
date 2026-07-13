package com.lattex.parse;

import java.util.Collection;
import java.util.Optional;

/**
 * A tiny, dependency-free "did you mean?" helper: Levenshtein edit distance plus a
 * nearest-candidate search used to turn a bare {@code Unknown command: \fract} into
 * {@code … — did you mean \frac?}. Pure and static — no parser state, no I/O.
 *
 * <p><strong>Threshold.</strong> {@link #nearest} only suggests a candidate whose edit
 * distance is at most {@code min(2, token.length() / 3)} — i.e. up to roughly a third of
 * the token's length, hard-capped at 2. The cap keeps a long token from matching something
 * wildly different; the length ratio keeps a short token from matching almost anything
 * (a token of 1–2 characters gets a bound of 0, so it is never "corrected" to noise).
 * A clear single typo (a dropped, added, or swapped-for letter) lands within it; an
 * unrelated token like {@code \qux} does not, so nothing is suggested rather than guessing.
 */
final class FuzzyMatch {

    private FuzzyMatch() {
    }

    /**
     * Classic Levenshtein edit distance (insert / delete / substitute, each cost 1)
     * between two strings, computed with a single rolling row (O(a·b) time, O(b) space).
     */
    static int editDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    /**
     * The candidate closest to {@code token} by {@link #editDistance}, provided it lands
     * within the threshold ({@code min(2, token.length() / 3)}). Ties are broken
     * lexicographically so the result is deterministic. Returns {@link Optional#empty()}
     * when nothing is close enough (or the candidate set is empty) — the caller then
     * suggests nothing rather than a misleading guess.
     */
    static Optional<String> nearest(String token, Collection<String> candidates) {
        int maxDistance = Math.min(2, token.length() / 3);
        if (maxDistance == 0) {
            return Optional.empty();
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int d = editDistance(token, candidate);
            if (d < bestDistance || (d == bestDistance && best != null && candidate.compareTo(best) < 0)) {
                bestDistance = d;
                best = candidate;
            }
        }
        if (best != null && bestDistance <= maxDistance) {
            return Optional.of(best);
        }
        return Optional.empty();
    }
}
