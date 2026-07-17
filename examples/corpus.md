# LatteX LaTeX-math test corpus

A tiered regression/acceptance corpus for the LatteX math renderer, derived from
the canonical LaTeX math gallery (Wikipedia *"Displaying a formula"*). It is the
target that drives the S3→S8 build-out and will feed the S8 render gallery.

**Single source of truth.** Every entry below is a row in
[`src/test/resources/com/lattex/parse/corpus.tsv`](../src/test/resources/com/lattex/parse/corpus.tsv).
`CorpusParseTest` drives each row through `com.lattex.parse.MathParser.parse` and
asserts the declared tier matches what the parser actually does today, so this
document and the parser cannot drift. **Tiers here are empirical, not guessed.**

> **Generated file — do not edit by hand.** `CorpusDocTest` regenerates this
> document from `corpus.tsv` on every build and fails if the committed copy is
> stale. Edit the tsv (and rerun the build), never this file.

## Coverage tiers

| Tier | Meaning | `parse()` today |
| --- | --- | --- |
| **`PARSES-NOW`** | The S3 parser builds a valid tree today (verified). | succeeds |
| **`NEEDS-S4-LAYOUT`** | Parses today, but faithful rendering needs *new* 2D layout (delimiter sizing to content, eval bars). | succeeds |
| **`NEEDS-PARSER-NODE`** | Needs a new `MathNode` / parser feature (named in the entry). | throws `MathSyntaxException` |
| **`NEEDS-FONT-STYLE`** | Missing feature is fundamentally a font-variant glyph set or emitter color. | throws `MathSyntaxException` |
| **`PARSER-BUG`** | `parse()` crashes with a *non*-`MathSyntaxException` (NPE/SOE/CCE). A robustness bug. | crashes |

> **Empirical frontier** over **166 entries** — the tier column is the source of truth in [`corpus.tsv`](../src/test/resources/com/lattex/parse/corpus.tsv), verified against `parse()` by `CorpusParseTest`: `PARSES-NOW` **164**, `NEEDS-PARSER-NODE` **2**, `PARSER-BUG` **0**. The parser fails cleanly (a named `MathSyntaxException`) on the entire not-yet frontier — no crashes.

Note on the split: `PARSES-NOW` vs `NEEDS-S4-LAYOUT` both parse today; the layout
tier is reserved for parsed trees whose faithful rendering needs a *new* S4
capability (stretchy delimiter sizing, eval bars) beyond the box-stacking that
`\frac`/`\sqrt`/scripts already establish. Constructs that don't parse yet are
tiered by their current frontier (see **Not-yet frontier** below), with S4 layout
following once the node exists.

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
| `\frac{\frac{1}{x}+\frac{1}{y}}{y-z}` | nested fraction (braced args) — recursion of core frac layout | `PARSES-NOW` |
| `\frac{\frac1x+\frac1y}{y-z}` | nested fraction, terse \\frac1x form — brace-optional single-token \\frac arguments | `PARSES-NOW` |
| `\sqrt{x^3+5}` | square root | `PARSES-NOW` |
| `\sqrt{\frac{a}{b}}` | root of a fraction (braced) — recursion of core radical layout | `PARSES-NOW` |
| `\sqrt[n]{1+x+x^2+\cdots+x^n}` | nth root, with \\cdots | `PARSES-NOW` |
| `x=a_0+\cfrac{1}{a_1+\cfrac{1}{a_2+\cfrac{1}{a_3+\cfrac{1}{a_4}}}}` | continued fraction — \\cfrac node (display-style fraction) | `PARSES-NOW` |
| `{}^3/_7` | diagonal fraction hack (empty-group scripts around a slash) | `PARSES-NOW` |

