package com.lattex;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The version string must name a FIXED TREE, or announce that it does not (plan ab8b2928).
///
/// WHY. On 2026-08-06 `build.gradle.kts` declared `version = "0.11.0"` while `src/main` had
/// drifted **38 files, +4887/-671** from the 0.11.0 cut (963121f) — and the repo carried NO
/// release tags at all, only `retired/*` branch markers. So the string "0.11.0" named no
/// particular tree. Two consumers pinning it days apart got different bytes. Stafficy,
/// meanwhile, vendored `lattex-0.11.1.jar`, a version whose only lineage is two retired tags
/// that are not ancestors of main.
///
/// The rule was written in a comment above the version line and enforced by nothing. 38 files
/// is what "someone will remember to bump it" was worth over one week. This is the guard that
/// comment always needed: it would have gone red on the FIRST commit after 963121f.
///
/// THE INVARIANT, and note it is an implication rather than a prohibition — main is free to
/// declare a release version, but only when that claim is actually backed:
///
///     version is bare (no -SNAPSHOT)  ==>  HEAD carries the matching release tag
///
/// A SNAPSHOT is always fine: it announces itself as a moving target and no consumer may pin
/// it. A bare version is a promise of immutability, and the tag is the only thing that can
/// keep it.
///
/// A FAILED PROOF IS NO PROOF. If the version cannot be parsed, or git cannot be consulted,
/// this test FAILS rather than passing. A guard that goes quiet exactly when it cannot
/// establish its property is the silent-clean this plan exists to remove — the same stance
/// BrewShot's `ResourceLease` takes when it retains a profile it cannot prove safe to delete.
class VersionIdentityGuardTest {

    private static final Path BUILD_FILE = Path.of("build.gradle.kts");

    /// The `version = "…"` assignment, at the start of a line so a version string mentioned
    /// inside a comment or a dependency coordinate cannot be mistaken for the declaration.
    private static final Pattern VERSION_DECL =
        Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"");

    private static String declaredVersion() throws IOException {
        assertTrue(Files.exists(BUILD_FILE),
            "build.gradle.kts not found from the test working dir " + Path.of("").toAbsolutePath()
                + " — this guard is INERT, not passing");
        String text = Files.readString(BUILD_FILE, StandardCharsets.UTF_8);
        Matcher m = VERSION_DECL.matcher(text);
        // NON-VACUITY. If the declaration moves or is reformatted, this guard must fail loudly
        // rather than quietly finding nothing to check.
        assertTrue(m.find(),
            "no `version = \"...\"` declaration found in build.gradle.kts — the anchor moved and "
                + "this guard can no longer see the thing it exists to check");
        return m.group(1);
    }

    /// Tags pointing at HEAD, or null when git could not be consulted at all. Null is NOT
    /// "no tags" — the two are deliberately distinguishable, because treating an unavailable
    /// prover as a clean result is the exact defect class this guard is about.
    private static List<String> tagsOnHead() {
        try {
            Process p = new ProcessBuilder("git", "tag", "--points-at", "HEAD")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    @Test
    void aBareReleaseVersionIsBackedByAMatchingTag() throws IOException {
        String version = declaredVersion();
        if (version.endsWith("-SNAPSHOT")) {
            // Main's ordinary state. It claims nothing a tag would have to keep.
            return;
        }
        List<String> tags = tagsOnHead();
        assertTrue(tags != null,
            "build.gradle.kts declares the bare release version \"" + version + "\", which is a "
                + "promise that this exact tree is what any consumer pinning that string gets — "
                + "and git could not be consulted to check it. A failed proof is no proof: this "
                + "guard refuses rather than assuming the promise holds.");
        // Accept the spellings a release tag plausibly takes, so the guard pins the PROPERTY
        // (this tree is tagged as this version) rather than one naming convention.
        boolean tagged = tags.stream().anyMatch(t ->
            t.equals(version) || t.equals("v" + version) || t.equals("lattex-" + version));
        assertTrue(tagged,
            "build.gradle.kts declares the bare release version \"" + version + "\" but HEAD "
                + "carries no matching tag (tags on HEAD: " + tags + "). A version string with no "
                + "tag behind it names no fixed tree — that is how main came to declare 0.11.0 "
                + "while sitting 38 files away from the 0.11.0 cut, with nothing going red. "
                + "Either tag this commit as the release, or declare a -SNAPSHOT version.");
    }

    @Test
    void theGuardIsReadingTheRealBuildFile() throws IOException {
        // POSITIVE CONTROL drawn from the corpus: the declared version must be a version-shaped
        // string. If the regex ever matches something else, both this and the assertion above
        // are checking the wrong text while reporting success.
        String version = declaredVersion();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"),
            "declared version \"" + version + "\" is not version-shaped — the regex is matching "
                + "something other than the version declaration");
    }
}
