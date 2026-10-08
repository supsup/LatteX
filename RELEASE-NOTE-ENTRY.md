# RELEASE-NOTE-ENTRY.md

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`.
Written to this file rather than edited in place, per the branch brief.
Plan `d2f3447c` (`lattex-nonletter-escape-residual`), residual half —
the math-mode counterpart to the already-landed text-mode work.

---

### LaTeX's escapable specials are now complete in math mode, and the whole escape space is censused

- **`\%`, `\&`, `\_` and `\$` render as their literal characters.** Standard
  LaTeX escapes seven specials to a literal glyph — `\# \$ \% \& \_ \{ \}` — and
  LatteX registered only three of them (`\#`, `\{`, `\}`). The other four reached
  the unknown-command throw. That was loud, never silent corruption, but it
  rejected ordinary, correct LaTeX that harvested sources carry constantly
  (`50\%`, `A \& B`, `x\_y`, `\$5`). Each is now one `Symbols` row: an ordinary
  (`ORD`) atom carrying the literal code point, on exactly the path `\#` already
  used. No new parser branch, no second registration — `CommandRegistry` derives
  each descriptor's grammar, index row, suggestion candidacy and macro
  reservation from that single table row.

- **The escaped forms stay distinct from the bare characters.** Bare `_` remains
  the subscript operator and bare `&` remains the matrix column separator; only
  the escaped forms are literal content. `\begin{matrix}a\&b\end{matrix}` is one
  cell containing an ampersand, not two cells — a difference invisible to a
  "renders without throwing" check, so it is asserted directly. `Symbols.CLASS_BY_CODEPOINT`
  still excludes ASCII, so four new ASCII rows cannot reclassify any pasted
  literal character.

- **The single-non-letter escape space is now censused end to end.** The lexer
  turns `\` plus one non-letter into a one-character control sequence, so that
  space is finite and enumerable. `NonLetterEscapeCensusTest` walks all 43
  printable-ASCII non-letters and pins the acceptance property: **each either
  renders with correct LaTeX semantics or fails loud — there is no silent third
  path.** Every unregistered escape must throw a classified unknown-command
  failure; every accepted one must carry a typed descriptor with an accepted
  example and macro reservation. The accepted set is pinned exactly (the seven
  specials, the spacing commands `\, \: \; \! \>` and the control space, the row
  separator `\\`, and `\|` — which is the double bar ‖, not a literal pipe), so a
  future addition or removal appears as a deliberate diff rather than a widening
  discovered by a corpus.

- **Two drift guards found while auditing the interactions.** The escapable
  specials are encoded in *two* independent tables — `Symbols.SYMBOLS` for math
  and `MathParser.TEXT_CONTROL_SYMBOLS` for `\text{…}` — with nothing structurally
  tying them together, so a special could become accepted at one surface and
  rejected at the other. That agreement is now pinned behaviorally at both
  surfaces from one list. Separately, four new *one-character* names in the
  fuzzy-suggestion pool could have started decorating unrelated failures with
  advice like "`\@` — did you mean `\%`?" (any two single characters are edit
  distance 1 apart). Measured: they do not; that is now pinned too.

- **On the evidence.** There is no TeX engine on the build machine, so none of
  this is a `pdflatex` differential and it is not claimed as one. The semantics
  asserted are the documented, standard, uncontroversial behavior of these four
  control symbols. What *is* machine-verified is the LatteX side: bundled STIX
  Two Math carries a real glyph for all four code points (`SymbolCoverageTest`
  fails the build and names any table code point without one, and never permits
  a `<text>` fallback), and the census pins the atom, the class, the rendered
  glyph paths and the accessible text. `examples/symbol-index.html` grew by
  exactly four cells (609 → 613 commands).

---

<!-- Appended by a later branch; the entry above is still unfolded on main and is kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`.
Written to this file rather than edited in place, per the branch brief.
Plan `18e34d82` (`lattex-unbraced-args`).

---

### Braces around a one-token argument are optional, as in TeX

