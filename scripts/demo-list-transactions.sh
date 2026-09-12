#!/usr/bin/env bash
# Phase 9 bulk export demo: create several transactions, then page with limit=2
# following nextCursor until hasMore is false.
#
# Prerequisites:
#   App running (e.g. docker compose up --build -d)
#   curl, jq
#
# Usage:
#   ./scripts/demo-list-transactions.sh
#   BASE_URL=http://localhost:8081 TXN_COUNT=5 LIMIT=2 ./scripts/demo-list-transactions.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TXN_COUNT="${TXN_COUNT:-5}"
LIMIT="${LIMIT:-2}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need curl
need jq

if [[ "${TXN_COUNT}" -lt 1 ]]; then
  echo "TXN_COUNT must be >= 1" >&2
  exit 1
fi
if [[ "${LIMIT}" -lt 1 || "${LIMIT}" -gt 200 ]]; then
  echo "LIMIT must be between 1 and 200" >&2
  exit 1
fi

echo "==> Waiting for ${BASE_URL}/actuator/health/readiness"
for _ in $(seq 1 60); do
  if curl -sf "${BASE_URL}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf "${BASE_URL}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null \
  || { echo "App not ready at ${BASE_URL}" >&2; exit 1; }

EMAIL="list-demo+$(date +%s)@example.com"
echo "==> Create user (${EMAIL})"
USER_ID=$(curl -sf -X POST "${BASE_URL}/api/v1/users" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\"}" | jq -r '.data.id')
echo "    userId=${USER_ID}"

echo "==> Create ${TXN_COUNT} transactions"
CREATED=0
for i in $(seq 1 "${TXN_COUNT}"); do
  # Small amounts avoid Rule 1; after ~3 APPROVED/FLAGGED in 60s later rows may be DECLINED
  # by velocity — they still persist and appear in the list.
  AMOUNT=$((10 + i)).00
  RESP=$(curl -sf -X POST "${BASE_URL}/api/v1/transactions" \
    -H 'Content-Type: application/json' \
    -d "{\"amount\":${AMOUNT},\"userId\":\"${USER_ID}\",\"merchantId\":\"mch_list_demo\",\"category\":\"GROCERIES\"}")
  TID=$(echo "${RESP}" | jq -r '.data.transactionId')
  STATUS=$(echo "${RESP}" | jq -r '.data.status')
  echo "    [${i}/${TXN_COUNT}] ${TID} ${STATUS}"
  CREATED=$((CREATED + 1))
done

echo "==> Page GET /api/v1/transactions?userId=…&limit=${LIMIT} (follow nextCursor)"
CURSOR=""
PAGE=0
SEEN=0
SEEN_IDS=()

while true; do
  PAGE=$((PAGE + 1))
  URL="${BASE_URL}/api/v1/transactions?userId=${USER_ID}&limit=${LIMIT}"
  if [[ -n "${CURSOR}" ]]; then
    # Cursor is URL-safe base64; still encode for query safety
    ENC=$(jq -rn --arg c "${CURSOR}" '$c|@uri')
    URL="${URL}&cursor=${ENC}"
  fi

  PAGE_JSON=$(curl -sf "${URL}")
  ITEMS=$(echo "${PAGE_JSON}" | jq -c '.data.items')
  HAS_MORE=$(echo "${PAGE_JSON}" | jq -r '.data.hasMore')
  NEXT=$(echo "${PAGE_JSON}" | jq -r '.data.nextCursor // empty')
  COUNT=$(echo "${PAGE_JSON}" | jq '.data.items | length')

  echo "    page ${PAGE}: items=${COUNT} hasMore=${HAS_MORE}"
  echo "${PAGE_JSON}" | jq -r '.data.items[] | "      \(.transactionId) \(.status) \(.createdAt)"'

  while IFS= read -r id; do
    [[ -z "${id}" ]] && continue
    for prev in "${SEEN_IDS[@]+"${SEEN_IDS[@]}"}"; do
      if [[ "${prev}" == "${id}" ]]; then
        echo "FAIL: duplicate transactionId across pages: ${id}" >&2
        exit 1
      fi
    done
    SEEN_IDS+=("${id}")
    SEEN=$((SEEN + 1))
  done < <(echo "${ITEMS}" | jq -r '.[].transactionId')

  if [[ "${HAS_MORE}" == "true" ]]; then
    [[ -n "${NEXT}" ]] || { echo "FAIL: hasMore=true but nextCursor is null" >&2; exit 1; }
    CURSOR="${NEXT}"
  else
    [[ -z "${NEXT}" || "${NEXT}" == "null" ]] \
      || { echo "FAIL: hasMore=false but nextCursor is set" >&2; exit 1; }
    break
  fi
done

echo "==> Collected ${SEEN} unique transactions across ${PAGE} page(s) (created ${CREATED})"
if [[ "${SEEN}" -ne "${CREATED}" ]]; then
  echo "FAIL: expected ${CREATED} listed transactions, got ${SEEN}" >&2
  exit 1
fi

EXPECTED_PAGES=$(( (CREATED + LIMIT - 1) / LIMIT ))
if [[ "${PAGE}" -ne "${EXPECTED_PAGES}" ]]; then
  echo "FAIL: expected ${EXPECTED_PAGES} pages for limit=${LIMIT}, got ${PAGE}" >&2
  exit 1
fi

echo "OK: cursor pagination drained (hasMore=false)"
