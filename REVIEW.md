# REVIEW.md (LatteX)

How to run a review is general practice; this file is what failure looks like in
THIS repo. Keep it short; add a class only when a real fix taught it.
The classes are where this repo has failed before, not a limit on what a reviewer may raise.

## What failure looks like here

- **Containment:** an Error (e.g. StackOverflowError) escapes a public entry point
  instead of arriving as the one typed failure, so it can reach a live page.
- **Output legality:** untrusted input reaches SVG, aria-label or a diagnostic
  message raw (NUL, ESC, newline), or a size cap holds at one site but not as a
  postcondition of every returned artifact.
- **Silent-wrong math:** input is accepted and renders plausibly but means something
  else, or SVG and MathML disagree about the same parse.
- **Blind gate:** a check that cannot see its target in its real environment (no tags
  in the checkout, browser pins skipped, a smoke test failing with no output).
- **Stale truth:** a byte hash, comment, javadoc or release note that no longer
  matches the code.

## Recurring classes (each taught by a real tip)

1. **A sibling entry point skips the shared boundary or gate.** One seam caught
   RuntimeException but not Error; others parsed outside containRender (a1b9d5f,
   c69542a); a seam kept a private, drifted copy of a shared gate (ec78f26).
   Check: every public render-adjacent method, parse stage included.
2. **The instance is fixed, the class is left open.** Surrogates were escaped while
   other ISO controls passed raw; the output cap ran only at the glyph-loop top
   (202a6f3, 79c7c14, f7ae51e). Check: name the class, test a second member.
3. **Lexer escapes not consumed atomically.** The \{ fix regressed \\{ (400002b,
   8563a32). Check: even and odd backslash runs, nested math in \text.
4. **No fixture has the shape that breaks.** 2i unfolded to 21; commands in \text
   flattened silently (2b88ace, 140f4e1). Check: coefficients, adjacency, real corpus.
5. **SVG and MathML diverge on one rule.** Integral limits, \limits, labelled braces
   (256e2e7, 7f2a63d, 97cdd4f). Check: both outputs read the same predicate.
6. **A gate blind in CI.** A tagless checkout broke the tag-derived guards (90c0bf4);
   a Docker smoke pinned an old version and failed silently (9dd618d); the exported-surface
   gate read inputs from the ambient checkout (8acdf45). Check: prove the gate goes red
   where it runs.
7. **A false or dropped claim in prose.** Comments, javadoc, release notes lost in a
   merge (f5a0e0f, 7863bae, 1732215). Check: measure the claim before it ships.

## Where to put extra depth

LatteX.java entry points and containRender; MathParser and OutputLegality; the
SvgEmitter output alphabet (a contract with downstream sanitizers) and the output
cap; MathML parity; lattex-fx.js and its test hook; any byte-golden re-pin (needs a
causal proof, not "the test printed a new hash"); the exported API surface and
module-info; ci.yml checkout inputs; trust/ scripts, Docker smoke, version guards.
Non-trivial layout code must cite its TeXbook App. G rule (clean-room rule).

## Required evidence

A verdict names, at the exact tip: `./gradlew build` (not `check`: build also runs
javadoc, check does not) with LATTEX_REQUIRE_BROWSER=1 and Chrome present, its JUnit
report count, an empty `git status --porcelain` afterwards, and
`tools/exported-api/check.sh <base> <tip>`, which CI runs on pull requests and on pushes to main. `./gradlew test` alone is NOT the gate: it excludes
the browser pins.
