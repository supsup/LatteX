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
  decomposition in U+00A0-U+2DFF) are read in math: a run of one kind that holds a
  character the font lacks, but whose base it draws (103 of them), becomes one real
  script of the base letters (`xᵢⱼ` is `x_{ij}`; a script digit in such a run moves with
  it; at the start, after `{`, a `\left` delimiter or a cell break it attaches to an empty
  base). A run the font draws whole (`x²`) renders exactly as before. Where a box is
  still drawn, the SVG's aria-label ends with ` (missing glyph: U+XXXX)`, the CLI warns
  on stderr and exits 3, and `RenderOptions.withStrictGlyphs(true)` / `--strict-glyphs`
  make it a failure; plan a1866884's lenient default stands. Existing clean renders are
  unchanged.
