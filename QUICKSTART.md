# LatteX Quickstart ☕

Render LaTeX math to SVG, then drop it straight into your HTML.

> **Status note.** LatteX is early but real — most of what's described here is
> **built and on `main`**: the render core, `\lx` syntax, inline/em sizing, `fx`
> effects, `renderFragment` (embedded math), and the native CLI (S7). **Planned,
> not yet in the API:** the click action menu (Copy LaTeX / Graph), Graph plotting,
> the HTTP service, the WASM build, and editor/markdown plugins — each flagged
> inline and in the [status legend](#7-status-legend). Accuracy over hype: if it
> isn't built, this doc says so.

---

## 1. What LatteX is

LatteX is a clean-room, pure-**Java 25**, **zero-runtime-dependency** library that
renders LaTeX math to **SVG** — no JavaScript engine, no headless browser, no
external `tex` binary. Its emitter targets a deliberately tiny, sanitizer-safe SVG
alphabet — only `<svg>`, `<g>`, `<path>`, and `<rect>`, with glyphs drawn as inline
filled `<path>`s (never `<text>`, `<use>`, `<defs>`, `<script>`, or external
`href`s) — so the output is safe to inline directly in HTML and already sits inside
a standard sanitizer allow-list. The math font (**STIX Two Math**, OFL) is bundled,
so there are no web fonts to load either. Apache-2.0.

**What it can render:** measured, not claimed — 506 of 506 real-world formulas
(100%) from the wild corpus render clean, regression-locked by a coverage
ratchet that only moves up. Browse the tour: **[examples/showcase.html]
(examples/showcase.html)** (highlights incl. matrices, cases, align, bra-ket,
and the mod/logic/dots families); struck-through terms via the cancel family
— `\cancel{x}` (up `/`), `\bcancel{x}` (down `\`), `\xcancel{x}` (an `X`), and
`\cancelto{value}{x}` (a struck arrow to a target value); the full command
inventory is the generated
[examples/symbol-index.html](examples/symbol-index.html), and the parse-tier
ledger is [examples/corpus.md](examples/corpus.md).

## 2. Render one expression

The whole core is one call. Give it LaTeX (no surrounding `$` delimiters), get back
a self-contained SVG string:

```java
import com.lattex.api.LatteX;

String svg = LatteX.render("x^2");
// -> <svg xmlns="http://www.w3.org/2000/svg" viewBox="..." width="..." height="..."
//         role="img" aria-label="x squared"> ... </svg>
```

That SVG is complete and standalone — write it to a `.svg` file, or paste it inline
into a page.

**With styling.** `render(String, RenderOptions)` takes a typed, validated
options object — the two knobs reachable from the public `com.lattex.api`
package are `scale` and `color`:

```java
import com.lattex.api.LatteX;
import com.lattex.api.RenderOptions;
import com.lattex.api.Color;

RenderOptions opts = RenderOptions.defaults()   // scale 1.0, currentColor, DISPLAY
    .withScale(1.5)
    .withColor(Color.parse("#c0392b"));

String svg = LatteX.render("\\frac{a+b}{c}", opts);
```

> Math renders in **display** style by default. For math set in a line of prose,
> use **inline** (text) style — smaller fractions and scripts, big-operator limits
> set beside rather than stacked, so it sits on the text line:
>
> ```java
> String inline = LatteX.renderInline("\\frac{a}{b}");                  // convenience
> String same   = LatteX.render("\\frac{a}{b}", RenderOptions.defaults().inline());
> ```
>
> `RenderOptions.defaults().inline()` / `.display()` are api-only selectors, so you
> never have to name the (non-exported) style type.

- **`scale`** — output size multiplier (default `1.0`), folded into the effective
  font size so the whole geometry scales as crisp vector output, not a CSS zoom.
  Bounded to `[0.1, 20.0]`.
- **`color`** — a validated `Color`: `Color.CURRENT` (emits `currentColor`, so the
  math inherits surrounding text color and survives dark mode — the default) or
  `Color.parse("#rrggbb")` / `Color.parse("#rgb")`.
- **`mathStyle`** — the top-level TeX `MathStyle`: `DISPLAY` (default), `TEXT`,
  `SCRIPT`, or `SCRIPT_SCRIPT`.

`RenderOptions` is an immutable `record`; derive variants with `withScale` /
`withColor` / `withMathStyle`. See `examples/x-squared.html`, `gallery.html`, and
`styled.html` for rendered output.

**Fluid (scale-to-fit) display math.** A display equation has a fixed natural width,
so a wide formula can overflow a narrow container. Opt in with
`RenderOptions.defaults().withFluid(true)` and the display `<svg>` carries **one**
sizing rule — `width:100%;max-width:<natural>px;height:auto` — so it shrinks to fit
a narrower container and never upscales past its natural size (the unchanged viewBox
keeps the aspect ratio). Presentation-only: the geometry, viewBox, and every glyph
are byte-identical to the fixed-size render, and this is scale-to-fit, **not**
line-breaking. Inline math (`renderInline` / `renderInlineResult`) and
`renderFragment` never go fluid — baseline seating depends on fixed sizing. Default
**off**: without the flag, output is byte-identical to previous releases. (Honest
scope note: Stafficy `/docs` does **not** consume fluid yet — its sanitizer strips
the style attribute, so `/docs` output is unchanged until a separate, deliberate
two-sided carve-out lands. Fluid works today in any standalone embedding.)

> **Which letters slant.** LatteX follows TeX's default math alphabet. A Latin
> letter is **math italic** (`x` draws 𝑥, `h` draws ℎ), and so is lowercase Greek
> (`\alpha`, `\epsilon`, `\phi`, …) and `\imath`/`\jmath`. Digits, uppercase Greek
> (`\Gamma`, `\Omega`), `\partial` and `\nabla` stay **upright**, as do whole words:
> function names (`\sin x` is upright *sin*, italic *x*), `\operatorname{…}`,
> `\mathrm{…}`, `\text{…}` and the legacy `{\rm …}` switch (which, as in TeX,
> straightens Latin letters only). `\mathit{x}` is the same glyph as a bare `x`. A `-`
> in math is the **minus sign** U+2212 — binary (`a-b`), unary (`-1`) or in a
> script (`x^{-1}`) — while `\text{a-b}` keeps its hyphen. The source stays what you
> typed: `toMathML` emits `<mi>x</mi>` (italic by MathML's own default),
> `<mi mathvariant="normal">Γ</mi>` and `<mo>−</mo>`, and the `thread`/`substitute`
> effects and the accessible label still key on `x`.
>
> **Words inside math — `\text{…}`** (and `\textbf`/`\textit`/`\texttt`/`\textrm`/
> `\mathrm`, and TeX's `\hbox`, which takes `\text`'s contract whole). The argument is *literal text*: plain characters (spaces preserved),
> invisible grouping braces, `$…$` or `\(…\)` to re-enter math mode
> (`\text{if $x>0$ then}`, `\text{If \(K\) is categorical}` — the two spellings
> are the same toggle, and the math takes the surrounding style),
> and an EXPLICIT set of control-symbol escapes that decode to their literal
> character — `\$` `\%` `\#` `\_` `\&` `\{` `\}` — plus `\,` (thin space) and the
> control space `\ ` (`\mathrm{in\ the\ interval}`), which decode to a plain space
> (text runs have no sub-em spacing unit); a tie `~` is a space too. The accents
> found in names — `\"` `\'` `` \` `` `\^` `\~` on one letter, bare or braced
> (`K\"ahler`, `Poincar\'e`, `\'{e}tale`, `\'{\i}`) — become the precomposed
> character (ä, é, í); a letter with no precomposed form fails loud. `\ref{key}`
> and `\eqref{key}` draw the same unresolved marker as in math mode, `??` and
> `(??)` (LatteX has no document to resolve labels against; the key is never
> shown). Every other
> backslash sequence fails loud: a command (`\text{see \cite{k}}` fails with
> `Unknown command in \text: \cite`), an unmapped control symbol (`\=`, `\.`),
> `\\` (a line break in real LaTeX — text runs are single-line, so it has no
> target and is rejected rather than silently dropped), or a trailing lone `\`.
> A math accent inside `\mathrm` (`\mathrm{\acute et}`) is still refused; write
> `\text{\'et}` or `\acute{\mathrm{e}}\mathrm{t}`.
> Nothing is ever silently flattened *or* left with a stray backslash; wrap math
> in `$…$` or `\(…\)` instead.
>
> **Braces around a one-token argument are optional, as in TeX.** An argument is
> the next token — one character, one control sequence, or one `{…}` group — with
> spaces before it skipped. So `\frac12`, `\sqrt2`, `\hat x`, `\mathrm d`,
> `\text a`, `x\pmod q`, `\boldsymbol 1_A`, `\phantom x`, `\tag1` and `\label k`
> mean exactly their braced forms, for every argument-taking command (environment
> arguments, such as `\begin{array}`'s column spec, still need their braces). It is
> ONE token: `x\pmod q r` is `x\pmod{q}r`, and `\mathrm dx` is `\mathrm{d}x`. A text
> command's one token is still literal text (`\mathrm\alpha` fails exactly as
> `\mathrm{\alpha}` does). With no token at all — end of input, `}`, `&` or `\\`
> — the error is the usual typed one, with the caret on the spot where the argument
> is missing; a cell or row boundary is never silently taken as an argument.
>
> **Inner alignment environments.** `aligned`, `split`, `gathered`, `alignedat`
> (with its mandatory `{n}`, like `alignat`) and mathtools' `multlined` render
> standalone exactly as their display twins (`align`, `gather`, `alignat`,
> `multline`), unnumbered.
>
> **Their position argument.** The optional `[t]`/`[b]`/`[c]` after
> `\begin{aligned}`/`\begin{split}`/`\begin{gathered}`/`\begin{alignedat}`/
> `\begin{multlined}` is parsed and **ignored**: it selects which
> row's baseline anchors the box in surrounding text, and LatteX renders the
> environment standalone, so it has no visual effect. Anything else in the bracket
> fails loud, matching `array`'s column-spec discipline.
>
> **`\hspace` / `\kern` units.** `em`, `ex` and `mu` are exact; `pt` is taken at a
> 10pt em (1pt = 1.8mu), and TeX's physical units `mm` `cm` `in` `bp` `pc` `dd` `cc`
> `sp` convert to pt by TeX's own ratios (1in = 72.27pt, 2.54cm = 1in, …) and then
> through that same pt anchor.
>
> **Operators you build yourself.** `\mathop{…}` makes an Op atom with TeX's limit
> behaviour: `\mathop{\mathrm{colim}}_{i\in I}` sets the limit **under** it in display
> style and beside it in text style, and `\limits`/`\nolimits` after it force either
> way. A single-symbol body (`\mathop{\boxtimes}_{i}`) is a large operator, centred on
> the math axis. `\varprojlim`/`\varinjlim`/`\varliminf`/`\varlimsup` are such
> operators already: "lim" with an arrow or a rule exactly as wide as "lim" (never
> wider, so the next atom never runs into it).
>
> **Stretchy arrows over and under.** `\overleftarrow` `\overrightarrow`
> `\overleftrightarrow` and `\underleftarrow` `\underrightarrow` `\underleftrightarrow`
> draw the bundled font's own extensible arrow at the width of what they decorate,
> as TeX does: exactly that width wherever the font's arrow pieces can reach it, and
> otherwise the widest arrow the font can draw that still fits (under "lim", 1340 of
> 1420 font units). Only a base narrower than the font's smallest arrow
> (`\underleftarrow{i}`) is widened to the arrow, base centred, so nothing overlaps.
>
> **`@{…}` and `!{…}` in an array column spec.** `@{math}` puts its material between
> the columns IN PLACE of the intercolumn space, on every row, as LaTeX does:
> `{c@{\qquad}c}` widens the gap, `{@{}l@{}}` drops the edge space,
> `{c@{\;\to\;}l}` draws an arrow between the columns. `!{math}` puts it there and
> keeps the space. Two expressions, or a `|` and an expression, at the SAME column
> boundary fail loud (LaTeX would concatenate them; LatteX keeps one item per
> boundary rather than guess at the order). `p{…}`, `*{…}` and the other `array`
> package types are still refused.
>
> **Arrows as `\big` delimiters.** `\big`/`\Big`/`\bigg`/`\Bigg` (and their
> `l`/`r`/`m` forms) and `\left`/`\right` take `\uparrow` `\downarrow` `\updownarrow`
> `\Uparrow` `\Downarrow` `\Updownarrow` — the hand-drawn commutative-diagram idiom
> `\big\downarrow` — and `\backslash` (the reverse solidus, as in
> `\mathbin{\big\backslash}`).
>
> **`\not` and the negated relations.** `\not` keeps the single Unicode character
> wherever Unicode has one (`\not\simeq` is ≄, `\not\sqsubseteq` is ⋢). Over anything
> else it draws an overstrike: the bundled font's negation slash (U+0338, the same
> stroke as in ≠) centred on the symbol, which keeps its own width and spacing class,
> so `a\not\perp b` spaces as a relation and `\not D` is the physicist's slashed D.
> amssymb's negated relations are all accepted (`\nsimeq` `\lneq` `\gneqq` `\lnsim`
> `\npreceq` `\subsetneqq` `\nsqsubseteq` `\precnapprox` …), the ones Unicode does
> not precompose (`\nleqslant` `\ngeqslant` `\nleqq` `\ngeqq` `\nsubseteqq`
> `\nsupseteqq`) as the same overstrike; a PASTED ≄ ≉ ≢ ⋠ … now spaces as a relation,
> like its command. Not accepted: `\varsubsetneq` & co, `\lvertneqq`/`\gvertneqq`
> (Unicode spells them only as a variation sequence) and `\nshortmid`/`\nshortparallel`.
> In MathML an overstrike is the symbol followed by U+0338 (`<mo>⊥̸</mo>`).
>
> **Two narrow acceptances, stated.** `\lhook` is plain TeX's hook *piece*, which has
> no glyph of its own in Unicode or the bundled font, so it is accepted only in the
> composites it exists for — `\lhook\joinrel\rightarrow` (= `\hookrightarrow`) and
> `\lhook\joinrel\longrightarrow` (the long hooked arrow) — and fails loud anywhere
> else. `\qedhere` (amsthm) is accepted and renders nothing: the proof's end mark
> belongs to the surrounding document, not the formula.
>
> **General fractions, spanning cells, row tags.** `\genfrac{left}{right}{thickness}{style}{num}{den}`
> is amsmath's general fraction — `\genfrac{[}{]}{0pt}{}{n}{k}_q` is the Gaussian
> binomial, and `\genfrac(){0pt}{}{n}{k}` is exactly `\binom{n}{k}`. A delimiter slot
> takes anything `\left` takes, or `{}` for none; the thickness is empty (the normal
> bar) or zero (no bar) — any other thickness fails loud; the style is empty, `0`
> (display), `1` (text), `2` or `3` (script sizes). `\multicolumn{n}{spec}{body}` opens
> an `array`, matrix or `cases` cell spanning `n` columns, aligned by its own one-column
> spec (`l`/`c`/`r` with optional `|` rules); the rules inside the span are not drawn
> on its row, and a span wider than its columns widens the last one, as TeX does. A span
> also covers `@{...}`/`!{...}` material inside it and at its right edge (on its row
> only); material to its left is still drawn, and every boundary keeps its width.
> `\tag{…}` on a row of `align`/`gather`/`alignat` (starred or not) numbers that row,
> drawn at the row's baseline in a right-aligned column after the grid; anywhere else
> — inside `gathered`/`aligned`/`split`, `equation`, a group or after `\displaystyle` —
> it tags the whole equation. One tag per row, one per equation. A `\tag` after a
> trailing `\\` (`a\\ \tag{1}`) numbers a real empty last row, as amsmath does.
>
> **Limits, stated.** `\genfrac`'s style accepts only empty, `0`, `1`, `2` and `3`;
> `4` and above fail loud. Its thickness unit must be lowercase (`0pt`, `0mm`, ...):
> TeX reads units case-insensitively, LatteX refuses `0PT`. Whether a space before
> `[` after `\\` is skipped is decided by the environment's OWN name (`array` and
> `eqnarray` skip it, every other environment does not), so an `array` nested inside
> `pmatrix` follows `array`'s rule and does not inherit amsmath's no-space-skip.
>
> **Smaller standard names.** `\vert` is the bar `|` as an ordinary symbol
> (`h\vert_{Z=1}`) as well as a delimiter; `{\sf …}` and `{\tt …}` join `\rm \bf \it
> \cal` as font declarations; esint's `\fint` (⨏), `\sqint`, `\ointclockwise` and
> `\ointctrclockwise` are integrals with side limits; a text command nests inside
> `\mathrm` or another text command (`\mathrm{non\text{-}tail}`, `\text{a \textbf{b}}`),
> refusing only a style it cannot combine (bold inside italic). A `\displaystyle`-family,
> `\color` or font switch now ends at `\right`, `\middle` and `\end` as well as at
> `}`, `&` and `\\`. In amsmath environments (everything but `array` and `eqnarray`),
> `\\ [x]` with a space is a row that starts with a bracket, not a spacing option —
> amsmath's own rule; `\\[2pt]` with no space is still the option everywhere.
>
> **Braces, tag labels, overlaps, primes.** A braced subformula in a list is an Ord
> atom, as in TeX: `152{,}320` is a tight thousands separator (no space after the
> comma), `a{+}b` and `x{=}y` set the symbol with no binary or relation glue, and
> `X/{\sim}` spaces the tilde as an ordinary symbol. Only a list item is a subformula:
> the braces of a command argument (`\frac{+}{2}`, `\overset{a}{=}`, `\mathrel{..}`)
> and of a script (`x^{+}`) just delimit it. A `\tag` label is TEXT, as amsmath sets it:
> `\tag{a}` is an upright a, `\tag{C-pair}` keeps its hyphen, and `\tag{\(*\)}` or
> `\tag{$\dagger$}` re-enter math (row tags too). A label text mode cannot take
> (`\tag{\ref{x}.1}`, `\tag{\ast}`: LatteX's text mode runs no commands) keeps the math
> reading it had before rather than failing. mathtools' `\mathllap{..}`, `\mathrlap{..}`
> and `\mathclap{..}` are zero-width boxes whose math content overhangs left, right or
> centred (`\sum_{\mathclap{1\le i\le n}}`), with an optional style
> (`\mathclap[\scriptstyle]{..}`). A prime is TeX's `^\prime` (consecutive primes and a
> following `^` join one superscript, so `f''_n` is `f^{\prime\prime}_n`), drawn with
> the font's script-style prime: the bundled font's plain prime is a raised text prime,
> which as a superscript floated above the letter.

