#!/usr/bin/env bash
# Interactive feature demo for the Payment Processing System.
# Walk through challenge + optional features one-by-one (or jump by number).
#
# Prerequisites:
#   docker compose up --build -d
#   curl, jq, docker
#
# Usage:
#   ./scripts/demo-interactive.sh
#   BASE_URL=http://localhost:8081 ./scripts/demo-interactive.sh
#
# At the prompt:
#   <number>   run that feature (e.g. 3 or 03)
#   next       run the next feature in order
#   list       show the menu again
#   status     show demo session state (userId, last txn, …)
#   prep       show prep steps for optional features (Redis, webhooks)
#   quit       exit
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
POSTGRES_USER="${POSTGRES_USER:-payments}"
POSTGRES_DB="${POSTGRES_DB:-payments}"
MONGO_DB="${MONGO_DB:-payments_audit}"

# Session state (shared across steps when useful)
DEMO_USER_ID=""
DEMO_USER_EMAIL=""
LAST_TXN_ID=""
LAST_IDEMPOTENCY_KEY=""
CURSOR_INDEX=0   # for "next" — 0-based into FEATURE_IDS

# ---------------------------------------------------------------------------
# Terminal helpers
# ---------------------------------------------------------------------------

if [[ -t 1 ]] && command -v tput >/dev/null 2>&1 && [[ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]]; then
  C_BOLD=$(tput bold)
  C_DIM=$(tput dim)
  C_RED=$(tput setaf 1)
  C_GREEN=$(tput setaf 2)
  C_YELLOW=$(tput setaf 3)
  C_CYAN=$(tput setaf 6)
  C_RESET=$(tput sgr0)
else
  C_BOLD="" C_DIM="" C_RED="" C_GREEN="" C_YELLOW="" C_CYAN="" C_RESET=""
fi

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "${C_RED}Missing required command: $1${C_RESET}" >&2
    exit 1
  }
}

hr() { printf '%s\n' "${C_DIM}────────────────────────────────────────────────────────────${C_RESET}"; }

banner() {
  clear 2>/dev/null || true
  echo
  echo "${C_BOLD}${C_CYAN}Payment Processing System — Interactive Demo${C_RESET}"
  echo "${C_DIM}Challenge features + optional enhancements, step by step.${C_RESET}"
  echo "${C_DIM}Base URL: ${BASE_URL}${C_RESET}"
  echo
}

section_title() {
  local title="$1"
  echo
  hr
  echo "${C_BOLD}${C_CYAN}Testing: ${title}${C_RESET}"
  hr
}

will_do() {
  echo
  echo "${C_BOLD}What this does${C_RESET}"
  echo "  $1"
}

expect_result() {
  echo
  echo "${C_BOLD}Expected result${C_RESET}"
  echo "  $1"
}

prep_box() {
  echo
  echo "${C_YELLOW}${C_BOLD}Preparation required${C_RESET}"
  while IFS= read -r line; do
    echo "  ${C_YELLOW}${line}${C_RESET}"
  done
}

show_request() {
  echo
  echo "${C_BOLD}Request${C_RESET}"
  while IFS= read -r line; do
    echo "  ${C_DIM}${line}${C_RESET}"
  done
}

show_response() {
  echo
  echo "${C_BOLD}Response${C_RESET}"
  if command -v jq >/dev/null 2>&1 && echo "$1" | jq empty 2>/dev/null; then
    echo "$1" | jq .
  else
    echo "$1"
  fi
}

pass() {
  echo
  echo "${C_GREEN}${C_BOLD}Result: PASS${C_RESET} — $1"
}

fail() {
  echo
  echo "${C_RED}${C_BOLD}Result: FAIL${C_RESET} — $1"
  return 1
}

info() { echo "  ${C_DIM}$*${C_RESET}"; }

# After title / description / expected (and optional prep), wait for audience.
# Returns 0 to run the step, 1 if aborted (caller should return 0).
confirm_or_abort() {
  echo
  read -r -p "${C_DIM}Press Enter to continue (or type skip to abort this step)… ${C_RESET}" ans || true
  if [[ "${ans}" == "skip" ]]; then
    info "Skipped."
    return 1
  fi
  return 0
}

# HTTP helper: sets HTTP_CODE, HTTP_HEADERS, HTTP_BODY.
# Do NOT call inside $() — that runs a subshell and drops the globals.
HTTP_CODE=""
HTTP_HEADERS=""
HTTP_BODY=""
http() {
  # usage: http METHOD URL [curl-args...]
  local method="$1" url="$2"
  shift 2
  local tmp hdr
  tmp=$(mktemp)
  hdr=$(mktemp)
  set +e
  HTTP_CODE=$(curl -sS -o "${tmp}" -w "%{http_code}" -D "${hdr}" -X "${method}" "${url}" "$@")
  local curl_rc=$?
  set -e
  HTTP_BODY=$(cat "${tmp}")
  HTTP_HEADERS=$(cat "${hdr}")
  rm -f "${tmp}" "${hdr}"
  if [[ "${curl_rc}" -ne 0 ]]; then
    HTTP_BODY="{\"error\":\"curl failed\",\"exitCode\":${curl_rc}}"
    HTTP_CODE="000"
  fi
}

header_value() {
  # case-insensitive header lookup from HTTP_HEADERS
  local name="$1"
  awk -v n="$(echo "${name}" | tr '[:upper:]' '[:lower:]')" '
    BEGIN { IGNORECASE=1 }
    {
      line=$0
      sub(/\r$/, "", line)
      split(line, a, /: */)
      key=tolower(a[1])
      if (key == n) { print a[2]; exit }
    }
  ' <<<"${HTTP_HEADERS}"
}

wait_ready() {
  echo "${C_BOLD}Checking app readiness…${C_RESET}"
  local i
  for i in $(seq 1 60); do
    if curl -sf "${BASE_URL}/actuator/health/readiness" 2>/dev/null | jq -e '.status == "UP"' >/dev/null 2>&1; then
      echo "  ${C_GREEN}Ready${C_RESET} at ${BASE_URL}"
      return 0
    fi
    sleep 2
  done
  echo "${C_RED}App not ready at ${BASE_URL}${C_RESET}" >&2
  echo "Start the stack: docker compose up --build -d" >&2
  exit 1
}

# ---------------------------------------------------------------------------
# Shared fixtures
# ---------------------------------------------------------------------------

create_fresh_user() {
  local prefix="${1:-demo}"
  local email="${prefix}+$(date +%s%N | cut -c1-13)@example.com"
  http POST "${BASE_URL}/api/v1/users" \
    -H 'Content-Type: application/json' \
    -H "X-Request-Id: demo-create-user-$(date +%s)" \
    -d "{\"email\":\"${email}\",\"kycStatus\":\"PENDING\"}"
  if [[ "${HTTP_CODE}" != "201" ]]; then
    echo "${HTTP_BODY}" >&2
    fail "Could not create user (HTTP ${HTTP_CODE})"
    return 1
  fi
  DEMO_USER_ID=$(echo "${HTTP_BODY}" | jq -r '.data.id')
  DEMO_USER_EMAIL="${email}"
}

