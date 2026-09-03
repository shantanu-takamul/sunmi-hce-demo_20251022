#!/usr/bin/env bash
# pos-qr-smoke.sh — exercise the LFI-gateway calls the POS app makes, from a terminal.
#
# What it does, in order (same calls as QRPayActivity / QRDisplayActivity):
#   1. Health probe        GET  /actuator/health
#   2. Generate dynamic QR POST /lfi-gateway/api/v1/qr/generate?requestId=<uuid>
#   3. (--poll)            GET  /lfi-gateway/api/v1/transactions/history?requestId=<uuid>
#                          every 2s until SUCCESS/FAILED or --poll-timeout
#
# The rcbdc (bootstrap/sit) hosts resolve ONLY inside the CB network (CB Wi-Fi / VDI):
# from anywhere else step 1 fails with a classified DNS error — that is the expected
# outside-CB result and proves the script, not the environment.
#
# Usage:
#   ./pos-qr-smoke.sh -e bootstrap -k "$LFI_API_KEY" [-w WALLETID] [-a 100] [--poll]
#   ./pos-qr-smoke.sh -u https://host -l acq-NEOPAY -k KEY -w WALLETID
#   ./pos-qr-smoke.sh -e qa --health-only            # mechanics check from anywhere
#
# Never hardcode the API key here or commit it; pass -k or export LFI_API_KEY.
set -euo pipefail

# ---------- defaults ----------
ENV=""
BASE_URL=""
LFI_ID=""
API_KEY="${LFI_API_KEY:-}"
WALLET_ID=""
AMOUNT_FILS=100            # 1.00 AED
CURRENCY="AED"
TERMINAL_ID="TERM001"
CACERT=""                  # set automatically for rcbdc hosts
POLL=false
HEALTH_ONLY=false
POLL_TIMEOUT=300
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_CA="$SCRIPT_DIR/cbuae_root_ca.pem"

# Local config (uncommitted): supplies LFI_API_KEY so -k is optional.
# shellcheck disable=SC1091
[[ -z "$API_KEY" && -f "$SCRIPT_DIR/bootstrap.env" ]] && . "$SCRIPT_DIR/bootstrap.env" && API_KEY="${LFI_API_KEY:-}"

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

# ---------- args ----------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -e|--env)          ENV="$2"; shift 2;;
    -u|--url)          BASE_URL="$2"; shift 2;;
    -l|--lfi)          LFI_ID="$2"; shift 2;;
    -k|--key)          API_KEY="$2"; shift 2;;
    -w|--wallet)       WALLET_ID="$2"; shift 2;;
    -a|--amount)       AMOUNT_FILS="$2"; shift 2;;
    --terminal)        TERMINAL_ID="$2"; shift 2;;
    --cacert)          CACERT="$2"; shift 2;;
    --poll)            POLL=true; shift;;
    --poll-timeout)    POLL_TIMEOUT="$2"; shift 2;;
    --health-only)     HEALTH_ONLY=true; shift;;
    -h|--help)         usage;;
    *) echo "unknown arg: $1"; usage;;
  esac
done

# ---------- per-env defaults (mirror QRConfig.java) ----------
case "$ENV" in
  bootstrap)
    : "${BASE_URL:=https://bootstrap-api.rcbdc.digitaldirham.gov.ae}"
    : "${LFI_ID:=acq-NEOPAY}"
    : "${WALLET_ID:=CUAEB11BE4C2F2}"
    [[ -z "$CACERT" ]] && CACERT="$DEFAULT_CA";;
  sit)
    : "${BASE_URL:=https://sit-api.rcbdc.digitaldirham.gov.ae}"
    : "${LFI_ID:=acq-NEOPAY}"
    : "${WALLET_ID:=CUAEB11BE4C2F2}"
    [[ -z "$CACERT" ]] && CACERT="$DEFAULT_CA";;
  qa)      : "${BASE_URL:=https://mithril-qa-backend.takamul.cc}";  : "${LFI_ID:=acq-NI}";;
  dev)     : "${BASE_URL:=https://mithril-dev-backend.takamul.cc}"; : "${LFI_ID:=acq-NEOPAY}";;
  demo)    : "${BASE_URL:=https://mithril-demo-backend.takamul.cc}";: "${LFI_ID:=acq-NEOPAY}";;
  staging) : "${BASE_URL:=https://mithril-staging-backend.takamul.cc}"; : "${LFI_ID:=acq-NEOPAY}";;
  local)   : "${BASE_URL:=http://localhost:3000}"; : "${LFI_ID:=lfi-ADCB}";;
  "" )     ;;
  * ) echo "unknown env '$ENV'"; exit 1;;
