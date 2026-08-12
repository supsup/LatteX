# The LatteX Dogfood Flow

*Running LatteX in a box — and the awkward fact that a version number is not proof.*

---

## Cold open

A docs author writes `50\%` in a page. Ordinary prose maths — fifty percent.

They get back:

```
lattex: error: invalid LaTeX:
  50\% \& 3\_4
    ^
Unknown command: \%
```

They stare at it. They check the LaTeX spec. `\%` is correct — it has been correct since
1985. They try `\percent`. They try escaping the escape. They eventually assume LatteX
doesn't support it and rewrite the sentence.

Nothing was wrong with their input. The renderer was eight days old, and `\%` had been
supported for six of them. Nothing on that error message said so.

This document is about closing the gap between *"my expression is wrong"* and *"my
renderer is old"* — because from where the author stands, those two look identical.

---

## The thing you must understand first

LatteX images tell you a **version string** and nothing else:

```
Implementation-Version: 0.13.0-SNAPSHOT
```

That is weaker than it looks, and LatteX has the scar to prove it. On 2026-08-11, Stafficy
`/docs` was serving math from `lattex-0.11.1.jar`. That version **does not exist**. LatteX's
own 0.12.0 notes say so outright: *"0.11.1 is not a LatteX release and must not be pinned."*
There is no such tag in the repository — only two retired branch tips. A jar was
circulating, declaring a version, and the version named nothing you could check out.

So the rule this whole document is built on:

> **A version string tells you what someone declared. A tag is the only thing that links an
> image to a commit — and it is a label applied by discipline, not evidence Docker enforces.**
> Pin tagged releases. Treat a bare version on an untagged build as a label, not evidence, and
> treat the tag itself as a claim that is only as good as the fence that produced it (Act I).

(If you also work on Sirentide: its jars stamp `Sirentide-Source-Revision`, a full 40-hex
commit, directly into the manifest. LatteX does not. That is a real asymmetry, and it is
why LatteX's staleness check below has to lean on tags and mtimes rather than simply asking
the artifact.)

---

## Act I — The Dockerfile, setting by setting

```sh
git fetch -q origin main || { echo 'refusing: fetch failed, origin/main may be stale'; exit 1; }
test -z "$(git status --porcelain)" || { echo 'refusing: working tree is dirty'; exit 1; }
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" \
  || { echo 'refusing: HEAD is not origin/main'; exit 1; }

SHA=$(git rev-parse --short HEAD)           # every example below reuses this
docker build -t "lattex:main-$SHA" .
```

**Why four lines of fence before one line of build.** `docker build .` sends *your working
tree* as the context, so a name derived from `origin/main` is a claim about a commit that may
be nothing like what you just built. Dirty tree, wrong branch, unfetched remote — each one
produces an image confidently labelled with a commit it does not contain. The fence makes the
tag's claim true at the moment it is made, and `--short HEAD` names the thing actually built
rather than the thing you hoped was checked out.

**The `||` on the fetch is the load-bearing one, and an earlier draft of this section left it
off.** Without it a failed fetch is just a message on the way past: `origin/main` keeps whatever
value it had from your last successful fetch, `HEAD` still equals that stale ref, both remaining
tests pass, and the build is blessed. The check that exists to catch an unfetched remote is
precisely the one that fails open when the remote is unfetched. A guard whose first step can
fail silently is not a guard.

No build arguments. Nothing to forget. Prerequisite: **Docker, and nothing else** — no host
JDK, no Gradle, no Chrome. Roughly a few minutes cold, ~227 MB final.

**Stage 1 — `eclipse-temurin:25-jdk AS build`**

| line | effect | why you care |
|---|---|---|
| `COPY . .` | copies the *filtered* context | `.dockerignore` drops `.git`, `build`, `**/build`, `src/test`, `docs`, `examples`, `libs`, `*.md`, `*.html`, `*.png`, `*.gif` |
| `./gradlew --no-daemon clean jar` | builds from source | `clean` guarantees exactly one jar for the `find` below |
| `find build/libs … ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit` | selects the jar | pattern-matched, not hard-coded |

