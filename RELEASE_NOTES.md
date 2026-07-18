# LatteX — Release Notes

LatteX turns LaTeX math into clean, self-contained **SVG** — pure Java, zero dependencies, safe to drop straight into a web page. New to it? See **[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for use-case walkthroughs.

---

## 0.9.0 · 2026-07-18 — nested inline math inside `\text{…}`, matrix-cell style fidelity, container drift guard + type-safe fill, hermetic test suite + CI clean-tree gate

Renderer changes are the post-0.8.0 mainline set below; the build itself also
hardened (last three items). Stafficy `/docs` still vendors `0.8.0` until it
re-pins this version.

- **A matrix nested in a script shrinks with its context.** A matrix's cell style
  was re-seeded *absolute* (always text style, un-cramped), so
  `x^{\begin{pmatrix}…\end{pmatrix}}` rendered its cells at full size instead of
  script size, and a matrix in a subscript dropped the `cramped` style. The cell
  style is now the smaller of the matrix kind's default and the enclosing style, and
  cramping is inherited — so a matrix in a super/subscript shrinks correctly while a
  top-level matrix and `smallmatrix` are unchanged (fidelity plan bc9b12ff #6, the
  last item on that plan).
- **Container drift guard — the `<span class="lx-math">` attribute surface is now
  build-pinned.** The inner-SVG alphabet has long been closed and guarded
  (`S8LeftContainmentTest`); the *outer* container that `renderStyledHtml` stamps
  had no equivalent, so a future `fx.*` or semantics feature adding an attribute name
  or forwarding an unescaped value could ship undetected. A new test asserts two
  universal invariants over a positive + hostile battery: every container attribute is
  in a closed allow-list (`class` / `aria-label` / the five `data-lx-fx-*` /
  `data-lx-glyphmap` / `data-lx-groupmap` / the open `data-lx-<identifier>` data-attr
  lane), and no attribute value can break out of the tag (no unescaped `<`/`>`/`"`, `&`
  only as an entity). Mutation-verified — an injected `onclick` or a hyphenated
  `data-lx-fx-<new>` fails the build (plan fd6bd568 S2).
- **The emitter fill contract is compiler-enforced.** `SvgEmitter.emit`/`emitFragment`
  took the fill as a `String` with a Javadoc-only "must be a `Color.svgValue()`" rule —
  a future internal caller passing author-derived text would have been a one-line
  containment break with no test. The parameter is now a typed `Color`, so only a
  validated `currentColor`/`#rrggbb` literal can reach the `fill` attribute (plan
  fd6bd568 S3). *(The plan's S1 parse-time DoS guard — a nested-brace bomb throwing a
  clean `MathSyntaxException` instead of a `StackOverflowError` — already landed via
  `MAX_DEPTH`/`MAX_SOURCE_LENGTH`, closing that slice; this branch completes the plan.)*

- **Nested inline math inside text — `\text{if $x>0$ then}`.** An unescaped
  `$…$` span inside a text-family argument (`\text` `\textrm` `\mathrm` `\textbf`
  `\textit` `\texttt`) re-enters math mode, LaTeX's text-mode toggle: the argument
  splits into literal text runs and recursively-parsed math segments — a full
  parse, so commands (`$\frac{1}{2}$`) work inside. `\$` is the literal-dollar
  escape and now renders a plain `$` (the backslash is consumed, matching LaTeX —
  with `$` toggling, the escape is the only way to write a dollar in text); an
  unpaired `$` is a positioned error, an empty `$$` contributes nothing, and a
  plain no-`$` argument parses to the same single run it always did. The inner
  parse **continues the outer parser's depth**, so
  pathological `\text{$\text{$…}$}` nesting hits the `MAX_DEPTH` guard as a clean
  parse error — never a stack overflow. Closed the #1 remaining text-mode
  coverage gap (plan a93c96b3).

- **Full-corpus render sweep — now points at its input on failure.** The sweep that
  runs every renderable `corpus.tsv` row (170 `PARSES-NOW` entries) through the complete
  `render`/`renderInline` pipeline — no-throw, the S8 output-alphabet containment audit,
  and a non-degenerate canvas, per row in both modes — landed on mainline earlier
  (`57efc52`); this cut versions it. Plan 32148cc8 S1's contribution is the failure
  handle: a red now names the exact `corpus.tsv:<line> [latex]` instead of the LaTeX
  alone, so a regression identifies its own input row.
- **Hermetic test suite.** The `examples/` page generators moved out of `test` into a
  `generateExamples` task, and BrewShot reference captures land in `build/` during
  `test` — `./gradlew test` leaves the working tree clean; regenerate the tracked
  examples on demand with `./gradlew generateExamples` (plan 32148cc8 S2).
- **CI clean-tree gate.** The GitHub Actions build now runs `git diff --exit-code`
  after the full suite, so a test writing into the checkout fails CI (plan 32148cc8 S3).

---

## 0.8.0 · 2026-07-17 — commutative diagrams, the norm bar, boxed, `\xlongequal`, braket, `\overparen`, dimensioned glue, `\prescript`, multi-integral & Unicode-operator fidelity, + precedence cascade fx

The release Stafficy `/docs` re-pins (vendored as `lattex-0.8.0.jar`, replacing
0.7.0). Everything below landed on mainline via individually Conf-RTM'd branches.

- **Multi-integral side limits — `\oint` / `\iint` / `\iiint` / `\oiint` / `\oiiint`
  / `\idotsint` now keep their limits *beside* the sign in display style**, like
  `\int`, instead of stacking them above/below. The limit-placement test matched only
  U+222B (`\int`), so every other integral fell through to the display-stack path; it
  now recognizes the whole integral block (U+222B–U+2233) plus `\idotsint` (U+2A0C).
  `\sum` / `\prod` still stack, as they should.
- **Literal pasted Unicode operators get correct spacing.** A pasted `≤`, `×`, `→`,
  `∈`, `∩`, … classified as `default → Ord` (no relation/binary spacing), so `a ≤ b`
  rendered visibly tighter than `a \le b`. Atom classification now consults a reverse
  symbol table derived from the `\command` table, so a literal operator carries the
  same class — and spacing — as its command form. Unmapped code points stay `Ord`.
- **`\prescript{sup}{sub}{base}` — left-attached scripts.** Physics / tensor
  pre-indices, e.g. carbon-14 as `\prescript{14}{6}{\mathrm{C}}`. Pure parser sugar
  over an empty-base `SupSub` (the `{}^{…}_{…}` idiom) followed by the base, so it
  renders identically to the manual form; an empty `{}` slot renders no script on
  that side. No new node, layout, or switch.

- **`\begin{CD}` — amscd commutative diagrams.** The `@`-connector grammar
  (`@>label>>` / `@<<<` / `@VVV` / `@AAA` right/left/down/up, plus `@=` `@|` `@.`)
  renders as a grid whose object cells are ordinary math and whose connectors
  stretch — horizontal arrows to the column width, vertical arrows across the row
  pitch — each carrying script-size side labels. `@=`/`@|` draw as in-alphabet
  rules (U+003D has no horizontal MATH construction); no `<line>`/`<marker>`.
- **`\|` renders the double bar ‖ (U+2016), not a single `|`.** The norm
  delimiter was a fidelity bug: both the symbol table and the `\left`/`\right`
  path mapped `\|` to a single bar. It now matches `\Vert`; the bare `|`
  character stays single, and stretchy `\left\|…\right\|` assembles.
- **`\boxed{…}` (and `\fbox{…}` as its math-mode analogue) — a framed
  sub-formula.** The body laid out normally inside a rule rectangle at fixed
  padding; the frame is four `<rect>`s (in-alphabet), the box behaves as an
  ordinary atom (sits correctly inline, e.g. `E = \boxed{mc^2}`), and the frame
  sizes to the body (a boxed fraction grows the frame to the taller content).
- **`\xlongequal` — the labelled extensible double-equals.** `A \xlongequal{\text{def}} B`
  draws a stretchable double line (`=`) with its label(s) above/below, the
  definitional-equals sibling of `\xrightarrow`. U+003D has no horizontal MATH
  construction, so the shaft is two in-alphabet `<rect>`s that stretch to the label
  width (never a thickened single bar); it composes with the other `\x…` arrows.
- **`\bra` / `\ket` / `\braket` — physics bra-ket notation.** `\bra{\psi}` → ⟨ψ|,
  `\ket{\phi}` → |φ⟩, `\braket{a|b}` → ⟨a|b⟩. Pure parser sugar over the existing
  angle-bracket + vertical-bar atoms, so a braket renders byte-identically to its
  manual `\langle…\rangle` form. Fixed-size v1 (stretchy braket is a follow-up).
- **`\overparen{…}` — the stretchy over-parenthesis accent** (⏜, U+23DC), sized to
  the base like `\widehat`, riding the existing wide-accent machinery (STIX carries
  the glyph + horizontal construction). (`\underparen` — a stretchy glyph *under* the
  base — needs an Accent-node change and is filed as a follow-up.)
- **Dimensioned horizontal glue — `\hspace{2em}` / `\mkern18mu` / `\kern` / `\mskip`.**
  A `<number><unit>` width (braced for `\hspace`, bare or braced for `\mkern`/`\kern`;
  `\hspace*` accepted) becomes a `Spacing` node in math units (18mu = 1em). em/ex/mu
  are exact; `pt` is approximated at a 10pt em (1pt ≈ 1.8mu) — absolute lengths are a
  follow-up. A malformed dimension or unknown unit throws a positioned parse error.

- **`precedence` fx effect — the order-of-operations cascade.** Hover a fenced
  expression and it lights up in *evaluation order*: the innermost group first,
  then outward, so the animation traces how the equation actually binds. Identity
  rides a renderer-emitted `data-lx-groupmap` container sidecar (runs of
  `<rank>:<path-index-list>`, rank 0 = evaluated first), so the runtime never
  guesses precedence from presentation markup. Only opacity/transform on existing
  `<path>`s change — no element is added to the inner `<svg>`.
- **Fenced-only v1, fail-honest.** It reconstructs grouping from delimiters
  (`\left`…`\right` / paren nesting), not operator precedence, so it never
  teaches wrong binding. When grouping can't be determined the whole-expression
  map is withheld and the effect degrades to inert — no partial cascade. Honors
  `prefers-reduced-motion`.

---

## 0.7.0 · 2026-07-14 — the corpus closes: 100% wild coverage

**Wild render coverage 99.4% → 100% (484/484).** Every formula in the real-world corpus now renders, regression-locked by the ratchet. (Unvendored until consumers bump: Stafficy `/docs` still pins 0.6.0.)

- **`eqnarray` / `alignat` environments** — the classic pre-`amsmath` three-column alignment and the explicit-column `alignat{n}`, both routed through the shared alignment grid. Closed 2 wild rows.
- **TeX infix fractions — `\over` / `\atop` / `\choose` / `\brace` / `\brack`** — the primitive infix forms (`{n! \over k!}`, `{n \choose k}`) that predate `\frac`, parsed by group-splitting at the infix operator. Closed the LAST wild-corpus gap row.
- **Numbering no-ops honored — `\nonumber` / `\notag` / `\allowdisplaybreaks`** — display-flow commands with no meaning in a single-equation SVG render are validated and consumed instead of erroring, so pasted textbook alignments render as written.
- **Did-you-mean suggestions** — an unknown command/environment error now carries a Levenshtein-nearest suggestion (`\fraç` → "did you mean \frac?").
- **Hermetic fx runtime harness** — `lattex-fx.js` lifecycle behavior (interval/listener/overlay teardown, re-entry non-growth, reduced-motion) pinned by 15 GraalJS-driven tests with an instrumented DOM stub — no Node toolchain, test-scope only, zero runtime dependencies held.

---

## 0.6.0 · 2026-07-08 — L-series fidelity (the vendored `/docs` build)

**Wild render coverage 98.6% → 99.4% (481/484).** The build Stafficy `/docs` pins.

- **`\middle`** — mid-expression delimiters that size with the enclosure (`\left\{ x \middle| x > 0 \right\}`), plus evaluation-bar receipts.
- **Never-throw floor** — `containRender` boundary: any internal failure degrades to a contained error render, never a propagated exception; an additive `cause` constructor keeps diagnostics whole.
- **Inline baseline metrics** — `InlineSvgResult` carries width/height/depth so an embedding consumer (Stafficy `/docs` inline math) aligns the SVG on the surrounding text baseline exactly.
- **OpenType math-kern script placement** — super/subscripts follow the font's kern staircases instead of fixed offsets; noticeably better italic attachment.
- **Sirentide-parity diagnostics + source-mirror drift guard** — the fragment seam consumed by the diagram renderer is pinned by tests on both sides.

---

## 0.5.0 · 2026-07-08 — embedded fragments, equation numbering & real semantic threading

**Wild render coverage 96.5% → 98.6%** (477/484), plus the semantic moat goes from demo to real.

- **`LatteX.renderFragment(latex, fontSizePx)`** — a new public API returning the inner `<g>/<path>/<rect>` markup + box metrics (`widthPx`/`heightPx`/`depthPx`), for a consumer that composes math inline on a shared baseline (a diagram renderer drawing math-in-labels). No `<svg>` wrapper; same minimal alphabet. Powers Sirentide's math-in-labels.
- **`LatteX.toMathML(latex)`** — Presentation-MathML from the SAME parse tree the SVG walks, so the structure matches the render exactly (`<mfrac>`/`<msup>`/`<munderover>`/`<msqrt>`/`<mtable>`, letters→`<mi>`, digits→`<mn>`, operators→`<mo>`). Gives assistive tech navigable structure instead of the flat aria string, plus an interop/procurement surface — still zero-dependency, pure Java, and verified well-formed XML across the entire wild corpus.
- **`\tag{…}` equation numbering** — a display equation's label renders flush-right, auto-wrapped in parens (`\tag{1}` → `(1)`). `\tag` is equation-global, hoisted to the top level wherever it appears; the label is math-mode (`\tag{5.29}`, `\tag{$*$}`). Closed the entire `\tag` gap cluster (+7 wild rows).
- **Semantic `thread` is real now.** The `thread` effect (hover a token → every occurrence lights up) shipped its runtime + contract in 0.2.1 but had **no producer** — glyphmaps were hand-stamped fixtures. `SvgEmitter.glyphmap` now serializes the `data-lx-glyphmap` token-identity sidecar from the layout (code point → emitted-path indices, in emit order), and `renderStyledHtml` auto-stamps it for a `thread` effect. The "automatic stamping" promised in 0.2.1 finally ships.
- **`\operatorname*{…}` spacing + `\oiint`/`\oiiint`** — operator names now accept spacing commands (`\operatorname*{arg\,max}`, `lim\;sup`); added the surface/volume integral big-operators. (+3 wild rows.)
- **`\displaystyle`/`\textstyle`/`\scriptstyle`/`\scriptscriptstyle`** — bare style switches that restyle everything after them to the end of the enclosing group (unlike `\dfrac`, which pins a single fraction). This is the wrapper MathJax and Wikipedia's MathML→TeX export put around essentially every display formula (`{\displaystyle …}`), so ingesting real scraped/encyclopedia content no longer chokes on the first equation. (+2 wild rows.)
- **Positioned parse errors** — `MathSyntaxException` now carries the source `offset()` of the problem (the lexer threads it through every token), and `caretString()` renders the input with a `^` under the offending column plus the message. The native CLI prints that caret block on a parse error, so `\frac a \foo` points right at `\foo` instead of a positionless "Unknown command". Backward-compatible: the message-only constructor still works and unpositioned errors fall back to just the message.
- **Hardening & honesty** — a matrix cell-count cap (`MAX_MATRIX_CELLS`) closes a breadth-DoS the depth guard missed (a wide+tall grid could force ~4·10⁸ cells from a ~100 KB source); the layout engine now carries its OWN recursion bound (`MAX_LAYOUT_DEPTH`) so an over-deep node tree fails with a caught `MathSyntaxException` instead of a render-thread-killing `StackOverflowError` — layout was previously safe only by coincidence of the parser's separate `MAX_DEPTH`, which a bump or a programmatic tree would silently break; and the CLI version is now single-sourced from the build (`lattex --version` no longer drifts), with the docs reconciled to the actual API.

---

## 0.4.0 · 2026-07-07 — extensible arrows, style-pinned fractions, per-subterm color & a PNG export

The fidelity push continues — **wild render coverage 92.8% → 96.5%**:

- **`\xrightarrow` / `\xleftarrow`** — extensible labelled arrows that stretch to their label(s): `A \xrightarrow{f} B`, `X \xrightarrow[\text{below}]{\text{above}} Y`. The inverse of the stack mechanism (there the brace sizes to the base; here the arrow sizes to its label), routed through the same OpenType-MATH horizontal-stretch machinery. Powers real commutative diagrams.
- **`\dfrac` / `\tfrac`** — style-pinned ruled fractions: `\dfrac` forces display style (a full-size fraction even inline), `\tfrac` forces text style (a small one even in display math) — the `\frac` siblings of `\dbinom`/`\tbinom`. Closed the entire `\tfrac`/`\dfrac` wild-corpus gap cluster (+10 rows).
- **`\color` / `\textcolor`** — per-subterm color: `\textcolor{red}{x^2}`, and a named palette (`red`, `blue`, `gray`, … — xcolor base + CSS) alongside `#rrggbb`. Layout-transparent (zero geometry effect); an inner color wins over an outer one; the fraction bar colors too. Names resolve to a hardcoded hex and an unknown name is a parse error — no new fill value beyond what `#rrggbb` already allowed.

### `bin/lattex-shot` — LaTeX → PNG
SVG is the native output, but sometimes you need a raster. `bin/lattex-shot` renders a LaTeX expression to a tightly-cropped PNG — LatteX's SVG + [BrewShot](https://github.com/supsup/BrewShot), as glue (no new dependency). `echo '\frac{a}{b}' | bin/lattex-shot - -o eq.png`. See the README's **PNG export** section.

### The fx catalogue, in motion
`examples/GALLERY.md` is now the full **23-effect motion catalogue** — every `\lx[fx.*]` effect isolated and captured as its own looping GIF (via BrewShot's element-targeted `recordGifElement`), each one playing itself.

---

## 0.3.0 · 2026-07-07 — the stack mechanism: real assembled braces & stacked annotations

One mechanism closed the largest remaining fidelity gap in the wild corpus — **wild render coverage 88.4% → 92.8%**, 21 `GAP → OK` ratchet flips from a single primitive:

- **`\underbrace` / `\overbrace`** — the horizontal brace is *real*, not drawn geometry: STIX exposes `HorizGlyphVariants`/assembly for U+23DE / U+23DF, and the brace rides the same `stretchHorizontal` path as `\widehat` (correct assembly at every width). A following `_` / `^` feeds the annotation slot beneath/above the brace.
- **`\substack`** — a single-column stacked argument (`MatrixKind.SUBSTACK`); it *is* a one-column `halign`, so it reuses the matrix machinery. Textbook rendering inside `\sum` limits.
- **`\stackrel` / `\overset` / `\underset`** — a base with a scriptstyle annotation stacked above/below, with TeXbook classes: braces are ORD, `\stackrel` is REL, `\overset`/`\underset` inherit the base's class (spacing stays correct in context).

All ride the shared `Stack(base, above, below, kind)` node + `stackBox`; the annotation-routing mirrors `parseBigOperator` (an `Op`-like `_`/`^` binding). BrewShot-verified: `\underbrace`/`\overbrace` assemblies stretch correctly and `\substack`-in-limits reads as the textbook. A gap-term-delete mutant fails the clearance test (the stack spacing is under test, not vacuous).

> `\underbrace{a_0 + a_1 + \dots + a_n}_{n+1\ \text{terms}}` &nbsp;·&nbsp; `\sum_{\substack{0 \le i \le n \\ i\ \text{odd}}} i` &nbsp;·&nbsp; `A \stackrel{\text{def}}{=} B`

Everything from 0.2.1 (the 26-effect fx catalogue, `thread`, placement-safe motion) ships unchanged. The full wild-corpus ratchet + CI gate (GitHub Actions, incl. javadoc + BrewShot pins) guards this cut.

---

## 0.2.1 · 2026-07-06 — the smoke-sweep: placement-safe effects, scroll-aware shows & `thread`

### `thread` — the first *semantic* effect (26th in the catalogue)
Hover any glyph and every other occurrence of the same source token lights up (optically bolded) while the rest of the equation recedes — `x` threads with the `x` in `x²`. Driven by the `data-lx-glyphmap` container sidecar (validated against the pinned contract grammar); **inert until a glyph map is present** — automatic stamping ships with the layout `codePoint` threading.

### Effects fixed by live operator smoke-testing (all found by Charles in one afternoon)
- **Placement-safe per-glyph motion**: `quantum`, `typeset`, `wobble`, `gravwell` set CSS transforms on glyph paths, which *replaced* each path's placement attribute and rendered glyphs as font-unit-sized blobs. A shared primitive now composes user-space deltas with the placement (with `transform-origin` pinned to the SVG attribute's semantics).
- **Scroll ends the show**: fixed-overlay effects (`lightning`, `storm`, `shatter`, `constellation`, `matrixrain`, `supernova`, `teleport`, `sparkler`) played over the wrong content after a scroll — hanging glass, star-map after-images, a page left dark. One `scrollKillable` teardown now ends any of them the moment the viewport truly moves (with a threshold so browser load-time scroll events can't kill an enter-effect at birth).
- **`gravwell` redesigned** (Charles's spec): click *anywhere* on the equation — that point becomes the well: an eclipse orb opens (dark disc, bright corona) and **every** glyph shrinks and spirals into it, holds a dark beat, then spirals back out as the orb collapses.
- **`crystallize` visible on white**: the frost now derives from `fx.glow-color` (or the equation's own ink) instead of a hardcoded ice-blue that vanished on light backgrounds.
- **`sparkler`** restores mid-draw glyphs if torn down early — an interrupted burn can never leave the equation half-invisible.

---

## 2026-07-06 — six new fx effects & the effect drift-guard

### New effects (19 → 25)
- **`shatter`** *(click toggle)* — the equation cracks like glass: a crack-web flashes, shards scatter and hang in zero-g, and the next click magnetically reassembles them.
- **`sparkler`** — a white-hot spark writes the equation in fire, embers spraying off the moving tip as the letters cool into place.
- **`quantum`** — superposition: the glyphs jitter fuzzily between ghost states until you *observe* them (hover) and the wavefunction collapses with a snap-flash.
- **`glitch`** — datamosh: the red/cyan channels rip apart, slice bands shear, then everything snaps back pixel-perfect (pure CSS keyframe).
- **`typeset`** — letterpress: the glyphs stamp onto the page one by one in reading order, each pressed in with a squash.
- **`constellation`** — a night-sky star map ignites along the glyph outlines, faint lines join the stars, then the map fuses into the crisp equation.

All five hold the containment contract — the effects ride the container, existing-`<path>` presentation attributes, or body-level overlays; nothing is ever added to the inner `<svg>`. `prefers-reduced-motion` degrades each to a static or minimal state.
> `\lx[fx.click=shatter]{ x^2-y^2=(x-y)(x+y) }` &nbsp;·&nbsp; `\lx[fx.enter=sparkler]{ \oint_C \vec{B}\cdot d\vec{l} = \mu_0 I }`

### Effect drift-guard
`EffectRuntimeParityTest` pins the `Effect` enum ↔ fx-runtime ↔ CSS agreement as a build-failing invariant, in both directions: an enum entry with no runtime handler (which would silently no-op on the page), a runtime handler no author can reach, or a keyframe effect without its `@keyframes` block each fail the build.

**Browse it:** `examples/effects.html` shows all 25 effects live.

---

## 2026-07-04 — matrices, aligned equations & the `\lx` author syntax

### Matrices, arrays & cases
Grids built from `&` columns and `\\` rows: the whole matrix family (plain, parenthesized, bracketed, braced, determinant, double-bar norm, and inline small), `array` with per-column alignment and vertical/horizontal rules, and piecewise `cases`.
> `\begin{pmatrix}a&b\\c&d\end{pmatrix}` &nbsp;·&nbsp; `\begin{cases}x & x>0\\-x & x<0\end{cases}` &nbsp;·&nbsp; `\begin{array}{c|c}1&2\\\hline 3&4\end{array}`

### Aligned & multi-line equations
Multi-line equations that line up where you want them: `align` / `aligned` line up on the relation (the `&`), `gather` centers each line, `split` breaks a single equation across lines (aligned on the relation), and `multline` sets a long equation's first line flush-left and its last line flush-right. Set in display style, so fractions and big-operator limits stay full size across the block.
> `\begin{aligned}(a+b)^2 &= (a+b)(a+b)\\ &= a^2+2ab+b^2\end{aligned}` &nbsp;·&nbsp; `\begin{gather}a=b\\x+y=z\end{gather}` &nbsp;·&nbsp; `\begin{multline}a+b\\+c+d\\+e\end{multline}`

### The `\lx[…]{…}` author syntax
Wrap any math to style, animate, or tag it — inline, with no separate config. `style.*` (scale, color, math style), `fx.*` (enter/hover/click effects — the classics `glow`/`pulse`/`fade`/`boom`/`lightning`/`storm`, plus `handscribe` (writes itself), `hologram`, `neonsign`, `crystallize` (frost), `blueprint`, `wobble` (jelly jiggle), `gravwell` (spiral black-hole + eclipse), `matrixrain`, `supernova`, `inkdrop`, `diffusion`, `refraction`, and `teleport` — 19 in all), `graph.*` (function-plot popups), and semantic / accessibility tags. Math inherits the page's text color by default (`currentColor`), so it's dark-mode-native out of the box.
> `\lx[style.color=#c0392b, style.scale=1.4]{\frac{a+b}{c}}` &nbsp;·&nbsp; `\lx[fx.hover=glow]{E=mc^2}`

### Multiple integrals
Double, triple, and multi-integral operators.
> `\iint_D f\,dA` &nbsp;·&nbsp; `\iiint_V \rho\,dV`

### Inline vs display sizing
Math for a line of running prose can now be set in **inline** (text) style — smaller fractions and scripts, big-operator limits beside rather than stacked — via `LatteX.renderInline(latex)` / `RenderOptions.defaults().inline()`, or the **`lattex --inline`** CLI flag (works standalone and in `--batch`). No flag / `.display()` picks the default full-size block. The split is what lets a static-site build bake `$…$` that sits on the line vs `$$…$$` as its own block (see SLOWSTART Scenario 4).
> `LatteX.renderInline("\\frac{a}{b}")` &nbsp;·&nbsp; `lattex --inline '\frac{a}{b}'`

**Browse it all:** `examples/effects.html` and `examples/graph.html` show the `\lx` effects and graph popups live; the corpus (`examples/corpus.md`) lists every supported expression with its status.

---

## 2026-07-03 — first release

Everything below shipped today, in the initial release. *(Future updates will each get their own dated section here.)* Hand LatteX a LaTeX math string and get back crisp, scalable SVG — here's what works, with an example of each.

### Fractions & roots
Stacked fractions (centered on the math axis) and square / nth roots.
> `\frac{a+b}{c}` &nbsp;·&nbsp; `\sqrt{2}` &nbsp;·&nbsp; `\sqrt[3]{x}`

### Superscripts & subscripts
Full script placement, alone or combined.
> `x^2` &nbsp;·&nbsp; `a_i` &nbsp;·&nbsp; `x_i^{2}` &nbsp;·&nbsp; `e^{i\pi}`

### Sums, integrals & products
Big operators with limits placed the way TeX does it — stacked above/below for sums and products, to the side for integrals.
> `\sum_{i=1}^{n} i` &nbsp;·&nbsp; `\int_0^\infty e^{-x}\,dx` &nbsp;·&nbsp; `\prod_{k=1}^{n} k`

### Auto-sizing delimiters
Parentheses, brackets, and braces that grow to hug whatever's inside.
> `\left(\frac{x^2}{y^3}\right)` &nbsp;·&nbsp; `\left[ \sum_i a_i \right]`

### Accents
Hats, vectors, bars, tildes — plus wide/stretchy versions that span their base.
> `\hat{x}` &nbsp;·&nbsp; `\vec{v}` &nbsp;·&nbsp; `\overline{a+b}` &nbsp;·&nbsp; `\widehat{ABC}` &nbsp;·&nbsp; `\overrightarrow{AB}`

### Named operators
Trig, log, and friends — set upright (not italic), with limits where they belong.
> `\sin x` &nbsp;·&nbsp; `\cos^2\theta` &nbsp;·&nbsp; `\lim_{x\to\infty}` &nbsp;·&nbsp; `\log_2 n` &nbsp;·&nbsp; `\det(A)` &nbsp;·&nbsp; `\operatorname{lcm}(a,b)`

### Text inside math
Real words in a formula, upright and correctly spaced.
> `\text{if } n \text{ is even}` &nbsp;·&nbsp; `\mathrm{d}x` &nbsp;·&nbsp; `\textbf{note}`

### Greek & 250+ symbols
The full Greek alphabet, plus relations, binary operators, arrows, set/logic symbols, dots, and more.
> `\alpha` &nbsp;·&nbsp; `\leq` &nbsp;·&nbsp; `\Rightarrow` &nbsp;·&nbsp; `\in` &nbsp;·&nbsp; `\subseteq` &nbsp;·&nbsp; `\pm` &nbsp;·&nbsp; `\cdots` &nbsp;·&nbsp; `\infty` &nbsp;·&nbsp; `\nabla`

### Math font styles
Blackboard-bold, calligraphic, fraktur, bold, sans-serif, italic, monospace.
> `\mathbb{R}` &nbsp;·&nbsp; `\mathcal{L}` &nbsp;·&nbsp; `\mathfrak{g}` &nbsp;·&nbsp; `\mathbf{x}` &nbsp;·&nbsp; `\mathsf{ABC}`

### Spacing
Fine spacing control, plus invisible "phantoms" for alignment.
> `a \quad b` &nbsp;·&nbsp; `x \, dx` &nbsp;·&nbsp; `\phantom{x}`

### Styling
Render at a custom scale, color, or math style.
> `LatteX.render(latex, RenderOptions.defaults().withScale(1.4))`

**Browse it all:** open `examples/symbol-index.html` (every supported command, live-rendered) or `examples/showcase.html` (a designed tour).

---

## Why it's safe and portable

- **Safe to inline anywhere.** The SVG uses only `<svg>`/`<g>`/`<path>`/`<rect>`, with glyphs drawn as filled paths — no `<script>`, no `<text>`, no external references or links. That means you can render even reader-submitted math with no cross-site-scripting risk. (A build-time test enforces this, so it can't regress.)
- **Zero dependencies, pure Java.** No JavaScript engine, no headless browser, no external `tex` install. The math font (STIX Two Math) is bundled and its glyphs are baked into the output.
- **Runs from anywhere.** Call it from Java/Kotlin/Scala, or use the standalone **`lattex`** command-line binary from any language — Node, Python, a shell script, a CI step. See QUICKSTART.

---

## What's next

- **Live in docs, made interactive** — `$…$` math renders in a Markdown → HTML pipeline (the flexmark seam), with `\lx` effects, graph popups, and click-to-copy reaching the page.
- **A MathML output option** — for screen readers and assistive tech.