## 3. The `\lx[...]{...}` syntax (author-facing)

For content authors — markdown, CMS fields, docs — LatteX defines one
self-delimiting macro so all styling and metadata travel *with* the expression:

```
\lx[ key=value, key=value, ... ]{ LaTeX body }
```

The options block is validated and reduced to typed values **at parse time**, and
**unknown keys fail loud** (the parser names the offending key). Two families:
`style.*` shapes the SVG itself; everything else (`fx.*`, semantics, `a11y`, `data`)
is validated and carried on the *container*, not baked into the sanitized SVG.

```
\lx[style.color=#c0392b, style.scale=1.4]{ \frac{a+b}{c} }

\lx[style.mathstyle=text]{ e^{i\pi} + 1 = 0 }

\lx[fx.hover=glow, fx.duration=250ms, intent=function, a11y.label="quadratic"]{ x^2 }
```

The key set:

| Key | Values |
| --- | --- |
| `style.scale` | `sm` (0.8), `md` (1.0), `lg` (1.4), or a bounded number like `1.4` |
| `style.color` | `currentColor`, or a `#rgb` / `#rrggbb` hex literal |
| `style.mathstyle` | `display` \| `text` \| `script` \| `scriptscript` |
| `fx.enter` / `fx.hover` / `fx.click` | `boom` \| `pulse` \| `fade` \| `glow` \| `lightning` \| `storm` \| `handscribe` \| `hologram` \| `neonsign` \| `crystallize` \| `blueprint` \| `wobble` \| `gravwell` \| `matrixrain` \| `supernova` \| `inkdrop` \| `diffusion` \| `refraction` \| `teleport` \| `shatter` \| `glitch` \| `sparkler` \| `quantum` \| `typeset` \| `constellation` \| `thread` \| `precedence` \| `cancel` \| `unfold` \| `substitute` \| `none` — see `examples/effects.html` live (`unfold` and `substitute` are opt-in/flag-gated and not shown there — see their own previews and the callout below) |
| `fx.duration` | a `<n>ms` value, e.g. `250ms` |
| `fx.substitute-to` | the literal integer `substitute` flips to, e.g. `3` or `-12` (at most 6 digits). The substituted form is grouped where adjacency would change the meaning: `2x` with `3` renders `2 \cdot 3`, never `23`; a negative after any operand keeps its product, so `ax` with `-3` renders `a \cdot -3`, never `a-3`; and a negative under a power or subscript is parenthesised, so `2x^2` with `-3` renders `2(-3)^2`, never `2-3^2`. `\textcolor` is transparent to both guards |
| `fx.substitute-var` | the single letter to replace, e.g. `x` — optional; omit it and the body's one distinct letter is used |
| `intent` / `concept` | a lowercase identifier (`^[a-z][a-z0-9_]*$`), e.g. `function` |
| `a11y.label` | free-text accessibility label — stored raw; illegal control characters are stripped and it is HTML-escaped once when stamped onto the container (an unpaired surrogate fails loud) |
| `data.<name>` | an identifier key + identifier value, e.g. `data.graph=true` |

