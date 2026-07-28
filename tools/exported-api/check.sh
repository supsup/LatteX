#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 BASE_COMMIT TIP_COMMIT [ALLOWLIST]" >&2
  exit 2
fi

base_commit=$1
tip_commit=$2
script_dir=$(cd "$(dirname "$0")" && pwd -P)
allowlist=${3:-"$script_dir/intentional-additions.txt"}

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
  --base-module-info "$base_tree/src/main/java/module-info.java" \
  --tip-classes "$tip_tree/build/classes/java/main" \
  --tip-module-info "$tip_tree/src/main/java/module-info.java" \
  --allowlist "$allowlist"
