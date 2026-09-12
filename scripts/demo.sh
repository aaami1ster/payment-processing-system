#!/usr/bin/env bash
# End-to-end demo against a running Compose stack (app + Postgres + Mongo).
# Usage:
#   docker compose up --build -d
#   ./scripts/demo.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
POSTGRES_USER="${POSTGRES_USER:-payments}"
POSTGRES_DB="${POSTGRES_DB:-payments}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need curl
need jq
need docker

echo "==> Waiting for ${BASE_URL}/actuator/health/readiness"
for _ in $(seq 1 60); do
  if curl -sf "${BASE_URL}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf "${BASE_URL}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null \
  || { echo "App not ready at ${BASE_URL}" >&2; exit 1; }

EMAIL="demo+$(date +%s)@example.com"
echo "==> Create user (${EMAIL})"
USER_JSON=$(curl -sf -X POST "${BASE_URL}/api/v1/users" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\"}")
USER_ID=$(echo "${USER_JSON}" | jq -r '.data.id')
echo "    userId=${USER_ID}"

echo "==> APPROVED small payment"
TXN1=$(curl -sf -X POST "${BASE_URL}/api/v1/transactions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-script-1' \
  -d "{\"amount\":100.00,\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}")
echo "${TXN1}" | jq '{status: .data.status, transactionId: .data.transactionId}'
TXN1_ID=$(echo "${TXN1}" | jq -r '.data.transactionId')

echo "==> Idempotent replay (same key + body)"
TXN1_REPLAY=$(curl -sf -X POST "${BASE_URL}/api/v1/transactions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-script-1' \
  -d "{\"amount\":100.00,\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}")
REPLAY_ID=$(echo "${TXN1_REPLAY}" | jq -r '.data.transactionId')
[[ "${REPLAY_ID}" == "${TXN1_ID}" ]] || { echo "Idempotency replay mismatch" >&2; exit 1; }
echo "    replayed transactionId=${REPLAY_ID}"

echo "==> DECLINED high amount without pre-approval"
curl -sf -X POST "${BASE_URL}/api/v1/transactions" \
  -H 'Content-Type: application/json' \
  -d "{\"amount\":12000.00,\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}" \
  | jq '{status: .data.status, rulesTriggered: .data.rulesTriggered}'

echo "==> Raise limit, then FLAGGED (new user + high amount)"
curl -sf -X PATCH "${BASE_URL}/api/v1/users/${USER_ID}" \
  -H 'Content-Type: application/json' \
  -d '{"preApprovedTransactionLimit":15000}' >/dev/null
TXN_FLAG=$(curl -sf -X POST "${BASE_URL}/api/v1/transactions" \
  -H 'Content-Type: application/json' \
  -d "{\"amount\":12000.00,\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}")
echo "${TXN_FLAG}" | jq '{status: .data.status, rulesTriggered: .data.rulesTriggered}'
TXN_FLAG_ID=$(echo "${TXN_FLAG}" | jq -r '.data.transactionId')

echo "==> GET transaction"
curl -sf "${BASE_URL}/api/v1/transactions/${TXN_FLAG_ID}" \
  | jq '{status: .data.status, transactionId: .data.transactionId}'

echo "==> Wait for outbox → Mongo audit"
for _ in $(seq 1 30); do
  STATUS=$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT status FROM audit_outbox WHERE transaction_id = '${TXN1_ID}'::uuid" 2>/dev/null || true)
  if [[ "${STATUS}" == "PUBLISHED" ]]; then
    break
  fi
  sleep 1
done

echo "==> Postgres outbox (recent)"
docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c \
  "SELECT transaction_id, status, attempts FROM audit_outbox ORDER BY id DESC LIMIT 5;"

echo "==> Mongo audit_logs (sample)"
docker compose exec -T mongo mongosh payments_audit --quiet --eval \
  'db.audit_logs.find({}, {_id:1, decision:1, userId:1}).limit(3).toArray()'

echo "==> Prometheus payment metrics (sample)"
curl -sf "${BASE_URL}/actuator/prometheus" | grep -E 'payment_transactions_total|outbox_pending' | head -20 || true

echo
echo "Demo OK. Swagger UI: ${BASE_URL}/swagger-ui.html"
echo "Bruno: cd bruno && npx @usebruno/cli run --env Local"