- **`\mathrm u`, `x\pmod q`, `\text a`, `\phantom x`, `\tag1` now render.** TeX
  reads an undelimited argument as the next token: one character, one control
  sequence, or one `{...}` group, with spaces before it skipped. LatteX already
  did this for fractions, roots, accents and font variants (`\frac12`, `\sqrt2`,
  `\hat x`, `\mathbf 1`), but the text family (`\mathrm`, `\text`, `\textbf`, ...),
  `\pmod`, the `\phantom` family, `\label`/`\ref`/`\eqref`, `\tag`, `\substack`,
  `\operatorname`, the colour argument and `\bordermatrix` still demanded a brace.
  All of them now share one argument predicate in the parser, so no command can
  drift back to a brace requirement on its own. An unbraced argument is **one
  token**: `x\pmod q r` is `x\pmod{q}r`, and `\mathrm dx` is `\mathrm{d}x`.

- **Braced and unbraced spellings produce the same tree.** The new
  `SingleTokenArgumentTest` asserts equality of the parse trees, not just "renders
  without throwing", so a mis-scoped argument cannot pass. A registry census walks
  every argument-taking descriptor and requires a one-token probe for its handler:
  a future argument-taking command without one fails the build. Where an argument
  has no valid one-token value (a colour name, a whole `\bordermatrix`), the census
  requires the one token to reach that command's own domain check (`invalid color:
  "r"`) rather than a brace complaint. A text command's one token is still literal
  text: `\mathrm\alpha` fails exactly as `\mathrm{\alpha}` does.

- **A boundary is never an argument.** With no token (end of input, `}`, a matrix
  `&`, or a row separator `\\`/`\cr`) every reader now throws a typed error whose
  caret points at the spot where the argument is missing. This also closes a quiet
  bug: before, `\frac1&2` or `x^&` inside a matrix took the `&` as the argument and
  merged two cells into one.

- **On the evidence.** Measured over 138,172 unique display formulas from 722
  research preprints (no paper macros supplied), against main `066b90f` and this
  branch. Rendered: 101,822 to 103,109 (+1,287). Candidate LatteX gaps: 4,594 to
  3,201. The `\mathrm expects a '{...}' text argument` bucket went from 1,040 to 0
  and every `Expected '{' but found ...` bucket (385 formulas, every one a `\pmod`)
  went to 0. No formula that rendered before fails now; 140 formulas still fail,
  but on a later, different error (most often a paper-defined macro such as `\eps`
  or `\dd`). There is no TeX engine on the build machine, so equivalence to TeX is
  asserted against the TeXbook's stated rule, not a `pdflatex` differential.

- **Known limits.** A control-sequence argument is read as a nucleus, so a command
  that takes its own arguments brings them: `\hat\mathbf x` is `\hat{\mathbf x}`
  (TeX's reading for an accent or script, whose math field expands the macro). For
  a macro argument such as `\frac\sqrt2 3`, TeX would take `\sqrt` alone and fail;
  LatteX renders the evident reading instead. Environment arguments
  (`\begin{array}{cc}`'s column spec, `alignat`'s count) still require braces.

---

<!-- Appended by a later branch; the entries above are still unfolded on main and are kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`.
Written to this file rather than edited in place, per the branch brief.
Plan `a85ff403` (`lattex-math-italic-minus`).

---

### Math letters are math italic, and `-` is a minus sign

- **Variables now slant, the way TeX sets them.** A bare `x` used to draw the upright
  roman x: its paths were byte-identical to `\mathrm{x}`. TeX's default math alphabet
  is math italic, so every formula LatteX rendered read like typed text. Now a Latin
  letter draws its Mathematical Italic glyph (𝑥; `h` uses the Letterlike slot ℎ),
  lowercase Greek draws italic Greek (including the variant shapes `\epsilon`,
  `\vartheta`, `\phi`, `\varrho`, `\varpi`, `\varkappa`), and `\imath`/`\jmath` draw
  the italic dotless forms. Digits, uppercase Greek, `\partial` and `\nabla` stay
  upright, and so does everything that was never a math letter: `\sin` and the other
  function names, `\operatorname`, `\mathrm`, `\text`. `\mathit{x}` and a bare `x` are
  now the same glyph, as in TeX. Explicit alphabets (`\mathbf`, `\mathbb`, `\mathsf`,
  `\mathcal`, ...) are unchanged.