`\lx` must currently be the **whole** top-level expression (nesting is a future
refinement). Values are bare tokens or `"quoted strings"`; whitespace outside quotes
is insignificant. This is the quickstart view — the full option grammar and rationale
live in the **`lattex-render-styling-options`** design plan. See
[examples/showcase.html](examples/showcase.html) for the macro end-to-end.

> **`unfold` and `substitute` are doubly gated.** They are the numeric-substitution
> family — the effects that need LatteX to *compute* (pre-render a bounded `\sum` into
> its explicit terms, or an expression with its variable replaced by a value). They stay
> off unless BOTH the host opts in — `RenderOptions.defaults().withInteractiveExpansion(true)`,
> passed to `LatteX.renderStyledHtml(latex, opts)` (default **off**) — AND the equation
> carries the matching directive. ONE flag covers both: the capability is "LatteX
> pre-renders computed material", not a switch per effect. With the flag off, the
> directive typesets normally and simply never arms.
>
> - `unfold` scope: `\sum` with literal-integer bounds, a single letter index, a bare
>   summand, up to 12 terms.
> - `substitute` scope: a literal-integer `fx.substitute-to` target, and ONE variable —
>   auto-detected when the body has exactly one distinct letter, otherwise named with
>   `fx.substitute-var`. Naming a variable the body does not contain is inert too,
>   deliberately: a payload identical to the source would present an author typo as a
>   working effect.
>
> Anything outside those scopes degrades inert rather than guessing. Note that
> substituting into an implicit product inserts an explicit `\cdot` — `2x` with `x=3`
> renders `2 \cdot 3`, because `23` would be a different number.

