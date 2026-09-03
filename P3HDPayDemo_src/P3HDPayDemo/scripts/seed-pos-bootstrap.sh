#!/usr/bin/env bash
# seed-pos-bootstrap.sh — one-shot device setup for the CB on-premise pilot.
#
# Installs the freshly built debug APK and seeds the terminal so it works with ZERO
# Settings interaction once it joins the CBUAE staff Wi-Fi:
#   env = bootstrap (also the APK default), API key seeded, manual flag set so no
#   fetch flow ever replaces it. Wallet id is left unset — the app's built-in
#   bootstrap default (CUAEB11BE4C2F2) applies, and Settings can override it.
#
# The key is seeded into the plain prefs file over adb — the mechanism SecurePrefs
# is designed for: the app migrates it into Keystore-encrypted storage on first
# read and deletes the plaintext copy.
#
# Key source (first match wins): $LFI_API_KEY env var, -k <key>, scripts/bootstrap.env.
# Run this at your desk over USB, BEFORE walking to the CB Wi-Fi.
set -euo pipefail

PACKAGE="com.lfi.p3hd.demo"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$SCRIPT_DIR/../app/build/outputs/apk/debug/app-debug.apk"
KEY="${LFI_API_KEY:-}"
SERIAL="${SUNMI_SERIAL:-}"
SKIP_INSTALL=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -k|--key)     KEY="$2"; shift 2;;
    -s|--serial)  SERIAL="$2"; shift 2;;
    --no-install) SKIP_INSTALL=true; shift;;
    -h|--help)    grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "unknown arg: $1"; exit 1;;
  esac
done

# shellcheck disable=SC1091
[[ -z "$KEY" && -f "$SCRIPT_DIR/bootstrap.env" ]] && . "$SCRIPT_DIR/bootstrap.env" && KEY="${LFI_API_KEY:-}"
[[ -z "$KEY" ]] && { echo "no API key: pass -k, export LFI_API_KEY, or create scripts/bootstrap.env"; exit 1; }

ADB=(adb); [[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "no device — connect the POS over USB (adb devices)"; exit 1; }

if ! $SKIP_INSTALL; then
  [[ -f "$APK" ]] || { echo "APK not built: $APK — run ./gradlew assembleDebug"; exit 1; }
  echo "▶ installing $(basename "$APK")"
  "${ADB[@]}" install -r "$APK"
fi

echo "▶ seeding prefs (env=bootstrap, key=${KEY:0:12}…, manual=true)"
"${ADB[@]}" shell am force-stop "$PACKAGE" 2>/dev/null || true
PREFS_XML="<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>
<map>
    <string name=\"auth:env\">bootstrap</string>
    <string name=\"lfi_api_key\">${KEY}</string>
    <boolean name=\"lfi_api_key_manual\" value=\"true\" />
</map>"
printf '%s\n' "$PREFS_XML" | \
  "${ADB[@]}" shell "run-as $PACKAGE tee /data/data/$PACKAGE/shared_prefs/p3hd_pref.xml" >/dev/null

echo "▶ launching"
"${ADB[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "done — the terminal is configured for bootstrap. Join the CBUAE STAFF Wi-Fi and generate a QR."
