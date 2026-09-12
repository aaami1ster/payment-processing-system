#!/usr/bin/env bash
# Burst POST /api/v1/transactions for one user until HTTP 429 RATE_LIMIT_EXCEEDED,
# then confirm no extra transactions / audit_outbox rows were written for the rejected calls.
#
# Prerequisites (rate limiting must be in the *running* image):
#   docker compose up --build -d
#   jq, curl, docker
#
# Usage:
#   ./scripts/demo-rate-limit.sh
#   USER_CAPACITY=60 MAX_ATTEMPTS=90 ./scripts/demo-rate-limit.sh
#
# Note: default user limit is 60/min (payment.rate-limit.user). After ~3 APPROVED,
# further 201s are often DECLINED by Rule 2 (VELOCITY) — those still persist rows.
# Only 429 responses must leave txn/outbox counts unchanged.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
POSTGRES_USER="${POSTGRES_USER:-payments}"
POSTGRES_DB="${POSTGRES_DB:-payments}"
# Matches application.yml payment.rate-limit.user.capacity (override if you changed it)
USER_CAPACITY="${USER_CAPACITY:-60}"
# Fail fast if we blow past the configured user bucket (refill may allow +a few)
EARLY_FAIL_AFTER="${EARLY_FAIL_AFTER:-$((USER_CAPACITY + 15))}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-$((USER_CAPACITY + 30))}"
EXTRA_REJECTS="${EXTRA_REJECTS:-3}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need curl
need jq
need docker

die_rebuild() {
  echo >&2
  echo "FAIL: $1" >&2
  echo >&2
  echo "The running app likely predates Phase 7 rate limiting (or limits were raised)." >&2
  echo "Rebuild and restart, then re-run:" >&2
  echo "  docker compose up --build -d" >&2
  echo "  ./scripts/demo-rate-limit.sh" >&2
  exit 1
}

count_txn() {
  local user_id="$1"
  docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT COUNT(*) FROM transactions WHERE user_id = '${user_id}'::uuid"
}

count_outbox() {
  local user_id="$1"
  docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT COUNT(*) FROM audit_outbox o
     JOIN transactions t ON t.id = o.transaction_id
     WHERE t.user_id = '${user_id}'::uuid"
}

echo "==> Waiting for ${BASE_URL}/actuator/health/readiness"
for _ in $(seq 1 60); do
  if curl -sf "${BASE_URL}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf "${BASE_URL}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null \
  || { echo "App not ready at ${BASE_URL}" >&2; exit 1; }

echo "==> Preflight: RateLimitFilter present in app image?"
if docker compose exec -T app sh -c \
  'jar tf /app/app.jar 2>/dev/null | grep -q RateLimitFilter.class || unzip -l /app/app.jar 2>/dev/null | grep -q RateLimitFilter.class'; then
  echo "    OK — RateLimitFilter.class found"
else
  die_rebuild "RateLimitFilter.class not found inside payments-app jar"
fi

EMAIL="ratelimit+$(date +%s)@example.com"
echo "==> Create user (${EMAIL})"
USER_ID=$(curl -sf -X POST "${BASE_URL}/api/v1/users" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\"}" | jq -r '.data.id')
echo "    userId=${USER_ID}"

echo "==> Burst POST /api/v1/transactions until 429"
echo "    expect ~$((USER_CAPACITY + 1)) (user capacity=${USER_CAPACITY}; max attempts=${MAX_ATTEMPTS})"
OK_COUNT=0
DECLINED_COUNT=0
HIT_429=0
RETRY_AFTER=""
BODY_429=""

