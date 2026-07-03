# LatteX LaTeX-math test corpus

A tiered regression/acceptance corpus for the LatteX math renderer, derived from
the canonical LaTeX math gallery (Wikipedia *"Displaying a formula"*). It is the
target that drives the S3→S8 build-out and will feed the S8 render gallery.

**Single source of truth.** Every entry below is a row in
[`src/test/resources/com/lattex/parse/corpus.tsv`](../src/test/resources/com/lattex/parse/corpus.tsv).
`CorpusParseTest` drives each row through `com.lattex.parse.MathParser.parse` and
asserts the declared tier matches what the parser actually does today, so this
document and the parser cannot drift. **Tiers here are empirical, not guessed.**

## Coverage tiers

| Tier | Meaning | `parse()` today |
| --- | --- | --- |
| **`PARSES-NOW`** | The S3 parser builds a valid tree today (verified). | succeeds |
| **`NEEDS-S4-LAYOUT`** | Parses today, but faithful rendering needs *new* 2D layout (delimiter sizing to content, eval bars). | succeeds |
| **`NEEDS-PARSER-NODE`** | Needs a new `MathNode` / parser feature (named in the entry). | throws `MathSyntaxException` |
| **`NEEDS-FONT-STYLE`** | Missing feature is fundamentally a font-variant glyph set or emitter color. | throws `MathSyntaxException` |
| **`PARSER-BUG`** | `parse()` crashes with a *non*-`MathSyntaxException` (NPE/SOE/CCE). A robustness bug. | crashes |

> Empirical result over **99 entries**: `PARSES-NOW` **26**, `NEEDS-S4-LAYOUT`
> **3**, `NEEDS-PARSER-NODE` **60**, `NEEDS-FONT-STYLE` **10**, `PARSER-BUG`
> **0**. The parser fails cleanly (a named `MathSyntaxException`) on the entire
> not-yet frontier — no crashes.

Note on the split: `PARSES-NOW` vs `NEEDS-S4-LAYOUT` both parse today; the layout
tier is reserved for parsed trees whose faithful rendering needs a *new* S4
capability (stretchy delimiter sizing, eval bars) beyond the box-stacking that
`\frac`/`\sqrt`/scripts already establish. Constructs that don't even parse yet
(matrices, cases, `\cfrac`) are tiered by their *current* frontier —
`NEEDS-PARSER-NODE` — with a note that S4 layout follows once the node exists.

---

## Scripts / fractions / roots

| LaTeX | Description | Tier |
| --- | --- | --- |
| `x^2` | single superscript | `PARSES-NOW` |
| `y_1` | single subscript | `PARSES-NOW` |
| `k_n^2` | sub and sup on the same base | `PARSES-NOW` |
| `x^{1.01}` | grouped (multi-token) superscript | `PARSES-NOW` |
| `k_{n+1}=n^2+k_n^2-k_{n-1}` | recurrence with grouped scripts | `PARSES-NOW` |
| `\frac{b}{c}` | simple fraction | `PARSES-NOW` |
| `\frac{\frac{1}{x}+\frac{1}{y}}{y-z}` | nested fraction (braced args) — recursion of core `\frac` layout | `PARSES-NOW` |
| `\frac{\frac1x+\frac1y}{y-z}` | nested fraction, terse `\frac1x` form — needs **brace-optional single-token arguments** | `NEEDS-PARSER-NODE` |
| `\sqrt{x^3+5}` | square root | `PARSES-NOW` |
| `\sqrt{\frac{a}{b}}` | root of a fraction (braced) — recursion of core radical layout | `PARSES-NOW` |
| `\sqrt[n]{1+x+x^2+\cdots+x^n}` | nth root, with `\cdots` | `PARSES-NOW` |
| `x=a_0+\cfrac{1}{a_1+\cfrac{1}{a_2+\cfrac{1}{a_3+\cfrac{1}{a_4}}}}` | continued fraction — needs **`\cfrac`** node (then S4 layout) | `NEEDS-PARSER-NODE` |
| `{}^3/_7` | diagonal-fraction hack (empty-group scripts around a slash) | `PARSES-NOW` |

