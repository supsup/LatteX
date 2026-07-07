# LatteX ☕

**Math, Y'all** — LaTeX math rendered to SVG, for the JVM.

LatteX is a clean-room, pure-**Java 25** library that renders LaTeX math to **SVG** — inline and display, baseline-accurate — with **zero runtime dependencies**. No JavaScript engine, no headless browser, no external `tex` binary. Point it at `\frac{1}{2}` and get back a crisp, scalable, self-contained `<svg>`.

```java
String svg = com.lattex.api.LatteX.render("\\frac{a+b}{c}");
```

> **Acknowledgments.** LatteX is a *clean-room* implementation — its layout is derived from Knuth's *TeXbook* (Appendix G) and the OpenType-MATH / SVG specifications, not from any existing renderer's source. That said, a grateful hat-tip to **[KaTeX](https://katex.org)**: we used its superb *supported-functions* coverage as a **feature reference** — *what* LaTeX commands are worth supporting — to shape our roadmap. Thanks also to the **STIX Two Math** font (SIL OFL) that LatteX bundles, and to the wider TeX/LaTeX ecosystem it stands on.

## See it

**[examples/showcase.html](examples/showcase.html)** — a curated tour of what
LatteX renders (every formula on it is regression-locked by the wild-corpus
ratchet: 467/484 real-world formulas, 96.5%, and only allowed to go up). For the
fx layer in motion, see the **[effects showcase](examples/effects.html)** — all
26 animated effects live — and **[the fx gallery](examples/GALLERY.md)** for
captured previews (every effect as its own looping GIF).

![A scroll through the LatteX showcase — the definition of the derivative, Euler's identity, the Basel problem, a Gaussian integral, the curl determinant, a piecewise cases block, an aligned derivation, and the 0.3.0 stack mechanism (underbrace + substack), every formula rendered to self-contained SVG](examples/showcase.gif)

<sub>↑ a scroll through <code>examples/showcase.html</code>, captured with <a href="https://github.com/supsup/BrewShot">BrewShot</a> — every frame is real renderer output.</sub>

## Why

The JVM lacks a modern, permissively-licensed, web-first math renderer. KaTeX and MathJax are JavaScript. JLaTeXMath is excellent but old (AWT/image-first) **and GPL**. SnuggleTeX is permissive but limited to MathML. LatteX fills that gap: **Apache-2.0, pure Java, SVG-native.**

## Design

- **Java 25, modern.** The math tree is a `sealed interface` + `record` algebraic data type, laid out with exhaustive pattern-matching switches — no visitor boilerplate.
- **Zero runtime dependencies, framework-free.** No Spring, no anything. A JPMS module you drop in and call. The glyphs come from a bundled OFL math font (STIX Two Math), emitted as SVG `<path>`s — so there are no fonts to load in the browser either.
- **SVG-native, inline-capable.** Output is a minimal, sanitizer-friendly SVG subset — only `<svg>`/`<g>`/`<path>`/`<rect>`, with glyphs as inline filled `<path>`s (no `<text>`/`<use>`/`<defs>`/`<script>`). The renderer exposes each expression's height and depth so inline math sits correctly on the text baseline.
- **Native-image clean.** No reflection; ships GraalVM reachability metadata, and an optional standalone `lattex` native CLI.
- **Design-for-both, ship SVG.** A layout core + pluggable output backends (mirroring TeX's own `dvisvgm`/`dvipng` driver split): SVG natively, with **[`bin/lattex-shot`](#png-export--binlattex-shot)** for a tightly-cropped **PNG** raster (as glue over [BrewShot](https://github.com/supsup/BrewShot), no new dependency).

## Status

Early but real. The parse → layout → SVG pipeline is wired end-to-end: `com.lattex.api.LatteX.render(...)` renders fractions, roots, scripts, big operators, matrices, aligned environments, delimiters, stacked annotations (`\underbrace`/`\overbrace`/`\substack`/`\stackrel`/`\overset`/`\underset`), extensible labelled arrows (`\xrightarrow`/`\xleftarrow`), style-pinned fractions (`\dfrac`/`\tfrac`), and per-subterm color (`\color`/`\textcolor`) to SVG today — **96.5% of the wild corpus** as of **0.4.0**. The `\lx[...]{...}` author syntax, inline em-sizing + baseline alignment, and the full **26-effect** `fx` layer are on the mainline, with parse-time DoS guards. See **[QUICKSTART.md](QUICKSTART.md)** for usage and cross-stack integration.

## The fx layer is OPTIONAL

The math renders from the jar **alone** — pure, inert `svg/g/path/rect`, no runtime, safe to inline anywhere. The `\lx` **effects** (glow, handscribe, supernova, shatter, sparkler, and 20 more) are an *opt-in* layer: they ride the `<span class="lx-math" data-lx-fx-*>` wrapper and are driven by a small vanilla-JS runtime **bundled in the jar**. Include it only if you want the animations.

On the JVM, read the assets straight off the API:

```java
LatteX.fxRuntimeJs()   // the runtime — serve as /js/lattex-fx.js, or inline in a trusted <script>
LatteX.fxStylesCss()   // the styles — serve as /css/lattex-fx.css, or inline in a <style>
```

Not on the JVM? They're plain jar resources — extract them at build time in any stack:

```bash
unzip -p lattex-0.4.0.jar com/lattex/fx/lattex-fx.js  > static/js/lattex-fx.js
unzip -p lattex-0.4.0.jar com/lattex/fx/lattex-fx.css > static/css/lattex-fx.css
```

Either way the consumer gets them **from the jar it already renders with** — no separately-managed asset, and the runtime can never drift from the renderer that stamped the attributes. Three rules from real integrations: extract from the **same jar version** you render with (never a cached copy); ship the js and css **together or not at all** (the css pre-hides `fx.enter` equations for the js to reveal); load the script with `defer` so it runs after the math is in the DOM. Full stack-by-stack walkthrough: **[SLOWSTART.md](SLOWSTART.md)** Scenario 4. Browse the whole catalogue live in `examples/effects.html` — or see it without building anything: **[the fx gallery](examples/GALLERY.md)** has real-browser screenshots and GIFs of the effects in motion, captured by [BrewShot](https://github.com/supsup/BrewShot) on every full test run.

## PNG export — `bin/lattex-shot`

SVG is the native output, but sometimes you need a raster (Slack, a GitHub issue
body, slides, an LMS upload). `bin/lattex-shot` is the raster backend this README's
Design section gestures at — as glue, not a new dependency: LatteX renders the math
to SVG with exact metrics, and [BrewShot](https://github.com/supsup/BrewShot)
screenshots it, tightly cropped.

```bash
./gradlew jar                                     # build the lattex jar first
bin/lattex-shot '\int_0^\infty e^{-x}dx = 1' -o eq.png
echo '\frac{a}{b}' | bin/lattex-shot - -o frac.png     # or pipe via stdin
bin/lattex-shot 'E = mc^2' --scale 4 --bg transparent -o hero.png
```

Flags: `-o FILE`, `--scale N` (it's SVG, so a bigger scale is *more pixels*, not
upscaling), `--bg COLOR`, `--color COLOR` (the ink), `--pad PX`. It finds the jar in
`build/libs/` and `brewshot` on your `PATH`; override with `LATTEX_JAR` / `BREWSHOT`.
The scale is a CSS transform (not an SVG width rewrite, which mis-composes some
placement-transformed glyphs), and the crop clips to the rendered box — so no
whitespace-trim guesswork.

## Build

```bash
./gradlew build
```

Requires a Java 25 toolchain (Gradle provisions it via the toolchain spec).

## License

- **Code:** [Apache-2.0](LICENSE).
- **Bundled math font:** STIX Two Math, under the SIL Open Font License (OFL) — see `NOTICE`.

## Contributing

LatteX is a **clean-room** implementation. Before contributing, read [CONTRIBUTING.md](CONTRIBUTING.md) — in particular the rule that we implement from the *primary sources* (Knuth's TeXbook + the OpenType/SVG specs), **never** from other renderers' code.

## Disclaimer

This project is provided under the Apache License 2.0 on an "AS IS" basis, without warranties or conditions of any kind. See the LICENSE file for details.