- **`-` in math is the minus sign.** `a-b` drew the 225-unit hyphen; it now draws
  U+2212, the 596-unit bar that lines up with `+`. That covers binary minus, unary
  minus (`-1`) and every negative exponent (`x^{-1}`). Text keeps its hyphen:
  `\text{a-b}` is unchanged.

- **Scripts follow the slanted letter.** Italic correction and the OpenType
  math-kern staircases are now read from the glyph that is actually drawn. Before,
  they were read from the upright glyph, which in STIX Two Math has zero italic
  correction, so a superscript on `f`, `V` or `P` sat where an upright letter would put
  it. The drawn nucleus, its italic correction, its kern staircase and its accent
  attachment now come from one resolver and cannot disagree.

- **`{\rm ...}` now does something.** The legacy switch used to be an identity remap,
  because bare letters were already upright. It now keeps Latin letters roman inside
  its group. As in TeX, it does not straighten Greek or the minus.

- **What you typed is still what is named.** Only the drawn glyph changed. The parse
  tree keeps the typed character, so the `thread` glyphmap (`78:` for x), `substitute`,
  the aria-label and `toMathML` all still say `x`. MathML now agrees with the picture:
  `-` emits `<mo>−</mo>`, and the letters LatteX keeps upright (uppercase Greek, `\rm`
  letters) emit `<mi mathvariant="normal">`. MathML Core already italicises a
  single-character `<mi>`, so plain letters need no attribute.

- **Every rendered formula changes, on purpose.** Any formula with a letter or a
  minus now renders different bytes and a slightly different width. The byte goldens
  (the whole-corpus ratchet hashes, the cap-postcondition SVGs, the batch golden and
  the stretchy-arrow ladder) were re-pinned in their own commit, after a control run
  showed that with the old alphabet forced back on, the tree reproduces every
  previous golden exactly. The tracked `examples/` pages and their BrewShot PNGs were
  regenerated. The wild-corpus ratchet counts successful renders, so it could not see
  either defect and did not move.

- **Known limits.** `\partial` keeps its own glyph. It is family 1 in TeX, but LatteX
  emits it to MathML as an operator (`<mo>∂</mo>`), so slanting it is a separate
  decision about both outputs. `\boldsymbol{x}` is still bold *upright* (TeX makes it bold italic), which
  predates this change. Most effect GIFs and the README's `showcase.gif` were not
  re-captured and still show upright letters.

---