## Sums / integrals / big operators

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\sum_{i=1}^{10} t_i` | sum with lower/upper limits | `PARSES-NOW` |
| `\sum_{\substack{0<i<m\\0<j<n}}P(i,j)` | multi-line subscript — needs **`\substack`** node | `NEEDS-PARSER-NODE` |
| `\int y\,dx` | indefinite integral with thin space | `PARSES-NOW` |
| `\int_0^\infty e^{-x}\,dx` | definite integral over [0, ∞) | `PARSES-NOW` |
| `\int_b^c` | integral with bare bounds | `PARSES-NOW` |
| `\prod_{i=1}^n` | product with limits | `PARSES-NOW` |
| `\coprod` | coproduct — needs **big-operator symbol** | `NEEDS-PARSER-NODE` |
| `\bigoplus` | direct-sum big operator | `NEEDS-PARSER-NODE` |
| `\bigotimes` | tensor-product big operator | `NEEDS-PARSER-NODE` |
| `\bigcap` | big intersection | `NEEDS-PARSER-NODE` |
| `\bigcup` | big union | `NEEDS-PARSER-NODE` |
| `\bigsqcup` | big square union | `NEEDS-PARSER-NODE` |
| `\bigvee` | big disjunction | `NEEDS-PARSER-NODE` |
| `\bigwedge` | big conjunction | `NEEDS-PARSER-NODE` |
| `\bigodot` | big circled dot | `NEEDS-PARSER-NODE` |
| `\biguplus` | big multiset union | `NEEDS-PARSER-NODE` |
| `\oint` | contour integral | `NEEDS-PARSER-NODE` |
| `\iint` | double integral | `NEEDS-PARSER-NODE` |
| `\iiint` | triple integral | `NEEDS-PARSER-NODE` |
| `\idotsint` | dotted multiple integral | `NEEDS-PARSER-NODE` |

> All of `\coprod … \idotsint` share one feature: **extend the `BIG_OPERATORS`
> table** (they already flow through the existing `BigOperator` node + limits
> machinery once the symbol is registered).

## Delimiters — fixed

| LaTeX | Description | Tier |
| --- | --- | --- |
| `(a)` | round parentheses | `PARSES-NOW` |
| `[b]` | square brackets | `PARSES-NOW` |
| `\{c\}` | escaped curly braces | `PARSES-NOW` |
| `|d|` | vertical bars | `PARSES-NOW` |
| `\|e\|` | double-bar — parses, but `\|` currently maps to the single `|` glyph (fidelity gap, not a crash) | `PARSES-NOW` |
| `\langle f\rangle` | angle brackets **as ordinary symbols** (outside `\left..\right`) | `NEEDS-PARSER-NODE` |
| `\lfloor g\rfloor` | floor delimiters as ordinary symbols | `NEEDS-PARSER-NODE` |
| `\lceil h\rceil` | ceiling delimiters as ordinary symbols | `NEEDS-PARSER-NODE` |
| `\ulcorner i\urcorner` | corner delimiters as ordinary symbols | `NEEDS-PARSER-NODE` |
| `/j\backslash` | slash and `\backslash` symbol | `NEEDS-PARSER-NODE` |

> The delimiter code points (`\langle \lfloor \lceil …`) are already known to
> `readDelimiter` inside `\left..\right`; the gap is registering them in the
> ordinary `SYMBOLS` table so they work as bare atoms too.

## Delimiters — scaled (`\left \right`)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\left(\frac{x^2}{y^3}\right)` | scaled parens — parses; needs delimiter sizing to content | `NEEDS-S4-LAYOUT` |
| `\left\{\frac{x^2}{y^3}\right\}` | scaled braces — parses; needs delimiter sizing | `NEEDS-S4-LAYOUT` |
| `\left.\frac{x^3}{3}\right|_0^1` | eval bar (braced) — parses; needs null-left + scaled-bar layout | `NEEDS-S4-LAYOUT` |
| `\left.\frac{x^3}3\right|_0^1` | eval bar, terse `\frac` denominator — needs **brace-optional single-token arguments** | `NEEDS-PARSER-NODE` |
| `P\left(A=2\middle|\frac{A^2}{B}>4\right)` | mid delimiter — needs **`\middle`** node | `NEEDS-PARSER-NODE` |
| `\binom{n}{r}` | binomial coefficient — needs **`\binom`** node | `NEEDS-PARSER-NODE` |
| `\frac{n!}{r!(n-r)!}=\binom{n}{r}` | binomial identity — needs **`\binom`** node | `NEEDS-PARSER-NODE` |

