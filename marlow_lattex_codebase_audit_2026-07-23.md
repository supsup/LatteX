# LatteX Deep Codebase Audit

- **Auditor:** Marlow
- **Date:** 2026-07-23
- **Repository:** LatteX
- **Authoritative baseline:** `main` at `334776537f8978919dac17e6cdde494f34b2e93a`
- **Audit branch:** `marlow/lattex-deep-code-audit-2026-07-23`
- **Scope:** Current Java renderer, parser, layout/font pipeline, CLI, optional browser FX
runtime, public API/module surface, tests, and documentation. This is a read-only code
audit plus this report; no production fixes were applied.

## Executive conclusion

LatteX has unusually strong tests and thoughtful containment work, but several of the
contracts asserted by its comments and public documentation are not true on current
`main`. The most important verified issue is that a parser-admitted, 80,000-character
formula returns a 2,601,491-character SVG with an `OK` diagnostic even though the
renderer advertises a hard 2,000,000-character output ceiling. That same emission path
also constructs every glyph's path string before its first cap check.

The largest performance risk is the assembly algorithm for sufficiently wide horizontal
stretchy glyphs. It repeatedly reconstructs and rescans the complete assembly for every
additional extender, making the fallback quadratic. A 16,384-character
`\overrightarrow{...}` generated 35,386 glyphs and took about 269 ms in a one-shot local
probe; doubling from 8,192 to 16,384 characters cost about 3.6x. Generated assembly
pieces are not charged to the layout-box budget.

There is also substantial repeat work in the normal render path: glyph outlines are
decoded multiple times, path strings are regenerated for SVG and sidecars, non-BMP
character lookup scans font tables linearly, and nested boxes repeatedly copy all
descendant placements. These are tractable because the bundled font is immutable and
the output representations are already immutable.

The clearest correctness drift caused by duplicated pipelines is the `cancel` effect:
`renderStyledHtml` emits its required glyph map, while `tryRenderMath` does not. Other
verified contract gaps include invalid XML/HTML control characters, unvalidated
fragment sizes, an unusable modular exception/style surface, incomplete command
enumeration, and mishandled control symbols inside `\text`.

I recommend fixing the output/resource invariants first, then consolidating the render
pipeline and caching immutable font/emission artifacts. The detailed priority order is
at the end of this report.

## Severity and confidence

- **High:** A public correctness/resource contract is broken, or accepted input has a
  predictable algorithmic amplification path that can materially affect a render
  service.
- **Medium:** A real correctness, integration, performance, lifecycle, or maintenance
  problem with narrower triggering conditions.
- **Low:** An invariant/documentation smell that is unlikely to fail normal rendering by
  itself.
- **Confidence: high** means reproduced on the audited commit or demonstrated by a
  compile probe. **Confidence: medium** means a direct static trace with no browser
  execution in this audit.

## Findings at a glance

| ID | Severity | Confidence | Finding |
|---|---|---|---|
| LTX-01 | High | High | The SVG output cap is bypassable, and glyph output is pre-materialized before checking it |
| LTX-02 | High | High | Stretchy-glyph assembly is quadratic and outside the layout work budget |
| LTX-03 | High | High | Font/outlining/emission hot paths repeatedly decode immutable data and use linear lookup |
| LTX-04 | Medium | High | Flattened `Box` composition repeatedly copies every descendant placement |
| LTX-05 | High | High | Duplicated render pipelines have already drifted: `cancel` loses its glyph map |
| LTX-06 | Medium | High | `renderFragment` accepts zero, negative, NaN, and infinite sizes |
| LTX-07 | High | High | MathML and styled HTML can contain illegal raw control characters |
| LTX-08 | High | High | The exported JPMS API exposes non-exported exception and style types |
| LTX-09 | Medium | High | CLI stdin and batch modes fully buffer unbounded input before per-formula caps |
| LTX-10 | Medium | Medium | Optional FX contains quadratic loops and detached-element lifecycle leaks |
| LTX-11 | Medium | High | Command dispatch, enumeration, suggestions, and macro ownership have different authorities |
| LTX-12 | Medium | High | Single-character control symbols in `\text` silently retain their backslash |
| LTX-13 | Medium | High | The default Gradle test task launches host Chrome when Chrome is installed |
| LTX-14 | Medium | High | Several structural representations encode fragile duplication or positional coupling |
| LTX-15 | Low | High | Public record invariants and documentation have drifted from behavior |

