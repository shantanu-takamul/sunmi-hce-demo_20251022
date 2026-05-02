#!/usr/bin/env bash
# deploy-local.sh — Build, install, tunnel, and launch P3HD Pay Demo
#                   pointed at your Mac's localhost:3000.
#
# Usage:
#   ./deploy-local.sh              — full build + install + tunnel + launch
#   ./deploy-local.sh --no-build   — skip Gradle, re-install last APK, re-tunnel
#   ./deploy-local.sh --tunnel-only — skip build+install, just re-tunnel + relaunch
#                                     (handy after a USB reconnect that killed the tunnel)
#
# How it works:
#   `adb install` always resets ADB reverse port-forwarding rules.
#   This script therefore runs `adb reverse tcp:3000 tcp:3000` AFTER install so
#   the POS device's http://localhost:3000 always routes to your Mac's :3000.
#   It also force-stops the app, writes env=local to SharedPreferences on-device,
#   then relaunches — so the app picks up the local URL on first open.

set -e

PACKAGE="com.lfi.p3hd.demo"
MAIN_ACTIVITY="$PACKAGE/.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
WALLET_ID="ADCB1920276ECD"

# ── Parse flags ──────────────────────────────────────────────────────────────
BUILD=true
INSTALL=true
for arg in "$@"; do
    case "$arg" in
        --no-build)    BUILD=false ;;
        --tunnel-only) BUILD=false; INSTALL=false ;;
        *) echo "Unknown argument: $arg"; exit 1 ;;
    esac
done

# ── 1. Detect Sunmi device ───────────────────────────────────────────────────
echo "▶ Detecting Sunmi device..."
SERIALS=$(adb devices | grep -v "emulator" | awk '/\tdevice/{print $1}')

if [ -z "$SERIALS" ]; then
    echo "✗ No ADB devices connected."
    exit 1
fi

SUNMI_SERIAL=""
for SERIAL in $SERIALS; do
    MFR=$(adb -s "$SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '[:space:]')
    if echo "$MFR" | grep -qi "sunmi"; then
        SUNMI_SERIAL="$SERIAL"
        MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '[:space:]')
        echo "  Found: $SUNMI_SERIAL  (model: $MODEL, mfr: $MFR)"
        break
    fi
done

if [ -z "$SUNMI_SERIAL" ]; then
    echo ""
    echo "✗ No Sunmi device found. Devices currently visible:"
    adb devices -l
    echo ""
    echo "  Manufacturers:"
    for S in $SERIALS; do
        M=$(adb -s "$S" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '[:space:]')
        echo "    $S → $M"
    done
    echo ""
    echo "  Make sure the P3H is connected via USB with USB debugging enabled."
    exit 1
fi

# ── 2. Build ─────────────────────────────────────────────────────────────────
if [ "$BUILD" = true ]; then
    echo ""
    echo "▶ Building debug APK..."
    ./gradlew assembleDebug
else
    echo ""
    if [ "$INSTALL" = true ]; then
        echo "  (--no-build: skipping Gradle, using existing APK)"
    else
        echo "  (--tunnel-only: skipping build and install)"
    fi

    if [ "$INSTALL" = true ] && [ ! -f "$APK_PATH" ]; then
        echo "✗ No APK found at $APK_PATH — run without --no-build first."
        exit 1
    fi
fi

# ── 3. Install ───────────────────────────────────────────────────────────────
if [ "$INSTALL" = true ]; then
    echo ""
    echo "▶ Installing on $SUNMI_SERIAL..."
    adb -s "$SUNMI_SERIAL" install -r "$APK_PATH"
    # NOTE: adb install resets all reverse port-forwarding rules — tunnel comes next.
fi

# ── 4. ADB reverse tunnel ────────────────────────────────────────────────────
echo ""
echo "▶ Setting up reverse tunnel: POS localhost:3000 → Mac localhost:3000..."
adb -s "$SUNMI_SERIAL" reverse tcp:3000 tcp:3000

echo "  Active rules:"
adb -s "$SUNMI_SERIAL" reverse --list | sed 's/^/    /'

