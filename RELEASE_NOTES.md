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

`x^2` (the atom + superscript class) renders to SVG. The parser accepts the full MVP grammar; 2-D layout for fractions, roots, big-operator limits, and scaled delimiters lands in **S4** (next).

### In review / next

- **S4 — Appendix-G layout** (fractions, roots, full sub/superscripts, big-operator limits, scaled delimiters). In peer review; merges next.
- **Render options + `\lx` macro** — `render(latex, RenderOptions)` (scale / color / mathstyle) and the self-delimiting `\lx[…]{…}` author syntax (validated, typed, fail-loud). Built; review pending.
- **Container affordances** — inline em-sizing + baseline alignment, `fx` effects, a click action-menu (Copy LaTeX / contextual Graph), and live graph plotting (`graph.open=single|multi`). Prototyped page-side; the production home is the S8 markdown→HTML docs pipeline. The SVG stays clean — every affordance lives on the container, never in the sanitized SVG.
- **S7 — native CLI** — a GraalVM `lattex` binary so non-JVM stacks (Node, Python, …) can shell out for SVG. Groundwork in place (reflection-free; font resource-config shipped).