## 3.5 User macros — `\newcommand` and notation packs

Real corpora define notation on page one; LatteX expands it before parsing.

Inline, in the expression itself:

```latex
\newcommand{\norm}[1]{\lVert #1 \rVert} \norm{x + y}
\def\avg{\frac{#1+#2}{2}} \avg{a}{b}
```

As a server-side preset pack (per-tenant notation KaTeX's client config can't
centralize) — applies to every render, `\lx{…}` bodies included:

```java
RenderOptions opts = RenderOptions.defaults()
    .withMacros(Map.of("R", "\\mathbb{R}", "inner", "\\langle #1, #2 \\rangle"));
LatteX.render("\\inner{u}{v} \\in \\R^{n}", opts);
```

CLI: `lattex --macro 'R=\mathbb{R}' '\R^{2}'` (repeatable).

The namespace is **additive-only**: built-in names are refused (`\renewcommand`
redefines *user* macros only), so a macro can never change what already-valid
input means. Runaway recursion and expansion bombs fail closed
(`MAX_MACRO_DEPTH` / `MAX_MACRO_EXPANSIONS`). Subset limits: definitions are
global to the input, and macros do not reach nested `$…$` spans inside
`\text{…}`.

## 4. Container features

The SVG stays clean; everything interactive rides on a trusted `<span class="lx-math">`
wrapper the API emits around it. Three helpers produce that wrapper:

- **`LatteX.renderInline(latex)`** — inline math for prose, defaulting to `TEXT`
  style. Use **`renderInlineResult(latex)`** when you need to seat it on the
  baseline: it returns the SVG alongside `depthEm` (ink below the baseline) and
  `heightEm`, and the *host* applies `vertical-align: calc(-1 * <depthEm>em)`.
  The metrics ride the result object deliberately — LatteX bakes no style
  attribute and stamps no depth attribute on the wrapper, so the page keeps
  ownership of its own CSS policy.
- **`LatteX.renderStyledHtml(latex)`** — if the source is an `\lx` with `fx.*`
  effects, wraps the SVG in a container stamped with `data-lx-fx-enter` /
  `data-lx-fx-hover` / `data-lx-fx-click` (+ `data-lx-fx-duration`). A page-side
  runtime (CSS `@keyframes` + a little JS) reads those and plays the animation.
  Sources without effects return the bare SVG. If you own your own wrapper
  instead, use `tryRenderMath(latex)` — see SLOWSTART §"Emit the wrapper the
  runtime reads" for why the deprecated `fxContainerAttrs` silently breaks the
  `thread` and `cancel` effects.
- **`LatteX.renderFragment(latex, fontSizePx)`** — the inner `<g>/<path>/<rect>`
  markup plus box metrics (`widthPx`/`heightPx`/`depthPx`), for a consumer that
  composes the math inline on a shared baseline (e.g. a diagram renderer drawing
  math-in-labels). Unlike `render*`, it returns no `<svg>` wrapper. `fontSizePx`
  must be a finite, strictly-positive size no larger than `LatteX.MAX_FRAGMENT_FONT_SIZE`
  (= `RenderOptions.MAX_SCALE` × the display size, i.e. `800`); a `NaN`/`Infinity`,
  zero, or negative size is rejected loud (`IllegalArgumentException`) rather than
  carried into the metrics as garbage. See the API javadoc on `MathFragment`.

