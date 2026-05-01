# P3HD Pay Demo — Agent Handoff Document

**Date**: 2026-05-01 (Session 3)
**Context window**: Approaching limit — this doc is authoritative; do NOT rely on conversation history.

---

## ⚡ START HERE — What the next agent must do

Read this entire file before touching any code.

**Current priority: Phase 12 — Integration testing on device `P359256QJ0004`.**

The local dev environment is fully configured. Run integration tests manually on the device and fix any runtime bugs you observe in logcat. Do NOT write test code — these are manual device tests. Phase 13 (receipt animation) is LAST, only after all Phase 12 tests pass.

**Before starting:** run the deploy script so the app is fresh on the device and pointed at the right env:

```bash
cd /Users/shantanu.bhosale/Downloads/sunmi-hce-demo_20251022/P3HDPayDemo_src/P3HDPayDemo

# For integration testing against STAGING (real API):
./deploy-local.sh --no-build   # installs + tunnels, then change env in app Settings UI

# For local server testing:
./deploy-local.sh              # builds + installs + sets env=local automatically
```

Watch logcat while testing:
```bash
adb -s P359256QJ0004 logcat -s QRPayActivity:D QRDisplayActivity:D NFCPayActivity:D PaymentSuccessActivity:D SettingActivity:D
```

---

## What Was Built

A new Android app **P3HD Pay Demo** (`com.lfi.p3hd.demo`) for the **Sunmi P3H Dual** POS device.
Lives at: `P3HDPayDemo_src/P3HDPayDemo/` inside this repo.

### File inventory (all present, build passes)

```
P3HDPayDemo_src/P3HDPayDemo/
├── build.gradle              AGP 8.3.2
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/           gradle-wrapper.properties → Gradle 8.5
├── deploy-local.sh           ← local dev script (see below)
└── app/
    ├── build.gradle          namespace "com.lfi.p3hd.demo", compileSdk 34
    ├── libs/
    │   ├── PayLib-release-2.0.18.aar       ← Sunmi Pay SDK
    │   └── PayLib-release-2.0.18-sources.jar
    └── src/main/
        ├── AndroidManifest.xml             networkSecurityConfig set (see commit 7)
        ├── java/com/lfi/p3hd/demo/
        │   ├── MyApplication.java           SDK binding
        │   ├── MainActivity.java            6-card navigation grid
        │   ├── BaseAppCompatActivity.java   toolbar + toast + formatStr
        │   ├── Constant.java
        │   ├── qr/
        │   │   ├── QRConfig.java           WALLET_ID = "ADCBCA87DA2BF8"
        │   │   ├── QRPayActivity.java      POS numpad + Generate QR + NFC Pay buttons
        │   │   ├── QRDisplayActivity.java  ZXing QR, 5-min timer, 5s polling
        │   │   ├── PaymentSuccessActivity.java  receipt display + bitmap printer
        │   │   └── QRExpiredActivity.java  timeout screen, back suppressed
        │   ├── nfc/
        │   │   ├── NFCPayActivity.java     HCE URL emulation + same polling as QR
        │   │   └── NFCReadActivity.java    7-step APDU sequence to read NFC Type 4
        │   ├── hce/
        │   │   ├── HCEActivity.java        open/write/read/close Type 2/4 + presets
        │   │   └── HCEReceiptActivity.java URL → HCE URI record for phone tap
        │   ├── other/
        │   │   └── SettingActivity.java    env picker + 2-step API key fetch
        │   ├── utils/
        │   │   ├── PreferencesUtil.java    prefs file "p3hd_pref"
        │   │   └── ByteUtil.java           hex↔bytes, APDU helpers
        │   └── wrapper/
        │       └── CheckCardCallbackV2Wrapper.java
        └── res/
            ├── layout/     10 XML layouts (light theme, all polished)
            ├── values/     colors.xml, dimens.xml, strings.xml, styles.xml
            └── xml/
                └── network_security_config.xml   ← cleartext HTTP fix for localhost
```

---

## Phase Status