for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  TMP=$(mktemp)
  HTTP_CODE=$(curl -sS -o "${TMP}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/transactions" \
    -H 'Content-Type: application/json' \
    -D "${TMP}.hdr" \
    -d "{\"amount\":10.00,\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_burst\",\"category\":\"GROCERIES\"}")

  if [[ "${HTTP_CODE}" == "201" ]]; then
    STATUS=$(jq -r '.data.status // empty' "${TMP}")
    OK_COUNT=$((OK_COUNT + 1))
    if [[ "${STATUS}" == "DECLINED" ]]; then
      DECLINED_COUNT=$((DECLINED_COUNT + 1))
    fi
    if (( i % 10 == 0 )); then
      echo "    … ${i} requests so far (${OK_COUNT}×201)"
    fi
    rm -f "${TMP}" "${TMP}.hdr"

    if (( OK_COUNT > EARLY_FAIL_AFTER )); then
      die_rebuild "still getting HTTP 201 after ${OK_COUNT} posts (expected 429 near user capacity ${USER_CAPACITY})"
    fi
    continue
  fi

  if [[ "${HTTP_CODE}" == "429" ]]; then
    HIT_429=1
    BODY_429=$(cat "${TMP}")
    RETRY_AFTER=$(awk 'BEGIN{IGNORECASE=1} /^Retry-After:/ {print $2}' "${TMP}.hdr" | tr -d '\r')
    CODE=$(echo "${BODY_429}" | jq -r '.errors[0].code // empty')
    echo "    hit HTTP 429 on attempt ${i}"
    echo "${BODY_429}" | jq '{message, errors, meta}'
    echo "    Retry-After=${RETRY_AFTER:-<missing>}"
    rm -f "${TMP}" "${TMP}.hdr"
    if [[ "${CODE}" != "RATE_LIMIT_EXCEEDED" ]]; then
      echo "Expected errors[0].code=RATE_LIMIT_EXCEEDED, got: ${CODE}" >&2
      exit 1
    fi
    break
  fi

  echo "Unexpected HTTP ${HTTP_CODE} on attempt ${i}:" >&2
  cat "${TMP}" >&2 || true
  rm -f "${TMP}" "${TMP}.hdr"
  exit 1
done

if [[ "${HIT_429}" -ne 1 ]]; then
  die_rebuild "no HTTP 429 within ${MAX_ATTEMPTS} attempts"
fi

TXN_AFTER_429=$(count_txn "${USER_ID}")
OUTBOX_AFTER_429=$(count_outbox "${USER_ID}")
echo "==> Counts right after first 429"
echo "    transactions=${TXN_AFTER_429}  outbox=${OUTBOX_AFTER_429}  (201 responses=${OK_COUNT}, of which DECLINED≈${DECLINED_COUNT})"

if [[ "${TXN_AFTER_429}" -ne "${OK_COUNT}" ]]; then
  echo "Expected transaction count ${OK_COUNT} (one per HTTP 201), got ${TXN_AFTER_429}" >&2
  exit 1
fi
if [[ "${OUTBOX_AFTER_429}" -ne "${OK_COUNT}" ]]; then
  echo "Expected outbox count ${OK_COUNT} (one per persisted txn), got ${OUTBOX_AFTER_429}" >&2
  exit 1
fi

echo "==> Fire ${EXTRA_REJECTS} more requests (expect 429, counts unchanged)"
for j in $(seq 1 "${EXTRA_REJECTS}"); do
  TMP=$(mktemp)
  HTTP_CODE=$(curl -sS -o "${TMP}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/transactions" \
    -H 'Content-Type: application/json' \
    -d "{\"amount\":10.00,\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_burst\",\"category\":\"GROCERIES\"}")
  CODE=$(jq -r '.errors[0].code // empty' "${TMP}")
  rm -f "${TMP}"
  if [[ "${HTTP_CODE}" != "429" || "${CODE}" != "RATE_LIMIT_EXCEEDED" ]]; then
    echo "Expected another 429 RATE_LIMIT_EXCEEDED, got HTTP ${HTTP_CODE} code=${CODE}" >&2
    exit 1
  fi
  echo "    extra reject ${j}: HTTP 429 RATE_LIMIT_EXCEEDED"
done

TXN_FINAL=$(count_txn "${USER_ID}")
OUTBOX_FINAL=$(count_outbox "${USER_ID}")
echo "==> Final counts"
echo "    transactions=${TXN_FINAL}  outbox=${OUTBOX_FINAL}"

if [[ "${TXN_FINAL}" -ne "${TXN_AFTER_429}" || "${OUTBOX_FINAL}" -ne "${OUTBOX_AFTER_429}" ]]; then
  echo "FAIL: 429 path wrote extra rows (txn ${TXN_AFTER_429}→${TXN_FINAL}, outbox ${OUTBOX_AFTER_429}→${OUTBOX_FINAL})" >&2
  exit 1
fi

echo
echo "Rate-limit burst OK: 429 RATE_LIMIT_EXCEEDED with no extra txn/outbox rows."