The `build` exclusion in `.dockerignore` is quietly one of the most valuable lines in the
repo. A host `build/libs` accumulates jars — I have watched a stale `lattex-0.11.0.jar`
sit beside a current one and win a glob by alphabetical luck. **The image cannot inherit
that mistake**: your host `build/` never enters the context, and the build starts with
`clean`. What ships is compiled from source, every time.

The bundled STIX font under `src/main/resources` **is** included — that's why the image
needs no fonts at runtime and renders identically anywhere.

**Stage 2 — the worker**

`docker/LatteXFolderWorker.java` compiles against the fresh jar into a separate
`lattex-worker.jar`, main class `com.lattex.cli.LatteXFolderWorker`. Container-only; it
never ships in the library.

**Stage 3 — `eclipse-temurin:25-jre-alpine`**

| setting | value | reason |
|---|---|---|
| user | `lattex` `10001:10001`, system, no home (`-S -D -H`) | never root |
| app tree | `/opt/lattex` | immutable artifacts |
| jars | `chmod 0444` | read-only — the process cannot rewrite its own code |
| entrypoint | `chmod 0555` | read+execute, not writable |
| data tree | `/lattex/input/{processing,finished,failed}`, `/lattex/output` | pre-created, `chown`ed, so a bare bind mount works |
| `USER 10001:10001` | before entrypoint | privileges dropped ahead of your code |

The JRE-alpine runtime carries no JDK, no Gradle, no source. Three files cross the stage
boundary: two jars and a shell script.

**The entrypoint's one sharp edge.** It dispatches on the first argument: `watch` starts
the folder worker, `cli` forces CLI mode, and anything else passes straight through to the
jar (the legacy shape). So if your *expression* is literally `watch` or `cli`, you must say
`cli` first:

```sh
docker run --rm lattex:main-$SHA cli watch > watch-expression.svg
```

(Every one-shot in Acts I and II names `lattex:main-$SHA`, the image you just built. `dogfood`
does not exist yet at this point in the story — it is promoted in Act III, *after* verification,
which is the whole ordering this document argues for. Act IV onward uses `dogfood`, by then
correctly.)

---

## Act II — Verify in three circles

**Circle 1 — does it render?**

```sh
docker run --rm lattex:main-$SHA cli '\frac{a}{b}' | head -1
```

**Circle 2 — does it report the version this source declares?**

```sh
docker run --rm lattex:main-$SHA cli --version
sed -n 's/^version = "\(.*\)"$/source declares: \1/p' build.gradle.kts
```

Both sides *printed*, for you to compare. Nothing here compares them — an earlier draft said
"both sides, compared", which credited these two commands with a check neither performs. If you
want the comparison made rather than displayed, that is what `assert_version` in
`docker/smoke-test.sh` does, and Circle 3 runs it.

**Circle 3 — the full contract:**

```sh
sh docker/smoke-test.sh lattex:main-$SHA     # -> "lattex Docker smoke: PASS"
```

Modes, mounts, stdin/argv/file input, batch NUL framing, reserved-word escaping, atomic
claims, restart recovery, collisions, races.

> 📎 **Why this check reports both sides — it used to report neither.** The version check in
> `docker/smoke-test.sh` once hard-coded `grep -q '^lattex 0\.11\.0$'`. When main moved to
> 0.12.0, that made a **correct** image fail its own smoke test, and fail *silently*:
> `grep -q` prints nothing, `set -e` aborts, so you got exit 1 naming neither the failing
> check nor the mismatch. The repair landed (plan `4a8bcc34`): `assert_version` derives the
> expected version from `build.gradle.kts` and prints expected-vs-actual with the source of
> each, so a mismatch now names itself. Nothing to work around here anymore — this is
> recorded because the failure mode is worth recognizing, not because it is still waiting.
> A pin that must be hand-edited every release is a pin that is wrong between releases.

---

## Act III — Three tags, three contracts

| tag | mutability | meaning |
|---|---|---|
| `lattex:local` | scratch | throwaway working build |
| `lattex:main-<sha>` | **by convention, not re-pointed** | the commit this image was built from |
| `lattex:dogfood` | **moving** | "the one I actually use today" |

```sh
docker tag "lattex:main-$SHA" lattex:dogfood
```

Build → verify → promote. In that order, always.

