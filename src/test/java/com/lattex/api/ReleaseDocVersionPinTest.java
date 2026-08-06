package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Release-doc version pin (review lattex-316). The user-facing install/run recipes in
 * {@code README.md}/{@code QUICKSTART.md}/{@code SLOWSTART.md} hard-code the CURRENT LatteX
 * version in Maven coordinates ({@code com.lattex:lattex:X.Y.Z}) and jar names
 * ({@code lattex-X.Y.Z.jar}). A version bump that freshens {@code build.gradle.kts} but not
 * these recipes ships instructions pointing at a jar the build no longer produces — lived at
 * the 0.11.0 cut, where the docs still said 0.9.0. This pins every such recipe reference to
 * the {@code build.gradle.kts} version, so any future drift reddens at build time.
 *
 * <p>Scope is deliberately the coordinate/jar forms ONLY: historical prose ("100% of the wild
 * corpus as of 0.7.0") and the dated {@code RELEASE_NOTES.md} version headings are legitimately
 * old and are never matched (RELEASE_NOTES is not in the recipe-doc set). The non-vacuity floor
 * guards against a doc rewrite silently dropping the recipes and passing empty.
 */
class ReleaseDocVersionPinTest {

    /** Docs that carry CURRENT-version install/run recipes (NOT RELEASE_NOTES — that is history). */
    private static final List<String> RECIPE_DOCS = List.of("README.md", "QUICKSTART.md", "SLOWSTART.md");

    /** {@code com.lattex:lattex:X.Y.Z} (group 1) OR {@code lattex-X.Y.Z.jar} (group 2). */
    private static final Pattern PINNED = Pattern.compile(
        "com\\.lattex:lattex:(\\d+\\.\\d+\\.\\d+)|lattex-(\\d+\\.\\d+\\.\\d+)\\.jar");

    /** The seven recipe refs present at the 0.11.0 cut — a floor (>=, so adding recipes is fine). */
    private static final int MIN_RECIPE_REFS = 7;

    @Test
    void everyCurrentVersionRecipeMatchesTheBuildVersion() throws IOException {
        String releasedVersion = latestReleasedVersion();
        int found = 0;
        List<String> mismatches = new ArrayList<>();
        for (String doc : RECIPE_DOCS) {
            String text = Files.readString(Path.of(doc));
            Matcher m = PINNED.matcher(text);
            while (m.find()) {
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                found++;
                if (!v.equals(releasedVersion)) {
                    mismatches.add(doc + ": \"" + m.group() + "\" pins " + v
                        + " but the latest RELEASE tag is " + releasedVersion);
                }
            }
        }
        assertTrue(mismatches.isEmpty(),
            "release-doc version drift — a version-pinned coordinate/jar recipe does not match "
            + "the released version " + releasedVersion + ": " + mismatches);
        assertTrue(found >= MIN_RECIPE_REFS,
            "expected >= " + MIN_RECIPE_REFS + " version-pinned coordinate/jar recipes across "
            + RECIPE_DOCS + " (non-vacuity floor); found " + found
            + " — did a doc rewrite drop the install/run recipes?");
    }

    /// The version a CONSUMER should pin — i.e. the latest RELEASE, which is not the same
    /// thing as the version main declares (plan ab8b2928).
    ///
    /// This used to read `project.version` out of build.gradle.kts, which was correct only
    /// while main permanently claimed to be a release. It no longer does: between cuts main
    /// declares `<next>-SNAPSHOT`, precisely so it stops pretending a moving target is
    /// immutable. Pinning install recipes to a SNAPSHOT would tell users to depend on a
    /// version that names no fixed tree — the exact defect this plan exists to remove, and
    /// the old reader would simply have failed to match it.
    ///
    /// So the released version is derived from the RELEASE TAG, which is the only thing that
    /// makes a version immutable. A FAILED PROOF IS NO PROOF: if git cannot be consulted, or
    /// no release tag exists, this FAILS rather than falling back to the build version —
    /// falling back is how the recipes would silently start pinning a SNAPSHOT again.
    private static String latestReleasedVersion() {
        try {
            Process p = new ProcessBuilder(
                "git", "tag", "--list", "lattex-*", "--sort=-v:refname")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0,
                "git could not be consulted for release tags — this guard refuses rather than "
                    + "assuming the recipes are correct");
            String tag = out.lines().map(String::trim).filter(t -> !t.isEmpty()).findFirst()
                .orElse(null);
            assertTrue(tag != null,
                "no lattex-* release tag exists, so there is no released version for the install "
                    + "recipes to pin. Cut a release before expecting this guard to pass.");
            return tag.substring("lattex-".length());
        } catch (java.io.IOException | InterruptedException e) {
            throw new AssertionError("git could not be consulted for release tags", e);
        }
    }
}
