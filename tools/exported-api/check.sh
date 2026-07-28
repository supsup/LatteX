#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 BASE_COMMIT TIP_COMMIT [TIP_ALLOWLIST]" >&2
  exit 2
fi

base_commit=$1
tip_commit=$2
script_dir=$(cd "$(dirname "$0")" && pwd -P)

base_sha=$(git rev-parse --verify "${base_commit}^{commit}")
tip_sha=$(git rev-parse --verify "${tip_commit}^{commit}")
gate_tmp=$(mktemp -d "${TMPDIR:-/tmp}/lattex-exported-api.XXXXXX")

cleanup() {
  if [[ -n "${gate_tmp:-}" && "$gate_tmp" == */lattex-exported-api.* ]]; then
    rm -rf -- "$gate_tmp"
  fi
}
trap cleanup EXIT

base_tree="$gate_tmp/base"
tip_tree="$gate_tmp/tip"
mkdir -p "$base_tree" "$tip_tree" "$gate_tmp/gradle-home"

git archive --format=tar "$base_sha" | tar -xf - -C "$base_tree"
git archive --format=tar "$tip_sha" | tar -xf - -C "$tip_tree"

base_allowlist="$base_tree/tools/exported-api/intentional-additions.txt"
if [[ ! -f "$base_allowlist" ]]; then
  base_allowlist="$gate_tmp/base-allowlist-empty.txt"
  touch "$base_allowlist"
fi

if [[ $# -eq 3 ]]; then
  tip_allowlist=$3
else
  tip_allowlist="$tip_tree/tools/exported-api/intentional-additions.txt"
fi
if [[ ! -f "$tip_allowlist" ]]; then
  echo "ERROR: tip intentional-additions ledger does not exist: $tip_allowlist" >&2
  exit 1
fi

echo "Building base $base_sha"
(
  cd "$base_tree"
  GRADLE_USER_HOME="$gate_tmp/gradle-home" ./gradlew classes --no-daemon --console=plain
)

echo "Building tip  $tip_sha"
(
  cd "$tip_tree"
  GRADLE_USER_HOME="$gate_tmp/gradle-home" ./gradlew classes --no-daemon --console=plain
)

java "$script_dir/ExportedSurfaceGate.java" \
  --base-classes "$base_tree/build/classes/java/main" \
  --base-module-info-class "$base_tree/build/classes/java/main/module-info.class" \
  --tip-classes "$tip_tree/build/classes/java/main" \
  --tip-module-info-class "$tip_tree/build/classes/java/main/module-info.class" \
  --base-allowlist "$base_allowlist" \
  --tip-allowlist "$tip_allowlist"
