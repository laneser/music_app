#!/usr/bin/env bash
# Deploy to a USB phone from WSL2 *without* usbipd — using the Windows-side adb.
#
# Why this exists: under WSL2 the phone is plugged into Windows, and Windows
# already has a working USB driver + adb (adb.exe). WSL2 can call adb.exe
# directly, so there is no need to forward the USB device into the WSL kernel
# with usbipd-win just to sideload an APK. (usbipd is only needed if you want a
# *Linux* adb inside the container/WSL to see /dev/bus/usb — see
# docs/arc42/11_risks_and_technical_debt.md and scripts/deploy-usb.sh.)
#
# Flow: build the debug APK in the devcontainer image -> copy it to a
# Windows-readable path -> `adb.exe install` over USB -> launch.
#
# Env overrides:
#   IMAGE       devcontainer image to build in     (default: cellocoach-dev:latest)
#   GRADLE_VOL  named docker volume for ~/.gradle   (default: cellocoach-gradle)
#   WIN_APK     Windows path adb.exe installs from  (default: C:\Users\Public\cellocoach-debug.apk)
#   SKIP_BUILD  set to 1 to install the existing APK without rebuilding
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE="${IMAGE:-cellocoach-dev:latest}"
GRADLE_VOL="${GRADLE_VOL:-cellocoach-gradle}"
WIN_APK="${WIN_APK:-C:\\Users\\Public\\cellocoach-debug.apk}"
# /mnt/c equivalent of WIN_APK, for the copy step.
WSL_APK="/mnt/c/Users/Public/cellocoach-debug.apk"
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.cellocoach

# --- 0. sanity: this path only makes sense on WSL2 with Windows adb ----------
if ! command -v adb.exe >/dev/null 2>&1; then
  cat >&2 <<'EOF'
adb.exe not found on PATH. This script is for WSL2, where Windows provides adb.
Options:
  - Install Android platform-tools on Windows (winget install Google.PlatformTools),
    or
  - Forward the USB device into WSL with usbipd-win and use scripts/deploy-usb.sh.
EOF
  exit 1
fi

# --- 1. build the APK inside the devcontainer image -------------------------
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$APK" ]; then
  echo "==> Building debug APK in $IMAGE"
  docker run --rm \
    -v "$PWD":/workspace -w /workspace \
    -v "$GRADLE_VOL":/root/.gradle \
    -e ANDROID_SDK_ROOT=/opt/android-sdk -e GRADLE_USER_HOME=/root/.gradle \
    --user root "$IMAGE" \
    bash -lc './gradlew --no-daemon assembleDebug'
fi
[ -f "$APK" ] || { echo "APK not found at $APK" >&2; exit 1; }

# --- 2. copy to a Windows-readable location ---------------------------------
echo "==> Copying APK to $WIN_APK"
cp "$APK" "$WSL_APK"

# --- 3. wait for an authorized device ---------------------------------------
echo "==> adb devices"
adb.exe start-server >/dev/null 2>&1 || true
adb.exe devices -l | sed '/^\*/d'

state="$(adb.exe get-state 2>/dev/null | tr -d '\r' || true)"
if [ "$state" != "device" ]; then
  echo "Device not authorized yet — accept the 'Allow USB debugging' prompt on the phone."
  for i in $(seq 1 24); do
    state="$(adb.exe get-state 2>/dev/null | tr -d '\r' || true)"
    [ "$state" = "device" ] && break
    sleep 5
  done
fi
[ "$state" = "device" ] || { echo "Still no authorized device. Tap Allow on the phone and re-run." >&2; exit 1; }

# --- 4. install + launch ----------------------------------------------------
echo "==> Installing"
adb.exe install -r "$WIN_APK" | sed '/^\*/d'
echo "==> Launching $PKG"
adb.exe shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
# Capture first (a `... | grep -q` pipeline would trip `set -o pipefail` via the
# SIGPIPE grep -q sends upstream, giving a false negative even on a match).
pkgs="$(adb.exe shell pm list packages 2>/dev/null | tr -d '\r' || true)"
case "$pkgs" in
  *"$PKG"*) echo "Done — $PKG installed and launched." ;;
  *)        echo "Install reported success but package not listed; check the phone." ;;
esac