<!-- Appended by a later branch; the entries above are still unfolded on main and are kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`, plan
`edbda088` (`fixpoint/lattex-ams-gaps`). Appended below the pending entry above
rather than replacing it, so neither is lost before the lead folds them in.

### The amsmath / amssymb names research papers use most now render

Measured first, on main `066b90f`, over **138,172 unique display formulas from
722 research preprints**: each name below was a refusal of standard LaTeX, with
its count. Every one is now a row in the existing tables (`Symbols` /
`CommandRegistry`), so its grammar, index cell, suggestion candidacy, macro
reservation and MathML come from that one row.

- **`\hbox{…}`** (957) takes `\text`'s contract whole: upright, spaces kept,
  `$…$` re-enters math.
- **`\begin{gathered}`** (897), plus the two missing inner forms
  **`alignedat`** (with alignat's mandatory `{n}`) and mathtools' **`multlined`**.
  Each lays out as its display twin; all take the optional `[t]`/`[b]`/`[c]`,
  which is read and ignored, as `aligned` and `split` already did.
- **`\lVert` `\rVert`** (118) and **`\lvert` `\rvert`** (55) are opening/closing
  atoms *and* delimiters, so `\left\lVert … \right\rVert` and `\bigl\lvert`
  work (23 more).
- **`\mathop{…}`** (57) is an Op atom with TeX's limit behaviour: limits under
  it in display style, beside it in text style, `\limits`/`\nolimits` after it
  respected. A one-symbol body (`\mathop{\boxtimes}_{i}`) is a large operator,
  centred on the axis (TeXbook App. G rule 13).
- **`\varprojlim`** (44), **`\varinjlim`** (14), and their siblings
  `\varliminf`/`\varlimsup`: "lim" with a stretchy arrow or rule under/over it,
  as an Op that takes limits. The arrows are STIX Two Math's own combining
  arrows below (U+20EE/U+20EF), stretched on their MATH constructions.
- Symbols: **`\Subset`** (50) / `\Supset`, **`\dashrightarrow`** (39) /
  `\dashleftarrow`, **`\Box`** (24), **`\restriction`** (10, = `\upharpoonright`),
  **`\Bbbk`** (7), and latexsym's binary **`\lhd` `\rhd` `\unlhd` `\unrhd`**.
- **`\lhook\joinrel\longrightarrow`** (35): the hook *piece* has no code point in
  Unicode or the bundled font, so `\lhook` is accepted only in the composites
  it exists for: `\lhook\joinrel\rightarrow` is exactly `\hookrightarrow`, and
  the long form is the extensible hooked arrow. Alone it fails loud and names
  `\hookrightarrow`. `\joinrel` itself is TeX's -3mu relation kern.
- **`\qedhere`** (8) is accepted and renders nothing; the proof's end mark
  belongs to the surrounding document, not the formula.
- **`\hspace` physical units**: `mm` (149), `cm` (24), and `in` `bp` `pc` `dd`
  `cc` `sp`, converted to pt by TeX's own ratios (TeXbook Ch.10) and then
  through LatteX's existing 10pt-em pt anchor, so they inherit exactly pt's
  approximation and nothing more.
- **The control space `\ `** inside `\mathrm`/`\text`-family arguments (34)
  decodes to a word space, like `\,` already did.

**Measured after**, same corpus, same script: rendered OK **101,822 → 103,902**
(73.7% → 75.2%); candidate LatteX gaps **4,594 → 2,108**. Every bucket above is
now zero. Some formulas that got past their old refusal now stop at a macro the
paper defines itself (31,756 → 32,162), which is the expected next failure, not
a regression.

**One determinism fix found on the way.** A pasted Unicode operator takes its
class from the command table, "first mapping wins"; but the table iterates in
`Map.copyOf`'s per-JVM order, so two rows sharing a code point with different
classes made a pasted glyph's spacing vary between runs. `\lVert` (Open) next to
`\Vert` (Ord) on U+2016 would have been a new instance, so class-tagged
spellings no longer claim the code point; a pasted ‖ stays Ord every run.
Pre-existing conflicts elsewhere in the table (for example `\perp` vs `\bot`
on U+22A5) are untouched and noted as a follow-up.

`examples/symbol-index.html` grew by exactly 24 cells (613 → 637 commands).

---

<!-- Appended by a later branch; the entries above are still unfolded on main and are kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`, plan
`c432f899` (`fixpoint/lattex-text-math`). Appended below the pending entries
above rather than replacing them.

### Math, accents and references inside `\text`, as LaTeX writes them

- **`\(…\)` re-enters math inside `\text`, exactly like `$…$`.** Theorem
  statements quoted into a formula write `\text{If \(K\) is categorical in some
  \(\lambda\ge H(K)\),}`; LatteX refused that with "commands are not expanded in
  text; wrap math in $...$". The `$…$` advice already worked; now the other
  spelling is the same toggle, scanned by the same code (escapes and nested
  `\text` arguments skipped identically), on every text-family command
  including `\hbox`. The math takes the surrounding style, as a `$` span does.
  An unpaired `\(` is a positioned error; a stray `\)` still fails loud.
