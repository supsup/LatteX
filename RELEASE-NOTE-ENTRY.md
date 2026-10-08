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
