# Release-note entry — plan 8b7596e0 (test / browserTest split)

Staged here rather than in `RELEASE_NOTES.md` to keep this branch free of
merge conflicts in that file. Fold the section below into the `## Unreleased`
block of `RELEASE_NOTES.md` at merge time, then delete this file.

---

### `test` no longer launches Chrome — real-browser pins moved to `browserTest`

- **`./gradlew test` is now a fast, zero-Chrome core suite.** `tasks.test` had no
  tag exclusion, so every real-browser BrewShot pin launched host Chrome on a
  plain `./gradlew test` whenever Chrome was installed — **16 `BrewShot.launch`
  call sites across six classes** — popping the operator's Chrome
  error/permission dialogs on machines the suite wasn't expected to touch.
- **Two of the six classes were entirely untagged**, so a bare
  `excludeTags("capture")` alone would have left them running:
  `BrewShotFxLifecycleTest` (5 launch sites) and `InteractiveMathBrowserTest`
  (3 launch sites). Both now carry a class-level `@Tag("capture")` like their
  siblings, so every launch site is behind the tag.
- **New `browserTest` task carries the browser assertions**, tag-selected
  (`includeTags("capture")`). `check`/`build` depend on **both** `test` and
  `browserTest`, so CI coverage is unchanged — only a bare local `./gradlew
  test` stops launching a browser. `LATTEX_REQUIRE_BROWSER=1` (read by
  `BrowserGate`, set by CI on an image whose Chrome presence is verified in a
  prior step) keeps its existing fail-closed meaning, now against `browserTest`:
  a missing browser fails the task instead of assumption-skipping.
- **`BrowserGate`'s own fail-closed pins stay in core `test`.** `BrowserGateTest`
  drives the gate through its injection seam and never launches Chrome, so a
  `LATTEX_REQUIRE_BROWSER` regression still goes red in the fast suite.
- **Untouched:** `generateExamples` and the tracked `examples/` regeneration
  flow, which stays off `check`/`build` and remains separate from both test
  tasks; and the `examples`-tagged page generators, which do not launch Chrome
  and stay in core `test`.
- **Docs updated:** `CONTRIBUTING.md` (its "no supported Chrome-free command"
  claim, which rested on the lifecycle tests being intentionally untagged, is
  now false and has been replaced with the two-task contract), plus the
  `README.md` and `QUICKSTART.md` build sections and the CI workflow comments.