In every case the data attributes live on the container the page emits — **never**
inside the sanitized SVG — so the emitter's minimal alphabet is unchanged.

> The `fx` animations are wired via the example page runtimes today; the SVG-side
> container-stamping (`renderStyledHtml`) is the shipped path. A click-to-open
> action menu (Copy LaTeX, contextual Graph) and real Graph *plotting* are planned,
> not yet in the API.

## 5. Integration by stack

Everything wraps the one core: `render(latex, options) → SVG string`.

### JVM — Java / Kotlin / Scala — *available*

Depend on the versioned artifact `com.lattex:lattex:0.12.0` (module `com.lattex`,
exporting `com.lattex.api`) and call the API directly:

```kotlin
// build.gradle.kts — resolve from ~/.m2 after `./gradlew publishToMavenLocal`
// in the LatteX repo (a published repo can be added later).
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("com.lattex:lattex:0.12.0") }
```

```java
String svg = com.lattex.api.LatteX.render("\\frac{a}{b}");
```

Zero runtime dependencies, so it drops into any JVM app with no transitive baggage.
The version is a real immutable release — pin it, and it can never silently change
under you (a LatteX update is an explicit version bump).

**Error handling — one typed channel, never an `Error`.** Every public render entry
(`render`, `renderInline`, `renderFragment`, `renderStyledHtml`) routes every *rendering*
failure through one exported supertype: **`com.lattex.api.LatteXException`**. Catch that.
The concrete type is still `com.lattex.parse.MathSyntaxException` (it extends
`LatteXException`, which extends `IllegalArgumentException`), so existing code catching
either of those keeps working unchanged — but `com.lattex.parse` is **not exported**, so a
consumer with its own `module-info` that `requires com.lattex` cannot name it at all. Catch
the exported supertype and modular and classpath consumers behave identically.

This matters for more than tidiness: `renderFragment` also throws a **bare**
`IllegalArgumentException` for a non-finite or non-positive `fontSizePx`. Catching
`LatteXException` distinguishes "your LaTeX is malformed" from "you passed a bad
parameter"; catching `IllegalArgumentException` conflates them. A genuine syntax error carries
the source offset and a caret-pointing `caretString()` for author-facing messages; an
unexpected internal failure in layout/emit is *contained* into the same channel (message
prefixed `internal render failure`, original failure preserved as the cause) — so a
`StackOverflowError` or renderer bug can never escape and kill the calling thread. Catch
`LatteXException`, show the caret, move on. (`OutOfMemoryError` is deliberately not
caught.) For batch pipelines that must degrade per-formula instead of per-page, use
`renderWithDiagnostics` — it NEVER throws and returns `RenderResult{svg, Diagnostics}`
with a Sirentide-parity outcome (`OK`/`PARSE_ERROR`/`RENDER_BUG`…), stage, message, and
the caret as data — one consumer code path for a failed diagram and a failed formula. Rendering is hardened for
UNTRUSTED input: caps on source length, nesting depth, layout box count, and output size
(the latter enforced incrementally so a runaway SVG is never built), plus control-char
stripping so the output stays within the `svg/g/path/rect` alphabet — a cap trip surfaces
as `OUTPUT_CAP_EXCEEDED`, never an escaped error.

```java
try {
    String svg = com.lattex.api.LatteX.render(userInput);
} catch (com.lattex.parse.MathSyntaxException e) {
    log.warn("math failed:\n{}", e.caretString()); // fall back to verbatim source
}
```

On the **classpath** (the common case) keep catching the concrete type as above — it is
what carries `offset()`, `source()`, and the caret. Inside a **module**, catch the
exported supertype instead:

```java
try {
    String svg = com.lattex.api.LatteX.render(userInput);
} catch (com.lattex.api.LatteXException e) {
    log.warn("math failed: {}", e.getMessage());
}
```