> ⚠️ **A `main-<sha>` tag is a convention you keep, not a property Docker enforces.** An earlier
> version of this table called it *immutable*, and that was wrong in a way worth spelling out,
> because the whole staleness check below rests on it. Docker tags are mutable pointers: nothing
> stops `docker tag <anything> lattex:main-abc1234`. Demonstrated, not assumed — pointing
> `lattex:main-deadbee` at `alpine`, then re-pointing the same tag at a LatteX image, both
> succeeded silently, and the second image now answered to a `main-<sha>` name for a commit it
> had no relationship to. Short SHAs can also collide as history grows.
>
> So the tag is a *label you are trusted to apply honestly*, which is exactly why the build fence
> in Act I matters. The genuinely immutable identity is the image ID
> (`docker inspect -f '{{.Id}}' lattex:dogfood`) — that names bytes and cannot be re-pointed.
> It just cannot tell you which commit produced them, because LatteX stamps no source revision
> into the artifact. That gap is the real subject of this section: the tag is the only thing
> *connecting bytes to a commit*, and it holds only as well as your discipline does.

---

## Act IV — "Is mine stale?"

```sh
docker run --rm lattex:dogfood cli --version
sed -n 's/^version = "\(.*\)"$/declared in source: \1/p' build.gradle.kts

tags=$(docker inspect -f '{{join .RepoTags "\n"}}' lattex:dogfood | sed -n 's/^lattex:main-//p')
n=$(printf '%s' "$tags" | grep -c . || true)

if [ "$n" -eq 0 ]; then
  echo "provenance unknown: no lattex:main-<sha> tag — rebuild"
elif [ "$n" -gt 1 ]; then
  echo "provenance AMBIGUOUS: this image answers to $n main-<sha> tags — refusing to guess:"
  printf '  %s\n' $tags
elif ! git cat-file -e "${tags}^{commit}" 2>/dev/null; then
  echo "provenance unverifiable: '$tags' is not a commit in this repository"
elif ! git fetch -q origin main; then
  echo "provenance uncomparable: fetch failed, cannot say what 'behind main' means right now"
elif ! git merge-base --is-ancestor "$tags" origin/main; then
  echo "provenance DIVERGENT: $tags is a real commit but not an ancestor of origin/main —"
  echo "  this image was built from a branch, not from main. 'behind' does not apply."
else
  echo "built from $tags, $(git rev-list --count "$tags"..origin/main) commit(s) behind main"
fi
```

**Five refusals, and none of them used to be here.** The first version of this block ended with
`head -1`, which turns "this image has several `main-<sha>` tags" into a silent arbitrary pick —
and an arbitrary pick is indistinguishable from a correct one in the output. Verified against a
real image carrying two such tags: `head -1` chose one and said nothing about the other.

Each later arm closes a *different* way of producing a confident sentence from a bad premise, and
the divergence arm is the subtlest: `git rev-list --count X..origin/main` returns a perfectly
plausible integer for a commit that lives on some other branch entirely, so an image built from a
feature branch reports as merely "3 commits behind main" rather than as not-on-main-at-all. The
ancestry check is what makes "behind" mean what the word implies. The fetch arm is here for the
same reason the build fence has one: a comparison against a stale `origin/main` is not a
comparison, and silence is the wrong way to say so.

Never read `RepoTags` positionally (`{{index .RepoTags 1}}`). The order is not guaranteed;
it works until the day it silently doesn't.

### Roleplay: the eight-day-old container

> **Author:** `50\%` is broken. Is this a LatteX bug?
>
> **You:** What does `cli --version` say?
>
> **Container:** `lattex 0.11.0`
>
> **Source:** `0.13.0-SNAPSHOT`
>
> **You:** Your maths is fine. My image is from July 28 — 37 commits back. Non-letter
> escapes landed after it.

Measured, not hypothetical: `lattex:dogfood` on this host was built 2026-07-28 while main
sat at 2026-08-05. Everything *looked* healthy — it rendered fractions, integrals, matrices
all day. It refused exactly the things it had never heard of, in a voice indistinguishable
from a genuine syntax error.

**A stale renderer does not fail loudly. It fails narrowly, and blames your input.**

---