esac
[[ -z "$BASE_URL" ]] && { echo "need -e <env> or -u <base_url>"; usage; }
BASE_URL="${BASE_URL%/}"

CURL_OPTS=(-sS --max-time 25)
[[ -n "$CACERT" ]] && CURL_OPTS+=(--cacert "$CACERT")

# ---------- helpers ----------
classify_curl_failure() {
  # $1 = curl exit code, $2 = stderr text
  local rc=$1 err=$2
  case $rc in
    6)  echo "DNS FAILURE — host does not resolve here. rcbdc hosts resolve only inside the CB network (CB Wi-Fi / VDI).";;
    7)  echo "CONNECT FAILURE — DNS ok but nothing answered (firewall / VIP / wrong port).";;
    28) echo "TIMEOUT — connection or response exceeded 25s (proxy in path? firewall drop?).";;
    35) echo "TLS HANDSHAKE FAILURE — protocol-level (old TLS stack, cipher mismatch, or non-TLS listener).";;
    60) echo "TLS TRUST FAILURE — server cert not signed by the CA in use ($([[ -n "$CACERT" ]] && echo "$CACERT" || echo 'system store')). Wrong CA, wrong host, or an intercepting proxy.";;
    *)  echo "curl exit $rc — $err";;
  esac
}

looks_like_html() { [[ "$1" == \<!doctype* || "$1" == \<!DOCTYPE* || "$1" == \<html* ]]; }

request() {
  # $1 method, $2 url, $3 body-or-empty, extra headers as remaining args
  local method=$1 url=$2 body=$3; shift 3
  local hdrs=() h
  for h in "$@"; do hdrs+=(-H "$h"); done
  local out http rc
  if [[ -n "$body" ]]; then
    out=$(curl "${CURL_OPTS[@]}" -X "$method" "$url" -H "Content-Type: application/json" ${hdrs[@]+"${hdrs[@]}"} -d "$body" -w $'\n%{http_code}' 2>/tmp/pos_smoke_err) || rc=$?
  else
    out=$(curl "${CURL_OPTS[@]}" -X "$method" "$url" ${hdrs[@]+"${hdrs[@]}"} -w $'\n%{http_code}' 2>/tmp/pos_smoke_err) || rc=$?
  fi
  if [[ -n "${rc:-}" ]]; then
    echo "  ✗ $(classify_curl_failure "$rc" "$(cat /tmp/pos_smoke_err)")" >&2
    return 1
  fi
  http="${out##*$'\n'}"
  RESPONSE_BODY="${out%$'\n'*}"
  RESPONSE_CODE="$http"
  return 0
}

uuid() { command -v uuidgen >/dev/null && uuidgen | tr 'A-Z' 'a-z' || cat /proc/sys/kernel/random/uuid; }

echo "── target ─────────────────────────────────────────────"
echo "  base URL : $BASE_URL"
echo "  X-LFI-ID : ${LFI_ID:-<none>}"
echo "  wallet   : ${WALLET_ID:-<none>}"
echo "  CA       : ${CACERT:-system trust store}"

# ---------- 1. health ----------
echo "── 1. health probe ────────────────────────────────────"
if request GET "$BASE_URL/actuator/health" ""; then
  echo "  HTTP $RESPONSE_CODE  ${RESPONSE_BODY:0:120}"
  if looks_like_html "$RESPONSE_BODY"; then
    echo "  ⚠ HTML page instead of JSON — a gateway/proxy answered, not the app (Cloudflare Access page on cloud envs, CB proxy error page on-prem)."
  fi