## Detailed findings

### LTX-01 — The SVG output cap is bypassable, and glyph output is pre-materialized before checking it

**Severity:** High · **Confidence:** High

`SvgEmitter.MAX_OUTPUT_CHARS` is 2,000,000 and is described as a hard, incremental
ceiling (`src/main/java/com/lattex/svg/SvgEmitter.java:34-37`). In practice:

- The check occurs only at the start of each emitted-glyph iteration
  (`SvgEmitter.java:121-130`).
- All glyph outlines have already been decoded and converted to path strings by
  `emittedGlyphs` before that loop starts (`SvgEmitter.java:185-193`).
- Rules are appended after the glyph loop with no checks (`SvgEmitter.java:145-170`).
- The closing group/SVG markup and fragment result have no final postcondition check
  (`SvgEmitter.java:69-71`, `99-103`, and `171`).

Reproduction on the audited commit:

```text
source = "\\boxed{}".repeat(10_000)   # 80,000 source characters
renderWithDiagnostics outcome = OK
returned SVG characters = 2,601,491
documented maximum = 2,000,000
```

The formula is under the parser's 100,000-character source cap. Empty boxes generate
rules rather than glyph paths, so they pass the only incremental check. This is both a
correctness failure and a memory-amplification problem. Even glyph-heavy input pays for
the complete `List<EmittedGlyph>` and every path string before the emitter can reject it.

Two relevant fixes already exist off `main` on
`fixpoint/lattex-hostile-input-hardening`:

- `817f0b2` — streams emitted glyphs instead of prebuilding the path-string list.
- `5427c41` — makes the output cap a final postcondition and adds a regression test.

Neither commit is an ancestor of the audited `main`. Reuse their implementation and
tests after rebasing/review; do not assume their surrounding release metadata is current.
The durable design should use one capped append abstraction for wrappers, glyphs, rules,
and returned sidecars, plus a final assertion before returning any artifact.

### LTX-02 — Stretchy-glyph assembly is quadratic and outside the layout work budget

**Severity:** High · **Confidence:** High

Both horizontal and vertical assembly begin with one extender repetition, construct a
complete expanded list, scan its complete span, increment the repetition count, then
reconstruct and rescan the list:

- `assembleHorizontal`: `LayoutEngine.java:2421-2435`
- `assembledStack`: `LayoutEngine.java:2531-2556`
- `expandAssembly`: `LayoutEngine.java:2560-2569`
- `assemblySpanDesign`: `LayoutEngine.java:2572-2578`

For `R` required repeats, this performs work proportional to
`1 + 2 + ... + R`, or O(`R²`), before the final placement pass. The 100,000-box layout
budget only increments for AST-level `layoutBox` calls (`LayoutEngine.java:142-161`);
the generated assembly pieces are not charged to it.

Directional one-shot measurements from current `main`:

| `\overrightarrow` body glyphs | Output glyphs | Layout time |
|---:|---:|---:|
| 256 | 552 | 0.58 ms |
| 512 | 1,105 | 1.56 ms |
| 1,024 | 2,211 | 4.81 ms |
| 2,048 | 4,423 | 17.78 ms |
| 4,096 | 8,846 | 23.33 ms |
| 8,192 | 17,692 | 74.19 ms |
| 16,384 | 35,386 | 268.89 ms |

These are not JMH results and should not be treated as stable absolute timings. The
output and code trace, however, establish the algorithmic shape: after the workload is
large enough to dominate startup/noise, doubling input approaches a 4x cost.

