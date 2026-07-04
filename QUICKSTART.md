# LatteX Quickstart ☕

Render LaTeX math to SVG, then drop it straight into your HTML.

> **Status note.** LatteX is early. The render core, `\lx` syntax, inline/em
> sizing, `fx` effects, and the action menu described below are **built** (living
> on review branches, merging soon). The native CLI (S7) is **built**; the HTTP
> service, WASM build, and editor/markdown plugins are **planned** — each is flagged inline and summarised in
> the [status legend](#7-status-legend). Accuracy over hype: if it isn't built, this
> doc says so.

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
`{scale, color, mathStyle}` triple:

```java
import com.lattex.api.LatteX;
import com.lattex.api.RenderOptions;
import com.lattex.style.Color;
import com.lattex.style.MathStyle;

RenderOptions opts = RenderOptions.defaults()   // scale 1.0, currentColor, DISPLAY
    .withScale(1.5)
    .withColor(Color.parse("#c0392b"))
    .withMathStyle(MathStyle.TEXT);

String svg = LatteX.render("\\frac{a+b}{c}", opts);
```

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

\lx[fx.hover=grow, fx.duration=250ms, intent=function, a11y.label="quadratic"]{ x^2 }
```

The key set:

| Key | Values |
| --- | --- |
| `style.scale` | `sm` (0.8), `md` (1.0), `lg` (1.4), or a bounded number like `1.4` |
| `style.color` | `currentColor`, or a `#rgb` / `#rrggbb` hex literal |
| `style.mathstyle` | `display` \| `text` \| `script` \| `scriptscript` |
| `fx.enter` / `fx.hover` / `fx.click` | `boom` \| `pulse` \| `fade` \| `glow` \| `grow` \| `none` |
| `fx.duration` | a `<n>ms` value, e.g. `250ms` |
| `intent` / `concept` | a lowercase identifier (`^[a-z][a-z0-9_]*$`), e.g. `function` |
| `a11y.label` | free-text accessibility label (HTML-escaped) |
| `data.<name>` | an identifier key + identifier value, e.g. `data.graph=true` |

`\lx` must currently be the **whole** top-level expression (nesting is a future
refinement). Values are bare tokens or `"quoted strings"`; whitespace outside quotes
is insignificant. This is the quickstart view — the full option grammar and rationale
live in the **`lattex-render-styling-options`** design plan. See
`examples/lx-demo.html` for the macro end-to-end.

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
- **`LatteX.renderMenuHtml(latex)`** / **`renderMenuInline(latex)`** — wraps the SVG
  in a container carrying `data-lx-latex` (the HTML-escaped LaTeX source, for a
  "Copy LaTeX" action) and, when the math is marked graphable (via `intent`/`concept`
  of `function`/`graph`, or a `data.graph`/`data.graphable` marker),
  `data-lx-graphable="true"`. A page-side runtime builds a click-to-open action menu
  (Copy LaTeX, and a contextual Graph icon). See `examples/menu-demo.html`.

In every case the data attributes live on the container the page emits — **never**
inside the sanitized SVG — so the emitter's minimal alphabet is unchanged.

> The `fx` animations, the action menu, and Graph *plotting* are wired via the
> example page runtimes today; the SVG-side container-stamping (`renderStyledHtml` /
> `renderMenu*`) is a prototype of the future S8 step, and real Graph plotting is
> planned.

## 5. Integration by stack

Everything wraps the one core: `render(latex, options) → SVG string`.

### JVM — Java / Kotlin / Scala — *available*

Depend on the JAR (module `com.lattex`, exporting `com.lattex.api` and
`com.lattex.style`) and call the API directly:

```java
String svg = com.lattex.api.LatteX.render("\\frac{a}{b}");
```

Zero runtime dependencies, so it drops into any JVM app with no transitive baggage.

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
files). Styling flags (`--scale`/`--color`/`--display`) land once the `RenderOptions`
API merges to the mainline — the CLI's arg parser has the seam ready.

**No GraalVM?** The same CLI runs on any JVM, no native build required:

```bash
./gradlew run --args="\frac{a}{b}"                 # via Gradle
java -jar build/libs/lattex-0.1.0-SNAPSHOT.jar "x^2"   # via the runnable jar
```

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
| Click action menu — Copy LaTeX / contextual Graph (`renderMenu*`) | Built* |
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
