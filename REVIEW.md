# REVIEW.md (LatteX)

Status: PROPOSED by Fixpoint 2026-11-09 for crew review (Charles asked for REVIEW.md
beyond stafficy). It is adopted and amended the same way as stafficy's REVIEW.md, by
crew majority. How to write and run a review is the platform practice in stafficy's
`START_HERE/REVIEW_PRACTICE.md`; this file is only what failure looks like in THIS repo.
Keep it short; add a class only when a real tip taught it.

## What failure looks like here

- **Clean room:** a non-trivial algorithm with no primary-source citation, or code that
  mirrors another renderer's structure (CONTRIBUTING section 1). This is a licence defect,
  not a style nit.
- **Runtime dependency:** anything beyond `java.base` on the runtime classpath
  (CONTRIBUTING section 2).
- **Output contract:** emitted SVG leaves the sanitizer-safe subset that the two-way
  containment (S8) harness pins (CONTRIBUTING section 5).
- **Looks right in tests, renders wrong:** rendered output changed and nobody looked at it.

## Recurring classes (each taught by a real tip)

1. **`./gradlew test` is not the gate.** `test` excludes `browserTest`; only `check` or
   `build` runs the browser pins, and a local run without Chrome can omit them.
   Check: the verdict names `./gradlew build` at the exact tip and whether the browser
   pins ran.
2. **A dead javadoc `{@link}` passes locally and fails CI.** It compiles and passes `jar`,
   but CI's full `build` runs `:javadoc`, which hard-fails. Check: the GitHub Actions run
   for the exact tip, not the push.
3. **Parameterized cases collide in the XML.** Gradle names a parameterized case by its
   display name alone (no method, no index), so two methods sharing a name pattern
   overwrite each other in the report (28201def; per-method prefixes on its branch at 430a7fbf,
   2026-11-09, not yet on main).
   Check: count distinct case names in the XML against the expected inventory.
4. **A test inlines a compile-time constant.** A `static final` budget such as
   `MathParser.MAX_DEPTH` is compiled into the test, so a cached test result says nothing
   about a changed constant (28201def). Check: `--rerun-tasks`, and say so.
5. **A JVM or stack size is not a portable floor.** A test that requests a small thread
   stack (256 KiB in 28201def) proves behaviour on this JVM only (lattex 1574, 1580).
6. **A retired behaviour rides back in a bundled branch.** Retiring a branch does not
   retire what it carried; until main pins the preserved behaviour with a test naming the
   ruling, a later carrier revives it silently (splitpos: retired at lattex 650, back at 750).
7. **A rendered-output READY names the eyes and the whole docs set.** Look at the output
   (BrewShot render) and check README, `RELEASE-NOTE-ENTRY.md`, QUICKSTART/SLOWSTART and the
   examples, not only the guide that changed.
8. **A release branch is red until its tag exists.** A bare version fails the two
   tag-guard tests by design; tag the exact land commit and push main and the tag together.
   The version lives in `build.gradle.kts`.
9. **Approval conditioned on a code change approves nothing.** Meeting the condition makes
   a new sha, and the land refuses a verdict pinned to the old one (lattex, 2026-09-08).
   Ask for the change and re-review the new tip instead.

## Where to put extra depth

`MathParser` depth and length budgets and their backstops, SVG emission and the sanitizer
subset, the `lattex-fx.js` runtime (behaviourally pinned by its harness), the exported API
surface (`tools/exported-api`), and the release and tag path.

## Required evidence

A verdict on code names a full `./gradlew build` at the exact tip, with its test count and
whether the browser pins ran, plus the GitHub Actions run at that tip. The land rail's
pre-land gate for this repo runs `./gradlew test --rerun-tasks`, `browserTest` with
`LATTEX_REQUIRE_BROWSER=1` (so a missing browser fails instead of skipping) and `jar`, as
`ai branch review-request` prints them; a verdict that skipped any of those says so.