Compute the required extender count algebraically from fixed-part span, extender
advance, and overlap; allocate/emit once. Charge generated pieces or generated output
work to a render budget so a shallow AST cannot create effectively unbounded layout
work.

### LTX-03 — Font/outlining/emission hot paths repeatedly decode immutable data and use linear lookup

**Severity:** High · **Confidence:** High

The bundled `SfntFont` is immutable after construction, but its hot queries are not
memoized:

- `outline(int)` reparses `glyf` data on every call
  (`src/main/java/com/lattex/font/SfntFont.java:358-545`).
- Layout reads the outline while creating atom metrics
  (`LayoutEngine.java:230-241`), then the top-level bounds pass reads it again
  (`LayoutEngine.java:103-122`).
- SVG emission decodes it again and builds path data
  (`SvgEmitter.java:185-193`).
- `glyphmap` and `groupmap` each call `emittedGlyphs` again
  (`SvgEmitter.java:213-218`, `263-265`).
- Styled rendering can request SVG, glyph map, and group map from one layout
  (`src/main/java/com/lattex/api/LatteX.java:422-430`).
- Format-12 cmap and OpenType coverage queries scan sorted records linearly
  (`SfntFont.java:763-800`).

Directional same-process measurements:

```text
100,000 outline("x") decodes:       72.004 ms
100,000 cached object reads:         0.622 ms   (~116x difference)
20,000 outline + path rebuilds:     34.449 ms
20,000 cached path length reads:     0.127 ms   (~271x difference)

1,000,000 glyphId(U+0078):          32.936 ms
1,000,000 glyphId(U+1D465):       1232.096 ms
1,000,000 glyphId(U+10FFFF):      1298.847 ms
```

The goal is not a global render-result cache. Use bounded, immutable, lazy caches keyed
by glyph ID for `GlyphOutline` and path data; their maximum cardinality is the bundled
font's glyph count. Build one per-render emission plan/stream so SVG and semantic
sidecars share path eligibility and identity without regenerating path strings. Replace
the sorted cmap/coverage scans with binary search or indexed lookup. Benchmark complete
representative renders after each step because allocation reduction is as important as
raw lookup time.

### LTX-04 — Flattened `Box` composition repeatedly copies every descendant placement

**Severity:** Medium · **Confidence:** High

Every `Box` owns copied, flattened glyph/rule lists (`src/main/java/com/lattex/layout/Box.java:30-36`).
Every parent composition calls `drawInto`, which creates a new `PositionedGlyph` or
`Rule` for every descendant (`Box.java:48-67`), and the parent `Box` copies the new
lists again. Rows, boxes, fractions, and many other layout forms repeat this pattern.

For equivalent final glyph counts, deeply nested fractions showed the following
directional cost versus a flat row:

| Glyphs | Nested average | Flat average | Ratio |
|---:|---:|---:|---:|
| 33 | 45.9 µs | 26.7 µs | 1.7x |
| 129 | 240.9 µs | 148.3 µs | 1.6x |
| 257 | 766.0 µs | 202.4 µs | 3.8x |
| 401 | 1,253.1 µs | 313.2 µs | 4.0x |

The layout-box counter sees the number of AST boxes, not the number of descendant
copies. Prefer a retained scene graph with child transforms and flatten once at the
top-level boundary. A smaller interim change is to account for copied placements in the
work budget and introduce translation-aware immutable views/builders to avoid
copy-on-every-parent.

### LTX-05 — Duplicated render pipelines have already drifted: `cancel` loses its glyph map

**Severity:** High · **Confidence:** High

LatteX has separate parse/layout/emit sequences for diagnostics, standard rendering,
inline rendering, fragments, styled HTML, and `tryRenderMath`
(`src/main/java/com/lattex/api/LatteX.java:105-117`, `192-222`, `249-265`,
`304-335`, `402-430`, and `608-622`).

The visible drift is concrete:

