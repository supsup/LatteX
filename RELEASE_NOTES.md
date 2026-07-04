# LatteX — Release Notes

Notable changes to `main`. LatteX is a clean-room, pure-**Java 25**, zero-runtime-dependency **LaTeX → SVG** math renderer (Apache-2.0; bundles STIX Two Math). See [`QUICKSTART.md`](QUICKSTART.md) for usage and [`CONTRIBUTING.md`](CONTRIBUTING.md) for the clean-room rules + the SVG minimal-subset invariant.

## Foundation — 2026-07-03

The reviewed renderer foundation lands on `main`. It builds green and renders `x^2` end-to-end; the full parser and font layer are in place, with 2-D layout (fractions, roots, …) landing next in S4.

### Added

- **M0 — walking skeleton.** `LatteX.render("x^2")` drives the whole pipeline end-to-end: OpenType font → parse → layout → SVG. The emitter targets a deliberately **minimal, sanitizer-safe SVG alphabet** — only `<svg>`/`<g>`/`<path>`/`<rect>`, glyphs as inline filled `<path>`s (no `<text>`/`<use>`/`<defs>`/`<script>`, no `href`, no `data:`) — so output is safe to inline directly in HTML. Bundles **STIX Two Math** (the TTF/glyf variant, ratified over the OTF for simpler zero-dependency outline parsing). A real allow-list containment test fails the build on any drift.
- **S2 — OpenType MATH font layer.** Full `SfntFont`: simple **and composite** glyph outlines, advances + left-side-bearings, the complete `MathConstants` table, italic-correction + top-accent attachment, per-glyph `MathKern`, and `MathVariants` (vertical/horizontal glyph construction + assembly) for stretchy glyphs.
- **S3 — math model + parser.** A sealed `MathNode` ADT (`Atom`, `MathList`, `SupSub`, `Fraction`, `Radical`, `BigOperator`, `Fenced`, `Spacing`) + a parser covering `^`/`_`, `\frac`, `\sqrt[]{}`, `\sum`/`\int`/`\prod` with limits, `\left…\right` delimiters, greek, and an MVP symbol set. Malformed input **fails cleanly** with a named `MathSyntaxException` (0 crashes across the 99-entry corpus). Layout consumers use **default-free exhaustive switches** — the compiler enforces node coverage; nodes not yet laid out throw explicit stubs until S4.
- **Test corpus.** A 99-entry tiered LaTeX corpus (`examples/corpus.md`) driven by a drift-proof `CorpusParseTest` (single TSV source of truth) that asserts each entry's declared coverage tier matches actual parser behavior.

### Rendering today

**Fractions, roots, full sub/superscripts, big-operator limits, and scaled `\left…\right` delimiters** all render to SVG — S4 landed (a `Box` layout model, math styles, Appendix-G spacing). The parser accepts the full MVP grammar; broader symbol/accent/environment coverage is the next tier (see `LatteX_docs/gap.md`).

## Coverage & tooling — 2026-07-03 (later)

A broad coverage + tooling wave, each slice self-verified and green, the emitter/minimal-alphabet invariant intact throughout.

### Added

- **KaTeX-gap Tier-1 (complete) — general-math breadth.** 250+ **symbols** (relations, binary operators, arrows, greek variants, negations, `\not` prefix); **accents** (`\hat \vec \widehat \overline …` — new `Accent` node, MATH top-accent positioning, stretchy via glyph variants); **named operators** (`\sin \cos \lim \det \operatorname` — upright roman with limit placement); **`\text{…}` mode** (upright, spaces preserved — `\text \textbf \textit \texttt \mathrm`, new `TextRun` node); **spacing** (`\, \quad \! …` + the `\phantom` family). Parser coverage jumped **26 → 94** of the (now 119-entry) corpus.
- **KaTeX-gap Tier-2 — font-variant alphabets.** `\mathbb \mathcal \mathfrak \mathbf \mathsf \mathscr \mathit \mathtt \boldsymbol` via the Unicode Mathematical Alphanumeric Symbols block — a codepoint remap (**no new fonts**; STIX covers the block), including the Letterlike-Symbols exceptions (ℝ ℒ ℨ …).
- **S7 — native CLI.** A GraalVM `lattex` binary (~14.7 MB, reflection-free, font baked in): `echo '\frac{a}{b}' | lattex` → SVG. Plus a JVM `./gradlew run` fallback.
- **S8 — left-containment test + specimen gallery.** A build-failing test asserting emitter output stays ⊆ the minimal alphabet (full deny-list, teeth-verified), and a live-rendered gallery (`examples/gallery-specimen.html`).
- **Showcase page** (`examples/showcase.html`) — a polished, theme-aware landing page with 99 live-rendered SVGs.

### Next

- **`\lx` macro + render options** (`RenderOptions`: scale/color/mathstyle; the `\lx[…]{…}` author syntax) and the **container affordances** (`fx` effects, click action-menu, live graph plotting) — built on earlier branches; need **rebasing onto the now-much-evolved `main`** before review/merge.
- **Tier-3 — environments** (matrices, `align`, `cases`, `array`) — the big remaining structural gap.
- **MathML output backend** — a second emitter target for accessibility.
- **S8 docs pipeline** — markdown `$…$` → LaTeX → SVG (`MathMarkerConverter`) for `stafficy_docs/LatteX`.

See `LatteX_docs/gap.md` for the full competitive picture and `LatteX_docs/ideas.md` for exploration ideas.
