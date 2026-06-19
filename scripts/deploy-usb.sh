#!/usr/bin/env bash
# Build the debug APK and install it onto a USB-connected phone.
#
# Prereqs on a WSL2 + Windows host (see docs/arc42/11_risks_and_tech_debt.md):
#   1. On Windows:  usbipd list  →  usbipd bind --busid <id>  →  usbipd attach --wsl --busid <id>
#   2. The container is started with --device=/dev/bus/usb --privileged (see devcontainer.json),
#      OR run `adb -a nodaemon server` on the host and rely on ADB_SERVER_SOCKET.
#   3. Enable USB debugging on the phone and accept the RSA prompt.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> adb devices"
adb devices -l

if ! adb get-state >/dev/null 2>&1; then
  echo "No device detected. See docs/arc42/11_risks_and_tech_debt.md (USB passthrough)." >&2
  exit 1
fi

echo "==> Building debug APK"
./gradlew assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
echo "==> Installing $APK"
adb install -r "$APK"
echo "==> Launching"
adb shell monkey -p com.cellocoach -c android.intent.category.LAUNCHER 1 || true
echo "Done."