# ── 5. Fetch API key from local server ────────────────────────────────────────
echo ""
echo "▶ Fetching API key from local server..."
LFI_API_KEY=""
if command -v python3 &>/dev/null; then
    AUTH_RESP=$(curl -s -X POST "http://localhost:3000/web/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"username":"test-global-admin","password":"12345","realm":"cbuae"}' 2>/dev/null)
    ACCESS_TOKEN=$(echo "$AUTH_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
    if [ -n "$ACCESS_TOKEN" ]; then
        REGEN_RESP=$(curl -s -X POST "http://localhost:3000/web/api/v1/lfis/lfi-ADCB/inbound-api-config/regenerate" \
            -H "Authorization: Bearer $ACCESS_TOKEN" \
            -H "X-LFI-ID: lfi-ADCB" \
            -H "X-Idempotency-Key: $(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo deploy-$(date +%s))" \
            -H "Content-Type: application/json" \
            -d '{"keyType":"PRIMARY","expiryDays":90}' 2>/dev/null)
        LFI_API_KEY=$(echo "$REGEN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('apiKey',''))" 2>/dev/null)
    fi
fi
if [ -n "$LFI_API_KEY" ]; then
    echo "  API key obtained: ${LFI_API_KEY:0:12}..."
else
    echo "  ⚠ Could not fetch API key (local server not running?). QR generate may fail."
    echo "    To fix: open app → Settings → Refresh"
fi

# ── 6. Write env=local + api_key to SharedPreferences ─────────────────────────
# Force-stop first so Android re-reads prefs from disk on next launch.
# Pipe the XML directly via stdin → run-as tee (avoids sdcard permission issues
# on Sunmi devices where the app user cannot read /sdcard under SELinux policy).
echo ""
echo "▶ Configuring app: env=local, wallet_id=$WALLET_ID..."

adb -s "$SUNMI_SERIAL" shell am force-stop "$PACKAGE" 2>/dev/null || true

PREFS_PATH="/data/data/$PACKAGE/shared_prefs/p3hd_pref.xml"
if [ -n "$LFI_API_KEY" ]; then
    PREFS_XML="<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>
<map>
    <string name=\"auth:env\">local</string>
    <string name=\"wallet_id\">${WALLET_ID}</string>
    <string name=\"lfi_api_key\">${LFI_API_KEY}</string>
</map>"
else
    PREFS_XML="<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>
<map>
    <string name=\"auth:env\">local</string>
    <string name=\"wallet_id\">${WALLET_ID}</string>
</map>"
fi

if printf '%s\n' "$PREFS_XML" | \
    adb -s "$SUNMI_SERIAL" shell "run-as $PACKAGE tee $PREFS_PATH" > /dev/null 2>&1; then
    echo "  SharedPreferences written (env=local)."
else
    echo "  ⚠ Could not write SharedPreferences automatically."
    echo "    Workaround: open the app → Settings → Change → select 'local' manually."
fi

# ── 7. Launch app ─────────────────────────────────────────────────────────────
echo ""
echo "▶ Launching P3HD Pay Demo..."
adb -s "$SUNMI_SERIAL" shell am start -n "$MAIN_ACTIVITY"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "────────────────────────────────────────────"
echo "✓  P3HD Pay Demo is running"
echo ""
echo "   Device  : $SUNMI_SERIAL  ($MODEL)"
echo "   Env     : local → http://localhost:3000"
echo "   Tunnel  : POS :3000 → Mac :3000  (active)"
echo "   Wallet  : $WALLET_ID"
if [ -n "$LFI_API_KEY" ]; then
    echo "   API key : ${LFI_API_KEY:0:12}... (written to prefs)"
else
    echo "   API key : ⚠ not set — open Settings → Refresh in the app"
fi
echo "────────────────────────────────────────────"
echo ""
echo "  Quick test:"
echo "    1. Tap 'QR Payment' → enter amount → 'Generate QR'"
echo "    2. Watch your local server receive POST /lfi-gateway/api/v1/qr/generate"
echo ""
echo "  Logcat (all app tags):"
echo "    adb -s $SUNMI_SERIAL logcat -s QRPayActivity:D QRDisplayActivity:D NFCPayActivity:D PaymentSuccessActivity:D SettingActivity:D"
echo ""
echo "  If tunnel drops (USB reconnect), run:"
echo "    ./deploy-local.sh --tunnel-only"
echo ""
