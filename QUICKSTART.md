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

**What it can render:** measured, not claimed — 484 of 484 real-world formulas
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
options object. All three knobs — `scale`, `color`, and `mathStyle` — are
reachable using only exported `com.lattex.api` types:

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
> `RenderOptions.defaults().inline()` / `.display()` / `.script()` / `.scriptScript()`
> are convenience selectors covering all four styles, so a caller usually never has to
> name the style enum at all.

- **`scale`** — output size multiplier (default `1.0`), folded into the effective
  font size so the whole geometry scales as crisp vector output, not a CSS zoom.
  Bounded to `[0.1, 20.0]`.
- **`color`** — a validated `Color`: `Color.CURRENT` (emits `currentColor`, so the
  math inherits surrounding text color and survives dark mode — the default) or
  `Color.parse("#rrggbb")` / `Color.parse("#rgb")`.
- **`mathStyle`** — the top-level TeX style, the **exported** enum
  `com.lattex.api.MathStyle`: `DISPLAY` (default), `TEXT`, `SCRIPT`, or
  `SCRIPT_SCRIPT`. (This enum lives in the exported API package precisely so a modular
  consumer can name it; the internal TeX-algorithm style type stays module-private.)

`RenderOptions` is an immutable `record`; derive variants with `withScale` /
`withColor` / `withMathStyle` (or the `inline()` / `display()` / `script()` /
`scriptScript()` selectors, which name no enum). See `examples/x-squared.html`,
`gallery.html`, and `styled.html` for rendered output.

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

> **Words inside math — `\text{…}`** (and `\textbf`/`\textit`/`\texttt`/`\textrm`/
> `\mathrm`). The argument is *literal text*: plain characters (spaces preserved),
> `\$` for a literal dollar, invisible grouping braces, and `$…$` to re-enter math
> mode (`\text{if $x>0$ then}`). Commands are **not** expanded inside text —
> `\text{see \eqref{eq1}}` fails loud (`Unknown command in \text: \eqref`) rather
> than silently serving the flattened characters; wrap math in `$…$` instead.
>
> **`aligned`/`split` position argument.** The optional `[t]`/`[b]`/`[c]` after
> `\begin{aligned}`/`\begin{split}` is parsed and **ignored**: it selects which
> row's baseline anchors the box in surrounding text, and LatteX renders the
> environment standalone, so it has no visual effect. Anything else in the bracket
> fails loud, matching `array`'s column-spec discipline.

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
| `fx.enter` / `fx.hover` / `fx.click` | `boom` \| `pulse` \| `fade` \| `glow` \| `lightning` \| `storm` \| `handscribe` \| `hologram` \| `neonsign` \| `crystallize` \| `blueprint` \| `wobble` \| `gravwell` \| `matrixrain` \| `supernova` \| `inkdrop` \| `diffusion` \| `refraction` \| `teleport` \| `shatter` \| `glitch` \| `sparkler` \| `quantum` \| `typeset` \| `constellation` \| `thread` \| `precedence` \| `cancel` \| `unfold` \| `none` — see `examples/effects.html` live (`unfold` is opt-in/flag-gated and not shown there — see its own preview and the callout below) |
| `fx.duration` | a `<n>ms` value, e.g. `250ms` |
| `intent` / `concept` | a lowercase identifier (`^[a-z][a-z0-9_]*$`), e.g. `function` |
| `a11y.label` | free-text accessibility label — stored raw; illegal control characters are stripped and it is HTML-escaped once when stamped onto the container (an unpaired surrogate fails loud) |
| `data.<name>` | an identifier key + identifier value, e.g. `data.graph=true` |

`\lx` must currently be the **whole** top-level expression (nesting is a future
refinement). Values are bare tokens or `"quoted strings"`; whitespace outside quotes
is insignificant. This is the quickstart view — the full option grammar and rationale
live in the **`lattex-render-styling-options`** design plan. See
`examples/lx-demo.html` for the macro end-to-end.

> **`unfold` is doubly gated.** It is the one effect that needs LatteX to *compute*
> (pre-render a bounded `\sum` into its explicit terms), so it stays off unless BOTH
> the host opts in — `RenderOptions.defaults().withInteractiveExpansion(true)`, passed
> to `LatteX.renderStyledHtml(latex, opts)` (default **off**) — AND the equation carries
> the `fx.*=unfold` directive. With the flag off, an `unfold` directive typesets the sum
> normally and simply never arms. Scope: `\sum` with literal-integer bounds, a single
> letter index, a bare summand, up to 12 terms; anything else degrades inert.

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

