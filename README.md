# LatteX ☕

**Math, Y'all** — LaTeX math rendered to SVG, for the JVM.

LatteX is a clean-room, pure-**Java 25** library that renders LaTeX math to **SVG** — inline and display, baseline-accurate — with **zero runtime dependencies**. No JavaScript engine, no headless browser, no external `tex` binary. Point it at `\frac{1}{2}` and get back a crisp, scalable, self-contained `<svg>`.

```java
String svg = com.lattex.api.LatteX.render("\\frac{a+b}{c}");
```

> **Acknowledgments.** LatteX is a *clean-room* implementation — its layout is derived from Knuth's *TeXbook* (Appendix G) and the OpenType-MATH / SVG specifications, not from any existing renderer's source. That said, a grateful hat-tip to **[KaTeX](https://katex.org)**: we used its superb *supported-functions* coverage as a **feature reference** — *what* LaTeX commands are worth supporting — to shape our roadmap. Thanks also to the **STIX Two Math** font (SIL OFL) that LatteX bundles, and to the wider TeX/LaTeX ecosystem it stands on.

## Why

The JVM lacks a modern, permissively-licensed, web-first math renderer. KaTeX and MathJax are JavaScript. JLaTeXMath is excellent but old (AWT/image-first) **and GPL**. SnuggleTeX is permissive but limited to MathML. LatteX fills that gap: **Apache-2.0, pure Java, SVG-native.**

## Design

- **Java 25, modern.** The math tree is a `sealed interface` + `record` algebraic data type, laid out with exhaustive pattern-matching switches — no visitor boilerplate.
- **Zero runtime dependencies, framework-free.** No Spring, no anything. A JPMS module you drop in and call. The glyphs come from a bundled OFL math font (STIX Two Math), emitted as SVG `<path>`s — so there are no fonts to load in the browser either.
- **SVG-native, inline-capable.** Output is a minimal, sanitizer-friendly SVG subset — only `<svg>`/`<g>`/`<path>`/`<rect>`, with glyphs as inline filled `<path>`s (no `<text>`/`<use>`/`<defs>`/`<script>`). The renderer exposes each expression's height and depth so inline math sits correctly on the text baseline.
- **Native-image clean.** No reflection; ships GraalVM reachability metadata, and an optional standalone `lattex` native CLI.
- **Design-for-both, ship SVG.** A layout core + pluggable output backends (mirroring TeX's own `dvisvgm`/`dvipng` driver split): SVG today, an optional raster backend later.

## Status

Early but real. The parse → layout → SVG pipeline is wired end-to-end: `com.lattex.api.LatteX.render(...)` renders fractions, roots, scripts, big operators, and delimiters to SVG today. The `\lx[...]{...}` author syntax, inline em-sizing + baseline alignment, `fx` effects, and a click action menu (Copy / Graph) are built too. (These currently live on review branches, merging to the mainline soon.) See **[QUICKSTART.md](QUICKSTART.md)** for usage and cross-stack integration.

## The fx layer is OPTIONAL

The math renders from the jar **alone** — pure, inert `svg/g/path/rect`, no runtime, safe to inline anywhere. The `\lx` **effects** (glow, handscribe, supernova, and 16 more) are an *opt-in* layer: they ride the `<span class="lx-math" data-lx-fx-*>` wrapper and are driven by a small vanilla-JS runtime **bundled in the jar**. Include it only if you want the animations:

```java
LatteX.fxRuntimeJs()   // the runtime — serve as /js/lattex-fx.js, or inline in a trusted <script>
LatteX.fxStylesCss()   // the styles — serve as /css/lattex-fx.css, or inline in a <style>
```

Both are jar resources (`com/lattex/fx/lattex-fx.{js,css}`); a consumer gets them from the jar it already depends on — no separately-managed asset. Drop them into a page and the effects light up; leave them out and the math is exactly as safe/static as before. Browse the whole catalogue live in `examples/effects.html`.

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
