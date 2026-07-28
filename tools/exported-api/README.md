# Exported-surface gate

`ExportedSurfaceGate.java` compares two compiled LatteX revisions. CI compares
the current PR base with GitHub's checked-out merge candidate, so a branch that
lags main is measured as it would actually land rather than as an obsolete raw
head snapshot. The tool reads each revision's compiled `module-info.class`
`Module` attribute to learn which packages are exported. The surface itself
comes from class-file access flags: accessible public/protected types and their
declared public/protected fields, constructors, and methods.
Synthetic and bridge artifacts are excluded; compiler-declared enum members are
included because consumers can call them.

Run the causal controls locally:

```bash
tools/exported-api/self-test.sh
```

Compare two repository commits:

```bash
tools/exported-api/check.sh BASE_COMMIT TIP_COMMIT
```

The command reports additions and removals. Any addition must exactly match a
line that the current PR adds to `intentional-additions.txt`; there are no
patterns or wildcards. Existing ledger lines cannot authorize a later
reintroduction, and a new line without a matching surface addition fails as a
typo or pre-approval. Add an entry only when the PR deliberately expands the
exported contract, and explain that decision in the PR and release notes.
Entries remain after merge as an audit ledger.

With two arguments, `check.sh` reads the approval ledger from the extracted
`TIP_COMMIT`, never from the ambient checkout. A third argument is reserved for
an explicit fixture or test override.

The fixture allowlists are narrower test evidence, not production approvals.
In particular, `lattex-mathstyle-intentional.txt` approves the enum surface from
the original incident but omits its three accidentally widened TeXbook helpers.
