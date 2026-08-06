package com.lattex;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/// Shared diagnostic for the tag-derived guards, so a MISSING TAG and an UNFETCHED CHECKOUT
/// stop reporting as the same thing (review lattex/828, Confluence).
///
/// WHY THIS EXISTS. `VersionIdentityGuardTest` and `ReleaseDocVersionPinTest` both decide
/// something by asking git for tags. Each already distinguishes two states carefully —
/// "git could not be consulted" (fail, a failed proof is no proof) from "git answered, and
/// the answer is empty". But there is a THIRD state neither could see: **the tags were never
/// fetched**. It presents byte-for-byte as the empty answer, and on CI it is the NORMAL
/// state — `actions/checkout` passes `--no-tags` unless `fetch-tags: true` is set.
///
/// So a guard would report "no lattex-* release tag exists. Cut a release before expecting
/// this guard to pass" at the exact moment someone HAS cut the release, and send them hunting
/// a tag that is sitting on the remote. That is a failure naming the wrong subsystem — the
/// same defect shape as an HTTP 500 that blames your credentials, and the reason this repo
/// has a version-identity guard at all.
///
/// WHAT IT CAN AND CANNOT DECIDE, stated plainly because the honest answer is "not fully".
/// From inside the checkout, a repo that has never been tagged and a repo whose tags were
/// not fetched are INDISTINGUISHABLE — both are simply "zero tags". This class therefore
/// does not claim to tell them apart. It detects the one thing that IS observable — the
/// checkout carries no tags AT ALL — and makes the message name both possibilities and the
/// concrete fix. Naming two candidate causes beats confidently naming the wrong one.
public final class TagFetchDiagnostic {

    private TagFetchDiagnostic() {
    }

    /// Total tags visible in this checkout, or -1 when git could not be consulted.
    ///
    /// -1 is deliberately distinct from 0 and is treated as "cannot say" by
    /// [#unfetchedCheckoutHint(int)] — this diagnostic must never manufacture a confident
    /// explanation out of a probe that itself failed.
    public static int totalTags() {
        try {
            Process p = new ProcessBuilder("git", "tag")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return -1;
            }
            return (int) out.lines().map(String::trim).filter(s -> !s.isEmpty()).count();
        } catch (java.io.IOException | InterruptedException e) {
            return -1;
        }
    }

    /// The sentence a tag-derived guard should append to its failure message, given how many
    /// tags the checkout carries in total.
    ///
    /// Empty string when the checkout demonstrably HAS tags (so "no matching tag" means
    /// exactly what it says) or when the probe could not run (-1). Non-empty only in the
    /// one case that is genuinely ambiguous.
    public static String unfetchedCheckoutHint(int totalTags) {
        if (totalTags != 0) {
            return "";
        }
        return " NOTE: this checkout carries NO TAGS AT ALL, so the tag may exist on the"
            + " remote and simply not have been fetched — that is what a CI checkout looks"
            + " like, because actions/checkout passes --no-tags unless `fetch-tags: true` is"
            + " set on the step (fetch-depth alone does NOT do it: checkout sends --no-tags"
            + " regardless of depth). Either no release has been cut, or the tags were never"
            + " fetched; from inside the checkout those are indistinguishable. Check"
            + " `git ls-remote --tags origin` before concluding the tag is missing.";
    }
}
