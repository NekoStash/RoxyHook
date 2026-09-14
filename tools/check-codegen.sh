#!/usr/bin/env bash
# Test actual renderer/validator/processor logic against an explicitly handwritten KSP model.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/codegen-check"
mkdir -p "$OUT"
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"
mapfile -t FIXTURES < <(find "$ROOT/tools/fixtures/ksp" -name '*.kt' | sort)
kotlinc "${FIXTURES[@]}" -jvm-target 17 -d "$OUT/ksp-model.jar"
mapfile -t PROCESSOR < <(find "$ROOT/roxy-ksp/src/main/kotlin" -name '*.kt' | sort)
kotlinc "${PROCESSOR[@]}" -jvm-target 17 -classpath "$OUT/ksp-model.jar" -d "$OUT/processor.jar"
PLUGIN="$ROOT/roxy-gradle-plugin/src/main/kotlin/hk/uwu/roxyhook/gradle"
HELPERS=("$PLUGIN/MetadataRenderer.kt" "$PLUGIN/EntrySourceTemplate.kt" "$PLUGIN/EntryMetadataValidator.kt" "$PLUGIN/DexClassIndex.kt" "$PLUGIN/RoxyApkInspector.kt")
kotlinc "${HELPERS[@]}" -jvm-target 17 -d "$OUT/tool-helpers.jar"
mapfile -t TESTS < <(find "$ROOT/tools/tests/codegen" -name '*.kt' | sort)
CP="$OUT/ksp-model.jar:$OUT/processor.jar:$OUT/tool-helpers.jar"
kotlinc "${TESTS[@]}" -jvm-target 17 -classpath "$CP" -include-runtime -d "$OUT/codegen-tests.jar"
java -Droxy.codegen.out="$OUT/generated" -cp "$OUT/codegen-tests.jar:$CP" hk.uwu.roxyhook.tooltests.CodegenSuite