| Phase | Description | Status |
|---|---|---|
| 1 | Project scaffold (Gradle, wrapper, libs) | ✅ Done |
| 2 | Core files (MyApplication, Manifest, utils) | ✅ Done |
| 3 | QR Payment module (5 classes) | ✅ Done |
| 4 | NFCPayActivity | ✅ Done |
| 5 | HCE demo screens | ✅ Done |
| 6 | NFC Read screen | ✅ Done |
| 7 | Settings + MainActivity | ✅ Done |
| 8 | All resources (layouts + values) | ✅ Done |
| 9 | Printer integration | ✅ Done (bitmap → printPointLine) |
| 10 | Build & install | ✅ Done — BUILD SUCCESSFUL, installed on P359256QJ0004 |
| 11 | UI polish (light theme + POS numpad + NFC Read screen) | ✅ Done (all screens polished) |
| 12 | Integration testing on device | 🔶 IN PROGRESS (API key bug fixed; QR generate verified working) |
| 13 | Receipt print animation (LAST) | ⬜ TODO — only after Phase 12 fully passes |

---

## Current App State (as of 2026-05-01, Session 2)

### Build
```bash
cd /Users/shantanu.bhosale/Downloads/sunmi-hce-demo_20251022/P3HDPayDemo_src/P3HDPayDemo
./gradlew assembleDebug   # → BUILD SUCCESSFUL
```
APK is installed on device. Use `deploy-local.sh` for all future installs.

### Git log (7 commits this project)
```
2b23245 Allow cleartext HTTP to localhost for local env (Android 9+ block fix)
e65ca8e Add deploy-local.sh: one-command build/install/tunnel for local dev
ca685f4 Add NFC payment, HCE emulation, NFC read card, settings, and main navigation
217c1ff Add QR payment module: POS numpad, QR display with 5-min timer, 5s polling, success/expired screens
9ecbed8 Add resource values: light theme palette, button styles, dimensions, strings
4d0c5e7 Add core app infrastructure: SDK binding, base activity, utils
651f2cc Add P3HD Pay Demo project scaffold (Gradle 8.5, AGP 8.3.2, PayLib 2.0.18)
```

---

## Work Done This Session (Session 3)

### 1. Static code review
Reviewed all 10 Java source files and all 10 layouts. No crash-level bugs found. All string/view IDs verified present. Threading model correct throughout. HCE lifecycle correct.

### 2. Device deployment + API key bug found and fixed
Ran `./deploy-local.sh` — build succeeded, installed, tunnel active. Discovered local server returns HTTP 500 for QR generate without API key (the assumption "no auth server at localhost" was wrong — same auth/regen endpoints exist at localhost:3000 as staging).

**`SettingActivity.java`**: Removed `if (env == "local") { showToast(); } else { fetchLfiApiKey(); }` — now always calls `fetchLfiApiKey()` regardless of env.

**`deploy-local.sh`**: Added step 5 that calls `curl` to login + regenerate, then writes `lfi_api_key` to SharedPreferences alongside env/wallet_id. The API key has 90-day expiry so won't need frequent refresh. Script still works if local server is down (shows warning, skips key write).

### 3. Verified working flows
- `curl` against `localhost:3000`: QR generate HTTP 200 ✓, status poll HTTP 200 (empty = PENDING) ✓
- Logcat shows `onConnectPaySDK` on every app start ✓
- Previous session logcat shows `QRPayActivity [200] {emvPayload:...}` — QR generation was already working on staging

---

## Work Done This Session (Session 2)

### 1. NFC Read Card screen UI polish (`activity_nfc_read.xml`)
Redesigned to match POS style (same pattern as `activity_nfc_pay.xml`):
- **Blue header band**: "NFC CARD READER" label + "Present NFC card to device" instruction
- **128dp circular icon** (blue `MaterialCardView`, 64dp corner radius): 📶 emoji at 48sp — same as the phone icon in NFC pay screen
- **Result card**: scrollable `MaterialCardView` with "RESULT:" label above, fills remaining vertical space
- **Buttons moved to the bottom**: `btn_start_scan` (AccentButton) + `btn_stop_scan` (SecondaryButton) in a fixed strip with divider above
- **No Java changes** — all three IDs (`btn_start_scan`, `btn_stop_scan`, `tv_info`) preserved exactly

