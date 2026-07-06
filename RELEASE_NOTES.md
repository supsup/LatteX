# LatteX — Release Notes

LatteX turns LaTeX math into clean, self-contained **SVG** — pure Java, zero dependencies, safe to drop straight into a web page. New to it? See **[QUICKSTART.md](QUICKSTART.md)** to get going and **[SLOWSTART.md](SLOWSTART.md)** for use-case walkthroughs.

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
- **Equation numbering** — for numbered display equations.