- `renderStyledHtml` uses the shared `usesGlyphmap` predicate, which includes both
  `THREAD` and `CANCEL` (`LatteX.java:424-429`, `486-489`).
- `tryRenderMath` hard-codes `THREAD` only (`LatteX.java:619-620`).

Reproduction:

```text
source = \lx[fx.enter=cancel]{\frac{x}{x}}
tryRenderMath container keys = [data-lx-fx-enter]
renderStyledHtml contains data-lx-glyphmap = true
```

The split render+attrs seam therefore arms `cancel` without the identity sidecar the
runtime consumes. Current tests pin `thread` at this seam but only cover `cancel`
through the styled-HTML path.

Commit `b455d5e` on `confluence/lattex-tryrendermath-glyphmap-gate` fixes the predicate
and adds a seam test; it is not an ancestor of audited `main`. Reuse the focused
code/test change. Longer term, introduce one internal `RenderPipeline` returning a
`RenderArtifacts` value (parsed body, style/effects, layout, SVG, optional maps,
metrics). Public entry points should only select/serialize artifacts, not independently
reimplement the pipeline.

### LTX-06 — `renderFragment` accepts zero, negative, NaN, and infinite sizes

**Severity:** Medium · **Confidence:** High

`LatteX.renderFragment` passes its public `fontSizePx` directly to `LayoutContext`
(`src/main/java/com/lattex/api/LatteX.java:304-335`). `LayoutContext` has no compact
constructor or size validation (`src/main/java/com/lattex/layout/LayoutContext.java:31-49`).

Observed results:

```text
size 0.0       -> width=0.0,      height=0.0, depth=0.0
size -1.0      -> width=0.484,    height=0.0, depth=0.473...
size NaN       -> all metrics NaN; output contains NaN
size +Infinity -> width Infinity; height/depth NaN; output contains non-finite values
```

Reject non-finite and non-positive sizes at the public boundary and set a documented
upper bound consistent with the scale policy. Add a final finite/nonnegative metrics
invariant in `MathFragment` or the internal artifact constructor so future alternate
paths cannot reintroduce invalid geometry.

### LTX-07 — MathML and styled HTML can contain illegal raw control characters

**Severity:** High · **Confidence:** High

The serializers have three inconsistent policies:

- SVG escaping drops disallowed C0 controls.
- MathML's `xmlEscape` only replaces `&`, `<`, `>`, and `"` and appends every other
  `char` unchanged (`LatteX.java:1063-1076`).
- A quoted `a11y.label` accepts arbitrary characters
  (`src/main/java/com/lattex/parse/LxOptionsParser.java:181-191`), is pre-escaped only
  for HTML metacharacters (`LxOptionsParser.java:345-351`), and is later appended as a
  trusted attribute (`LatteX.java:538-539`).

Reproduction:

```text
LatteX.toMathML("x\u0000y") contains raw NUL             = true
renderStyledHtml(\lx[a11y.label="<NUL>"]{x}) contains NUL = true
LatteX.render("x\u0000y") contains raw NUL                = false
```

NUL is not a legal XML 1.0 character, so the MathML string is not a valid XML document.
The Java HTML fragment also violates the library's own clean-output expectation and
relies on a downstream HTML parser to repair it.

Store semantic text raw rather than already escaped. Apply a shared Unicode/code-point
legality policy at each output boundary, then perform format-specific escaping once.
Test NUL, the remaining disallowed C0 characters, unpaired surrogates, and legal
whitespace across SVG, MathML, aria labels, and data attributes.

### LTX-08 — The exported JPMS API exposes non-exported exception and style types

**Severity:** High · **Confidence:** High

The module exports only `com.lattex.api`
(`src/main/java/module-info.java:5-6`), but:

- The quickstart tells consumers to catch
  `com.lattex.parse.MathSyntaxException` (`QUICKSTART.md:240-262`).
- `RenderOptions`, an exported public record, exposes non-exported
  `com.lattex.layout.MathStyle` in its record component, generated accessor/canonical
  constructor, compatibility constructors, and `withMathStyle`
  (`src/main/java/com/lattex/api/RenderOptions.java:43-45`, `67-74`, `93-95`).