- **Accents in names.** `\"` `\'` `` \` `` `\^` `\~` on one letter, bare or
  braced (`K\"ahler`, `Poincar\'e`, `\'{e}tale`, `\'{\i}`), become the
  precomposed character. A letter with no precomposed character the bundled
  font draws fails loud; a census renders every accent on every ASCII letter
  and requires a real glyph or a refusal (107 compose).
- **`\ref{key}` / `\eqref{key}` inside text** draw the same unresolved marker
  math mode already drew, `??` and `(??)`; the key is never shown. A tie `~` in
  text is now a space (it drew a literal tilde, e.g. `Proposition~\ref{…}`).
- **Still refused, deliberately:** a math accent inside `\mathrm`
  (`\mathrm{\acute et}`, 10 corpus formulas): `\mathrm` is lexed as a text
  run here, and whether its argument should become math is a separate decision.

**Measured**, same corpus and script as the entries above, against main
`85c1ed7`: rendered OK **105,234 → 105,315**; candidate LatteX gaps
**376 → 288**. The `\(`-in-text (53 + 5 in `\hbox`), `\eqref` (12 + 2),
`\ref` (5), `\"` (7 + 2) and `\'` (3) buckets are now zero. Seven of those
formulas now stop at a macro the paper defines itself (inside the nested
math), and one at a pre-existing, unrelated `\displaystyle`-in-`array` gap.

---

