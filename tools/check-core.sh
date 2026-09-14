#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROXY_CHECK_OUT:-$ROOT/build/offline-check}"
command -v kotlinc >/dev/null || { echo 'Install Kotlin CLI 1.9+ for the offline JVM contract check.' >&2; exit 1; }
mkdir -p "$OUT"
python3 "$ROOT/tools/generate-signature-fixtures.py" "$OUT/fixtures"
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"
kotlinc "$OUT/fixtures/kava/Resolvers.kt" "$OUT/fixtures/kava/KavaRef.kt" -jvm-target 17 -d "$OUT/kava-signatures.jar"
mapfile -t CORE < <(find "$ROOT/roxy-core/src/main/kotlin" "$ROOT/roxy-annotations/src/main/kotlin" -name '*.kt' | sort)
mapfile -t TESTING < <(find "$ROOT/roxy-testing/src/main/kotlin" -name '*.kt' | sort)
mapfile -t TESTS < <(find "$ROOT/roxy-testing/src/test/kotlin" -name '*.kt' | sort)
kotlinc "${CORE[@]}" -jvm-target 17 -classpath "$OUT/kava-signatures.jar" -d "$OUT/roxy-core.jar"
kotlinc "${TESTING[@]}" -jvm-target 17 -classpath "$OUT/roxy-core.jar" -d "$OUT/roxy-testing.jar"
CP="$OUT/roxy-core.jar:$OUT/roxy-testing.jar:$OUT/kava-signatures.jar"
kotlinc "${TESTS[@]}" -jvm-target 17 -classpath "$CP" -include-runtime -d "$OUT/contracts.jar"
java -Droxy.report="$OUT/roxy-contracts.xml" -cp "$OUT/contracts.jar:$CP" hk.uwu.roxyhook.testing.ContractSuite
java -cp "$OUT/contracts.jar:$CP" hk.uwu.roxyhook.testing.RegressionSuite
printf '\nJVM behavior tested; KavaRef names used compile-only signatures. This is NOT a real Android/dependency build.\n'
