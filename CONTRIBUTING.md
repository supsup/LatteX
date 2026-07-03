# Contributing to LatteX

LatteX is small, modern, and deliberately opinionated. A few rules are **load-bearing** — they protect the license, the portability, and the security posture. Please read these before opening a PR.

## 1. Clean-room — the license rule (non-negotiable)

LatteX is **Apache-2.0**. To stay that way, we implement **from primary sources only**, never from another renderer's source code:

- **The layout algorithm** comes from **Knuth's _The TeXbook_ (Appendix G, "Generating Boxes from Formulas")** and _TeX: The Program_. The algorithm is a published method (not copyrightable), and TeX's own source is permissively licensed.
- **Glyphs + math metrics** come from the **public OpenType / sfnt + OpenType MATH-table specifications** and an **OFL-licensed** math font.
- **Output** follows the **W3C SVG** spec.

**Do not read GPL renderers' code (e.g. JLaTeXMath) and reproduce their structure.** You may use them as **black boxes** — compare rendered *output*, never copy *code* (verbatim or paraphrased). Copyright protects expression, and a GPL derivative cannot be relicensed to Apache-2.0. When in doubt, implement from the TeXbook and the specs — the same well every good renderer drew from.

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