<!-- Appended by a later branch; the entries above are still unfolded on main and are kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`, plan
`b3f198f2` (`fixpoint/lattex-varlim-width`). Appended below the pending entries
above rather than replacing them.

### Stretchy arrows fit what they decorate; `\underleftarrow` & co are new

**The bug.** `\varprojlim` and `\varinjlim` (added in plan `edbda088`) drew their
arrow wider than "lim": the stretchy-accent path picked the smallest arrow AT
LEAST the base's width, and the font's arrows come in steps of about 447 font
units, so under the 1420-unit "lim" it drew the 1786-unit one, centred. The head
hung 183 units out on the left and the shaft ran under the next atom
(`\varprojlim M`, the paren of `\varprojlim_k\bigl(`). `\overrightarrow` and its
siblings had the same rule. Found on a BrewShot picture; the edbda088 tests checked
acceptance and code points, not geometry.

**Now.** The six arrow accents are FITTED to the base box, as TeX's are: the
widest rendering whose ink is not wider than the box. Where the font's assembly
can reach the box width (its parts may overlap anywhere between the minimum and
their connector lengths), the arrow is exactly that width; under "lim" no
assembly lands between 1306 and 1519 units, so the 1340-unit variant is drawn,
within 15 units of lim's own ink at each end. A base narrower than the smallest
arrow (`\underleftarrow{i}`) widens its box to the arrow, base centred, so a
neighbour never overlaps it. Hats, tildes and parentheses keep their previous
sizing.

**New commands.** `\underleftarrow`, `\underrightarrow`, `\underleftrightarrow`
(amsmath); the last stretches on the font's left-right arrow construction, since
U+034D has none in STIX Two Math. `examples/symbol-index.html` grew by 3 cells
(637 -> 640 commands), and its `\varprojlim`/`\varinjlim`/over-arrow cells redraw
with the fitted arrows.

**Pinned geometrically** (`ArrowAccentWidthTest`): arrow ink within the base box
and every later glyph starting after the operator, for `\varprojlim`/`\varinjlim`
in text and display style and all six arrow commands; exact width over a wide
base. The `\overrightarrow` byte-identity ladder in `StretchyAssemblyLinearTest`
was re-pinned after showing the old four hashes reproduce with the fit forced off.

---

<!-- Appended by a later branch; the entries above are still unfolded on main and are kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`, plan
`fc988bc4` (`fixpoint/lattex-small-gaps`). Appended below the pending entries
above rather than replacing them.

### `@{...}` column specs, arrows as `\big` delimiters, `\not` over anything, every negated relation

Measured on the research corpus (746 preprints, 137,880 unique display formulas):
main cc8186a renders 105,156 and has 288 real gaps; this branch renders 105,199 and
has 240. The four buckets below (19 + 14 + 1 + 12 refusals, plus 6 more from the
named negations and their bases) are gone; nothing that rendered before fails now.

**`@{...}` and `!{...}` in `array` column specs.** `@{math}` sets its material between
the columns on every row IN PLACE of the intercolumn space (LaTeX's semantics, so
`@{}` removes an edge or column gap and `@{\quad\longrightarrow\quad}` draws an arrow
column); `!{math}` keeps the space. The material is a per-boundary
`MathNode.ColumnSeparator` on `Matrix` (a new record component; the old 7-argument
constructor still builds a grid with none). MathML puts it inside the adjacent cell,
so the table keeps the author's column count. Two expressions, or a `|` and an
expression, at one boundary fail loud.

**Arrows as delimiters.** `\big`..`\Bigg` (with `l`/`r`/`m`) and `\left`/`\right` take
`\uparrow` `\downarrow` `\updownarrow` `\Uparrow` `\Downarrow` `\Updownarrow` (the
font's vertical constructions) and `\backslash` (the reverse solidus).

**`\not` overstrikes.** The precomposed character is still preferred, and more of them
are known (`\not\simeq` = U+2244, `\not\sqsubseteq`, `\not\preccurlyeq`, the
turnstiles, `\not\lesssim` …). Any other target, which used to be refused, is drawn
struck: the new `MathNode.Negated` places U+0338 COMBINING LONG SOLIDUS OVERLAY (the
stroke STIX Two Math draws inside ≠) centred on the target's ink, keeping the
target's width and class (`a\not\perp b` spaces as a relation; `\not D` is a slashed
D). MathML appends U+0338 to the token (`<mo>⊥̸</mo>`); a non-atom target takes
`<menclose notation="updiagonalstrike">`. `\not` with nothing after it still fails,
now naming `\not`.

**Negated relations.** 32 new names with their precomposed code points (`\nsimeq`
`\napprox` `\nequiv` `\nasymp` `\lneq` `\gneq` `\lneqq` `\gneqq` `\lnsim` `\gnsim`
`\lnapprox` `\gnapprox` `\nlesssim` `\ngtrsim` `\nlessgtr` `\ngtrless` `\npreceq`
`\nsucceq` `\precneqq` `\succneqq` `\precnsim` `\succnsim` `\precnapprox`
`\succnapprox` `\nsubset` `\nsupset` `\subsetneqq` `\supsetneqq` `\nsqsubseteq`
`\nsqsupseteq` `\nni` `\notni`), six overstruck ones Unicode does not precompose
(`\nleqslant` `\ngeqslant` `\nleqq` `\ngeqq` `\nsubseteqq` `\nsupseteqq`), and the
bases `\preccurlyeq` `\succcurlyeq` `\VDash`, all relations. Registering them also
fixes the PASTED glyph: a pasted ≄ (and ≉ ≢ ≭ ⊄ ⊅ ∌ ⋠ …) used to space as an
ordinary symbol (`N≄0` tight) and now gets relation spacing like `\neq`. Not added:
`\varsubsetneq` & co and `\lvertneqq`/`\gvertneqq` (Unicode has them only as a
variation sequence, which LatteX does not map) and `\nshortmid`/`\nshortparallel`.
`examples/symbol-index.html` grew by 41 cells (640 -> 681 commands).

**Pinned.** `SmallGapsTest`: corpus formulas verbatim, a census of every negated name
against its code point's Unicode NAME, a STIX glyph and REL class (command and
pasted), relation spacing on both sides, and the loud refusals. `SmallGapsLayoutTest`:
the slash's ink centred on and inside the struck relation's box and matching ≠'s
slash; each `\big` level's arrow span centred on the axis; `@` material between its
columns on each row with no `\arraycolsep` beside it. Nine mutants (slash dropped,
slash uncentred, overstrike parse path dropped, `@` dropped, `@` keeping its padding,
material on one row only, `\downarrow` delimiter dropped, `\not\simeq` mapping dropped,
`\napprox` unregistered) each turn the suite red. `SymbolCoverageTest`'s
"`\not\alpha` must throw" pin is inverted: the overstrike is real ink.

