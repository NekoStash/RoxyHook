#!/usr/bin/env bash
# Offline checks only. Upstream artifacts/Gradle/APK/device verification are separate.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p verification
run() {
    local name="$1"; shift
    set +e
    "$@" 2>&1 | tee "verification/$name.log"
    local code="${PIPESTATUS[0]}"
    set -e
    printf '%s\n' "$code" > "verification/$name.exit"
    return "$code"
}
run core-tests tools/check-core.sh
run codegen-tests tools/check-codegen.sh
run adapter-signatures tools/check-signatures.sh
run lifecycle-tests tools/check-lifecycle.sh
run source-audit python3 tools/audit-source.py
printf '\nOffline behavior/models/signatures passed. No Gradle/SDK/ART result is implied.\n'
