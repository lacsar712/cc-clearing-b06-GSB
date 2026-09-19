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

echo "Seeding members..."
M1=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Alpha Bank"}')
M2=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Beta Securities"}')
M3=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Gamma Clearing"}')
M4=$(curl -sf -X POST "${BACKEND_URL}/api/members" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"Delta Holdings"}')

ID1=$(printf '%s' "$M1" | sed -n 's/.*"memberId":"\([^"]*\)".*/\1/p')
ID2=$(printf '%s' "$M2" | sed -n 's/.*"memberId":"\([^"]*\)".*/\1/p')
ID3=$(printf '%s' "$M3" | sed -n 's/.*"memberId":"\([^"]*\)".*/\1/p')

SETTLE_DATE=$(date -u +%Y-%m-%d 2>/dev/null || echo "2026-09-10")
SETTLE_DATE2=$(date -u -d "@$(( $(date -u +%s) + 86400 ))" +%Y-%m-%d 2>/dev/null || echo "2026-09-11")

add_obligation() {
  # payer payee currency amount settleDate
  curl -sf -X POST "${BACKEND_URL}/api/obligations" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"payerMemberId\":\"$1\",\"payeeMemberId\":\"$2\",\"currency\":\"$3\",\"amount\":$4,\"tradeDate\":\"$5\",\"settleDate\":\"$5\"}" >/dev/null
}

run_netting() {
  # settleDate currency -> prints runId
  RESP=$(curl -sf -X POST "${BACKEND_URL}/api/netting-runs" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"settleDate\":\"$1\",\"currency\":\"$2\"}")
  printf '%s' "$RESP" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p'
}

echo "Seeding obligations batch 1 (settleDate=${SETTLE_DATE} USD)..."
add_obligation "$ID1" "$ID2" "USD" "100000.00000000" "$SETTLE_DATE"
add_obligation "$ID2" "$ID3" "USD" "60000.00000000" "$SETTLE_DATE"
add_obligation "$ID3" "$ID1" "USD" "40000.00000000" "$SETTLE_DATE"
add_obligation "$ID1" "$ID3" "USD" "25000.00000000" "$SETTLE_DATE"
RUN1=$(run_netting "$SETTLE_DATE" "USD")
echo "Netting run 1 completed: ${RUN1}"

echo "Seeding obligations batch 2 (settleDate=${SETTLE_DATE} USD, second batch same day/currency)..."
add_obligation "$ID2" "$ID1" "USD" "20000.00000000" "$SETTLE_DATE"
add_obligation "$ID3" "$ID2" "USD" "15000.00000000" "$SETTLE_DATE"
RUN2=$(run_netting "$SETTLE_DATE" "USD")
echo "Netting run 2 completed: ${RUN2}"

echo "Seeding obligations batch 3 (settleDate=${SETTLE_DATE2} USD)..."
add_obligation "$ID1" "$ID2" "USD" "50000.00000000" "$SETTLE_DATE2"
add_obligation "$ID2" "$ID1" "USD" "30000.00000000" "$SETTLE_DATE2"
RUN3=$(run_netting "$SETTLE_DATE2" "USD")
echo "Netting run 3 completed: ${RUN3}"

echo "Seeding obligations batch 4 (settleDate=${SETTLE_DATE} CNY)..."
add_obligation "$ID1" "$ID3" "CNY" "12000.00000000" "$SETTLE_DATE"
add_obligation "$ID3" "$ID2" "CNY" "7000.00000000" "$SETTLE_DATE"
RUN4=$(run_netting "$SETTLE_DATE" "CNY")
echo "Netting run 4 completed: ${RUN4}"

echo "Settling run 1..."
curl -sf -X POST "${BACKEND_URL}/api/netting-runs/${RUN1}/settle" -H "$AUTH" >/dev/null
echo "Run 1 settled"

echo "Seeding obligations batch 5 (settleDate=${SETTLE_DATE} USD, left OPEN for manual netting)..."
add_obligation "$ID1" "$ID3" "USD" "9000.00000000" "$SETTLE_DATE"
add_obligation "$ID2" "$ID1" "USD" "11000.00000000" "$SETTLE_DATE"

echo "Seed completed successfully"
exit 0
