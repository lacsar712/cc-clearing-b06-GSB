#!/bin/sh
set -e

BACKEND_URL="${BACKEND_URL:-http://backend:8080}"
echo "Waiting for backend at ${BACKEND_URL}..."

i=0
until curl -sf "${BACKEND_URL}/api/health" >/dev/null; do
  i=$((i + 1))
  if [ "$i" -gt 60 ]; then
    echo "Backend not ready after 60 attempts"
    exit 1
  fi
  sleep 2
done
echo "Backend is up"

LOGIN=$(curl -sf -X POST "${BACKEND_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"operator","password":"op123456"}')
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "Failed to login for seed"
  echo "$LOGIN"
  exit 1
fi
AUTH="Authorization: Bearer ${TOKEN}"

MEMBERS=$(curl -sf "${BACKEND_URL}/api/members" -H "$AUTH")
COUNT=$(printf '%s' "$MEMBERS" | grep -o '"memberId"' | wc -l | tr -d ' ')
if [ "$COUNT" -gt 0 ]; then
  echo "Seed skipped: members already exist ($COUNT)"
  exit 0
fi

# Extract the first occurrence of "<key>":"<value>" from a JSON blob (no jq dependency).
json_first() {
  printf '%s' "$1" | grep -oE "\"$2\":\"[^\"]*\"" | head -1 \
    | sed "s/.*\"$2\":\"\\([^\"]*\\)\".*/\\1/"
}

post_obligation() {
  # payer payee currency amount tradeDate settleDate
  curl -sf -X POST "${BACKEND_URL}/api/obligations" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"payerMemberId\":\"$1\",\"payeeMemberId\":\"$2\",\"currency\":\"$3\",\"amount\":$4,\"tradeDate\":\"$5\",\"settleDate\":\"$6\"}" >/dev/null
}

run_netting() {
  # settleDate currency -> echoes the runId
  RESP=$(curl -sf -X POST "${BACKEND_URL}/api/netting-runs" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"settleDate\":\"$1\",\"currency\":\"$2\"}")
  json_first "$RESP" "runId"
}

echo "Seeding members..."
M1=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Alpha Bank"}')
M2=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Beta Securities"}')
M3=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Gamma Clearing"}')
# Delta has no obligations -> never appears in any run -> empty history table for acceptance.
curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Delta Holdings"}' >/dev/null

ID1=$(json_first "$M1" "memberId")
ID2=$(json_first "$M2" "memberId")
ID3=$(json_first "$M3" "memberId")

SETTLE_DATE=$(date -u +%Y-%m-%d 2>/dev/null || echo "2026-09-10")
TRADE_DATE="$SETTLE_DATE"
# Portable next-day: epoch + 86400 (busybox date supports "-d @epoch"); fallback to fixed demo date.
SETTLE_DATE_2=$(date -u -d "@$(( $(date -u +%s 2>/dev/null || echo 1789000000) + 86400 ))" +%Y-%m-%d 2>/dev/null || echo "2026-09-11")

# --- Batch A: same settleDate/USD, will be executed then SETTLED ---
# Nets: Alpha -85000, Beta +40000, Gamma +45000
echo "Seeding batch A obligations for settleDate=${SETTLE_DATE} USD..."
post_obligation "$ID1" "$ID2" USD 100000.00000000 "$TRADE_DATE" "$SETTLE_DATE"
post_obligation "$ID2" "$ID3" USD 60000.00000000  "$TRADE_DATE" "$SETTLE_DATE"
post_obligation "$ID3" "$ID1" USD 40000.00000000  "$TRADE_DATE" "$SETTLE_DATE"
post_obligation "$ID1" "$ID3" USD 25000.00000000  "$TRADE_DATE" "$SETTLE_DATE"

echo "Executing netting batch A..."
RUN_A=$(run_netting "$SETTLE_DATE" USD)
echo "Batch A runId=${RUN_A}; settling it..."
curl -sf -X POST "${BACKEND_URL}/api/netting-runs/${RUN_A}/settle" -H "$AUTH" >/dev/null

# --- Batch B: SAME settleDate/USD, a second COMPLETED run (left NOT settled) ---
# Nets: Alpha +20000, Beta -30000, Gamma +10000
# Aggregate for settleDate/USD across A+B: Alpha -65000, Beta +10000, Gamma +55000
echo "Seeding batch B obligations for settleDate=${SETTLE_DATE} USD..."
post_obligation "$ID2" "$ID1" USD 30000.00000000 "$TRADE_DATE" "$SETTLE_DATE"
post_obligation "$ID1" "$ID3" USD 10000.00000000 "$TRADE_DATE" "$SETTLE_DATE"

echo "Executing netting batch B..."
RUN_B=$(run_netting "$SETTLE_DATE" USD)
echo "Batch B runId=${RUN_B} (left COMPLETED, not settled)"

# --- Batch C: next settleDate, EUR (gives a second aggregate row per member) ---
# Nets: Alpha +2000, Beta +3000, Gamma -5000
echo "Seeding batch C obligations for settleDate=${SETTLE_DATE_2} EUR..."
post_obligation "$ID3" "$ID2" EUR 5000.00000000 "$SETTLE_DATE" "$SETTLE_DATE_2"
post_obligation "$ID2" "$ID1" EUR 2000.00000000 "$SETTLE_DATE" "$SETTLE_DATE_2"

echo "Executing netting batch C..."
RUN_C=$(run_netting "$SETTLE_DATE_2" EUR)
echo "Batch C runId=${RUN_C} (COMPLETED, not settled)"

echo ""
echo "Seed completed successfully."
echo "Historical net positions per member (only COMPLETED runs; A is settled):"
echo "  ${SETTLE_DATE} USD : Alpha -65000.00000000 (A -85000 + B +20000)"
echo "                     : Beta  +10000.00000000 (A +40000 + B -30000)"
echo "                     : Gamma +55000.00000000 (A +45000 + B +10000)"
echo "  ${SETTLE_DATE_2} EUR: Alpha  +2000.00000000 (C)"
echo "                     : Beta   +3000.00000000 (C)"
echo "                     : Gamma  -5000.00000000 (C)"
echo "  Delta Holdings: no history -> empty table"
exit 0