## Act V — The dogfood watcher

```sh
DOGFOOD_ROOT="$HOME/projects/dogfood/lattex"   # every Act V command reuses this
mkdir -p "$DOGFOOD_ROOT/Input" "$DOGFOOD_ROOT/Output"

docker run -d --name lattex-dogfood --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  -v "$DOGFOOD_ROOT/Input:/lattex/input" \
  -v "$DOGFOOD_ROOT/Output:/lattex/output" \
  lattex:dogfood watch

docker logs -f lattex-dogfood
```

> 📎 **`$DOGFOOD_ROOT` is not tidiness — it is the difference between a job running and a job
> vanishing.** Earlier drafts mounted an absolute path here and then wrote jobs to a *relative*
> `Input/`, so a reader following along from the repository root created `./Input/q.tex`, which
> the watcher never sees. Nothing errors: the file sits in a directory nobody is watching, and
> the failure looks exactly like "the watcher ignored my job". Two mounted absolute paths and
> two relative ones spelled the same way is a trap the document laid itself.
>
> Both directories are also spelled out rather than written `{Input,Output}`, because brace
> expansion is a *bash* feature and this is an `sh` block. Measured both ways rather than
> assumed: macOS `/bin/sh` (bash in POSIX mode) expands it and creates the two directories,
> while busybox `ash` on Linux creates one literal directory named `{Input,Output}` — so the
> watcher mounts paths that do not exist, and the reader who wrote the doc on a Mac never sees
> it. A portability bug that only appears off the author's machine is the kind worth spending
> two extra words on.

Jobs are **visible, regular, direct-child `*.tex` files**. Not nested, not hidden, not
symlinks, not other extensions.

**Write hidden, then rename.** Always:

```sh
printf '%s\n' '\int_0^1 x^2\,dx' > "$DOGFOOD_ROOT/Input/.q.tex.tmp"
mv "$DOGFOOD_ROOT/Input/.q.tex.tmp" "$DOGFOOD_ROOT/Input/q.tex"
```

The rename is atomic; a slow redirect is not. Polling defaults to 500 ms
(`LATTEX_WATCH_POLL_MS`, 10–60000). `LATTEX_INPUT_DIR` / `LATTEX_OUTPUT_DIR` exist for
custom images, but `/lattex/input` and `/lattex/output` are the documented contract.

### ⚠️ Re-tagging the image does NOT update the running container

A container is bound to the image **ID** it was created from, not to the tag name. After you
promote a new `lattex:dogfood`, `docker restart lattex-dogfood` keeps serving the **old**
build — restart restarts the same container, it does not re-resolve the tag. Recreate it:

> ⚠️ **`docker rm -f` destroys the named container, not just its process.** Anything living in
> that container's writable layer — a partially processed job, an in-flight claim directory —
> goes with it. `$DOGFOOD_ROOT/Input` and `Output` are bind mounts and survive, holding whatever
> host-side ownership they already had; nothing else does. So graceful stop is the default here
> and `-f` is the exception, not the other way round.

```sh
docker stop -t 30 lattex-dogfood && docker rm lattex-dogfood
# `docker rm -f` only when the graceful stop has already failed — it kills a job mid-flight.
docker run -d --name lattex-dogfood --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  -v "$DOGFOOD_ROOT/Input:/lattex/input" \
  -v "$DOGFOOD_ROOT/Output:/lattex/output" \
  lattex:dogfood watch
```

**Ask for the two IDs; do not read the `docker ps` image column.** An earlier version of this
section claimed a bare hex ID appears there when the tag has moved. That is false, and it is
false in the direction that matters: a container keeps the image *reference string* it was
created with, so `docker ps` goes on printing `lattex:dogfood` however far the tag has moved.
Confirmed by read-only inspection of a real dogfood container — configured image
`lattex:dogfood`, pinned `.Image` an ID. The tell that was supposed to reveal staleness is
exactly as reassuring when stale as when current. Compare the IDs instead:

```sh
pinned=$(docker inspect -f '{{.Image}}' lattex-dogfood)
current=$(docker image inspect lattex:dogfood --format '{{.Id}}')
test "$pinned" = "$current" \
  && echo "container is running the current lattex:dogfood" \
  || echo "STALE: tag moved to ${current%%:*}… without this container — recreate it"
```