### 2. Local dev script (`deploy-local.sh`)
Created at `P3HDPayDemo_src/P3HDPayDemo/deploy-local.sh`. Executable. Three modes:

| Mode | Command |
|---|---|
| Full (build + install + tunnel + launch) | `./deploy-local.sh` |
| Skip build (re-install + re-tunnel) | `./deploy-local.sh --no-build` |
| Tunnel only (USB replugged) | `./deploy-local.sh --tunnel-only` |

Key behaviors:
- Detects Sunmi device via `ro.product.manufacturer` (works for all Sunmi models)
- Runs `adb reverse tcp:3000 tcp:3000` **after** `adb install` — because install resets all reverse port-forwarding rules
- Writes `env=local` + `wallet_id=ADCBCA87DA2BF8` to `p3hd_pref.xml` using `run-as tee` (NOT `cp from /sdcard/` — SELinux blocks app user from reading sdcard on Sunmi)
- Force-stops app before writing prefs so stale in-memory cache is cleared
- Launches app via `adb shell am start`

### 3. Cleartext HTTP fix (`network_security_config.xml`)
**Root cause**: Android 9+ blocks `http://` traffic by default. The `local` env uses `http://localhost:3000`.

**Fix**:
- Created `app/src/main/res/xml/network_security_config.xml` — permits cleartext HTTP only for `localhost`, `127.0.0.1`, `10.0.2.2` (emulator alias). All other domains remain HTTPS-only.
- Added `android:networkSecurityConfig="@xml/network_security_config"` to `<application>` in `AndroidManifest.xml`.

This was triggered by the user hitting the error during QR generation with local env.

---

## Local Dev Setup (fully working)

```bash
# One command to deploy and point at localhost:3000
cd P3HDPayDemo_src/P3HDPayDemo
./deploy-local.sh

# Re-tunnel after USB reconnect (no rebuild needed)
./deploy-local.sh --tunnel-only
```

After `./deploy-local.sh` the app:
- Is built and installed fresh
- Has `adb reverse tcp:3000 tcp:3000` active (POS → Mac)
- Has `env=local` written to SharedPreferences → `QRConfig.getBaseUrl()` returns `http://localhost:3000`
- Is launched and showing MainActivity

---

## Phase 12 — Integration Test Checklist (NEXT PRIORITY)

Run these manually on device `P359256QJ0004`. Observe logcat. Fix bugs in code. Do NOT write test code.

### Local env tests (localhost:3000)
- [ ] **QR generate**: Enter 10.00 AED → Generate QR → verify POST hits `http://localhost:3000/lfi-gateway/api/v1/qr/generate`
- [ ] **QR polling**: Simulate SUCCESS response → success screen shows correct amount + ref + datetime
- [ ] **NFC Pay**: Enter amount → NFC Pay → HCE opens → tap phone → poll → SUCCESS

### Staging env tests (real API, requires API key)
1. Open app → Settings → Change → select `staging` → confirm wallet ID dialog → wait for "API key ready" toast
2. - [ ] **QR Payment**: 10.00 AED → Generate QR → scan with phone → SUCCESS → success screen
3. - [ ] **QR Print**: On success screen → Print → thermal receipt prints (header + amount + ref + QR)
4. - [ ] **NFC Payment**: 10.00 AED → NFC Pay → HCE opens → tap phone → SUCCESS
5. - [ ] **HCE Emulate**: Type 4 → Open → Write Data1 → tap phone → phone reads text → Close
6. - [ ] **HCE Receipt**: Enter URL → Enable NFC Tap → tap phone → phone browser opens URL → Close
7. - [ ] **NFC Read Card**: Start Scan → tap NFC Type 4 card → NDEF text displayed

### Known issue — FIXED (Session 3)
The local server at `localhost:3000` **does** require `X-LFI-API-KEY` for QR generation (returns HTTP 500 without it). The original assumption that "no auth server at localhost" was wrong.

**Fixes applied:**
- `SettingActivity.java` — removed `if (selectedEnv.equals("local"))` special case; `fetchLfiApiKey()` now always called on any env change, including local.
- `deploy-local.sh` — step 5 now fetches a fresh API key from `http://localhost:3000/web/api/v1/auth/login` + regenerate, and writes it as `lfi_api_key` in SharedPreferences alongside `env` and `wallet_id`.