A compiled modular consumer importing `MathSyntaxException` failed with:

```text
package com.lattex.parse is not visible
(package com.lattex.parse is declared in module com.lattex, which does not export it)
```

Compiling the current sources with `javac -Xlint:exports` produced five warnings, all
from `RenderOptions` leaking `MathStyle`. The quickstart itself is contradictory: it
first says consumers never need to name the non-exported type, then documents
`mathStyle` and recommends `withMathStyle` (`QUICKSTART.md:78-91`).

Move the supported public exception into `com.lattex.api` (or expose an API exception
supertype) instead of exporting the parser internals. Move the public style enum into
the API package or make the record component an API-level type. Keep `.inline()` and
`.display()` convenience selectors, but also provide supported selectors for all four
styles. Add a tiny modular-consumer compilation fixture to the build.

### LTX-09 — CLI stdin and batch modes fully buffer unbounded input before per-formula caps

**Severity:** Medium · **Confidence:** High

Single-formula stdin uses `readAllBytes` before parsing
(`src/main/java/com/lattex/cli/Main.java:260-267`). Batch mode also uses
`readAllBytes`, constructs one full `String`, then regex-splits the entire input into an
array before producing the first result (`Main.java:329-338`).

The parser's 100,000-character cap is per formula and runs only after these aggregate
allocations. A multi-gigabyte stream or batch can therefore exhaust process memory
without any individual record crossing the renderer's cap. Existing CLI tests use
small in-memory inputs and do not exercise aggregate bounds.

Use a delimiter-aware streaming reader for newline and NUL modes, enforce a byte/char
limit while each record is read, and write batch output progressively. Decide and
document whether there is also an aggregate job cap; do not rely on the per-record
parser cap for transport-level memory safety.

### LTX-10 — Optional FX contains quadratic loops and detached-element lifecycle leaks

**Severity:** Medium · **Confidence:** Medium (direct static trace; browser execution intentionally deferred)

Three production-scale patterns deserve correction:

1. `constellation` creates 3-14 stars per emitted path with no global star ceiling, then
   scans every star to find each star's nearest neighbors
   (`src/main/resources/com/lattex/fx/lattex-fx.js:1796-1839`). It also creates a radial
   gradient for every visible star on every animation frame (`lattex-fx.js:1877-1915`).
   This is O(`S²`) setup plus allocation-heavy per-frame work.
2. `thread` maps each path to an array of group indices, then every hover scans all
   paths and calls `group.indexOf(i)` (`lattex-fx.js:1929-1942`, `1969-1984`). A large
   repeated-token group approaches O(`P²`) per hover.
3. `hologram` owns a perpetual interval, resize listener, and body overlay; `neonsign`
   owns a perpetual timeout chain (`lattex-fx.js:416-482`, `489-545`). Their cleanup is
   tied to `scrollKillable`, which only observes real scrolling
   (`lattex-fx.js:82-111`). Removing an equation from a no-scroll SPA view does not
   trigger teardown. The comment that element removal is “out of scope” is not a safe
   lifecycle contract for an embeddable runtime.

Set a global constellation star budget and use a spatial grid for nearby-star lookup.
Use boolean membership/indexed group IDs for `thread`. Provide an explicit destroy path
and stop perpetual work when `!el.isConnected`; a scoped `MutationObserver` is another
option if explicit host teardown is not practical.

No browser-backed test was launched in this audit because the user reported repeated
local Chrome error dialogs. These static findings should be validated in the isolated
browser task recommended by LTX-13, including a detached-DOM lifecycle test and a
large-formula frame/setup budget.

### LTX-11 — Command dispatch, enumeration, suggestions, and macro ownership have different authorities

**Severity:** Medium · **Confidence:** High

