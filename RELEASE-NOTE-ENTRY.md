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


### fixpoint/xml-legality

#### U+FFFE and U+FFFF no longer reach MathML or SVG

The shared output-legality policy checked only C0 controls and surrogates, so the two BMP
noncharacters U+FFFE and U+FFFF passed straight into MathML text and the SVG `aria-label`,
and an XML 1.0 parser rejected the document. `OutputLegality` now expresses the whole XML
1.0 `Char` production once, as `isXmlChar(int)`, and every XML boundary uses it.

- **The two noncharacters are stripped**, exactly as an illegal C0 control already was, at
  every boundary that runs the shared policy: MathML, the SVG `aria-label`, and the
  styled-HTML a11y and `data-lx-*` attributes. A lone surrogate still fails loud.
- **Legal characters at the edges are unchanged**: U+D7FF, U+E000, U+FFFD, and astral code
  points up to U+10FFFF (as valid surrogate pairs) are preserved verbatim.
- **The interactive endpoint validator uses the same predicate.** Its `aria-label` check
  carried a private C0-only copy of the rule; it now refuses any label outside the `Char`
  production through `OutputLegality.isXmlLegal`.
