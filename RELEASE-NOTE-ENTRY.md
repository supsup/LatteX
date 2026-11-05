# RELEASE-NOTE-ENTRY.md

Entries queued for the next `RELEASE_NOTES.md` fold; newest last; folded on
release. Each landed branch appends its entry here instead of editing
`RELEASE_NOTES.md` in place, so the fold into the **Unreleased** section is a
single release-time act rather than a per-branch merge hazard. The entries
below are kept verbatim as their branches wrote them.

---

### fixpoint/version-0.14.0-snapshot

- main declares 0.14.0-SNAPSHOT after the 0.13.0 release cut (Charles, 2026-11-03): between releases main is a moving target and no consumer may pin it (plan 38cf48e4, ruling lattex 1089). No behaviour change.