The actual structural grammar is the large switch beginning at
`src/main/java/com/lattex/parse/MathParser.java:895`. `supportedCommands` enumerates
only the static symbol/operator/accent/font/spacing maps while claiming to reflect
exactly what parsing accepts (`MathParser.java:1839-1872`). A separate, hand-maintained
`STRUCTURAL_COMMANDS` list feeds suggestions only (`MathParser.java:1875-1909`).
Unknown-command handling rebuilds and sorts the map-derived list for each error.

Current results:

```text
supportedCommands contains \boxed       = false
supportedCommands contains \cancel      = false
supportedCommands contains \bra         = false
supportedCommands contains \prescript   = false
supportedCommands contains \bordermatrix = false
\boxd{x} error = "Unknown command: \boxd"   # no \boxed suggestion
```

The generated symbol-index test therefore covers every *enumerated* command, not every
accepted command. Macro ownership avoids the stale list by probing the parser and
interpreting the prefix of its exception message
(`src/main/java/com/lattex/parse/MacroExpander.java:231-255`), which replaces list drift
with a string-protocol dependency.

Create typed command descriptors that own name, category, argument grammar/handler,
example template, and macro-reservation status. Build dispatch, documentation
enumeration, fuzzy candidates, and macro ownership from that authority. At minimum,
expose a typed “unknown command” reason rather than recognizing exception text and cache
the immutable suggestion candidate list.

### LTX-12 — Single-character control symbols in `\text` silently retain their backslash

**Severity:** Medium · **Confidence:** High

The recent fail-loud behavior in `literalText` only rejects a backslash followed by an
ASCII letter and only decodes `\$` (`MathParser.java:1682-1713`). Other control symbols
fall through, discard neither character, and become visible backslashes:

```text
LatteX.toMathML("\\text{50\\%}")
-> <math ...><mtext>50\%</mtext></math>

LatteX.toMathML("\\text{tag\\#1}")
-> <math ...><mtext>tag\#1</mtext></math>
```

The quickstart says the text subset supports plain characters, `\$`, grouping braces,
and nested `$...$` math (`QUICKSTART.md:94-99`). The current result is neither standard
LaTeX control-symbol behavior nor a fail-loud subset.

Explicitly decode the supported text control symbols (`\%`, `\#`, `\{`, `\}`, `\\`,
and any others LatteX chooses) or reject every backslash sequence except the documented
ones. Add parser, MathML, accessibility-description, and SVG-visible-text tests so the
chosen subset is consistent.

### LTX-13 — The default Gradle test task launches host Chrome when Chrome is installed

**Severity:** Medium · **Confidence:** High

`tasks.test` includes all JUnit tags (`build.gradle.kts:49-60`), including the
`capture` tests. There are eight `BrewShot.launch` call sites across the BrewShot
harness tests. `BrowserGate` skips only when Chrome is absent; if it is installed, an
ordinary `./gradlew test` launches it
(`src/test/java/com/lattex/harness/BrowserGate.java:20-35`).

The build comments use “hermetic” to mean the tests write under `build/`, but an
installed external browser is still a host dependency and side effect. It also explains
why a normal local test can produce the Chrome errors the user has been seeing.

Split fast/core `test` from an explicit `browserTest` task. Let CI `check` depend on
both in an image that sets `LATTEX_REQUIRE_BROWSER=1`, while local core testing never
starts Chrome unless requested. Keep capture/reference regeneration separate from both.
This preserves the browser safety net while making its lifecycle and failure surface
explicit.

### LTX-14 — Several structural representations encode fragile duplication or positional coupling

**Severity:** Medium · **Confidence:** High

This is a cluster of maintainability risks rather than one observed render failure:

1. **Math spacing is indexed by enum ordinal.** `SPACING` is a raw 8x8 array tied to the
   exact declaration order of `MathClass`
   (`LayoutEngine.java:468-489`; `MathNode.java:45-61`). The drift guard converts enum
   values to a `Set` (`MatrixKindDriftGuardTest.java:38-44`), so reordering the enum
   passes the test while silently changing spacing. Use a typed pair key, nested
   `EnumMap`, or an order-pinning test.