## Sums / integrals / big operators

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\sum_{i=1}^{10} t_i` | sum with lower/upper limits | `PARSES-NOW` |
| `\sum_{\substack{0<i<m\\0<j<n}}P(i,j)` | multi-line subscript via the \\substack single-column stack | `PARSES-NOW` |
| `\int y\,dx` | indefinite integral with thin space | `PARSES-NOW` |
| `\int_0^\infty e^{-x}\,dx` | definite integral over [0, infinity) | `PARSES-NOW` |
| `\int_b^c` | integral with bare bounds | `PARSES-NOW` |
| `\prod_{i=1}^n` | product with limits | `PARSES-NOW` |
| `\coprod` | coproduct big operator | `PARSES-NOW` |
| `\bigoplus` | direct-sum big operator | `PARSES-NOW` |
| `\bigotimes` | tensor-product big operator | `PARSES-NOW` |
| `\bigcap` | big intersection | `PARSES-NOW` |
| `\bigcup` | big union | `PARSES-NOW` |
| `\bigsqcup` | big square union | `PARSES-NOW` |
| `\bigvee` | big disjunction | `PARSES-NOW` |
| `\bigwedge` | big conjunction | `PARSES-NOW` |
| `\bigodot` | big circled dot | `PARSES-NOW` |
| `\biguplus` | big multiset union | `PARSES-NOW` |
| `\oint` | contour integral | `PARSES-NOW` |
| `\iint` | double integral | `PARSES-NOW` |
| `\iiint` | triple integral | `PARSES-NOW` |
| `\idotsint` | dotted multiple integral | `PARSES-NOW` |

## Delimiters — fixed

| LaTeX | Description | Tier |
| --- | --- | --- |
| `(a)` | round parentheses | `PARSES-NOW` |
| `[b]` | square brackets | `PARSES-NOW` |
| `\{c\}` | escaped curly braces | `PARSES-NOW` |
| `\|d\|` | vertical bars | `PARSES-NOW` |
| `\\|e\\|` | double-bar norm delimiters (\\\| maps to ‖, U+2016, like \\Vert) | `PARSES-NOW` |
| `\left\\| \frac{x}{y} \right\\|` | stretchy double-bar norm around tall content | `PARSES-NOW` |
| `\langle f\rangle` | angle brackets as ordinary symbols (outside \\left..\\right) | `PARSES-NOW` |
| `\lfloor g\rfloor` | floor delimiters as ordinary symbols | `PARSES-NOW` |
| `\lceil h\rceil` | ceiling delimiters as ordinary symbols | `PARSES-NOW` |
| `\ulcorner i\urcorner` | corner delimiters as ordinary symbols | `PARSES-NOW` |
| `/j\backslash` | slash and \\backslash symbol | `PARSES-NOW` |

## Delimiters — scaled (\left \right)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\left(\frac{x^2}{y^3}\right)` | scaled parens — S4 fencedBox stretches the delimiters to the fraction body | `PARSES-NOW` |
| `\left\{\frac{x^2}{y^3}\right\}` | scaled braces — delimiters stretch symmetrically about the axis to the content | `PARSES-NOW` |
| `\left.\frac{x^3}{3}\right\|_0^1` | eval bar (braced) — null-left + stretched bar verified (LayoutS4Test.evalBarStretchesOverTheBody) | `PARSES-NOW` |
| `\left.\frac{x^3}3\right\|_0^1` | eval bar, terse \\frac denominator — null-left + stretched bar verified (same layout path) | `PARSES-NOW` |
| `P\left(A=2\middle\|\frac{A^2}{B}>4\right)` | mid delimiter — \\middle node + same-span stretch (MiddleDelim; LayoutS4Test.middleDelimiterStretchesLikeTheOuterPair) | `PARSES-NOW` |
| `\binom{n}{r}` | binomial coefficient — \\binom node (rule-less paren-fenced stack) | `PARSES-NOW` |
| `\frac{n!}{r!(n-r)!}=\binom{n}{r}` | binomial identity — \\binom node (rule-less paren-fenced stack) | `PARSES-NOW` |

## Accents / decorations