## Accents / decorations

| LaTeX | Description | Tier |
| --- | --- | --- |
| `a'` | prime (parses as an ordinary `'` atom) | `PARSES-NOW` |
| `a''` | double prime (two `'` atoms) | `PARSES-NOW` |
| `a''''` | quadruple prime (four `'` atoms) | `PARSES-NOW` |
| `\hat{a}` | hat accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\bar{a}` | bar accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\dot{a}` | dot accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\ddot{a}` | double-dot accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\acute{a}` | acute accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\grave{a}` | grave accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\check{a}` | caron accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\breve{a}` | breve accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\tilde{a}` | tilde accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\vec{a}` | vector-arrow accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\mathring{a}` | ring accent — needs **Accent** node | `NEEDS-PARSER-NODE` |
| `\aa` | the letter å (a-with-ring) — needs symbol | `NEEDS-PARSER-NODE` |
| `\widehat{AAA}` | wide (stretchy) hat — needs **stretchy-Accent** node | `NEEDS-PARSER-NODE` |
| `\widetilde{AAA}` | wide (stretchy) tilde — needs **stretchy-Accent** node | `NEEDS-PARSER-NODE` |
| `\overline{a}` | overline — needs **Overline** node | `NEEDS-PARSER-NODE` |
| `\underline{a}` | underline — needs **Underline** node | `NEEDS-PARSER-NODE` |

## Text / spacing / color

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\text{50 apples}\times\text{100 apples}=\text{lots of apples}^2` | text runs — needs **`\text`** node (upright text mode) | `NEEDS-PARSER-NODE` |
| `f(n)=\begin{cases}n/2&\text{if }n\text{ even}\\-(n+1)/2&\text{if }n\text{ odd}\end{cases}` | piecewise cases — needs **cases environment** node (then S4 layout) | `NEEDS-PARSER-NODE` |
| `k={\color{red}x}-2` | colored subterm — needs **`\color`** node + emitter color | `NEEDS-FONT-STYLE` |

## Matrices / arrays

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\begin{matrix}a&b\\c&d\end{matrix}` | plain matrix — needs **environment + `&`/`\\`** node | `NEEDS-PARSER-NODE` |
| `\begin{pmatrix}a&b\\c&d\end{pmatrix}` | parenthesized matrix | `NEEDS-PARSER-NODE` |
| `\begin{bmatrix}a&b\\c&d\end{bmatrix}` | bracketed matrix | `NEEDS-PARSER-NODE` |
| `\begin{Bmatrix}a&b\\c&d\end{Bmatrix}` | brace matrix | `NEEDS-PARSER-NODE` |
| `\begin{vmatrix}a&b\\c&d\end{vmatrix}` | determinant (single-bar) matrix | `NEEDS-PARSER-NODE` |
| `A_{m,n}=\begin{pmatrix}a_{1,1}&\cdots&a_{1,n}\\\vdots&\ddots&\vdots\\a_{m,1}&\cdots&a_{m,n}\end{pmatrix}` | full matrix with `\cdots \vdots \ddots` | `NEEDS-PARSER-NODE` |
| `\bordermatrix{&1&2\\1&a&b\\2&c&d}` | bordered matrix with row/col labels — needs **`\bordermatrix`** node | `NEEDS-PARSER-NODE` |
| `\begin{array}{c|c}1&2\\\hline 3&4\end{array}` | array with vertical+horizontal rules — needs **array node + column spec + `\hline`** | `NEEDS-PARSER-NODE` |
| `\vdots` | vertical-dots symbol | `NEEDS-PARSER-NODE` |
| `\ddots` | diagonal-dots symbol | `NEEDS-PARSER-NODE` |