2. **Matrix and border-matrix geometry repeat a grid algorithm and constants.**
   Standard matrix geometry lives around `LayoutEngine.java:1837-2027`;
   border-matrix repeats row stacking, width aggregation, and `0.3/0.18/0.5em`
   constants around `2040-2150`. Keep their distinct AST semantics, but share a
   `GridMetrics` measurement/placement phase.
3. **The OpenType MATH constants record is a 56-int positional wire schema.**
   `src/main/java/com/lattex/font/MathConstants.java:21-77` is constructed by one long
   positional call in `SfntFont.java:616-687`. An adjacent-field swap compiles and only
   a subset is behavior-pinned. Parse into named locals or smaller semantic records
   before constructing the final value.
4. **Responsibility is concentrated in very large units.** `LayoutEngine` is 2,614
   lines, `MathParser` 1,964, `LatteX` 1,188, and `lattex-fx.js` 2,534. `LatteX`
   combines public facade, containment, SVG/HTML assembly, accessibility prose,
   MathML, optional semantic evaluation, and resource loading. Extract by existing
   boundaries: render pipeline, MathML serializer, accessibility describer, HTML
   container serializer, and resource loader.
5. **Bundled JS/CSS are reread and decoded on every accessor call.**
   `LatteX.fxRuntimeJs`/`fxStylesCss` eventually call `readAllBytes` on every request
   (`LatteX.java:1164-1186`). In a directional probe, 500 JS reads took 50.177 ms;
   reusing one returned string took 0.007 ms. Cache both immutable resources using the
   same lazy-holder pattern already used for the font.

These refactors should follow, not precede, the behavioral fixes. Extracting the shared
render pipeline (LTX-05) and emission plan (LTX-03) provides useful seams without a
large speculative rewrite.

### LTX-15 — Public record invariants and documentation have drifted from behavior

**Severity:** Low · **Confidence:** High

Examples:

- `RenderedMath` promises an immutable attribute map but has no defensive-copy compact
  constructor.
- `MathFragment` documents non-null MathML and nonnegative metrics but its record
  constructor accepts null/NaN/negative values.
- `RenderResult` documents non-null diagnostics but does not enforce it.
- `InlineSvgResult` documents nonnegative metrics but accepts non-finite/negative data.
- `Color.BLACK` still says it is the default renderer fill, while public defaults use
  `currentColor`.
- Effect parsing supports far more values than its nearby Javadoc lists.
- The deprecated `fxContainerAttrs` removal note says “after 0.7” even though the
  audited line is 0.11-era code.
- The quickstart contains an older embedding description that conflicts with its newer
  fragment/baseline API and, as noted in LTX-08, contradictory JPMS guidance.

Add compact constructors where the record owns a real public invariant. Then do one
documentation pass from current tests/API signatures; delete stale transitional text
rather than trying to reconcile multiple generations of guidance.

## Existing work that should be reused

These commits are visible locally but are **not** ancestors of the audited `main`:

| Commit | Branch | Relevance |
|---|---|---|
| `817f0b2` | `fixpoint/lattex-hostile-input-hardening` | Streams emitted glyphs; addresses the pre-materialization half of LTX-01 |
| `5427c41` | `fixpoint/lattex-hostile-input-hardening` | Makes the SVG cap a postcondition and adds hostile-input coverage for LTX-01 |
| `b455d5e` | `confluence/lattex-tryrendermath-glyphmap-gate` | Uses the shared glyph-map predicate and tests `cancel` for LTX-05 |

Review and rebase the focused production/test diffs. In particular, do not blindly
cherry-pick version bumps or release-note context from an older branch.

## Rejected or superseded hypotheses

The following plausible concerns were checked and are **not** current findings:

- Full-document and fragment SVG emission do not maintain separate painting logic;
  both call `emitInner`.
- The bundled font is already loaded through a lazy singleton. The issue is repeated
  outline/path decoding, not repeated font-file construction.
