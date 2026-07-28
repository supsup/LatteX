#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd -P)
fixture_dir="$script_dir/fixtures"
gate_tmp=$(mktemp -d "${TMPDIR:-/tmp}/lattex-exported-api-self-test.XXXXXX")

cleanup() {
  if [[ -n "${gate_tmp:-}" && "$gate_tmp" == */lattex-exported-api-self-test.* ]]; then
    rm -rf -- "$gate_tmp"
  fi
}
trap cleanup EXIT

compile_fixture() {
  local name=$1
  mkdir -p "$gate_tmp/classes/$name"
  find "$fixture_dir/$name" -name '*.java' -print | sort > "$gate_tmp/$name.sources"
  javac -d "$gate_tmp/classes/$name" "@$gate_tmp/$name.sources"
}

run_gate() {
  local tip=$1
  local log=$2
  java "$script_dir/ExportedSurfaceGate.java" \
    --base-classes "$gate_tmp/classes/base" \
    --base-module-info "$fixture_dir/base/module-info.java" \
    --tip-classes "$gate_tmp/classes/$tip" \
    --tip-module-info "$fixture_dir/$tip/module-info.java" \
    --allowlist "$fixture_dir/mathstyle-intentional.txt" >"$log" 2>&1
}

for fixture in base widened corrected positive; do
  compile_fixture "$fixture"
done

widened_log="$gate_tmp/widened.log"
if run_gate widened "$widened_log"; then
  echo "self-test failure: the widened MathStyle fixture unexpectedly passed" >&2
  cat "$widened_log" >&2
  exit 1
fi
for method in \
  'public method com.example.api.MathStyle#fractionChildStyle():com.example.api.MathStyle' \
  'public method com.example.api.MathStyle#isDisplay():boolean' \
  'public method com.example.api.MathStyle#scriptStyle():com.example.api.MathStyle'; do
  grep -F -- "$method" "$widened_log" >/dev/null || {
    echo "self-test failure: widened fixture did not name $method" >&2
    cat "$widened_log" >&2
    exit 1
  }
done

corrected_log="$gate_tmp/corrected.log"
run_gate corrected "$corrected_log" || {
  echo "self-test failure: the corrected MathStyle fixture did not pass" >&2
  cat "$corrected_log" >&2
  exit 1
}

positive_log="$gate_tmp/positive.log"
if run_gate positive "$positive_log"; then
  echo "self-test failure: the deliberate public/protected additions unexpectedly passed" >&2
  cat "$positive_log" >&2
  exit 1
fi
for method in \
  'public method com.example.api.MathStyle#deliberatePositiveControl():boolean' \
  'protected method com.example.api.MathStyle#protectedPositiveControl():int' \
  'public static field com.example.api.Existing#DELIBERATE_FIELD:int' \
  'protected field com.example.api.Existing#protectedField:java.lang.String' \
  'public constructor com.example.api.Existing(int)' \
  'protected type com.example.api.Existing$ProtectedNested' \
  'protected constructor com.example.api.Existing$ProtectedNested()' \
  'public method com.example.api.Existing$ProtectedNested#nestedMethod():void'; do
  grep -F -- "$method" "$positive_log" >/dev/null || {
    echo "self-test failure: positive control did not name $method" >&2
    cat "$positive_log" >&2
    exit 1
  }
done

echo "PASS: widened MathStyle fails with all three methods named."
echo "PASS: relocated-method MathStyle passes with only the intentional enum surface."
echo "PASS: deliberate public and protected additions fail even though module-info is unchanged."
