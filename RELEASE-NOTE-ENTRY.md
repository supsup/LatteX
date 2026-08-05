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
