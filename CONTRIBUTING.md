# Contributing to LatteX

LatteX is small, modern, and deliberately opinionated. A few rules are **load-bearing** — they protect the license, the portability, and the security posture. Please read these before opening a PR.

## 1. Clean-room — the license rule (non-negotiable)

LatteX is **Apache-2.0**, and every contribution must keep it that way. Copyright protects **expression** (source code — its structure, sequence, and organization), not **ideas or algorithms**. A GPL derivative **cannot** be relicensed to Apache-2.0, so a single tainted file can compromise the whole project. This rule matters most in the parser and layout engine, where the temptation to "just look at how JLaTeXMath does it" is highest — so hold the line there especially.

**DO — implement from these PRIMARY sources, and cite them in the code:**
- **Layout** → Knuth, *The TeXbook* **Appendix G** ("Generating Boxes from Formulas") + *TeX: The Program* (`mlist_to_hlist`). Non-trivial layout code **must** carry a comment naming the specific rule/section it implements (e.g. `// TeXbook App.G rule 15b`), so provenance is auditable.
- **Glyphs + math metrics** → the public **OpenType/sfnt + OpenType MATH-table** specs + an **OFL** math font.
- **Output** → the **W3C SVG** spec.

**DO NOT:**
- Open a GPL renderer's source (JLaTeXMath, MicroTeX, …) to guide your implementation. They are **black boxes** — you may compare their rendered *output* to ours, never read their *code*.
- Reproduce another renderer's code structure, class layout, or operation sequence — even paraphrased. The legal test is **not** "byte-identical"; it is "not a derivative / no substantial similarity." Similar *structure* can infringe.
- **Chinese wall:** if you have read a GPL renderer's implementation of a component, you should **not** be the person who writes LatteX's version of that component.

**Enforcement:** a PR that can't cite a primary source for a non-trivial algorithm — or that mirrors a GPL renderer's structure — gets sent back. When in doubt, go **upstream**: implement from Knuth and the specs, the same well every renderer drew from.

## 2. Zero runtime dependencies, framework-free

The core library depends on **`java.base` only**. No Spring, no DI framework, no third-party runtime jars. Test-scope dependencies (JUnit 5) are fine. An optional raster backend may use `java.desktop`, but it lives in its own module — never the core.

## 3. Java 25, modern idioms

Model the math tree as a `sealed interface` + `record` ADT and traverse it with **exhaustive pattern-matching switches** (record deconstruction), so the compiler proves every node type is handled. Records for value types (`Metrics`, path segments, points). No mutable POJOs or visitor scaffolding where an ADT fits.

## 4. Native-image clean

No runtime reflection or dynamic classloading. Any bundled resource (the font) must ship **GraalVM reachability metadata** so native-image consumers work unchanged. If you add a resource, register it.

## 5. The emitter ↔ sanitizer minimal-subset invariant

The SVG emitter targets a **named minimal subset**: `<path>` + basic transforms + `<text>`, and **never** `<use>`, `<defs>`, `<symbol>`, `<foreignObject>`, or `<script>`. This subset is a contract with downstream HTML sanitizers (LatteX SVG must pass unchanged). A **two-way containment test** enforces it and must stay green — if you change the emitter's output alphabet, the test (and the downstream allow-list) has to move with it, not around it.

## Build & test

```bash
./gradlew build      # compile + test
./gradlew test       # tests only
```

Java 25 toolchain, provisioned by Gradle. Keep the build dependency-light and the tests deterministic (env-scrubbed — no ambient state).