| LaTeX | Description | Tier |
| --- | --- | --- |
| `a'` | prime (parses as an ordinary ' atom) | `PARSES-NOW` |
| `a''` | double prime (two ' atoms) | `PARSES-NOW` |
| `a''''` | quadruple prime (four ' atoms) | `PARSES-NOW` |
| `\hat{a}` | hat accent (glyph over base) | `PARSES-NOW` |
| `\bar{a}` | bar accent (macron over base) | `PARSES-NOW` |
| `\dot{a}` | dot accent (dot over base) | `PARSES-NOW` |
| `\ddot{a}` | double-dot accent (diaeresis over base) | `PARSES-NOW` |
| `\acute{a}` | acute accent (over base) | `PARSES-NOW` |
| `\grave{a}` | grave accent (over base) | `PARSES-NOW` |
| `\check{a}` | caron accent (over base) | `PARSES-NOW` |
| `\breve{a}` | breve accent (over base) | `PARSES-NOW` |
| `\tilde{a}` | tilde accent (over base) | `PARSES-NOW` |
| `\vec{a}` | vector arrow accent (over base) | `PARSES-NOW` |
| `\mathring{a}` | ring accent (over base) | `PARSES-NOW` |
| `\aa` | the letter a-with-ring (å) — needs symbol | `NEEDS-PARSER-NODE` |
| `\widehat{AAA}` | wide (stretchy) hat sized to the base | `PARSES-NOW` |
| `\widetilde{AAA}` | wide (stretchy) tilde sized to the base | `PARSES-NOW` |
| `\overline{a}` | overline (rule over base) | `PARSES-NOW` |
| `\underline{a}` | underline (rule under base) | `PARSES-NOW` |

## Text / spacing / color

| LaTeX | Description | Tier |
| --- | --- | --- |
| `a\,b\:c\;d` | thin / medium / thick fixed spaces (3mu / 4mu / 5mu) | `PARSES-NOW` |
| `a\thinspace b\medspace c\thickspace d` | named fixed spaces (aliases of \\, \\: \\;) | `PARSES-NOW` |
| `a\!b\negthinspace c\negmedspace d\negthickspace e` | negative fixed spaces (-3mu / -4mu / -5mu) | `PARSES-NOW` |
| `x\quad y\qquad z` | quad (18mu) and qquad (36mu) | `PARSES-NOW` |
| `a\enspace b\ c\>d~e` | enspace (.5em), control space, \\> (=\\:), and tie ~ | `PARSES-NOW` |
| `\phantom{M}x\hphantom{yy}z\vphantom{(}w` | phantom / hphantom / vphantom reserve space with no ink | `PARSES-NOW` |
| `\sqrt{x}+\mathstrut y` | mathstrut — a zero-width strut sizing the row | `PARSES-NOW` |
| `\text{50 apples}\times\text{100 apples}=\text{lots of apples}^2` | text runs — needs \\text node (upright text mode) | `PARSES-NOW` |
| `f(n)=\begin{cases}n/2&\text{if }n\text{ even}\\-(n+1)/2&\text{if }n\text{ odd}\end{cases}` | piecewise cases — cases environment (left brace over two left-aligned columns) | `PARSES-NOW` |
| `k={\color{red}x}-2` | colored subterm — \\color group-scoped switch + emitter fill | `PARSES-NOW` |

## Matrices / arrays

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\begin{matrix}a&b\\c&d\end{matrix}` | plain matrix — environment with &/\\\\ grid node | `PARSES-NOW` |
| `\begin{pmatrix}a&b\\c&d\end{pmatrix}` | parenthesized matrix | `PARSES-NOW` |
| `\begin{bmatrix}a&b\\c&d\end{bmatrix}` | bracketed matrix | `PARSES-NOW` |
| `\begin{Bmatrix}a&b\\c&d\end{Bmatrix}` | brace matrix | `PARSES-NOW` |
| `\begin{vmatrix}a&b\\c&d\end{vmatrix}` | determinant (single-bar) matrix | `PARSES-NOW` |
| `\begin{Vmatrix}a&b\\c&d\end{Vmatrix}` | double-bar (norm) matrix | `PARSES-NOW` |
| `\begin{smallmatrix}a&b\\c&d\end{smallmatrix}` | inline small matrix (script style) | `PARSES-NOW` |
| `A_{m,n}=\begin{pmatrix}a_{1,1}&\cdots&a_{1,n}\\\vdots&\ddots&\vdots\\a_{m,1}&\cdots&a_{m,n}\end{pmatrix}` | full matrix with \\cdots \\vdots \\ddots | `PARSES-NOW` |
| `\bordermatrix{&1&2\\1&a&b\\2&c&d}` | bordered matrix with row/col labels — needs \\bordermatrix node | `NEEDS-PARSER-NODE` |
| `\begin{array}{c\|c}1&2\\\hline 3&4\end{array}` | array with vertical and horizontal rules — array node + column spec + \\hline | `PARSES-NOW` |
| `\vdots` | vertical dots symbol | `PARSES-NOW` |
| `\ddots` | diagonal dots symbol | `PARSES-NOW` |

## Aligned equations (align / aligned / gather)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\begin{align}a&=b\\c&=d\end{align}` | two-equation align — alternating right/left column pair aligned on the relation | `PARSES-NOW` |
| `\begin{align*}E&=mc^2\end{align*}` | starred align (no equation numbers) — same rendering | `PARSES-NOW` |
| `\begin{aligned}f(x)&=x^2\\&=x\cdot x\end{aligned}` | derivation with an elided continuation LHS — empty first cell aligns on \\= | `PARSES-NOW` |
| `\begin{align}x&=1&y&=2\\a&=3&b&=4\end{align}` | several equations per line — two aligned relation columns with a wide inter-equation gap | `PARSES-NOW` |
| `\begin{align}\int_0^1 x\,dx&=\frac{1}{2}\\\sum_{k=1}^n k&=\frac{n(n+1)}{2}\end{align}` | display-style cells — integrals, fractions and big-op limits set full size | `PARSES-NOW` |
| `\begin{gather}a=b\\x+y=z\end{gather}` | gather — each row centred, no & alignment | `PARSES-NOW` |
| `\begin{split}a&=b+c\\&=d\end{split}` | split — a single equation broken over lines, aligned on the relation (reuses the align machinery) | `PARSES-NOW` |
| `\begin{multline}a+b+c+d\\+e+f+g\\+h+i\end{multline}` | multline — one long equation: first line flush-left, last flush-right, middle centred | `PARSES-NOW` |
| `\begin{multline*}\int_0^1 f\\=\frac{1}{2}\end{multline*}` | starred multline (no equation number) — same layout | `PARSES-NOW` |

## Named operators / misc

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\lim_{x\to\infty}\exp(-x)=0` | limit and exp — \\lim (under-limits in display) + roman \\exp | `PARSES-NOW` |
| `\cos(2\theta)=\cos^2\theta-\sin^2\theta` | cos and sin — roman named operators with beside scripts | `PARSES-NOW` |
| `\frac{d}{dx}(kg(x))` | derivative operator built from primitives | `PARSES-NOW` |
| `x\equiv a\pmod{b}` | modulo — \\pmod landed 2026-07-07 (wild-corpus trivial tier) | `PARSES-NOW` |
| `\boldsymbol{\beta}=(\beta_1,\beta_2,\ldots,\beta_n)` | bold beta vector — \\boldsymbol remaps to bold math-alphanumeric (incl. Greek) | `PARSES-NOW` |
| `\top` | top symbol | `PARSES-NOW` |
| `\bot` | bottom symbol | `PARSES-NOW` |

## Font / style alphabets

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\mathcal{ABCDEF}` | calligraphic/script alphabet — remaps to U+1D49C run (+ Letterlike exceptions) | `PARSES-NOW` |
| `\mathbb{ABCDEF}` | blackboard-bold alphabet — remaps to U+1D538 run (C/H/N/P/Q/R/Z via Letterlike) | `PARSES-NOW` |
| `\mathfrak{ABCDEF}` | fraktur alphabet — remaps to U+1D504 run (C/H/I/R/Z via Letterlike) | `PARSES-NOW` |
| `\mathrm{ABCDEF}` | upright roman — needs font-variant node | `PARSES-NOW` |
| `\mathsf{ABCDEF}` | sans-serif — remaps to U+1D5A0 run | `PARSES-NOW` |
| `\mathit{ABCDEF}` | math italic — remaps to U+1D434 run (h via Letterlike) | `PARSES-NOW` |
| `\mathbf{ABCDEF}` | bold — remaps to U+1D400 run | `PARSES-NOW` |
| `\boldsymbol{abcdef}` | bold symbols — remaps to bold math-alphanumeric run | `PARSES-NOW` |
| `\alpha,A,\beta,B,\gamma,\Gamma,\pi,\Pi,\phi,\mu,\Phi` | greek upper+lower mixed with latin | `PARSES-NOW` |
| `\varphi` | variant phi | `PARSES-NOW` |

## Tier-1a symbol breadth (relations / binops / arrows / negations)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `a\leq b\geq c\equiv d\sim e\simeq f\cong g` | core relations | `PARSES-NOW` |
| `x\prec y\succ z\ll w\gg v\subseteq u\supseteq t` | ordering + set relations | `PARSES-NOW` |
| `a\parallel b\perp c\models d\vdash e\propto f\approx g` | logical + geometric relations | `PARSES-NOW` |
| `a\pm b\mp c\times d\div e\cdot f\ast g\star h` | arithmetic binary operators | `PARSES-NOW` |
| `a\oplus b\ominus c\otimes d\oslash e\odot f` | circled binary operators | `PARSES-NOW` |
| `a\cap b\cup c\sqcap d\sqcup e\wedge f\vee g\setminus h` | set + lattice binary operators | `PARSES-NOW` |
| `a\to b\gets c\mapsto d\leftrightarrow e` | basic arrows | `PARSES-NOW` |
| `a\Leftarrow b\Rightarrow c\Leftrightarrow d\longrightarrow e` | double + long arrows | `PARSES-NOW` |
| `a\hookrightarrow b\rightleftharpoons c\leadsto d` | harpoon + hook + squiggle arrows | `PARSES-NOW` |
| `\infty\partial\nabla\hbar\ell\Re\Im\aleph\forall\exists` | miscellaneous ordinary symbols | `PARSES-NOW` |
| `a\nleq b\ngeq c\nmid d\notin e\neq f` | explicit precomposed negations | `PARSES-NOW` |
| `a\not= b,\ x\not\in S,\ A\not\subset B` | \\not prefix over precomposed relations | `PARSES-NOW` |
| `\varepsilon\vartheta\varpi\varrho\varsigma\varphi\digamma` | greek variant letters | `PARSES-NOW` |

## Lx macro / affordances (styling · effects · graphs · semantics)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\lx[style.color=#cc0000]{\frac{a+b}{c}}` | lx macro — inline color styling | `PARSES-NOW` |
| `\lx[style.scale=lg]{x^2}` | lx macro — scale bucket | `PARSES-NOW` |
| `\lx[style.mathstyle=display]{\sum_{i=1}^{n} i}` | lx macro — math-style override | `PARSES-NOW` |
| `\lx[fx.enter=boom]{E=mc^2}` | lx macro — enter effect | `PARSES-NOW` |
| `\lx[fx.hover=pulse,fx.click=glow,fx.duration=400ms]{x^2}` | lx macro — hover/click effects + duration | `PARSES-NOW` |
| `\lx[graph.domain=-3..3]{x^2-3}` | lx macro — graphable domain (graph.* option) | `PARSES-NOW` |
| `\lx[graph.open=multi]{\frac{1}{x}}` | lx macro — multi-open graph (graph.* option) | `PARSES-NOW` |
| `\lx[intent=ratio,concept=normalized_score]{\frac{a+b}{c}}` | lx macro — semantic intent/concept | `PARSES-NOW` |
| `\lx[a11y.label="normalized score"]{\frac{a}{b}}` | lx macro — accessibility label | `PARSES-NOW` |
| `\lx[data.source=calc_12]{x^2}` | lx macro — data attribute | `PARSES-NOW` |
| `\lx[style.color=#333,fx.enter=fade,intent=ratio,a11y.label="a ratio"]{\frac{a}{b}}` | lx macro — combined options | `PARSES-NOW` |

## Commutative diagrams (amscd \begin{CD})

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\begin{CD} A @>f>> B @VVV C \end{CD}` | CD — object row + a horizontal and a vertical arrow | `PARSES-NOW` |
| `\begin{CD} A @>f>> B \\ @VgVV @VVhV \\ C @>>k> D \end{CD}` | CD — the commutative square (both label sides) | `PARSES-NOW` |
| `\begin{CD} X @>>> Y \\ @\| @VVV \\ X @>>> Z \end{CD}` | CD — vertical double-bar connector (@\|) | `PARSES-NOW` |
| `\begin{CD} A @= B @>>> C \end{CD}` | CD — horizontal double-line connector (@=) | `PARSES-NOW` |
| `\begin{CD} A @<<< B @AAA C \end{CD}` | CD — leftward and upward arrows | `PARSES-NOW` |

## Boxed / framed

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\boxed{x+1}` | framed sub-formula — \\boxed rule rectangle (amsmath) | `PARSES-NOW` |
| `\boxed{x^* = f(x^*)}` | boxed fixed-point identity | `PARSES-NOW` |
| `\fbox{a+b}` | \\fbox accepted as the math-mode frame analogue | `PARSES-NOW` |
| `\boxed{\frac{a}{b}}` | boxed fraction — frame sizes to the taller body | `PARSES-NOW` |

## Extensible arrows (amsmath \x...)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `A \xlongequal{\text{def}} B` | labelled extensible double-equals — \\xlongequal (two in-alphabet rules) | `PARSES-NOW` |
| `\xlongequal[y]{x}` | \\xlongequal with above + below labels | `PARSES-NOW` |

## Physics braket (\bra \ket \braket)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\braket{\psi \| \hat{H} \| \psi}` | physics braket sugar — ⟨ψ\|Ĥ\|ψ⟩ over angle-bracket atoms | `PARSES-NOW` |
| `\bra{\phi}\ket{\psi}` | \\bra ⟨φ\| and \\ket \|ψ⟩ juxtaposed | `PARSES-NOW` |

## Stretchy over-parenthesis accent

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\overparen{AB}` | stretchy over-parenthesis accent (⏜ sized to the base) | `PARSES-NOW` |
| `\overparen{x+y+z+w}` | \\overparen stretches to a wide base | `PARSES-NOW` |

## Dimensioned glue (\hspace \mkern \kern)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `a\hspace{2em}b` | dimensioned horizontal glue — \\hspace{2em} (36mu) | `PARSES-NOW` |
| `x\mkern18mu y` | bare-form dimensioned glue — \\mkern18mu (1em) | `PARSES-NOW` |

## Prescript (left-attached scripts)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\prescript{14}{6}{\mathrm{C}}` | left-attached scripts — carbon-14 (pre-super/pre-sub via empty-base SupSub) | `PARSES-NOW` |
| `\prescript{a}{b}{T}` | tensor pre-indices | `PARSES-NOW` |

## Literal Unicode operators (classified via the reverse symbol table)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `a ≤ b` | literal ≤ (U+2264) classifies as Rel like \\le — relation spacing, not Ord | `PARSES-NOW` |
| `x × y → z` | literal × (Bin) and → (Rel) get \\times/\\rightarrow spacing from the reverse table | `PARSES-NOW` |
| `p ∈ A ∩ B` | literal ∈ (Rel) and ∩ (Bin) — pasted set notation gets correct atom spacing | `PARSES-NOW` |

## Multi-integral side limits (display keeps limits beside, like \int)

| LaTeX | Description | Tier |
| --- | --- | --- |
| `\oint_0^{2\pi} F\,d\theta` | contour integral keeps SIDE limits in display (U+222B..U+2233 block) | `PARSES-NOW` |
| `\idotsint_{\Omega} f` | \\idotsint (U+2A0C) also keeps side limits, not stacked | `PARSES-NOW` |

---

## Not-yet frontier

Every row that does **not** yet parse into a tree — i.e. everything outside `PARSES-NOW` — grouped by tier, generated straight from `corpus.tsv` so it can never list an already-shipped feature as still-needed.

### `NEEDS-PARSER-NODE` — needs a new `MathNode` / parser feature (named in the row) (2)

| LaTeX | Description |
| --- | --- |
| `\aa` | the letter a-with-ring (å) — needs symbol |
| `\bordermatrix{&1&2\\1&a&b\\2&c&d}` | bordered matrix with row/col labels — needs \\bordermatrix node |