Then ask the *running container* what it is, which is the only check that describes what is
actually serving you:

```sh
docker exec lattex-dogfood java -jar /opt/lattex/lattex.jar --version
```

Act IV verifies the **image**. This verifies the **process**. When they disagree, this wins.

### Lifecycle

```text
Input/q.tex
  -> Input/processing/<job-id>/q.tex     claimed (atomic move)
  -> Input/finished/q.tex                success — source preserved
  -> Input/failed/q.tex                  failure
Output/q.svg                             success
Output/q.tex.error.txt                   failure — bounded, non-secret
```

Claims are moves into `processing/<job-id>/<original-name>`, keeping the id and the filename
in separate path components so long names stay valid after claiming. Two containers on the
same mounts cannot both claim one source, and valid claims left in `processing/` are
recovered after restart. Existing output is never overwritten — a collision is failed
explicitly or retained under `collisions/<job-id>/`.

### One-shot, without the watcher

```sh
docker run --rm lattex:dogfood cli '\frac{a}{b}' > eq.svg
printf '%s\n' '\sqrt{2}' | docker run --rm -i lattex:dogfood cli > root.svg

docker run --rm --user "$(id -u):$(id -g)" \
  -v "$DOGFOOD_ROOT/Input:/lattex/input:ro" -v "$DOGFOOD_ROOT/Output:/lattex/output" \
  lattex:dogfood cli --input /lattex/input/eq.tex -o /lattex/output/eq.svg
```

(Same `$DOGFOOD_ROOT` as Act V, deliberately. This example used `$PWD`, which quietly made it a
*third* root in one document — the watcher's absolute mounts, Act V's relative writes, and this.
Each was defensible alone; together they meant "Input/" named a different directory in three
places.)

Note the input mount is **read-only** here — one-shot mode never needs to write to it. Only
watch mode requires a writable input, because claiming is a move.

**What makes the output writable is the `--user` override, not the mount.** A bind mount does not
confer ownership: the host directory keeps the uid/gid it already had, and the container writes as
whatever user it runs as. LatteX's image runs as its own fixed non-root uid, so without
`--user "$(id -u):$(id -g)"` the output lands owned by that uid and a host reader can find a file
that exists, is correct, and refuses to open. That is the same shape as the `output owned by
10001` row in troubleshooting below — one cause, two symptoms, and the override is the fix for
both.

---

## Downstream: the vendored-jar hazard

Stafficy `/docs` does not use this container — it vendors a LatteX **jar** into
`modules/content/libs/`. Same failure class, different delivery: that jar was pinned at the
withdrawn `0.11.1` and refused `\%` on live docs pages.

If you change how LatteX is delivered, remember there are **two** consumers with independent
staleness: this image, and that vendored jar. Updating one says nothing about the other.

---

## Troubleshooting

| symptom | look here first |
|---|---|
| a valid expression is "invalid LaTeX" | **check `cli --version` before doubting the expression** |
| smoke test red with no output | you are on an old smoke script with the hard-coded pin; update it |
| first CLI argument is `watch`/`cli` | prefix explicit `cli` |
| output owned by `10001` | pass `--user "$(id -u):$(id -g)"` |
| watcher ignores your file | not a visible direct-child `*.tex` |
| output never appears | check `Input/failed/` and `Output/*.error.txt` |
| build fails in the Gradle stage | no host cache inside the build stage; it needs network |

---

## The short version

1. Fence first (clean tree, `HEAD` == `origin/main`), then
   `docker build -t lattex:main-$SHA .` — no build args needed. The fence is what makes the
   tag's claim true; `docker build .` ships your working tree, not the commit you named.
2. Verify: renders → version matches source → smoke passes.
3. Tag `main-<sha>` **first**; promote `dogfood` second. It is your only link from bytes to a
   commit — and a convention you keep, not one Docker enforces, since any tag can be re-pointed.
4. Pin **tagged** releases. `0.11.1` is the standing proof that a version string can name
   nothing at all.
5. Rename files into `Input/`; never write them in place.

When maths that should work doesn't, check the renderer's age before you rewrite the maths.