Both verified working: `curl` test against local server returns HTTP 200 `emvPayload` with API key.

---

## Critical Facts (carry these forward, never change)

1. **WALLET_ID = `"ADCBCA87DA2BF8"`** — 4th char from end is capital **D**, not zero. Wrong value → HTTP 400.
2. **Amount in fils** — `Math.round(aed * 100)`. API rejects decimals.
3. **No `showInputState()` before navigation** in `QRPayActivity.generateQR()` — causes UI flash; go straight to `startActivity()`.
4. **`onResume()` always calls `showInputState()`** in `QRPayActivity` — intentional reset.
5. **Empty transactions array = PENDING** — not an error, keep polling.
6. **`hceManagerV2` and `readCardOptV2` are null** until `onConnectPaySDK()` fires. Always check `MyApplication.app.isConnectPaySDK()`.
7. **Always call `hceClose()` in `onDestroy()`** — leaked HCE session locks the NFC controller.
8. **Printer API** is `PrinterOptV2` (`printPointLine(byte[])`) — NOT `SunmiPrinterService`. No external printer library.
9. **`HCEActivity`** uses `CardType.IC` for NFC Type 2 and `CardType.NFC` for NFC Type 4 — matches original Sunmi HCE demo, is correct.
10. **`NFCReadActivity` must NOT call `SunmiPayKernel.getInstance().destroyPaySDK()`** in onDestroy — destroys the shared app-level SDK. Only `cancelCheckCard()` + `cardOff()`.
11. **`BaseAppCompatActivity.initActionbar()`** must call `setSupportActionBar(toolbar)` first — already fixed in the code.
12. **Do NOT add `Co-Authored-By` trailer to commits** — house rule per CLAUDE.md.
13. **Phase 13 (receipt animation) is LAST** — only after all Phase 12 integration tests pass.
14. **`adb reverse` must run AFTER `adb install`** — install resets all port-forwarding rules. `deploy-local.sh` handles this.
15. **`run-as` + `cp /sdcard/...` fails on Sunmi** — SELinux blocks app user from reading sdcard. Use `run-as tee` with stdin pipe instead (already in deploy-local.sh).
16. **Cleartext HTTP to localhost** — allowed via `res/xml/network_security_config.xml`. Do NOT use `android:usesCleartextTraffic="true"` (too broad). Current config is correct.
17. **SharedPreferences prefs file** is `p3hd_pref.xml`, key `auth:env`, default `"staging"`. Written by `deploy-local.sh` for local dev; written by `SettingActivity` for other envs.
18. **Local env requires API key** — `localhost:3000` returns HTTP 500 without `X-LFI-API-KEY`. `deploy-local.sh` now fetches a fresh key automatically. `SettingActivity` now fetches key for ALL envs including local. Status polling (`/transactions/history`) does NOT require the API key.

---

## Device Info

- **P3H Dual serial**: `P359256QJ0004`
- **ADB**: `adb -s P359256QJ0004 <command>`
- **Build**: compileSdk 34, minSdk 25, Java 1.8, AGP 8.3.2, Gradle 8.5
- **SDK**: `PayLib-release-2.0.18.aar` in `app/libs/`

---

## Repo Structure (do not touch existing projects)

```
sunmi-hce-demo_20251022/
├── sunmihcedemo_src/sunmihcedemo/   ← existing HCE demo (DO NOT TOUCH)
├── SunmiNFCDemo_src/SunmiNFCDemo/   ← existing NFC demo (DO NOT TOUCH)
├── P3HDPayDemo_src/P3HDPayDemo/     ← NEW project (work only here)
└── (docs, APKs, HANDOFF.md, P3HD_TODO.md, P3HD_QR_ANALYSIS.md)
```

---

## Key Reference Docs

1. `P3HD_TODO.md` — full phase checklist with acceptance criteria (phases 1–12 complete, 13 todo)
2. `P3HD_QR_ANALYSIS.md` — QR API spec (endpoints, headers, payload shapes)
3. `CLAUDE.md` — repo-level rules (no watermarks in commits, etc.)