else
  echo "  Aborting — the base URL is not reachable from this machine."
  exit 2
fi
$HEALTH_ONLY && { echo "health-only mode: done."; exit 0; }

[[ -z "$API_KEY" ]]   && { echo "need -k <api key> (or export LFI_API_KEY)"; exit 1; }
[[ -z "$WALLET_ID" ]] && { echo "need -w <wallet id>"; exit 1; }
[[ -z "$LFI_ID" ]]    && { echo "need -l <lfi id>"; exit 1; }

# ---------- 2. QR generate ----------
REQUEST_ID=$(uuid)
echo "── 2. QR generate (requestId=$REQUEST_ID) ─────"
BODY=$(cat <<JSON
{"messageTypeId":"cbdc.231.101.01","qrType":"DYNAMIC","walletId":"$WALLET_ID","amount":$AMOUNT_FILS,"commissionAmount":0,"currency":"$CURRENCY","terminalId":"$TERMINAL_ID"}
JSON
)
if ! request POST "$BASE_URL/lfi-gateway/api/v1/qr/generate?requestId=$REQUEST_ID" "$BODY" \
      "X-LFI-ID: $LFI_ID" "X-LFI-API-KEY: $API_KEY"; then
  exit 2
fi
if [[ "$RESPONSE_CODE" != 200 ]]; then
  echo "  ✗ HTTP $RESPONSE_CODE"
  if looks_like_html "$RESPONSE_BODY"; then
    echo "    HTML response — gateway/proxy page, first line: $(echo "$RESPONSE_BODY" | head -1 | cut -c1-100)"
  else
    echo "    ${RESPONSE_BODY:0:400}"
  fi
  case "$RESPONSE_CODE" in
    401) echo "    → API key rejected (wrong key, wrong slot, expired, or key was rotated).";;
    403) echo "    → AUTHZ_LFI_MISMATCH? wallet '$WALLET_ID' may not belong to '$LFI_ID'.";;
    400) echo "    → validation: wallet must be the merchant's TECHNICAL wallet; amount ≥ 1 fils.";;
  esac
  exit 3
fi
EMV=$(echo "$RESPONSE_BODY" | sed -n 's/.*"emvCode":"\([^"]*\)".*/\1/p')
[[ -z "$EMV" ]] && EMV=$(echo "$RESPONSE_BODY" | sed -n 's/.*"emvPayload":"\([^"]*\)".*/\1/p')
EXPIRES=$(echo "$RESPONSE_BODY" | sed -n 's/.*"expiresAt":"\([^"]*\)".*/\1/p')
echo "  ✓ HTTP 200"
echo "    emvCode  : ${EMV:0:60}… (${#EMV} chars)"
echo "    expiresAt: $EXPIRES"
OUT="/tmp/pos_qr_$REQUEST_ID.txt"
printf '%s\n' "$EMV" > "$OUT"
echo "    full payload saved: $OUT  (feed it to the wallet's /qr/validate to test the phone side)"

# ---------- 3. poll ----------
if $POLL; then
  echo "── 3. polling history every 2s (timeout ${POLL_TIMEOUT}s) ─"
  START=$(date +%s)
  while :; do
    if request GET "$BASE_URL/lfi-gateway/api/v1/transactions/history?requestId=$REQUEST_ID" "" \
          "X-LFI-ID: $LFI_ID" "X-LFI-API-KEY: $API_KEY"; then
      STATUS=$(echo "$RESPONSE_BODY" | sed -n 's/.*"transactionStatus":"\([^"]*\)".*/\1/p' | head -1)
      printf '  %(%H:%M:%S)T  HTTP %s  status=%s\n' -1 "$RESPONSE_CODE" "${STATUS:-<no txn yet>}"
      [[ "$STATUS" == SUCCESS || "$STATUS" == FAILED ]] && { echo "  terminal: $STATUS"; exit 0; }
    fi
    (( $(date +%s) - START > POLL_TIMEOUT )) && { echo "  poll timeout (QR likely expired unpaid)"; exit 0; }
    sleep 2
  done
fi
echo "done."
