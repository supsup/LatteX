package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// No deprecation may promise removal at a VERSION (plan 398daca1 item 2).
///
/// WHY. `fxContainerAttrs` was `@deprecated` with "kept one minor version …; removal after
/// 0.7.0". The project reached 0.11.0 with the method still public, still exported, and
/// still unable to carry the `data-lx-glyphmap` sidecar its own doc names. Four minor
/// versions past its stated sunset. A removal window that passes in silence teaches readers
/// that deprecation notes in this codebase are not load-bearing — which costs more than the
/// method does, because it devalues every OTHER deprecation note too.
///
/// WHY THE FIX IS A CONDITION AND NOT A NEW DATE, which is the whole point of this guard:
/// re-stating it as "removal after 0.12.0" would have re-armed the identical fuse. This is
/// the same trap Fixpoint caught in the README's corpus claim (lattex/815) — "as of 0.7.0"
/// in a section headed CURRENT — and the same answer applies. A date ages by itself and
/// nothing notices; a condition is either met or it is not, and a reader can check it.
///
/// AND THE PROMISE WAS NEVER KEEPABLE, which is why "just remove it" was the wrong fix.
/// Verified downstream rather than assumed: Stafficy's `MathMarkerConverter` calls
/// `renderer.fxContainerAttrs`, and `InProcessJarRenderer` implements that by calling
/// `LatteX.fxContainerAttrs`. It is live on the `/docs` split seam today, so removal at
/// 0.7.0 would have broken math rendering downstream. The deprecation note promised
/// something its own consumer made impossible.
///
/// SCOPE, stated so it is not read as broader: this scans `LatteX.java`'s deprecation
/// javadoc for a DATED removal promise. It does not audit deprecations elsewhere in the
/// codebase, and it cannot tell whether a stated CONDITION is a good one — only that a
/// version-shaped promise has not come back.
class DeprecationSunsetTest {

    private static final Path API = Path.of("src/main/java/com/lattex/api/LatteX.java");

    /// "removal after 0.7.0", "removed in 1.0", "will be removed in 0.12.0" — a removal
    /// promise anchored to a release rather than to a checkable condition.
    private static final Pattern DATED_REMOVAL = Pattern.compile(
        "(?i)(?:removal|removed|remove)[^.\\n]{0,40}?\\b\\d+\\.\\d+(?:\\.\\d+)?\\b");

    @Test
    void theInstrumentReadsARealSource() throws IOException {
        // POSITIVE CONTROL. If the path moves, the scan reads nothing and the assertion below
        // passes over an empty string — the vacuous green this plan family is about.
        assertTrue(Files.exists(API),
            "LatteX.java not found from " + Path.of("").toAbsolutePath() + " — guard is INERT");
        String src = Files.readString(API, StandardCharsets.UTF_8);
        assertTrue(src.contains("@deprecated"),
            "no @deprecated javadoc found in LatteX.java — either every deprecation was removed "
                + "(fine, but this guard is now inert and should be retired) or the anchor moved");
    }

    @Test
    void noDeprecationPromisesRemovalAtAVersion() throws IOException {
        String src = Files.readString(API, StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();
        for (Matcher m = DATED_REMOVAL.matcher(src); m.find(); ) {
            offenders.add(m.group().replaceAll("\\s+", " ").strip());
        }
        assertTrue(offenders.isEmpty(),
            "a deprecation promises removal at a VERSION: " + offenders + ". That promise ages by "
                + "itself and nothing notices when it passes — fxContainerAttrs sat four minor "
                + "versions past its own stated sunset. State the CONDITION under which it goes "
                + "(\"when no consumer routes through the split seam\") so a reader can check it, "
                + "rather than a release that will simply arrive.");
    }

    @Test
    void theSurvivingDeprecationExplainsWhyItSurvives() throws IOException {
        // The other half of honest: removing the dated promise without saying why the method is
        // still here would trade a false statement for an absent one. A reader who finds a
        // four-version-old deprecation deserves to know it is deliberate and what would end it.
        String src = Files.readString(API, StandardCharsets.UTF_8);
        int at = src.indexOf("public static String fxContainerAttrs");
        assertTrue(at > 0, "fxContainerAttrs was removed — if that is deliberate, retire this test "
            + "and the sunset paragraph with it");
        String doc = src.substring(Math.max(0, at - 2200), at);
        assertTrue(doc.contains("MathMarkerConverter") || doc.contains("InProcessJarRenderer"),
            "the deprecation no longer names the live downstream caller that keeps it alive; "
                + "without that, the next reader cannot tell a deliberate survival from a "
                + "forgotten one — which is the state this plan item found it in");
    }
}