- **`LatteX.renderInline(latex)`** — inline math for prose. The root `<svg>`
  `width`/`height` are emitted in **`em`**, so the math scales to whatever
  `font-size` it lands in (body text, a heading, …) via plain CSS inheritance — no
  surrounding-style detection. The wrapper carries `data-lx-depth` (the baseline
  depth in em); a few lines of page CSS/JS read it and set
  `vertical-align: calc(-1 * <depth>em)` so the math sits on the text baseline.
  Defaults to `TEXT` style. See `examples/prose.html`.
- **`LatteX.renderStyledHtml(latex)`** — if the source is an `\lx` with `fx.*`
  effects, wraps the SVG in a container stamped with `data-fx-enter` /
  `data-fx-hover` / `data-fx-click` (+ `data-fx-duration`). A page-side runtime
  (CSS `@keyframes` + a little JS) reads those and plays the animation. Sources
  without effects return the bare SVG. See `examples/fx-demo.html`.
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

Depend on the versioned artifact `com.lattex:lattex:0.11.0` (module `com.lattex`,
exporting `com.lattex.api`) and call the API directly:

```kotlin
// build.gradle.kts — resolve from ~/.m2 after `./gradlew publishToMavenLocal`
// in the LatteX repo (a published repo can be added later).
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("com.lattex:lattex:0.11.0") }
```

```java
String svg = com.lattex.api.LatteX.render("\\frac{a}{b}");
```

Zero runtime dependencies, so it drops into any JVM app with no transitive baggage.
The version is a real immutable release — pin it, and it can never silently change
under you (a LatteX update is an explicit version bump).

**Error handling — one typed channel, never an `Error`.** Every public render entry
(`render`, `renderInline`, `renderFragment`, `renderStyledHtml`) throws exactly one
exception type: `com.lattex.parse.MathSyntaxException`, which extends the **exported**
supertype `com.lattex.api.LatteXException`. Catch whichever suits your build: a plain
classpath app can catch either; a **modular consumer** (its own `module-info` that
`requires com.lattex`) must catch `com.lattex.api.LatteXException`, since
`com.lattex.parse` is not an exported package. Both catch every failure the render
methods raise. A genuine syntax error carries
the source offset and a caret-pointing `caretString()` for author-facing messages; an
unexpected internal failure in layout/emit is *contained* into the same channel (message
prefixed `internal render failure`, original failure preserved as the cause) — so a
`StackOverflowError` or renderer bug can never escape and kill the calling thread. Catch
`MathSyntaxException`, show the caret, move on. (`OutOfMemoryError` is deliberately not
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
} catch (com.lattex.api.LatteXException e) {   // exported supertype — works from any module
    log.warn("math failed: {}", e.getMessage()); // fall back to verbatim source
}
```

> `LatteXException` is the exported handle; `caretString()` (the caret-pointing,
> author-facing form) lives on the concrete `com.lattex.parse.MathSyntaxException`. A
> classpath app can `catch (com.lattex.parse.MathSyntaxException e)` directly and call
> `e.caretString()`; a modular consumer catches `LatteXException` and can narrow with an
> `instanceof` check when it needs the caret.

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
parsing. Exit status is `0` on success, `1` on a render/IO error (with the parser's
message on stderr for invalid LaTeX), `2` on a usage error. The CLI is a thin wrapper
over the JVM `LatteX.render` — same core, byte-identical SVG.

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
java -jar build/libs/lattex-0.11.0.jar "x^2"            # via the runnable jar
```

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
| `LatteX.render(latex)` / `render(latex, RenderOptions)` | Built* |
| `RenderOptions` (scale / color / mathStyle) | Built* |
| `\lx[...]{...}` author syntax (validated, fail-loud) | Built* |
| Inline math — em-sizing + baseline alignment (`renderInline`) | Built* |
| `fx.*` effects on the container (`renderStyledHtml`) | Built* |
| Click action menu — Copy LaTeX / contextual Graph | Planned |
| Native CLI (`lattex`, GraalVM) — argv/stdin → SVG, `-o`/`--help`/`--version` | Built (S7) |
| HTTP service wrapper | Planned / optional |
| Browser / JS (WASM) build | Future |
| Reference markdown plugins (remark/rehype, Python filter) | Future |
| Real Graph *plotting* (beyond the menu affordance) | Future |

\* *Built and demoed on review branches; merging to the mainline soon. The repo's
top-level README still describes an earlier stubbed state — trust this table and the
`examples/` pages.*

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
