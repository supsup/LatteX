# RELEASE-NOTE-ENTRY.md

Entries queued for the next `RELEASE_NOTES.md` fold; newest last; folded on
release. Each landed branch appends its entry here instead of editing
`RELEASE_NOTES.md` in place, so the fold into the **Unreleased** section is a
single release-time act rather than a per-branch merge hazard. The entries
below are kept verbatim as their branches wrote them.

---

### fixpoint/version-0.14.0-snapshot

- main declares 0.14.0-SNAPSHOT after the 0.13.0 release cut (Charles, 2026-11-03): between releases main is a moving target and no consumer may pin it (plan 38cf48e4, ruling lattex 1089). No behaviour change.


### marlow/lattex364-fx-knobs-slice1-20261002

- Lightning and storm accept `fx.bolts` (ASCII integer `1..8`, omitted default `2`
  per source) and `fx.intensity` (ASCII integer percent `0..300`, omitted default
  `100`). Invalid authored values fail at parse time. Intensity `0` keeps the
  equation visible while disabling those effects; changing a zero-initialized
  wrapper to a positive intensity and calling `LatteXFx.play(element)` plays it.
  Knobs change wrapper attributes, never the inner SVG; omitting both keeps the
  existing output. The gallery adds tuned lightning and disabled/late-enabled
  storm specimens. Hosts that sanitize wrappers must explicitly allow the two
  bounded attributes; this does not establish Stafficy `/docs` adoption.
- Regenerated committed examples with the current renderer and runtime, including
  showcase and gallery specimen pages that had become stale on main. Their
  refreshed layout is independent of the lightning and storm knobs.


### confluence/lattex-text-accents-and-commands

