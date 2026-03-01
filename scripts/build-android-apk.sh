#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
ANDROID_DIR="$FRONTEND_DIR/android"
SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
JAVA_HOME_21="${JAVA_HOME_21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
GRADLE_HOME_DIR="$ROOT_DIR/.gradle"

detect_lan_ip() {
  if command -v ifconfig >/dev/null 2>&1; then
    ifconfig en0 2>/dev/null | awk '/inet / {print $2; exit}'
    return
  fi
  echo ""
}

LAN_IP="${LAN_IP:-$(detect_lan_ip)}"
if [[ -z "$LAN_IP" ]]; then
  echo "Unable to detect LAN IP. Set LAN_IP explicitly and retry."
  exit 1
fi

export JAVA_HOME="$JAVA_HOME_21"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

echo "Using LAN API base: http://$LAN_IP:8080/api"
echo "Using ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT"

cd "$FRONTEND_DIR"
REACT_APP_API_BASE="http://$LAN_IP:8080/api" npm run build
npx cap sync android

cd "$ANDROID_DIR"
GRADLE_USER_HOME="$GRADLE_HOME_DIR" ./gradlew assembleDebug

APK_SOURCE="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_TARGET="$ROOT_DIR/Calorie-Tracker-debug.apk"
cp "$APK_SOURCE" "$APK_TARGET"

echo "APK ready:"
echo "  $APK_TARGET"