## Named operators / misc

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\lim_{x\to\infty}\exp(-x)=0` | limit and exp — needs **named-operator** node (`\lim` under-limits, `\exp`) | `NEEDS-PARSER-NODE` |
| `\cos(2\theta)=\cos^2\theta-\sin^2\theta` | cos and sin — needs **named-operator** node | `NEEDS-PARSER-NODE` |
| `\frac{d}{dx}(kg(x))` | derivative operator built from primitives | `PARSES-NOW` |
| `x\equiv a\pmod{b}` | modulo — needs **`\pmod`** node | `NEEDS-PARSER-NODE` |
| `\boldsymbol{\beta}=(\beta_1,\beta_2,\ldots,\beta_n)` | bold beta vector — needs **`\boldsymbol`** (bold-math variant) | `NEEDS-FONT-STYLE` |
| `\top` | top symbol | `NEEDS-PARSER-NODE` |
| `\bot` | bottom symbol | `NEEDS-PARSER-NODE` |

## Font / style alphabets

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\mathcal{ABCDEF}` | calligraphic alphabet — needs **font-variant** node + glyphs | `NEEDS-FONT-STYLE` |
| `\mathbb{ABCDEF}` | blackboard-bold alphabet — needs **font-variant** node + glyphs | `NEEDS-FONT-STYLE` |
| `\mathfrak{ABCDEF}` | fraktur alphabet — needs **font-variant** node + glyphs | `NEEDS-FONT-STYLE` |
| `\mathrm{ABCDEF}` | upright roman — needs **font-variant** node | `NEEDS-FONT-STYLE` |
| `\mathsf{ABCDEF}` | sans-serif — needs **font-variant** node + glyphs | `NEEDS-FONT-STYLE` |
| `\mathit{ABCDEF}` | math italic — needs **font-variant** node | `NEEDS-FONT-STYLE` |
| `\mathbf{ABCDEF}` | bold — needs **font-variant** node + glyphs | `NEEDS-FONT-STYLE` |
| `\boldsymbol{abcdef}` | bold symbols — needs **bold-math** variant | `NEEDS-FONT-STYLE` |
| `\alpha,A,\beta,B,\gamma,\Gamma,\pi,\Pi,\phi,\mu,\Phi` | greek upper+lower mixed with latin | `PARSES-NOW` |
| `\varphi` | variant phi — needs symbol | `NEEDS-PARSER-NODE` |

---

## Ranked frontier — what the gallery demands most

New parser features ordered by how many corpus entries each unblocks:

1. **Accent node** (incl. stretchy + over/underline) — ~15 entries:
   `\hat \bar \dot \ddot \acute \grave \check \breve \tilde \vec \mathring`,
   plus `\widehat \widetilde` (stretchy) and `\overline \underline`.
2. **Extend the `BIG_OPERATORS` symbol table** — ~14 entries:
   `\coprod \bigoplus \bigotimes \bigcap \bigcup \bigsqcup \bigvee \bigwedge
   \bigodot \biguplus \oint \iint \iiint \idotsint` (reuse the existing
   `BigOperator` node).
3. **Extend the ordinary `SYMBOLS` table** — ~11 entries: delimiter symbols as
   bare atoms (`\langle \rangle \lfloor \rfloor \lceil \rceil \ulcorner
   \urcorner \backslash`) + misc (`\vdots \ddots \top \bot \varphi \aa`).
4. **Font-variant / `Styled` node** — 10 entries: `\mathcal \mathbb \mathfrak
   \mathrm \mathsf \mathit \mathbf \boldsymbol` (+ `NEEDS-FONT-STYLE` glyph sets).
5. **Environment / alignment node** (`\begin..\end`, `&`, `\\`) — ~9 entries:
   the five matrix flavors + full matrix + `array` (+ `\hline`, column spec) +
   `cases` (+ `\bordermatrix` as a variant). Also unblocks S4 grid layout.
6. **Named-operator node** — 3 entries: `\lim \exp \cos \sin` (and `\pmod`).
7. **Brace-optional single-token arguments** (`\frac1x`, `\sqrt2`) — 2 entries.
8. **`\binom` node** — 2 entries.
9. Singletons: **`\cfrac`**, **`\middle`**, **`\substack`**, **`\text`**,
   **`\color`**, **`\pmod`** — 1 entry each (but `\text` recurs inside `cases`).