ensure_user() {
  if [[ -z "${DEMO_USER_ID}" ]]; then
    info "No session user yet — creating one…"
    create_fresh_user "session"
    info "userId=${DEMO_USER_ID}  email=${DEMO_USER_EMAIL}"
  fi
}

patch_user_limit() {
  local limit="$1"
  http PATCH "${BASE_URL}/api/v1/users/${DEMO_USER_ID}" \
    -H 'Content-Type: application/json' \
    -d "{\"preApprovedTransactionLimit\":${limit}}"
}

post_txn() {
  # args: amount category [idempotency-key] [merchant]
  # Sets HTTP_CODE / HTTP_BODY (do not capture via $()).
  local amount="$1" category="$2"
  local key="${3:-}"
  local merchant="${4:-mch_demo}"
  local args=(-H 'Content-Type: application/json' -H "X-Request-Id: demo-txn-$(date +%s%N | cut -c1-13)")
  if [[ -n "${key}" ]]; then
    args+=(-H "Idempotency-Key: ${key}")
  fi
  http POST "${BASE_URL}/api/v1/transactions" "${args[@]}" \
    -d "{\"amount\":${amount},\"userId\":\"${DEMO_USER_ID}\",\"merchantId\":\"${merchant}\",\"category\":\"${category}\"}"
}

# ---------------------------------------------------------------------------
# Feature catalog (order = "next" order)
# id|short-name|title
# ---------------------------------------------------------------------------

FEATURES=(
  "1|health|Health & readiness"
  "2|users|User management (create / get / list / update)"
  "3|approved|Process transaction → APPROVED"
  "4|rule1|Fraud Rule 1: amount without approval → DECLINED"
  "5|rule2|Fraud Rule 2: velocity under concurrency (2 + 2 parallel)"
  "6|rule3|Fraud Rule 3: high-risk category → DECLINED"
  "7|rule4|Fraud Rule 4: new user high amount → FLAGGED"
  "8|idem-replay|Idempotency: safe replay (same key + body)"
  "9|idem-conflict|Idempotency: conflict (same key, different body) → 409"
  "10|get-txn|Get transaction by ID"
  "11|list-txn|List / bulk export with cursor pagination"
  "12|audit|Audit logging (Postgres outbox → MongoDB)"
  "13|rate-limit|Rate limiting → HTTP 429"
  "14|metrics|Observability: Prometheus metrics"
  "15|errors|Error paths (validation, unknown user, not found)"
  "16|redis|Redis user cache (optional)"
  "17|webhooks|Signed webhooks (optional)"
)

feature_count() { echo "${#FEATURES[@]}"; }

feature_field() {
  # feature_field INDEX field  → field in {id,name,title}
  local idx="$1" field="$2"
  local row="${FEATURES[$idx]}"
  case "${field}" in
    id)    echo "${row%%|*}" ;;
    name)  echo "${row}" | cut -d'|' -f2 ;;
    title) echo "${row}" | cut -d'|' -f3- ;;
  esac
}