- I found no confirmed unsafe publication or mutation race in `SfntFont`; it is built
  before publication and its parsed structures are immutable.
- Layout thread-local counters are small, reset per top-level layout, and recursion depth
  is balanced in `finally`.
- Matrix/CD measurement passes reuse laid-out cell boxes rather than rendering each cell
  twice.
- Inline path duplication is part of the deliberately minimal, self-contained SVG
  alphabet; I do not recommend a blanket `<defs>/<use>` rewrite without measuring
  browser and sidecar consequences.
- The earlier `MatrixKind` default-branch drift was fixed on current `main`. LTX-14 is
  the different, still-live ordinal-coupling problem for `MathClass`.
- Effect enum/runtime/CSS name duplication is covered by parity tests; no current name
  mismatch was found.
- Separate `BorderMatrix` AST semantics are justified. Only its grid-geometry machinery
  should be shared.
- A global full-render cache is not recommended: input cardinality and tenant lifetime
  make it harder to bound than the immutable per-glyph caches proposed in LTX-03.

## Verification performed

All checks were run from the isolated audit worktree at the baseline SHA. No browser was
started.

- Compiled production and test classes: `./gradlew classes testClasses --no-daemon`
  — passed.
- Built the jar: `./gradlew jar --no-daemon` — passed.
- Ran focused non-browser suites:
  `RenderedMathSeamTest`, `RenderFragmentTest`, `MathMLTest`,
  `HostileInputHardeningTest`, `ResourceCapDirectTest`, `MainTest`,
  `FxRuntimeLifecycleTest`, and `SilentFlattenRegressionTest` — **69 tests, 0 failures,
  0 errors, 0 skipped**.
- Compiled two JPMS consumer probes: ordinary `RenderOptions` chaining compiled;
  importing the documented typed exception failed because its package is not exported.
- Compiled production sources with `javac -Xlint:exports`: five warnings, all from
  `RenderOptions` exposing non-exported `MathStyle`.
- Ran a package-scoped Java audit probe for the concrete reproductions and directional
  timing data reported above.
- Compared relevant off-main fixes with current ancestry rather than treating their
  existence as proof that `main` was fixed.

The standard full Gradle suite was deliberately not run because it includes eight
Chrome-launching BrewShot paths and the user reported repeated local Chrome errors.
LTX-13 recommends preserving those assertions behind an explicit browser task.

## Recommended implementation order

1. **Restore hard resource invariants.** Rebase/review `817f0b2` and `5427c41`; add the
   rule-dominated 80k-source regression; algebraically solve stretchy assembly and
   budget generated pieces.
2. **Remove known output drift.** Reuse the focused `b455d5e` change/test, then create
   one internal render-artifact pipeline.
3. **Make serializers and geometry reject invalid state.** Normalize illegal code
   points at output boundaries; validate fragment size and final metrics.
4. **Eliminate immutable hot-path repeat work.** Cache outlines/paths/resources, use
   binary font-table lookup, and share one emission plan per render.
5. **Repair the supported API boundary.** Move exception/style types into the exported
   API and add a modular-consumer compile fixture.
6. **Bound transport and browser-runtime work.** Stream CLI records; cap/star-index the
   constellation; use constant-time thread membership; add detached-element teardown.
7. **Unify grammar metadata and text control-symbol policy.** Replace the multiple
   command authorities and add explicit `\text` behavior.
8. **Refactor fragile structures after behavior is pinned.** Address box flattening,
   grid geometry, ordinal spacing, positional MATH constants, public record invariants,
   and stale documentation.

## Bottom line

This is not a recommendation to rewrite LatteX. The parser/layout model and test corpus
provide good foundations. The highest-value changes are narrow and evidence-backed:
restore the output cap already implemented off-main, solve one quadratic loop, cache
immutable font artifacts, and make all public render surfaces consume one internal
pipeline. Those changes remove the most serious resource and drift risks while also
creating safer seams for the later code-health work.
