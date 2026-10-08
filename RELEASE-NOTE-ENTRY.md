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
