# Exported-surface gate

`ExportedSurfaceGate.java` compares two compiled LatteX revisions. It reads each
revision's `module-info.java` only to learn which packages are exported. The
surface itself comes from class-file access flags: accessible public/protected
types and their declared public/protected fields, constructors, and methods.
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
line in `intentional-additions.txt`; there are no patterns or wildcards. Add an
entry only when the PR deliberately expands the exported contract, and explain
that decision in the PR and release notes. Entries remain as an audit ledger.

The fixture allowlists are narrower test evidence, not production approvals.
In particular, `lattex-mathstyle-intentional.txt` approves the enum surface from
the original incident but omits its three accidentally widened TeXbook helpers.
