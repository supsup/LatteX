#!/bin/sh
set -eu

image=${1:-lattex:local}
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/lattex-docker-smoke.XXXXXX")
name_suffix=$$
watch_one="lattex-smoke-one-$name_suffix"
watch_recovery="lattex-smoke-recovery-$name_suffix"
watch_collision="lattex-smoke-collision-$name_suffix"
watch_race_a="lattex-smoke-race-a-$name_suffix"
watch_race_b="lattex-smoke-race-b-$name_suffix"

cleanup() {
    docker rm -f "$watch_one" "$watch_recovery" "$watch_collision" \
        "$watch_race_a" "$watch_race_b" >/dev/null 2>&1 || true
    if [ -n "$tmp_root" ] && [ "$tmp_root" != "/" ]; then
        chmod -R u+rwx "$tmp_root" >/dev/null 2>&1 || true
        rm -rf -- "$tmp_root"
    fi
}
trap cleanup EXIT INT TERM

wait_for_path() {
    target=$1
    container=$2
    attempt=0
    while [ ! -e "$target" ] && [ "$attempt" -lt 240 ]; do
        sleep 0.05
        attempt=$((attempt + 1))
    done
    if [ ! -e "$target" ]; then
        echo "timed out waiting for expected worker artifact: $target" >&2
        docker logs "$container" >&2 || true
        return 1
    fi
}

assert_svg() {
    target=$1
    test -s "$target"
    grep -q '<svg' "$target"
    grep -q '</svg>' "$target"
}

# Runtime shape: non-root, immutable jars, and fixed mount roots. The CLI
# renders immediately below, which also proves the bundled font is present and
# readable without requiring JDK-only `jar` tooling in the JRE image.
docker run --rm --entrypoint sh "$image" -c '
    test "$(id -u)" = 10001
    test -r /opt/lattex/lattex.jar
    test ! -w /opt/lattex/lattex.jar
    test -r /opt/lattex/lattex-worker.jar
    test -d /lattex/input
    test -d /lattex/output
'

# Explicit `cli` and the old no-mode image shape preserve argv/stdin bytes.
docker run --rm "$image" '\frac{a}{b}' > "$tmp_root/legacy-argv.svg"
docker run --rm "$image" cli '\frac{a}{b}' > "$tmp_root/explicit-argv.svg"
cmp "$tmp_root/legacy-argv.svg" "$tmp_root/explicit-argv.svg"
assert_svg "$tmp_root/legacy-argv.svg"

printf '%s\n' '\sqrt{2}' \
    | docker run --rm -i "$image" > "$tmp_root/legacy-stdin.svg"
printf '%s\n' '\sqrt{2}' \
    | docker run --rm -i "$image" cli > "$tmp_root/explicit-stdin.svg"
cmp "$tmp_root/legacy-stdin.svg" "$tmp_root/explicit-stdin.svg"
docker run --rm "$image" cli --help | grep -q 'USAGE:'
docker run --rm "$image" cli --version | grep -q '^lattex 0\.11\.0$'

printf '%s\n' 'x^2' '\frac{a}{b}' \
    | docker run --rm -i "$image" cli --batch > "$tmp_root/batch.bin"
test "$(tr -cd '\000' < "$tmp_root/batch.bin" | wc -c | tr -d ' ')" -eq 2
if docker run --rm "$image" cli '\definitelyNotLatteX' \
        > "$tmp_root/invalid.svg" 2> "$tmp_root/invalid.err"; then
    echo 'invalid CLI input unexpectedly succeeded' >&2
    exit 1
fi
test ! -s "$tmp_root/invalid.svg"

input="$tmp_root/Input"
output="$tmp_root/Output"
mkdir -p "$input" "$output"
chmod 0777 "$input" "$output"

# CLI file mode consumes the read-only input mount and writes the output mount.
printf '%s\n' '\int_0^1 x^2\,dx' > "$input/cli example.tex"
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$input:/lattex/input:ro" \
    -v "$output:/lattex/output" \
    "$image" cli --input '/lattex/input/cli example.tex' \
    -o '/lattex/output/cli example.svg'
assert_svg "$output/cli example.svg"

# Startup backlog, multiline input, failure, spaces, and ignore rules.
printf '%s\n' '\begin{aligned}' 'a &= b + c \\' 'd &= e' '\end{aligned}' \
    > "$input/startup formula.tex"
printf '%s\n' '\definitelyNotLatteX{TOP-SECRET-SENTINEL}' > "$input/bad.tex"
printf '%s\n' 'unreadable sentinel' > "$input/unreadable.tex"
printf '%s\n' 'x + y' > "$input/.upload.tex.tmp"
printf '%s\n' '{"ignored":true}' > "$input/ignored.json"
mkdir -p "$input/finished" "$input/processing"
printf '%s\n' 'z^2' > "$input/finished/state child.tex"
printf '%s\n' 'q^2' > "$input/processing/not-a-claim.tex"
chmod -R a+rwX "$input" "$output"
# Producers need only publish readable bytes; archive transitions mutate the
# input directory, not the source inode.
chmod 0444 "$input/startup formula.tex"
chmod 0000 "$input/unreadable.tex"

docker run -d --name "$watch_one" \
    -e LATTEX_WATCH_POLL_MS=20 \
    -v "$input:/lattex/input" \
    -v "$output:/lattex/output" \
    "$image" watch >/dev/null

