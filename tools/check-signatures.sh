#!/usr/bin/env bash
# Handwritten upstream signatures: catches Kotlin/API-shape errors, NOT a real dependency or APK build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/signature-check"
CORE="$ROOT/build/offline-check"
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"
if [[ ! -f "$CORE/roxy-core.jar" ]] || find "$ROOT/roxy-core/src" "$ROOT/roxy-annotations/src" -name '*.kt' -newer "$CORE/roxy-core.jar" | grep -q .; then
    "$ROOT/tools/check-core.sh"
fi
if [[ ! -d "$ROOT/build/codegen-check/generated" ]]; then "$ROOT/tools/check-codegen.sh"; fi
mkdir -p "$OUT/java"
python3 "$ROOT/tools/generate-signature-fixtures.py" "$OUT/fixtures"
find "$OUT/fixtures" -name '*.java' | sort > "$OUT/java-sources.txt"
javac --release 17 -d "$OUT/java" @"$OUT/java-sources.txt"
CP="$CORE/roxy-core.jar:$CORE/kava-signatures.jar:$OUT/java"
mapfile -t ANDROID < <(find "$ROOT/roxy-android/src/main/kotlin" -name '*.kt' | sort)
kotlinc -jvm-target 17 -classpath "$CP" "${ANDROID[@]}" -d "$OUT/android.jar"
echo 'PASS  Android layer, separately compiled against handwritten signatures'
mapfile -t PLATFORM < <(find "$ROOT/roxy-platforms/libxposed/src/main/kotlin" -name '*.kt' | sort)
kotlinc -jvm-target 17 -classpath "$CP:$OUT/android.jar" "${PLATFORM[@]}" -d "$OUT/libxposed.jar"
echo 'PASS  LibXposed + integrated service layer, separately compiled against handwritten signatures'
mapfile -t SAMPLES < <(find "$ROOT/samples" -name '*.kt' | sort)
mapfile -t GENERATED < <(find "$ROOT/build/codegen-check/generated" -name '*.kt' | sort)
kotlinc -jvm-target 17 -classpath "$CP:$OUT/android.jar:$OUT/libxposed.jar" "${SAMPLES[@]}" "${GENERATED[@]}" -d "$OUT/samples.jar"
echo 'PASS  Both sample sources AND the actual processor-rendered native entry compile together'
echo 'SIGNATURE SMOKE PASSED. Handwritten fixtures, NOT upstream artifacts, Gradle, D8/R8 or Android.'