A known limit, stated rather than glossed: `caretString()`, `offset()`, and `source()` are
declared on the concrete `MathSyntaxException`, so a modular consumer can *catch* every
render failure but cannot reach the caret data. If you need author-facing carets from
inside a module today, use `renderWithDiagnostics` — it never throws and returns the caret
as data, through exported types only.

**Inline math on the text baseline.** `renderInline` gives you the SVG; for prose
embedding use `renderInlineResult` — the same SVG plus baseline metrics, so the
formula sits ON the line instead of floating above it. Apply the depth as
`vertical-align` on *your* wrapper (the SVG itself stays style-attribute-free):

```java
var r = com.lattex.api.LatteX.renderInlineResult("y_i^2");
String html = "<span style=\"vertical-align:-" + r.depthEm() + "em\">" + r.svg() + "</span>";
```

### Any other stack — Node, Python, Ruby, Go, static-site generators — *available (S7)*

A self-contained **native CLI**, `lattex`, built with GraalVM native-image — **no JVM
required on the host**. It reads LaTeX from an argument or stdin and writes SVG to
stdout (or a file), so any language can shell out:

```bash
lattex "\frac{a}{b}" > fraction.svg     # expression as an argument
echo '\frac{a}{b}' | lattex             # …or piped on stdin
lattex "x^2 + y^2 = z^2" -o pythagoras.svg
lattex --help
```

```python
# Shell out from Python — same for Node, Ruby, Go, a Makefile, …
import subprocess
svg = subprocess.run(["lattex", r"\frac{a}{b}"],
                     capture_output=True, text=True).stdout
```

Flags: `-o/--output <file>`, `-h/--help`, `-V/--version`, and `--` to end option
parsing. Exit status is `0` on success, `1` on a render/IO error (including a failed
stdout write or flush, such as a closed downstream pipe), `2` on a usage error.
In one-shot mode, invalid LaTeX is explained on stderr. In `--batch`, successful SVG
and in-place `lattex: error: …` records remain NUL-delimited on stdout.
Output-delivery failures are explained on stderr; a stdout failure may leave incomplete
output and never reports success. The CLI is a thin wrapper over the JVM
`LatteX.render` — same core, byte-identical SVG. stdin (and each `--batch` record) is
read incrementally with a 100,000-character-per-expression cap enforced as it's read —
no *unbounded* whole-stream read before a check, though the decoder's bounded read-ahead
may already hold a short remainder — see **Scenario 7** in SLOWSTART.md for the
`--batch` streaming/limit details.

**Build it** (GraalVM CE for JDK 25 must be on `PATH` — e.g. `sdk use java 25-graalce`):

```bash
./gradlew nativeImage          # → build/native/lattex (standalone binary)
```

The binary is fully self-contained: the STIX Two Math font is baked into the image via
the GraalVM reachability metadata the library already ships (no reflection, no external
files). Styling flags are wired to the typed `RenderOptions`: `--scale <N>` (vector
size multiplier, `[0.1, 20.0]`), `--color <C>` (`currentColor` or a `#rgb`/`#rrggbb`
hex), and `--inline` (text style). All three work standalone and in `--batch`; a bad
value fails loud with exit code 2. A top-level `\lx[...]` in the source still wins.

**No GraalVM?** The same CLI runs on any JVM, no native build required:

```bash
./gradlew run --args="\frac{a}{b}"                 # via Gradle
java -jar build/libs/lattex-<version>.jar "x^2"    # via the runnable jar
```

The jar's file name follows `version` in `build.gradle.kts` (a `-SNAPSHOT` suffix
between releases), not the last released number. `./gradlew build` also writes
`-sources` and `-javadoc` jars beside it, so a bare `lattex-*.jar` glob matches three
files; pick the one without a classifier.

### Performance — native binary vs. `java -jar` vs. `./gradlew run`

The three launch modes render **byte-identical SVG**; what differs is **startup cost**, which dominates a single render (the render itself is sub-millisecond):

| Mode | What it pays per run | Best for |
|---|---|---|
| **native binary** (`lattex`) | ~nothing — no JVM to start | shelling out per expression, CI, non-JVM stacks |
| **`java -jar`** | one JVM cold start | a JVM app already warm, or a one-off without building native |
| **`./gradlew run`** | JVM + Gradle task graph | the dev loop only — never ship this |

**What we measured** — 50 runs each of `\sum_{i=1}^{n} i = \frac{n(n+1)}{2}` (all producing identical output), on an Apple-Silicon Mac, JDK 25 / GraalVM CE 25:

| Mode | avg / run |
|---|---:|
| native binary | ~5 ms |
| `java -jar` | ~50 ms |
| `./gradlew run` | ~330 ms |

> ⚠️ **These are illustrative only — absolute numbers depend heavily on your machine** (CPU, disk, JVM/GraalVM version, warm vs. cold caches), and you have to build the native binary yourself first. The stable takeaway is the **ratio**: the native binary is roughly an order of magnitude faster *per invocation* than `java -jar` (no JVM to start), and `./gradlew run` carries dev-loop overhead you'd never ship. **For anything that shells out to LatteX repeatedly, use the native binary.**

**Measure it on your own machine:**

