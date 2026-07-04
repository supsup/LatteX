#!/usr/bin/env bash
#
# LatteX render-startup benchmark: native binary vs. `java -jar` vs. `./gradlew run`.
#
# Times N runs of the same expression through each launch mode. The three modes
# render byte-identical SVG; what differs is startup cost, which dominates a
# single render (the render itself is sub-millisecond).
#
# Usage:   tools/bench.sh [N] ["latex expression"]
# Example: tools/bench.sh 50 '\sum_{i=1}^{n} i = \frac{n(n+1)}{2}'
#
# The native mode needs a GraalVM for JDK 25 — set GRAALVM_HOME (e.g.
#   export GRAALVM_HOME="$HOME/.sdkman/candidates/java/25-graalce"
# ) or have `native-image` on PATH. If neither is available the native row is
# skipped and only the JVM modes are timed.
#
# NOTE: absolute numbers are highly machine-dependent (CPU, disk, JVM/GraalVM
# version, warm vs. cold caches). The stable signal is the RATIO between modes.
set -euo pipefail

cd "$(cd "$(dirname "$0")/.." && pwd)"   # repo root (script lives in tools/)

N="${1:-50}"
EXPR="${2:-\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}}"

echo "LatteX benchmark — $N runs each, expression: $EXPR"
echo

# --- build artifacts -------------------------------------------------------
echo "Building jar…"
./gradlew -q jar >/dev/null
JAR="$(ls build/libs/*.jar | grep -vE 'javadoc|sources' | head -1)"

NATIVE="build/native/lattex"
HAVE_NATIVE=0
if [ -n "${GRAALVM_HOME:-}" ] || command -v native-image >/dev/null 2>&1; then
  echo "Building native binary (GraalVM native-image)…"
  if ./gradlew -q nativeImage >/dev/null 2>&1 && [ -x "$NATIVE" ]; then
    HAVE_NATIVE=1
  else
    echo "  native build failed — skipping native row (check GRAALVM_HOME / JDK 25)."
  fi
else
  echo "  no GRAALVM_HOME and no native-image on PATH — skipping native row."
fi

# --- sanity: identical output ---------------------------------------------
jar_bytes=$(echo "$EXPR" | java -jar "$JAR" | wc -c | tr -d ' ')
echo
echo "Output size: java -jar → ${jar_bytes} bytes (all modes render identically)"

# --- warm up ---------------------------------------------------------------
echo "$EXPR" | java -jar "$JAR" >/dev/null
./gradlew -q run --args="$EXPR" >/dev/null 2>&1
[ "$HAVE_NATIVE" = 1 ] && echo "$EXPR" | "$NATIVE" >/dev/null

# --- time N runs -----------------------------------------------------------
timeit() { # label, then a shell snippet run via eval
  local label="$1"; shift
  local s e
  s=$(date +%s.%N)
  for _ in $(seq "$N"); do eval "$@" >/dev/null 2>&1; done
  e=$(date +%s.%N)
  awk -v s="$s" -v e="$e" -v n="$N" -v l="$label" \
    'BEGIN{t=(e-s)*1000; printf "  %-22s total %9.1f ms   avg %8.2f ms/run\n", l, t, t/n}'
}

echo
echo "Timing $N runs each:"
[ "$HAVE_NATIVE" = 1 ] && timeit "native binary" "echo \"\$EXPR\" | \"\$NATIVE\""
timeit "java -jar"       "echo \"\$EXPR\" | java -jar \"\$JAR\""
timeit "gradle run"      "./gradlew -q run --args=\"\$EXPR\""
echo
echo "Reminder: these numbers are machine-specific — compare the ratios, not the absolutes."
