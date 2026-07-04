# LatteX Slowstart ☕📖

A slower, narrative companion to **[QUICKSTART.md](QUICKSTART.md)**.

The quickstart answers *"what's the one call and how do I integrate it?"* fast.
This guide answers a different question: **"which of these people is me, and what
should I actually do?"** Below are a handful of short roleplay scenarios — a named
person, a real situation, the LatteX approach that fits, *why* it fits, and a
concrete, tested snippet. Skim the headings, find the one closest to your
situation, and start there.

Every code block here has been run against the code on this branch. Where a
capability isn't built yet, the scenario says so plainly and points at the
[status legend in QUICKSTART](QUICKSTART.md#7-status-legend). Accuracy over hype.

**The one thing that's true in every scenario:** the whole core is a single,
stateless call — `render(latex)` → a self-contained SVG string. Everything below
is just *how you reach that call* from where you already are.

---

## 1. Priya — backend engineer generating documents on the JVM

> *"Our billing service emails PDF/HTML invoices, and finance wants the tax
> formulas shown as real math, not `(a*b)/c` in a monospace font. I'm in a
> Java service. I don't want to stand up a browser or call out to a `tex`
> install in production."*

**Approach: embed the JAR and call the API directly.** This is the home-turf
case. LatteX is a pure-Java-25 library with **zero runtime dependencies**, so it
drops into any JVM service with no transitive baggage, no native processes, no
network hop. `render` is a stateless static method — you give it LaTeX, you get
back an SVG string you can splice straight into the HTML you're already building.

```java
import com.lattex.api.LatteX;

// Anywhere in your request/report code:
String svg = LatteX.render("\\frac{\\text{net} \\times \\text{rate}}{100}");

String pageFragment = """
    <p>Tax due:</p>
    %s
    """.formatted(svg);   // the SVG inlines directly — it's sanitizer-safe by construction
```

The returned SVG is complete and standalone (it carries its own `viewBox`,
`width`/`height`, and an `aria-label`), so you can also just write it to a `.svg`
file, or hand it to your PDF renderer.

**No Spring Boot starter required — and that's on purpose.** There's nothing to
auto-configure: no beans, no connection pool, no lifecycle. It's a static call
against an immutable, lazily-loaded bundled font. In a Spring MVC app you'd call
`LatteX.render(...)` from your controller/service exactly like above. *If* you
later wanted a `<latex:...>` Thymeleaf tag so template authors never touch Java,
that would be a thin **future** add (a small Thymeleaf dialect wrapping the same
call) — but importing the jar and calling `render()` is the norm, and it's all
you need today.

**Status: Built.** `com.lattex.api.LatteX.render(String)` renders fractions,
roots, sub/superscripts, big operators, and delimiters on the mainline today.
Typed styling (scale/color/math-style via a `RenderOptions` overload and the
`\lx[...]{...}` author macro) is built on review branches and merging soon — see
[QUICKSTART §2–§3](QUICKSTART.md#2-render-one-expression); until it lands, the
mainline entry point is the single-argument `render(String)` shown above.

---

## 2. Marcus — a blog/docs author who just wants `$…$` to render

> *"I write my posts in Markdown. I don't want a math *framework* — I want the
> `$E = mc^2$` I already type to come out as crisp math in the built HTML.
> That's it."*

**Approach: a tiny build-time preprocessor that shells out to the `lattex`
CLI, once per math span.** You don't need the JVM in your toolchain or a plugin
for your exact static-site setup — you need a script that finds each `$…$`, runs
`lattex` on the inside, and pastes the SVG back in. Because the output SVG is
sanitizer-safe, it inlines with no post-processing.

A minimal shell preprocessor (replaces every `$…$` in a file with rendered SVG):

```bash
#!/usr/bin/env bash
# md-math.sh FILE  →  prints the file with each $…$ span replaced by its SVG.
# Uses the `lattex` native CLI (see QUICKSTART §5 for how to build it).
perl -pe 's{\$(.+?)\$}{`lattex "$1"`}ge' "$1"
```

```bash
./md-math.sh post.md > post.expanded.md     # then feed post.expanded.md to your MD→HTML step
```

That's the "nothing more" version. The `lattex` CLI reads the expression as an
argument (or on stdin) and prints the SVG to stdout — see Scenario 6 for the CLI
in a real pipeline.

**Status: CLI = Built (S7). First-class pipeline = Planned (S8).** Shelling out
per span works today. A *first-class* Markdown→SVG pipeline that understands
`$…$` (inline), `$$…$$` (display), and `\lx[…]{…}` markers as a single build step
is **planned** — on the JVM as a flexmark extension, with reference remark/rehype
and Python filters as a **future** add. See
[QUICKSTART §6](QUICKSTART.md#6-markdown--html-pipeline).

---

## 3. Dr. Chen — a data scientist in a Python / Jupyter notebook

> *"I'm writing up an analysis in a notebook. I want the model's loss function
> shown as actual math in a cell, next to the plot — and I'm in Python, not
> Java."*

**Approach: shell out to the native `lattex` binary with `subprocess`, and
display the SVG inline.** No JVM, no Python package to install — the native CLI
is a standalone binary, so from Python it's just a subprocess that returns a
string. Jupyter renders SVG directly with `IPython.display.SVG`.

```python
import subprocess
from IPython.display import SVG

def latex_svg(expr: str) -> str:
    return subprocess.run(
        ["lattex", expr],
        capture_output=True, text=True, check=True,
    ).stdout

SVG(latex_svg(r"\sum_{i=1}^{n} i = \frac{n(n+1)}{2}"))   # renders inline in the cell
```

(Use a raw string — `r"..."` — so Python leaves the backslashes for LaTeX.)

**Status: Built (S7).** The native CLI is a real, self-contained binary; see
[QUICKSTART §5](QUICKSTART.md#5-integration-by-stack) for building it once with
GraalVM (or running the same CLI on any JVM if you'd rather not).

---

## 4. Sam — a static-site generator author (Hugo / 11ty / mdBook)

> *"My site is built with a static-site generator. I want writers to drop math
> into content and have it rendered at build time — baked into the HTML, no
> client-side JS, no runtime."*

**Approach: a build-time preprocessor that rewrites `$…$`/`$$…$$` into inline SVG
before the SSG's markdown engine runs.** From the engine's point of view the SVG
is then just inline HTML that passes straight through. The host differs — an
**11ty** `beforeMarkdown` transform, a **Hugo** render hook / pre-build step, an
**mdBook** preprocessor — but the pattern is identical:

```
markdown source
  → scan for math markers ($…$ inline, $$…$$ display) — fence + escape aware
  → render each through LatteX (inline vs display; --batch for many-per-page)
  → splice the baked SVG in place (malformed → leave the verbatim source)
  → the SSG bakes markdown → HTML as usual
  → static page. No browser JS. No runtime. No sanitizer.
```

Concretely as an **11ty** transform (11ty is Node, so it shells to the binary),
one `lattex --batch` call per page rather than one process per equation:

```js
// .eleventy.js
const { execFileSync } = require("node:child_process");

function renderAll(exprs, inline) {                       // ONE process for the page
  const flags = inline ? ["--batch", "--inline", "-0"] : ["--batch", "-0"];
  const out = execFileSync("lattex", flags,
    { input: exprs.join("\0"), encoding: "utf8" });
  return out.split("\0").slice(0, exprs.length);          // NUL-delimited SVGs, in order
}

module.exports = (eleventyConfig) => {
  eleventyConfig.addTransform("latex", (content) =>
    rewriteMarkers(content, renderAll));  // fence/escape-aware scan → renderAll → splice
};
```

**The four details that bite** — learned wiring the real seam:

- **Inline vs display is not one filter.** `$…$` → `lattex --inline` (text style:
  smaller fractions, side-set limits — it *sits on the prose line*); `$$…$$` →
  `lattex` (display: full size, its own block). Render inline math in display mode
  and full-height fractions shove the line open mid-sentence. One flag, but it's
  "math in a sentence" reading right versus broken.
- **Batch, or pay a JVM start per equation.** A `lattex` call *per marker* spawns
  one process per equation — a 50-equation page pays 50 cold starts. Collect the
  page's markers and make one `lattex --batch` call (NUL-delimited in and out, one
  process); splice back by index. See **Scenario 7 (Nadia)** for batch depth. (A
  JVM-based SSG can call the jar API in-process — no spawn at all.)
- **Marker detection is fence- and escape-aware.** `$x$` inside a fenced code
  block or a `code span` is a writer *documenting* the syntax — skip it. `\$` is a
  literal dollar (a price: `\$5`), never a delimiter. Match `$$` before `$` so a
  display block isn't mis-split into two empty inlines.
- **Malformed → verbatim, never fail the build.** If an expression won't render
  (`lattex` exits nonzero / emits an error record in `--batch`), leave the original
  `$…$` source in place — don't abort the page bake. A typo degrades to visible
  source the author can fix; the rest of the site still ships.

**Why bake with LatteX at all** — the trust story a browser math renderer can't
give a static site: LatteX emits **only** `svg`/`g`/`path`/`rect` with geometry +
fill — never `<script>`, `<foreignObject>`, `<use>`, external refs, or `on*`. So
the baked SVG is inert static geometry: **no downstream sanitization of the math,
and no runtime JS** (unlike MathJax/KaTeX in the browser). For a site baking
*untrusted contributor* markdown, "math in → safe static SVG out, no runtime, no
sanitize" is the structural win a client-side renderer can't offer.

**Status: Built via the CLI (S7).** The `lattex` binary — `--inline` for the prose
split, `--batch` to amortize a page's many spans — is what all of these call;
dedicated first-party SSG plugins are **future** (see the status legend).

---

## 5. Ana — a security-conscious app rendering *user-submitted* math

> *"We let readers post comments and answers that can contain math. Rendering
> arbitrary user-authored markup is exactly the XSS nightmare I've spent my
> career avoiding. Can I inline reader LaTeX without opening a hole?"*

**Approach: render it with LatteX and inline the SVG — the output is
sanitizer-safe *by construction*, so this is the case LatteX is quietly best
at.** The emitter targets a deliberately tiny SVG alphabet — only `<svg>`,
`<g>`, `<path>`, and `<rect>`, with every glyph drawn as an inline filled
`<path>`. It **never** emits `<text>`, `<use>`, `<defs>`, `<script>`, `<style>`,
`<foreignObject>`, event handlers (`on*=`), `href`/`xlink:`, or `data:`/`javascript:`
URIs. There is no attribute in the output that can carry executable content.

```java
import com.lattex.api.LatteX;

// `userLatex` came from an untrusted reader. If it isn't valid math,
// render throws — you catch it and show an error, never raw markup.
String svg = LatteX.render(userLatex);
// `svg` is safe to inline directly: it sits inside a standard sanitizer
// allow-list already, because it only ever uses svg/g/path/rect.
```

The key word is **by construction**, not "by careful review": the minimal
alphabet is enforced by a **build-failing containment test**
(`S8LeftContainmentTest`) that runs the emitter over a broad battery of every
construct and symbol the parser accepts and fails the build if any output
element or attribute falls outside the contract. So a future feature *cannot*
silently introduce fresh attack surface — the build breaks first. (Do still
validate/limit input length and handle the parse exception, as above; the
guarantee is about *output* markup, not about accepting unbounded input.)

**Status: Built.** The minimal-alphabet emitter and its containment test are on
the mainline. See [QUICKSTART §1](QUICKSTART.md#1-what-lattex-is) for the full
alphabet description.

---

## 6. Jordan — a CI / docs-build pipeline engineer

> *"Our docs build runs in CI and renders a few hundred formulas each time.
> I care about two things: it must not need a JVM warmup per formula, and it
> has to be reproducible."*

**Approach: the native `lattex` binary as a step in your Makefile / CI.** The
native-image binary has **no JVM to start**, so shelling out to it hundreds of
times is cheap — roughly an order of magnitude faster *per invocation* than
`java -jar` (which pays a cold JVM start every time). Same core underneath, so
the SVG is byte-identical whichever launch mode you use.

```make
# Makefile — render every .tex snippet in math/ to an .svg in build/
SNIPPETS := $(wildcard math/*.tex)
SVGS     := $(SNIPPETS:math/%.tex=build/%.svg)

build/%.svg: math/%.tex
	@mkdir -p $(@D)
	lattex "$$(cat $<)" -o $@

math: $(SVGS)
```

```bash
make math        # renders only the snippets that changed
```

Because `render` is deterministic and the font is baked into the binary, the same
input always produces the same SVG — good for caching and for diffing generated
output in review.

**Status: Built (S7).** For the measured native-vs-JVM startup numbers and a
runnable benchmark (`tools/bench.sh`), see
[QUICKSTART §5 → Performance](QUICKSTART.md#performance--native-binary-vs-java--jar-vs-gradlew-run).

---

## 7. Nadia — rendering a whole page's worth of math at once

> *"My page has 200 `$…$` spans. Shelling out once per span works, but that's 200
> process starts — 200× the startup cost. Can I render them all in one shot?"*

**Approach: `lattex --batch` — many expressions in, many SVGs out, one process.**
Feed one expression per line on stdin; get back one **NUL-terminated** SVG record
per input, in the same order. The JVM starts (or the native binary spawns)
**once**, no matter how many formulas you throw at it — so the per-formula cost
collapses toward just the render itself.

```bash
# one LaTeX expression per line → one NUL-delimited SVG per line, in order
printf 'x^2\n\\frac{a}{b}\n\\sum_{i=1}^{n} i\n' | lattex --batch > out.nul
```

Split the output on NUL to line records back up 1:1 with your inputs:

```python
records = open("out.nul", "rb").read().split(b"\0")[:-1]   # drop the trailing empty
for src, rec in zip(["x^2", "\\frac{a}{b}", "\\sum_{i=1}^{n} i"], records):
    svg = rec.decode("utf-8")
    ok  = svg.startswith("<svg")          # else it's a "lattex: error: …" line
    print(src, "→", "rendered" if ok else svg)
```

A single bad expression doesn't sink the batch — its slot becomes a marked
`lattex: error: …` record and the rest still render (the exit code is `1` if any
failed, so CI still catches it). If an expression contains a literal newline (a
rare multi-line block), separate inputs with NUL instead and add `-0`.

This is the efficient backend for a docs pipeline rendering a page's many spans —
and it's what makes a fair **jar-vs-binary** timing comparison possible: without
it you'd be measuring process-spawn cost, not the renderer.

**Status: Built.** `--batch` (and `-0` / `--null`) ship in the CLI.

---

## 8. Lee — "I just want to see it work"

> *"Before I integrate anything, show me it renders."*

**Approach: one CLI call, then open a demo page.**

```bash
lattex "x^2 + y^2 = z^2" -o hello.svg && open hello.svg
```

No native binary built yet? The same CLI runs on any JVM straight from the repo:

```bash
./gradlew run --args="x^2 + y^2 = z^2"      # prints the SVG to stdout
```

Then open the pre-rendered galleries in a browser to see the breadth:

- `examples/symbol-index.html` — the auto-enumerated index of every command the
  parser accepts
- `examples/showcase.html` — a curated tour of what renders
- `examples/gallery.html` — assorted rendered specimens

**Status: Built.** These pages ship in the repo and render with no build step.

---

## Which mode should I use? — a recap

All modes wrap the same core: `render(latex)` → SVG. Pick by where you're
calling from.

| You are… | Use | Call / ship | Status |
|---|---|---|---|
| on the JVM (Java/Kotlin/Scala) | **the JAR** | `com.lattex.api.LatteX.render(latex)` | **Built** |
| on any other stack (Python, Node, Ruby, Go, shell) | **the native CLI** | `lattex "…"` (argv or stdin) → SVG | **Built (S7)** |
| rendering many formulas at once (a page, a batch job) | **the CLI in `--batch`** | `… \| lattex --batch` → NUL-delimited SVGs, one process | **Built** |
| just trying it, or a one-off without building native | **`./gradlew run` / `java -jar`** | `./gradlew run --args="x^2"` | **Built** (dev / one-off) |
| authoring Markdown/docs and want `$…$` markers understood | **CLI preprocessor now; first-class pipeline later** | shell out per span (§2), or the flexmark extension | CLI **Built**; pipeline **Planned (S8)** |
| wanting typed styling (scale / color / math-style, `\lx[…]{…}`) | the `RenderOptions` overload + `\lx` macro | *(see QUICKSTART §2–§3)* | on review branches, **merging soon** |
| wanting a hosted `POST LaTeX → SVG` service | HTTP wrapper | — | **Planned / optional** |
| wanting it to run client-side in the browser | WASM build | — | **Future** |

For the exact commands to build the native binary, the runnable jar, and the
full status legend, head to **[QUICKSTART.md](QUICKSTART.md)**.

---

*LatteX is Apache-2.0; the bundled STIX Two Math font is under the SIL Open Font
License (OFL). See [QUICKSTART.md](QUICKSTART.md) and
[CONTRIBUTING.md](CONTRIBUTING.md) for more.*
