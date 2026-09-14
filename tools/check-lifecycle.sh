#!/usr/bin/env bash
# Runs real lifecycle installer/dispatcher code under an explicit dispatch model and Android signatures.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/signature-check"
CORE="$ROOT/build/offline-check"
if [[ ! -f "$OUT/android.jar" ]] || [[ ! -f "$OUT/libxposed.jar" ]]; then "$ROOT/tools/check-signatures.sh"; fi
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"
mapfile -t TESTS < <(find "$ROOT/tools/tests/lifecycle" -name '*.kt' | sort)
CP="$OUT/android.jar:$OUT/libxposed.jar:$CORE/roxy-core.jar:$CORE/kava-signatures.jar:$OUT/java"
kotlinc -jvm-target 17 -classpath "$CP" "${TESTS[@]}" -include-runtime -d "$OUT/lifecycle-model-tests.jar"
java -cp "$OUT/lifecycle-model-tests.jar:$CP" hk.uwu.roxyhook.lifecycletests.LifecycleSuite