- Commands a LaTeX author types for characters LatteX already draws are no longer
  refused (plan 2d5a59ad, found by the crew's census of the showcase pages, 2026-11-25).
  Inside `\text` and its family: the accents spelled as control words, double acute,
  caron, breve and cedilla (`\H{o}` is ő, `\v{C}` is Č, `\v\j` as `\v{\j}`), each
  composing to its precomposed character from the Unicode canonical compositions (57
  pairs; the fourteen carons STIX Two Math does not draw are refused, the message saying
  it is the font); and fifteen text symbols, `\ss \o \O \ae \AE \oe \OE \aa \AA \l
  \L \dag \ddag \copyright \pounds`, with the spaces after a control word skipped as
  TeX does (`Gro\ss e` is "Große"). A text word the user has defined as a macro is not
  decoded: it stays refused, as before. `\S` and `\P` are deliberately NOT added (they
  are among the commonest user macros; the raw characters draw).
- Two changes to the OLD five accents too: a bare `\j` is now a base, as `\i` was
  (`\^\j` is ĵ; refused on main), and a refusal now says which reason holds (the font
  lacks the precomposed character, naming it, or Unicode has none). **Behaviour change:**
  when the user has defined `\i` or `\j` as a macro, an accent on it inside `\text` is
  refused instead of silently taking the dotless letter (main took the built-in for `\i`).
  Spaces and an end of line after a text control word are skipped, as TeX skips them.
- In math: `\blacktriangleright` and `\blacktriangleleft` (U+25B8 and U+25C2, relations,
  the filled partners of `\triangleright` and `\triangleleft`). Checked at the source
  (Lattice): amssymb declares both `\mathrel`, with the same ink bounds in msam10.afm as
  their unfilled `\vartriangle` partners, and the bundled small glyphs match
  `\vartriangle`'s size; unicode-math instead maps them to the large U+25B6/U+25C0 as
  ordinary symbols, and LatteX follows amssymb. A raw ▸ or ◂ deliberately takes the same
  relation spacing (see below);
  and `\lgroup` and `\rgroup` (U+27EE and U+27EF) at their natural size. They cannot be
  stretched: `\left`, `\right` and the `\big` family refuse them, because the bundled
  font has no larger sizes of them.
- **Newly reserved built-in names:** `\lgroup`, `\rgroup`, `\blacktriangleright`,
  `\blacktriangleleft` (a user `\newcommand` of these is now refused as a built-in).
- **Spacing change on raw characters:** a raw U+25B8 or U+25C2 is now a relation and a
  raw U+27EE / U+27EF an opening / closing bracket, because a raw character takes its
  class from the symbol table (`a ▸ b` is spaced as a relation; in `⟮ - x ⟯` the minus is
  now unary). The command index grows from 692 to 696.

### confluence/lattex-unmapped-glyph-loud

- No character is drawn as the missing-glyph box in silence any more (plan 14579fe4,
  rulings stafficy/35638 and 35665; found by the crew's census of the showcase pages,
  2026-11-25, where x with subscript i, j, u or v drew boxes with exit 0). Unicode's
  superscript and subscript characters (163, every one-character `<super>`/`<sub>`
  decomposition in U+00A0-U+2DFF) are read in math: a cluster of them that holds a
  character the font lacks, but whose base it draws (103 of them), becomes real scripts
  of the base letters, its superscripts as one and its subscripts as one on the same base
  (`xᵢⱼ` is `x_{ij}`, `x²ᵢ` is `x^{2}_{i}`; a script digit in such a cluster moves with
  it). A character in such a cluster whose base the font lacks too (10 of them) stays as
  typed, so its box names what the author typed. A cluster the font draws whole (`x²`)
  renders exactly as before.
- **The rewrite only improves:** any input main renders, this renders. Where a cluster
  cannot attach as scripts (the nucleus already has a script of that kind, a typed script
  of that kind follows it, or it stands where an argument is read: `x_1ᵢ`, `xᵢ_j`,
  `A^{-1}ᵀ`, `x^ᵢ`, `\frac1ᵢ`), or has nothing before it, it is its own nucleus, an empty
  base carrying its scripts, as its raw character was one atom on main; where an argument
  is read it takes ONE character, as one raw character was one argument (`\frac²ᵢ` is
  still a fraction of the two); and a cluster directly followed by a typed `^`, `_` or
  prime (`\frac12ᵀ_j^3`, `xᵢ^2`) is its own nucleus and takes them, as the raw character
  did, decided by one token of lookahead before any script argument is parsed, so parse
  work stays linear. Pinned over a corpus: 47 templates times three cluster shapes times all 163
  characters, against a model of main; a wider replay of 18,048 contexts finds no input
  main renders that this refuses.
- **Where a box is still drawn**, the SVG's aria-label ends with
  ` (missing glyph: U+XXXX)` (the reserved substring is `LatteX.MISSING_GLYPH_MARK`, on
  the exported API); the CLI warns on stderr (`lattex: warning: no glyph for U+XXXX`),
  still writes the SVG and **still exits 0**, because hosts read a nonzero exit as "leave
  the source in place"; and `RenderOptions.withStrictGlyphs(true)` / `--strict-glyphs`
  make it a failure (exit 1). Plan a1866884's lenient default stands. Not covered:
  `renderFragment`, which returns markup without an aria-label (a host that needs the
  check uses `renderWithDiagnostics`), and the opt-in rendered error card, which draws its
  own message.
- **Existing clean renders are unchanged, with two exceptions by design:** author text
  that spells the mark (`\text{... (missing glyph: ...}` or an `a11y.label`) gets a
  zero-width space U+200B after "glyph", applied after the text is normalised so a
  stripped control character cannot rejoin it; and a label whose base is empty no longer
  starts with a space (three corpus labels: `{}^3/_7`, `\prescript{14}{6}`,
  `\prescript{a}{b}`; the two whole-corpus hashes are re-pinned with that measured as
  their sole cause; stripped of labels all 187 renders are byte-identical).
- **New exported API:** `RenderOptions.withStrictGlyphs(boolean)`,
  `RenderOptions.strictGlyphs()`, the 8-argument `RenderOptions` constructor (the
  7-argument one is kept), and `LatteX.MISSING_GLYPH_MARK`.

### confluence/lattex-raw-class-deterministic

- A pasted ⊥, △ or ▽ now has one class in every run (plan a9dde3b4; found by Fixpoint's and
  Lattice's readers, stafficy/35720 and 35723). Two spellings of different classes share
  each of these code points, and the class of the pasted character was whichever the symbol
  table's per-JVM iteration order met first, so 'a ⊥ b' gave two different SVGs from one
  jar (16 fresh JVMs on main: widths 72.36 and 94.5822, 8 each). The pasted characters now
  take Unicode's own math class (MathClass-15.txt: U+22A5 relation, U+25B3 and U+25BD
  binary), so a raw ⊥ spaces as `\perp`, a raw △ as `\bigtriangleup`, a raw ▽ as
  `\bigtriangledown`; every named command keeps its own class (`\bot`, `\triangle` and
  `\triangledown` stay ordinary). The rule stays the one on main: a pasted character takes
  the class of the command that names it, and Unicode's file is only the tie-breaker where
  several commands name one code point with different classes (so the small black
  triangles ▸ ◂, one command each, stay relations, though MathClass-15 says binary). The
  symbol table now refuses to build if a future pair of spellings disagrees with no sourced
  choice, or if a choice goes stale (its spellings agree, or none remains).

### confluence/lattex-aria-numbers-and-marks

- Accessibility labels read numbers as numbers (plan fd362dd3; repros from the crew's
  showcase pages, 2026-11-25; reviews by Lattice and Fixpoint). A run of digits of one
  numbering system, a digit-only group `{23}` included, with a decimal point or a braced
  group separator `{,}` between digits, or a point before them, is one word
  (`e^{\pi\sqrt{163}}` ends "the square root of 163"; `\frac{4{,}372}{E}` reads "4,372";
  `.5` reads ".5"). A bare or `\mathpunct` comma stays a separator, as it is drawn (f(1, 2)),
  and a thin space separates (`1\,000` reads "1 000"). TeX puts a script on the LAST digit,
  so `10^{-3}` reads "10 to the power of - 3" and `12^2` "12 squared"; a combining mark
  after a number sits over its last digit only, as `0.1\bar{6}` draws ("0.1 bar over 6").
- The font's own script characters read as the scripts they are, each read as its command
  form reads: x² is "x squared" as `x^{2}` is, x²₃ and x₃² both "x sub 3 to the power of 2"
  as `x^2_3` is, x⁻¹ "x to the power of - 1" as `x^{-1}` is. Only characters the font draws:
  one drawn as the missing-glyph box is read as itself. With no base in front (at the start,
  or after an operator: ²³⁵U) a run is read whole ("superscript 235 U"). The ordinals ª and º
  are letters, not scripts ("1 º").
- A combining mark after a base reads as its accent command (x with U+0304 is "bar over x",
  as `\bar{x}` is), and lands where it is drawn: after a superscript it is over the script
  (x, superscript two, bar reads "x to the power of bar over 2"). Any mix of marks and
  scripts is read in linear work.
- Labels only: no geometry changes (stripped of labels, every corpus render is
  byte-identical). Five of the 187 corpus labels change, all digit runs; the two
  whole-corpus hash pins are re-pinned with that measured as their sole cause, and the
  tracked example pages gallery, sum and the symbol index are regenerated. Because a label can grow or shrink, an input near the 2,000,000-character output cap can
  move across it (measured by Fixpoint: x followed by combining marks renders on main up to
  17,233 marks and here up to 16,252; a run of sevens renders here up to 10,252 and on main
  up to 10,200; within about 5 percent of the cap).