print_menu() {
  echo
  echo "${C_BOLD}Features${C_RESET}  ${C_DIM}(type number, name, or next)${C_RESET}"
  hr
  local i id name title
  for i in $(seq 0 $(( ${#FEATURES[@]} - 1 ))); do
    id=$(feature_field "$i" id)
    name=$(feature_field "$i" name)
    title=$(feature_field "$i" title)
    printf "  ${C_BOLD}%2s${C_RESET}:${C_CYAN}%-14s${C_RESET} %s\n" "${id}" "${name}" "${title}"
  done
  hr
  echo "  ${C_DIM}Commands: next | list | status | prep | quit${C_RESET}"
  echo
}

print_status() {
  echo
  echo "${C_BOLD}Session state${C_RESET}"
  echo "  userId:        ${DEMO_USER_ID:-<none — will be created on demand>}"
  echo "  email:         ${DEMO_USER_EMAIL:-<none>}"
  echo "  lastTxnId:     ${LAST_TXN_ID:-<none>}"
  echo "  lastIdemKey:   ${LAST_IDEMPOTENCY_KEY:-<none>}"
  echo "  next index:    $(( CURSOR_INDEX + 1 )) / $(feature_count)  ($(feature_field "${CURSOR_INDEX}" name 2>/dev/null || echo done))"
  echo
}

print_prep() {
  echo
  echo "${C_BOLD}Optional feature preparation${C_RESET}"
  hr
  echo "${C_BOLD}16 — Redis user cache${C_RESET}"
  cat <<'EOF'
  1. In .env set:  PAYMENT_CACHE_USER_ENABLED=true
  2. Restart with Redis profile:
       docker compose --profile redis up --build -d
  3. Confirm Redis:  docker compose ps redis
  4. Re-run this demo and choose 16.
EOF
  echo
  echo "${C_BOLD}17 — Signed webhooks${C_RESET}"
  cat <<'EOF'
  1. Start a host listener on :9999 (separate terminal):

     python3 - <<'PY'
     from http.server import BaseHTTPRequestHandler, HTTPServer
     class H(BaseHTTPRequestHandler):
         def do_POST(self):
             n = int(self.headers.get("Content-Length", 0))
             body = self.rfile.read(n)
             print("X-Signature:", self.headers.get("X-Signature"))
             print("X-Request-Id:", self.headers.get("X-Request-Id"))
             print(body.decode())
             self.send_response(200); self.end_headers(); self.wfile.write(b"{}")
         def log_message(self, *a): pass
     HTTPServer(("0.0.0.0", 9999), H).serve_forever()
     PY

  2. In .env set:  PAYMENT_WEBHOOKS_ENABLED=true
  3. Restart:  docker compose up --build -d
  4. Optional reachability:
       docker compose exec app wget -S -qO- --timeout=3 \
         http://host.docker.internal:9999/hooks || echo FAIL </dev/null
  5. Re-run this demo and choose 17. Watch the Python terminal for delivery.
EOF
  echo
  echo "${C_BOLD}Core stack (features 1–15)${C_RESET}"
  cat <<'EOF'
  docker compose up --build -d
  # readiness must be UP — no Redis/webhooks required
EOF
  hr
}

resolve_feature() {
  # stdin/arg → sets RESOLVED_INDEX (0-based) or returns 1
  local input="$1"
  input=$(echo "${input}" | tr '[:upper:]' '[:lower:]' | sed 's/^0*//; s/^$/0/')
  local i id name
  for i in $(seq 0 $(( ${#FEATURES[@]} - 1 ))); do
    id=$(feature_field "$i" id)
    name=$(feature_field "$i" name)
    if [[ "${input}" == "${id}" || "${input}" == "${name}" ]]; then
      RESOLVED_INDEX=$i
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------------
# Feature implementations
# ---------------------------------------------------------------------------

run_health() {
  section_title "Health & readiness"
  will_do "Call readiness (requires Postgres) and show aggregated health. Mongo/Redis are not required for readiness — resilience design."
  expect_result "Readiness status=UP; aggregated health is returned without requiring Mongo or Redis."
  confirm_or_abort || return 0

  show_request <<EOF
GET ${BASE_URL}/actuator/health/readiness
GET ${BASE_URL}/actuator/health
EOF

  local ready agg
  http GET "${BASE_URL}/actuator/health/readiness"
  ready="$HTTP_BODY"
  echo
  echo "${C_BOLD}Readiness (HTTP ${HTTP_CODE})${C_RESET}"
  show_response "${ready}"
  local ready_status
  ready_status=$(echo "${ready}" | jq -r '.status // empty')

  http GET "${BASE_URL}/actuator/health"
  agg="$HTTP_BODY"
  echo
  echo "${C_BOLD}Aggregated health (HTTP ${HTTP_CODE})${C_RESET}"
  show_response "${agg}"

  if [[ "${ready_status}" == "UP" ]]; then
    pass "Readiness is UP — app can accept traffic (Postgres reachable)."
  else
    fail "Expected readiness status=UP, got '${ready_status}'"
  fi
}

run_users() {
  section_title "User management"
  will_do "Create a user, GET by id, list with cursor, then PATCH KYC + pre-approved limit. Maps to challenge: User Management."
  expect_result "Create 201 → get/list 200 → update 200 with kycStatus=VERIFIED and preApprovedTransactionLimit=15000."
  confirm_or_abort || return 0

  show_request <<EOF
POST /api/v1/users  {"email":"…","kycStatus":"PENDING"}
GET  /api/v1/users/{id}
GET  /api/v1/users?limit=3
PATCH /api/v1/users/{id}  {"kycStatus":"VERIFIED","preApprovedTransactionLimit":15000}
EOF

  local created got listed patched
  create_fresh_user "users"
  created="$HTTP_BODY"
  echo
  echo "${C_BOLD}1) Create — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${created}"
  [[ "${HTTP_CODE}" == "201" ]] || { fail "Create expected 201"; return 1; }

  http GET "${BASE_URL}/api/v1/users/${DEMO_USER_ID}" -H "X-Request-Id: demo-get-user"
  got="$HTTP_BODY"
  echo
  echo "${C_BOLD}2) Get — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${got}"
  [[ "${HTTP_CODE}" == "200" ]] || { fail "Get expected 200"; return 1; }

  http GET "${BASE_URL}/api/v1/users?limit=3"
  listed="$HTTP_BODY"
  echo
  echo "${C_BOLD}3) List (limit=3) — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${listed}"
  [[ "${HTTP_CODE}" == "200" ]] || { fail "List expected 200"; return 1; }

  http PATCH "${BASE_URL}/api/v1/users/${DEMO_USER_ID}" \
    -H 'Content-Type: application/json' \
    -d '{"kycStatus":"VERIFIED","preApprovedTransactionLimit":15000}'
  patched="$HTTP_BODY"
  echo
  echo "${C_BOLD}4) Update — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${patched}"
  local kyc limit
  kyc=$(echo "${patched}" | jq -r '.data.kycStatus')
  limit=$(echo "${patched}" | jq -r '.data.preApprovedTransactionLimit')
  if [[ "${HTTP_CODE}" == "200" && "${kyc}" == "VERIFIED" && "${limit}" == "15000" ]]; then
    pass "User CRUD works (create → get → list → update). Session userId=${DEMO_USER_ID}"
  else
    fail "Update did not apply KYC/limit as expected"
  fi
}

run_approved() {
  section_title "Process transaction → APPROVED"
  will_do "Authorize a small groceries payment for the session user."
  expect_result "HTTP 201 with status=APPROVED and empty rulesTriggered."
  confirm_or_abort || return 0

  ensure_user
  show_request <<EOF
POST /api/v1/transactions
Body: {"amount":100.00,"userId":"${DEMO_USER_ID}","merchantId":"mch_demo","category":"GROCERIES"}
EOF

  local body status rules
  post_txn "100.00" "GROCERIES"
  body="$HTTP_BODY"
  show_response "${body}"
  status=$(echo "${body}" | jq -r '.data.status // empty')
  rules=$(echo "${body}" | jq -c '.data.rulesTriggered // []')
  LAST_TXN_ID=$(echo "${body}" | jq -r '.data.transactionId // empty')

  if [[ "${HTTP_CODE}" == "201" && "${status}" == "APPROVED" ]]; then
    pass "Transaction APPROVED (txn=${LAST_TXN_ID}, rules=${rules})."
  else
    fail "Expected HTTP 201 APPROVED, got HTTP ${HTTP_CODE} status=${status}"
  fi
}

run_rule1() {
  section_title "Fraud Rule 1 — AMOUNT_WITHOUT_APPROVAL"
  will_do "Post amount > 10,000 for a user with no pre-approved limit. Challenge Rule 1."
  expect_result "HTTP 201 with status=DECLINED and rulesTriggered including AMOUNT_WITHOUT_APPROVAL."
  confirm_or_abort || return 0

  # Fresh user: preApprovedTransactionLimit is null (API rejects PATCH 0 / clearing to zero).
  create_fresh_user "rule1"
  info "userId=${DEMO_USER_ID}  preApprovedTransactionLimit=null"

  show_request <<EOF
POST /api/v1/transactions
Body: {"amount":12000.00,"userId":"${DEMO_USER_ID}","merchantId":"mch_demo","category":"GROCERIES"}
EOF

  local body status rules
  post_txn "12000.00" "GROCERIES"
  body="$HTTP_BODY"
  show_response "${body}"
  status=$(echo "${body}" | jq -r '.data.status // empty')
  rules=$(echo "${body}" | jq -c '.data.rulesTriggered // []')
  LAST_TXN_ID=$(echo "${body}" | jq -r '.data.transactionId // empty')

  if [[ "${HTTP_CODE}" == "201" && "${status}" == "DECLINED" ]] \
    && echo "${rules}" | jq -e 'index("AMOUNT_WITHOUT_APPROVAL") != null' >/dev/null; then
    pass "Rule 1 fired — DECLINED with AMOUNT_WITHOUT_APPROVAL (note: still HTTP 201; decision is in data)."
  else
    fail "Expected DECLINED + AMOUNT_WITHOUT_APPROVAL, got status=${status} rules=${rules}"
  fi
}

run_rule2() {
  section_title "Fraud Rule 2 — VELOCITY (concurrency)"
  will_do "Seed 2 APPROVED payments, then fire 2 concurrent POSTs for the same user. FOR UPDATE serializes them so the race cannot double-approve."
  expect_result "Exactly 1 concurrent APPROVED (#3) + 1 DECLINED with VELOCITY (#4); DB authorized count = 3."
  confirm_or_abort || return 0

  info "Using a fresh user so prior demo traffic does not skew the window."
  create_fresh_user "velocity"
  info "userId=${DEMO_USER_ID}"

  show_request <<EOF
POST ×2 sequential  amount=25.00  (seed — expect APPROVED)
POST ×2 concurrent  amount=30.00  (same userId — race under FOR UPDATE)
EOF

  local i body status
  for i in 1 2; do
    post_txn "25.00" "GROCERIES" "" "mch_seed_${i}"
    body="$HTTP_BODY"
    status=$(echo "${body}" | jq -r '.data.status // empty')
    echo
    echo "${C_BOLD}Seed ${i}/2 — HTTP ${HTTP_CODE} status=${status}${C_RESET}"
    echo "${body}" | jq '{status: .data.status, transactionId: .data.transactionId, rulesTriggered: .data.rulesTriggered}'
    if [[ "${status}" != "APPROVED" && "${status}" != "FLAGGED" ]]; then
      fail "Seed payment ${i} should be APPROVED/FLAGGED, got ${status} (rate-limit? wait a minute and retry)"
      return 1
    fi
  done

  echo
  echo "${C_BOLD}Firing 2 concurrent POSTs…${C_RESET}"
  local tmp1 tmp2 code1 code2
  tmp1=$(mktemp)
  tmp2=$(mktemp)
  code1=$(mktemp)
  code2=$(mktemp)

  (
    curl -sS -o "${tmp1}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/transactions" \
      -H 'Content-Type: application/json' \
      -H "X-Request-Id: demo-vel-race-a-$(date +%s%N | cut -c1-13)" \
      -d "{\"amount\":30.00,\"userId\":\"${DEMO_USER_ID}\",\"merchantId\":\"mch_race_a\",\"category\":\"GROCERIES\"}" \
      >"${code1}"
  ) &
  local pid_a=$!
  (
    curl -sS -o "${tmp2}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/transactions" \
      -H 'Content-Type: application/json' \
      -H "X-Request-Id: demo-vel-race-b-$(date +%s%N | cut -c1-13)" \
      -d "{\"amount\":30.00,\"userId\":\"${DEMO_USER_ID}\",\"merchantId\":\"mch_race_b\",\"category\":\"GROCERIES\"}" \
      >"${code2}"
  ) &
  local pid_b=$!
  wait "${pid_a}" "${pid_b}"

  local http_a http_b body_a body_b status_a status_b
  http_a=$(cat "${code1}")
  http_b=$(cat "${code2}")
  body_a=$(cat "${tmp1}")
  body_b=$(cat "${tmp2}")
  rm -f "${tmp1}" "${tmp2}" "${code1}" "${code2}"

  status_a=$(echo "${body_a}" | jq -r '.data.status // empty')
  status_b=$(echo "${body_b}" | jq -r '.data.status // empty')

  echo
  echo "${C_BOLD}Concurrent A — HTTP ${http_a}${C_RESET}"
  show_response "${body_a}"
  echo
  echo "${C_BOLD}Concurrent B — HTTP ${http_b}${C_RESET}"
  show_response "${body_b}"

  local success=0 declines=0

  count_concurrent_result() {
    local st="$1" bd="$2" hc="$3" label="$4"
    if [[ "${hc}" != "201" ]]; then
      fail "Concurrent ${label} expected HTTP 201, got ${hc}"
      return 1
    fi
    if [[ "${st}" == "APPROVED" || "${st}" == "FLAGGED" ]]; then
      success=$((success + 1))
      LAST_TXN_ID=$(echo "${bd}" | jq -r '.data.transactionId // empty')
    elif [[ "${st}" == "DECLINED" ]] \
      && echo "${bd}" | jq -e '.data.rulesTriggered | index("VELOCITY") != null' >/dev/null; then
      declines=$((declines + 1))
      LAST_TXN_ID=$(echo "${bd}" | jq -r '.data.transactionId // empty')
    fi
    return 0
  }

  count_concurrent_result "${status_a}" "${body_a}" "${http_a}" "A" || return 1
  count_concurrent_result "${status_b}" "${body_b}" "${http_b}" "B" || return 1

  local authorized declined_v
  authorized=$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT COUNT(*) FROM transactions
     WHERE user_id = '${DEMO_USER_ID}'::uuid AND status IN ('APPROVED','FLAGGED')" \
    </dev/null)
  declined_v=$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT COUNT(*) FROM transactions
     WHERE user_id = '${DEMO_USER_ID}'::uuid
       AND status = 'DECLINED'
       AND 'VELOCITY' = ANY(rules_triggered)" \
    </dev/null)

  info "DB: authorized(APPROVED|FLAGGED)=${authorized}  declined(VELOCITY)=${declined_v}"

  if [[ "${success}" -eq 1 && "${declines}" -eq 1 && "${authorized}" -eq 3 && "${declined_v}" -eq 1 ]]; then
    pass "Concurrency OK — FOR UPDATE serialized the race: 1 extra APPROVED (#3) + 1 VELOCITY DECLINED (#4); authorized total=3."
  else
    fail "Expected 1 success + 1 VELOCITY decline (authorized=3); got success=${success} declines=${declines} authorized=${authorized} declined_v=${declined_v}"
  fi
}

run_rule3() {
  section_title "Fraud Rule 3 — HIGH_RISK_CATEGORY"
  will_do "High-risk category (CRYPTO/CASH_ADVANCE) AND amount > 5,000 → DECLINED. Challenge Rule 3."
  expect_result "HTTP 201 with status=DECLINED and rulesTriggered including HIGH_RISK_CATEGORY."
  confirm_or_abort || return 0

  ensure_user
  show_request <<EOF
POST /api/v1/transactions
Body: {"amount":5500.00,"userId":"${DEMO_USER_ID}","merchantId":"mch_demo","category":"CRYPTO"}
EOF

  local body status rules
  post_txn "5500.00" "CRYPTO"
  body="$HTTP_BODY"
  show_response "${body}"
  status=$(echo "${body}" | jq -r '.data.status // empty')
  rules=$(echo "${body}" | jq -c '.data.rulesTriggered // []')
  LAST_TXN_ID=$(echo "${body}" | jq -r '.data.transactionId // empty')

  if [[ "${HTTP_CODE}" == "201" && "${status}" == "DECLINED" ]] \
    && echo "${rules}" | jq -e 'index("HIGH_RISK_CATEGORY") != null' >/dev/null; then
    pass "Rule 3 fired — DECLINED with HIGH_RISK_CATEGORY."
  else
    fail "Expected DECLINED + HIGH_RISK_CATEGORY, got status=${status} rules=${rules}"
  fi
}

run_rule4() {
  section_title "Fraud Rule 4 — NEW_USER_HIGH_AMOUNT (FLAG)"
  will_do "Raise pre-approved limit so Rule 1 does not decline, then amount > 5,000 on a user younger than 30 days → FLAGGED (allowed). Challenge Rule 4."
  expect_result "HTTP 201 with status=FLAGGED and rulesTriggered including NEW_USER_HIGH_AMOUNT."
  confirm_or_abort || return 0

  create_fresh_user "flag"
  info "Fresh user ${DEMO_USER_ID} — created just now (< 30 days)."
  patch_user_limit 15000
  info "Set preApprovedTransactionLimit=15000 so Rule 1 does not DECLINE."

  show_request <<EOF
PATCH /api/v1/users/${DEMO_USER_ID}  {"preApprovedTransactionLimit":15000}
POST  /api/v1/transactions
Body: {"amount":12000.00,"userId":"${DEMO_USER_ID}","merchantId":"mch_demo","category":"GROCERIES"}
EOF

  local body status rules
  post_txn "12000.00" "GROCERIES"
  body="$HTTP_BODY"
  show_response "${body}"
  status=$(echo "${body}" | jq -r '.data.status // empty')
  rules=$(echo "${body}" | jq -c '.data.rulesTriggered // []')
  LAST_TXN_ID=$(echo "${body}" | jq -r '.data.transactionId // empty')

  if [[ "${HTTP_CODE}" == "201" && "${status}" == "FLAGGED" ]] \
    && echo "${rules}" | jq -e 'index("NEW_USER_HIGH_AMOUNT") != null' >/dev/null; then
    pass "Rule 4 fired — FLAGGED (payment allowed) with NEW_USER_HIGH_AMOUNT."
  else
    fail "Expected FLAGGED + NEW_USER_HIGH_AMOUNT, got status=${status} rules=${rules}"
  fi
}

run_idem_replay() {
  section_title "Idempotency — safe replay"
  will_do "Same Idempotency-Key + same body returns the same transactionId (no duplicate charge). Optional enhancement."
  expect_result "Both calls HTTP 201 with the identical transactionId."
  confirm_or_abort || return 0

  ensure_user
  LAST_IDEMPOTENCY_KEY="demo-idem-$(date +%s)"
  show_request <<EOF
POST /api/v1/transactions
Header: Idempotency-Key: ${LAST_IDEMPOTENCY_KEY}
Body: {"amount":50.00,"userId":"${DEMO_USER_ID}","merchantId":"mch_demo","category":"GROCERIES"}
…then repeat identical request
EOF

  local first second id1 id2
  post_txn "50.00" "GROCERIES" "${LAST_IDEMPOTENCY_KEY}"
  first="$HTTP_BODY"
  echo
  echo "${C_BOLD}First call — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${first}"
  id1=$(echo "${first}" | jq -r '.data.transactionId // empty')

  post_txn "50.00" "GROCERIES" "${LAST_IDEMPOTENCY_KEY}"

  second="$HTTP_BODY"
  echo
  echo "${C_BOLD}Replay — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${second}"
  id2=$(echo "${second}" | jq -r '.data.transactionId // empty')
  LAST_TXN_ID="${id2}"

  if [[ -n "${id1}" && "${id1}" == "${id2}" ]]; then
    pass "Replay returned the same transactionId=${id1} (idempotent)."
  else
    fail "Replay mismatch: first=${id1} second=${id2}"
  fi
}

run_idem_conflict() {
  section_title "Idempotency — conflict → 409"
  will_do "Same Idempotency-Key with a different body fingerprint → HTTP 409 IDEMPOTENCY_CONFLICT."
  expect_result "First call 201; second call HTTP 409 with errors[0].code=IDEMPOTENCY_CONFLICT."
  confirm_or_abort || return 0

  ensure_user
  local key="demo-conflict-$(date +%s)"
  show_request <<EOF
POST key=${key} amount=50.00   (succeeds)
POST key=${key} amount=99.00   (expect 409 IDEMPOTENCY_CONFLICT)
EOF

  local first second code
  post_txn "50.00" "GROCERIES" "${key}"
  first="$HTTP_BODY"
  echo
  echo "${C_BOLD}First — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${first}"
  LAST_TXN_ID=$(echo "${first}" | jq -r '.data.transactionId // empty')

  post_txn "99.00" "GROCERIES" "${key}"

  second="$HTTP_BODY"
  echo
  echo "${C_BOLD}Conflict attempt — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${second}"
  code=$(echo "${second}" | jq -r '.errors[0].code // empty')

  if [[ "${HTTP_CODE}" == "409" && "${code}" == "IDEMPOTENCY_CONFLICT" ]]; then
    pass "Conflict correctly rejected with 409 IDEMPOTENCY_CONFLICT."
  else
    fail "Expected 409 IDEMPOTENCY_CONFLICT, got HTTP ${HTTP_CODE} code=${code}"
  fi
}

run_get_txn() {
  section_title "Get transaction by ID"
  will_do "Fetch a known transaction; then show 404 for a random UUID."
  expect_result "Known id → HTTP 200; unknown UUID → HTTP 404 NOT_FOUND."
  confirm_or_abort || return 0

  ensure_user
  if [[ -z "${LAST_TXN_ID}" ]]; then
    info "No prior txn in session — creating one…"
    local body
    post_txn "25.00" "TRAVEL"
    body="$HTTP_BODY"
    LAST_TXN_ID=$(echo "${body}" | jq -r '.data.transactionId')
  fi

  show_request <<EOF
GET /api/v1/transactions/${LAST_TXN_ID}
GET /api/v1/transactions/00000000-0000-0000-0000-000000000000  (expect 404)
EOF

  local got miss code
  http GET "${BASE_URL}/api/v1/transactions/${LAST_TXN_ID}"
  got="$HTTP_BODY"
  echo
  echo "${C_BOLD}Found — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${got}"
  local ok_found=0
  [[ "${HTTP_CODE}" == "200" ]] && ok_found=1

  http GET "${BASE_URL}/api/v1/transactions/00000000-0000-0000-0000-000000000000"
  miss="$HTTP_BODY"
  echo
  echo "${C_BOLD}Missing — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${miss}"
  code=$(echo "${miss}" | jq -r '.errors[0].code // empty')

  if [[ "${ok_found}" -eq 1 && "${HTTP_CODE}" == "404" && "${code}" == "NOT_FOUND" ]]; then
    pass "GET by id works; unknown id returns 404 NOT_FOUND."
  else
    fail "Unexpected get/not-found behavior"
  fi
}

run_list_txn() {
  section_title "List / bulk export (cursor pagination)"
  will_do "Create several transactions for a fresh user, then page with limit=2 following nextCursor until hasMore=false. Optional bulk-export enhancement."
  expect_result "5 transactions drained across 3 pages (limit=2); hasMore becomes false; no duplicate ids."
  confirm_or_abort || return 0

  create_fresh_user "list"
  info "userId=${DEMO_USER_ID}"

  local n=5 i body
  echo
  echo "${C_BOLD}Creating ${n} transactions…${C_RESET}"
  for i in $(seq 1 "${n}"); do
    post_txn "$((10 + i)).00" "GROCERIES"
    body="$HTTP_BODY"
    echo "  [${i}/${n}] $(echo "${body}" | jq -r '.data | "\(.transactionId) \(.status)"')"
  done

  show_request <<EOF
GET /api/v1/transactions?userId=${DEMO_USER_ID}&limit=2
…follow data.nextCursor while hasMore=true
EOF

  local cursor="" page=0 seen=0 has_more next count page_json
  while true; do
    page=$((page + 1))
    local url="${BASE_URL}/api/v1/transactions?userId=${DEMO_USER_ID}&limit=2"
    if [[ -n "${cursor}" ]]; then
      local enc
      enc=$(jq -rn --arg c "${cursor}" '$c|@uri')
      url="${url}&cursor=${enc}"
    fi
    http GET "${url}"
    page_json="$HTTP_BODY"
    has_more=$(echo "${page_json}" | jq -r '.data.hasMore')
    next=$(echo "${page_json}" | jq -r '.data.nextCursor // empty')
    count=$(echo "${page_json}" | jq '.data.items | length')
    seen=$((seen + count))
    echo
    echo "${C_BOLD}Page ${page} — HTTP ${HTTP_CODE} items=${count} hasMore=${has_more}${C_RESET}"
    echo "${page_json}" | jq '{items: [.data.items[] | {transactionId, status, amount}], nextCursor: .data.nextCursor, hasMore: .data.hasMore}'
    if [[ "${has_more}" == "true" ]]; then
      cursor="${next}"
    else
      break
    fi
  done

  if [[ "${seen}" -eq "${n}" && "${page}" -eq 3 ]]; then
    pass "Pagination drained ${seen} rows across ${page} pages (limit=2)."
  else
    fail "Expected ${n} rows / 3 pages, got seen=${seen} pages=${page}"
  fi
}

run_audit() {
  section_title "Audit logging (outbox → MongoDB)"
  will_do "Authorize a payment, wait for audit_outbox PUBLISHED, then show the Mongo audit_logs document. Challenge: durable audit that survives restarts."
  expect_result "Payment 201; outbox destination=AUDIT becomes PUBLISHED; Mongo audit_logs has a doc for that transactionId."
  confirm_or_abort || return 0

  ensure_user
  local body txn_id
  post_txn "42.00" "ELECTRONICS"
  body="$HTTP_BODY"
  txn_id=$(echo "${body}" | jq -r '.data.transactionId // empty')
  LAST_TXN_ID="${txn_id}"

  show_request <<EOF
POST /api/v1/transactions  (creates PG transaction + audit_outbox in one commit)
Then poll:  SELECT status FROM audit_outbox WHERE transaction_id = '${txn_id}'
Then Mongo: db.audit_logs.find({_id: '${txn_id}'})
EOF

  echo
  echo "${C_BOLD}Authorize response — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${body}"

  echo
  echo "${C_BOLD}Waiting for outbox → Mongo…${C_RESET}"
  local status="" i
  for i in $(seq 1 30); do
    status=$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
      "SELECT status FROM audit_outbox WHERE transaction_id = '${txn_id}'::uuid AND destination = 'AUDIT'" \
      </dev/null 2>/dev/null || true)
    info "attempt ${i}: outbox status=${status:-<none>}"
    [[ "${status}" == "PUBLISHED" ]] && break
    sleep 1
  done

  echo
  echo "${C_BOLD}Postgres audit_outbox (recent)${C_RESET}"
  docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c \
    "SELECT transaction_id, destination, status, attempts FROM audit_outbox ORDER BY id DESC LIMIT 5;" \
    </dev/null

  echo
  echo "${C_BOLD}Mongo ${MONGO_DB}.audit_logs${C_RESET}"
  local mongo_doc
  mongo_doc=$(docker compose exec -T mongo mongosh "${MONGO_DB}" --quiet --eval \
    "JSON.stringify(db.audit_logs.findOne({_id: '${txn_id}'}))" \
    </dev/null 2>/dev/null || echo null)
  show_response "${mongo_doc}"

  if [[ "${status}" == "PUBLISHED" && "${mongo_doc}" != "null" && -n "${mongo_doc}" ]]; then
    pass "Outbox PUBLISHED and Mongo audit doc exists for transactionId=${txn_id}."
  else
    fail "Expected PUBLISHED + Mongo doc; outbox=${status} mongo=${mongo_doc}"
  fi
}

run_rate_limit() {
  section_title "Rate limiting → HTTP 429"
  will_do "Burst POST /transactions for one user until RATE_LIMIT_EXCEEDED. Rejected calls must not write txn/outbox rows. Default user capacity ≈ 60/min."
  expect_result "HTTP 429 with RATE_LIMIT_EXCEEDED; DB transaction count equals only the successful 201 responses."
  prep_box <<'EOF'
This step sends many requests (~60+) and may take ~30–60s.
If you never hit 429, rebuild: docker compose up --build -d
EOF
  confirm_or_abort || return 0

  create_fresh_user "ratelimit"
  info "userId=${DEMO_USER_ID}"

  show_request <<EOF
POST /api/v1/transactions  (loop until HTTP 429)
EOF

  local max=90 i tmp code body_429="" retry_after="" hit=0 ok=0
  for i in $(seq 1 "${max}"); do
    tmp=$(mktemp)
    code=$(curl -sS -o "${tmp}" -w "%{http_code}" -D "${tmp}.hdr" -X POST "${BASE_URL}/api/v1/transactions" \
      -H 'Content-Type: application/json' \
      -d "{\"amount\":10.00,\"userId\":\"${DEMO_USER_ID}\",\"merchantId\":\"mch_burst\",\"category\":\"GROCERIES\"}")
    if [[ "${code}" == "201" ]]; then
      ok=$((ok + 1))
      if (( i % 10 == 0 )); then
        info "… ${i} requests (${ok}×201)"
      fi
      rm -f "${tmp}" "${tmp}.hdr"
      continue
    fi
    if [[ "${code}" == "429" ]]; then
      hit=1
      body_429=$(cat "${tmp}")
      retry_after=$(awk 'BEGIN{IGNORECASE=1} /^Retry-After:/ {print $2}' "${tmp}.hdr" | tr -d '\r')
      rm -f "${tmp}" "${tmp}.hdr"
      echo
      echo "${C_BOLD}Hit 429 on attempt ${i}${C_RESET}"
      show_response "${body_429}"
      info "Retry-After=${retry_after:-<missing>}"
      break
    fi
    echo "Unexpected HTTP ${code}:" >&2
    cat "${tmp}" >&2
    rm -f "${tmp}" "${tmp}.hdr"
    fail "Unexpected status during burst"
    return 1
  done

  if [[ "${hit}" -ne 1 ]]; then
    fail "No 429 within ${max} attempts — rebuild app image?"
    return 1
  fi

  local err
  err=$(echo "${body_429}" | jq -r '.errors[0].code // empty')
  local txn_count
  txn_count=$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT COUNT(*) FROM transactions WHERE user_id = '${DEMO_USER_ID}'::uuid" \
    </dev/null)

  if [[ "${err}" == "RATE_LIMIT_EXCEEDED" && "${txn_count}" == "${ok}" ]]; then
    pass "429 RATE_LIMIT_EXCEEDED; DB has exactly ${txn_count} rows (= successful 201s). No ghost writes."
  else
    fail "code=${err} txn_count=${txn_count} expected_ok=${ok}"
  fi
}

run_metrics() {
  section_title "Observability — Prometheus metrics"
  will_do "Scrape /actuator/prometheus for payment and outbox series (challenge: observability)."
  expect_result "Scrape includes payment_transactions_total and/or outbox_pending series."
  confirm_or_abort || return 0

  show_request <<EOF
GET ${BASE_URL}/actuator/prometheus
Filter: payment_transactions_total | outbox_pending
EOF

  local scrape sample
  http GET "${BASE_URL}/actuator/prometheus"
  scrape="$HTTP_BODY"
  sample=$(echo "${scrape}" | grep -E 'payment_transactions_total|outbox_pending' | head -20 || true)

  echo
  echo "${C_BOLD}Sample metrics (HTTP ${HTTP_CODE})${C_RESET}"
  if [[ -n "${sample}" ]]; then
    echo "${sample}"
    pass "Prometheus endpoint exposes payment/outbox metrics."
  else
    fail "Expected payment_transactions_total / outbox_pending in scrape"
  fi
}

run_errors() {
  section_title "Error paths"
  will_do "Show clean API errors: validation 400, unknown user 404, unknown path 404."
  expect_result "400 VALIDATION_ERROR; 404 USER_NOT_FOUND; 404 for unknown path — all with ApiResponse error envelope."
  confirm_or_abort || return 0

  show_request <<EOF
POST /api/v1/transactions  {"amount":-1,...}           → 400 VALIDATION_ERROR
POST /api/v1/transactions  userId=random-uuid          → 404 USER_NOT_FOUND
GET  /api/v1/does-not-exist                            → 404
EOF

  local bad unknown path code1 code2 code3
  # Fresh user so a prior rate-limit burst on the session user does not turn this into 429.
  create_fresh_user "errors"
  info "userId=${DEMO_USER_ID}"

  http POST "${BASE_URL}/api/v1/transactions" \
    -H 'Content-Type: application/json' \
    -d "{\"amount\":-1,\"userId\":\"${DEMO_USER_ID}\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}"
  bad="$HTTP_BODY"
  echo
  echo "${C_BOLD}1) Validation — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${bad}"
  code1=$(echo "${bad}" | jq -r '.errors[0].code // empty')
  local ok1=0
  [[ "${HTTP_CODE}" == "400" && "${code1}" == "VALIDATION_ERROR" ]] && ok1=1

  http POST "${BASE_URL}/api/v1/transactions" \
    -H 'Content-Type: application/json' \
    -d '{"amount":10.00,"userId":"00000000-0000-0000-0000-000000000001","merchantId":"mch_demo","category":"GROCERIES"}'
  unknown="$HTTP_BODY"
  echo
  echo "${C_BOLD}2) Unknown user — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${unknown}"
  code2=$(echo "${unknown}" | jq -r '.errors[0].code // empty')
  local ok2=0
  [[ "${HTTP_CODE}" == "404" && "${code2}" == "USER_NOT_FOUND" ]] && ok2=1

  http GET "${BASE_URL}/api/v1/does-not-exist"
  path="$HTTP_BODY"
  echo
  echo "${C_BOLD}3) Unknown path — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${path}"
  code3=$(echo "${path}" | jq -r '.errors[0].code // empty')
  local ok3=0
  [[ "${HTTP_CODE}" == "404" ]] && ok3=1

  if [[ "${ok1}" -eq 1 && "${ok2}" -eq 1 && "${ok3}" -eq 1 ]]; then
    pass "Validation / USER_NOT_FOUND / unknown path all return proper error envelopes."
  else
    fail "One or more error cases mismatched (validation=${ok1} user=${ok2} path=${ok3})"
  fi
}

run_redis() {
  section_title "Redis user cache (optional)"
  will_do "GET /users/{id} uses optional read-through cache; PATCH invalidates. Authorize path never uses Redis."
  expect_result "Repeated GETs succeed; PATCH updates KYC; cache hit/miss visible in app logs (user.cache)."
  prep_box <<'EOF'
Required before this step:
  1. PAYMENT_CACHE_USER_ENABLED=true in .env
  2. docker compose --profile redis up --build -d
  3. Confirm: docker compose ps redis  (running)
If Redis is off, this step will detect it and stop cleanly.
EOF
  confirm_or_abort || return 0

  # Detect Redis / cache enabled
  if ! docker compose ps --status running redis 2>/dev/null | grep -q redis; then
    fail "Redis container not running. Run: docker compose --profile redis up --build -d"
    return 1
  fi

  # Fresh user — avoid reusing a session user whose rate-limit bucket was emptied
  # by step 13 (GET /users/{id} is keyed by path userId in RateLimitFilter).
  create_fresh_user "redis"
  info "userId=${DEMO_USER_ID}"

  show_request <<EOF
GET  /api/v1/users/${DEMO_USER_ID}   (may populate cache)
GET  /api/v1/users/${DEMO_USER_ID}   (expect cache hit in app logs: user.cache.hit)
PATCH /api/v1/users/${DEMO_USER_ID}  (evicts user:{id})
GET  /api/v1/users/${DEMO_USER_ID}   (miss → Postgres → re-cache)
EOF

  local g1 g2 patched g3
  http GET "${BASE_URL}/api/v1/users/${DEMO_USER_ID}"
  g1="$HTTP_BODY"
  echo
  echo "${C_BOLD}GET #1 — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${g1}"
  [[ "${HTTP_CODE}" == "200" ]] || { fail "GET #1 expected 200, got ${HTTP_CODE}"; return 1; }

  http GET "${BASE_URL}/api/v1/users/${DEMO_USER_ID}"
  g2="$HTTP_BODY"
  echo
  echo "${C_BOLD}GET #2 (should be cached) — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${g2}"
  [[ "${HTTP_CODE}" == "200" ]] || { fail "GET #2 expected 200, got ${HTTP_CODE}"; return 1; }

  http PATCH "${BASE_URL}/api/v1/users/${DEMO_USER_ID}" \
    -H 'Content-Type: application/json' \
    -d '{"kycStatus":"VERIFIED"}'
  patched="$HTTP_BODY"
  echo
  echo "${C_BOLD}PATCH (invalidate) — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${patched}"
  [[ "${HTTP_CODE}" == "200" ]] || { fail "PATCH expected 200, got ${HTTP_CODE}"; return 1; }

  http GET "${BASE_URL}/api/v1/users/${DEMO_USER_ID}"
  g3="$HTTP_BODY"
  echo
  echo "${C_BOLD}GET #3 after PATCH — HTTP ${HTTP_CODE}${C_RESET}"
  show_response "${g3}"

  echo
  info "Check app logs for cache hit/miss: docker compose logs app --since 2m | grep user.cache"
  local kyc
  kyc=$(echo "${g3}" | jq -r '.data.kycStatus // empty')
  if [[ "${HTTP_CODE}" == "200" && "${kyc}" == "VERIFIED" ]]; then
    pass "User GETs succeed with Redis up; PATCH applied (cache invalidated). Authorize still uses Postgres FOR UPDATE only."
  else
    fail "GET #3 expected 200 with kycStatus=VERIFIED, got HTTP ${HTTP_CODE} kyc=${kyc}"
  fi
}

run_webhooks() {
  section_title "Signed webhooks (optional)"
  will_do "When enabled, each payment also enqueues a WEBHOOK outbox row; publisher POSTs HMAC-signed JSON asynchronously. Authorize never waits on the subscriber."
  expect_result "Fast HTTP 201; WEBHOOK outbox row PUBLISHED (listener up) or PENDING (listener down) — payment still succeeds."
  prep_box <<'EOF'
Required before this step:
  1. Host listener on :9999 (see "prep" command for Python snippet)
  2. PAYMENT_WEBHOOKS_ENABLED=true in .env
  3. docker compose up --build -d
  4. Watch the Python terminal for X-Signature + body
EOF
  confirm_or_abort || return 0

  local enabled
  enabled=$(docker compose exec -T app printenv PAYMENT_WEBHOOKS_ENABLED </dev/null 2>/dev/null || echo "false")
  # Also try spring-style; compose usually injects the env var
  if [[ "${enabled}" != "true" ]]; then
    # soft check via a probe payment + outbox destination
    info "Env PAYMENT_WEBHOOKS_ENABLED=${enabled:-unset} — will verify via outbox rows after payment."
  fi

  create_fresh_user "webhook"
  info "userId=${DEMO_USER_ID}"

  show_request <<EOF
POST /api/v1/transactions  (expect fast 201)
Then: SELECT destination, status, attempts FROM audit_outbox ORDER BY id DESC LIMIT 5
EOF

  local body txn_id
  post_txn "75.00" "TRAVEL"
  body="$HTTP_BODY"
  txn_id=$(echo "${body}" | jq -r '.data.transactionId // empty')
  LAST_TXN_ID="${txn_id}"

  echo
  echo "${C_BOLD}Authorize — HTTP ${HTTP_CODE} (should be fast; does not wait on webhook HTTP)${C_RESET}"
  show_response "${body}"

  echo
  echo "${C_BOLD}Waiting briefly for outbox publisher…${C_RESET}"
  sleep 3
  docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c \
    "SELECT destination, status, attempts, left(coalesce(last_error,''), 60) AS err
     FROM audit_outbox WHERE transaction_id = '${txn_id}'::uuid ORDER BY destination;" \
    </dev/null

  local wh_status
  wh_status=$(docker compose exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT status FROM audit_outbox WHERE transaction_id = '${txn_id}'::uuid AND destination = 'WEBHOOK'" \
    </dev/null 2>/dev/null || true)

  if [[ -z "${wh_status}" ]]; then
    fail "No WEBHOOK outbox row — enable PAYMENT_WEBHOOKS_ENABLED=true and rebuild."
    return 1
  fi

  if [[ "${wh_status}" == "PUBLISHED" ]]; then
    pass "WEBHOOK outbox PUBLISHED for txn=${txn_id}. Check the host listener for X-Signature: sha256=…"
  elif [[ "${wh_status}" == "PENDING" ]]; then
    pass "WEBHOOK row exists (PENDING) — payment still 201. Start/fix listener; publisher will retry. Resilience demo."
  else
    fail "Unexpected WEBHOOK status=${wh_status}"
  fi
}

run_feature_by_index() {
  local idx="$1"
  local id name
  id=$(feature_field "$idx" id)
  name=$(feature_field "$idx" name)
  case "${name}" in
    health)        run_health ;;
    users)         run_users ;;
    approved)      run_approved ;;
    rule1)         run_rule1 ;;
    rule2)         run_rule2 ;;
    rule3)         run_rule3 ;;
    rule4)         run_rule4 ;;
    idem-replay)   run_idem_replay ;;
    idem-conflict) run_idem_conflict ;;
    get-txn)       run_get_txn ;;
    list-txn)      run_list_txn ;;
    audit)         run_audit ;;
    rate-limit)    run_rate_limit ;;
    metrics)       run_metrics ;;
    errors)        run_errors ;;
    redis)         run_redis ;;
    webhooks)      run_webhooks ;;
    *)             fail "Unknown feature ${name}"; return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

