# LatteX — Release Notes

LatteX turns LaTeX math into clean, self-contained **SVG** — pure Java, zero dependencies, safe to drop straight into a web page. New to it? See **[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for use-case walkthroughs.

---

## 0.5.0 · 2026-07-08 — embedded fragments, equation numbering & real semantic threading

**Wild render coverage 96.5% → 98.6%** (477/484), plus the semantic moat goes from demo to real.

- **`LatteX.renderFragment(latex, fontSizePx)`** — a new public API returning the inner `<g>/<path>/<rect>` markup + box metrics (`widthPx`/`heightPx`/`depthPx`), for a consumer that composes math inline on a shared baseline (a diagram renderer drawing math-in-labels). No `<svg>` wrapper; same minimal alphabet. Powers Sirentide's math-in-labels.
- **`\tag{…}` equation numbering** — a display equation's label renders flush-right, auto-wrapped in parens (`\tag{1}` → `(1)`). `\tag` is equation-global, hoisted to the top level wherever it appears; the label is math-mode (`\tag{5.29}`, `\tag{$*$}`). Closed the entire `\tag` gap cluster (+7 wild rows).
- **Semantic `thread` is real now.** The `thread` effect (hover a token → every occurrence lights up) shipped its runtime + contract in 0.2.1 but had **no producer** — glyphmaps were hand-stamped fixtures. `SvgEmitter.glyphmap` now serializes the `data-lx-glyphmap` token-identity sidecar from the layout (code point → emitted-path indices, in emit order), and `renderStyledHtml` auto-stamps it for a `thread` effect. The "automatic stamping" promised in 0.2.1 finally ships.
- **`\operatorname*{…}` spacing + `\oiint`/`\oiiint`** — operator names now accept spacing commands (`\operatorname*{arg\,max}`, `lim\;sup`); added the surface/volume integral big-operators. (+3 wild rows.)
- **`\displaystyle`/`\textstyle`/`\scriptstyle`/`\scriptscriptstyle`** — bare style switches that restyle everything after them to the end of the enclosing group (unlike `\dfrac`, which pins a single fraction). This is the wrapper MathJax and Wikipedia's MathML→TeX export put around essentially every display formula (`{\displaystyle …}`), so ingesting real scraped/encyclopedia content no longer chokes on the first equation. (+2 wild rows.)
- **Hardening & honesty** — a matrix cell-count cap (`MAX_MATRIX_CELLS`) closes a breadth-DoS the depth guard missed (a wide+tall grid could force ~4·10⁸ cells from a ~100 KB source); and the CLI version is now single-sourced from the build (`lattex --version` no longer drifts), with the docs reconciled to the actual API.

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
