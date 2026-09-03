#!/usr/bin/env bash
# pos-tls-check.sh — verify the TLS chain a host actually serves against our internal CA PEM.
# Run this ON CB STAFF WI-FI before anything else: it answers, in one screen, whether the
# app's bundled cbuae-CBDC-CA trust will work (the swagger curl used --insecure, so that
# proof is still missing).
#
# Usage: ./pos-tls-check.sh [host]           (default: bootstrap-api.rcbdc.digitaldirham.gov.ae)
#        ./pos-tls-check.sh bootstrap-api-lfi.rcbdc.digitaldirham.gov.ae
set -euo pipefail
HOST="${1:-bootstrap-api.rcbdc.digitaldirham.gov.ae}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CA="$SCRIPT_DIR/cbuae_root_ca.pem"
OUT="/tmp/tls_chain_${HOST}.txt"

echo "── served certificate chain ($HOST:443) ──"
if ! openssl s_client -connect "$HOST:443" -servername "$HOST" -showcerts </dev/null >"$OUT" 2>&1; then
  grep -m1 -E "connect|errno|refused|timed" "$OUT" || true
  echo "✗ TCP/TLS connection failed — are you on CB staff Wi-Fi?"
  exit 2
fi
grep -E "^ (0|1|2) s:|^   i:" "$OUT" | sed 's/^/  /' || grep -E "s:|i:" "$OUT" | head -6 | sed 's/^/  /'

echo "── verification against $CA ──"
VERIFY=$(openssl s_client -connect "$HOST:443" -servername "$HOST" -CAfile "$CA" </dev/null 2>&1 | grep "Verify return code")
echo "  $VERIFY"
case "$VERIFY" in
  *"0 (ok)"*) echo "  ✓ chain validates against cbuae-CBDC-CA — the app's bundled trust WILL work";;
  *"unable to get local issuer"*|*"unable to verify"*) echo "  ✗ served chain does NOT reach our root — either an intermediate is missing from the server chain, or a different CA fronts this host (intercepting proxy?). Full chain saved: $OUT";;
  *) echo "  ✗ see $OUT for the full handshake";;
esac

echo "── leaf details (SAN + validity) ──"
openssl x509 -in <(awk '/BEGIN CERT/{f=1} f{print} /END CERT/{exit}' "$OUT") -noout -subject -dates 2>/dev/null | sed 's/^/  /'
openssl x509 -in <(awk '/BEGIN CERT/{f=1} f{print} /END CERT/{exit}' "$OUT") -noout -text 2>/dev/null | grep -A1 "Subject Alternative Name" | sed 's/^/  /'

echo "── device-clock sanity (server Date vs local) ──"
SERVER_DATE=$(curl -sI --max-time 10 --insecure "https://$HOST/actuator/health" 2>/dev/null | grep -i '^date:' | cut -d' ' -f2-)
echo "  server: ${SERVER_DATE:-<no response>}"
echo "  local : $(date -u '+%a, %d %b %Y %H:%M:%S GMT')"
echo
echo "full chain transcript: $OUT"