wait_for_path "$output/startup formula.svg" "$watch_one"
wait_for_path "$output/bad.tex.error.txt" "$watch_one"
wait_for_path "$output/unreadable.tex.error.txt" "$watch_one"
wait_for_path "$input/finished/startup formula.tex" "$watch_one"
wait_for_path "$input/failed/bad.tex" "$watch_one"
wait_for_path "$input/failed/unreadable.tex" "$watch_one"
assert_svg "$output/startup formula.svg"
test ! -e "$output/bad.svg"
test ! -e "$output/unreadable.svg"
test -e "$input/.upload.tex.tmp"
test -e "$input/ignored.json"
test -e "$input/finished/state child.tex"
test -e "$input/processing/not-a-claim.tex"
test ! -e "$output/upload.svg"
test ! -e "$output/ignored.svg"
test "$(wc -c < "$output/bad.tex.error.txt")" -le 600
! grep -q 'TOP-SECRET-SENTINEL' "$output/bad.tex.error.txt"
grep -q 'unreadable-input' "$output/unreadable.tex.error.txt"
docker stop -t 2 "$watch_one" >/dev/null
docker rm "$watch_one" >/dev/null

# Restart recovery: a valid UUID-prefixed processing file is reclaimed after
# the prior process is gone; unrelated processing files remain untouched.
recovery_id=0123456789abcdef0123456789abcdef
printf '%s\n' '\sum_{i=1}^{4} i' \
    > "$input/processing/$recovery_id--recovered formula.tex"
docker run -d --name "$watch_recovery" \
    -e LATTEX_WATCH_POLL_MS=20 \
    -v "$input:/lattex/input" \
    -v "$output:/lattex/output" \
    "$image" watch >/dev/null
wait_for_path "$output/recovered formula.svg" "$watch_recovery"
wait_for_path "$input/finished/recovered formula.tex" "$watch_recovery"
assert_svg "$output/recovered formula.svg"
test -e "$input/processing/not-a-claim.tex"
docker stop -t 2 "$watch_recovery" >/dev/null
docker rm "$watch_recovery" >/dev/null

# A second source with a used name cannot overwrite the prior SVG or archive.
printf '%s\n' 'x + 1' > "$input/same name.tex"
docker run -d --name "$watch_collision" \
    -e LATTEX_WATCH_POLL_MS=20 \
    -v "$input:/lattex/input" \
    -v "$output:/lattex/output" \
    "$image" watch >/dev/null
wait_for_path "$output/same name.svg" "$watch_collision"
wait_for_path "$input/finished/same name.tex" "$watch_collision"
before_svg=$(sha256sum "$output/same name.svg" | cut -d ' ' -f 1)
before_source=$(sha256sum "$input/finished/same name.tex" | cut -d ' ' -f 1)
printf '%s\n' 'y + 2' > "$input/.same name.tex.tmp"
mv "$input/.same name.tex.tmp" "$input/same name.tex"
wait_for_path "$output/same name.tex.error.txt" "$watch_collision"
wait_for_path "$input/failed/same name.tex" "$watch_collision"
test "$before_svg" = "$(sha256sum "$output/same name.svg" | cut -d ' ' -f 1)"
test "$before_source" = "$(sha256sum "$input/finished/same name.tex" | cut -d ' ' -f 1)"
docker stop -t 2 "$watch_collision" >/dev/null
docker rm "$watch_collision" >/dev/null

# Two workers share one mount. Atomic claim plus idempotent publication/archive
# makes exactly one worker finish the source even if advisory locks degrade.
race_input="$tmp_root/RaceInput"
race_output="$tmp_root/RaceOutput"
mkdir -p "$race_input" "$race_output"
chmod 0777 "$race_input" "$race_output"
docker run -d --name "$watch_race_a" \
    -e LATTEX_WATCH_POLL_MS=20 \
    -v "$race_input:/lattex/input" \
    -v "$race_output:/lattex/output" \
    "$image" watch >/dev/null
docker run -d --name "$watch_race_b" \
    -e LATTEX_WATCH_POLL_MS=20 \
    -v "$race_input:/lattex/input" \
    -v "$race_output:/lattex/output" \
    "$image" watch >/dev/null
printf '%s\n' '\sqrt{x^2 + y^2}' > "$race_input/.race file.tex.tmp"
mv "$race_input/.race file.tex.tmp" "$race_input/race file.tex"
wait_for_path "$race_output/race file.svg" "$watch_race_a"
wait_for_path "$race_input/finished/race file.tex" "$watch_race_a"
sleep 0.2
assert_svg "$race_output/race file.svg"
test ! -e "$race_output/race file.tex.error.txt"
test ! -e "$race_input/failed/race file.tex"
test "$(docker inspect --format '{{.State.Running}}' "$watch_race_a")" = true
test "$(docker inspect --format '{{.State.Running}}' "$watch_race_b")" = true
finished_count=$(
    { docker logs "$watch_race_a"; docker logs "$watch_race_b"; } 2>&1 \
        | grep -c ' finished$'
)
test "$finished_count" -eq 1
docker stop -t 2 "$watch_race_a" "$watch_race_b" >/dev/null
docker rm "$watch_race_a" "$watch_race_b" >/dev/null

# No success-shaped temp artifacts survive any path.
test -z "$(find "$output" "$race_output" -maxdepth 1 -type f -name '.*.tmp' -print -quit)"

echo 'lattex Docker smoke: PASS'
