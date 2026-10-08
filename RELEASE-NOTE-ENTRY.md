# RELEASE-NOTE-ENTRY.md

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