```bash
tools/bench.sh                          # 50 runs of each mode → avg ms/run
tools/bench.sh 100 '\int_0^1 x^2\,dx'   # custom run count + expression
```

The native row is included when a GraalVM for JDK 25 is on `GRAALVM_HOME` (e.g. `export GRAALVM_HOME="$HOME/.sdkman/candidates/java/25-graalce"`) or `native-image` is on `PATH`; otherwise it times just the JVM modes.

### HTTP service — *planned / optional*

> **Planned / optional.**

A thin wrapper exposing `POST LaTeX → SVG` for hosted or high-throughput use, where
you'd rather call a service than embed the JAR or spawn the CLI per expression. Same
core underneath.

### Browser / JS via WebAssembly — *future*

> **Future.**

A WASM build so the same renderer runs client-side in the browser with no server
round-trip.

## 6. Markdown → HTML pipeline

The integration pattern for docs and content sites: a build step scans markdown for
math markers — `$…$` (inline), `$$…$$` (display), and `\lx[…]{…}` — and replaces each
match with the rendered SVG. Because the output SVG is sanitizer-safe, it inlines
directly into the generated HTML with no post-processing.

- **On the JVM** — a [flexmark](https://github.com/vsch/flexmark-java) extension that
  hooks the markdown parse and calls `LatteX.render*` per match. This is Stafficy's
  S8 path.
- **On other stacks** — a preprocessor that shells out to the `lattex` CLI once per
  expression (see §5; **available, S7**).
- **Reference plugins** — remark/rehype for the JS ecosystem, and a Python
  markdown/Pandoc filter — are **future**.

Pick `renderInline` for `$…$` markers and `render` (display style) for `$$…$$`, so
inline math is em-sized and baseline-seated while display math renders full size.

## 7. Status legend

| Capability | Status |
| --- | --- |
| `LatteX.render(latex)` / `render(latex, RenderOptions)` | Built |
| `RenderOptions` (scale / color / mathStyle) | Built |
| `\lx[...]{...}` author syntax (validated, fail-loud) | Built |
| Inline math — em-sizing + baseline alignment (`renderInline`) | Built |
| `fx.*` effects on the container (`renderStyledHtml`) | Built |
| Click action menu — Copy LaTeX / contextual Graph | Planned |
| Native CLI (`lattex`, GraalVM) — argv/stdin → SVG; flags `-o/--output`, `--batch`, `-0/--null`, `--inline`, `--scale`, `--macro`, `--color`, `-h/--help`, `-V/--version`, `--` | Built (S7) |
| HTTP service wrapper | Planned / optional |
| Browser / JS (WASM) build | Future |
| Reference markdown plugins (remark/rehype, Python filter) | Future |
| Real Graph *plotting* (beyond the menu affordance) | Future |

Everything marked Built is on the mainline; this table and the top-level README
describe the same state.

---

## Build

```bash
./gradlew build      # compile + test (Java 25 toolchain, auto-provisioned by Gradle)
```

Requires a Java 25 toolchain, which Gradle downloads via the toolchain spec. The core
has **zero runtime dependencies** (test-scope JUnit 5 only). See
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the clean-room rules and the SVG
minimal-subset invariant, and the `examples/` directory for rendered output you can
open in a browser.

## License

Code: [Apache-2.0](LICENSE). Bundled STIX Two Math font: SIL Open Font License (OFL).

## 8. Trusted two-state equation transitions

Use `InteractiveMath` when a trusted host wants to show one equation and an
alternate form without changing LatteX's static SVG emitter or the author
`\lx` grammar:

```java
import com.lattex.api.InteractiveMath;
import com.lattex.api.InteractiveOptions;
import com.lattex.api.InteractiveResult;

InteractiveResult result = InteractiveMath.render(
    "\\frac{a}{b}",
    "\\frac{b}{a}",
    InteractiveOptions.defaults().withDurationMillis(320));

if (result.status() != InteractiveResult.Status.FAILED) {
    String html = result.html(); // interactive component or one exact static SVG
}
```

Both endpoints are rendered independently. `INTERACTIVE` contains the trusted
two-state component, `STATIC_FALLBACK` contains one exact static SVG when only
one endpoint succeeds, and `FAILED` contains no partial markup. The first
runtime performs a whole-expression FLIP/crossfade rather than claiming
per-glyph morphing. Duration defaults to 240 ms, accepts 0–2000 ms, and the
current endpoint contract requires fixed-size `RenderOptions`.

Serve the separately bundled assets from the same JAR version:

```java
InteractiveMath.stylesCss(); // /css/lattex-interactive.css
InteractiveMath.runtimeJs(); // /js/lattex-interactive.js
```

```html
<link rel="stylesheet" href="/css/lattex-interactive.css">
<script defer src="/js/lattex-interactive.js"></script>
```

The script auto-initializes document content. For dynamically inserted
components call `LatteXInteractive.init(scope)`, and call
`LatteXInteractive.destroy(scope)` before explicit teardown. Hover previews the
alternate state; the explicit control works by click or keyboard. Reduced
motion disables animation, not the state change. With CSS but no successful
JavaScript initialization, both labeled states stay readable and the control
remains hidden.