<!-- Appended by a later branch; the entries above are still unfolded on main and are kept, not replaced. -->

Proposed entry for the **Unreleased** section of `RELEASE_NOTES.md`, plan
`636d214f` (`fixpoint/lattex-standard-gaps`). Appended below the pending entries
above rather than replacing them.

### The next standard-LaTeX gaps: `\genfrac`, `\multicolumn`, row `\tag`s, `\vert`, `\sf`, `\fint`

Measured on the research corpus (746 preprints, 137,880 display formulas): main
f396b9d refused 232 formulas for reasons that were LatteX's, not the paper's. This
branch: 178, with 63 more formulas rendering (105,199 -> 105,262) and none newly
failing.

- **`\genfrac{l}{r}{thickness}{style}{num}{den}`** (amsmath). Built from the same
  nodes as its instances, so `\genfrac(){0pt}{}{n}{k}` IS `\binom{n}{k}` and
  `\genfrac{}{}{}{0}{a}{b}` IS `\dfrac{a}{b}` (pinned tree-equal and SVG-equal). A
  non-zero explicit thickness fails loud rather than drawing the default bar.
- **`\multicolumn{n}{spec}{body}`** in `array`, the matrices and `cases`. The span is
  aligned across its columns by its own spec, a wider span widens the LAST spanned
  column (TeX's rule), the rules inside the span are not drawn on its row and its own
  trailing `|` is. `Matrix` carries the spans as metadata; a grid without one is
  unchanged, down to one full-height rect per vertical rule. A span also covers
  `@{...}`/`!{...}` material, as TeX's column templates do: on the span's row the
  material inside it and at its right edge is not drawn (and the leading material
  too when it starts in column one), the material to its left still is, and every
  boundary keeps its width on every row.
- **`\tag` per row** in `align`/`gather`/`alignat` (starred too), drawn on its row's
  baseline in a right-aligned column after the grid, and as `<mlabeledtr>` in
  MathML. Anywhere else (`gathered`, `equation`, a group, after `\displaystyle`) it
  tags the equation, as amsmath does.
- **`\vert`** is the ordinary bar symbol, not only a delimiter; **`\sf`** and
  **`\tt`** complete the TeX 2.09 font declarations; esint's **`\fint`** (U+2A0F),
  **`\sqint`** (U+2A16), **`\ointclockwise`** and **`\ointctrclockwise`** are
  integrals with side limits; **`\text` nests in `\mathrm`** and in other text
  commands (`\mathrm{non\text{-}tail}`).
- **Two parser bugs from the investigation buckets.** (1) A `\displaystyle`/
  `\textstyle` (or `\color`, or legacy font) switch ran past `\end{…}` and `\right`,
  so `\displaystyle` in a pmatrix's last cell gave "\end without a matching \begin"
  (7 corpus formulas) and one inside `\left(..\right)` gave "\right without matching
  \left" (3). It now ends at `\end`, `\right` and `\middle` too. (2) After `\\` in an
  amsmath environment, a SPACED `[` was read as the spacing option, so
  `\\ [B]_2` failed "no nucleus" (4). amsmath reads that option without skipping
  spaces; now so does LatteX (LaTeX's own `array`/`eqnarray` still skip them).

`examples/symbol-index.html` grew from 681 to 689 commands. Newly reserved built-in
names (a preset macro of the same name is now refused, as for every addition):
`\genfrac \multicolumn \sf \tt \fint \sqint \ointclockwise \ointctrclockwise`.

**Review fixes (lattex/1015).** A `\tag` after a trailing `\\` in `align`/`gather`
(`a\\ \tag{1}`) threw a raw `IllegalArgumentException` out of `render` and
`toMathML`: the empty row was dropped as a phantom and its tag pointed past the
grid. It is now a real numbered empty last row, as in amsmath, drawn below the row
above with its tag on its own baseline (a row's height now includes its tag's), and
any grid invariant the parser fails to uphold is a typed `MathSyntaxException`.

**Limits.** `\genfrac`'s style is empty or `0`-`3`; `4` and above fail loud. Its
thickness unit must be lowercase (`0PT` is refused; TeX accepts it). The `\\ [`
space-skip decision is made by the environment's own name, so an `array` nested in
`pmatrix` skips the space as `array` does and does not inherit amsmath's rule.

### Braced atoms are ordinary; `\tag` labels are text; `\mathllap` & co; the superscript prime

Found by typesetting the math showcase (plan `720cd87e`), not by a test.

- **A braced subformula is an Ord atom** (TeXbook ch. 17). A one-item group used to
  collapse to its item and keep that item's class, so `152{,}320` was exactly as wide
  as `152,320` and `a{+}b` as `a+b`, with nothing refused: a corpus scan cannot see it.
  Now `{,}` `{+}` `{=}` `{;}` `{\le}` lay out as their `\mathord` spelling, and a braced
  `\sin`, `\left(..\right)` or `{n\choose k}` is Ord too. Command and script arguments
  are not subformulas and keep their class (`\overset{a}{=}` is still a relation,
  `\mathbin{+}` still binary). A braced Ord atom stays a bare atom, so `{x}^2` draws as
  `x^2`; a braced `\sum` keeps its display size and takes its scripts beside it. The
  showcase's quotient-map card (`X/{\sim}`) narrows 324.18 -> 301.96 as the tildes lose
  their relation glue; it is the one tracked example that moves, regenerated.
- **`\tag` labels are text**, as amsmath sets them: `\tag{a}` is upright, `\tag{C-pair}`
  keeps its hyphen, `\tag{$\dagger$}` no longer draws its dollar signs, and
  `\tag{\(*\)}` renders instead of failing. Row tags likewise. A label text mode cannot
  read (`\tag{\ref{x}.1}`, `\tag{\ast}`) falls back to its old math reading. Digit labels
  draw the same glyphs either way, so `\tag{1}` output is unchanged. In MathML the label
  is an `<mtext>`.
- **`\mathllap`, `\mathrlap`, `\mathclap`** (mathtools): zero width, content in math at
  the current style (or the optional `[\scriptstyle]`-family argument), keeping height
  and depth, spacing as Ord. MathML: `<mpadded width="0">`.
- **Primes.** The construction already matched TeX and is now pinned (`f'` draws
  exactly `f^{\prime}`, `u''` exactly `u^{\prime\prime}`, `x'^2` exactly
  `x^{\prime 2}`). The glyph did not: STIX Two Math's U+2032 is a text prime drawn
  raised (ink 0.399 to 0.703 em), and superscripting it put a small prime well above the
  letter. A prime in a script style now draws the font's `ssty` alternate
  (`minute.ssty`, ink 0.085 to 0.527 em), the superscript prime a TeX engine on this font
  uses. Only the prime family takes `ssty`; other glyphs' script alternates are a
  separate decision. The two whole-corpus byte pins move for this alone (three corpus
  rows have a prime) and reproduce exactly with it reverted.

`examples/symbol-index.html` grew from 689 to 692 commands. Newly reserved built-in
names: `\mathllap \mathrlap \mathclap`.

**Preprint corpus** (137,880 display formulas from 746 preprints): rendered 105,262 ->
105,270; candidate LatteX gaps 178 -> 170 (the four `\tag{\(..\)}` formulas and the four
overlap formulas); newly failing 0, compared formula by formula.

**Limits.** A tag label read as text expands no macros; one that text mode refuses is
re-read as math with the preset macros only, not ones defined inline earlier in the
formula. `\tag*` is still unsupported.
