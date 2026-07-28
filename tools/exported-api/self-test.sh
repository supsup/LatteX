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

run_gate_with_allowlists() {
  local tip=$1
  local base_allowlist=$2
  local tip_allowlist=$3
  local log=$4
  java "$script_dir/ExportedSurfaceGate.java" \
    --base-classes "$gate_tmp/classes/base" \
    --base-module-info-class "$gate_tmp/classes/base/module-info.class" \
    --tip-classes "$gate_tmp/classes/$tip" \
    --tip-module-info-class "$gate_tmp/classes/$tip/module-info.class" \
    --base-allowlist "$base_allowlist" \
    --tip-allowlist "$tip_allowlist" >"$log" 2>&1
}

run_gate() {
  run_gate_with_allowlists "$1" \
    "$script_dir/intentional-additions.txt" \
    "$fixture_dir/mathstyle-intentional.txt" "$2"
}

for fixture in base widened corrected positive escaped-export ledger-tip; do
  compile_fixture "$fixture"
done

escaped_log="$gate_tmp/escaped-export.log"
if run_gate_with_allowlists escaped-export \
    "$script_dir/intentional-additions.txt" \
    "$script_dir/intentional-additions.txt" "$escaped_log"; then
  echo "self-test failure: a Unicode-escaped exports directive was not detected" >&2
  cat "$escaped_log" >&2
  exit 1
fi
grep -F -- 'public method com.example.internal.Leaked#newlyExported():void' "$escaped_log" >/dev/null || {
  echo "self-test failure: escaped-export control did not name the newly exported method" >&2
  cat "$escaped_log" >&2
  exit 1
}

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

historic_log="$gate_tmp/historic.log"
if run_gate_with_allowlists corrected \
    "$fixture_dir/mathstyle-intentional.txt" \
    "$fixture_dir/mathstyle-intentional.txt" "$historic_log"; then
  echo "self-test failure: historic ledger lines unexpectedly approved a reintroduction" >&2
  cat "$historic_log" >&2
  exit 1
fi
grep -F -- 'public type com.example.api.MathStyle' "$historic_log" >/dev/null || {
  echo "self-test failure: historic-ledger control did not reject the reintroduced type" >&2
  cat "$historic_log" >&2
  exit 1
}

unused_log="$gate_tmp/unused.log"
if run_gate_with_allowlists corrected \
    "$script_dir/intentional-additions.txt" \
    "$fixture_dir/mathstyle-intentional-with-unused.txt" "$unused_log"; then
  echo "self-test failure: an unused new declaration unexpectedly passed" >&2
  cat "$unused_log" >&2
  exit 1
fi
grep -F -- 'public method com.example.api.MathStyle#notActuallyAdded():void' "$unused_log" >/dev/null || {
  echo "self-test failure: unused-declaration control did not name the stale line" >&2
  cat "$unused_log" >&2
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

commit_repo="$gate_tmp/commit-repo"
mkdir -p "$commit_repo/src/main/java" "$commit_repo/tools/exported-api"
cp -R "$fixture_dir/base/." "$commit_repo/src/main/java/"
cp "$script_dir/intentional-additions.txt" \
  "$commit_repo/tools/exported-api/intentional-additions.txt"
cat >"$commit_repo/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/classes/java/main build/exported-api
find src/main/java -name '*.java' -print | sort >build/exported-api/sources.txt
javac -d build/classes/java/main @build/exported-api/sources.txt
EOF
chmod +x "$commit_repo/gradlew"
git -C "$commit_repo" init -q
git -C "$commit_repo" add .
git -C "$commit_repo" -c user.name=ExportedSurfaceGate \
  -c user.email=exported-surface@example.invalid commit -q -m base
base_commit=$(git -C "$commit_repo" rev-parse HEAD)

rm -rf -- "$commit_repo/src/main/java"
mkdir -p "$commit_repo/src/main/java"
cp -R "$fixture_dir/ledger-tip/." "$commit_repo/src/main/java/"
cp "$fixture_dir/ledger-tip-intentional.txt" \
  "$commit_repo/tools/exported-api/intentional-additions.txt"
git -C "$commit_repo" add .
git -C "$commit_repo" -c user.name=ExportedSurfaceGate \
  -c user.email=exported-surface@example.invalid commit -q -m tip
tip_commit=$(git -C "$commit_repo" rev-parse HEAD)

ledger_log="$gate_tmp/tip-ledger.log"
(
  cd "$commit_repo"
  "$script_dir/check.sh" "$base_commit" "$tip_commit"
) >"$ledger_log" 2>&1 || {
  echo "self-test failure: default ledger was not read from TIP_COMMIT" >&2
  cat "$ledger_log" >&2
  exit 1
}

override_log="$gate_tmp/explicit-ledger-override.log"
if (
  cd "$commit_repo"
  "$script_dir/check.sh" "$base_commit" "$tip_commit" \
    "$script_dir/intentional-additions.txt"
) >"$override_log" 2>&1; then
  echo "self-test failure: explicit empty ledger override unexpectedly passed" >&2
  cat "$override_log" >&2
  exit 1
fi
grep -F -- 'public method com.example.internal.Leaked#newlyExported():void' \
  "$override_log" >/dev/null || {
  echo "self-test failure: explicit ledger override did not control approval" >&2
  cat "$override_log" >&2
  exit 1
}

echo "PASS: widened MathStyle fails with all three methods named."
echo "PASS: relocated-method MathStyle passes with only the intentional enum surface."
echo "PASS: deliberate public and protected additions fail even though module-info is unchanged."
echo "PASS: historic ledger lines cannot approve reintroduction; unused new lines fail."
echo "PASS: compiled Module metadata catches Unicode-escaped exports directives."
echo "PASS: default approval ledger comes from TIP_COMMIT; explicit override remains explicit."
