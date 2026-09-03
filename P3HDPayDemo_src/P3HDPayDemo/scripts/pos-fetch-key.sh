#!/usr/bin/env bash
# pos-fetch-key.sh — mint (REGENERATE) an acquirer's inbound LFI API key, the same flow
# the POS app's ApiKeyManager runs: portal login → inbound-api-config/regenerate.
#
# ⚠⚠ REGENERATION ROTATES THE KEY IN THE CHOSEN SLOT. Anything else holding that slot's
#    key (lfi-reference, another POS, the Business Portal) gets 401 UNAUTHORIZED until
#    it is re-paired — and the backend caches key config ~5 min, so breakage appears
#    delayed. The plaintext is shown ONCE and cannot be recovered. On shared envs
#    (qa/bootstrap/sit) run this only when you own the slot. Slot discipline:
#    POS fleet = PRIMARY, lfi-reference = SECONDARY (see POS_BOOTSTRAP_BRAIN.md §6.3).
#
# Usage:
#   ./pos-fetch-key.sh -e bootstrap -l acq-NEOPAY --slot PRIMARY --username <user>
#   ./pos-fetch-key.sh -u https://host -l acq-NI --slot SECONDARY --expiry-days 90
#
# Password is prompted (never an argument, never stored). Requires a CB-admin portal user.
set -euo pipefail

ENV=""; BASE_URL=""; LFI_ID=""; SLOT="PRIMARY"; EXPIRY_DAYS=90
USERNAME=""; REALM="cbuae"; CACERT=""; YES=false
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_CA="$SCRIPT_DIR/cbuae_root_ca.pem"

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -e|--env)       ENV="$2"; shift 2;;
    -u|--url)       BASE_URL="$2"; shift 2;;
    -l|--lfi)       LFI_ID="$2"; shift 2;;
    --slot)         SLOT="$2"; shift 2;;
    --expiry-days)  EXPIRY_DAYS="$2"; shift 2;;
    --username)     USERNAME="$2"; shift 2;;
    --realm)        REALM="$2"; shift 2;;
    --cacert)       CACERT="$2"; shift 2;;
    --yes)          YES=true; shift;;
    -h|--help)      usage;;
    *) echo "unknown arg: $1"; usage;;
  esac
done

case "$ENV" in
  bootstrap) : "${BASE_URL:=https://bootstrap-api.rcbdc.digitaldirham.gov.ae}"; [[ -z "$CACERT" ]] && CACERT="$DEFAULT_CA";;
  sit)       : "${BASE_URL:=https://sit-api.rcbdc.digitaldirham.gov.ae}";       [[ -z "$CACERT" ]] && CACERT="$DEFAULT_CA";;
  qa)        : "${BASE_URL:=https://mithril-qa-backend.takamul.cc}";;
  dev)       : "${BASE_URL:=https://mithril-dev-backend.takamul.cc}";;
  local)     : "${BASE_URL:=http://localhost:3000}";;
  "") ;;
  *) echo "unknown env '$ENV'"; exit 1;;
esac
[[ -z "$BASE_URL" ]] && { echo "need -e <env> or -u <base_url>"; usage; }
[[ -z "$LFI_ID" ]]   && { echo "need -l <lfi id>"; usage; }
[[ "$SLOT" == PRIMARY || "$SLOT" == SECONDARY ]] || { echo "--slot must be PRIMARY or SECONDARY"; exit 1; }
BASE_URL="${BASE_URL%/}"

CURL_OPTS=(-sS --max-time 25)
[[ -n "$CACERT" ]] && CURL_OPTS+=(--cacert "$CACERT")

echo "── target ──────────────────────────────────────────────"
echo "  base URL : $BASE_URL"
echo "  LFI      : $LFI_ID"
echo "  slot     : $SLOT   (expiry ${EXPIRY_DAYS}d)"
echo "  CA       : ${CACERT:-system trust store}"
echo
echo "⚠  This ROTATES $LFI_ID's $SLOT key. Any current holder of that slot's key"
echo "   (lfi-reference, another terminal, the portal) will 401 until re-paired."
if ! $YES; then
  read -r -p "Type the slot name ($SLOT) to proceed: " CONFIRM
  [[ "$CONFIRM" == "$SLOT" ]] || { echo "aborted."; exit 1; }
fi

[[ -z "$USERNAME" ]] && read -r -p "portal username: " USERNAME
read -r -s -p "portal password for $USERNAME: " PASSWORD; echo

echo "── 1. login ────────────────────────────────────────────"
LOGIN=$(curl "${CURL_OPTS[@]}" -X POST "$BASE_URL/web/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"realm\":\"$REALM\"}" \
  -w $'\n%{http_code}') || { echo "  ✗ network/TLS failure (see curl error above)"; exit 2; }
unset PASSWORD
CODE="${LOGIN##*$'\n'}"; BODY="${LOGIN%$'\n'*}"
[[ "$CODE" == 200 ]] || { echo "  ✗ HTTP $CODE — ${BODY:0:300}"; exit 3; }
TOKEN=$(echo "$BODY" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
[[ -n "$TOKEN" ]] || { echo "  ✗ no access_token in response"; exit 3; }
echo "  ✓ authenticated"

echo "── 2. regenerate $SLOT key ─────────────────────────────"
IDEM=$(command -v uuidgen >/dev/null && uuidgen || echo "$RANDOM-$RANDOM")
RESP=$(curl "${CURL_OPTS[@]}" -X POST "$BASE_URL/web/api/v1/lfis/$LFI_ID/inbound-api-config/regenerate" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Idempotency-Key: $IDEM" \
  -d "{\"keyType\":\"$SLOT\",\"expiryDays\":$EXPIRY_DAYS}" \
  -w $'\n%{http_code}') || { echo "  ✗ network/TLS failure"; exit 2; }
CODE="${RESP##*$'\n'}"; BODY="${RESP%$'\n'*}"
[[ "$CODE" == 200 ]] || { echo "  ✗ HTTP $CODE — ${BODY:0:400}"; exit 3; }
KEY=$(echo "$BODY" | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')
EXP=$(echo "$BODY" | sed -n 's/.*"expiresAt":"\([^"]*\)".*/\1/p')
echo "  ✓ new $SLOT key for $LFI_ID (expires $EXP):"
echo
echo "    $KEY"
echo
echo "  Shown once — store it now (POS Settings → API Key, or your secret store)."
echo "  If lfi-reference held this slot, re-pair it (rotate-lfi-key runbook) or screening 401s."
