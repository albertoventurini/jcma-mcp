#!/usr/bin/env bash
# Build the jcma GraalVM native image. Output: build/native/nativeCompile/jcma
# Requires a GraalVM 25 toolchain (SDKMAN 25.0.2-graalce); Gradle auto-detects it.
# Pass-through args go to Gradle, e.g. ./build-native-image.sh --info
set -euo pipefail

cd "$(dirname "$0")"

./gradlew nativeCompile "$@"

bin="build/native/nativeCompile/jcma"
[ -x "$bin" ] || { echo "error: expected binary missing: $bin" >&2; exit 1; }

echo
echo "built: $bin ($(du -h "$bin" | cut -f1))"
echo "re-index with:  rm -rf .jcma && $bin index ."
