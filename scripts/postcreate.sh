#!/usr/bin/env bash
# Runs once after the devcontainer is created.
set -euo pipefail

cd "$(dirname "$0")/.."

# Generate the Gradle wrapper jar/scripts using the system Gradle, so the repo
# doesn't have to vendor the binary gradle-wrapper.jar.
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "==> Generating Gradle wrapper (8.7)"
  gradle wrapper --gradle-version 8.7 --distribution-type bin
fi

# Write local.properties so Gradle finds the SDK.
if [ ! -f local.properties ]; then
  echo "sdk.dir=${ANDROID_SDK_ROOT:-/opt/android-sdk}" > local.properties
fi

echo "==> Warming up dependencies (offline-friendly cache)"
./gradlew --version || true

echo "Devcontainer ready. Try:  ./gradlew testDebugUnitTest"
