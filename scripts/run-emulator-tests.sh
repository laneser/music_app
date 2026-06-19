#!/usr/bin/env bash
# Create a headless KVM-accelerated AVD and run the instrumented E2E tests.
# Requires the container to have been started with --device=/dev/kvm.
set -euo pipefail
cd "$(dirname "$0")/.."

AVD=ci_avd
IMAGE="system-images;android-34;google_apis;x86_64"

if ! avdmanager list avd | grep -q "$AVD"; then
  echo "no" | avdmanager create avd -n "$AVD" -k "$IMAGE" --device "pixel_6"
fi

echo "==> Booting emulator (headless)"
emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
EMU_PID=$!
trap 'kill $EMU_PID 2>/dev/null || true' EXIT

adb wait-for-device
# Block until the system is fully booted.
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell input keyevent 82 || true

echo "==> Running connectedDebugAndroidTest"
./gradlew connectedDebugAndroidTest