main() {
  need curl
  need jq
  need docker

  banner
  wait_ready
  print_menu

  echo "${C_DIM}Tip: start with 1→7 for the challenge core, then optional 8–17.${C_RESET}"
  echo "${C_DIM}Type prep for Redis/webhook setup steps.${C_RESET}"
  echo

  while true; do
    local prompt_next
    if (( CURSOR_INDEX < ${#FEATURES[@]} )); then
      prompt_next=$(feature_field "${CURSOR_INDEX}" name)
    else
      prompt_next="(done)"
    fi
    local input
    echo
    read -r -p "${C_BOLD}demo>${C_RESET} ${C_DIM}[next=${prompt_next}]${C_RESET} " input || { echo; break; }
    input=$(echo "${input}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    [[ -z "${input}" ]] && input="next"

    case "$(echo "${input}" | tr '[:upper:]' '[:lower:]')" in
      q|quit|exit)
        echo "Bye."
        break
        ;;
      list|menu|help|h|\?)
        print_menu
        ;;
      status)
        print_status
        ;;
      prep)
        print_prep
        ;;
      next)
        if (( CURSOR_INDEX >= ${#FEATURES[@]} )); then
          echo "All features have been walked once. Pick a number to re-run, or quit."
          continue
        fi
        local idx="${CURSOR_INDEX}"
        CURSOR_INDEX=$(( CURSOR_INDEX + 1 ))
        set +e
        run_feature_by_index "${idx}"
        local rc=$?
        set -e
        if [[ "${rc}" -ne 0 ]]; then
          echo "${C_DIM}(You can re-run: $(feature_field "${idx}" id) or $(feature_field "${idx}" name))${C_RESET}"
        fi
        ;;
      *)
        if resolve_feature "${input}"; then
          # Jumping to a feature also advances "next" past it
          if (( RESOLVED_INDEX >= CURSOR_INDEX )); then
            CURSOR_INDEX=$(( RESOLVED_INDEX + 1 ))
          fi
          set +e
          run_feature_by_index "${RESOLVED_INDEX}"
          local rc=$?
          set -e
          if [[ "${rc}" -ne 0 ]]; then
            echo "${C_DIM}(Re-run with: $(feature_field "${RESOLVED_INDEX}" id))${C_RESET}"
          fi
        else
          echo "Unknown: '${input}'. Type list, next, prep, status, or quit."
        fi
        ;;
    esac
  done
}

main "$@"
